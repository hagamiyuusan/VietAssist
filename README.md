# LUMORA / VietAssist

Ứng dụng Android hỗ trợ quan sát và đọc nội dung bằng tiếng Việt.

## Các luồng xử lý

- **Camera trực tiếp:** luôn hiện sau khi cấp quyền camera. EfficientDet vẽ khung, nhãn và độ tin cậy của vật thể; tọa độ được chuyển sang preview qua cùng CameraX viewport, có xử lý xoay và crop.
- **Chụp · mô tả xung quanh:** chụp một ảnh gửi Vintern. Camera tiếp tục phát; EfficientDet tạm dừng trong lúc xử lý để hạn chế tranh tài nguyên. Kết quả dưới camera thuộc ảnh vừa chụp; không thay preview bằng ảnh tĩnh.
- **Đọc chữ:** ra lệnh “đọc văn bản”, “đọc tài liệu” hoặc “hãy đọc”. Luồng chụp dùng **OCR mặc định** trong Thiết lập: **ML Kit** (mặc định, không cần Vintern) hoặc **Vintern** (cần model sẵn sàng). Không tự đổi backend khi lỗi. “Đọc kỹ/đọc chi tiết” cũng dùng cùng lựa chọn OCR; không có hai nút đọc riêng trên màn hình chính.

`OcrSettings` là nguồn duy nhất cho lựa chọn OCR. Mỗi lượt đọc giữ lựa chọn tại thời điểm bắt đầu; thay đổi Settings không làm đổi backend của lượt đang xử lý. “Đọc lại” đọc kết quả chữ đã lưu; “chụp lại/nhận diện lại” dùng lựa chọn hiện tại để lấy ảnh mới. Các lệnh tạm dừng quan sát chỉ dừng nhận diện vật thể, camera vẫn hiển thị.

## LightOnOCR cho đọc văn bản

Trong **Thiết lập → OCR mặc định**, chọn **LightOnOCR 0.8B**, sau đó **Tải LightOnOCR**. Đây là LightOnOCR-3-0.8B với model ngôn ngữ Q4_K_M và bộ mã hóa ảnh F16, tổng 734.284.576 byte (khoảng 700 MiB), tách biệt khỏi file Vintern. Lựa chọn OCR vẫn có mặc định ML Kit; app không tự đổi backend nếu thiếu model hoặc suy luận lỗi.

