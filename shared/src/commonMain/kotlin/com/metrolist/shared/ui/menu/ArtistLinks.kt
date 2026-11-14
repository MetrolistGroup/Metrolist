package com.metrolist.shared.ui.menu

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.CollectionPreviews

private val NoLinkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.None))

fun artistLinksText(
    artists: List<ArtistTiny>,
    onArtistClick: ((String) -> Unit)?,
): AnnotatedString? =
    onArtistClick?.takeIf { artists.isNotEmpty() }?.let { click ->
        buildAnnotatedString {
            artists.forEachIndexed { index, artist ->
                if (index > 0) append(", ")
                val id = artist.id
                if (id.isNullOrBlank()) {
                    append(artist.displayName)
                } else {
                    withLink(
                        LinkAnnotation.Clickable("ARTIST", NoLinkStyle) {
                            CollectionPreviews.record(artist)
                            click(id)
                        },
                    ) { append(artist.displayName) }
                }
            }
        }
    }
