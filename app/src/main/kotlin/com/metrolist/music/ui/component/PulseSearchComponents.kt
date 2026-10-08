/**
 * Native 7xTune search composition.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.SevenXTunePalette

@Composable
fun PulseSearchHeader(
    query: TextFieldValue,
    isOnline: Boolean,
    focusRequester: FocusRequester,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SevenXTunePalette.Midnight)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.dismiss),
                    tint = SevenXTunePalette.TextSecondary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sevenx_search_kicker),
                    style = MaterialTheme.typography.labelSmall,
                    color = SevenXTunePalette.ElectricBlue,
                )
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = stringResource(R.string.sevenx_search_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    color = SevenXTunePalette.TextPrimary,
                )
            }
        }

        Spacer(modifier = Modifier.size(10.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(SevenXTunePalette.Surface)
                    .border(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                SevenXTunePalette.Violet.copy(alpha = 0.26f),
                                SevenXTunePalette.ElectricBlue.copy(alpha = 0.10f),
                                SevenXTunePalette.PulsePink.copy(alpha = 0.18f),
                            ),
                        ),
                        RoundedCornerShape(24.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = null,
                tint = SevenXTunePalette.Violet,
                modifier = Modifier.padding(horizontal = 9.dp).size(20.dp),
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                textStyle =
                    TextStyle(
                        color = SevenXTunePalette.TextPrimary,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                singleLine = true,
                cursorBrush =
                    androidx.compose.ui.graphics.SolidColor(
                        SevenXTunePalette.Violet,
                    ),
                decorationBox = { innerTextField ->
                    if (query.text.isEmpty()) {
                        Text(
                            text =
                                stringResource(
                                    if (isOnline) {
                                        R.string.search_yt_music
                                    } else {
                                        R.string.search_library
                                    },
                                ),
                            color = SevenXTunePalette.TextMuted,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query.text) }),
            )

            if (query.text.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.close),
                        tint = SevenXTunePalette.TextSecondary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(9.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchModePill(
                label = stringResource(R.string.sevenx_search_online),
                icon = R.drawable.language,
                selected = isOnline,
                onClick = { onModeChange(true) },
                modifier = Modifier.weight(1f),
            )
            SearchModePill(
                label = stringResource(R.string.sevenx_search_local),
                icon = R.drawable.library_music,
                selected = !isOnline,
                onClick = { onModeChange(false) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchModePill(
    label: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier =
            modifier
                .clip(shape)
                .clickable(onClick = onClick)
                .background(
                    if (selected) {
                        Brush.linearGradient(
                            listOf(
                                SevenXTunePalette.Violet.copy(alpha = 0.84f),
                                SevenXTunePalette.ElectricBlue.copy(alpha = 0.56f),
                            ),
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                Color.Transparent,
                                Color.Transparent,
                            ),
                        )
                    },
                )
                .border(
                    1.dp,
                    if (selected) {
                        Color.White.copy(alpha = 0.10f)
                    } else {
                        SevenXTunePalette.TextMuted.copy(alpha = 0.10f)
                    },
                    shape,
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) Color.White else SevenXTunePalette.TextSecondary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.size(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight =
                if (selected) {
                    androidx.compose.ui.text.font.FontWeight.Bold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Medium
                },
            color = if (selected) Color.White else SevenXTunePalette.TextSecondary,
        )
    }
}
