package com.vietassist.app

import android.graphics.*
import android.util.Size
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
class DetectionOverlayTest {
    @Test fun captureMatchesVisibleRegionAndRetainsItsOwnPixels() {
        val imageTransform = OutputTransform(Matrix(), Size(100, 200))
        val previewTransform = OutputTransform(Matrix().apply { setScale(2f, 2f); postTranslate(-30f, 10f) }, Size(100, 200))
        assertEquals(RectF(15f, -5f, 115f, 195f), visibleRegionForPreview(imageTransform, previewTransform, 200, 400))
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val full = copyVisibleFrame(source, RectF(0f, 0f, 64f, 64f))
        assertNotSame(source, full)
        val cropped = copyVisibleFrame(source, RectF(16f, 8f, 48f, 40f))
        assertEquals(32, cropped.width)
        assertEquals(32, cropped.height)
        try {
            copyVisibleFrame(source, RectF(80f, 80f, 90f, 90f))
            fail("Region outside the camera must be rejected")
        } catch (_: IllegalStateException) { }
        assertFalse(source.isRecycled)
        source.recycle()
        assertEquals(Color.RED, full.getPixel(0, 0))
        assertEquals(Color.RED, cropped.getPixel(0, 0))
        full.recycle()
        cropped.recycle()
    }
    @Test fun mappingUsesPreviewCropAndScaleAndOverlayClearsOldBoxes() {
        val source = OutputTransform(Matrix(), Size(100, 200))
        val target = OutputTransform(Matrix().apply { setScale(2f, 2f); postTranslate(-30f, 10f) }, Size(100, 200))
        val rect = RectF(20f, 30f, 60f, 70f)
        CoordinateTransform(source, target).mapRect(rect)
        assertEquals(RectF(10f, 70f, 90f, 150f), rect)
        val rotated = OutputTransform(Matrix().apply { setRotate(90f); postTranslate(200f, 0f) }, Size(100, 200))
        val rotationBox = RectF(20f, 30f, 60f, 70f)
        CoordinateTransform(source, rotated).mapRect(rotationBox)
        assertEquals(RectF(130f, 20f, 170f, 60f), rotationBox)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val overlay = DetectionOverlay(instrumentation.targetContext)
            overlay.layout(0, 0, 200, 400)
            overlay.update(listOf(DetectionBox(rect, "Tổng hợp")))
            assertEquals("Khung nhận diện: 1 vật thể", overlay.contentDescription)
            val image = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
            overlay.draw(Canvas(image))
            assertTrue(image.getPixel(10, 100) != Color.TRANSPARENT)
            overlay.update(listOf(DetectionBox(RectF(201f, 0f, 220f, 50f), "Ngoài khung")))
            assertEquals("Khung nhận diện: 0 vật thể", overlay.contentDescription)
            overlay.update(listOf(DetectionBox(RectF(-10f, 50f, 20f, 100f), "Một phần trong khung")))
            assertEquals("Khung nhận diện: 1 vật thể", overlay.contentDescription)
            overlay.update(emptyList())
            image.eraseColor(Color.TRANSPARENT)
            overlay.draw(Canvas(image))
            assertEquals(Color.TRANSPARENT, image.getPixel(10, 100))
            assertEquals("Khung nhận diện: 0 vật thể", overlay.contentDescription)
            image.recycle()
        }
    }
}
