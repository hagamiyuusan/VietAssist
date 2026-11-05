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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class LightOnLifecycleTest {
    private val cancellation = AtomicLong()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun lateObserverSeesCompletedDownloadWithoutScreenCallbacks() = runBlocking {
        assumeTrue(LightOnModelManager.isReady(context))
        LightOnModelManager.download(context) { }
        // Tương đương màn hình mới đăng ký sau khi callback của màn hình cũ đã kết thúc.
        val state = LightOnModelManager.downloadState.first()
        assertFalse(state.downloading)
        assertEquals(100, state.percent)
        assertTrue(LightOnModelManager.isReady(context))
    }

    @Test fun secondDownloadCannotResetProgressOrUnlockCurrentDownload() {
        assumeTrue(LightOnModelManager.isReady(context))
        var rejected = 0
        var secondProgressCalls = 0
        LightOnModelManager.download(context) { percent ->
            val before = LightOnModelManager.downloadState.value
            assertTrue(before.downloading)
            assertEquals(percent, before.percent)
            try {
                LightOnModelManager.download(context) { secondProgressCalls++ }
                fail("Second download must be rejected")
            } catch (_: IOException) { rejected++ }
            assertEquals(before, LightOnModelManager.downloadState.value)
        }
        assertTrue(rejected > 0)
        assertEquals(0, secondProgressCalls)
        assertFalse(LightOnModelManager.isDownloading)
        assertEquals(100, LightOnModelManager.downloadState.value.percent)
    }

    @Test fun downloadFailureReleasesStateAndRetryCanComplete() {
        assumeTrue(LightOnModelManager.isReady(context))
        try {
            LightOnModelManager.download(context) { throw IOException("synthetic-progress-callback-failure") }
            fail("Injected callback failure must propagate")
        } catch (_: IOException) { }
        assertFalse(LightOnModelManager.isDownloading)
        LightOnModelManager.download(context) { }
        assertFalse(LightOnModelManager.isDownloading)
        assertEquals(100, LightOnModelManager.downloadState.value.percent)
    }

    @Test fun missingNativeLibraryCannotDownloadOrRunInference() = runBlocking {
        val invalid = object : ContextWrapper(context) {
            override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
                nativeLibraryDir = cacheDir.resolve("missing-native-test").absolutePath
            }
        }
        val filesBefore = LightOnModelManager.modelDir(context).listFiles()!!
            .associate { it.name to it.length() }
        var progressCalls = 0
        try {
            LightOnModelManager.download(invalid) { progressCalls++ }
            fail("Download must reject missing native library")
        } catch (_: IOException) { }
        assertEquals(0, progressCalls)
        assertFalse(LightOnModelManager.isDownloading)
        assertEquals(filesBefore, LightOnModelManager.modelDir(context).listFiles()!!
            .associate { it.name to it.length() })
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        assertSame(LightOnEngine.Result.Failed, LightOnEngine.analyze(invalid, image, LightOnEngine.Mode.READ, cancellation))
        assertTrue(image.isRecycled)
    }

}
