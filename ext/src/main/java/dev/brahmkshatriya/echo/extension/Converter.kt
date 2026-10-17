package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.spotify.AudioFormat
import dev.brahmkshatriya.echo.extension.spotify.Base62
import dev.brahmkshatriya.echo.extension.spotify.Queries
import dev.brahmkshatriya.echo.extension.spotify.models.Albums
import dev.brahmkshatriya.echo.extension.spotify.models.Artists
import dev.brahmkshatriya.echo.extension.spotify.models.Artwork
import dev.brahmkshatriya.echo.extension.spotify.models.Canvas
import dev.brahmkshatriya.echo.extension.spotify.models.HomeFeed
import dev.brahmkshatriya.echo.extension.spotify.models.IAlbum
import dev.brahmkshatriya.echo.extension.spotify.models.IArtist
import dev.brahmkshatriya.echo.extension.spotify.models.ITrack
import dev.brahmkshatriya.echo.extension.spotify.models.Item
import dev.brahmkshatriya.echo.extension.spotify.models.Item.Wrapper
import dev.brahmkshatriya.echo.extension.spotify.models.ItemsV2
import dev.brahmkshatriya.echo.extension.spotify.models.LibraryV3
import dev.brahmkshatriya.echo.extension.spotify.models.ProfileAttributes
import dev.brahmkshatriya.echo.extension.spotify.models.Releases
import dev.brahmkshatriya.echo.extension.spotify.models.SearchDesktop
import dev.brahmkshatriya.echo.extension.spotify.models.Sections
import dev.brahmkshatriya.echo.extension.spotify.models.Sections.ItemsItem
import dev.brahmkshatriya.echo.extension.spotify.models.Sections.SectionItem
import dev.brahmkshatriya.echo.extension.spotify.models.TracksV2
import dev.brahmkshatriya.echo.extension.spotify.models.UserFollowers
import dev.brahmkshatriya.echo.extension.spotify.models.UserProfileView
import spotify.extendedmetadata.audiofiles.AudioFilesExtensionProto.AudioFilesExtensionResponse
import spotify.extendedmetadata.metadata.ExtendedMetadataProto
import spotify.extendedmetadata.metadata.ExtendedMetadataProto.AudioFile.Format
import spotify.extendedmetadata.metadata.ExtendedMetadataProto.BatchedExtensionResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject


fun List<HomeFeed.Chip>.toTabs() = map {
    Tab(it.id!!, it.label?.transformedLabel!!)
}

fun HomeFeed.Home.toShelves(queries: Queries, cropCovers: Boolean): List<Shelf> {
    return sectionContainer?.sections?.toShelves(
        queries, cropCovers, greeting?.transformedLabel ?: "What's on your mind?"
    )!!
}

fun Sections.toShelves(
    queries: Queries,
    cropCovers: Boolean,
    emptyTitle: String? = null,
    token: String? = null,
): List<Shelf> {
    return items?.mapNotNull { item ->
        item.data ?: return@mapNotNull null
        if (item.data.typename == Sections.Typename.BrowseRelatedSectionData)
            return@mapNotNull item.toCategory(queries, cropCovers)

        val uri = item.uri ?: return@mapNotNull null
        val title = item.data.title?.transformedLabel ?: emptyTitle ?: ""
        val subtitle = item.data.subtitle?.transformedLabel
        when (item.data.typename) {
            null -> null
            Sections.Typename.BrowseGenericSectionData ->
                Shelf.Lists.Items(
                    id = uri,
                    title = title,
                    subtitle = subtitle,
                    list = item.sectionItems?.items?.mapNotNull { it.content.toMediaItem(cropCovers) }!!,
                )

            Sections.Typename.HomeGenericSectionData, Sections.Typename.HomeSpotlightSectionData ->
                Shelf.Lists.Items(
                    id = uri,
                    title = title,
                    subtitle = subtitle,
                    list = item.sectionItems?.items?.mapNotNull { it.content.toMediaItem(cropCovers) }!!,
                    more = if ((item.sectionItems.totalCount ?: 0) > 3) paged<Shelf> { offset ->
                        val sectionItem = queries.homeSection(uri, token, offset).json
                            .data.homeSections.sections.first().sectionItems
                        val next = sectionItem.pagingInfo?.nextOffset
                        sectionItem.items!!.mapNotNull {
                            it.content.toMediaItem(cropCovers)?.toShelf()
                        } to next
                    }.toFeed() else null
                )

            Sections.Typename.BrowseGridSectionData -> {
                Shelf.Lists.Categories(
                    id = uri,
                    title = title,
                    subtitle = subtitle,
                    list = item.sectionItems?.items?.mapNotNull {
                        it.toBrowseCategory(queries, cropCovers)
                    }!!,
                    type = Shelf.Lists.Type.Grid
                )
            }

            Sections.Typename.BrowseRelatedSectionData -> throw IllegalStateException()
            Sections.Typename.HomeShortsSectionData -> Shelf.Lists.Items(
                id = uri,
                title = title,
                subtitle = subtitle,
                list = item.sectionItems?.items?.mapNotNull { it.content.toMediaItem(cropCovers) }!!,
                type = Shelf.Lists.Type.Grid
            )

            Sections.Typename.HomeFeedBaselineSectionData -> item.sectionItems?.items
                ?.firstOrNull()?.content?.data?.toMediaItem(cropCovers)?.toShelf()

            Sections.Typename.BrowseUnsupportedSectionData -> null
            Sections.Typename.HomeOnboardingSectionDataV2 -> null
            Sections.Typename.HomeWatchFeedSectionData -> null
            Sections.Typename.HomeRecentlyPlayedSectionData -> null
            Sections.Typename.HomeNativeAdsSectionData -> null
            Sections.Typename.HomeYourDJSectionData -> null
            Sections.Typename.HomePromotionSectionData -> null
            Sections.Typename.Unknown -> null
        }
    }!!
}

private fun SectionItem.toCategory(api: Queries, cropCovers: Boolean): Shelf.Category? {
    val item = sectionItems?.items?.firstOrNull() ?: return null
    return item.toBrowseCategory(api, cropCovers)
}

fun ITrack.toTrack(
    cropCovers: Boolean,
    a: Album? = null,
    url: String? = null,
    added: Date? = null,
): Track? {
    val album = albumOfTrack?.toAlbum(cropCovers, name) ?: a
    return Track(
        id = uri ?: url ?: return null,
        title = name ?: return null,
        cover = album?.cover,
        artists = artists.toArtists(null, cropCovers),
        album = album,
        isExplicit = contentRating?.isExplicit() ?: false,
        duration = duration?.totalMilliseconds ?: trackDuration?.totalMilliseconds,
        plays = playcount?.toLong(),
        releaseDate = album?.releaseDate,
        playlistAddedDate = added
    )
}

