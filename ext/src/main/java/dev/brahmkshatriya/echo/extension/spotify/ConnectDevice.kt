package dev.brahmkshatriya.echo.extension.spotify

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Spotify Connect-device presence, mirroring the proven reference shapes
 * for the license path.
 *
 * Reference ground truth for every shape below (payloads, headers, order):
 * - state PUT format is the STRING "file_ids_mp4", never an int.
 * - state/batch headers are Cookie + Bearer only. No client-token,
 *   no spotify-app-version on the Connect path. UA is the Chrome/137
 *   desktop string (license/mint/CDN keep their own captured shapes).
 * - batch envelope is client_version 0.0.0 / chrome-148 platform /
 *   harmony:4.72.0, sent with Cookie + Bearer.
 * - jssdk_playback_start carries file_id "", the real client_id
 *   (65b708073fc0480ea92a077233ca87bd), real correlation_id,
 *   feature "web-player", session = startedAtMs string.
 * - track_stream_verification carries playback_service "web_player",
 *   session = startedAtMs string, sequence_id always 1.
 * - finalize sends BOTH verification + playback_stats events, then a
 *   track_data_finalized state WITH playback_stats (ms_latency 98).
 * - start order is play-command -> start-event batch -> before_track_load
 *   (pos 0) -> started_playing (pos min(1004, dur)).
 * - server is authoritative for refs: every state PUT response carries
 *   state_machine + updated_state_ref, adopted into the session.
 * - NO transfer call anywhere in the history flow; device id is a stable
 *   persisted value ("Echo Spotify"), re-registered every 45-min TTL.
 *
 * Best-effort only: every entry point never throws and never gates
 * playback. 429s defer via Retry-After (15s..10min clamp).
 */
object ConnectDevice {

