package com.macrotrack.app.ui.search

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera barcode scanner for packaged-food lookup.
 *
 * This screen returns the raw value from ML Kit. It deliberately does not
 * decide that a UPC is an EAN, add leading zeroes, or query Open Food Facts
 * directly. FoodRepository remains the single exact-match lookup boundary.
 */
@Composable
fun BarcodeScannerScreen(
    onBarcodeDetected: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcodeDetected by rememberUpdatedState(onBarcodeDetected)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasRequestedPermissionOnce by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            hasRequestedPermissionOnce = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        // After a denial, the system stops offering a rationale once the user has
        // permanently declined (or on some OEMs, on a "never ask again" tap). At
        // that point re-launching the system dialog is a silent no-op, so give the
        // user an explicit escape hatch to the app's system Settings page instead.
        val canShowSystemRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: false
        val isPermanentlyDenied = hasRequestedPermissionOnce && !canShowSystemRationale

        PermissionRequestContent(
            isPermanentlyDenied = isPermanentlyDenied,
            onRequestPermission = {
                hasRequestedPermissionOnce = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenAppSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
            onClose = onClose,
        )
        return
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidPreview(
            modifier = Modifier.fillMaxSize(),
            previewView = previewView,
        )

        DisposableEffect(previewView, lifecycleOwner) {
                val callbackExecutor = ContextCompat.getMainExecutor(context)
                val analysisExecutor = Executors.newSingleThreadExecutor()
                val scanner = BarcodeScanning.getClient(productBarcodeOptions())
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                var cameraProvider: ProcessCameraProvider? = null
                var imageAnalysis: ImageAnalysis? = null
                var disposed = false

                val listener = object : Runnable {
                    override fun run() {
                        if (disposed) return

                        try {
                            cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val analysis = ImageAnalysis.Builder()
                                // The analyzer must not receive a backlog of stale frames.
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                // 1280x720 is within ML Kit's recommended real-time range.
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER,
                                            ),
                                        )
                                        .build(),
                                )
                                .build()
                                .also { imageAnalysis = it }

                            analysis.setAnalyzer(
                                analysisExecutor,
                                ProductBarcodeAnalyzer(
                                    scanner = scanner,
                                    callbackExecutor = callbackExecutor,
                                    onBarcodeDetected = currentOnBarcodeDetected,
                                ),
                            )

                            cameraProvider?.unbindAll()
                            cameraProvider?.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        } catch (e: Exception) {
                            cameraError = e.message ?: "The camera could not be started."
                        }
                    }
                }

                cameraProviderFuture.addListener(listener, callbackExecutor)

                onDispose {
                    disposed = true
                    imageAnalysis?.clearAnalyzer()
                    cameraProvider?.unbindAll()
                    scanner.close()
                    analysisExecutor.shutdown()
                }
            }

        ScannerOverlay(
            cameraError = cameraError,
            onClose = onClose,
        )
    }
}

private fun productBarcodeOptions(): BarcodeScannerOptions {
    return BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_ITF,
        )
        .build()
}

@Composable
private fun AndroidPreview(
    modifier: Modifier,
    previewView: PreviewView,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { previewView },
    )
}

@Composable
private fun ScannerOverlay(
    cameraError: String?,
    onClose: () -> Unit,
) {
    // Note: no systemBarsPadding() here. This overlay lives inside a route that
    // already receives the outer Scaffold's full system-bar inset padding, so an
    // additional systemBarsPadding() would double-apply the same insets.
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onClose) {
                Text("Close")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(width = 320.dp, height = 180.dp)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp),
                ),
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Align the package barcode inside the frame",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (cameraError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = cameraError,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PermissionRequestContent(
    isPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClose: () -> Unit,
) {
    // Note: no systemBarsPadding() here, for the same reason as ScannerOverlay —
    // the outer Scaffold's paddingValues already reserves the system-bar insets
    // for this route.
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isPermanentlyDenied) {
            Text(
                "Camera access was denied. Enable it from app settings to scan a barcode.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenAppSettings) {
                Text("Open app settings")
            }
        } else {
            Text("Camera access is needed to scan a food barcode.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Allow camera")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onClose) {
            Text("Search manually")
        }
    }
}

private class ProductBarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val callbackExecutor: Executor,
    private val onBarcodeDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val emitted = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        if (emitted.get()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )

        scanner.process(image)
            .addOnSuccessListener(callbackExecutor) { barcodes ->
                val rawValue = barcodes.asSequence()
                    .mapNotNull { it.rawValue?.trim() }
                    .firstOrNull { it.isNotEmpty() }
                if (rawValue != null && emitted.compareAndSet(false, true)) {
                    onBarcodeDetected(rawValue)
                }
            }
            .addOnFailureListener(callbackExecutor) {
                // A bad frame is not a scanner failure. Keep analyzing later frames.
            }
            .addOnCompleteListener(callbackExecutor) {
                imageProxy.close()
            }
    }
}
