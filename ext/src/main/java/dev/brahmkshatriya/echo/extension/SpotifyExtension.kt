package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.TrackerMarkClient
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.MP4_128
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.MP4_128_DUAL
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.MP4_256
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.MP4_256_DUAL
import dev.brahmkshatriya.echo.extension.spotify.Base62
import dev.brahmkshatriya.echo.extension.spotify.ConnectDevice
import dev.brahmkshatriya.echo.extension.spotify.SpotifyQueue
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.FLAC_FLAC
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.FLAC_FLAC_24BIT
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.OGG_VORBIS_160
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.OGG_VORBIS_320
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat.OGG_VORBIS_96
import dev.brahmkshatriya.echo.extension.spotify.Json
import dev.brahmkshatriya.echo.extension.spotify.Queries
import dev.brahmkshatriya.echo.extension.spotify.SpotifyApi
import dev.brahmkshatriya.echo.extension.spotify.WebPlayerConfig
import dev.brahmkshatriya.echo.extension.spotify.models.AccountAttributes
import dev.brahmkshatriya.echo.extension.spotify.models.ArtistOverview
import dev.brahmkshatriya.echo.extension.spotify.models.GetAlbum
import dev.brahmkshatriya.echo.extension.spotify.models.Item
import dev.brahmkshatriya.echo.extension.spotify.models.UserProfileView
import dev.brahmkshatriya.echo.extension.PodcastSupport.episode
import dev.brahmkshatriya.echo.extension.PodcastSupport.show
import dev.brahmkshatriya.echo.extension.PodcastSupport.toTrack
import dev.brahmkshatriya.echo.extension.PodcastSupport.toAlbum
import dev.brahmkshatriya.echo.extension.PodcastSupport.toArtist
import dev.brahmkshatriya.echo.extension.PodcastSupport.episodeUris
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import java.io.EOFException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import spotify.extendedmetadata.metadata.ExtendedMetadataProto.ExtensionKind

