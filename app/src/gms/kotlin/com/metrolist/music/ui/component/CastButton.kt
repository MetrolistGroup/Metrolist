package com.metrolist.music.ui.component

import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import com.google.android.gms.cast.framework.CastButtonFactory
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.EnableGoogleCastKey
import com.metrolist.music.utils.rememberPreference

@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val castHandler = playerConnection?.service?.castConnectionHandler
    val isCasting by castHandler?.isCasting?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val (enabled) = rememberPreference(EnableGoogleCastKey, defaultValue = true)
    val contentDescription = stringResource(R.string.google_cast)
    val iconTint = if (isCasting) MaterialTheme.colorScheme.primary else tintColor

    LaunchedEffect(enabled) {
        if (enabled) {
            castHandler?.initialize()
        } else if (isCasting) {
            castHandler?.disconnect()
        }
    }

    if (enabled) {
        AndroidView(
            factory = { viewContext ->
                val themedContext = viewContext.castThemedContext()
                MediaRouteButton(themedContext).apply {
                    CastButtonFactory.setUpMediaRouteButton(themedContext, this)
                    dialogFactory = CastDialogFactory
                }
            },
            update = { button ->
                button.contentDescription = contentDescription
                ContextCompat
                    .getDrawable(
                        context,
                        if (isCasting) R.drawable.cast_connected else R.drawable.cast,
                    )?.mutate()
                    ?.let { drawable ->
                        DrawableCompat.wrap(drawable).also {
                            DrawableCompat.setTint(it, iconTint.toArgb())
                            button.setRemoteIndicatorDrawable(it)
                        }
                    }
            },
            modifier = modifier.size(56.dp),
        )
    }
}

private fun Context.castThemedContext() =
    ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)

private object CastDialogFactory : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment() = CastChooserDialogFragment()

    override fun onCreateControllerDialogFragment() = CastControllerDialogFragment()
}

internal class CastChooserDialogFragment : MediaRouteChooserDialogFragment() {
    override fun onCreateChooserDialog(context: Context, savedInstanceState: Bundle?) =
        MediaRouteChooserDialog(context.castThemedContext())
}

internal class CastControllerDialogFragment : MediaRouteControllerDialogFragment() {
    override fun onCreateControllerDialog(context: Context, savedInstanceState: Bundle?) =
        MediaRouteControllerDialog(context.castThemedContext())
}
