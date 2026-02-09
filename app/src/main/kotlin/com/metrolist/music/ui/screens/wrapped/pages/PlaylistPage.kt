package com.metrolist.music.ui.screens.wrapped.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R
import com.metrolist.music.ui.screens.wrapped.LocalWrappedManager
import com.metrolist.music.ui.screens.wrapped.PlaylistCreationState
import com.metrolist.music.ui.screens.wrapped.WrappedConstants
import com.metrolist.music.ui.screens.wrapped.components.AnimatedBackground
import com.metrolist.music.ui.screens.wrapped.components.AutoResizingText
import com.metrolist.music.ui.screens.wrapped.components.ShapeType
import com.metrolist.music.ui.theme.bbh_bartle
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun PlaylistPage() {
    val manager = LocalWrappedManager.current
    val state by manager.state.collectAsState()
    val playlistCreationState = state.playlistCreationState

    val (playlistImageRes, playlistImageName) = remember {
        if (Random.nextBoolean()) {
            Pair(R.drawable.wrapped_playlistv1, "wrapped_playlistv1")
        } else {
            Pair(R.drawable.wrapped_playlistv2, "wrapped_playlistv2")
        }
    }

    var startAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200)
        startAnimation = true
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 200)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(shapeTypes = listOf(ShapeType.Circle))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .alpha(contentAlpha),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AutoResizingText(
                text = stringResource(R.string.wrapped_playlist_ready),
                style = TextStyle(
                    fontFamily = bbh_bartle,
                    fontSize = 40.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 48.sp
                )
            )
            Spacer(modifier = Modifier.height(32.dp))
            Image(
                painter = painterResource(id = playlistImageRes),
                contentDescription = stringResource(R.string.album_cover_desc),
                modifier = Modifier
                    .size(256.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.wrapped_playlist_title, WrappedConstants.YEAR),
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = {
                    if (playlistCreationState == PlaylistCreationState.Idle) {
                        manager.createPlaylist(playlistImageName)
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.height(50.dp)
            ) {
                when (playlistCreationState) {
                    is PlaylistCreationState.Idle -> Text(
                        text = stringResource(R.string.wrapped_create_playlist),
                        style = TextStyle(color = Color.Black, fontWeight = FontWeight.Bold)
                    )
                    is PlaylistCreationState.Creating -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                    is PlaylistCreationState.Success -> Text(
                        text = stringResource(R.string.wrapped_playlist_saved),
                        style = TextStyle(color = Color.Black, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