open class SpotifyExtension : ExtensionClient, LoginClient.WebView,
    SearchFeedClient, HomeFeedClient, LibraryFeedClient, LyricsClient, ShareClient,
    TrackClient, LikeClient, RadioClient, SaveClient,
    AlbumClient, PlaylistClient, ArtistClient, FollowClient, PlaylistEditClient,
    TrackerClient, TrackerMarkClient {

    override suspend fun getSettingItems() = listOf(
        SettingSwitch(
            "Show Canvas",
            "show_canvas",
            "Whether to show background video canvas in songs",
            showCanvas
        ),
        SettingSwitch(
            "Crop Covers",
            "crop_covers",
            "Whether to crop artist and users images to fill the whole circle",
            cropCovers
        ),
        SettingSwitch(
            "Show Music Video",
            "show_mv",
            "Whether to list the full music video as a playable variant",
            showMv
        ),
        SettingSwitch(
            "Prefer Music Video",
            "prefer_mv",
            "Whether the music video plays by default instead of the song mix",
            preferMv
        )
    )

    private val showCanvas get() = setting.getBoolean("show_canvas") ?: true
    private val cropCovers get() = setting.getBoolean("crop_covers") ?: true
    private val showMv get() = setting.getBoolean("show_mv") ?: true
    private val preferMv get() = setting.getBoolean("prefer_mv") ?: false

    lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
        api.settings = settings
    }

    open val filesDir = File("spotify")
    val api by lazy { SpotifyApi() }
    private val cdnClient by lazy { OkHttpClient() }
    val queries by lazy { Queries(api) }

    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val dontCache = true
        override val initialUrl =
            "https://accounts.spotify.com/en/login?allow_password=1".toGetRequest(
                mapOf("User-Agent" to WebPlayerConfig.USER_AGENT)
            )
        override val stopUrlRegex =
            Regex("(https://accounts\\.spotify\\.com/.{2}/status.*)|(https://open\\.spotify\\.com)")

        val emailRegex = Regex("remember=([^;]+)")
        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User> {
            if (!cookie.contains("sp_dc")) throw Exception("Token not found")
            val api = SpotifyApi()
            api.settings = setting
            api.setCookie(cookie)
            val email = emailRegex.find(cookie)?.groups?.get(1)?.value?.let {
                URLDecoder.decode(it, "UTF-8")
            }
            val user = Queries(api).profileAttributes().json.toUser().copy(
                extras = mapOf("cookie" to cookie),
                subtitle = email
            )
            return listOf(user)
        }
    }

    override fun setLoginUser(user: User?) {
        val cookie = if (user == null) null
        else user.extras["cookie"] ?: throw ClientException.Unauthorized(user.id)
        api.setCookie(cookie)
        api.setUser(user?.id)
        this.user = user
        this.product = null
    }

    private var user: User? = null
    override suspend fun getCurrentUser(): User? {
        return queries.profileAttributes().json.toUser()
    }

    private var product: AccountAttributes.Product? = null
    private suspend fun hasPremium(): Boolean {
        if (api.cookie == null) return false
        if (product == null) product = queries.accountAttributes().json.data.me.account.product
        return product != AccountAttributes.Product.FREE
    }

    private fun getBrowsePage(): Feed.Data<Shelf> = PagedData.Single {
        queries.browseAll().json.data.browseStart.sections.toShelves(queries, cropCovers)
    }.toFeedData()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) return Feed(listOf()) { getBrowsePage() }
        val (shelves, tabs) = queries.searchDesktop(query).json.data.searchV2
            .toShelvesAndTabs(query, queries, cropCovers)
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "ARTISTS" -> paged {
                    queries.searchArtist(query, it).json.data.searchV2.artists
                        .toItemShelves(cropCovers)
                }

                "TRACKS" -> paged {
                    queries.searchTrack(query, it).json.data.searchV2.tracksV2
                        .toItemShelves(cropCovers)
                }

                "ALBUMS" -> paged {
                    queries.searchAlbum(query, it).json.data.searchV2.albumsV2
                        .toItemShelves(cropCovers)
                }

                "PLAYLISTS" -> paged {
                    queries.searchPlaylist(query, it).json.data.searchV2.playlists
                        .toItemShelves(cropCovers)
                }

                "GENRES" -> paged {
                    queries.searchGenres(query, it).json.data.searchV2.genres
                        .toCategoryShelves(queries, cropCovers)
                }

                "EPISODES" -> paged {
                    queries.searchFullEpisodes(query, it).json.data.searchV2.episodes
                        .toItemShelves(cropCovers)
                }

                "USERS" -> paged {
                    queries.searchUser(query, it).json.data.searchV2.users.toItemShelves(cropCovers)
                }

                else -> shelves
            }.toFeedData()
        }
    }

    override suspend fun loadFeed(track: Track) = PagedData.Single {
        if (track.type == Track.Type.Podcast) return@Single emptyList<Shelf>()
        val (union, rec) = coroutineScope {
            val a = async { queries.getTrack(track.id).json.data.trackUnion }
            val b = queries.internalLinkRecommenderTrack(track.id).json.data.seoRecommendedTrack
            a.await() to b
        }
        val list =
            rec.items.mapNotNull { it.data?.toTrack(cropCovers) }.takeIf { it.isNotEmpty() }?.let {
                listOf(Shelf.Lists.Tracks("${track.id}_more", "More like this", it))
            } ?: spotifyRadioShelf(track.id)
        val first =
            union.firstArtist?.items?.firstOrNull()?.toShelves(queries, cropCovers) ?: emptyList()
        val other =
            union.otherArtists?.items.orEmpty().map { it.toShelves(queries, cropCovers) }.flatten()
        list + first + other
    }.toFeed()

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        val mvSource = streamable.extras["mvSource"]
        if (streamable.id.startsWith("mv:") || mvSource != null) {
            val sourceId = mvSource ?: streamable.id.removePrefix("mv:")
            val dur = streamable.extras["dur"]?.toLongOrNull() ?: 180_000L
            return videoSource(sourceId, dur, isDownload)
        }
        return when (streamable.type) {
            Streamable.MediaType.Server -> {
                if (streamable.extras["podcastExternal"] == "true") {
                    val url = PodcastSupport.externalAudioUrl(streamable.id)
                        ?: throw ClientException.NotSupported("Invalid external podcast URL")
                    return Streamable.Source.Http(url.toGetRequest()).toMedia()
                }
                val cookie = api.cookie ?: throw ClientException.LoginRequired()
                val format = streamable.extras["formatNum"]!!.toInt()
                val fileId = streamable.extras["fileId"] ?: streamable.id

                streamable.extras["gid"]?.takeIf { it.isNotBlank() }?.let { gid ->
                    runCatching { "spotify:track:${Base62.encode(gid)}" }.getOrNull()?.let { uri ->
                        ConnectDevice.note(fileId, uri)
                    }
                }
                runCatching {
                    val auth = currentAuth()
                    if (auth != null) {
                        ConnectDevice.report(
                            setting, cookie, auth.second, auth.third, format, fileId,
                            streamable.extras["durMs"]?.toLongOrNull() ?: 0L,
                        )
                    }
                }

                when (format) {
                    AudioFormat.MP3_96, AudioFormat.MP3_160,
                    AudioFormat.MP3_256, AudioFormat.MP3_320 -> {
                        if (streamable.extras["podcast"] != "true")
                            throw ClientException.NotSupported("Non-podcast MP3 stream")
                        val urls = queries.storageResolve(format.toString(), streamable.id)
                            .json.cdnUrl
                        val url = SpotifyCdn.firstWorking(urls) { candidate ->
                            cdnClient.newCall(
                                Request.Builder().url(candidate)
                                    .header("Range", "bytes=0-15")
                                    .header("Accept-Encoding", "identity")
                                    .build()
                            ).await().use { SpotifyCdn.acceptsRange(it.code, 0) }
                        }
                            ?: throw IllegalStateException("No CDN URL for podcast episode")
                        Streamable.Source.Http(url.toGetRequest()).toMedia()
                    }
                    OGG_VORBIS_320, OGG_VORBIS_160, OGG_VORBIS_96 ->
                        oggStream(format.toString(), streamable)
                    FLAC_FLAC, FLAC_FLAC_24BIT -> try {
                        oggStream(format.toString(), streamable)
                    } catch (failure: IllegalStateException) {
                        val fallback = (AudioStreamFallback.onLicenseRejection(streamable, failure)
                            ?: if (AudioStreamFallback.isLicenseRejection(failure)) {
                                try {
                                    resolveLegacyFlacFallback(streamable)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    failure.addSuppressed(e)
                                    null
                                }
                            } else null)
                            ?: throw failure
                        try {
                            oggStream(fallback.extras.getValue("formatNum"), fallback)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (fallbackError: Exception) {
                            failure.addSuppressed(fallbackError)
                            throw failure
                        }
                    }
                    MP4_256, MP4_128, MP4_256_DUAL, MP4_128_DUAL ->
                        widevineStream(streamable, isDownload)
                    else -> throw ClientException.NotSupported(AudioFormat.name(format))
                }
            }

            Streamable.MediaType.Background ->
                Streamable.Media.Background(streamable.id.toGetRequest())

            else -> throw IllegalStateException("Unsupported Streamable : $streamable")
        }
    }

    open val showWidevineStreams = true

    /** Queued tracks from older extension versions have no fallback file metadata. */
    private suspend fun resolveLegacyFlacFallback(streamable: Streamable): Streamable? {
        val gid = streamable.extras["gid"]
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) } ?: return null
        val id = "spotify:track:${Base62.encode(gid)}"
        val track = loadTrack(Track(id = id, title = ""), false)
        return track.streamables.filter {
            it.extras["formatNum"]?.toIntOrNull() in listOf(
                OGG_VORBIS_320, OGG_VORBIS_160, OGG_VORBIS_96,
            )
        }.maxByOrNull { it.quality }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = coroutineScope {
        if (track.type == Track.Type.Podcast || track.id.startsWith("spotify:episode:")) {
            val response = api.extendedMetadata(
                listOf(track.id), listOf(ExtensionKind.EPISODE_V4, ExtensionKind.AUDIO_FILES)
            )
            val episode = with(PodcastSupport) { response.episode(track.id) }
                ?: throw ClientException.NotSupported("Podcast episode metadata unavailable")
            val audioFiles = with(PodcastSupport) {
                response.bytes(ExtensionKind.AUDIO_FILES, track.id)
            }?.let {
                spotify.extendedmetadata.audiofiles.AudioFilesExtensionProto.AudioFilesExtensionResponse.parseFrom(it)
            }
            return@coroutineScope with(PodcastSupport) {
                episode.toTrack(track.id, track, audioFiles)
            }
        }
        val hasPremium = hasPremium()
        val canvas =
            if (showCanvas) async { queries.canvas(track.id).json.toStreamable() } else null
        val mediaJson = runCatching {
            queries.trackPlaybackMedia(track.id).json
        }.getOrNull()
        val entries = mediaJson?.mediaEntries().orEmpty()
        entries.forEach { ConnectDevice.note(it.fileId, track.id) }
        val mvSourceId = mediaJson?.videoSourceCandidates()?.firstOrNull()?.substringBefore('@')
        queries.extendedMetadata(track.id).toTrack(
            hasPremium, true, showWidevineStreams, canvas?.await(),
            entries, showMv, preferMv, mvSourceId
        ).copy(
            isExplicit = track.isExplicit
        )
    }

    private suspend fun spotifyRadioShelf(id: String): List<Shelf> = runCatching {
        val auth = currentAuth()
        val queued = if (auth != null) {
            SpotifyQueue.rip(queries, setting, auth.first, auth.second, id, cropCovers)
        } else emptyList()
        if (queued.isNotEmpty()) {
            return@runCatching listOf(Shelf.Lists.Tracks("${id}_radio", "Radio-like", queued))
        }
        val radioUri = queries.seedToPlaylist(id).json.mediaItems.firstOrNull()?.uri
            ?: return@runCatching emptyList()
        val tracks = queries.fetchPlaylistContent(radioUri, 0)
            .json.data.playlistV2.content!!.items!!
            .mapNotNull { it.itemV2?.data?.toTrack(cropCovers) }
            .drop(1)
        if (tracks.isEmpty()) emptyList()
        else listOf(Shelf.Lists.Tracks("${id}_radio", "Radio-like", tracks))
    }.getOrDefault(emptyList())

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        runCatching {
            val d = details?.takeIf { it.extensionId == "spotify" } ?: return@runCatching
            val now = System.currentTimeMillis()
            val paused = !isPlaying
            val wall = wallPosition(now)
            val seekJump = kotlin.math.abs(d.currentPosition - wall) > 8000L
            val debug = when {
                seekJump -> "seek"
                paused != lastTickPaused || d.track.id != lastTickUri -> if (paused) "pause" else "resume"
                else -> return@runCatching
            }
            val (cookie, bearer, ct) = currentAuth() ?: return@runCatching
            val fileId = ConnectDevice.fileIdForUri(d.track.id)
            val pos: Long
            val prev: Long
            if (debug == "seek") {
                prev = wall
                pos = d.currentPosition
            } else {
                pos = wall
                prev = wall
            }
            lastTickUri = d.track.id
            lastTickFile = fileId
            lastTickPos = pos
            lastTickDur = d.totalDuration ?: 0L
            lastTickPaused = paused
            lastTickMs = now
            ConnectDevice.tick(
                setting, cookie, bearer, ct, d.track.id, fileId,
                pos, prev, d.totalDuration ?: 0L, paused, debug,
                contextUri = d.context?.id,
            )
        }
    }

    private fun wallPosition(now: Long = System.currentTimeMillis()): Long {
        val dur = lastTickDur
        val elapsed = if (lastTickPaused) 0L else now - lastTickMs
        val pos = lastTickPos + elapsed.coerceAtLeast(0L)
        return if (dur > 0L) pos.coerceIn(0L, dur) else pos.coerceAtLeast(0L)
    }

    private fun finalizePosition(): Long {
        val wall = wallPosition()
        val dur = lastTickDur
        return if (dur > 1_000L && wall >= dur - 250L) dur - 250L else wall
    }

    override suspend fun getMarkAsPlayedDuration(details: TrackDetails): Long? {
        val dur = details.totalDuration?.takeIf { it > 0L } ?: return 30_000L
        return minOf(dur, 30_000L)
    }

    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        runCatching {
            if (details.extensionId != "spotify") return@runCatching
            val (cookie, bearer, ct) = currentAuth() ?: return@runCatching
            val fileId = ConnectDevice.fileIdForUri(details.track.id)
            val dur = details.totalDuration ?: lastTickDur
            val wall = wallPosition()
            val pos = maxOf(30_000L, wall).let { if (dur > 0L) it.coerceAtMost(dur) else it }
            ConnectDevice.tick(
                setting, cookie, bearer, ct, details.track.id, fileId,
                pos, wall, dur,
                false, "played_threshold_reached",
                contextUri = details.context?.id,
            )
            ConnectDevice.verify(
                cookie, bearer, ct, details.track.id, fileId, pos,
            )
        }
    }

    override suspend fun onTrackChanged(details: TrackDetails?) {
        runCatching {
            val d = details
            if (d == null || d.extensionId != "spotify") {
                lastTickUri?.let { uri ->
                    val (cookie, bearer, ct) = currentAuth() ?: return@runCatching
                    val fin = finalizePosition()
                    ConnectDevice.tick(
                        setting, cookie, bearer, ct, uri, lastTickFile,
                        fin, fin, lastTickDur, true, "track_data_finalized", true,
                    )
                    ConnectDevice.verify(cookie, bearer, ct, uri, lastTickFile, fin)
                }
                if (d == null) lastTickUri = null
                return@runCatching
            }
            val (cookie, bearer, ct) = currentAuth() ?: return@runCatching
            val replay = lastTickUri == d.track.id &&
                d.currentPosition < lastTickPos
            ConnectDevice.startTrack(
                setting, cookie, bearer, d.track.id, d.totalDuration ?: 0L,
                contextUri = d.context?.id,
                force = replay,
            )
            val newFile = ConnectDevice.fileIdForUri(d.track.id)
            val nextPb = newFile?.let { ConnectDevice.sessionIdsFor(it)?.playback } ?: ""
            if (lastTickUri != null && (lastTickUri != d.track.id || replay)) {
                val fin = finalizePosition()
                lastTickUri?.let { uri ->
                    ConnectDevice.tick(
                        setting, cookie, bearer, ct, uri, lastTickFile,
                        fin, fin, lastTickDur, false, "track_data_finalized", true,
                    )
                    ConnectDevice.verify(cookie, bearer, ct, uri, lastTickFile, fin, nextPb)
                }
            }
            val fileId = newFile ?: ConnectDevice.fileIdForUri(d.track.id)
            lastTickUri = d.track.id
            lastTickFile = fileId
            lastTickPos = d.currentPosition
            lastTickDur = d.totalDuration ?: 0L
            lastTickPaused = false
            lastTickMs = System.currentTimeMillis()
        }
    }

    private suspend fun createRadio(id: String): Radio {
        val radioId = queries.seedToPlaylist(id).json.mediaItems.first().uri
        return queries.fetchPlaylist(radioId).json.data.playlistV2.toRadio(cropCovers)!!
    }

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?) = createRadio(item.id)
    override suspend fun loadRadio(radio: Radio) = radio
    override suspend fun loadTracks(radio: Radio) = loadPlaylistTracks(radio.id, true).toFeed()

    override suspend fun loadPlaylist(playlist: Playlist) =
        when (val type = playlist.id.substringAfter(":").substringBefore(":")) {
            "playlist" -> {
                val new =
                    queries.fetchPlaylist(playlist.id).json.data.playlistV2.toPlaylist(cropCovers)!!
                new.copy(
                    isEditable = runCatching {
                        val id = getPlaylistId(new)
                        queries.editPlaylistMetadata(id, new.title, new.description).json
                            .capabilities?.canEditItems
                    }.getOrNull() ?: false
                )
            }

            "collection" -> {
                val totalSongs = if (playlist.trackCount == null)
                    queries.fetchLibraryTracks(0).json.data.me.library.tracks.totalCount
                else playlist.trackCount

                likedPlaylist.copy(
                    cover = "https://misc.scdn.co/liked-songs/liked-songs-640.jpg".toImageHolder(),
                    trackCount = totalSongs
                )
            }

            else -> throw ClientException.NotSupported("Unsupported playlist type: $type")
        }

    private fun loadPlaylistTracks(id: String, skipFirst: Boolean = false) = paged { offset ->
        val content = queries.fetchPlaylistContent(id, offset).json.data.playlistV2.content!!
        val tracks = content.items!!.mapNotNull {
            val track = it.itemV2?.data?.toTrack(cropCovers, added = it.addedAt?.toDate())
                ?: return@mapNotNull null
            if (it.uid != null) track.copy(extras = mapOf("uid" to it.uid)) else track
        }.let {
            if (skipFirst && offset == 0) it.drop(1) else it
        }
        val page = content.pagingInfo!!
        val next = page.offset!! + page.limit!!
        tracks to if (content.totalCount!! > next) next else null
    }

    private fun loadLikedTracks() = paged { offset ->
        val content = queries.fetchLibraryTracks(offset).json.data.me.library.tracks
        val tracks = content.items.map {
            it.track.data?.toTrack(
                cropCovers,
                url = it.track.uri,
                added = it.addedAt?.toDate()
            )!!
        }
        val page = content.pagingInfo!!
        val next = page.offset!! + page.limit!!
        tracks to if (content.totalCount!! > next) next else null
    }

    override suspend fun loadTracks(playlist: Playlist) =
        when (val type = playlist.id.substringAfter(":").substringBefore(":")) {
            "playlist" -> loadPlaylistTracks(playlist.id).toFeed()
            "collection" -> loadLikedTracks().toFeed()
            else -> throw ClientException.NotSupported("Unsupported playlist type: $type")
        }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int,
    ) {
        getPlaylistId(playlist)
        val uid = tracks[fromIndex].extras["uid"]!!
        val before = if (fromIndex - toIndex > 0) 0 else 1
        val fromUid = tracks.getOrNull(toIndex + before)?.extras?.get("uid")
        queries.moveItemsInPlaylist(playlist.id, uid, fromUid)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>,
    ) {
        if (api.cookie == null) throw ClientException.LoginRequired()
        when (val type = playlist.id.substringAfter(":").substringBefore(":")) {
            "playlist" -> {
                val uids = indexes.map { tracks[it].extras["uid"]!! }.toTypedArray()
                queries.removeFromPlaylist(playlist.id, *uids)
            }

            "collection" -> {
                val uris = indexes.map { tracks[it].id }.toTypedArray()
                queries.removeFromLibrary(*uris)
            }

            else -> throw ClientException.NotSupported("Unsupported playlist type: $type")
        }
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        new: List<Track>,
    ) {
        if (api.cookie == null) throw ClientException.LoginRequired()
        when (val type = playlist.id.substringAfter(":").substringBefore(":")) {
            "playlist" -> {
                val uris = new.map { it.id }.toTypedArray()
                val fromUid = tracks.getOrNull(index)?.extras?.get("uid")
                queries.addToPlaylist(playlist.id, fromUid, *uris)
            }

            "collection" -> {
                val uris = new.map { it.id }.toTypedArray()
                queries.addToLibrary(*uris)
            }

            else -> throw ClientException.NotSupported("Unsupported playlist type: $type")
        }

    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        if (api.cookie == null) throw ClientException.LoginRequired()
        val uri = queries.createPlaylist(title, description).json.uri
        val userId = getCurrentUser()!!.id.substringAfter("spotify:user:")
        queries.playlistToLibrary(userId, uri)
        return loadPlaylist(Playlist(uri, title, true, isRadioSupported = false))
    }

    private fun getPlaylistId(playlist: Playlist): String {
        if (api.cookie == null) throw ClientException.LoginRequired()
        if (!playlist.id.startsWith("spotify:playlist:"))
            throw ClientException.NotSupported("Unsupported playlist type: ${playlist.id}")
        return playlist.id.substringAfter("spotify:playlist:")
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        getPlaylistId(playlist)
        val userId = getCurrentUser()!!.id.substringAfter("spotify:user:")
        queries.deletePlaylist(userId, playlist.id)
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist, title: String, description: String?,
    ) {
        val id = getPlaylistId(playlist)
        queries.editPlaylistMetadata(id, title, description).raw
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        return editablePlaylists(queries, track?.id, null, cropCovers).loadAll()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? =
        when (playlist.id.substringAfter(":").substringBefore(":")) {
            "playlist" -> {
                PagedData.Single<Shelf> {

                    val playlists = queries.seoRecommendedPlaylist(playlist.id).json.data
                        .seoRecommendedPlaylist.items.mapNotNull { it.toMediaItem(cropCovers) }
                    if (playlists.isEmpty()) return@Single emptyList()
                    listOf(
                        Shelf.Lists.Items(
                            id = "${playlist.id}_more",
                            title = "More Playlists like this",
                            list = playlists,
                        )
                    )
                }.toFeed()

            }

            else -> null
        }

    override suspend fun loadAlbum(album: Album): Album {
        if (album.type == Album.Type.Show || album.id.startsWith("spotify:show:")) {
            val response = api.extendedMetadata(listOf(album.id), listOf(ExtensionKind.SHOW_V4))
            val show = with(PodcastSupport) { response.show(album.id) }
                ?: throw ClientException.NotSupported("Podcast show metadata unavailable")
            return with(PodcastSupport) { show.toAlbum(album.id, album) }
        }
        val res = queries.getAlbum(album.id)
        return res.json.data.albumUnion.toAlbum(cropCovers)!!.copy(
            extras = mapOf("raw" to res.raw)
        )
    }


    override suspend fun loadFeed(album: Album): Feed<Shelf>? = when (album.type) {
        Album.Type.Show -> null
        Album.Type.Book -> null
        else -> PagedData.Single {
            val union = api.json.decode<GetAlbum>(album.extras["raw"]!!).data.albumUnion
            union.moreAlbumsByArtist?.items.orEmpty().map {
                it.toShelves(queries, cropCovers)
            }.flatten()
        }.toFeed()
    }

    override suspend fun loadTracks(album: Album) = when (album.type) {
        Album.Type.Show -> {
            val uris = queries.showEpisodeUris(album.id)
            paged<Track> { offset ->
                val chunk = uris.drop(offset.toInt()).take(20)
                val episodes = if (chunk.isEmpty()) emptyList() else {
                    val batch = api.extendedMetadata(chunk, listOf(ExtensionKind.EPISODE_V4))
                    with(PodcastSupport) {
                        chunk.mapNotNull { uri -> batch.episode(uri)?.toTrack(uri) }
                    }
                }
                val next = (offset + chunk.size).toLong().takeIf { it < uris.size.toLong() }
                episodes to next
            }.toFeed()
        }
        Album.Type.Book -> null
        else -> {
            var next = 0L
            paged { offset ->
                val content =
                    queries.queryAlbumTracks(album.id, offset).json.data.albumUnion.tracksV2!!
                val tracks = content.items!!.map {
                    it.track?.toTrack(cropCovers, album)!!
                }
                next += tracks.size
                tracks to if (content.totalCount!! > next) next else null
            }.toFeed()
        }
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val home = queries.home(null).json.data?.home!!
        val tabs = if (api.cookie == null) emptyList() else
            listOf(Tab("", "All")) + home.homeChips?.toTabs()!!
        return Feed(tabs) { tab ->
            PagedData.Single {
                val res = if (tab == null || tab.id == "") home
                else queries.home(tab.id).json.data?.home!!
                res.toShelves(queries, cropCovers)
            }.toFeedData()
        }
    }

    override suspend fun onShare(item: EchoMediaItem): String {
        val type = item.id.substringAfter("spotify:").substringBefore(":")
        val id = item.id.substringAfter("spotify:$type:")
        return "https://open.spotify.com/$type/$id"
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        if (api.cookie == null) throw ClientException.LoginRequired()
        val filters = queries.libraryV3(0).json.data?.me?.libraryV3?.availableFilters
            ?.mapNotNull { it.name }.orEmpty()
        val tabs = (listOf("All", "You") + filters).map { Tab(it, it) }
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "All", null -> pagedLibrary(queries, cropCovers = cropCovers)
                "You" -> PagedData.Single {
                    val top = queries.userTopContent().json.data.me.profile
                    listOfNotNull(
                        Shelf.Lists.Items(
                            "top_artists",
                            "Top Artist",
                            top.topArtists?.items.orEmpty().mapNotNull {
                                it.toMediaItem(cropCovers)
                            }
                        ),
                        Shelf.Lists.Tracks(
                            "top_tracks",
                            "Top Tracks",
                            top.topTracks?.items.orEmpty().mapNotNull {
                                (it.data as? Item.Track)?.toTrack(cropCovers)
                            }
                        )
                    ) + try {
                        // A missing history endpoint or insufficient OAuth scope
                        // must not prevent the user's top artists/tracks loading.
                        val uris = queries.recentlyPlayed().json.contentUris()
                        if (uris.isEmpty()) emptyList() else {
                            queries.fetchEntitiesForRecentlyPlayed(uris).json.data.lookup.mapNotNull {
                                it.data?.toMediaItem(cropCovers)?.toShelf()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                else -> pagedLibrary(queries, tab.id, null, cropCovers)
            }.toFeedData()
        }
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        if (api.cookie == null) throw ClientException.LoginRequired()
        if (shouldSave) queries.addToLibrary(item.id)
        else queries.removeFromLibrary(item.id)
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        if (api.cookie == null) return false
        val isSaved = queries.areEntitiesInLibrary(item.id)
            .json.data?.lookup?.firstOrNull()?.data?.saved
        return isSaved ?: false
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        if (shouldLike) queries.addToLibrary(item.id)
        else queries.removeFromLibrary(item.id)
    }

    override suspend fun isItemLiked(item: EchoMediaItem) = isItemSaved(item)

    override suspend fun isFollowing(item: EchoMediaItem) = isItemSaved(item)

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        if (item !is Artist) return null
        return item.extras["followers"]?.toLong()
    }

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        if (api.cookie == null) throw ClientException.LoginRequired()
        when (val type = item.id.substringAfter(":").substringBefore(":")) {
            "artist", "show" -> {
                if (shouldFollow) queries.addToLibrary(item.id)
                else queries.removeFromLibrary(item.id)
            }

            "user" -> {
                val id = item.id.substringAfter("spotify:user:")
                if (shouldFollow) queries.followUsers(id)
                else queries.unfollowUsers(id)
            }

            else -> throw IllegalArgumentException("Unsupported artist type: $type")
        }
    }

    private suspend fun loadPodcastEpisodes(
        episodeUris: List<String>, showArtist: Artist,
    ): List<Track> {
        if (episodeUris.isEmpty()) return emptyList()
        val batch = api.extendedMetadata(episodeUris, listOf(ExtensionKind.EPISODE_V4))
        return with(PodcastSupport) {
            episodeUris.mapNotNull { uri ->
                batch.episode(uri)?.toTrack(
                    uri, fallback = Track(id = uri, title = "", artists = listOf(showArtist))
                )
            }
        }
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        return when (val type = artist.id.substringAfter(":").substringBefore(":")) {
            "artist" -> {
                val res = api.json.decode<ArtistOverview>(artist.extras["raw"]!!)
                res.data.artistUnion.toShelves(queries, cropCovers)
            }

            "show" -> coroutineScope {
                // Keep recommendations independent from episode loading: a change
                // to Spotify's public show page must not hide playable episodes.
                val relatedDeferred = async {
                    try {
                        queries.relatedPodcastShows(artist.id)
                            .mapNotNull { it.toArtist(cropCovers) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val uris = queries.showEpisodeUris(artist.id)
                val first = loadPodcastEpisodes(uris.take(20), artist)
                val related = relatedDeferred.await()
                listOfNotNull(Shelf.Lists.Tracks(
                    id = "${artist.id}_episodes",
                    title = "Episodes",
                    list = first,
                    more = if (uris.size > 20) paged<Shelf> { offset ->
                        val chunk = uris.drop(offset.toInt()).take(20)
                        val tracks = loadPodcastEpisodes(chunk, artist)
                        tracks.map { it.toShelf() } to
                            (offset + chunk.size).toLong().takeIf { it < uris.size.toLong() }
                    }.toFeed() else null,
                ), related.takeIf { it.isNotEmpty() }?.let {
                    Shelf.Lists.Items(
                        id = "${artist.id}_similar_podcasts",
                        title = "More podcasts like this",
                        list = it,
                    )
                })
            }

            "user" -> {
                val res = api.json.decode<UserProfileView>(artist.extras["raw"]!!)
                val id = artist.id.substringAfter("spotify:user:")
                listOfNotNull(
                    res.toShelf(),
                    queries.profileFollowers(id).json.toShelf("${id}_followers", "Followers"),
                    queries.profileFollowing(id).json.toShelf("${id}_following", "Following")
                )
            }

            else -> throw IllegalArgumentException("Unsupported artist type: $type")
        }.toFeed(Feed.Buttons(showPlayAndShuffle = true))
    }

    override suspend fun loadArtist(artist: Artist): Artist {
        when (val type = artist.id.substringAfter(":").substringBefore(":")) {
            "show" -> {
                val response = api.extendedMetadata(listOf(artist.id), listOf(ExtensionKind.SHOW_V4))
                val show = with(PodcastSupport) { response.show(artist.id) }
                    ?: throw ClientException.NotSupported("Podcast show metadata unavailable")
                return with(PodcastSupport) { show.toArtist(artist.id, artist) }
            }

            "artist" -> {
                val res = queries.queryArtistOverview(artist.id)
                val artist = res.json.data.artistUnion.toArtist(null, cropCovers)
                return artist!!.copy(
                    extras = artist.extras + mapOf("raw" to res.raw)
                )
            }

            "user" -> {
                val id = artist.id.substringAfter("spotify:user:")
                val profile = queries.profileWithPlaylists(id)
                return profile.json.toArtist()!!.copy(
                    extras = mapOf("raw" to profile.raw)
                )
            }

            else -> throw IllegalArgumentException("Unsupported artist type: $type")
        }
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track) = PagedData.Single {
        val id = track.id.substringAfter("spotify:track:")
        val image = track.cover as ImageHolder.NetworkRequestImageHolder
        val lyrics = runCatching { queries.colorLyrics(id, image.request.url).json.lyrics }
            .getOrNull() ?: return@Single emptyList<Lyrics>()
        var last = Long.MAX_VALUE
        val list = lyrics.lines?.reversed()?.mapNotNull {
            val start = it.startTimeMs?.toLong()!!
            val item = Lyrics.Item(
                it.words ?: return@mapNotNull null,
                startTime = start,
                endTime = last,
            )
            last = start
            item
        }?.reversed() ?: return@Single emptyList<Lyrics>()
        listOf(
            Lyrics(
                id = track.id,
                title = track.title,
                subtitle = lyrics.providerDisplayName,
                lyrics = Lyrics.Timed(list)
            )
        )
    }.toFeed()

    override suspend fun loadLyrics(lyrics: Lyrics) = lyrics

    /** Minted web Bearer + CT reusing the session caches (tracker callbacks). */
    private suspend fun currentAuth(): Triple<String, String, String?>? {
        val cookie = api.cookie ?: return null
        val (bearer, ct) = runCatching { webAuth() }.getOrNull() ?: return null
        return Triple(cookie, bearer, ct)
    }

    /**
     * THE license header map: browser-verbatim, shared by audio AND video.
     * Single source of truth — both endpoints live in the same family,
     * take the same BQC class, and grant under the same shape.
     */
    private fun licenseHeaders(bearer: String, ct: String?): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["Authorization"] = "Bearer $bearer"
        if (!ct.isNullOrBlank()) headers["client-token"] = ct
        headers["Origin"] = WebPlayerConfig.ORIGIN
        headers["Referer"] = WebPlayerConfig.REFERER
        headers["User-Agent"] = WebPlayerConfig.USER_AGENT
        headers["Accept"] = "*/*"
        headers["Accept-Language"] = WebPlayerConfig.ACCEPT_LANGUAGE
        headers["sec-ch-ua"] = WebPlayerConfig.SEC_CH_UA
        headers["sec-ch-ua-mobile"] = WebPlayerConfig.SEC_CH_UA_MOBILE
        headers["sec-ch-ua-platform"] = WebPlayerConfig.SEC_CH_UA_PLATFORM
        headers["sec-fetch-dest"] = "empty"
        headers["sec-fetch-mode"] = "cors"
        headers["sec-fetch-site"] = "same-site"
        return headers
    }

    /**
     * Full music video on stock Echo, merged A+V in one MPD: the segments
     * are CENC, one KID covers both profiles (browser-verified: identical
     * PSSH in both inits), keys come from the manifest-declared video
     * license endpoint with the SHARED audio headers. The ext fetches tiny
     * init segments (URL-authed, no CDM) only to learn roles (soun/vide
     * via hdlr), the real codec (avcC), and the PSSH box — then hands stock
     * Exo a file: MPD + license URL. Merge is structural: one MPD, two
     * AdaptationSets, one grant.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun videoSource(
        sourceId: String, fallbackDurMs: Long, isDownload: Boolean,
    ): Streamable.Media {
        if (isDownload) {
            throw ClientException.NotSupported("Music Video downloads are not supported")
        }
        val (bearer, ct) = webAuth()
        val cachedMpd = java.io.File(filesDir, "mv_$sourceId.mpd")
        val jsonFile = java.io.File(filesDir, "mv_$sourceId.json")
        val freshMs = 24 * 3600 * 1000L
        if (cachedMpd.exists() && jsonFile.exists() &&
            System.currentTimeMillis() - cachedMpd.lastModified() < freshMs
        ) {
            val licPath = runCatching {
                parseVideoManifest(sourceId, jsonFile.readText())?.licensePath
            }.getOrNull() ?: "/widevine-license/v1/video/license"
            return videoLicensed(cachedMpd, licPath, bearer, ct, isDownload)
        }
        // Prune week-old MV artifacts (mpd/json).
        runCatching {
            filesDir.listFiles { f ->
                f.name.startsWith("mv_")
            }?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 7 * 24 * 3600 * 1000L) it.delete()
            }
        }
        val raw = runCatching { queries.videoManifest(sourceId, bearer, ct) }.getOrElse {
            throw ClientException.NotSupported("Failed to load video manifest")
        }
        runCatching {
            jsonFile.parentFile?.mkdirs()
            jsonFile.writeText(raw)
        }
        val manifest = parseVideoManifest(sourceId, raw)
            ?: throw ClientException.NotSupported("Failed to parse video manifest")
        suspend fun getCdnBytes(u: String, range: String? = null): ByteArray? = runCatching {
            chromeClient.newCall(
                Request.Builder().url(u)
                    .header("User-Agent", WebPlayerConfig.USER_AGENT)
                    .header("Referer", WebPlayerConfig.REFERER)
                    .header("sec-ch-ua", WebPlayerConfig.SEC_CH_UA)
                    .header("sec-ch-ua-mobile", WebPlayerConfig.SEC_CH_UA_MOBILE)
                    .header("sec-ch-ua-platform", WebPlayerConfig.SEC_CH_UA_PLATFORM)
                    .apply { if (range != null) header("Range", range) }
                    .build()
            ).await().use { resp ->
                if (!resp.isSuccessful) throw Exception("cdn http=${resp.code}")
                resp.body.bytes()
            }
        }.getOrNull()
        val reps = mutableListOf<MvRep>()
        data class MvCand(
            val content: VideoContent, val profile: String, val kind: String, val codec: String,
        )
        val cands = mutableListOf<MvCand>()
        var psshB64: String? = null
        var probed = 0
        outer@ for (content in manifest.contents) {
            for (p in content.profiles) {
                if (probed++ >= 8) break@outer
                val initU = manifest.initUrl(content, p) ?: continue
                val init = getCdnBytes(initU) ?: continue
                val kind = initTrackKind(init) ?: continue
                if (psshB64 == null) {
                    findInitPssh(init)?.let { psshB64 = Base64.Default.encode(it) }
                }
                val codec = if (kind == "vide") initVideoCodec(init) ?: "avc1.4d401e" else "mp4a.40.2"
                cands += MvCand(content, p, kind, codec)
                if (cands.count { it.kind == "vide" } >= 5 &&
                    cands.any { it.kind == "soun" } && psshB64 != null
                ) break@outer
            }
        }
        for (role in listOf("vide", "soun")) {
            var taken = 0
            val cap = if (role == "vide") 5 else 1
            for (c in cands.filter { it.kind == role }) {
                if (taken >= cap) break
                val seg0U = manifest.segmentUrl(c.content, c.profile, "0") ?: continue
                if (getCdnBytes(seg0U, "bytes=0-0") == null) continue
                val bw = if (role == "vide") avcBandwidth(c.codec) else 160000
                reps += MvRep(c.content, c.profile, c.kind, c.codec, bw)
                taken++
            }
        }
        val pssh = psshB64
            ?: throw ClientException.NotSupported("Video manifest contains no PSSH")
        if (reps.isEmpty()) {
            throw ClientException.NotSupported("Video has no playable profile")
        }
        reps.sortBy { it.bandwidth }
        val withDur = manifest.copy(
            durationMs = maxOf(manifest.durationMs, fallbackDurMs).takeIf { it > 0 } ?: 180_000L
        )
        val mpd = buildVideoMpd(withDur, reps, pssh)
        val mpdFile = java.io.File(filesDir, "mv_$sourceId.mpd").apply {
            parentFile?.mkdirs()
            writeText(mpd)
        }
        val licPath = manifest.licensePath ?: "/widevine-license/v1/video/license"
        return videoLicensed(mpdFile, licPath, bearer, ct, isDownload)
    }

    private fun videoLicensed(
        mpdFile: java.io.File, licPath: String, bearer: String, ct: String?,
        isDownload: Boolean,
    ): Streamable.Media {
        val license = Streamable.Decryption.Widevine(
            "https://gew1-spclient.spotify.com$licPath".toGetRequest(licenseHeaders(bearer, ct)),
            false,
        )
        return Streamable.Source.Http(
            request = mpdFile.toURI().toString().toGetRequest(),
            type = Streamable.SourceType.DASH,
            decryption = license,
            quality = 5,
            title = "Music Video",
            isVideo = true,
            isLive = !isDownload,
        ).toMedia()
    }

    /** Web Bearer + CT, minted once per ~10min and reused.
     * Cache key is sp_dc (long-lived); full cookie rotates and would
     * re-mint per tap. */
    private suspend fun webAuth(): Pair<String, String?> {
        val cookie = api.cookie
        val now = System.currentTimeMillis()
        val spDc = cookie?.let { extractSpDc(it) }
        val webBearer = if (spDc != null && spDc == cachedMintSpDc && now < cachedMintExpMs) {
            cachedMintBearer
        } else {
            cookie?.let { getWebBearer(it) }?.also {
                cachedMintSpDc = spDc
                cachedMintBearer = it
                cachedMintExpMs = now + MINT_TTL_MS
            }
        }
        val bearer = webBearer ?: run {
            api.clientTokenManager.ensureValid()
            api.getWebAccessToken()
        }
        val ct = if (webBearer != null) {
            if (spDc != null && spDc == cachedCtSpDc && now < cachedCtExpMs) cachedCt
            else cookie?.let { mintWebClientToken(it) }?.also {
                cachedCtSpDc = spDc
                cachedCt = it
                cachedCtExpMs = now + MINT_TTL_MS
            }
        } else api.clientTokenManager.clientToken
        return bearer to ct
    }

    private suspend fun widevineStream(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media.Server {
        val format = streamable.extras["formatNum"]
            ?: throw ClientException.NotSupported("Missing format")
        val fileId = streamable.extras["fileId"] ?: streamable.id
        val mirrors = queries.storageResolve(format, fileId).json.cdnUrl
        val url = mirrors[kotlin.math.abs(fileId.hashCode()) % mirrors.size]
        val (bearer, ct) = webAuth()
        val headers = licenseHeaders(bearer, ct)
        streamable.extras["gid"]?.takeIf { it.isNotBlank() }?.let { gid ->
            runCatching { "spotify:track:${Base62.encode(gid)}" }.getOrNull()?.let { uri ->
                ConnectDevice.note(fileId, uri)
            }
        }
        runCatching {
            ConnectDevice.report(
                setting, api.cookie, bearer, ct, format.toIntOrNull(), fileId,
                streamable.extras["durMs"]?.toLongOrNull() ?: 0L,
            )
        }
        val licenseUrl = "https://spclient.wg.spotify.com/widevine-license/v1/audio/license"
        val decryption = Streamable.Decryption.Widevine(
            licenseUrl.toGetRequest(headers),
            true,
        )
        return Streamable.Source.Http(
            request = url.toGetRequest(),
            decryption = decryption,
            isLive = !isDownload,
        ).toMedia()
    }

    /** Device id bound to the login session when present (else the install id).
     * Keeps every minted token tied to the same device. */
    private fun sessionDeviceId(cookie: String): String {
        return cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("__Host-device_id=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?: api.deviceId()
    }

    /** OkHttp with Chrome-ordered TLS cipher suites. OkHttp's defaults advertise
     * a Java fingerprint that some Spotify front doors refuse; the platform
     * stack underneath is Chrome-derived, so only the advertised order needs
     * fixing. Used for the WAF-sensitive mint path. */
    private val chromeClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                        .cipherSuites(
                            CipherSuite.TLS_AES_128_GCM_SHA256,
                            CipherSuite.TLS_AES_256_GCM_SHA384,
                            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
                        )
                        .build(),
                    ConnectionSpec.MODERN_TLS,
                )
            )
            .build()
    }

    /** The browser's full cookie jar: login dump plus anything open.spotify.com
     * sets on a visit (sp_key et al). The mint endpoint may refuse a partial
     * jar even when sp_dc itself is valid. */
    private suspend fun fullCookieJar(cookie: String): String {
        val jar = linkedMapOf<String, String>()
        cookie.split(';').map { it.trim() }
            .filter { it.contains('=') }
            .forEach { jar[it.substringBefore('=')] = it.substringAfter('=') }
        runCatching {
            val req = Request.Builder()
                .url("https://open.spotify.com/")
                .header("User-Agent", WebPlayerConfig.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Cookie", cookie)
                .build()
            chromeClient.newCall(req).await().use { resp ->
                resp.headers.values("Set-Cookie").forEach { sc ->
                    val pair = sc.substringBefore(';').trim()
                    if (pair.contains('=')) {
                        jar[pair.substringBefore('=')] = pair.substringAfter('=')
                    }
                }
            }
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend fun getWebBearer(cookie: String): String? {
        val spDc = extractSpDc(cookie)
        if (spDc != null) {
            val token = runCatching {
                val url = api.web.getWebTokenUrl("transport")
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", WebPlayerConfig.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Accept-Language", WebPlayerConfig.ACCEPT_LANGUAGE)
                    .header("Referer", WebPlayerConfig.REFERER)
                    .header("sec-ch-ua", WebPlayerConfig.SEC_CH_UA)
                    .header("sec-ch-ua-mobile", WebPlayerConfig.SEC_CH_UA_MOBILE)
                    .header("sec-ch-ua-platform", WebPlayerConfig.SEC_CH_UA_PLATFORM)
                    .header("sec-fetch-dest", "empty")
                    .header("sec-fetch-mode", "cors")
                    .header("sec-fetch-site", "same-origin")
                    .header("Cookie", "sp_dc=$spDc")
                    .build()
                chromeClient.newCall(req).await().use { resp ->
                    if (!resp.isSuccessful) null
                    else api.json.decode<JsonObject>(resp.body.string())
                        .jsonObject["accessToken"]?.jsonPrimitive?.content
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (!token.isNullOrBlank()) return token
        }
        return runCatching {
            val jar = fullCookieJar(cookie)
            val req = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web-player")
                .header("User-Agent", WebPlayerConfig.USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", WebPlayerConfig.ACCEPT_LANGUAGE)
                .header("Referer", WebPlayerConfig.REFERER)
                .header("Origin", WebPlayerConfig.ORIGIN)
                .header("sec-ch-ua", WebPlayerConfig.SEC_CH_UA)
                .header("sec-ch-ua-mobile", WebPlayerConfig.SEC_CH_UA_MOBILE)
                .header("sec-ch-ua-platform", WebPlayerConfig.SEC_CH_UA_PLATFORM)
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-site")
                .header("Cookie", jar)
                .build()
            chromeClient.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) null
                else api.json.decode<JsonObject>(resp.body.string())
                    .jsonObject["accessToken"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    private fun extractSpDc(cookie: String): String? = cookie.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("sp_dc=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

    private suspend fun mintWebClientToken(cookie: String): String? = runCatching {
        val deviceId = sessionDeviceId(cookie)
        val body = """{"client_data":{"client_version":"${WebPlayerConfig.appVersion}","client_id":"${WebPlayerConfig.CLIENT_ID}","js_sdk_data":{"device_brand":"unknown","device_id":"$deviceId","device_model":"unknown","device_type":"computer","os":"windows","os_version":"NT 10.0"}}}"""
        val req = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            .header("User-Agent", WebPlayerConfig.USER_AGENT)
            .header("Accept-Language", WebPlayerConfig.ACCEPT_LANGUAGE)
            .header("Origin", WebPlayerConfig.ORIGIN)
            .header("Referer", WebPlayerConfig.REFERER)
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-site")
            .header("Accept", "application/json")
            .header("content-type", "application/json")
            .header("Connection", "keep-alive")
            .header("Cookie", cookie)
            .post(body.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        okhttp3.OkHttpClient().newCall(req).await().use { response ->
            if (!response.isSuccessful) return@runCatching null
            api.json.decode<JsonObject>(response.body.string())
                .jsonObject["granted_token"]?.jsonObject
                ?.get("token")?.jsonPrimitive?.content
        }
    }.getOrNull()

    open suspend fun getKey(json: Json, accessToken: String, fileId: String): ByteArray {
        require(fileId.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid Spotify audio file ID" }
        val request = Request.Builder()
            .url("https://spclient.wg.spotify.com/playplay/v1/key/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/x-protobuf")
            .post(PlayPlayLicenseRequest.encode().toRequestBody("application/x-protobuf".toMediaType()))
            .build()
        return api.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Spotify PlayPlay request failed: HTTP ${response.code}")
            }
            val obfuscated = PlayPlayRecordDecoder.parseObfuscatedKey(response.body.bytes())
            PlayPlayRecordDecoder.decodeObfuscatedKey(obfuscated)
        }
    }

    private suspend fun oggStream(format: String, streamable: Streamable): Streamable.Media {
        val fileId = streamable.id
        val urls = queries.storageResolve(format, fileId).json.cdnUrl
            .filter(SpotifyCdn::isSafeUrl).distinct()
        if (urls.isEmpty()) throw IllegalStateException("No CDN URL for audio file")
        val accessToken = api.getWebAccessToken()
        val key = getKey(api.json, accessToken, fileId)
        return Streamable.InputProvider { position, length ->
            if (length == 0L) ByteArrayInputStream(ByteArray(0)) to 0L
            else decryptFromPosition(format, key, AUDIO_IV, position, length) { pos, end ->
                val range = "bytes=$pos-${end?.toString().orEmpty()}"
                var lastStatus = -1
                var lastError: IOException? = null
                for (url in urls) {
                    val request = Request.Builder().url(url)
                        .header("Range", range)
                        .header("Accept-Encoding", "identity")
                        .build()
                    val response = try {
                        cdnClient.newCall(request).await()
                    } catch (e: IOException) {
                        lastError = e
                        continue
                    }
                    if (SpotifyCdn.acceptsRange(response.code, pos)) {
                        val size = response.body.contentLength().takeIf { it >= 0L }
                            ?: response.header("Content-Length")?.toLongOrNull() ?: -1L
                        // Closing this stream closes the underlying response body.
                        return@decryptFromPosition response.body.byteStream() to size
                    }
                    lastStatus = response.code
                    response.close()
                }
                throw IOException(
                    "All CDN locations failed for audio range (last HTTP status $lastStatus)",
                    lastError,
                )
            }
        }.toSource(fileId).toMedia()
    }

    private suspend fun decryptFromPosition(
        format: String,
        key: ByteArray,
        iv: BigInteger,
        position: Long,
        length: Long,
        provider: suspend (Long, Long?) -> Pair<InputStream, Long>,
    ): Pair<InputStream, Long> {
        val isFLAC = format.toInt() == FLAC_FLAC || format.toInt() == FLAC_FLAC_24BIT
        val byteSkip = if(isFLAC) 0 else 0xA7L
        val newPos = position + byteSkip
        val alignedPos = newPos - (newPos % 16)
        val blockOffset = (newPos % 16).toInt()

        val endByte = if (length < 0) null else alignedPos + blockOffset + length - 1
        val (input, contentLength) = provider(alignedPos, endByte)

        val ivCounter = iv.add(BigInteger.valueOf(alignedPos / 16))
        val ivBytes = ivCounter.to16ByteArray()

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(ivBytes)
        )

        val cipherStream = CipherInputStream(input, cipher)
        cipherStream.skipBytes(blockOffset)
        return cipherStream to if (contentLength < 0L) -1L
        else (contentLength - blockOffset).coerceAtLeast(0L)
    }

    companion object {
        /** V18: session caches keyed by sp_dc (stable across cookie rotation). */
        var cachedMintSpDc: String? = null
        var cachedMintBearer: String? = null
        var cachedMintExpMs: Long = 0L
        var cachedCtSpDc: String? = null
        var cachedCt: String? = null
        var cachedCtExpMs: Long = 0L

        const val MINT_TTL_MS = 600_000L

        /** Tracker throttle: last reported tick (skip/pause/seek spam guard). */
        var lastTickUri: String? = null
        var lastTickFile: String? = null
        var lastTickPos: Long = 0L
        var lastTickDur: Long = 0L
        var lastTickPaused: Boolean = false
        var lastTickMs: Long = 0L

        private val AUDIO_IV = BigInteger("72e067fbddcbcf77ebe8bc643f630d93", 16)
        private fun BigInteger.to16ByteArray(): ByteArray {
            val full = toByteArray()
            return when {
                full.size == 16 -> full
                full.size > 16 -> full.copyOfRange(full.size - 16, full.size)
                else -> ByteArray(16 - full.size) + full
            }
        }

        fun InputStream.skipBytes(len: Int) {
            var remaining = len
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0) {
                val toRead = minOf(remaining, buffer.size)
                val read = read(buffer, 0, toRead)
                if (read == -1) break // EOF
                remaining -= read
            }
            if (remaining > 0) throw EOFException("Reached end of stream before reading $len bytes")
        }
    }
}
