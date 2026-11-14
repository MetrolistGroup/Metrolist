package com.metrolist.shared.viewmodels

internal object LastFmArtistFixtures {
    const val FIRST_PHOTO = "https://lastfm-img.freetls.fastly.net/i/u/ar0/11111111111111111111111111111111.jpg"
    const val SECOND_PHOTO = "https://lastfm-img.freetls.fastly.net/i/u/ar0/22222222222222222222222222222222.jpg"
    const val AVATAR = "https://lastfm-img.freetls.fastly.net/i/u/avatar170s/33333333333333333333333333333333.jpg"

    val overview =
        """
        <html lang="en"><body>
        <p class="truncate-4-lines">Public artist biography read more</p>
        <h4 class="header-metadata-tnew-title">Listeners</h4><abbr class="intabbr" title="123">123</abbr>
        <h4 class="header-metadata-tnew-title">Scrobbles</h4><abbr class="intabbr" title="456">456</abbr>
        <a href="/tag/indie+pop">indie pop</a>
        <a href="/music/Artist/_/Song">Song</a><a href="/music/Artist/Album">Album</a>
        <img src="$AVATAR" alt="Artist">
        </body></html>
        """.trimIndent()

    val gallery =
        """
        <li class="image-list-item"><img src="${FIRST_PHOTO.replace("ar0", "300x300")}"></li>
        <li class="image-list-item"><img src="$FIRST_PHOTO"></li>
        <li class="image-list-item"><img src="$SECOND_PHOTO"></li>
        <li class="image-list-item"><img src="http://lastfm-img.freetls.fastly.net/i/u/ar0/unsafe.jpg"></li>
        <li class="image-list-item"><img src="https://example.com/untrusted.jpg"></li>
        """.trimIndent()

    fun metadata(similar: String) =
        """{"artist":{"bio":{"summary":"API biography"},"stats":{"listeners":"789","playcount":"987"},"similar":{"artist":$similar}}}"""
}