fun Item.Playlist.toPlaylist(cropCovers: Boolean): Playlist? {
    val desc = description?.removeHtml()?.ifEmpty { null }
    return Playlist(
        id = uri ?: return null,
        title = name ?: return null,
        isEditable = false,
        isRadioSupported = false,
        description = desc,
        subtitle = ownerV2?.data?.name,
        cover = images?.items?.firstOrNull()?.toImageHolder(cropCovers),
        authors = listOfNotNull(ownerV2?.data?.toMediaItem(cropCovers) as? Artist),
        trackCount = content?.totalCount,
        duration = content?.toDuration(),
        creationDate = content?.items?.map { it.addedAt?.toDate() }?.maxByOrNull { it ?: Date(0) }
    )
}

private fun Item.Playlist.Content.toDuration(): Long? {
    val items = items ?: return null
    val average = items.run {
        sumOf { it.itemV2?.data?.trackDuration?.totalMilliseconds ?: 0 }.toDouble() / items.size
    }
    val count = totalCount ?: return null
    return (average * count).toLong()
}

fun Item.PseudoPlaylist.toPlaylist(cropCovers: Boolean): Playlist? {
    return Playlist(
        id = uri ?: return null,
        title = name ?: return null,
        isEditable = true,
        isRadioSupported = false,
        cover = image?.toImageHolder(cropCovers),
        trackCount = count
    )
}

fun Item.Playlist.toRadio(cropCovers: Boolean): Radio? {
    return Radio(
        id = uri ?: return null,
        title = name ?: return null,
        subtitle = description?.removeHtml(),
        cover = images?.items?.firstOrNull()?.toImageHolder(cropCovers)
    )
}

fun IAlbum.toAlbum(cropCovers: Boolean, n: String? = null): Album? {
    return Album(
        id = uri ?: return null,
        title = name ?: n ?: return null,
        subtitle = date?.year?.toString(),
        artists = artists.toArtists(null, cropCovers),
        cover = coverArt?.toImageHolder(cropCovers),
        trackCount = tracksV2?.totalCount,
        duration = tracksV2?.toDuration(),
        releaseDate = date?.toDate(),
        description = buildString {
            if (!courtesyLine.isNullOrBlank()) appendLine(courtesyLine)
            copyright?.items?.forEach {
                appendLine(it.text)
            }
        },
        label = label
    )
}

fun TracksV2.toDuration(): Long? {
    val average = items?.run {
        sumOf { it.track?.duration?.totalMilliseconds ?: 0 }.toDouble() / items.size
    } ?: return null
    val count = totalCount ?: return null
    return (average * count).toLong()
}

fun dev.brahmkshatriya.echo.extension.spotify.models.Date.toDate(): Date? {
    if (isoString != null) return isoString.toDate(precision)
    if (year != null) return Date(year)
    return null
}

private fun String.toDate(precision: String? = null): Date {
    val locale = Locale.ENGLISH
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", locale)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    val targetTime = runCatching { formatter.parse(this) }.getOrElse {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", locale).run {
            timeZone = TimeZone.getTimeZone("UTC")
            parse(this@toDate)
        }
    }
    val calendar = Calendar.getInstance()
    calendar.time = targetTime
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    if (precision == "YEAR") return Date(year)
    if (precision == "MONTH") return Date(year, month)
    return Date(year, month, day)
}

fun IArtist.toArtist(type: String? = null, cropCovers: Boolean): Artist? {
    return Artist(
        id = uri ?: return null,
        name = profile?.name ?: return null,
        cover = visuals?.avatarImage?.toImageHolder(cropCovers),
        subtitle = type,
        bio = profile?.biography?.text?.removeHtml(),
        extras = mapOf(
            "followers" to stats?.followers.toString()
        )
    )
}

fun Artists?.toArtists(subtitle: String? = null, cropCovers: Boolean) =
    this?.items?.mapNotNull { it.toArtist(subtitle, cropCovers) } ?: listOf()

fun Item.Podcast.toArtist(cropCovers: Boolean): Artist? {
    return Artist(
        id = uri ?: return null,
        name = name ?: return null,
        subtitle = "Podcast",
        cover = coverArt?.toImageHolder(cropCovers),
        isRadioSupported = false,
    )
}

fun Item.Audiobook.toAlbum(cropCovers: Boolean): Album? {
    return Album(
        id = uri ?: return null,
        title = name ?: return null,
        type = Album.Type.Book,
        cover = coverArt?.toImageHolder(cropCovers),
        description = description?.removeHtml(),
        subtitle = "Audiobook",
        releaseDate = publishDate?.toDate(),
    )
}

fun Artists.toShelf(
    id: String, title: String, subtitle: String? = null, more: Feed<Shelf>, cropCovers: Boolean,
): Shelf? {
    if (items.isNullOrEmpty()) return null
    return Shelf.Lists.Items(
        id = id,
        title = title,
        subtitle = subtitle,
        list = items.mapNotNull { it.toArtist(subtitle, cropCovers) },
        more = if (items.size > 3) more else null
    )
}

fun Albums.toShelf(id: String, title: String, more: Feed<Shelf>, cropCovers: Boolean): Shelf? {
    if (items.isNullOrEmpty()) return null
    return Shelf.Lists.Items(
        id = id,
        title = title,
        list = items.mapNotNull { it.releases?.items?.firstOrNull()?.toAlbum(cropCovers) },
        more = if (items.size > 3) more else null
    )
}

fun Releases.toShelf(id: String, title: String, more: Feed<Shelf>, cropCovers: Boolean): Shelf? {
    if (items.isNullOrEmpty()) return null
    return Shelf.Lists.Items(
        id = id,
        title = title,
        list = items.mapNotNull { it.toAlbum(cropCovers) },
        more = if (items.size > 3) more else null
    )
}

private fun ItemsV2.toShelf(
    id: String,
    title: String,
    more: Feed<Shelf>,
    cropCovers: Boolean,
): Shelf? {
    if (items.isNullOrEmpty()) return null
    return Shelf.Lists.Items(
        id = id,
        title = title,
        list = items.mapNotNull { it.toMediaItem(cropCovers) },
        more = if (items.size > 3) more else null
    )
}

fun TracksV2.toTrackShelf(id: String, title: String, cropCovers: Boolean): Shelf? {
    if (items.isNullOrEmpty()) return null
    return Shelf.Lists.Tracks(
        id = id,
        title = title,
        list = items.mapNotNull { it.track?.toTrack(cropCovers) }
    )
}

fun pagedItemsV2(
    cropCovers: Boolean, block: suspend (offset: Int) -> ItemsV2,
): PagedData<Shelf> {
    var count = 0L
    return paged { offset ->
        val res = block(offset)
        val items = res.items?.mapNotNull { it.toMediaItem(cropCovers)?.toShelf() } ?: emptyList()
        val total = res.totalCount ?: 0
        count += items.size
        items to if (count < total) count else null
    }
}

