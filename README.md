# LUMORA / VietAssist

Ứng dụng Android hỗ trợ quan sát và đọc nội dung bằng tiếng Việt.

## Các luồng xử lý

- **Quan sát:** CameraX → EfficientDet → nhãn/vị trí vật thể → giọng đọc.
- **Đọc chữ nhanh:** CameraX → ảnh OCR tăng tương phản → ML Kit Text Recognition → giọng đọc. Không cần Vintern. Nhận kết quả đầu tiên có chữ; nói “nhận diện lại” để lấy phần khác, “đọc lại” để nghe lại kết quả đã lưu.
- **Mô tả ảnh / đọc chi tiết AI:** chụp một ảnh → đóng camera → Vintern → giọng đọc. Trên Adreno 735, GPU OpenCL xử lý ảnh và CPU sinh văn bản theo số đo trên thiết bị. “Trở lại camera” mở lại luồng quan sát.

Các lệnh “đọc văn bản”, “đọc tài liệu”, “hãy đọc” dùng OCR nhanh. “Đọc chi tiết”, “đọc kỹ” dùng Vintern.

## Tải model và chuẩn bị AI

Tải đủ file chỉ có nghĩa là model đã nằm trên bộ nhớ lưu trữ. Khi mở ứng dụng, engine nạp model vào RAM và chạy một ảnh đen tổng hợp để chuẩn bị các kernel trước khi công bố **AI đã sẵn sàng**. Giao diện hiển thị trạng thái chuẩn bị; OCR nhanh vẫn sử dụng được trong lúc chờ.

Engine thuộc tiến trình ứng dụng, được dùng lại khi đổi hoặc mở lại Activity. Đóng màn hình không chủ động giải phóng model. Nếu Android kết thúc tiến trình, lần mở tiếp theo cần chuẩn bị lại. Đây là đánh đổi RAM để giảm thời gian chờ khi sử dụng AI.

Lỗi chuẩn bị được hiển thị và có thao tác thử lại; không tự đổi sang model khác hay chặn chức năng OCR cơ bản.

## Build và kiểm thử

Cấu hình Android SDK qua `local.properties` hoặc `ANDROID_HOME`, và JDK tương thích với Gradle wrapper. Cho máy ARM64:

```sh
bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug -Pandroid.injected.build.abi=arm64-v8a
```

APK debug hiện được tạo tại `app/build/intermediates/apk/debug/app-debug.apk`. Kiểm thử suy luận cần điện thoại có driver OpenCL và hai file Vintern đã tải. `VinternDeviceTest` kiểm tra chuẩn bị/làm nóng, hủy yêu cầu đang xếp hàng, tái sử dụng model và đọc ảnh tổng hợp. Các kiểm thử Compose kiểm tra trạng thái chuẩn bị và khóa đúng thao tác AI, giữ OCR nhanh khả dụng.

## Lịch sử Git

Git được khởi tạo ngày 2026-11-04. Commit `ac10e34` là ảnh chụp code đang có trước sửa đổi khởi động/UI lần này; không phải lịch sử nguyên thủy trước toàn bộ quá trình phát triển. Model tải về, APK, cache build và cấu hình máy cá nhân được loại khỏi Git.