    private const val APRESOLVE_URL = "https://apresolve.spotify.com/?type=dealer-g2&type=spclient"
    private const val BATCH_URL = "https://gew1-spclient.spotify.com/melody/v1/msg/batch"
    private const val DEVICE_TTL_MS = 45 * 60 * 1000L
    private const val SESSION_TTL_MS = 45 * 60 * 1000L
    private const val DEALER_TIMEOUT_MS = 10_000L
    /** Dealer-await cap on the resolve path (report blocks playback
     * resolve; the push normally lands in <1s after command=200). */
    private const val REPORT_DEALER_TIMEOUT_MS = 5_000L
    private const val SDK_ID = "harmony:4.72.0"
    private const val PLATFORM = "web_player windows 10;chrome 148.0.0.0;desktop"
    private const val MODEL = "harmony-4.72.0-web-player"
    private const val DEVICE_NAME = "Echo Spotify"
    private const val DEVICE_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private const val CONNECT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private const val BITRATE = 128_000
    private const val SETTING_KEY = "spotify_connect_device_id"
    private val CONNECTION_REGEX = Regex("""hm://pusher/(?:[^/]+/)?connections/([^/?#]+)""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val dealerClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private data class Device(
        val cookieKey: String,
        val deviceId: String,
        val webgateUrl: String,
        val webSocket: WebSocket,
        val connectionId: String,
        val commandQueue: DealerCommandQueue,
        var seq: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        fun nextSeq(): Int = ++seq
        fun close() = runCatching { webSocket.close(1000, "echo-connect-done") }
    }

    /** Dealer replace_state push queue (reference shape). The play command's
     * server-issued state (machine/state/playback ids) arrives here; state
     * PUTs with self-minted refs are 200ed but painted nowhere. */
    private class DealerCommandQueue {
        private val pending = ArrayDeque<JsonObject>()
        private var waiter: CompletableDeferred<JsonObject>? = null

        @Synchronized
        fun nextCommand(): CompletableDeferred<JsonObject> {
            if (pending.isNotEmpty()) return CompletableDeferred(pending.removeFirst())
            return CompletableDeferred<JsonObject>().also { waiter = it }
        }

        @Synchronized
        fun offer(command: JsonObject) {
            val w = waiter
            if (w != null) {
                waiter = null
                if (w.complete(command)) return
            }
            pending.addLast(command)
        }
    }

    private data class DealerState(
        val machine: String,
        val state: String,
        val playback: String?,
        val durationMs: Long?,
    )

    @Volatile
    private var device: Device? = null

    @Volatile
    private var blockedUntilMs: Long = 0L

    private val json = Json()

    /**
     * Per-track session, reference shape. Field names are kept stable for
     * existing callers (sessionIdsFor(...).playback for nextPlaybackId).
     * session = startedAtMs string (NOT random), machine = Ch + 28 url-safe
     * chars, state/playback/correlation = 16-byte hex.
     */
    data class SessionIds(
        val machine: String,
        val state: String,
        val playback: String,
        val session: String,
        val correlation: String,
    )

    private data class Session(
        val trackUri: String,
        var machine: String,
        var state: String,
        var playback: String,
        val sessionId: String,
        var correlation: String,
        val startedAtMs: Long,
        var durationMs: Long,
        var paused: Boolean = false,
        var startReported: Boolean = false,
        /** Normalized requested context (liked stays collection here). */
        var wantContext: String? = null,
        /** Effectively adopted context (dealer match; liked falls back). */
        var contextUri: String? = null,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        fun toIds() = SessionIds(machine, state, playback, sessionId, correlation)
    }

    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized
    private fun sessionFor(trackUri: String, durationMs: Long): Session {
        pruneSessions()
        sessions[trackUri]?.takeIf {
            System.currentTimeMillis() - it.createdAtMs < SESSION_TTL_MS
        }?.let {
            if (durationMs > 0L) it.durationMs = durationMs
            return it
        }
        val now = System.currentTimeMillis()
        val fresh = Session(
            trackUri = trackUri,
            machine = randomMachineId(),
            state = randomHex(16),
            playback = randomHex(16),
            sessionId = now.toString(),
            correlation = randomHex(16),
            startedAtMs = now,
            durationMs = durationMs.coerceAtLeast(0L),
        )
        sessions[trackUri] = fresh
        while (sessions.size > 200) {
            sessions.remove(sessions.keys.first())
        }
        return fresh
    }

    @Synchronized
    private fun pruneSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, s) -> now - s.createdAtMs > SESSION_TTL_MS }
    }

    /** Stable account key: the full cookie rotates, sp_dc doesn't. Keying
     * the device on the full cookie re-registers (new dealer WS + observer
     * + register burst) on every rotation — endpoint hammering, 429s, and
     * exactly the flapping death seen in the picker. */
    private fun accountKey(cookie: String): String =
        cookie.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?.let { "sp_dc=$it" }
            ?: "hash=${cookie.hashCode()}"

    @Synchronized
    private fun sessionOrNull(trackUri: String): Session? {
        pruneSessions()
        return sessions[trackUri]
    }

    /** True when this track has a live session (gates queue-rip fetches so
     * cold page-opens never hit the cluster endpoint). */
    @Synchronized
    fun hasSession(trackUri: String): Boolean {
        pruneSessions()
        return sessions.containsKey(trackUri)
    }

    @Synchronized
    private fun dropSession(trackUri: String) {
        sessions.remove(trackUri)
    }

    /** Drop every session: their machine/state refs died with the device
     * (server 200s-but-paints-nothing on orphaned refs), so the next tick
     * must full-start fresh instead of limping on until the next skip. */
    @Synchronized
    private fun dropAllSessions() {
        sessions.clear()
    }

    fun sessionIdsFor(fileId: String): SessionIds? {
        val uri = synchronized(trackByFile) { trackByFile[fileId] } ?: return null
        return sessionOrNull(uri)?.toIds()
    }

    /** fileId -> track uri, for matching cluster tracks to our file. */
    private val trackByFile = LinkedHashMap<String, String>()

    /** Reverse lookup: our fileId for a full track uri (tracker callbacks). */
    fun fileIdForUri(trackUri: String): String? = synchronized(trackByFile) {
        trackByFile.entries.firstOrNull { it.value == trackUri }?.key
    }

    fun note(fileId: String, trackUri: String) {
        if (fileId.isBlank() || trackUri.isBlank()) return
        synchronized(trackByFile) {
            trackByFile[fileId] = trackUri
            while (trackByFile.size > 200) {
                trackByFile.remove(trackByFile.keys.first())
            }
        }
    }

    private fun trackUriFor(fileId: String): String? = synchronized(trackByFile) {
        trackByFile[fileId]
    }

    /**
     * Full reference start for one track: play-command + dealer-await ->
     * start-event batch -> before_track_load (0) -> started_playing
     * (min(1004, dur)). Once per session TTL (startReported guard); repeats
     * return the cached outcome. Never throws.
     */
    suspend fun startTrack(
        setting: Settings,
        cookie: String?,
        bearer: String,
        trackUri: String?,
        durationMs: Long,
        contextUri: String? = null,
        force: Boolean = false,
    ): Int? = runCatching {
        if (System.currentTimeMillis() < blockedUntilMs) return@runCatching null
        if (cookie.isNullOrBlank()) return@runCatching null
        val uri = trackUri?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val dev = ensure(cookie, bearer, setting) ?: return@runCatching null
        if (force) dropSession(uri)
        val session = sessionFor(uri, durationMs)
        ensureStarted(cookie, bearer, dev, session, contextUri)
        dev.seq
    }.getOrNull()

    /** Run the start flow unless this session already reported it with the
     * same NORMALIZED context. Comparing raw ids misfires: a liked request
     * ("spotify:collection:tracks") never equals the adopted fallback
     * (track/station) and would re-anchor on every tick. A changed context
     * re-anchors refs with play + one PUT — no duplicate start batch. */
    private suspend fun ensureStarted(
        cookie: String, bearer: String, dev: Device, session: Session,
        requestedContext: String? = null,
    ): Boolean {
        val want = requestedContext?.toPlaybackContextUri()
        if (session.startReported &&
            (want == null || want == session.wantContext)
        ) return true
        if (session.startReported) {
            val dur = session.durationMs.coerceAtLeast(0L)
            if (playAndAdoptDealerState(cookie, bearer, dev, session, requestedContext)) {
                putState(cookie, bearer, dev, session, "started_playing",
                    minOf(1_004L, dur), 0L, dur, false)
            }
            return true
        }
        // Load the track into our device state BEFORE any state PUT, exactly
        // like the reference client (play command + skip_to, no transfer call). The
        // dealer answers with the server-issued state refs; PUTs without
        // them are 200ed but painted nowhere.
        playAndAdoptDealerState(cookie, bearer, dev, session, requestedContext)
        // Start-event batch first (Bearer + Cookie, reference shape).
        postBatch(
            cookie, bearer,
            listOf(buildStartEvent(session)),
            "start",
        )
        val dur = session.durationMs.coerceAtLeast(0L)
        putState(cookie, bearer, dev, session, "before_track_load", 0L, 0L, dur, false)
        val pos = minOf(1_004L, dur)
        putState(cookie, bearer, dev, session, "started_playing", pos, 0L, dur, false)
        session.startReported = true
        return true
    }

    /**
     * Resolve-path presence only. Runs at streamable resolve — including
     * Echo's next-track PREFETCHES — so it must never play-command, batch,
     * or PUT: doing so hijacks the device to a track that isn't playing
     * (random skips on the Spotify side). Only ensures the device exists
     * (dealer + registration = the license-path signs of life). The full
     * start runs when playback actually begins (tracker startTrack / ticks).
     * Never throws.
     */
    suspend fun report(
        setting: Settings,
        cookie: String?,
        bearer: String,
        clientToken: String?,
        formatNum: Int?,
        fileId: String?,
        durationMs: Long,
    ): Int? = runCatching {
        if (System.currentTimeMillis() < blockedUntilMs) return@runCatching null
        if (cookie.isNullOrBlank()) return@runCatching null
        ensure(cookie, bearer, setting) ?: return@runCatching null
        null
    }.getOrNull()

    /**
     * Live tick for the player's TrackerClient callbacks (pause/resume/seek/
     * threshold/finalize). One best-effort state PUT with REAL position and
     * playing state. Never throws, never gates playback.
     */
    suspend fun tick(
        setting: Settings,
        cookie: String?,
        bearer: String,
        clientToken: String?,
        trackUri: String?,
        fileId: String?,
        positionMs: Long,
        previousMs: Long,
        durationMs: Long,
        paused: Boolean,
        debugSource: String,
        withStats: Boolean = false,
        contextUri: String? = null,
    ): Int? = runCatching {
        if (System.currentTimeMillis() < blockedUntilMs) return@runCatching null
        if (cookie.isNullOrBlank()) return@runCatching null
        val uri = trackUri?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val dev = ensure(cookie, bearer, setting) ?: return@runCatching null
        // Reference order: threshold/finalize on a never-started session are
        // dropped (nothing to scrobble); live ticks ensure-start first so
        // pause/seek always ride adopted refs, never minted ones.
        var session = sessionOrNull(uri)
        if (session == null) {
            if (debugSource == "track_data_finalized" ||
                debugSource == "played_threshold_reached"
            ) return@runCatching null
            session = sessionFor(uri, durationMs)
        }
        if (!session.startReported) {
            ensureStarted(cookie, bearer, dev, session, contextUri)
        }
        val dur = session.durationMs.coerceAtLeast(0L)
        val pos = if (dur > 0L) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        val prev = if (dur > 0L) previousMs.coerceIn(0L, dur) else previousMs.coerceAtLeast(0L)
        putState(cookie, bearer, dev, session, debugSource, pos, prev, dur, paused, withStats)
    }.getOrNull()

    /**
     * Scrobble-grade verification (threshold/finalize): Verification sends BOTH
     * track_stream_verification + jssdk_playback_stats in one Bearer+Cookie
     * batch. Server keys off playback_id; session is the startedAtMs string,
     * service is "web_player", sequence is always 1.
     */
    suspend fun verify(
        cookie: String?,
        bearer: String,
        clientToken: String?,
        trackUri: String?,
        fileId: String?,
        msPlayed: Long,
        nextPlaybackId: String = "",
    ): Boolean? = runCatching {
        if (System.currentTimeMillis() < blockedUntilMs) return@runCatching null
        if (cookie.isNullOrBlank() || bearer.isBlank()) return@runCatching null
        val uri = trackUri?.takeIf { it.isNotBlank() } ?: return@runCatching null
        // Never invent a session for verify: no start means nothing to scrobble.
        val session = sessionOrNull(uri) ?: return@runCatching null
        if (!session.startReported) return@runCatching null
        val pos = if (session.durationMs > 0L) msPlayed.coerceIn(0L, session.durationMs)
        else msPlayed.coerceAtLeast(0L)
        val events = listOf(
            buildVerificationEvent(session, pos, nextPlaybackId),
            buildStatsEvent(session, pos, session.durationMs),
        )
        postBatch(cookie, bearer, events, "verify")
        true
    }.getOrNull()

    private suspend fun ensure(cookie: String, bearer: String, setting: Settings): Device? {
        val now = System.currentTimeMillis()
        val key = accountKey(cookie)
        device?.takeIf { it.cookieKey == key && now - it.createdAtMs < DEVICE_TTL_MS }
            ?.let { return it }
        device?.close()
        device = null
        return runCatching {
            // Stable persisted ID (NOT fresh per TTL): Spotify lists every
            // new deviceId as a separate entry, so rotating IDs spams the
            // picker with ghosts. Re-registering the same ID refreshes the
            // one "Echo Spotify" row instead.
            val deviceId = deviceId(setting)
            val (dealerUrl, webgateUrl) = endpoints()
            val (ws, connectionId, queue) = connectDealer(dealerUrl, bearer)
            registerObserver(cookie, bearer, webgateUrl, deviceId, connectionId)
            val seq0 = registerDevice(cookie, bearer, webgateUrl, deviceId, connectionId)
            Device(key, deviceId, webgateUrl, ws, connectionId, queue, seq0).also { device = it }
        }.getOrNull()
    }

    /** Latest known device id (compat): persisted value or fresh random. */
    fun deviceId(setting: Settings): String {
        runCatching { setting.getString(SETTING_KEY) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = randomHex(20)
        runCatching { setting.putString(SETTING_KEY, fresh) }
        return fresh
    }

    private suspend fun endpoints(): Pair<String, String> {
        val req = Request.Builder()
            .url(APRESOLVE_URL)
            .header("User-Agent", CONNECT_UA)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).await().use { resp ->
            val root = json.parser.parseToJsonElement(resp.body.string()).jsonObject
            fun first(key: String, fallback: String): String {
                val v = root[key]?.jsonArray?.getOrNull(0)
                    ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: fallback
                return v.removeSuffix(":443")
            }
            val dealer = first("dealer-g2", "dealer.g2.spotify.com")
            val sp = first("spclient", "spclient.wg.spotify.com")
            return "wss://$dealer" to "https://$sp"
        }
    }

    private suspend fun connectDealer(dealerUrl: String, bearer: String): Triple<WebSocket, String, DealerCommandQueue> {
        val deferred = CompletableDeferred<Pair<WebSocket, String>>()
        val queue = DealerCommandQueue()
        val encoded = URLEncoder.encode(bearer, Charsets.UTF_8.name())
        lateinit var ws: WebSocket
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseDealerCommand(text)?.let { cmd ->
                    queue.offer(cmd)
                    // Opportunistic queue harvest (their primary queue
                    // source): every replace_state carries the show_next
                    // chain, cached per seed for the rip.
                    SpotifyQueue.harvestStateMachine(cmd)
                }
                parseConnectionId(text)?.let { connectionId ->
                    if (!deferred.isCompleted) deferred.complete(ws to connectionId)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!deferred.isCompleted) deferred.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("dealer closed early"))
                }
            }
        }
        ws = dealerClient.newWebSocket(
            Request.Builder().url("$dealerUrl?access_token=$encoded").build(), listener
        )
        val (sock, id) = withTimeoutOrNull(DEALER_TIMEOUT_MS) { deferred.await() } ?: run {
            runCatching { ws.close(1000, "echo-connect-timeout") }
            error("dealer connection id timed out")
        }
        return Triple(sock, id, queue)
    }

    /** hm://track-playback/v1/command replace_state push (server-issued ids). */
    private fun parseDealerCommand(message: String): JsonObject? = runCatching {
        val root = json.parser.parseToJsonElement(message).jsonObject
        if (root.str("type") != "message") return@runCatching null
        if (root.str("uri") != "hm://track-playback/v1/command") return@runCatching null
        root["payloads"]?.jsonArray?.firstOrNull() as? JsonObject
    }.getOrNull()?.takeIf { it.str("type") == "replace_state" }

    private fun parseConnectionId(message: String): String? = runCatching {
        val root = json.parser.parseToJsonElement(message).jsonObject
        if (root["type"]?.jsonPrimitive?.content != "message") return@runCatching null
        root["headers"]?.jsonObject?.get("Spotify-Connection-Id")
            ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: root["uri"]?.jsonPrimitive?.content?.let { uri ->
                CONNECTION_REGEX.find(uri)?.groupValues?.getOrNull(1)
                    ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
            }
    }.getOrNull()

    private suspend fun registerObserver(
        cookie: String, bearer: String, webgateUrl: String,
        deviceId: String, connectionId: String,
    ) {
        val observerId = "hobs_$deviceId".take(40)
        val payload = buildJsonObject {
            put("member_type", "CONNECT_STATE")
            putJsonObject("device") {
                putJsonObject("device_info") {
                    putJsonObject("capabilities") {
                        put("can_be_player", false)
                        put("hidden", true)
                        put("needs_full_player_state", true)
                    }
                }
            }
        }.toString()
        val req = Request.Builder()
            .url("$webgateUrl/connect-state/v1/devices/$observerId")
            .header("User-Agent", CONNECT_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Spotify-Connection-Id", connectionId)
            .header("App-Platform", WebPlayerConfig.APP_PLATFORM)
            .header("Referer", WebPlayerConfig.REFERER)
            .header("Origin", WebPlayerConfig.ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $bearer")
            .put(payload.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).await().use { resp ->
            if (resp.code == 429) defer(resp, "observer")
        }
    }

    private suspend fun registerDevice(
        cookie: String, bearer: String, webgateUrl: String,
        deviceId: String, connectionId: String,
    ): Int {
        val payload = buildJsonObject {
            putJsonObject("device") {
                put("brand", "SpotifyHarmonyGeneric")
                putJsonObject("capabilities") {
                    put("change_volume", true)
                    put("enable_play_token", true)
                    put("supports_file_media_type", true)
                    put("play_token_lost_behavior", "pause")
                    put("disable_connect", false)
                    put("audio_podcasts", true)
                    put(
                        "manifest_formats",
                        buildJsonArray {
                            add(JsonPrimitive("file_ids_mp3"))
                            add(JsonPrimitive("file_urls_mp3"))
                            add(JsonPrimitive("file_ids_mp4"))
                            add(JsonPrimitive("file_ids_mp4_dual"))
                        },
                    )
                    put("supports_preferred_media_type", true)
                    put("supports_playback_offsets", true)
                    put("supports_playback_speed", true)
                }
                put("device_id", deviceId)
                put("device_type", "computer")
                putJsonObject("metadata") { }
                put("model", MODEL)
                put("name", DEVICE_NAME)
                put("platform_name", PLATFORM)
                put("platform_identifier", PLATFORM)
                put("is_group", false)
                put("correlation_id", randomHex(16))
                put("client_version", SDK_ID)
            }
            put("outro_endcontent_snooping", false)
            put("connection_id", connectionId)
            put("client_version", SDK_ID)
            put("volume", 65535)
        }.toString()
        val req = Request.Builder()
            .url("$webgateUrl/track-playback/v1/devices")
            .header("User-Agent", CONNECT_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("App-Platform", WebPlayerConfig.APP_PLATFORM)
            .header("Referer", WebPlayerConfig.REFERER)
            .header("Origin", WebPlayerConfig.ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $bearer")
            .post(payload.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).await().use { resp ->
            val body = runCatching { resp.body.string() }.getOrDefault("")
            if (!resp.isSuccessful) {
                if (resp.code == 429) defer(resp, "register")
                error("register HTTP ${resp.code}: ${body.take(120)}")
            }
            return runCatching {
                json.parser.parseToJsonElement(body).jsonObject["initial_seq_num"]
                    ?.jsonPrimitive?.content?.toIntOrNull()
            }.getOrNull() ?: 0
        }
    }

    /**
     * Reference state PUT: format is the string "file_ids_mp4",
     * playback_stats carries ms_latency 98, headers are Cookie + Bearer
     * only. Adopts the server-returned state_machine / updated_state_ref
     * into the session (the server is authoritative for refs).
     */
    private suspend fun putState(
        cookie: String, bearer: String,
        dev: Device, session: Session,
        debugSource: String, positionMs: Long, previousMs: Long,
        durationMs: Long, paused: Boolean, withStats: Boolean = false,
    ): Int {
        val pos = positionMs.coerceAtLeast(0L)
        val dur = durationMs.coerceAtLeast(0L)
        val payload = buildJsonObject {
            put("seq_num", dev.nextSeq())
            put("debug_source", debugSource)
            put("previous_position", previousMs.coerceAtLeast(0L))
            putJsonObject("state_ref") {
                put("state_machine_id", session.machine)
                put("state_id", session.state)
                put("paused", paused)
            }
            putJsonObject("sub_state") {
                put("playback_speed", if (paused) 0 else 1)
                put("position", pos)
                put("duration", dur)
                put("media_type", "AUDIO")
                put("bitrate", BITRATE)
                put("audio_quality", "HIGH")
                put("format", "file_ids_mp4")
                put("is_video_on", false)
            }
            if (withStats) {
                putJsonObject("playback_stats") {
                    put("ms_total_est", dur)
                    put("ms_metadata_duration", 0)
                    put("ms_manifest_latency", 0)
                    put("ms_latency", 98)
                }
            }
        }.toString()
        val req = Request.Builder()
            .url("${dev.webgateUrl}/track-playback/v1/devices/${dev.deviceId}/state")
            .header("User-Agent", CONNECT_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("App-Platform", WebPlayerConfig.APP_PLATFORM)
            .header("Referer", WebPlayerConfig.REFERER)
            .header("Origin", WebPlayerConfig.ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $bearer")
            .put(payload.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).await().use { resp ->
            val code = resp.code
            val body = runCatching { resp.body.string() }.getOrDefault("")
            if (code == 429) {
                defer(resp, "state")
                return code
            }
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 409) {
                dev.close()
                if (device === dev) device = null
                dropAllSessions()
                return code
            }
            if (code == 410) {
                return code
            }
            if (resp.isSuccessful && body.isNotBlank()) {
                adoptServerRef(session, body)
            }
            return code
        }
    }

    /** Adopt state_machine + updated_state_ref from a state PUT response.
     * Silent skip when absent (the reference does the same); safe casts because
     * the server answers JsonNull here when the ref is unknown. */
    private fun adoptServerRef(session: Session, body: String) = runCatching {
        val root = json.parser.parseToJsonElement(body) as? JsonObject ?: return@runCatching
        val machine = root["state_machine"] as? JsonObject ?: return@runCatching
        val ref = root["updated_state_ref"] as? JsonObject ?: return@runCatching
        val machineId = machine.str("state_machine_id") ?: return@runCatching
        val idx = (ref["state_index"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull() ?: return@runCatching
        val state = machine["states"]?.jsonArray?.getOrNull(idx) as? JsonObject
            ?: return@runCatching
        val stateId = state.str("state_id") ?: return@runCatching
        session.machine = machineId
        session.state = stateId
        (ref["paused"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toBooleanStrictOrNull()?.let { session.paused = it }
        val trackIdx = (state["track"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull()
        val track = trackIdx?.let { machine["tracks"]?.jsonArray?.getOrNull(it) as? JsonObject }
        track?.dealerPlaybackId()?.let { session.playback = it }
        track?.dealerDurationMs()?.let { if (it > 0L) session.durationMs = it }
            ?: state.dealerDurationMs()?.let { if (it > 0L) session.durationMs = it }
    }

    private fun buildStartEvent(session: Session): JsonObject = buildJsonObject {
        put("type", "jssdk_playback_start")
        putJsonObject("message") {
            put("play_track", session.trackUri)
            put("file_id", "")
            put("playback_id", session.playback)
            put("session_id", session.sessionId)
            put("ms_start_position", 0)
            put("initially_paused", false)
            put("client_id", DEVICE_CLIENT_ID)
            put("correlation_id", session.correlation)
            put("feature_identifier", "web-player")
        }
    }

    private fun buildVerificationEvent(
        session: Session, positionMs: Long, nextPlaybackId: String?,
    ): JsonObject = buildJsonObject {
        put("type", "track_stream_verification")
        putJsonObject("message") {
            put("play_track", session.trackUri)
            put("playback_id", session.playback)
            put("ms_played", positionMs)
            put("ms_nominal_played", positionMs)
            put("session_id", session.sessionId)
            put("sequence_id", 1)
            put("next_playback_id", nextPlaybackId.orEmpty())
            put("playback_service", "web_player")
        }
    }

    private fun buildStatsEvent(
        session: Session, positionMs: Long, durationMs: Long,
    ): JsonObject = buildJsonObject {
        val pos = positionMs.coerceAtLeast(0L)
        val dur = durationMs.coerceAtLeast(0L)
        put("type", "jssdk_playback_stats")
        putJsonObject("message") {
            put("play_track", session.trackUri)
            put("file_id", "")
            put("playback_id", session.playback)
            put("internal_play_id", session.playback)
            put("memory_cached", false)
            put("persistent_cached", false)
            put("audio_format", "")
            put("video_format", "")
            put("manifest_id", "")
            put("protected", false)
            put("key_system", "")
            put("key_system_impl", "")
            put("urls_json", "[]")
            put("start_time", session.startedAtMs)
            put("end_time", session.startedAtMs + pos)
            put("external_start_time", session.startedAtMs)
            put("ms_play_latency", 0)
            put("ms_init_latency", 0)
            put("ms_head_latency", 0)
            put("ms_first_bytes_latency", 0)
            put("ms_manifest_latency", 0)
            put("ms_resolve_latency", 0)
            put("ms_license_session_latency", 0)
            put("ms_license_generation_latency", 0)
            put("ms_license_request_latency", 0)
            put("ms_license_update_latency", 0)
            put("ms_played", pos)
            put("ms_nominal_played", pos)
            put("ms_file_duration", dur)
            put("ms_actual_duration", dur)
            put("ms_metadata_duration", 0)
            put("ms_start_position", 0)
            put("ms_end_position", pos)
            put("ms_initial_rebuffer", 0)
            put("ms_seek_rebuffer", 0)
            put("ms_seek_rebuffer_longest", 0)
            put("ms_stall_rebuffer", 0)
            put("ms_stall_rebuffer_longest", 0)
            put("ms_played_per_surface", buildJsonObject { })
            put("ms_played_visible", pos)
            put("n_stalls", 0)
            put("n_rendition_upgrade", 0)
            put("n_rendition_downgrade", 0)
            put("bps_bandwidth_max", 0)
            put("bps_bandwidth_min", 0)
            put("bps_bandwidth_avg", 0)
            put("n_seekback", 0)
            put("n_seekforward", 0)
            put("audio_start_bitrate", BITRATE)
            put("video_start_bitrate", 0)
            put("start_bitrate", BITRATE)
            put("audio_quality", "")
            put("time_weighted_bitrate", BITRATE)
            put("reason_start", "playbtn")
            put("reason_end", "endplay")
            put("initially_paused", false)
            put("had_error", false)
            put("n_warnings", 0)
            put("n_navigator_offline", 0)
            put("session_id", session.sessionId)
            put("sequence_id", 1)
            put("client_id", DEVICE_CLIENT_ID)
            put("correlation_id", session.correlation)
            put("n_dropped_video_frames", 0)
            put("n_total_video_frames", 0)
            put("resolution_max", 0)
            put("resolution_min", 0)
            put("total_bytes", 0)
            put("strategy", "")
            put("ms_played_per_audio_format", buildJsonObject { })
            put("ms_played_per_video_format", buildJsonObject { })
        }
    }

    /** Dealer batch envelope: Bearer + Cookie (reference shape, no CT). */
    private suspend fun postBatch(
        cookie: String, bearer: String, events: List<JsonObject>, what: String,
    ) {
        val payload = buildJsonObject {
            put("client_version", "0.0.0")
            put("platform", PLATFORM)
            put("sdk_id", SDK_ID)
            put("messages", buildJsonArray { events.forEach { add(it as JsonElement) } })
        }.toString()
        val req = Request.Builder()
            .url(BATCH_URL)
            .header("User-Agent", CONNECT_UA)
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("App-Platform", WebPlayerConfig.APP_PLATFORM)
            .header("Referer", WebPlayerConfig.REFERER)
            .header("Origin", WebPlayerConfig.ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $bearer")
            .post(payload.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).await().use { resp ->
            if (resp.code == 429) defer(resp, "event-$what")
        }
    }

    /**
     * Play-command + dealer-await (reference start shape). Contexts tried in
     * order — resolved playback context (playlist/album, liked excluded),
     * then the track itself, then its station — first dealer match wins and
     * is recorded on the session (drives recs/queue). Captures the queue
     * waiter BEFORE each POST so the replace_state push can't slip past.
     * Returns false on total miss (caller falls back to minted refs).
     */
    private suspend fun playAndAdoptDealerState(
        cookie: String, bearer: String, dev: Device, session: Session,
        requestedContext: String? = null,
    ): Boolean {
        val trackId = session.trackUri.toTrackId()
        val station = trackId?.let { "spotify:station:track:$it" }
        val want = requestedContext?.toPlaybackContextUri()
        // Liked (collection) is detected but never SENT: the server builds
        // recs/queue off it wrong, so it falls back to track + station.
        val resolved = want
            ?.takeUnless { it.isCollectionUri() }
            ?.let { ctx ->
                val pl = ctx.toPlaylistUri()
                if (pl != null && !pl.isPlaylistFormatUri()) {
                    resolvePlaylistFormatUri(cookie, bearer, dev.webgateUrl, pl) ?: ctx
                } else ctx
            }
        session.wantContext = want
        val contexts = listOfNotNull(resolved, session.trackUri, station).distinct()
        for (ctx in contexts) {
            val firstWait = dev.commandQueue.nextCommand()
            if (!playCommand(cookie, bearer, dev, session.trackUri, ctx)) continue
            val st = awaitDealerState(dev, firstWait, trackId, REPORT_DEALER_TIMEOUT_MS)
                ?: continue
            session.machine = st.machine
            session.state = st.state
            st.playback?.takeIf { it.isNotBlank() }?.let { session.playback = it }
            st.durationMs?.takeIf { it > 0L }?.let { session.durationMs = it }
            session.contextUri = ctx
            return true
        }
        // Keep the requested context for the next attempt even on total miss.
        resolved?.let { session.contextUri = it }
        return false
    }

    private suspend fun awaitDealerState(
        dev: Device, firstWait: CompletableDeferred<JsonObject>, expectedTrackId: String?,
        timeoutMs: Long = DEALER_TIMEOUT_MS,
    ): DealerState? = withTimeoutOrNull(timeoutMs) {
        var wait = firstWait
        while (true) {
            parseDealerState(wait.await(), expectedTrackId)?.let { return@withTimeoutOrNull it }
            wait = dev.commandQueue.nextCommand()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** Parse a replace_state push; null when for another track/ad/junk. */
    private fun parseDealerState(command: JsonObject, expectedTrackId: String?): DealerState? =
        runCatching {
            val machine = command["state_machine"] as? JsonObject ?: return@runCatching null
            val ref = command["state_ref"] as? JsonObject ?: return@runCatching null
            val machineId = machine.str("state_machine_id") ?: return@runCatching null
            val idx = (ref["state_index"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toIntOrNull() ?: return@runCatching null
            val state = machine["states"]?.jsonArray?.getOrNull(idx) as? JsonObject
                ?: return@runCatching null
            val stateId = state.str("state_id") ?: return@runCatching null
            val trackIdx = (state["track"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toIntOrNull()
            val track = trackIdx?.let { machine["tracks"]?.jsonArray?.getOrNull(it) as? JsonObject }
            val currentId = track?.dealerTrackId()
            if (currentId?.startsWith("ad:", ignoreCase = true) == true) return@runCatching null
            if (expectedTrackId != null && currentId != null && currentId != expectedTrackId) {
                return@runCatching null
            }
            DealerState(
                machineId, stateId, track?.dealerPlaybackId(),
                state.dealerDurationMs() ?: track?.dealerDurationMs(),
            )
        }.getOrNull()

    /** Resolve a playlist uri to its canonical playlist-format form
     * (playlist/v2/resolve-uri). Null on any failure. */
    private suspend fun resolvePlaylistFormatUri(
        cookie: String, bearer: String, webgateUrl: String, playlistUri: String,
    ): String? = runCatching {
        val req = Request.Builder()
            .url("$webgateUrl/playlist/v2/resolve-uri/$playlistUri")
            .header("User-Agent", CONNECT_UA)
            .header("Accept", "application/json")
            .header("App-Platform", WebPlayerConfig.APP_PLATFORM)
            .header("Referer", WebPlayerConfig.REFERER)
            .header("Origin", WebPlayerConfig.ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()
        client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val root = json.parser.parseToJsonElement(resp.body.string()) as? JsonObject
                ?: return@runCatching null
            root["resolvedPlaylists"]?.jsonArray?.firstOrNull()
                .let { it as? JsonObject }?.str("uri")?.toPlaylistUri()
                ?: root.str("uri")?.toPlaylistUri()
        }
    }.getOrNull()

    /** Safe string getter (JsonNull-safe; the server loves explicit nulls). */
    private fun JsonObject.str(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun String.toTrackId(): String? = when {
        startsWith("spotify:track:", ignoreCase = true) -> substringAfterLast(':')
        matches(Regex("^[A-Za-z0-9]{22}$")) -> this
        else -> null
    }?.takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }

    /** Playback-context normalizers. Liked
     * (spotify:collection:tracks) is detected but never USED as a play
     * context — the server builds recs/queue off it wrong, so callers fall
     * back to track + station. */
    private fun String.toPlaylistUri(): String? {
        val decoded = runCatching {
            java.net.URLDecoder.decode(trim(), Charsets.UTF_8.name())
        }.getOrDefault(trim())
        val lower = decoded.lowercase()
        val candidate = when {
            lower.startsWith("spotify:playlist:") -> decoded.substringAfterLast(':')
            lower.startsWith("spotify:user:") && lower.contains(":playlist:") ->
                decoded.substringAfterLast(":playlist:")

            lower.contains("/playlist/") -> decoded.substringAfter("/playlist/")
            decoded.matches(Regex("^[A-Za-z0-9]{22}$")) -> decoded
            else -> return null
        }.substringBefore('?').substringBefore('#').trim().trim('/')
            .substringBefore('/').substringAfterLast(':')
        return candidate.takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }
            ?.let { "spotify:playlist:$it" }
    }

    private fun String.toAlbumUri(): String? {
        val decoded = runCatching {
            java.net.URLDecoder.decode(trim(), Charsets.UTF_8.name())
        }.getOrDefault(trim())
        val lower = decoded.lowercase()
        val candidate = when {
            lower.startsWith("spotify:album:") -> decoded.substringAfterLast(':')
            lower.contains("/album/") -> decoded.substringAfter("/album/")
            decoded.matches(Regex("^[A-Za-z0-9]{22}$")) -> decoded
            else -> return null
        }.substringBefore('?').substringBefore('#').trim().trim('/')
            .substringBefore('/').substringAfterLast(':')
        return candidate.takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }
            ?.let { "spotify:album:$it" }
    }

    private fun String.toCollectionUri(): String? = trim().takeIf {
        it.equals("spotify:collection:tracks", ignoreCase = true) ||
            it.equals("collection:tracks", ignoreCase = true) ||
            it.contains("open.spotify.com/collection/tracks", ignoreCase = true)
    }?.let { "spotify:collection:tracks" }

    private fun String.isCollectionUri(): Boolean =
        equals("spotify:collection:tracks", ignoreCase = true)

    private fun String.isPlaylistFormatUri(): Boolean =
        startsWith("spotify:playlist-format:", ignoreCase = true)

    private fun String.toPlaybackContextUri(): String? = when {
        isPlaylistFormatUri() -> this
        startsWith("spotify:station:track:", ignoreCase = true) -> this
        else -> toCollectionUri() ?: toPlaylistUri() ?: toAlbumUri() ?: toTrackId()?.let { "spotify:track:$it" }
    }

    private fun JsonObject.dealerTrackId(): String? =
        listOfNotNull(
            str("uri")?.toTrackId(),
            str("track_uri")?.toTrackId(),
            str("trackUri")?.toTrackId(),
            str("id")?.toTrackId(),
            (this["metadata"] as? JsonObject)?.str("uri")?.toTrackId(),
            (this["metadata"] as? JsonObject)?.str("track_uri")?.toTrackId(),
            (this["metadata"] as? JsonObject)?.str("entity_uri")?.toTrackId(),
        ).firstOrNull()

    private fun JsonObject.dealerPlaybackId(): String? =
        (this["logData"] as? JsonObject)?.str("playbackId")
            ?: (this["log_data"] as? JsonObject)?.str("playback_id")
            ?: str("playback_id")
            ?: str("playbackId")

    private fun JsonObject.dealerDurationMs(): Long? =
        (this["duration_override"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toLongOrNull()
            ?: (this["metadata"] as? JsonObject)?.let {
                (it["duration"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toLongOrNull()
            }

    /**
     * Tell our own device to play this track in [contextUri] (play command +
     * skip_to), reference shape. Sent on EVERY report: each resolve is a new
     * playback session with fresh server-issued refs (no dedup — a skipped
     * await on replay would stall resolve waiting for a push that never comes).
     */
    private suspend fun playCommand(
        cookie: String, bearer: String, dev: Device, trackUri: String,
        contextUri: String,
    ): Boolean {
        if (System.currentTimeMillis() < blockedUntilMs) return false
        val payload = buildJsonObject {
            putJsonObject("command") {
                put("endpoint", "play")
                putJsonObject("context") {
                    put("uri", contextUri)
                    put("url", "context://$contextUri")
                }
                putJsonObject("play_origin") {
                    put("feature_identifier", "web-player")
                    put("feature_version", SDK_ID)
                }
                putJsonObject("options") {
                    put("license", "")
                    putJsonObject("skip_to") {
                        put("track_uri", trackUri)
                    }
                    put("initially_paused", false)
                }
                putJsonObject("logging_params") {
                    put("page_instance_ids", JsonArray(emptyList()))
                    put("interaction_ids", JsonArray(emptyList()))
                    put("command_id", randomHex(16))
                }
            }
        }.toString()
        return runCatching {
            val req = Request.Builder()
                .url("${dev.webgateUrl}/connect-state/v1/player/command/from/${dev.deviceId}/to/${dev.deviceId}")
                .header("User-Agent", CONNECT_UA)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("App-Platform", WebPlayerConfig.APP_PLATFORM)
                .header("Referer", WebPlayerConfig.REFERER)
                .header("Origin", WebPlayerConfig.ORIGIN)
                .header("Cookie", cookie)
                .header("Authorization", "Bearer $bearer")
                .post(payload.toByteArray().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).await().use { resp ->
                if (resp.code == 429) defer(resp, "command")
                if (!resp.isSuccessful) {
                    return@runCatching false
                }
                true
            }
        }.getOrNull() ?: false
    }

    /** Raw connect-state cluster snapshot (backing the queue rip).
     * Bearer + Cookie headers, reference shape. Null on any failure.
     * Deliberately NEVER trips the tracker backoff: this is best-effort
     * garnish fired from feed opens, and a 429 here must not silence
     * pause/seek/finalize for up to 10 minutes. */
    suspend fun clusterJson(
        setting: Settings, cookie: String?, bearer: String?,
    ): JsonObject? = runCatching {
        if (cookie.isNullOrBlank() || bearer.isNullOrBlank()) return@runCatching null
        val dev = ensure(cookie, bearer, setting) ?: return@runCatching null
        val req = Request.Builder()
            .url("${dev.webgateUrl}/connect-state/v1/cluster")
            .header("User-Agent", CONNECT_UA)
            .header("Accept", "application/json")
            .header("App-Platform", WebPlayerConfig.APP_PLATFORM)
            .header("Referer", WebPlayerConfig.REFERER)
            .header("Origin", WebPlayerConfig.ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()
        client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body.string().takeIf { it.isNotBlank() }?.let {
                json.parser.parseToJsonElement(it) as? JsonObject
            }
        }
    }.getOrNull()

    private fun defer(resp: Response, what: String) {
        val ms = resp.header("Retry-After")?.toLongOrNull()?.times(1000L)
            ?.coerceIn(15_000L, 10 * 60_000L) ?: 120_000L
        blockedUntilMs = maxOf(blockedUntilMs, System.currentTimeMillis() + ms)
    }

    private fun randomMachineId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val rnd = SecureRandom()
        return buildString {
            append("Ch")
            repeat(28) { append(chars[rnd.nextInt(chars.length)]) }
        }
    }

    private fun randomHex(n: Int): String =
        ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