fun pagedArtists(
    cropCovers: Boolean, block: suspend (offset: Int) -> Artists,
): PagedData<Shelf> {
    var count = 0L
    return paged { offset ->
        val res = block(offset)
        val items =
            res.items?.mapNotNull { it.toArtist(null, cropCovers)?.toShelf() } ?: emptyList()
        val total = res.totalCount ?: 0
        count += items.size
        items to if (count < total) count else null
    }
}

fun pagedAlbums(
    cropCovers: Boolean, block: suspend (offset: Int) -> Albums,
): PagedData<Shelf> {
    var count = 0L
    return paged { offset ->
        val res = block(offset)
        val items = res.items?.map {
            it.releases?.items?.firstOrNull()?.toAlbum(cropCovers)!!
        } ?: emptyList()
        val total = res.totalCount ?: 0
        count += items.size
        items.map { it.toShelf() } to if (count < total) count else null
    }
}

fun IArtist.toShelves(queries: Queries, cropCovers: Boolean): List<Shelf> {
    val uri = uri ?: return emptyList()
    return listOfNotNull(
        discography?.topTracks?.toTrackShelf("${uri}_top_tracks", "Top Tracks", cropCovers),
        discography?.latest?.toAlbum(cropCovers)?.toShelf(),
        discography?.popularReleasesAlbums?.toShelf(
            "${uri}_popular",
            "Popular Albums",
            pagedAlbums(cropCovers) {
                queries.queryArtistDiscographyAll(uri, it)
                    .json.data.artistUnion.discography?.all!!
            }.toFeed(),
            cropCovers
        ),
        relatedContent?.featuringV2?.toShelf(
            "${uri}_featuring",
            "Featuring ${profile?.name}",
            pagedItemsV2(cropCovers) {
                queries.queryArtistFeaturing(uri, it)
                    .json.data.artistUnion.relatedContent?.featuringV2!!
            }.toFeed(),
            cropCovers
        ),
        discography?.albums?.toShelf(
            "${uri}_albums",
            "Albums",
            pagedAlbums(cropCovers) {
                queries.queryArtistDiscographyAlbums(uri, it)
                    .json.data.artistUnion.discography?.albums!!
            }.toFeed(),
            cropCovers
        ),
        discography?.singles?.toShelf(
            "${uri}_singles",
            "Singles",
            pagedAlbums(cropCovers) {
                queries.queryArtistDiscographySingles(uri, it)
                    .json.data.artistUnion.discography?.singles!!
            }.toFeed(),
            cropCovers
        ),
        discography?.compilations?.toShelf(
            "${uri}_compilations",
            "Compilations",
            pagedAlbums(cropCovers) {
                queries.queryArtistDiscographyCompilations(uri, it)
                    .json.data.artistUnion.discography?.compilations!!
            }.toFeed(),
            cropCovers
        ),
        profile?.playlistsV2?.toShelf(
            "${uri}_playlists",
            "Playlists",
            pagedItemsV2(cropCovers) {
                queries.queryArtistPlaylists(uri, it)
                    .json.data.artistUnion.profile?.playlistsV2!!
            }.toFeed(),
            cropCovers
        ),
        relatedContent?.appearsOn?.toShelf(
            "${uri}_appears_on",
            "Appears On",
            pagedAlbums(cropCovers) {
                queries.queryArtistAppearsOn(uri, it)
                    .json.data.artistUnion.relatedContent?.appearsOn!!
            }.toFeed(),
            cropCovers
        ),
        relatedContent?.discoveredOnV2?.toShelf(
            "${uri}_discovered_on",
            "Discovered On",
            pagedItemsV2(cropCovers) {
                queries.queryArtistDiscoveredOn(uri, it)
                    .json.data.artistUnion.relatedContent?.discoveredOnV2!!
            }.toFeed(),
            cropCovers
        ),
        relatedContent?.relatedArtists?.toShelf(
            "${uri}_related_artists",
            "Artist",
            "Artist",
            pagedArtists(cropCovers) {
                queries.queryArtistRelated(uri, it)
                    .json.data.artistUnion.relatedContent?.relatedArtists!!
            }.toFeed(),
            cropCovers
        )
    )
}

val likedPlaylist = Playlist(
    "spotify:collection:tracks",
    "Liked Songs",
    true,
    isRadioSupported = false,
    cover = "https://misc.scdn.co/liked-songs/liked-songs-300.png".toImageHolder()
)

fun Wrapper.toMediaItem(cropCovers: Boolean) = when (uri) {
    "spotify:user:@:collection" -> likedPlaylist
    else -> data?.toMediaItem(cropCovers)
}

fun Item.toMediaItem(cropCovers: Boolean): EchoMediaItem? {
    return when (this) {
        is Item.Album -> toAlbum(cropCovers)

        is Item.PreRelease -> Album(
            id = preReleaseContent?.uri ?: return null,
            title = preReleaseContent.name ?: return null,
            type = Album.Type.PreRelease,
            artists = preReleaseContent.artists.toArtists(null, cropCovers),
            cover = preReleaseContent.coverArt?.toImageHolder(cropCovers)
        )

        is Item.PseudoPlaylist -> toPlaylist(cropCovers)

        is Item.Playlist -> toPlaylist(cropCovers)

        is Item.Artist -> toArtist("Artist", cropCovers)

        is Item.Track -> toTrack(cropCovers)

        is Item.Episode -> Track(
            id = uri ?: return null,
            title = name ?: return null,
            type = Track.Type.Podcast,
            isRadioSupported = false,
            cover = coverArt?.toImageHolder(cropCovers),
            description = description?.removeHtml(),
            artists = listOfNotNull(podcastV2?.data?.toArtist(cropCovers)),
            isExplicit = contentRating?.isExplicit() ?: false,
            duration = duration?.totalMilliseconds,
            releaseDate = releaseDate?.toDate(),
        )

        is Item.Podcast -> toArtist(cropCovers)

        is Item.Chapter -> Track(
            id = uri ?: return null,
            title = name ?: return null,
            cover = coverArt?.toImageHolder(cropCovers),
            description = description?.removeHtml(),
            album = audiobookV2?.data?.toAlbum(cropCovers),
            isExplicit = contentRating?.isExplicit() ?: false,
            duration = duration?.totalMilliseconds,
        )

        is Item.Audiobook -> toAlbum(cropCovers)

        is Item.User -> Artist(
            id = uri ?: return null,
            name = displayName ?: name ?: username ?: return null,
            subtitle = "User",
            cover = avatar?.toImageHolder(cropCovers)
        )

        is Item.BrowseClientFeature -> null
        is Item.BrowseSectionContainer -> null
        is Item.Genre -> null
        is Item.Folder -> null
        is Item.Merch -> null
        is Item.NotFound -> null
        is Item.RestrictedContent -> null
        is Item.BrowseSpacesHub -> null
        is Item.PromotionDefaultNative -> null
        is Item.GenericError -> null
        is Item.DiscoveryFeed -> null
        is Item.Unknown -> null
    }
}

val htmlRegex = Regex("<[^>]*>")
private fun String?.removeHtml(): String? {
    return this?.replace(htmlRegex, "")
}

