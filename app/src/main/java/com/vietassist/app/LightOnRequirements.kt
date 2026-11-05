package com.vietassist.app

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.Build
import java.io.File

/** Chính sách dùng chung cho thiết lập, tải model và bắt đầu suy luận. */
data class LightOnRequirements(
    val is64Bit: Boolean,
    val nativeLibraryPresent: Boolean,
    val totalRam: Long,
    val availableRam: Long,
    val lowMemory: Boolean,
    val freeStorage: Long,
    val requiredStorage: Long,
    val gpuAvailable: Boolean = true,
    val androidSdk: Int = 28
) {
    val hardwareBlocker: String?
        get() = when {
            androidSdk < 28 -> "AI trên máy cần Android 9 trở lên. Camera và nhận diện vật thể vẫn dùng được."
            !is64Bit -> "AI trên máy cần ứng dụng chạy ở chế độ 64 bit."
            !nativeLibraryPresent -> "Bản cài này thiếu thư viện AI cho máy. Hãy cài bản phù hợp."
            !gpuAvailable -> "AI trên máy cần driver GPU OpenCL được hãng điện thoại cung cấp. Camera và nhận diện vật thể vẫn dùng được."
            else -> null
        }

    val downloadBlocker: String?
        get() = hardwareBlocker ?: if (freeStorage < requiredStorage) {
            "Không đủ dung lượng tải mô hình. Hãy giải phóng bộ nhớ rồi kiểm tra lại."
        } else null

    val inferenceBlocker: String?
        get() = hardwareBlocker ?: if (lowMemory) {
            "Android đang báo thiếu RAM. Hãy đóng bớt ứng dụng rồi thử lại AI."
        } else null

    // Khuyến nghị của ứng dụng, chưa phải ngưỡng hiệu năng đã benchmark.
    val ramWarning: Boolean
        get() = totalRam < (4L shl 30) || availableRam < (2L shl 30)

    companion object {
        fun read(context: Context, store: VisionModelStore = LightOnModelManager): LightOnRequirements {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            return LightOnRequirements(
                is64Bit = Process.is64Bit(),
                nativeLibraryPresent = File(context.applicationInfo.nativeLibraryDir, "liblighton.so").isFile,
                totalRam = memory.totalMem,
                availableRam = memory.availMem,
                lowMemory = memory.lowMemory,
                freeStorage = context.filesDir.usableSpace,
                requiredStorage = store.requiredDownloadSpace(context),
                gpuAvailable = File(context.applicationInfo.nativeLibraryDir, "liblighton.so").isFile &&
                    GpuRuntime.available(context.applicationInfo.nativeLibraryDir),
                androidSdk = Build.VERSION.SDK_INT
            )
        }
    }
}

/** Cache the native OpenCL device probe; library presence alone is insufficient. */
private object GpuRuntime {
    private val checked = mutableMapOf<String, Boolean>()
    @Synchronized fun available(nativeLibraryDir: String): Boolean = checked.getOrPut(nativeLibraryDir) {
        try {
            LightOnNative.nativeGpuAvailable(nativeLibraryDir)
        } catch (_: UnsatisfiedLinkError) { false }
          catch (_: RuntimeException) { false }
    }
}
