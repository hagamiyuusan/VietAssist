package com.vietassist.app

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LightOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun missingOrBlockedLightOnCannotFallBackToLightOn() = runBlocking {
        val directory = File(context.cacheDir, "lighton-missing-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = directory
        }
        try {
            val previous = LightOnEngine.preparationFor(VisionModel.LIGHTON).value
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            assertSame(LightOnEngine.Result.ModelMissing, LightOnEngine.analyze(isolated, image,
                LightOnEngine.Mode.READ, AtomicLong(), VisionModel.LIGHTON))
            assertTrue(image.isRecycled)
            assertEquals(previous, LightOnEngine.preparationFor(VisionModel.LIGHTON).value)
            val blocked = object : ContextWrapper(isolated) {
                override fun getApplicationContext() = this
                override fun getApplicationInfo() = ApplicationInfo(context.applicationInfo).apply {
                    nativeLibraryDir = File(directory, "missing-native").absolutePath
                }
            }
            var progress = 0
            try { LightOnModelManager.download(blocked) { progress++ }; fail("Invalid device must not download") }
            catch (_: IOException) { }
            assertEquals(0, progress)
            assertFalse(LightOnModelManager.isDownloading)
            assertTrue(LightOnModelManager.modelDir(isolated).listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun readsVietnameseOnGpu() = runBlocking {
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            assumeTrue(LightOnModelManager.isReady(context))
            assertNull(LightOnRequirements.read(context, LightOnModelManager).inferenceBlocker)
            var progress = -1
            LightOnModelManager.download(context) { progress = it }
            assertEquals(100, progress)
            LightOnEngine.release()
            val started = android.os.SystemClock.elapsedRealtime()
            assertTrue("LightOn native load and warmup failed", LightOnEngine.prepare(context, model = VisionModel.LIGHTON).await())
            assertEquals(LightOnEngine.PreparationState.Ready, LightOnEngine.preparationFor(VisionModel.LIGHTON).value)
            assertEquals(LightOnEngine.PreparationState.Ready, LightOnEngine.preparationState.value)
            assertEquals("OpenCL", LightOnNative.nativeBackendName())
            val image = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val canvas = Canvas(image)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 58f }
            listOf("THÔNG BÁO", "Hôm nay là thứ Năm.", "Số thứ tự: 12345", "Xin vui lòng chờ.")
                .forEachIndexed { index, line -> canvas.drawText(line, 45f, 110f + index * 100f, paint) }
            val result = LightOnEngine.analyze(context, image, LightOnEngine.Mode.READ, AtomicLong(), VisionModel.LIGHTON)
            assertTrue("Synthetic Vietnamese OCR failed", result is LightOnEngine.Result.Success)
            val text = (result as LightOnEngine.Result.Success).text.lowercase(Locale.ROOT)
            assertTrue("Synthetic heading missing accents", text.contains("thông báo"))
            assertTrue("Synthetic sentence missing accents", text.contains("hôm nay"))
            assertTrue("Synthetic digits misread", text.contains("12345"))
            assertTrue(image.isRecycled)
            println("LIGHTON_PROBE syntheticViPassed=true elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        } finally { activity.close() }
    }
}
