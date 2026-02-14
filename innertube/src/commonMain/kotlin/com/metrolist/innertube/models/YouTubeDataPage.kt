package com.metrolist.innertube.models


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YouTubeDataPage(
    @SerialName("contents")
    val contents: Contents? = null,
) {
    @Serializable
    data class Contents(
        @SerialName("twoColumnWatchNextResults")
        val twoColumnWatchNextResults: TwoColumnWatchNextResults? = null,
    ) {
        @Serializable
        data class TwoColumnWatchNextResults(
            @SerialName("results")
            val results: Results? = null,
        ) {
            @Serializable
            data class Results(
                @SerialName("results")
                val results: Results? = null,
            ) {
                @Serializable
                data class Results(
                    @SerialName("contents")
                    val content: List<Content?>? = null,
                ) {
                    @Serializable
                    data class Content(
                        @SerialName("videoPrimaryInfoRenderer")
                        val videoPrimaryInfoRenderer: VideoPrimaryInfoRenderer? = null,
                        @SerialName("videoSecondaryInfoRenderer")
                        val videoSecondaryInfoRenderer: VideoSecondaryInfoRenderer? = null,
                        @SerialName("itemSectionRenderer")
                        val itemSectionRenderer: ItemSectionRenderer? = null,
                    ) {
                        @Serializable
                        data class ItemSectionRenderer(
                            @SerialName("contents")
                            val contents: List<Content?>? = null,
                        ) {
                            @Serializable
                            data class Content(
                                @SerialName("continuationItemRenderer")
                                val continuationItemRenderer: ContinuationItemRenderer? = null,
                            ) {
                                @Serializable
                                data class ContinuationItemRenderer(
                                    @SerialName("trigger")
                                    val trigger: String? = null,
                                    @SerialName("continuationEndpoint")
                                    val continuationEndpoint: ContinuationEndpoint? = null,
                                ) {
                                    @Serializable
                                    data class ContinuationEndpoint(
                                        @SerialName("clickTrackingParams")
                                        val clickTrackingParams: String? = null,
                                        @SerialName("continuationCommand")
                                        val continuationCommand: ContinuationCommand? = null,
                                    ) {
                                        @Serializable
                                        data class ContinuationCommand(
                                            @SerialName("token")
                                            val token: String? = null,
                                            @SerialName("request")
                                            val request: String? = null,
                                        )
                                    }
                                }
                            }
                        }

                        @Serializable
                        data class VideoPrimaryInfoRenderer(
                            @SerialName("title")
                            val title: Title? = null,
                            @SerialName("viewCount")
                            val viewCount: ViewCount? = null,
                            @SerialName("dateText")
                            val dateText: DateText? = null,
                        ) {
                            @Serializable
                            data class Title(
                                @SerialName("runs")
                                val runs: List<Run>? = null,
                            ) {
                                @Serializable
                                data class Run(
                                    @SerialName("text")
                                    val text: String? = null,
                                )
                            }

                            @Serializable
                            data class ViewCount(
                                @SerialName("videoViewCountRenderer")
                                val videoViewCountRenderer: VideoViewCountRenderer? = null,
                            ) {
                                @Serializable
                                data class VideoViewCountRenderer(
                                    @SerialName("viewCount")
                                    val viewCount: VideoViewCountRenderer.ViewCount? = null,
                                ) {
                                    @Serializable
                                    data class ViewCount(
                                        @SerialName("simpleText")
                                        val simpleText: String? = null,
                                    )
                                }
                            }

                            @Serializable
                            data class DateText(
                                @SerialName("simpleText")
                                val simpleText: String? = null,
                            )
                        }

                        @Serializable
                        data class VideoSecondaryInfoRenderer(
                            @SerialName("owner")
                            val owner: Owner? = null,
                            @SerialName("attributedDescription")
                            val attributedDescription: AttributedDescription? = null,
                        ) {
                            @Serializable
                            data class AttributedDescription(
                                @SerialName("content")
                                val content: String? = null,
                            )

                            @Serializable
                            data class Owner(
                                @SerialName("videoOwnerRenderer")
                                val videoOwnerRenderer: VideoOwnerRenderer? = null,
                            ) {
                                @Serializable
                                data class VideoOwnerRenderer(
                                    @SerialName("thumbnail")
                                    val thumbnail: Thumbnail? = null,
                                    @SerialName("subscriberCountText")
                                    val subscriberCountText: SubscriberCountText? = null,
                                    @SerialName("title")
                                    val title: Title? = null,
                                    @SerialName("navigationEndpoint")
                                    val navigationEndpoint: NavigationEndpoint? = null,
                                ) {
                                    @Serializable
                                    data class Thumbnail(
                                        @SerialName("thumbnails")
                                        val thumbnails: List<com.metrolist.innertube.models.Thumbnail>? = null,
                                    )

                                    @Serializable
                                    data class SubscriberCountText(
                                        @SerialName("simpleText")
                                        val simpleText: String? = null,
                                    )

                                    @Serializable
                                    data class Title(
                                        @SerialName("runs")
                                        val runs: List<Run>? = null,
                                    ) {
                                        @Serializable
                                        data class Run(
                                            @SerialName("text")
                                            val text: String? = null,
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