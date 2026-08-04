package com.macrotrack.app.ui.search

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.macrotrack.app.domain.NutritionLabelParser
import com.macrotrack.app.domain.OcrLine
import com.macrotrack.app.domain.ParsedNutritionLabel
import java.util.concurrent.Executors

/**
 * Captures one still photo of a nutrition panel, runs on-device ML Kit Text
 * Recognition on it, and hands a parsed result back via [onResult]. The
 * bitmap is never persisted or uploaded - only recognized text leaves this
 * screen. Mirrors BarcodeScannerScreen's camera setup and permission flow.
 */
@Composable
fun NutritionLabelScannerScreen(
    onResult: (ParsedNutritionLabel) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)

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
    var isProcessing by remember { mutableStateOf(false) }
    var showRetryPrompt by remember { mutableStateOf(false) }

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
        val canShowSystemRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: false
        val isPermanentlyDenied = hasRequestedPermissionOnce && !canShowSystemRationale

        NutritionLabelPermissionContent(
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
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        DisposableEffect(previewView, lifecycleOwner) {
            val callbackExecutor = ContextCompat.getMainExecutor(context)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            var cameraProvider: ProcessCameraProvider? = null
            var disposed = false

            val listener = Runnable {
                if (disposed) return@Runnable
                try {
                    cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                        .also { imageCapture = it }

                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                } catch (e: Exception) {
                    cameraError = e.message ?: "The camera could not be started."
                }
            }

            cameraProviderFuture.addListener(listener, callbackExecutor)

            onDispose {
                disposed = true
                cameraProvider?.unbindAll()
            }
        }

        NutritionLabelOverlay(
            cameraError = cameraError,
            isProcessing = isProcessing,
            showRetryPrompt = showRetryPrompt,
            onCapture = {
                val capture = imageCapture ?: return@NutritionLabelOverlay
                isProcessing = true
                showRetryPrompt = false
                captureAndRecognize(
                    imageCapture = capture,
                    executor = Executors.newSingleThreadExecutor(),
                    mainExecutor = ContextCompat.getMainExecutor(context),
                    onLines = { lines ->
                        isProcessing = false
                        val result = NutritionLabelParser.parse(lines)
                        if (result.isEmpty) {
                            showRetryPrompt = true
                        } else {
                            currentOnResult(result)
                        }
                    },
                    onError = {
                        isProcessing = false
                        showRetryPrompt = true
                    },
                )
            },
            onClose = onClose,
        )
    }
}

private fun captureAndRecognize(
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    mainExecutor: java.util.concurrent.Executor,
    onLines: (List<OcrLine>) -> Unit,
    onError: () -> Unit,
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            @androidx.camera.core.ExperimentalGetImage
            override fun onCaptureSuccess(image: ImageProxy) {
                val mediaImage = image.image
                if (mediaImage == null) {
                    image.close()
                    mainExecutor.execute(onError)
                    return
                }
                val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(inputImage)
                    .addOnSuccessListener(mainExecutor) { text ->
                        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            line.boundingBox?.let { box ->
                                OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                            }
                        }
                        onLines(lines)
                    }
                    .addOnFailureListener(mainExecutor) { onError() }
                    .addOnCompleteListener(mainExecutor) { image.close() }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute(onError)
            }
        },
    )
}

@Composable
private fun NutritionLabelOverlay(
    cameraError: String?,
    isProcessing: Boolean,
    showRetryPrompt: Boolean,
    onCapture: () -> Unit,
    onClose: () -> Unit,
) {
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

        Text(
            text = "Frame the nutrition information panel, then tap Capture",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )

        if (showRetryPrompt) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = "Couldn't read that label - try again?",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
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

        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onCapture, enabled = !isProcessing) {
            if (isProcessing) CircularProgressIndicator() else Text("Capture")
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NutritionLabelPermissionContent(
    isPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isPermanentlyDenied) {
            Text(
                "Camera access was denied. Enable it from app settings to scan a nutrition label.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenAppSettings) {
                Text("Open app settings")
            }
        } else {
            Text("Camera access is needed to scan a nutrition label.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Allow camera")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onClose) {
            Text("Enter manually")
        }
    }
}
