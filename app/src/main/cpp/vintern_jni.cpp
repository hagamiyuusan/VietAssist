// Cầu nối JNI giữa LUMORA và llama.cpp/libmtmd để chạy Vintern-1B-v3.5 trên máy.
// GPU OpenCL mã hóa ảnh; trên Adreno 735, CPU sinh văn bản nhanh hơn GPU.
// Kotlin bảo đảm chỉ một luồng gọi vào đây.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <exception>
#include <memory>
#include <vector>
#include <mutex>
#include <string>

#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LUMORA_VINTERN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Lời hệ thống gốc của Vintern (mẫu hội thoại Hermes-2 trong repo 5CD-AI).
constexpr const char * SYSTEM_PROMPT =
        "Bạn là một mô hình trí tuệ nhân tạo đa phương thức Tiếng Việt có tên gọi là Vintern, "
        "được phát triển bởi người Việt. Bạn là một trợ lý trí tuệ nhân tạo hữu ích và không gây hại.";

constexpr int N_CTX   = 4096;
constexpr int N_BATCH = 512;

std::mutex g_mutex;
std::atomic<bool> g_cancel{false};
llama_model   * g_model = nullptr;
llama_context * g_lctx  = nullptr;
mtmd_context  * g_mctx  = nullptr;
std::string g_backend_name;

// Các file GGUF Vintern hiện có không khai báo tokenizer.ggml.eos_token_id, nên
// llama.cpp lấy mặc định id 11 (dấu phẩy trong từ vựng Qwen) làm token kết thúc
// và câu trả lời bị cắt ở dấu phẩy đầu tiên. Vì vậy tự tìm token kết thúc của
// mẫu hội thoại ChatML thay vì dùng llama_vocab_is_eog().
llama_token g_im_end     = LLAMA_TOKEN_NULL;
llama_token g_endoftext  = LLAMA_TOKEN_NULL;

llama_token single_special_token(const llama_vocab * vocab, const char * text) {
    llama_token tokens[4];
    const int32_t n = llama_tokenize(vocab, text, static_cast<int32_t>(strlen(text)),
                                     tokens, 4, false, true);
    return n == 1 ? tokens[0] : LLAMA_TOKEN_NULL;
}

bool is_stop_token(const llama_vocab * vocab, llama_token token) {
    if (g_im_end == LLAMA_TOKEN_NULL && g_endoftext == LLAMA_TOKEN_NULL) {
        return llama_vocab_is_eog(vocab, token);
    }
    return token == g_im_end || token == g_endoftext;
}

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, TAG, text);
}

