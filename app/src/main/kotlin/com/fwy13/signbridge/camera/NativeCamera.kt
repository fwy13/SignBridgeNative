package com.fwy13.signbridge.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fwy13.signbridge.models.ModelStorage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@OptIn(TransformExperimental::class)
@Composable
fun NativeCamera(
    mode: String, recordSignal: Int, modifier: Modifier = Modifier, onState: (CaptureState) -> Unit,
    onResult: (String) -> Unit, onQr: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
    }
    val stateCallback by rememberUpdatedState(onState)
    val resultCallback by rememberUpdatedState(onResult)
    val qrCallback by rememberUpdatedState(onQr)
    val requested = remember { AtomicInteger(recordSignal) }
    SideEffect { requested.set(recordSignal) }
    var overlay by remember(mode) { mutableStateOf(CaptureState()) }
    var mirrored by remember { mutableStateOf(true) }
    val preview = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode =
            PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    var overlayPoints by remember(mode) {
        mutableStateOf<List<List<Offset>>>(emptyList())
    }

    if (!permission) {
        Column(modifier) {
            Text("Camera"); Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
            Text(
                "Cho phép camera / Allow camera"
            )
        }
        }
        return
    }
    Box(modifier) {
        AndroidView(
            factory = { preview },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { previewSize = it }
        )
        Canvas(Modifier.fillMaxSize()) {
            val edges = listOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 4,
                0 to 5, 5 to 6, 6 to 7, 7 to 8,
                5 to 9, 9 to 10, 10 to 11, 11 to 12,
                9 to 13, 13 to 14, 14 to 15, 15 to 16,
                13 to 17, 0 to 17,
                17 to 18, 18 to 19, 19 to 20
            )

            overlayPoints.forEach { points ->
                if (points.size == 21) {
                    edges.forEach { (a, b) ->
                        drawLine(
                            color = Color(0xFF45C8C2),
                            start = points[a],
                            end = points[b],
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    points.forEach { point ->
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx(),
                            center = point
                        )
                    }
                }
            }
        }
    }
    if (previewSize.width == 0 || previewSize.height == 0) return
    DisposableEffect(mode, owner, permission) {
        val alive = AtomicBoolean(true)
        val qrSent = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        var landmarker: HandLandmarker? = null
        var session: RecognitionSession? = null
        var seenSignal = requested.get()
        var provider: ProcessCameraProvider? = null
        var boundPreview: Preview? = null
        var boundAnalysis: ImageAnalysis? = null
        val transformFactory = ImageProxyTransformFactory().apply {
            setUsingRotationDegrees(true)
            setUsingCropRect(false)
        }
        val rotation = preview.display?.rotation ?: Surface.ROTATION_0

        val cameraPreview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(preview.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(
                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            )
            .build()

        fun report(error: Throwable) {
            main.post {
                if (alive.get()) stateCallback(
                    CaptureState(
                        error = error.message ?: error.javaClass.simpleName
                    )
                )
            }
        }
        executor.execute {
            try {
                if (mode != "qr") {
                    val path = ModelStorage(context).requiredHandLandmarker().absolutePath
                    fun create(delegate: Delegate) = HandLandmarker.createFromOptions(
                        context, HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(
                                BaseOptions.builder().setModelAssetPath(path).setDelegate(delegate)
                                    .build()
                            )
                            .setRunningMode(RunningMode.VIDEO).setNumHands(2)
                            .setMinHandDetectionConfidence(.5f)
                            .setMinHandPresenceConfidence(.5f).setMinTrackingConfidence(.5f).build()
                    )
                    landmarker = try {
                        create(Delegate.GPU)
                    } catch (_: Exception) {
                        create(Delegate.CPU)
                    }
                    session = RecognitionSession(
                        context,
                        mode
                    ) { label -> main.post { if (alive.get()) resultCallback(label) } }
                }
                main.post {
                    if (alive.get()) {
                        val future = ProcessCameraProvider.getInstance(context)
                        future.addListener({
                            if (alive.get()) try {
                                val cameraProvider = future.get(); provider = cameraProvider
                                val front =
                                    mode != "qr" && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                                mirrored = front
                                val selector =
                                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                                val cameraPreview = Preview.Builder().build()
                                    .also { it.setSurfaceProvider(preview.surfaceProvider) }
                                val analysis = ImageAnalysis.Builder()
                                    .setTargetResolution(android.util.Size(640, 480))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                boundPreview = cameraPreview; boundAnalysis = analysis
                                var lastTime = -1L
                                analysis.setAnalyzer(executor) { image ->
                                    var bitmap: Bitmap? = null;
                                    var upright: Bitmap? = null
                                    try {
                                        if (alive.get()) {
                                            bitmap = image.toBitmap()
                                            upright =
                                                if (image.imageInfo.rotationDegrees == 0) bitmap else Bitmap.createBitmap(
                                                    bitmap!!,
                                                    0,
                                                    0,
                                                    bitmap!!.width,
                                                    bitmap!!.height,
                                                    Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) },
                                                    true
                                                )
                                            val frame = requireNotNull(upright)
                                            val sourceTransform = transformFactory.getOutputTransform(image)
                                            if (mode == "qr") {
                                                val pixels =
                                                    IntArray(frame.width * frame.height); frame.getPixels(
                                                    pixels,
                                                    0,
                                                    frame.width,
                                                    0,
                                                    0,
                                                    frame.width,
                                                    frame.height
                                                )
                                                val found = runCatching {
                                                    MultiFormatReader().decode(
                                                        BinaryBitmap(
                                                            HybridBinarizer(
                                                                RGBLuminanceSource(
                                                                    frame.width,
                                                                    frame.height,
                                                                    pixels
                                                                )
                                                            )
                                                        ),
                                                        mapOf(
                                                            DecodeHintType.POSSIBLE_FORMATS to listOf(
                                                                BarcodeFormat.QR_CODE
                                                            )
                                                        )
                                                    ).text
                                                }.getOrNull()
                                                if (found != null && qrSent.compareAndSet(
                                                        false,
                                                        true
                                                    )
                                                ) main.post { if (alive.get()) qrCallback(found) }
                                            } else {
                                                val time = maxOf(
                                                    image.imageInfo.timestamp / 1_000_000,
                                                    lastTime + 1
                                                ); lastTime = time
                                                val mp = BitmapImageBuilder(frame).build()
                                                val detection = try {
                                                    requireNotNull(landmarker).detectForVideo(
                                                        mp,
                                                        time
                                                    )
                                                } finally {
                                                    mp.close()
                                                }
                                                val hands =
                                                    detection.landmarks().mapIndexed { i, points ->
                                                        val category =
                                                            detection.handedness().getOrNull(i)
                                                                ?.firstOrNull()
                                                        HandDetection(
                                                            category?.categoryName().orEmpty(),
                                                            category?.score() ?: 0f,
                                                            points.map {
                                                                HandPoint(
                                                                    it.x(),
                                                                    it.y(),
                                                                    it.z()
                                                                )
                                                            })
                                                    }
                                                if (seenSignal != requested.get()) {
                                                    seenSignal =
                                                        requested.get(); session?.requestRecord()
                                                }
                                                val state = requireNotNull(session).process(
                                                    hands,
                                                    time,
                                                    frame.width,
                                                    frame.height
                                                )
                                                // Capture dimensions before the bitmap is recycled.
                                                val frameWidth = frame.width.toFloat()
                                                val frameHeight = frame.height.toFloat()

                                                main.post {
                                                    if (alive.get()) {
                                                        val targetTransform = preview.outputTransform

                                                        overlayPoints = if (targetTransform == null) {
                                                            emptyList()
                                                        } else {
                                                            try {
                                                                val coordinateTransform = CoordinateTransform(
                                                                    sourceTransform,
                                                                    targetTransform
                                                                )

                                                                state.hands.map { hand ->
                                                                    val points = FloatArray(hand.points.size * 2)

                                                                    hand.points.forEachIndexed { index, point ->
                                                                        points[index * 2] = point.x * frameWidth
                                                                        points[index * 2 + 1] = point.y * frameHeight
                                                                    }

                                                                    coordinateTransform.mapPoints(points)

                                                                    List(hand.points.size) { index ->
                                                                        Offset(
                                                                            x = points[index * 2],
                                                                            y = points[index * 2 + 1]
                                                                        )
                                                                    }
                                                                }
                                                            } catch (error: IllegalArgumentException) {
                                                                android.util.Log.w(
                                                                    "SignBridgeCamera",
                                                                    "Preview transform is not ready",
                                                                    error
                                                                )
                                                                emptyList()
                                                            }
                                                        }

                                                        stateCallback(state)
                                                    }
                                                }
                                            }
                                        }
                                    } catch (error: Exception) {
                                        report(error)
                                    } finally {
                                        if (upright !== bitmap) upright?.recycle(); bitmap?.recycle(); image.close()
                                    }
                                }
                                val viewPort = requireNotNull(preview.viewPort) {
                                    "Camera preview is not ready"
                                }

                                val useCaseGroup = UseCaseGroup.Builder()
                                    .setViewPort(viewPort)
                                    .addUseCase(cameraPreview)
                                    .addUseCase(analysis)
                                    .build()

                                cameraProvider.bindToLifecycle(
                                    owner,
                                    selector,
                                    useCaseGroup
                                )
                            } catch (error: Exception) {
                                report(error)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                }
            } catch (error: Throwable) {
                report(error)
            }
        }
        onDispose {
            alive.set(false)
            boundAnalysis?.clearAnalyzer()
            val useCases = listOfNotNull<UseCase>(boundPreview, boundAnalysis).toTypedArray()
            if (useCases.isNotEmpty()) provider?.unbind(*useCases)
            executor.execute { session?.close(); landmarker?.close() }
            executor.shutdown()
        }
    }
}