private fun Artwork.toImageHolder(cropCovers: Boolean): ImageHolder? {
    return this.sources.sortedBy { it.height }.middleOrNull()?.url?.toImageHolder(crop = cropCovers)
}

private fun <T> List<T>.middleOrNull() = getOrNull(size.ceilDiv(2)) ?: lastOrNull()
private fun Int.ceilDiv(other: Int) = ceil(this.toDouble() / other).toInt()

fun <T : Any> paged(
    load: suspend (offset: Int) -> Pair<List<T>, Long?>,
) = PagedData.Continuous { cont ->
    val offset = cont?.toInt() ?: 0
    val (data, next) = load(offset)
    Page(data, next?.toString())
}

fun ItemsItem.toBrowseCategory(queries: Queries, cropCovers: Boolean): Shelf.Category? {
    val uri = uri
    val item = content.data
    if (item !is Item.BrowseSectionContainer) return null
    return Shelf.Category(
        id = uri,
        title = item.data?.cardRepresentation?.title?.transformedLabel!!,
//        image = item.data.cardRepresentation.artwork?.toImageHolder(),
        backgroundColor = item.data.cardRepresentation.backgroundColor?.hex,
        feed = paged {
            val sections = queries.browsePage(uri, it).json.data.browse.sections
            val next = sections.pagingInfo?.nextOffset
            sections.toShelves(queries, cropCovers) to next
        }.toFeed()
    )
}

fun ProfileAttributes.toUser() = User(
    id = data.me.profile.uri!!,
    name = data.me.profile.name!!,
    cover = data.me.profile.avatar?.sources?.lastOrNull()?.url?.toImageHolder()
)

private val filteredCategory = listOf("PODCASTS", "AUDIOBOOKS")
fun SearchDesktop.SearchV2.toShelvesAndTabs(
    query: String,
    queries: Queries,
    cropCovers: Boolean,
): Pair<PagedData<Shelf>, List<Tab>> {
    val tabs = listOf(Tab("ALL", "All")) + chipOrder?.items?.map { chip ->
        Tab(chip.typeName!!, chip.typeName.lowercase().replaceFirstChar { it.uppercaseChar() })
    }!!

    val shelves = listOfNotNull(
        topResultsV2?.itemsV2?.firstOrNull()?.item?.toMediaItem(cropCovers)?.toShelf(),
        tracksV2?.items?.toTrackShelf("${query}_songs", "Songs", cropCovers),
        topResultsV2?.featured?.toMediaShelf("${query}_featured", "Featured", cropCovers),
        artists?.toMediaShelf("${query}_artists", "Artists", cropCovers),
        albumsV2?.toMediaShelf("${query}_albums", "Albums", cropCovers),
        playlists?.toMediaShelf("${query}_playlists", "Playlists", cropCovers),
        podcasts?.toMediaShelf("${query}_podcasts", "Podcasts", cropCovers),
        episodes?.toMediaShelf("${query}_episodes", "Episodes", cropCovers),
        users?.toMediaShelf("${query}_users", "Users", cropCovers),
        genres?.toCategoryShelf("${query}_genres", "Genres", queries, cropCovers)
    )
    return PagedData.Single { shelves } to tabs.filter { it.id !in filteredCategory }
}

private fun SearchDesktop.SearchItems.toCategoryShelf(
    id: String, title: String, queries: Queries, cropCovers: Boolean,
): Shelf? {
    if (items.isNullOrEmpty()) return null
    val items = items.mapNotNull { it.toGenreCategory(queries, cropCovers) }
    return Shelf.Lists.Categories(
        id = id,
        title = title,
        list = items,
        type = Shelf.Lists.Type.Grid
    )
}

fun SearchDesktop.SearchItems?.toItemShelves(cropCovers: Boolean): Pair<List<Shelf>, Long?> {
    if (this == null || items == null) return emptyList<Shelf>() to null
    val items = items
    val next = pagingInfo?.nextOffset
    return items.mapNotNull { item ->
        item.data?.toMediaItem(cropCovers)?.toShelf()
    } to next
}

fun SearchDesktop.TracksV2?.toItemShelves(
    cropCovers: Boolean,
): Pair<List<Shelf>, Long?> {
    if (this == null || items == null) return emptyList<Shelf>() to null
    val items = items
    val next = pagingInfo?.nextOffset
    return items.mapNotNull { item ->
        item.item?.data?.toTrack(cropCovers)?.toShelf()
    } to next
}

fun SearchDesktop.SearchItems?.toCategoryShelves(
    queries: Queries,
    cropCovers: Boolean,
): Pair<List<Shelf>, Long?> {
    if (this == null || items == null) return emptyList<Shelf>() to null
    val items = items
    val next = pagingInfo?.nextOffset
    return items.mapNotNull { item ->
        item.toGenreCategory(queries, cropCovers)
    } to next
}

private fun Wrapper.toGenreCategory(
    queries: Queries, cropCovers: Boolean,
): Shelf.Category? {
    val item = data
    if (item !is Item.Genre) return null
    val uri = item.uri!!
    return Shelf.Category(
        id = uri,
        title = item.name ?: return null,
        feed = paged {
            val sections = queries.browsePage(uri, it).json.data.browse.sections
            val next = sections.pagingInfo?.nextOffset
            sections.toShelves(queries, cropCovers) to next
        }.toFeed()
    )
}

private fun SearchDesktop.SearchItems.toMediaShelf(id: String, title: String, cropCovers: Boolean) =
    items?.toMediaShelf(id, title, cropCovers)

private fun List<Wrapper>?.toMediaShelf(id: String, title: String, cropCovers: Boolean): Shelf? {
    if (this.isNullOrEmpty()) return null
    return Shelf.Lists.Items(
        id = id,
        title = title,
        list = mapNotNull { it.toMediaItem(cropCovers) }
    )
}

private fun List<SearchDesktop.TrackWrapperWrapper>?.toTrackShelf(
    id: String,
    title: String,
    cropCovers: Boolean,
): Shelf? {
    if (this.isNullOrEmpty()) return null
    return Shelf.Lists.Tracks(
        id = id,
        title = title,
        list = mapNotNull { it.item?.data?.toTrack(cropCovers) }
    )
}

fun Canvas.toStreamable(): Streamable? {
    val canvas = data?.trackUnion?.canvas ?: return null
    if (!canvas.type.orEmpty().startsWith("VIDEO")) return null
    val url = canvas.url ?: return null
    return Streamable.background(
        id = url,
        title = canvas.type ?: return null,
        quality = 0
    )
}

fun Format.show(
    hasPremium: Boolean, supportsPlayPlay: Boolean, showWidevineStreams: Boolean,
) = when (this) {
    Format.FLAC_FLAC, Format.FLAC_FLAC_24BIT, Format.OGG_VORBIS_320 -> hasPremium && supportsPlayPlay
    Format.OGG_VORBIS_160 -> supportsPlayPlay
    Format.OGG_VORBIS_96 -> supportsPlayPlay
    Format.MP4_256, Format.MP4_256_DUAL -> hasPremium && showWidevineStreams
    Format.MP4_128, Format.MP4_128_DUAL -> showWidevineStreams
    else -> false
}

