package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class YouTubeLocale(
    val gl: String, // geolocation
    val hl: String, // host language
)
