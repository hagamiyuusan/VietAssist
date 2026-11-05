# LUMORA / VietAssist

Ứng dụng Android hỗ trợ nhận diện vật thể và đọc văn bản bằng tiếng Việt.

## Sử dụng

- Camera luôn hiển thị sau khi cấp quyền, cùng bounding box nhận diện vật thể. Không tự phát giọng đọc cảnh.
- Nút **Chụp · Đọc văn bản** hoặc lệnh **đọc văn bản** chụp ảnh và dùng bộ OCR trong Settings.
- **ML Kit là mặc định**, không cần tải model hoặc GPU OpenCL. Lựa chọn LightOnOCR đã lưu được giữ khi nâng cấp; lựa chọn Vintern cũ chuyển một lần về ML Kit.
- **LightOnOCR-3-0.8B** là lựa chọn OCR nâng cao duy nhất. Chỉ cho tải, chuẩn bị và chạy khi kiểm tra GPU OpenCL đạt; không chuyển sang CPU nếu lỗi.
- Lệnh **mô tả xung quanh** đọc các vật thể đang nhận diện trên camera, không gọi VLM. Vintern đã được gỡ khỏi model, bộ tải, JNI và giao diện.
- Nhấn để nói hoặc bật Luôn nghe trong Settings. Nhận dạng giọng nói do dịch vụ Android đảm nhiệm. Kiểm thử bằng dịch vụ giả lập không chứng minh nhận dạng giọng nói thực tế.

## LightOnOCR và kiểm tra GPU

`LightOnRequirements` là nguồn kiểm tra chung của Settings, bộ tải và engine: Android 9+, tiến trình 64 bit, thư viện native, GPU OpenCL được runtime tìm thấy, dung lượng và tình trạng RAM. Chỉ có file thư viện OpenCL chưa được coi là có GPU phù hợp. Chuẩn bị model còn phải nạp thành công và làm nóng trên GPU trước khi báo sẵn sàng.

`LightOnEngine` sở hữu một executor và runtime native. Encoder ảnh cùng toàn bộ layer ngôn ngữ được offload lên OpenCL, gồm KV cache và các phép toán offload được runtime hỗ trợ. CPU vẫn điều phối, xử lý ảnh đầu vào và tokenizer; không có chế độ chạy model ngôn ngữ bằng CPU. Không tự đổi OCR khi thiếu model, lỗi GPU hoặc suy luận thất bại.

LightOnOCR dùng prompt rỗng, chỉ gửi ảnh, theo [chế độ transcription chính thức](https://huggingface.co/lightonai/LightOnOCR-3-0.8B#prompt-modes). Mẫu hội thoại Qwen3.5 tắt thinking nằm ở `app/src/main/cpp/lighton_jni.cpp`. Native runtime: llama.cpp b11645; OpenCL Headers/loader v2026.05.29 được ghim SHA-256 trong CMake.

Model ngôn ngữ Q8_0 và encoder ảnh F16 có tổng 1.016.830.752 byte, nằm trong thư mục ứng dụng `files/lighton`. [Nguồn GGUF](https://huggingface.co/richardyoung/LightOnOCR-3-0.8B-GGUF/tree/5901dcc106d24a337ec0762a072cd58b2c330fa6) được ghim revision, kích thước, SHA-256 trong `LightOnModelManager.kt`. Bộ tải hỗ trợ tiếp tục file dở, chặn người ghi song song và xác minh hash trước khi công bố hoàn tất.

## Giới hạn đã biết

Trước khi chuyển toàn bộ layer ngôn ngữ sang GPU, ảnh mẫu tiếng Việt 1000×600 đọc đúng các cụm kiểm tra trong khoảng 8–10 giây; một ảnh camera 1920×856 mất khoảng 42 giây rồi trả rỗng. Các số này thuộc cấu hình cũ, không chứng minh tốc độ hoặc chất lượng bản GPU hiện tại. Chưa coi đọc tài liệu camera thực tế là đạt. Camera vẫn dùng ảnh ImageAnalysis, chưa có luồng chụp tài liệu độ phân giải cao.

Mỗi lượt chụp có mã riêng; lượt cũ và callback nhận giọng nói đến muộn không được ghi đè kết quả mới. Bitmap do engine sở hữu được giải phóng cả khi hủy.

## Build và kiểm thử

```sh
ANDROID_HOME=/home/huy/Android/Sdk JAVA_HOME=/home/huy/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -Pandroid.injected.build.abi=arm64-v8a
```

APK debug: `app/build/intermediates/apk/debug/app-debug.apk`. `LightOnDeviceTest` kiểm tra đọc ảnh tiếng Việt tổng hợp trên backend OpenCL. Các kiểm thử Settings kiểm tra ML Kit mặc định, lựa chọn OCR, chặn tải khi thiếu GPU, và tiếp tục dùng chức năng cơ bản. Không ghi ảnh hoặc nội dung OCR của người dùng vào log.

Backend cuối dùng OpenCL và model Q8_0. Bản Q4_K_M không chạy được token ngôn ngữ GPU trên thiết bị Adreno 735 đã thử. Probe OpenCL + Q8_0 đã đọc đúng mẫu chữ số công khai “12345” ở ảnh 480×160, khoảng 1,57 giây. Đây là phép thử chức năng, chưa phải benchmark chất lượng OCR ảnh camera. Không có cấu hình chuyển model về CPU.

Bản OpenCL + Q8_0 đã qua kiểm thử ảnh tiếng Việt bốn dòng 1000×600 trên Adreno 735: các cụm có dấu và chữ số đúng, suy luận khoảng 15,6 giây trong một lượt. Không coi đây là benchmark tốc độ trung vị; chưa chứng minh chất lượng tài liệu camera thực tế. Toàn bộ 25/25 layer ngôn ngữ được offload lên GPU, encoder ảnh cũng dùng OpenCL. Kernel được cache trong thư mục cache riêng của app.

Xác minh bản cài cuối trên iQOO Z9 Turbo / Adreno 735: build và lint qua, 16 unit test, 2 kiểm thử LightOn GPU và 9 kiểm thử Settings/OCR/lệnh qua. Lượt chuẩn bị sau khi giữ cache khoảng 4,6 giây; lượt ảnh tiếng Việt lặp lại khoảng 15,6 giây. Kiểm thử lệnh dùng recognition service giả lập, chưa xác nhận nhận giọng người dùng thực tế. Camera trực tiếp đã được kiểm tra đang phát, OCR trên điện thoại là ML Kit và bộ nghe thật đã khôi phục.