/** (fileId, numeric format) MP4 entry plus the media key it was listed under. */
data class MediaEntry(val fileId: String, val format: Int, val key: String)

/**
 * (fileId, format, mediaKey) MP4 entries from the track-playback/v1/media response.
 * Entries are tagged with the media key that lists them, because one response can
 * carry variants (normal song vs music-video audio) under different keys. Unknown
 * formats are dropped.
 */
fun JsonObject.mediaEntries(): List<MediaEntry> {
    val media = this["media"] as? JsonObject
    if (media != null) {
        return media.flatMap { (k, v) ->
            collectMediaPairs(v).map { it.copy(key = k) }
        }.distinct()
    }
    return collectMediaPairs(this).distinct()
}

private fun collectMediaPairs(el: JsonElement): List<MediaEntry> {
    val out = mutableListOf<MediaEntry>()
    fun collectOne(e: JsonElement) {
        if (e !is JsonObject) return
        val id = ((e["file_id"] ?: e["fileId"]) as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() } ?: return
        val format = (e["format"] as? JsonPrimitive)?.contentOrNull ?: return
        normalizeMediaFormat(format)?.let { out += MediaEntry(id, it, "") }
    }
    fun visit(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                (e["file_ids_mp4"] as? JsonArray)?.forEach(::collectOne)
                (e["file_ids_mp4_dual"] as? JsonArray)?.forEach(::collectOne)
                e.values.forEach(::visit)
            }
            is JsonArray -> e.forEach(::visit)
            else -> {}
        }
    }
    visit(el)
    return out
}

private fun normalizeMediaFormat(format: String): Int? {
    format.toIntOrNull()?.let { return it.takeIf { it == 10 || it == 11 || it == 12 || it == 13 } }
    return when (format.uppercase()) {
        "MP4_128" -> 10
        "MP4_256" -> 11
        "MP4_128_DUAL" -> 12
        "MP4_256_DUAL" -> 13
        else -> null
    }
}

/** One encoding's templates + profiles (manifests vary: templates at root or per-content). */
data class VideoContent(
    val encodingId: String,
    val initTemplate: String?,
    val segTemplate: String?,
    val profiles: List<String>,
)

/** Music-video manifest (manifests/v9/json supports_drm) in template form. */
data class VideoManifest(
    val sourceId: String,
    val baseUrl: String,
    val fileType: String,
    val segLenSec: Int,
    val durationMs: Long,
    val contents: List<VideoContent>,
    /** DRM-shaped keys (pssh/kid/license/protection) seen anywhere in the body. */
    val drmHints: List<String>,
    /** Manifest-declared Widevine license path, if present. */
    val licensePath: String?,
)

/** Lenient parse of the supports_drm JSON (encoding shapes vary). Null when unusable. */
fun parseVideoManifest(sourceId: String, raw: String): VideoManifest? = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    val bases = root["base_urls"]?.jsonArray
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val base = bases.firstOrNull { it.contains("video-") }
        ?: bases.firstOrNull() ?: return@runCatching null
    val content0 = root["contents"]?.jsonArray?.firstOrNull() as? JsonObject
    // Templates + scalars live at ROOT for video manifests — each content
    // inherits them unless it defines its own.
    fun JsonObject?.Str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull
    val fileType = root.Str("file_type") ?: content0.Str("file_type") ?: "mp4"
    fun profilesOf(c: JsonObject?): List<String> =
        (root["profiles"]?.jsonArray ?: c?.get("profiles")?.jsonArray)
            ?.mapNotNull { el ->
                if (el is JsonObject) {
                    ((el["id"] ?: el["profile_id"] ?: el["profileId"]) as? JsonPrimitive)?.contentOrNull
                } else (el as? JsonPrimitive)?.contentOrNull
            }?.takeIf { it.isNotEmpty() } ?: listOf("13")
    val contents = root["contents"]?.jsonArray
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { c ->
            val enc = c.Str("encoding_id") ?: return@mapNotNull null
            VideoContent(
                encodingId = enc,
                initTemplate = c.Str("initialization_template")
                    ?: root.Str("initialization_template"),
                segTemplate = c.Str("segment_template")
                    ?: root.Str("segment_template"),
                profiles = profilesOf(c),
            )
        }?.takeIf { it.isNotEmpty() }
        ?: listOf(
            VideoContent(
                encodingId = root.Str("encoding_id") ?: content0.Str("encoding_id") ?: "",
                initTemplate = root.Str("initialization_template"),
                segTemplate = root.Str("segment_template"),
                profiles = profilesOf(content0),
            )
        )
    val segLen = (root.Str("segment_length") ?: content0.Str("segment_length"))
        ?.toIntOrNull()?.takeIf { it in 1..30 } ?: 4
    val duration = (root.Str("end_time_millis") ?: content0.Str("end_time_millis"))
        ?.toLongOrNull() ?: 0L
    val hints = mutableListOf<String>()
    fun scan(e: JsonElement, depth: Int) {
        if (depth > 6) return
        if (e is JsonObject) for ((k, v) in e) {
            val kl = k.lowercase()
            if (v is JsonPrimitive && ("pssh" in kl || "kid" in kl || "license" in kl ||
                        "protect" in kl || "drm" in kl)
            ) {
                hints += "$k=${v.contentOrNull?.take(80)}"
            } else scan(v, depth + 1)
        } else if (e is JsonArray) e.forEach { scan(it, depth + 1) }
    }
    scan(root, 0)
    // The manifest declares its own license endpoint; prefer it over any
    // hardcoded path (robust to regional/rotation changes).
    val licPath = hints.firstOrNull {
        it.startsWith("license_server_endpoint=") && "widevine" in it
    }?.substringAfter('=')?.substringBefore('?')?.takeIf { it.startsWith("/") }
    VideoManifest(
        sourceId, base.trimEnd('/'), fileType, segLen,
        duration, contents, hints.distinct().take(12), licPath,
    )
}.getOrNull()

/** Fill {{profile_id}} / {{segment_timestamp}} / {{file_type}} placeholders. */
fun VideoManifest.segmentUrl(content: VideoContent, profile: String, timestamp: String): String? {
    val t = content.segTemplate ?: return null
    return baseUrl + "/" + t
        .replace("{{profile_id}}", profile)
        .replace("{{segment_timestamp}}", timestamp)
        .replace("{{file_type}}", fileType)
}

fun VideoManifest.initUrl(content: VideoContent, profile: String): String? {
    val t = content.initTemplate ?: return null
    return baseUrl + "/" + t
        .replace("{{profile_id}}", profile)
        .replace("{{file_type}}", fileType)
}

