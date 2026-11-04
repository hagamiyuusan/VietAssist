package com.vietassist.app

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Observer
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DetectionBox(val rect: RectF, val label: String)
@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
private data class PreviewViewport(val transform: OutputTransform, val width: Int, val height: Int)

/** CameraX maps the rotated analysis buffer into the preview's shared viewport. */
@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onDetection: (String) -> Unit,
    analysisPaused: Boolean = false,
    vinternCaptureRequest: Int = 0,
    onVinternFrame: (Long, Bitmap) -> Unit = { _, frame -> frame.recycle() }
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val main = remember(context) { ContextCompat.getMainExecutor(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val disposed = remember { AtomicBoolean(false) }
    val epoch = remember { AtomicLong(0) }
    val requested = remember { AtomicLong(0) }
    val handled = remember { AtomicLong(0) }
    val paused by rememberUpdatedState(analysisPaused)
    val publish by rememberUpdatedState(onDetection)
    val capture by rememberUpdatedState(onVinternFrame)
    val overlay = remember { arrayOfNulls<DetectionOverlay>(1) }
    val previewRef = remember { arrayOfNulls<PreviewView>(1) }
    val streamObserver = remember { arrayOfNulls<Observer<PreviewView.StreamState>>(1) }
    val viewportRef = remember { AtomicReference<PreviewViewport?>(null) }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    val useCases = remember { mutableListOf<UseCase>() }
    val detector = remember(context) {
        try { ObjectDetector.createFromFile(context, "efficientdet-lite0.tflite") }
        catch (_: Exception) { null }
    }
    LaunchedEffect(vinternCaptureRequest) { requested.set(vinternCaptureRequest.toLong()) }
    LaunchedEffect(analysisPaused) {
        epoch.incrementAndGet()
        if (analysisPaused) overlay[0]?.update(emptyList())
    }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            streamObserver[0]?.let { previewRef[0]?.previewStreamState?.removeObserver(it) }
            epoch.incrementAndGet()
            providerRef[0]?.unbind(*useCases.toTypedArray())
            executor.execute { detector?.close() }
            executor.shutdown()
        }
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        val previewView = PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Camera trực tiếp"
        }
        previewRef[0] = previewView
        fun refreshViewport() {
            val transform = previewView.outputTransform
            if (transform != null && previewView.width > 0 && previewView.height > 0) {
                viewportRef.set(PreviewViewport(transform, previewView.width, previewView.height))
            }
        }
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshViewport() }
        val observer = Observer<PreviewView.StreamState> { state ->
            refreshViewport()
            previewView.contentDescription = if (state == PreviewView.StreamState.STREAMING)
                "Camera trực tiếp · đang phát" else "Camera trực tiếp · đang chuẩn bị"
        }
        streamObserver[0] = observer
        previewView.previewStreamState.observe(lifecycle, observer)
        val boxes = DetectionOverlay(ctx).also { overlay[0] = it }
        val container = FrameLayout(ctx).apply {
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(boxes, FrameLayout.LayoutParams(-1, -1))
        }
        previewView.doOnLayout {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                if (disposed.get()) return@addListener
                try {
                    val provider = future.get()
                    providerRef[0] = provider
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    var lastAnalysis = 0L
                    val transforms = ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }
                    analysis.setAnalyzer(executor) { proxy ->
                        val run = epoch.get()
                        try {
                            if (disposed.get()) return@setAnalyzer
                            val hasCapture = requested.get() > handled.get()
                            if (paused && !hasCapture) return@setAnalyzer
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (!hasCapture && now - lastAnalysis < 450L) return@setAnalyzer
                            lastAnalysis = now
                            val viewport = viewportRef.get()
                            if (viewport == null) {
                                main.execute { if (!disposed.get()) refreshViewport() }
                                return@setAnalyzer
                            }
                            val bitmap = imageProxyToBitmap(proxy)
                            val rotatedBitmap = if (proxy.imageInfo.rotationDegrees == 0) bitmap else {
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                                    Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }, true)
                                    .also { if (it !== bitmap) bitmap.recycle() }
                            }
                            try {
                                val source = transforms.getOutputTransform(proxy)
                                val visibleRegion = visibleRegionForPreview(source, viewport.transform, viewport.width, viewport.height)
                                val request = requested.get()
                                if (request > handled.get()) {
                                    handled.set(request)
                                    val frame = copyVisibleFrame(rotatedBitmap, visibleRegion)
                                    main.execute {
                                        if (disposed.get()) frame.recycle() else capture(request, frame)
                                    }
                                }
                                if (paused) return@setAnalyzer
                                if (detector == null) {
                                    main.execute { if (!disposed.get()) publish("Không tải được model nhận diện vật thể") }
                                    return@setAnalyzer
                                }
                                val detections = detector.detect(TensorImage.fromBitmap(rotatedBitmap))
                                    .filter { RectF.intersects(it.boundingBox, visibleRegion) }
                                val found = detections.mapNotNull { detection ->
                                    val category = detection.categories.maxByOrNull { it.score }
                                    if (category == null || category.score < .35f || category.label.isBlank()) null
                                    else DetectionBox(RectF(detection.boundingBox),
                                        "${translateDetectionForSpeech(category.label) ?: category.label} ${(category.score * 100).toInt()}%")
                                }.take(8)
                                val result = detections
                                    .flatMap { detection ->
                                        val box = detection.boundingBox
                                        val centerX = box.centerX() / rotatedBitmap.width.toFloat()
                                        val centerY = box.centerY() / rotatedBitmap.height.toFloat()
                                        val areaRatio =
                                            (box.width() * box.height()) /
                                                    (rotatedBitmap.width.toFloat() * rotatedBitmap.height.toFloat())

                                        val horizontalPosition = when {
                                            centerX < 0.15f -> "sát mép trái"
                                            centerX < 0.35f -> "hơi lệch trái"
                                            centerX > 0.85f -> "sát mép phải"
                                            centerX > 0.65f -> "hơi lệch phải"
                                            else -> "gần giữa khung hình"
                                        }
                                        val verticalPosition = when {
                                            centerY < 0.33f -> "vùng trên khung hình"
                                            centerY > 0.67f -> "vùng dưới khung hình"
                                            else -> ""
                                        }
                                        val position = buildString {
                                            append("trong tầm nhìn phía trước, ")
                                            append(horizontalPosition)
                                            if (verticalPosition.isNotBlank()) {
                                                append(", ")
                                                append(verticalPosition)
                                            }
                                        }
                                        val relativeSize = when {
                                            areaRatio >= 0.22f -> "kích thước lớn trong ảnh"
                                            areaRatio >= 0.07f -> "kích thước vừa trong ảnh"
                                            else -> "kích thước nhỏ trong ảnh"
                                        }

                                        detection.categories.mapNotNull { category ->
                                            val label = category.label
                                            val score = category.score

                                            if (label.isBlank() || score < 0.35f) {
                                                null
                                            } else {
                                                "$label (${(score * 100).toInt()}%) [$position | $relativeSize]"
                                            }
                                        }
                                    }
                                    .distinct()
                                    .take(4)
                                    .joinToString("; ")
                                    .ifBlank {
                                        "Chưa phát hiện vật thể rõ ràng"
                                    }

                                main.execute {
                                    if (!disposed.get() && !paused && run == epoch.get()) {
                                        refreshViewport()
                                        val target = previewView.outputTransform
                                        boxes.update(if (target == null) emptyList() else {
                                            val transform = CoordinateTransform(source, target)
                                            found.map { box -> DetectionBox(RectF(box.rect).also { transform.mapRect(it) }, box.label) }
                                        })
                                        publish(result)
                                    }
                                }
                            } finally { if (!rotatedBitmap.isRecycled) rotatedBitmap.recycle() }
                        } catch (_: Exception) {
                            main.execute {
                                if (!disposed.get() && !paused && run == epoch.get()) { boxes.update(emptyList()); publish("Không xử lý được hình ảnh camera") }
                            }
                        } finally { proxy.close() }
                    }
                    useCases.addAll(listOf(preview, analysis))
                    val viewport = checkNotNull(previewView.viewPort) { "Camera viewport chưa sẵn sàng" }
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA,
                        UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis).setViewPort(viewport).build())
                } catch (_: Exception) {
                    if (!disposed.get()) publish("Không thể khởi động camera/AI")
                }
            }, main)
        }
        container
    })
}