void free_all() {
    g_backend_name.clear();
    if (g_mctx) { mtmd_free(g_mctx); g_mctx = nullptr; }
    if (g_lctx) { llama_free(g_lctx); g_lctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

std::string to_std_string(JNIEnv * env, jstring value) {
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jbyteArray to_byte_array(JNIEnv * env, const std::string & value) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(value.size()),
                            reinterpret_cast<const jbyte *>(value.data()));
    return array;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_vietassist_app_VinternNative_nativeInit(JNIEnv * env, jobject, jstring native_lib_dir, jstring cache_dir) {
    try {
        const std::string cache = to_std_string(env, cache_dir) + "/opencl";
        setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache.c_str(), 1);
        ggml_log_set(log_callback, nullptr);
        llama_log_set(log_callback, nullptr);
        mtmd_helper_log_set(log_callback, nullptr);
        // Các biến thể CPU của ggml là thư viện riêng; nạp từ thư mục native của APK.
        const std::string dir = to_std_string(env, native_lib_dir);
        ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
        LOGI("Đã khởi tạo ggml backend từ %s", dir.c_str());
    } catch (const std::exception &) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Không khởi tạo được GPU OpenCL");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vietassist_app_VinternNative_nativeLoad(
        JNIEnv * env, jobject, jstring model_path, jstring mmproj_path, jint n_threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all();
    try {

        const std::string model_file  = to_std_string(env, model_path);
        const std::string mmproj_file = to_std_string(env, mmproj_path);

        ggml_backend_dev_t gpu = nullptr;
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto device = ggml_backend_dev_get(i);
            if (std::strcmp(ggml_backend_reg_name(ggml_backend_dev_backend_reg(device)), "OpenCL") == 0) {
                gpu = device;
                break;
            }
        }
        if (!gpu) {
            LOGE("Không có GPU OpenCL phù hợp để chạy Vintern");
            return JNI_FALSE;
        }
        // Đã đo trên Adreno 735: GPU nhanh ở phần ảnh, CPU nhanh ở phần từng token.
        // Đây là cấu hình theo thiết bị, không tự chuyển backend khi lỗi.
        const bool cpu_language = std::strcmp(
                ggml_backend_dev_description(gpu), "QUALCOMM Adreno(TM) 735") == 0;
        ggml_backend_dev_t devices[] = { cpu_language ? nullptr : gpu, nullptr };

        llama_model_params model_params = llama_model_default_params();
        model_params.devices = devices;
        model_params.n_gpu_layers = cpu_language ? 0 : -1;
        g_model = llama_model_load_from_file(model_file.c_str(), model_params);
        if (!g_model) {
            LOGE("Không tải được mô hình ngôn ngữ: %s", model_file.c_str());
            return JNI_FALSE;
        }

        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        g_im_end    = single_special_token(vocab, "<|im_end|>");
        g_endoftext = single_special_token(vocab, "<|endoftext|>");
        LOGI("Token kết thúc: <|im_end|>=%d, <|endoftext|>=%d", g_im_end, g_endoftext);

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx           = N_CTX;
        ctx_params.n_batch         = N_BATCH;
        ctx_params.n_threads       = n_threads;
        ctx_params.n_threads_batch = n_threads;
        ctx_params.no_perf         = false;
        ctx_params.op_offload      = !cpu_language;
        ctx_params.offload_kqv     = !cpu_language;
        g_lctx = llama_init_from_model(g_model, ctx_params);
        if (!g_lctx) {
            LOGE("Không tạo được llama_context");
            free_all();
            return JNI_FALSE;
        }

        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu       = true;
        mtmd_params.device        = gpu;
        mtmd_params.n_threads     = n_threads;
        mtmd_params.print_timings = true;
        mtmd_params.warmup        = false;
        g_mctx = mtmd_init_from_file(mmproj_file.c_str(), g_model, mtmd_params);
        if (!g_mctx || !mtmd_support_vision(g_mctx)) {
            LOGE("Không tải được bộ mã hóa ảnh: %s", mmproj_file.c_str());
            free_all();
            return JNI_FALSE;
        }

        LOGI("Đã tải Vintern: ảnh GPU %s, ngôn ngữ %s, %d luồng CPU",
             ggml_backend_dev_description(gpu), cpu_language ? "CPU" : "GPU OpenCL", n_threads);
        g_backend_name = cpu_language ? "OpenCL+CPU" : "OpenCL";
        return JNI_TRUE;
    } catch (const std::exception &) {
        LOGE("Không khởi tạo được mô hình Vintern");
        free_all();
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vietassist_app_VinternNative_nativeBackendName(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_backend_name.c_str());
}

// Trả về văn bản UTF-8 dạng byte[] (Kotlin tự giải mã) để không lỗi khi
// câu trả lời bị cắt giữa một ký tự nhiều byte. Trả về null nếu lỗi hoặc bị hủy.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vietassist_app_VinternNative_nativeGenerate(
        JNIEnv * env, jobject, jbyteArray rgb, jint width, jint height,
        jstring question, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        g_cancel.store(false);
        if (!g_model || !g_lctx || !g_mctx) {
            LOGE("Vintern chưa được tải");
            return nullptr;
        }

        const jsize n_bytes = env->GetArrayLength(rgb);
        if (width <= 0 || height <= 0 || n_bytes != width * height * 3) {
            LOGE("Kích thước ảnh không hợp lệ: %dx%d, %d byte", width, height, n_bytes);
            return nullptr;
        }

        std::vector<unsigned char> pixels(n_bytes);
        env->GetByteArrayRegion(rgb, 0, n_bytes, reinterpret_cast<jbyte *>(pixels.data()));
        if (env->ExceptionCheck()) return nullptr;
        auto bitmap = std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>(
                mtmd_bitmap_init(static_cast<uint32_t>(width), static_cast<uint32_t>(height), pixels.data()),
                mtmd_bitmap_free);

        // Mỗi câu hỏi là một lượt độc lập; không giữ lịch sử hội thoại.
        llama_memory_clear(llama_get_memory(g_lctx), true);

        const std::string prompt =
                std::string("<|im_start|>system\n") + SYSTEM_PROMPT + "<|im_end|>\n" +
                "<|im_start|>user\n" + mtmd_default_marker() + "\n" +
                to_std_string(env, question) + "<|im_end|>\n" +
                "<|im_start|>assistant\n";

        mtmd_input_text text;
        text.text          = prompt.c_str();
        text.text_len      = prompt.size();
        text.add_special   = true;
        text.parse_special = true;

        auto chunks = std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)>(
                mtmd_input_chunks_init(), mtmd_input_chunks_free);
        const mtmd_bitmap * bitmaps[] = { bitmap.get() };
        const int32_t tokenize_result = mtmd_tokenize(g_mctx, chunks.get(), &text, bitmaps, 1);
        bitmap.reset();
        if (tokenize_result != 0) {
            LOGE("mtmd_tokenize lỗi: %d", tokenize_result);
            chunks.reset();
            return nullptr;
        }

        llama_pos n_past = 0;
        const int32_t eval_result = mtmd_helper_eval_chunks(
                g_mctx, g_lctx, chunks.get(), 0, 0, N_BATCH, true, &n_past);
        chunks.reset();
        if (eval_result != 0) {
            LOGE("Không xử lý được ảnh và câu hỏi: %d", eval_result);
            return nullptr;
        }
        LOGI("Đã nạp ảnh và câu hỏi: %d token", n_past);

        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
                llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        // Giải mã tham lam (giống do_sample=False của model card) kèm phạt lặp nhẹ,
        // vì mô hình nhỏ dễ lặp lại một cụm từ khi đọc văn bản dài.
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), 64, 1.15f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());

        std::string output;
        bool ok = true;
        char piece[256];
        for (int i = 0; i < max_tokens && n_past < N_CTX; ++i) {
            if (g_cancel.load()) {
                LOGW("Đã hủy sinh văn bản");
                ok = false;
                break;
            }
            llama_token token = llama_sampler_sample(sampler.get(), g_lctx, -1);
            if (is_stop_token(vocab, token)) break;

            const int32_t n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (n > 0) output.append(piece, n);

            llama_batch batch = llama_batch_get_one(&token, 1);
            if (llama_decode(g_lctx, batch) != 0) {
                LOGE("llama_decode lỗi ở token thứ %d", i);
                break;
            }
            ++n_past;
        }

        if (!ok) return nullptr;
        LOGI("Vintern trả lời %zu byte", output.size());
        return to_byte_array(env, output);
    } catch (const std::exception &) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Vintern chưa xử lý được ảnh");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vietassist_app_VinternNative_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vietassist_app_VinternNative_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all();
}