/** Widevine system id box scan (moov-aware only, so audio bytes can't false-positive). */
private val WV_SYSTEM_ID = byteArrayOf(
    0xED.toByte(), 0xEF.toByte(), 0x8B.toByte(), 0xA9.toByte(),
    0x79.toByte(), 0xD6.toByte(), 0x4A.toByte(), 0xCE.toByte(),
    0xA3.toByte(), 0xC8.toByte(), 0x27.toByte(), 0xDC.toByte(),
    0xD5.toByte(), 0x1D.toByte(), 0x21.toByte(), 0xED.toByte()
)

private fun eachBox(buf: ByteArray, start: Int, end: Int): List<Triple<String, Int, Int>> {
    val out = mutableListOf<Triple<String, Int, Int>>()
    var o = start
    while (o + 8 <= end) {
        val size = ((buf[o].toInt() and 0xFF) shl 24) or
            ((buf[o + 1].toInt() and 0xFF) shl 16) or
            ((buf[o + 2].toInt() and 0xFF) shl 8) or
            (buf[o + 3].toInt() and 0xFF)
        if (size < 8 || o + size > end) return out
        out += Triple(buf.copyOfRange(o + 4, o + 8).toString(Charsets.US_ASCII), o, o + size)
        o += size
    }
    return out
}

/** Full Widevine PSSH box bytes from an init segment (moov/pssh), or null. */
fun findInitPssh(init: ByteArray): ByteArray? {
    for ((t, s, e) in eachBox(init, 0, init.size)) {
        if (t != "moov") continue
        for ((t2, s2, e2) in eachBox(init, s + 8, e)) {
            if (t2 == "pssh" && e2 - s2 >= 32 &&
                init.copyOfRange(s2 + 12, s2 + 28).contentEquals(WV_SYSTEM_ID)
            ) return init.copyOfRange(s2, e2)
        }
    }
    return null
}

/** Track role from an init segment: "soun", "vide", or null. */
fun initTrackKind(init: ByteArray): String? {
    for ((t, s, e) in eachBox(init, 0, init.size)) {
        if (t != "moov") continue
        for ((t2, s2, e2) in eachBox(init, s + 8, e)) {
            if (t2 != "trak") continue
            for ((t3, s3, e3) in eachBox(init, s2 + 8, e2)) {
                if (t3 != "mdia") continue
                for ((t4, s4, e4) in eachBox(init, s3 + 8, e3)) {
                    if (t4 == "hdlr" && e4 - s4 >= 20) {
                        return init.copyOfRange(s4 + 16, s4 + 20).toString(Charsets.US_ASCII)
                    }
                }
            }
        }
    }
    return null
}

/** Video codec string from an init segment (avc1.PPCCLL via avcC), or null.
 * CENC uses the encrypted sample entry (encv, not avc1); children start 86
 * bytes in (8 box header + 78 entry payload), verified against real inits. */