internal class DetectionOverlay(context: Context) : View(context) {
    private var boxes: List<DetectionBox> = emptyList()
    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(181, 245, 210); style = Paint.Style.STROKE; strokeWidth = 2 * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 13 * resources.displayMetrics.scaledDensity
    }
    private val background = Paint().apply { color = Color.rgb(181, 245, 210) }
    fun update(detections: List<DetectionBox>) {
        val viewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
        boxes = detections.filter { RectF.intersects(it.rect, viewport) }
        // Metadata only: useful for TalkBack and device verification, no camera pixels.
        contentDescription = "Khung nhận diện: ${boxes.size} vật thể"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        for (box in boxes) {
            canvas.drawRect(box.rect, border)
            val textWidth = label.measureText(box.label).coerceAtMost(width.toFloat() - 8 * density)
            val left = box.rect.left.coerceIn(0f, (width - textWidth - 8 * density).coerceAtLeast(0f))
            val baseline = (box.rect.top - 5 * density).coerceAtLeast(-label.ascent() + 4 * density)
            canvas.drawRect(left, baseline + label.ascent() - 4 * density,
                left + textWidth + 8 * density, baseline + label.descent() + 4 * density, background)
            canvas.drawText(box.label, left + 4 * density, baseline, label)
        }
        canvas.restore()
    }
}

/** Ảnh gửi OCR/VLM phải cùng vùng nhìn với preview và sở hữu bitmap riêng. */
internal fun copyVisibleFrame(source: Bitmap, visible: RectF): Bitmap {
    val crop = Rect()
    visible.roundOut(crop)
    check(crop.intersect(0, 0, source.width, source.height)) { "Vùng camera không hợp lệ" }
    val selected = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
    return if (selected === source) source.copy(Bitmap.Config.ARGB_8888, false) else selected
}

@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
internal fun visibleRegionForPreview(source: OutputTransform, preview: OutputTransform, width: Int, height: Int): RectF =
    RectF(0f, 0f, width.toFloat(), height.toFloat()).also { CoordinateTransform(preview, source).mapRect(it) }
