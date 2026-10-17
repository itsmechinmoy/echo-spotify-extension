package dev.brahmkshatriya.echo.extension.spotify

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.toMediaItem
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Spotify web-player queue rip, ported from the proven reference shapes
 * (player_state.next_tracks + dealer state-machine show_next walk +
 * 10-min per-seed cache + batch hydration).
 *
 * Our shape: cluster snapshot via [ConnectDevice.clusterJson] (2 attempts,
 * 350ms apart), next_tracks parsed with the reference field-name variants,
 * bare entries hydrated through fetchEntitiesForRecentlyPlayed (our
 * equivalent of their decorateContextTracks), merged cluster-first.
 *
 * Best-effort: empty list on login refusal, track mismatch, or any error.
 * Never throws, never gates playback.
 */
object SpotifyQueue {

    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_TRACKS = 50

    private data class Entry(val tracks: List<Track>, val at: Long)

    private val cache = LinkedHashMap<String, Entry>()

    /**
     * Harvest a dealer replace_state push (their primary queue source):
     * walk states[].transitions.show_next from the current state_index,
     * collecting track ids (bare — hydrated at rip time). Called on every
     * push by the Connect listener; never throws.
     */
    fun harvestStateMachine(command: JsonObject) = runCatching {
        val machine = command["state_machine"] as? JsonObject ?: return@runCatching
        val ref = command["state_ref"] as? JsonObject ?: return@runCatching
        val startIdx = (ref["state_index"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return@runCatching
        val trackObjs = machine["tracks"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
        if (trackObjs.size < 2) return@runCatching
        val stateObjs = machine["states"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
        if (stateObjs.isEmpty()) return@runCatching
        val cur = stateObjs.getOrNull(startIdx) ?: return@runCatching
        val trackIdx = (cur["track"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?.takeIf { it in trackObjs.indices } ?: return@runCatching
        val currentId = trackObjs.getOrNull(trackIdx)?.queueTrackId() ?: return@runCatching
        val seen = linkedSetOf(currentId)
        val ids = mutableListOf<String>()
        var idx: Int? = startIdx
        while (ids.size < MAX_TRACKS) {
            val st = idx?.takeIf { it in stateObjs.indices }?.let(stateObjs::get)
                ?: break
            val next = ((st["transitions"] as? JsonObject)
                ?.get("show_next") as? JsonObject)
                ?.let { (it["state_index"] as? JsonPrimitive)?.content?.toIntOrNull() }
                ?.takeIf { it in stateObjs.indices && seen.add("s$it") }
                ?: break
            val ti = (stateObjs[next]["track"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?.takeIf { it in trackObjs.indices } ?: break
            val tid = trackObjs[ti].queueTrackId()
                ?.takeIf { seen.add(it) } ?: break
            ids += tid
            idx = next
        }
        if (ids.isEmpty()) return@runCatching
        println("SpotifyWidevine: queue harvest seed=${currentId.takeLast(8)} tracks=${ids.size}")
        synchronized(cache) {
            val tracks = ids.mapNotNull { id ->
                cache[currentId]?.tracks?.firstOrNull { it.id == "spotify:track:$id" }
                    ?: Track(id = "spotify:track:$id", title = "")
            }
            cache[currentId] = Entry(tracks, System.currentTimeMillis())
            while (cache.size > 64) cache.remove(cache.keys.first())
        }
    }

    /** Rip the web-player next-up queue for [seedTrackUri] (full uri). */
    suspend fun rip(
        queries: Queries,
        setting: Settings,
        cookie: String?,
        bearer: String?,
        seedTrackUri: String,
        cropCovers: Boolean,
        limit: Int = 25,
    ): List<Track> = runCatching {
        val seedId = seedTrackUri.toTrackId() ?: return@runCatching emptyList()
        // Harvested/dealer cache first (their primary source).
        var raws: List<Raw>? = synchronized(cache) {
            cache[seedId]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }
                ?.tracks?.map { Raw(it.id.toTrackId() ?: it.id, it) }
        }
        if (raws == null) {
            // No session = device doesn't hold this track (cold page-open):
            // skip the fetch entirely instead of spamming cluster for a
            // guaranteed miss. The tracker start warms it on actual playback.
            if (!ConnectDevice.hasSession(seedTrackUri)) {
                println("SpotifyWidevine: queue cold seed=${seedId.takeLast(8)}")
                return@runCatching emptyList()
            }
            // Two attempts like the reference (cluster lands right after a
            // state PUT; the first read can race it).
            var cluster: JsonObject? = null
            repeat(2) { attempt ->
                cluster = ConnectDevice.clusterJson(setting, cookie, bearer)
                if (cluster?.playerState()?.currentId() == seedId) return@repeat
                if (attempt == 0) delay(350L)
            }
            val root = cluster ?: return@runCatching emptyList()
            // Player state at root, or nested under "cluster".
            val player = root.playerState()
                ?: (root["cluster"] as? JsonObject)?.playerState()
                ?: return@runCatching emptyList()
            if (player.currentId() != seedId) {
                println("SpotifyWidevine: queue miss current=${player.currentId()?.takeLast(8)} seed=${seedId.takeLast(8)}")
                return@runCatching emptyList()
            }
            val seen = linkedSetOf(seedId)
            raws = player["next_tracks"]?.jsonArray.orEmpty()
                .asSequence()
                .mapNotNull { (it as? JsonObject)?.toRaw() }
                .filter { seen.add(it.id) }
                .take(MAX_TRACKS)
                .toList()
        }
        val list = raws.orEmpty()
        if (list.isEmpty()) return@runCatching emptyList()
        // Hydrate bare entries (no title) via batch lookup — the proven
        // library-tab pattern (their decorateContextTracks equivalent).
        // Runs on cache hits too: harvest stores id-only shells.
        val bare = list.filter { it.track?.title.isNullOrBlank() }.map { it.id }
        val hydrated = if (bare.isEmpty()) emptyMap() else runCatching {
            queries.fetchEntitiesForRecentlyPlayed(bare.map { "spotify:track:$it" })
                .json.data.lookup.mapNotNull { item ->
                    item.data?.toMediaItem(cropCovers) as? Track
                }.associateBy { it.id.toTrackId() ?: it.id }
        }.onFailure {
            println("SpotifyWidevine: queue hydrate FAIL ${it.message?.take(120)}")
        }.getOrDefault(emptyMap())
        val tracks = list.asSequence()
            .map { raw ->
                val h = hydrated[raw.id]
                val base = raw.track
                when {
                    base == null -> h
                    h == null -> base.takeIf { !base.title.isNullOrBlank() }
                    else -> base.mergeFrom(h)
                }
            }
            .filterNotNull()
            .filter { !it.title.isNullOrBlank() }
            .distinctBy { it.id }
            .take(limit)
            .toList()
        println("SpotifyWidevine: queue rip seed=${seedId.takeLast(8)} tracks=${tracks.size}")
        if (tracks.isNotEmpty()) {
            synchronized(cache) {
                cache[seedId] = Entry(tracks, System.currentTimeMillis())
                while (cache.size > 64) cache.remove(cache.keys.first())
            }
        }
        tracks
    }.onFailure {
        println("SpotifyWidevine: queue rip FAIL ${it.message?.take(140)}")
    }.getOrDefault(emptyList())

    /** Cluster meta wins; hydrated fills blanks (their mergeWithHydrated). */
    private fun Track.mergeFrom(h: Track): Track = copy(
        title = title.takeIf { it.isNotBlank() } ?: h.title,
        artists = artists.ifEmpty { h.artists },
        album = album ?: h.album,
        cover = cover ?: h.cover,
        duration = duration ?: h.duration,
        isExplicit = isExplicit || h.isExplicit,
    )

    private fun JsonObject.playerState(): JsonObject? =
        this["player_state"] as? JsonObject

    private fun JsonObject.currentId(): String? =
        (this["track"] as? JsonObject)?.queueTrackId()

    private data class Raw(val id: String, val track: Track?)

    private fun JsonObject.toRaw(): Raw? {
        val id = queueTrackId() ?: return null
        val meta = (this["metadata"] as? JsonObject)
        val title = meta?.str("name") ?: meta?.str("title")
            ?: str("name") ?: str("title") ?: return Raw(id, null)
        val artists = parseArtists()
        val cover = parseImageUrl()?.toImageHolder()
        val duration = parseDurationMs()
        return Raw(
            id,
            Track(
                id = "spotify:track:$id",
                title = title,
                artists = artists,
                cover = cover,
                duration = duration,
            )
        )
    }

    private fun JsonObject.parseArtists(): List<Artist> {
        val names = listOfNotNull(
            str("artist_name"), str("artistName"), str("artist"),
            str("artists"), str("display_artist"), str("displayArtist"),
            (this["metadata"] as? JsonObject)?.str("artist_name"),
            (this["metadata"] as? JsonObject)?.str("artist"),
        ).firstOrNull()
            ?.split(Regex("\\s*(?:,|;|\\u2022|\\|)\\s*"))
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val uri = str("artist_uri") ?: str("artistUri")
            ?: str("artist_id")?.let { "spotify:artist:$it" }
            ?: (this["metadata"] as? JsonObject)?.str("artist_uri")
        return names.mapIndexed { i, name ->
            Artist(
                id = uri.takeIf { i == 0 && !it.isNullOrBlank() }.orEmpty(),
                name = name,
            )
        }
    }

    private fun JsonObject.parseImageUrl(): String? {
        val meta = this["metadata"] as? JsonObject
        return listOfNotNull(
            str("image_url"), str("imageUrl"), str("cover_url"), str("coverUrl"),
            meta?.str("image_url"), meta?.str("cover_url"), meta?.str("coverUrl"),
            (this["images"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.let {
                    (it as? JsonObject)?.str("url")
                        ?: (it as? JsonPrimitive)?.content
                },
        ).firstOrNull { it.isNotBlank() && it.startsWith("http") }
    }

    private fun JsonObject.parseDurationMs(): Long? {
        val meta = this["metadata"] as? JsonObject
        return (this["duration_override"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (this["duration"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: meta?.let { (it["duration"] as? JsonPrimitive)?.content?.toLongOrNull() }
    }

    /** Track-id extraction with their field-name variants. */
    private fun JsonObject.queueTrackId(): String? =
        listOfNotNull(
            str("uri")?.toTrackId(),
            str("track_uri")?.toTrackId(),
            str("trackUri")?.toTrackId(),
            str("id")?.toTrackId(),
            (this["metadata"] as? JsonObject)?.str("uri")?.toTrackId(),
            (this["metadata"] as? JsonObject)?.str("track_uri")?.toTrackId(),
            (this["metadata"] as? JsonObject)?.str("entity_uri")?.toTrackId(),
        ).firstOrNull()

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun String.toTrackId(): String? = when {
        startsWith("spotify:track:", ignoreCase = true) -> substringAfterLast(':')
        matches(Regex("^[A-Za-z0-9]{22}$")) -> this
        else -> null
    }?.takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }
}
