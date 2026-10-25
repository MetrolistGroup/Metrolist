package com.metrolist.shazamkit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShazamRequestJson(
    @SerialName("geolocation")
    val geolocation: Geolocation,
    @SerialName("signature")
    val signature: Signature,
    @SerialName("timestamp")
    val timestamp: Long,
    @SerialName("timezone")
    val timezone: String
) {
    @Serializable
    data class Geolocation(
        @SerialName("altitude")
        val altitude: Double,
        @SerialName("latitude")
        val latitude: Double,
        @SerialName("longitude")
        val longitude: Double
    )

    @Serializable
    data class Signature(
        @SerialName("samplems")
        val samplems: Long,
        @SerialName("timestamp")
        val timestamp: Long,
        @SerialName("uri")
        val uri: String
    )
}

@Serializable
data class ShazamResponseJson(
    @SerialName("matches")
    val matches: List<Match?>? = null,
    @SerialName("location")
    val location: Location? = null,
    @SerialName("timestamp")
    val timestamp: Long? = null,
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("track")
    val track: Track? = null,
    @SerialName("tagid")
    val tagid: String? = null
) {
    @Serializable
    data class Match(
        @SerialName("id")
        val id: String? = null,
        @SerialName("offset")
        val offset: Double? = null,
        @SerialName("timeskew")
        val timeskew: Double? = null,
        @SerialName("frequencyskew")
        val frequencyskew: Double? = null
    )

    @Serializable
    data class Location(
        @SerialName("latitude")
        val latitude: Double? = null,
        @SerialName("longitude")
        val longitude: Double? = null,
        @SerialName("altitude")
        val altitude: Double? = null,
        @SerialName("accuracy")
        val accuracy: Double? = null
    )

    @Serializable
    data class Track(
        @SerialName("layout")
        val layout: String? = null,
        @SerialName("type")
        val type: String? = null,
        @SerialName("key")
        val key: String? = null,
        @SerialName("title")
        val title: String? = null,
        @SerialName("subtitle")
        val subtitle: String? = null,
        @SerialName("images")
        val images: Images? = null,
        @SerialName("share")
        val share: Share? = null,
        @SerialName("hub")
        val hub: Hub? = null,
        @SerialName("sections")
        val sections: List<Section?>? = null,
        @SerialName("url")
        val url: String? = null,
        @SerialName("artists")
        val artists: List<Artist?>? = null,
        @SerialName("isrc")
        val isrc: String? = null,
        @SerialName("genres")
        val genres: Genres? = null,
        @SerialName("relatedtracksurl")
        val relatedtracksurl: String? = null,
        @SerialName("albumadamid")
        val albumadamid: String? = null
    ) {
        @Serializable
        data class Images(
            @SerialName("background")
            val background: String? = null,
            @SerialName("coverart")
            val coverart: String? = null,
            @SerialName("coverarthq")
            val coverarthq: String? = null,
            @SerialName("joecolor")
            val joecolor: String? = null
        )

        @Serializable
        data class Share(
            @SerialName("subject")
            val subject: String? = null,
            @SerialName("text")
            val text: String? = null,
            @SerialName("href")
            val href: String? = null,
            @SerialName("image")
            val image: String? = null,
            @SerialName("twitter")
            val twitter: String? = null,
            @SerialName("html")
            val html: String? = null,
            @SerialName("avatar")
            val avatar: String? = null,
            @SerialName("snapchat")
            val snapchat: String? = null
        )

        @Serializable
        data class Hub(
            @SerialName("type")
            val type: String? = null,
            @SerialName("image")
            val image: String? = null,
            @SerialName("actions")
            val actions: List<Action?>? = null,
            @SerialName("options")
            val options: List<Option?>? = null,
            @SerialName("providers")
            val providers: List<Provider?>? = null,
            @SerialName("explicit")
            val explicit: Boolean? = null,
            @SerialName("displayname")
            val displayname: String? = null
        ) {
            @Serializable
            data class Action(
                @SerialName("name")
                val name: String? = null,
                @SerialName("type")
                val type: String? = null,
                @SerialName("id")
                val id: String? = null,
                @SerialName("uri")
                val uri: String? = null
            )

            @Serializable
            data class Option(
                @SerialName("caption")
                val caption: String? = null,
                @SerialName("actions")
                val actions: List<OptionAction?>? = null,
                @SerialName("beacondata")
                val beacondata: Beacondata? = null,
                @SerialName("image")
                val image: String? = null,
                @SerialName("type")
                val type: String? = null,
                @SerialName("listcaption")
                val listcaption: String? = null,
                @SerialName("overflowimage")
                val overflowimage: String? = null,
                @SerialName("colouroverflowimage")
                val colouroverflowimage: Boolean? = null,
                @SerialName("providername")
                val providername: String? = null
            ) {
                @Serializable
                data class OptionAction(
                    @SerialName("name")
                    val name: String? = null,
                    @SerialName("type")
                    val type: String? = null,
                    @SerialName("uri")
                    val uri: String? = null,
                    @SerialName("id")
                    val id: String? = null
                )

                @Serializable
                data class Beacondata(
                    @SerialName("type")
                    val type: String? = null,
                    @SerialName("providername")
                    val providername: String? = null
                )
            }

            @Serializable
            data class Provider(
                @SerialName("caption")
                val caption: String? = null,
                @SerialName("images")
                val images: ProviderImages? = null,
                @SerialName("actions")
                val actions: List<ProviderAction?>? = null,
                @SerialName("type")
                val type: String? = null
            ) {
                @Serializable
                data class ProviderImages(
                    @SerialName("overflow")
                    val overflow: String? = null,
                    @SerialName("default")
                    val default: String? = null
                )

                @Serializable
                data class ProviderAction(
                    @SerialName("name")
                    val name: String? = null,
                    @SerialName("type")
                    val type: String? = null,
                    @SerialName("uri")
                    val uri: String? = null
                )
            }
        }

        @Serializable
        data class Section(
            @SerialName("type")
            val type: String? = null,
            @SerialName("metapages")
            val metapages: List<Metapage?>? = null,
            @SerialName("tabname")
            val tabname: String? = null,
            @SerialName("metadata")
            val metadata: List<Metadata?>? = null,
            @SerialName("url")
            val url: String? = null,
            @SerialName("text")
            val text: List<String>? = null,
        ) {
            @Serializable
            data class Metapage(
                @SerialName("image")
                val image: String? = null,
                @SerialName("caption")
                val caption: String? = null
            )

            @Serializable
            data class Metadata(
                @SerialName("title")
                val title: String? = null,
                @SerialName("text")
                val text: String? = null
            )
        }

        @Serializable
        data class Artist(
            @SerialName("id")
            val id: String? = null,
            @SerialName("adamid")
            val adamid: String? = null
        )

        @Serializable
        data class Genres(
            @SerialName("primary")
            val primary: String? = null
        )
    }
}

data class RecognitionResult(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtUrl: String?,
    val coverArtHqUrl: String?,
    val genre: String?,
    val releaseDate: String?,
    val label: String?,
    val lyrics: List<String>?,
    val shazamUrl: String?,
    val appleMusicUrl: String?,
    val spotifyUrl: String?,
    val isrc: String?,
    val youtubeVideoId: String? = null
)

sealed class RecognitionStatus {
    data object Ready : RecognitionStatus()
    data object Listening : RecognitionStatus()
    data object Processing : RecognitionStatus()
    data class Success(val result: RecognitionResult) : RecognitionStatus()
    data class NoMatch(val message: String = "No matches found") : RecognitionStatus()
    data class Error(val message: String) : RecognitionStatus()
}
