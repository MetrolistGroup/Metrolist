/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.kidj.KiDj
import com.metrolist.music.kidj.motionEnergy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiDjScreen(navController: NavController) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val running by KiDj.running.collectAsState()
    val url by KiDj.url.collectAsState()
    val floor by KiDj.floor.collectAsState()
    val wishes by KiDj.wishes.collectAsState()
    val log by KiDj.log.collectAsState()
    val rescueEnabled by KiDj.rescueEnabled.collectAsState()
    val sensitivity by KiDj.sensitivity.collectAsState()
    var cameraOn by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> cameraOn = granted }

    // The phone usually lies next to the DJ desk; keep the screen awake while party mode is open.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.ki_dj)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        Button(
            onClick = {
                if (running) KiDj.stop() else playerConnection?.let { KiDj.start(context.applicationContext, it) }
            },
            enabled = running || playerConnection != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (running) R.string.ki_dj_stop else R.string.ki_dj_start)) }

        if (running) {
            val link = url
            if (link == null) {
                Text(stringResource(R.string.ki_dj_no_wifi))
            } else {
                val qr = remember(link) { qrBitmap(link) }
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = link,
                    modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
                )
                Text(
                    stringResource(R.string.ki_dj_scan_hint, link),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ki_dj_camera), Modifier.weight(1f))
            Switch(
                checked = cameraOn,
                onCheckedChange = { on ->
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (on && !granted) permissionLauncher.launch(Manifest.permission.CAMERA) else cameraOn = on
                },
            )
        }
        if (cameraOn) {
            FloorCamera(sensitivity.toDouble())
            Text(stringResource(R.string.ki_dj_floor, (floor * 100).toInt()))
            LinearProgressIndicator(progress = { floor.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.ki_dj_sensitivity))
            Slider(value = sensitivity, onValueChange = { KiDj.sensitivity.value = it }, valueRange = 1f..30f)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ki_dj_rescue), Modifier.weight(1f))
            Switch(checked = rescueEnabled, onCheckedChange = { KiDj.rescueEnabled.value = it })
        }

        if (log.isNotEmpty()) Text(log, color = MaterialTheme.colorScheme.primary)

        Text(stringResource(R.string.ki_dj_wishes), style = MaterialTheme.typography.titleMedium)
        if (wishes.isEmpty()) Text(stringResource(R.string.ki_dj_no_wishes))
        wishes.forEach { wish ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(wish.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${wish.artist} · ${wish.votes}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { KiDj.playNow(wish.id) }) { Text(stringResource(R.string.ki_dj_play_now)) }
                IconButton(onClick = { KiDj.remove(wish.id) }) {
                    Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.ki_dj_remove))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Back camera preview; every ~0.5 s the luminance change feeds the dance floor meter. */
@Composable
private fun FloorCamera(sensitivity: Double) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { FloorState() }
    state.sensitivity = sensitivity
    AndroidView(
        factory = { ctx -> PreviewView(ctx) },
        modifier = Modifier.fillMaxWidth().height(180.dp),
    ) { previewView ->
        if (state.bound) return@AndroidView
        state.bound = true
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                image.use {
                    val now = System.currentTimeMillis()
                    if (now - state.lastMs < 500) return@use
                    state.lastMs = now
                    val buffer = it.planes[0].buffer
                    val frame = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
                    state.prev?.let { prev -> KiDj.onFloor(motionEnergy(prev, frame, state.sensitivity)) }
                    state.prev = frame
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            state.provider = provider
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose { state.provider?.unbindAll() }
    }
}

private class FloorState {
    var bound = false
    var lastMs = 0L
    var prev: ByteArray? = null
    var sensitivity = 12.0
    var provider: ProcessCameraProvider? = null
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
