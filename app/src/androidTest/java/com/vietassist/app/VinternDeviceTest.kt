package com.vietassist.app

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class VinternDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun lateObserverSeesCompletedDownloadWithoutScreenCallbacks() = runBlocking {
        assumeTrue(VinternModelManager.isReady(context))
        VinternModelManager.download(context) { }
        // Tương đương màn hình mới đăng ký sau khi callback của màn hình cũ đã kết thúc.
        val state = VinternModelManager.downloadState.first()
        assertFalse(state.downloading)
        assertEquals(100, state.percent)
        assertTrue(VinternModelManager.isReady(context))
    }

    @Test fun secondDownloadCannotResetProgressOrUnlockCurrentDownload() {
        assumeTrue(VinternModelManager.isReady(context))
        var rejected = 0
        var secondProgressCalls = 0
        VinternModelManager.download(context) { percent ->
            val before = VinternModelManager.downloadState.value
            assertTrue(before.downloading)
            assertEquals(percent, before.percent)
            try {
                VinternModelManager.download(context) { secondProgressCalls++ }
                fail("Second download must be rejected")
            } catch (_: IOException) { rejected++ }
            assertEquals(before, VinternModelManager.downloadState.value)
        }
        assertTrue(rejected > 0)
        assertEquals(0, secondProgressCalls)
        assertFalse(VinternModelManager.isDownloading)
        assertEquals(100, VinternModelManager.downloadState.value.percent)
    }

    @Test fun downloadFailureReleasesStateAndRetryCanComplete() {
        assumeTrue(VinternModelManager.isReady(context))
        try {
            VinternModelManager.download(context) { throw IOException("synthetic-progress-callback-failure") }
            fail("Injected callback failure must propagate")
        } catch (_: IOException) { }
        assertFalse(VinternModelManager.isDownloading)
        VinternModelManager.download(context) { }
        assertFalse(VinternModelManager.isDownloading)
        assertEquals(100, VinternModelManager.downloadState.value.percent)
    }

    @Test fun missingNativeLibraryCannotDownloadOrRunInference() = runBlocking {
        val invalid = object : ContextWrapper(context) {
            override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
                nativeLibraryDir = cacheDir.resolve("missing-native-test").absolutePath
            }
        }
        val filesBefore = VinternModelManager.modelDir(context).listFiles()!!
            .associate { it.name to it.length() }
        var progressCalls = 0
        try {
            VinternModelManager.download(invalid) { progressCalls++ }
            fail("Download must reject missing native library")
        } catch (_: IOException) { }
        assertEquals(0, progressCalls)
        assertFalse(VinternModelManager.isDownloading)
        assertEquals(filesBefore, VinternModelManager.modelDir(context).listFiles()!!
            .associate { it.name to it.length() })
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        assertSame(VinternEngine.Result.Failed, VinternEngine.analyze(invalid, image, VinternEngine.Mode.DESCRIBE))
        assertTrue(image.isRecycled)
    }

    @Test fun installedModelCanDescribeAndReadSyntheticImages() = runBlocking {
        // Giữ tiến trình foreground; camera/nhận diện liên tục không được tranh
        // CPU/GPU với phép đo engine. Luồng camera được kiểm tra riêng trong app.
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            assertNull(VinternRequirements.read(context).inferenceBlocker)
            assumeTrue("Model files must be prepared on the test device", VinternModelManager.isReady(context))
            var progress = 0
            VinternModelManager.download(context) { progress = it }
            assertEquals(100, progress)
            repeat(2) {
                val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                val result = VinternEngine.analyze(context, image, VinternEngine.Mode.DESCRIBE)
                assertTrue("Native inference must return non-empty text", result is VinternEngine.Result.Success)
                assertTrue(VinternNative.nativeBackendName() in setOf("OpenCL", "OpenCL+CPU"))
                assertTrue(image.isRecycled)
            }
            // Dùng cùng Activity để phép đo giữ foreground suốt cả hai chế độ.
            val image = Bitmap.createBitmap(896, 448, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.WHITE)
            Canvas(image).drawText("12345", 90f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 230f
                typeface = Typeface.MONOSPACE
            })
            val result = VinternEngine.analyze(context, image, VinternEngine.Mode.READ)
            assertTrue("Native document reading must succeed", result is VinternEngine.Result.Success)
            val syntheticText = (result as VinternEngine.Result.Success).text
            assertEquals("Synthetic digits must be read in order: $syntheticText",
                "12345", syntheticText.filter(Char::isDigit))
            assertTrue(VinternNative.nativeBackendName() in setOf("OpenCL", "OpenCL+CPU"))
            assertTrue(image.isRecycled)
        } finally {
            VinternEngine.release()
            activity.close()
        }
    }
}
