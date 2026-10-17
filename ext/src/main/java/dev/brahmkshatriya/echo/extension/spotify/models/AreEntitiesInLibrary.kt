package dev.brahmkshatriya.echo.extension.spotify.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AreEntitiesInLibrary (
    val data: Data? = null
) {

    @Serializable
    data class Data(
        val lookup: List<Lookup>? = null
    )

    @Serializable
    data class Lookup(
        @SerialName("__typename")
        val typename: String? = null,
        // Flat shape: saved/uri sit directly on the lookup entry.
        val uri: String? = null,
        val saved: Boolean? = null,
        // Nested shape: entity fetch under data.
        val data: LookupData? = null
    ) {
        /** Saved flag across both shapes; null when absent either way. */
        fun isSaved(): Boolean? = saved ?: data?.saved
    }

    @Serializable
    data class LookupData(
        @SerialName("__typename")
        val typename: String? = null,
        val saved: Boolean? = null
    )
}