Nguồn gốc: [LightOn chính thức](https://huggingface.co/lightonai/LightOnOCR-3-0.8B); [GGUF dùng trong app](https://huggingface.co/richardyoung/LightOnOCR-3-0.8B-GGUF/tree/5901dcc106d24a337ec0762a072cd58b2c330fa6). Mỗi file được ghim revision, kích thước và SHA-256 trong `VinternModelManager.kt`; các model dùng chung bộ tải có tiếp tục tải và trạng thái riêng.

Runtime llama.cpp b11645 dùng chung một executor và chỉ giữ một model trong RAM. Đọc văn bản bằng LightOnOCR dùng ảnh và mẫu hội thoại Qwen3.5 tắt thinking, không chèn lời hệ thống Vintern hay phạt lặp làm đổi chữ. Mô tả cảnh luôn dùng Vintern; chuyển tác vụ có thể phải nạp model còn lại. Trạng thái chuẩn bị của mỗi model được giữ riêng và thu hồi khi model bị giải phóng. Hủy lượt đọc không cho phép kết quả cũ xuất hiện.

LightOnOCR nhận tối đa cạnh dài 2048 px và sinh tối đa 2048 token trong cấu hình hiện tại. Luồng camera hiện vẫn lấy ảnh phân tích có độ phân giải mục tiêu 1280×720, nên cấu hình này chưa thay thế bước chụp tài liệu độ phân giải cao. Chưa có benchmark chất lượng ảnh chụp thực tế hay tốc độ LightOnOCR trên Android; kết quả ảnh tổng hợp trên máy phát triển không thay thế bằng chứng trên điện thoại.

`LightOnDeviceTest` kiểm tra ảnh tiếng Việt tổng hợp, chữ số, thiếu model, khóa thiết bị không phù hợp và chuyển Vintern ↔ LightOn mà không chia sẻ sai trạng thái sẵn sàng. Chỉ chạy kiểm thử suy luận khi đã có file model trên thiết bị; không ghi ảnh hoặc văn bản người dùng vào log.

## Điều khiển bằng giọng nói

Trong **Thiết lập → Điều khiển bằng giọng nói**, chọn:

- **Nhấn để nói** (mặc định): nhấn nút trên màn hình chính để mở một phiên nhận lệnh tiếng Việt ngay trong app bằng `SpeechRecognizer` của Android. Nhận kết quả hoặc hủy thì phiên kết thúc, không tự nghe lại và không chạy service nghe nền. Nút đổi thành “Đang nghe… Nhấn để dừng” trong phiên; có thể bấm để hủy. Nếu máy thiếu dịch vụ nhận giọng nói, app báo rõ và các nút chức năng vẫn dùng được.
- **Luôn nghe**: nghe lệnh tự động khi app ở phía trước và nghe câu gọi khi chạy nền. Cần quyền micro; có thể tốn pin hơn. Khi đổi về Nhấn để nói, app dừng service nghe nền và bỏ callback của phiên nghe cũ.

Khi nhận lệnh hợp lệ, màn hình hiện “Đã nhận lệnh” và tác vụ tương ứng; lệnh đọc hiển thị cả bộ OCR đang chọn. Trạng thái đang nghe và lỗi nhận dạng hiển thị ở cả hai chế độ. Lệnh ngắn đầy đủ có thể được nhận ngay từ kết quả tạm thời, không phải chờ dịch vụ chốt cả câu. Mỗi phiên nghe có danh tính riêng; kết thúc phiên sẽ giải phóng recognizer và bỏ callback đến muộn, kể cả khi dịch vụ bị ngắt kết nối. App cho phép lệnh ngắn ngắt lời đọc vật thể, đồng thời chặn nhận lại chính câu hướng dẫn đang phát.

Trong Thiết lập có trạng thái giọng Việt, nút nghe thử và mở cài đặt giọng đọc hệ thống. Activity và service dùng chung chính sách kiểm tra giọng `vi` đã cài; app không tự dùng giọng ngôn ngữ khác.

`VoiceSettings` lưu và cung cấp cùng một lựa chọn cho màn hình, Activity và service. Các lệnh nhận được ở cả hai chế độ đều đi qua cùng hàm xử lý lệnh. Việc nhận dạng giọng nói do dịch vụ Android cung cấp, không phải Vintern; khả năng hoạt động khi không có mạng phụ thuộc dịch vụ trên máy.

## Tải model và chuẩn bị AI

Tải đủ file chỉ có nghĩa là model đã nằm trên bộ nhớ lưu trữ. Khi mở ứng dụng, engine nạp model vào RAM và chạy một ảnh đen tổng hợp để chuẩn bị các kernel trước khi công bố **AI đã sẵn sàng**. Giao diện hiển thị trạng thái chuẩn bị; OCR vẫn sử dụng được trong lúc chờ nếu chọn ML Kit.

Engine thuộc tiến trình ứng dụng, được dùng lại khi đổi hoặc mở lại Activity. Đóng màn hình không chủ động giải phóng model. Nếu Android kết thúc tiến trình, lần mở tiếp theo cần chuẩn bị lại. Đây là đánh đổi RAM để giảm thời gian chờ khi sử dụng AI.

Lỗi chuẩn bị được hiển thị và có thao tác thử lại; không tự đổi sang model khác hay chặn chức năng OCR cơ bản.

## Build và kiểm thử

Cấu hình Android SDK qua `local.properties` hoặc `ANDROID_HOME`, và JDK tương thích với Gradle wrapper. Cho máy ARM64:

```sh
bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug -Pandroid.injected.build.abi=arm64-v8a
```

APK debug hiện được tạo tại `app/build/intermediates/apk/debug/app-debug.apk`. Kiểm thử suy luận cần điện thoại có driver OpenCL và hai file Vintern đã tải. `VinternDeviceTest` kiểm tra chuẩn bị/làm nóng, hủy yêu cầu đang xếp hàng, tái sử dụng model và đọc ảnh tổng hợp. Các kiểm thử Compose kiểm tra trạng thái chuẩn bị, lựa chọn OCR và lưu cài đặt. Kiểm thử ảnh tổng hợp kiểm tra đọc ML Kit và phép ánh xạ khung vào preview.

## Lịch sử Git

Git được khởi tạo ngày 2026-11-04. Commit `ac10e34` là ảnh chụp code đang có trước sửa đổi khởi động/UI lần này; không phải lịch sử nguyên thủy trước toàn bộ quá trình phát triển. Model tải về, APK, cache build và cấu hình máy cá nhân được loại khỏi Git.
