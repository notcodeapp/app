package com.notcode.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** QR scanner for desktop invitations; result goes through onResult and is throttled to one delivery. */
@Composable
internal fun InvitationQrScanner(onResult: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var scanning by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        scanning = granted
        if (!granted) onError("Нет доступа к камере. Вставьте приглашение вручную.")
    }
    fun startScanning() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionGranted = true
            scanning = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) } }
    DisposableEffect(Unit) { onDispose { executor.shutdownNow() } }
    if (!scanning) {
        OutlinedButton(onClick = ::startScanning, modifier = Modifier.fillMaxWidth()) { Text("Сканировать QR-код с ПК") }
        return
    }
    if (!permissionGranted) return
    val previewView = remember { PreviewView(context) }
    DisposableEffect(scanning) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val resultDelivered = AtomicBoolean(false)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val text = try {
                        decodeQr(reader, image)
                    } finally {
                        image.close()
                    }
                    if (text != null && resultDelivered.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute {
                            scanning = false
                            provider.unbindAll()
                            onResult(text)
                        }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (error: Exception) {
                onError("Камера недоступна: ${error.message ?: "неизвестная ошибка"}. Вставьте приглашение вручную.")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { providerFuture.get().unbindAll() } }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Сканирование QR", style = MaterialTheme.typography.titleSmall)
                Text("Наведите камеру на код ПК", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().height(280.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
            OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть сканер") }
        }
    }
}

/** Decode one YUV frame; returns null on any failure so analysis keeps running. */
private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? = runCatching {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(image.width * image.height)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            bytes[y * image.width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
        }
    }
    val source = PlanarYUVLuminanceSource(
        bytes, image.width, image.height,
        0, 0, image.width, image.height, false,
    )
    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text.also { reader.reset() }
}.getOrNull()
