package com.vietassist.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Từ điển nhãn tiếng Việt cho LUMORA.
 * - Tải dictionary.json từ GitHub Raw khi có mạng.
 * - Lưu bản tải thành công trong SharedPreferences để dùng offline.
 * - Luôn có từ điển dự phòng nếu chưa tải được.
 *
 * Chỉ dịch nhãn model trả về, không bổ sung lớp nhận diện cho EfficientDet.
 */
object OnlineVietnameseDictionary {
    private const val TAG = "LUMORA_DICTIONARY"
    private const val PREFS = "lumora_dictionary_cache"
    private const val KEY_JSON = "dictionary_json"
    private const val KEY_VERSION = "dictionary_version"

    private const val DICTIONARY_URL =
        "https://raw.githubusercontent.com/Kaises305-sudo/lumora-vietnamese-dictionary/main/dictionary.json"

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var loaded = false

    private val fallbackLabels = mapOf(
        "person" to "người",
        "bicycle" to "xe đạp",
        "car" to "ô tô",
        "motorcycle" to "xe máy",
        "airplane" to "máy bay",
        "bus" to "xe buýt",
        "train" to "tàu hỏa",
        "truck" to "xe tải",
        "boat" to "thuyền",
        "traffic light" to "đèn giao thông",
        "fire hydrant" to "trụ cứu hỏa",
        "stop sign" to "biển báo dừng",
        "parking meter" to "máy tính tiền đỗ xe",
        "bench" to "ghế băng",
        "bird" to "chim",
        "cat" to "mèo",
        "dog" to "chó",
        "horse" to "ngựa",
        "sheep" to "cừu",
        "cow" to "bò",
        "elephant" to "voi",
        "bear" to "gấu",
        "zebra" to "ngựa vằn",
        "giraffe" to "hươu cao cổ",
        "backpack" to "ba lô",
        "umbrella" to "ô",
        "handbag" to "túi xách",
        "suitcase" to "va li",
        "bottle" to "chai",
        "cup" to "cốc",
        "wine glass" to "ly thủy tinh",
        "bowl" to "bát",
        "banana" to "quả chuối",
        "apple" to "quả táo",
        "sandwich" to "bánh mì kẹp",
        "orange" to "quả cam",
        "broccoli" to "bông cải xanh",
        "carrot" to "cà rốt",
        "hot dog" to "xúc xích kẹp bánh mì",
        "pizza" to "bánh pizza",
        "donut" to "bánh vòng",
        "cake" to "bánh ngọt",
        "chair" to "ghế",
        "couch" to "ghế sofa",
        "potted plant" to "chậu cây",
        "bed" to "giường",
        "dining table" to "bàn ăn",
        "toilet" to "bồn cầu",
        "tv" to "ti vi",
        "laptop" to "máy tính xách tay",
        "mouse" to "chuột máy tính",
        "remote" to "điều khiển từ xa",
        "keyboard" to "bàn phím",
        "cell phone" to "điện thoại",
        "microwave" to "lò vi sóng",
        "oven" to "lò nướng",
        "toaster" to "máy nướng bánh mì",
        "sink" to "bồn rửa",
        "refrigerator" to "tủ lạnh",
        "book" to "quyển sách",
        "clock" to "đồng hồ",
        "vase" to "bình hoa",
        "scissors" to "kéo",
        "teddy bear" to "gấu bông",
        "hair drier" to "máy sấy tóc",
        "toothbrush" to "bàn chải đánh răng",
        "motorbike" to "xe máy",
        "bike" to "xe đạp",
        "sofa" to "ghế sofa",
        "mobile phone" to "điện thoại",
        "cellphone" to "điện thoại",
        "smartphone" to "điện thoại thông minh",
        "purse" to "ví hoặc túi nhỏ",
        "wallet" to "ví",
        "door" to "cửa",
        "window" to "cửa sổ",
        "stairs" to "cầu thang",
        "staircase" to "cầu thang",
        "table" to "bàn",
        "desk" to "bàn làm việc",
        "monitor" to "màn hình máy tính",
        "computer" to "máy tính",
        "printer" to "máy in",
        "fan" to "quạt",
        "lamp" to "đèn",
        "light" to "đèn",
        "plant" to "cây",
        "tree" to "cây",
        "flower" to "hoa",
        "cupboard" to "tủ",
        "cabinet" to "tủ",
        "box" to "hộp",
        "package" to "gói hàng",
        "helmet" to "mũ bảo hiểm",
        "shoe" to "giày",
        "shoes" to "giày",
        "slipper" to "dép",
        "slippers" to "dép",
        "hat" to "mũ",
        "clothing" to "quần áo",
        "shirt" to "áo",
        "pants" to "quần",
        "person sitting" to "người đang ngồi",
        "human" to "người",
        "road" to "đường",
        "street" to "đường",
        "pavement" to "vỉa hè",
        "sidewalk" to "vỉa hè",
        "fence" to "hàng rào",
        "pole" to "cột",
        "post" to "cột",
        "sign" to "biển báo",
        "traffic sign" to "biển báo giao thông",
        "stairs railing" to "tay vịn cầu thang",
        "water" to "nước",
        "food" to "thức ăn",
        "plate" to "đĩa",
        "pan" to "chảo",
        "pot" to "nồi",
        "key" to "chìa khóa",
        "keys" to "chùm chìa khóa",
        "remote control" to "điều khiển từ xa",
        "air conditioner" to "máy điều hòa",
        "aircon" to "máy điều hòa",
        "washing machine" to "máy giặt",
        "fan heater" to "quạt sưởi",
        "unknown" to "vật thể không xác định"
    )
    @Volatile private var labels: Map<String, String> = fallbackLabels
    /** Gọi từ Activity khi app mở; không chặn luồng giao diện. */
    fun initialize(context: Context) {
        if (loaded) {
            refresh(context.applicationContext)
            return
        }
        synchronized(this) {
            if (loaded) return
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            // Dùng cache ngay lập tức nếu có; mạng không phải điều kiện bắt buộc.
            val cached = prefs.getString(KEY_JSON, null)
            if (!cached.isNullOrBlank()) {
                parseDictionary(cached)?.let { labels = fallbackLabels + it }
            }
            loaded = true
            refresh(appContext)
        }
    }