fun initVideoCodec(init: ByteArray): String? = runCatching {
    for ((t, s, e) in eachBox(init, 0, init.size)) {
        if (t != "moov") continue
        for ((t2, s2, e2) in eachBox(init, s + 8, e)) {
            if (t2 != "trak") continue
            for ((t3, s3, e3) in eachBox(init, s2 + 8, e2)) {
                if (t3 != "mdia") continue
                for ((t4, s4, e4) in eachBox(init, s3 + 8, e3)) {
                    if (t4 != "minf") continue
                    for ((t5, s5, e5) in eachBox(init, s4 + 8, e4)) {
                        if (t5 != "stbl") continue
                        for ((t6, s6, e6) in eachBox(init, s5 + 8, e5)) {
                            // stsd children start past version/flags + entry_count.
                            if (t6 != "stsd") continue
                            for ((t7, s7, e7) in eachBox(init, s6 + 16, e6)) {
                                if (t7 == "avc1" || t7 == "encv") {
                                    for ((t8, s8, e8) in eachBox(init, s7 + 86, e7)) {
                                        if (t8 == "avcC" && e8 - s8 >= 12) {
                                            val b = init.copyOfRange(s8 + 9, s8 + 12)
                                            return@runCatching "avc1.%02x%02x%02x".format(
                                                b[0], b[1], b[2]
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    null
}.getOrNull()

/** Approximate bandwidth from an avc1 codec string (level byte drives it).
 * Lets the MPD ladder sort correctly even when the manifest orders profiles
 * any-which-way (proven: ids 5,4 carried the LOWEST levels). */
fun avcBandwidth(codec: String): Int {
    val level = runCatching {
        codec.substringAfter("avc1.").take(6).substring(4, 6).toInt(16)
    }.getOrNull() ?: return 2000000
    return when {
        level <= 0x0d -> 500000
        level <= 0x15 -> 1200000
        level <= 0x1e -> 2000000
        level <= 0x1f -> 3000000
        level <= 0x28 -> 6000000
        else -> 8000000
    }
}

private fun xmlEsc(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace("\"", "&quot;")

/**
 * Static MPD with per-representation SegmentTemplates: every playable
 * (content, profile) pair becomes a Representation under its role's
 * AdaptationSet, all sharing the one Widevine KID (browser-verified:
 * identical PSSH across inits). Exo adapts across qualities AND survives a
 * dead profile. Playback = stock Exo DASH + video/license grants.
 */
data class MvRep(
    val content: VideoContent,
    val profile: String,
    /** "vide" or "soun" */
    val kind: String,
    val codec: String,
    val bandwidth: Int,
)

fun buildVideoMpd(
    manifest: VideoManifest,
    reps: List<MvRep>,
    psshB64: String,
): String {
    val durS = (manifest.durationMs / 1000.0).let { "PT${it}S" }
    // Segments are named by SECOND offsets (0, 4, 8 … 40 — browser-verified,
    // and the 4s-play-then-404 proved $Number$ wrong), so $Time$ addressing
    // with an explicit timeline. r covers the whole period.
    val total = kotlin.math.ceil(manifest.durationMs / 1000.0 / manifest.segLenSec)
        .toInt().coerceAtLeast(1)
    fun repXml(rep: MvRep, id: String): String {
        val initT = rep.content.initTemplate ?: return ""
        val segT = rep.content.segTemplate ?: return ""
        val init = xmlEsc(
            manifest.baseUrl + "/" + initT
                .replace("{{profile_id}}", rep.profile)
                .replace("{{file_type}}", manifest.fileType)
        )
        val media = xmlEsc(
            manifest.baseUrl + "/" + segT
                .replace("{{profile_id}}", rep.profile)
                .replace("{{segment_timestamp}}", "\$Time\$")
                .replace("{{file_type}}", manifest.fileType)
        )
        return "<Representation id=\"$id\" bandwidth=\"${rep.bandwidth}\" codecs=\"${rep.codec}\">" +
            "<SegmentTemplate timescale=\"1\" initialization=\"$init\" media=\"$media\">" +
            "<SegmentTimeline><S t=\"0\" d=\"${manifest.segLenSec}\" r=\"${total - 1}\"/></SegmentTimeline>" +
            "</SegmentTemplate></Representation>"
    }
    val sets = StringBuilder()
    // Video AdaptationSet first (Exo prefers first set for initial selection).
    var vi = 0
    for (rep in reps.filter { it.kind == "vide" }) {
        if (vi == 0) {
            sets.append("<AdaptationSet mimeType=\"video/mp4\">")
            sets.append("<ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dc0d51d21e\">")
            sets.append("<cenc:pssh>$psshB64</cenc:pssh></ContentProtection>")
        }
        sets.append(repXml(rep, "v$vi"))
        vi++
    }
    if (vi > 0) sets.append("</AdaptationSet>")
    var ai = 0
    for (rep in reps.filter { it.kind == "soun" }) {
        if (ai == 0) {
            sets.append("<AdaptationSet mimeType=\"audio/mp4\" lang=\"und\">")
            sets.append("<ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dc0d51d21e\">")
            sets.append("<cenc:pssh>$psshB64</cenc:pssh></ContentProtection>")
        }
        sets.append(repXml(rep, "a$ai"))
        ai++
    }
    if (ai > 0) sets.append("</AdaptationSet>")
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<MPD type=\"static\" mediaPresentationDuration=\"$durS\" minBufferTime=\"PT4S\" " +
        "profiles=\"urn:mpeg:dash:profile:isoff-live:2011\" " +
        "xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:cenc=\"urn:mpeg:cenc:2013\">" +
        "<Period>$sets</Period></MPD>"
}

/**
 * Exactly-32-hex strings (manifest source ids) anywhere in the media JSON,
 * with key context. Audio fileIds are 40-hex and never match.
 */
fun JsonObject.videoSourceCandidates(): List<String> {
    val out = LinkedHashSet<String>()
    val hex32 = Regex("[0-9a-f]{32}")
    fun visit(e: JsonElement, path: String) {
        when (e) {
            is JsonObject -> for ((k, v) in e) visit(v, "$path.$k")
            is JsonArray -> e.forEachIndexed { i, v -> visit(v, "$path[$i]") }
            is JsonPrimitive -> if (e.isString) {
                val s = e.contentOrNull ?: return
                if (s.length == 32 && hex32.matches(s)) out += "$s@$path"
            }
        }
    }
    visit(this, "$")
    return out.toList()
}

private fun BatchedExtensionResponse.extensionBytes(
    kind: ExtendedMetadataProto.ExtensionKind
): ByteArray? =
    extendedMetadataList
        .firstOrNull { it.extensionKind == kind }
        ?.extensionDataList
        ?.firstOrNull()
        ?.takeIf { it.hasExtensionData() }
        ?.extensionData?.value?.toByteArray()

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

fun BatchedExtensionResponse.toTrack(
    hasPremium: Boolean,
    supportsPlayPlay: Boolean,
    showWidevineStreams: Boolean,
    canvas: Streamable?,
    mediaEntries: List<MediaEntry> = emptyList(),
    showMv: Boolean = true,
    preferMv: Boolean = false,
    mvSourceId: String? = null,
): Track {

    val trackBytes = extensionBytes(ExtendedMetadataProto.ExtensionKind.TRACK_V4) ?: ByteArray(0)
    val trackProto = if (trackBytes.isNotEmpty())
        ExtendedMetadataProto.Track.parseFrom(trackBytes) else null

    val audioFilesBytes =
        extensionBytes(ExtendedMetadataProto.ExtensionKind.AUDIO_FILES) ?: ByteArray(0)
    val audioFiles = if (audioFilesBytes.isNotEmpty())
        AudioFilesExtensionResponse.parseFrom(audioFilesBytes) else null

    val gid = trackProto?.takeIf { it.hasGid() }?.gid?.toByteArray()
    val id = "spotify:track:${Base62.encode(gid?.toHex() ?: "")}"
    val title = trackProto?.name ?: ""

    // Spotify relinks some restricted tracks to alternative tracks. Their
    // playable file IDs can differ from AUDIO_FILES, which can still describe
    // the original restricted track; requesting a key for one of those old
    // IDs results in HTTP 403. Prefer files of an unrestricted alternative
    // when the primary TRACK_V4 is restricted or has no direct audio files.
    val primaryFiles = trackProto?.fileList.orEmpty().filter { it.hasFileId() }
    val alternativeFiles = trackProto?.alternativeList.orEmpty()
        .asSequence()
        .filter { it.hasGid() && it.gid != trackProto?.gid }
        .sortedBy { it.restrictionCount != 0 }
        .flatMap { it.fileList.asSequence() }
        .filter { it.hasFileId() }
        .toList()
    val needsRelinking = trackProto != null &&
        (trackProto.restrictionCount > 0 || primaryFiles.isEmpty())
    val files = when {
        needsRelinking && alternativeFiles.isNotEmpty() -> alternativeFiles
        audioFiles != null && audioFiles.filesCount > 0 -> audioFiles.filesList.map { it.file }
        primaryFiles.isNotEmpty() -> primaryFiles
        else -> alternativeFiles
    }

    // A FLAC license can be refused even on Premium accounts. Retain the
    // best available Vorbis file so FLAC failures do not terminate playback.
    val vorbisFallback = AudioStreamFallback.bestVorbisFile(
        files.filter { it.format.show(hasPremium, supportsPlayPlay, showWidevineStreams) }
    )

    val streamables = mutableListOf<Streamable>()

    // Web-player MP4 entries (if Widevine enabled)
    if (mediaEntries.isNotEmpty() && showWidevineStreams) {
        val own = mediaEntries.filter { it.key.isBlank() || it.key == id }
        val pool = if (own.isNotEmpty()) own else mediaEntries
        val candidates = pool.filter { e ->
            (e.format == 10 || e.format == 12 || hasPremium) && showWidevineStreams
        }
        val chosen = candidates.firstOrNull()
        chosen?.let { e ->
            val formatName = AudioFormat.name(e.format)
            streamables.add(
                Streamable.server(
                    id = e.fileId,
                    quality = AudioFormat.quality(e.format),
                    title = formatName.replace("_", " "),
                    extras = mapOf(
                        "fileId" to e.fileId,
                        "formatNum" to e.format.toString(),
                        "formatName" to formatName,
                        "gid" to (gid?.toHex() ?: ""),
                        "durMs" to (trackProto?.duration ?: 0).toString(),
                    )
                )
            )
        }
    }

    // PlayPlay / standard audio files
    files.forEach { file ->
        file.takeIf { it.hasFileId() && it.format.show(hasPremium, supportsPlayPlay, showWidevineStreams) }
            ?.let {
                val formatName = file.format.name
                val formatNum = file.format.number
                val fileIdHex = file.fileId.toByteArray().toHex()
                streamables.add(
                    Streamable.server(
                        id = fileIdHex,
                        quality = AudioFormat.quality(formatNum),
                        title = formatName.replace("_", " "),
                        extras = AudioStreamFallback.attachFallback(mapOf(
                            "fileId" to fileIdHex,
                            "formatNum" to formatNum.toString(),
                            "formatName" to formatName,
                            "gid" to (gid?.toHex() ?: ""),
                            "durMs" to (trackProto?.duration ?: 0).toString(),
                        ), vorbisFallback.takeIf { AudioStreamFallback.isFlac(formatNum) })
                    )
                )
            }
    }

    // Music Video (picture)
    val maxAudioQuality = streamables.maxOfOrNull { it.quality } ?: 0
    val videoRow = if (showMv && mvSourceId != null) {
        Streamable.server(
            id = "mv:$mvSourceId",
            quality = if (preferMv) maxOf(maxAudioQuality + 1, 3) else 0,
            title = "Music Video",
            extras = mapOf(
                "mvSource" to mvSourceId,
                "dur" to (trackProto?.duration ?: 0).toString(),
                "gid" to (gid?.toHex() ?: ""),
            ),
        )
    } else null

    if (preferMv && videoRow != null) {
        streamables.add(0, videoRow)
    } else if (videoRow != null) {
        streamables.add(videoRow)
    }

    val alb = trackProto?.takeIf { it.hasAlbum() }?.album?.let { album ->
        if (!album.hasGid()) return@let null
        val albumGid = album.gid.toByteArray()
        val albumId = Base62.encode(albumGid.toHex())
        Album(
            id = "spotify:album:$albumId",
            title = album.name.ifEmpty { return@let null },
            cover = album.takeIf { it.hasCoverGroup() }?.coverGroup?.imageList
                ?.lastOrNull()?.takeIf { it.hasFileId() }?.fileId?.toByteArray()?.let {
                    "https://i.scdn.co/image/${it.toHex()}".toImageHolder()
                },
            artists = album.artistList.mapNotNull {
                if (!it.hasGid()) return@mapNotNull null
                val artistGid = it.gid.toByteArray()
                val artistId = Base62.encode(artistGid.toHex())
                Artist("spotify:artist:$artistId", it.name.ifEmpty { return@mapNotNull null })
            },
            label = if (album.hasLabel()) album.label else null,
        )
    }

    val artists = (trackProto?.artistWithRoleList ?: emptyList()).mapNotNull {
        if (!it.hasArtistGid()) return@mapNotNull null
        val artistGid = it.artistGid.toByteArray()
        val artistId = Base62.encode(artistGid.toHex())
        val name = it.artistName.ifEmpty { return@mapNotNull null }
        val roleName = it.role.name.removePrefix("ARTIST_ROLE_").replace('_', ' ').lowercase()
            .replaceFirstChar { c -> c.uppercase() }
        Artist("spotify:artist:$artistId", name, subtitle = roleName)
    }

    return Track(
        id = id,
        title = title,
        cover = alb?.cover,
        streamables = if (canvas != null) streamables + canvas else streamables,
        duration = (trackProto?.duration ?: 0).toLong(),
        artists = artists,
        album = alb,
        albumOrderNumber = (trackProto?.number ?: 0).toLong(),
        albumDiscNumber = (trackProto?.discNumber ?: 0).toLong(),
        isrc = trackProto?.externalIdList?.firstOrNull()?.id,
        releaseDate = alb?.releaseDate,
        description = trackProto?.takeIf { it.hasAlbum() && it.album.hasLabel() }?.album?.label,
        isExplicit = trackProto?.explicit ?: false,
    )
}

fun UserProfileView.toArtist(): Artist? {
    return Artist(
        id = uri ?: return null,
        name = name ?: return null,
        cover = imageUrl?.toImageHolder(),
        bio = "Total Public Playlists Count : $totalPublicPlaylistsCount",
        extras = mapOf(
            "followers" to followersCount?.toString().orEmpty(),
        )
    )
}

fun UserProfileView.toShelf(): Shelf? {
    if (publicPlaylists.isNullOrEmpty()) return null
    val playlists = publicPlaylists.mapNotNull {
        val owner = it.ownerUri?.let { uri ->
            Artist(id = uri, name = it.ownerName!!)
        }
        Playlist(
            id = it.uri ?: return@mapNotNull null,
            title = it.name ?: return@mapNotNull null,
            false,
            isRadioSupported = false,
            cover = it.imageUrl?.toImageHolder(),
            authors = listOfNotNull(owner)
        )
    }
    return Shelf.Lists.Items("${uri}_public_playlists", "Public Playlists", playlists)
}

fun UserFollowers.toShelf(id: String, title: String): Shelf? {
    if (profiles.isNullOrEmpty()) return null
    val users = profiles.mapNotNull {
        Artist(
            id = it.uri ?: return@mapNotNull null,
            name = it.name ?: return@mapNotNull null,
            cover = it.imageUrl?.toImageHolder()
        )
    }
    return Shelf.Lists.Items(id, title, users)
}

fun pagedLibrary(
    queries: Queries, filter: String? = null, folderUri: String? = null, cropCovers: Boolean,
) = paged { offset ->
    val res = queries.libraryV3(offset, filter, folderUri)
    val library = res.json.data?.me?.libraryV3!!
    val shelves = library.items.mapNotNull { it.toShelf(queries, cropCovers) }
    val page = library.pagingInfo!!
    val next = page.offset!! + page.limit!!
    shelves to if (library.totalCount!! > next) next else null
}

fun LibraryV3.Item.toShelf(queries: Queries, cropCovers: Boolean): Shelf? {
    return if (item?.typename == "LibraryFolderResponseWrapper") {
        val folder = item.data as Item.Folder
        val folderUri = folder.uri ?: return null
        Shelf.Category(
            folderUri, folder.name!!,
            pagedLibrary(queries, null, folderUri, cropCovers).toFeed()
        )
    } else item?.data?.toMediaItem(cropCovers)?.toShelf()
}

fun editablePlaylists(
    queries: Queries,
    track: String?,
    folderUri: String? = null,
    cropCovers: Boolean,
): PagedData<Pair<Playlist, Boolean>> =
    paged { offset ->
        val res = queries.editablePlaylists(
            offset, folderUri, track ?: "spotify:track:3z5lNLYtGC6LmvrxSbCQgd"
        ).json.data.me.editablePlaylists!!
        val playlists = res.items.mapNotNull {
            when (val item = it.item.data) {
                is Item.PseudoPlaylist -> {
                    val curated = it.curates ?: return@mapNotNull null
                    val playlist = item.toPlaylist(cropCovers) ?: return@mapNotNull null
                    listOfNotNull(playlist to curated)
                }

                is Item.Playlist -> {
                    val curated = it.curates ?: return@mapNotNull null
                    val playlist = item.toPlaylist(cropCovers) ?: return@mapNotNull null
                    listOfNotNull(playlist to curated)
                }

                is Item.Folder -> {
                    val uri = item.uri ?: return@mapNotNull null
                    editablePlaylists(queries, track, uri, cropCovers).loadAll()
                }

                else -> null
            }
        }.flatten()
        val page = res.pagingInfo!!
        val next = page.offset!! + page.limit!!
        playlists to if (res.totalCount!! > next) next else null
    }