    /** Cho phép người dùng tiếp tục dùng từ điển hiện tại khi mạng lỗi. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(DICTIONARY_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3500
                    readTimeout = 3500
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    Log.w(TAG, "Không tải được từ điển; HTTP $status. Tiếp tục dùng bản cache/dự phòng.")
                    return@execute
                }
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseDictionary(json)
                if (parsed.isNullOrEmpty()) {
                    Log.w(TAG, "JSON từ điển không hợp lệ hoặc không có nhãn.")
                    return@execute
                }
                labels = fallbackLabels + parsed
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_JSON, json)
                    .putInt(KEY_VERSION, JSONObject(json).optInt("version", 1))
                    .apply()
                Log.i(TAG, "Đã cập nhật ${parsed.size} nhãn tiếng Việt từ nguồn online.")
            } catch (error: Exception) {
                Log.w(TAG, "Không có mạng hoặc tải từ điển thất bại; dùng cache/dự phòng.", error)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun translate(label: String): String? {
        val key = label.trim().lowercase(Locale.ROOT)
        return labels[key] ?: fallbackLabels[key]
    }

    fun currentVersion(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VERSION, 0)

    private fun parseDictionary(json: String): Map<String, String>? {
        return try {
            val root = JSONObject(json)
            val source = root.optJSONObject("labels") ?: return null
            buildMap {
                val keys = source.keys()
                while (keys.hasNext()) {
                    val key = keys.next().trim().lowercase(Locale.ROOT)
                    val value = source.optString(key, "").trim()
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }.takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            Log.w(TAG, "Không phân tích được dictionary.json", error)
            null
        }
    }
}
