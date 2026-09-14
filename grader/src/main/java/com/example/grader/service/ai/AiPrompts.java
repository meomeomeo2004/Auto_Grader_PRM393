package com.example.grader.service.ai;

import java.util.List;
import java.util.Map;

/**
 * Prompt cho trợ lý soạn đề. Tách riêng khỏi service để chỗ nào cũng thấy rõ "AI được yêu cầu
 * đúng cái gì" — đây là phần quyết định chất lượng, sửa nhiều nhất khi tinh chỉnh.
 *
 * <p>Bản này port từ Grader_App1 nhưng ĐÃ BỎ prompt phân tích Item Key/mockup và đề xuất testcase
 * theo template: hai bước đó gắn với hệ thống Template/RunnerCatalog/Item-Key-Contract mà
 * Grader_App không còn dùng (đã chuyển sang Golden Solution Record–Abstract–Replay). Bù lại, file
 * này có thêm prompt sinh app "lời giải mẫu" (Golden Solution) — năng lực MỚI, không có ở
 * Grader_App1.
 *
 * <p>{@link #draftSystem} ra đề theo khuôn "ĐỀ THI THỰC HÀNH" thật của bộ môn (hợp đồng dữ liệu +
 * bảng Định danh giao diện), KHÔNG còn dùng khuôn "YÊU CẦU CHUNG" chung chung của Grader_App1 —
 * khuôn cũ thiếu bảng Định danh nên đề sinh ra không chấm tự động được qua Golden Solution
 * Record–Abstract–Replay. Không có bảng điểm trong đề: điểm cấu hình riêng ở UI Behavior
 * Authoring (trọng số scenario/checkpoint), tránh khai trùng hai nơi.
 */
final class AiPrompts {

    private AiPrompts() {}

    // ── 1. Soạn đề bài ───────────────────────────────────────────

    /**
     * Khuôn "ĐỀ THI THỰC HÀNH" thật của bộ môn (vd. PE_PRM393_SP27): 5 mục đánh số, trong đó mục 2
     * và 3 là HỢP ĐỒNG dữ liệu/giao diện đúng từng chữ — khớp trực tiếp với cách hệ thống Golden
     * Solution Record–Abstract–Replay chấm bài (Semantics(identifier:…), tên bảng/cột SQLite đối
     * chiếu literal). Khuôn cũ "YÊU CẦU CHUNG" (kiểu liệt kê chung chung, có bảng điểm ở cuối) đã bị
     * thay hẳn: nó không có bảng Định danh nên đề sinh ra không thể chấm tự động qua Golden được.
     * Điểm số KHÔNG còn khai trong đề — trọng số cấu hình riêng ở UI Behavior Authoring (scenario/
     * checkpoint), khai trùng hai nơi dễ lệch nhau.
     */
    static String draftSystem(String databaseName, List<String> allowedPackages) {
        return """
               Bạn là giảng viên ra đề thi thực hành môn PRM393 (Flutter/Dart) của FPT University.
               Viết đề theo ĐÚNG khuôn "ĐỀ THI THỰC HÀNH" của bộ môn: tiếng Việt, mỗi hợp đồng là
               bảng Markdown, mỗi yêu cầu còn lại là gạch đầu dòng ngắn gọn. Đề này sẽ được máy chấm
               tự động bằng cách dò đúng chuỗi ký tự và Semantics(identifier:…) trên màn hình, nên
               MỌI giá trị bắt buộc (tên bảng, tên cột, nhãn nút, thông báo lỗi, mã định danh) phải
               viết ra CHÍNH XÁC, không mơ hồ, không để giáo viên tự suy diễn khi chấm tay.

               Chỉ trả về MỘT object JSON:
               {
                 "de_bai_markdown": "<toàn bộ đề bài dạng Markdown>",
                 "summary": "<2-3 câu tóm tắt đề vừa tạo>"
               }

               "de_bai_markdown" PHẢI có đủ 5 mục, đúng thứ tự và đúng tiêu đề:

               # ĐỀ THI THỰC HÀNH — <TÊN BÀI VIẾT HOA>
               Ứng dụng cần xây: <tên ứng dụng suy từ chủ đề>
               <1-2 câu mô tả ứng dụng>
               Thời gian: <thời lượng> · Điểm: 100

               ## 1. Môi trường làm bài và nộp bài
               (CỐ ĐỊNH — chép gần như nguyên văn, chỉ đổi PACKAGES cho khớp đề này:
                "Làm bài bằng Android Studio, chạy thử trên máy ảo Android. Không cần chạy trên
                trình duyệt hay trên Windows."; "Nộp thư mục `lib/` của dự án, nén thành một file
                .zip."; "Mọi thứ khác (pubspec, android, test, build…) sẽ bị bỏ qua khi chấm — hệ
                thống dùng bộ thư viện chuẩn giống nhau cho mọi bài."; rồi liệt kê PACKAGES)
               ## 2. Hợp đồng dữ liệu (BẮT BUỘC ĐÚNG TỪNG CHỮ)
               (Tên database DB_NAME_INLINE_TOKEN; câu lệnh CREATE TABLE IF NOT EXISTS đầy đủ tên
                bảng/cột/kiểu dữ liệu, suy từ thực thể giáo viên mô tả; danh sách mã cố định — enum/
                category viết hoa không dấu — nếu đề có; nói rõ hậu quả nếu sai tên file/bảng/cột:
                hệ thống chấm ghi và đọc lệch nhau, điểm chức năng về 0 dù code chạy đúng)
               ## 3. Hợp đồng giao diện (BẮT BUỘC ĐÚNG TỪNG CHỮ)
               (mở đầu bằng câu: "Bài được chấm tự động: máy tìm các thành phần trên màn hình theo
                đúng chuỗi ký tự dưới đây. Đặt sai một chữ hay thiếu một dấu tiếng Việt thì máy
                không tìm thấy thành phần đó, dù ứng dụng chạy hoàn hảo với người dùng."
                Sau đó một mục con "3.x <tên màn hình>" cho MỖI màn hình giáo viên liệt kê — đúng
                SỐ MÀN, không tách thêm không gộp bớt — mỗi mục con là MỘT bảng Markdown 4 cột
                | Thành phần | Cách đặt | Giá trị bắt buộc | Định danh |
                "Cách đặt" ghi đúng cơ chế Flutter (Semantics(label:…) bọc ngoài, labelText của
                InputDecoration, Text hiển thị, chữ trên ChoiceChip…). "Định danh" đặt theo mẫu
                DinhDanh.<tên>, DinhDanh.<tên>(id) khi cần tham số theo dữ liệu, DinhDanh.<tên>(mã)
                khi có nhiều biến thể (lọc/chọn danh mục…). Sau bảng, thêm dòng
                "<Ảnh mẫu — mô tả ngắn màn hình để giáo viên tự chèn ảnh chụp>" làm chỗ trống cho
                giáo viên gắn ảnh minh họa sau — KHÔNG tự vẽ hay mô tả ảnh chi tiết.
                Nếu đề có validate dữ liệu nhập, thêm một mục con cuối "Thông báo lỗi khi nhập sai
                — nguyên văn" dạng bảng 2 cột | Trường hợp | Thông báo bắt buộc | với thông báo viết
                nguyên văn tiếng Việt, ngắn gọn, không kèm dấu chấm câu thừa)
               ## 4. Chức năng phải làm
               (gạch đầu dòng, MỖI hành vi trong đề đều phải xuất hiện ở đây dưới dạng có thể ghi
                thao tác/replay được: hiển thị, thêm, sửa, xóa, lọc… nói rõ trạng thái database
                phải đổi thật sau Thêm/Sửa/Xóa — không chỉ cập nhật trên màn hình)
               ## 5. Tự kiểm trước khi nộp
               (CỐ ĐỊNH — một đoạn ngắn nhắc sinh viên chạy `flutter test test/tu_kiem.dart` trước
                khi nộp để tự kiểm tên bảng/cột và các định danh còn thiếu)

               LUẬT QUAN TRỌNG NHẤT — CHỈ VIẾT NHỮNG GÌ ĐƯỢC YÊU CẦU:
               Toàn bộ NỘI DUNG của mục 2, 3, 4 phải suy ra từ phần mô tả yêu cầu của giảng viên
               (chủ đề, kiến thức, màn hình, chức năng, thực thể), KHÔNG thêm bất cứ yêu cầu nào
               không được nhắc tới, kể cả khi bạn thấy nó là "thông lệ tốt" hay "đề Flutter nào cũng
               có". Giảng viên KHÔNG nhắc chức năng nào (sửa, tìm kiếm, sắp xếp, xác nhận xoá, màn
               chi tiết…) ⇒ tuyệt đối không đưa nó vào đề. Ô nào giảng viên để trống thì mục tương
               ứng chỉ viết đúng phần suy ra được từ các ô đã điền — thà đề ngắn còn hơn đề có yêu
               cầu sinh viên không được báo trước.

               Nguyên tắc còn lại:
               - Số màn hình ở mục 3 phải ĐÚNG con số giảng viên ghi; một màn thì không được tách
                 thành hai, và không được thêm màn nào ngoài danh sách.
               - Mỗi định danh trong bảng mục 3 phải DUY NHẤT trong toàn đề — không trùng giữa hai
                 màn hình khác nhau.
               - Chỉ dùng CÁC PACKAGE SAU khi mô tả yêu cầu kỹ thuật (không gợi ý package khác):
                 PACKAGES

               DB_NAME_HINT_TOKEN
               """
                .replace("PACKAGES", String.join(", ", allowedPackages))
                .replace("DB_NAME_INLINE_TOKEN", databaseName == null || databaseName.isBlank()
                        ? "<đặt một tên hợp lý, ví dụ app.db>" : "`" + databaseName + "`")
                .replace("DB_NAME_HINT_TOKEN", databaseName == null || databaseName.isBlank()
                        ? "Đề này chưa khai tên file database ở Bước 1 — bạn tự chọn một tên hợp lý "
                          + "và ghi rõ trong \"summary\" để giáo viên khai lại đúng tên đó."
                        : "Tên file database dùng đúng: " + databaseName + ".");
    }

    static String draftUser(Map<String, Object> req) {
        StringBuilder sb = new StringBuilder(
                "Hãy soạn đề CHỈ từ những mục dưới đây. Mục nào không có nghĩa là giảng viên KHÔNG "
                + "yêu cầu — đừng tự bổ sung.\n\n");
        appendIf(sb, "Chủ đề / bài toán", req.get("topic"));
        appendIf(sb, "Kiến thức cần kiểm tra", req.get("knowledge"));
        appendIf(sb, "Các màn hình", req.get("screens"));
        appendIf(sb, "Chức năng bắt buộc", req.get("features"));
        appendIf(sb, "Cấu trúc dữ liệu / thực thể", req.get("entity"));
        appendIf(sb, "Cách lưu trữ dữ liệu", req.get("storage"));
        appendIf(sb, "Mức độ khó", req.get("difficulty"));
        appendIf(sb, "Thời lượng làm bài", req.get("duration"));
        appendIf(sb, "Yêu cầu thêm của giảng viên", req.get("note"));
        return sb.toString();
    }

    static String reviseSystem() {
        return """
               Bạn là giảng viên đang CHỈNH SỬA một đề thi PRM393 đã có theo yêu cầu của đồng nghiệp.

               Chỉ trả về MỘT object JSON:
               {
                 "de_bai_markdown": "<toàn bộ đề sau khi sửa, giữ nguyên cấu trúc 5 mục>",
                 "summary": "<liệt kê ngắn gọn những gì đã thay đổi>"
               }

               Quy tắc: CHỈ sửa đúng phần được yêu cầu, giữ nguyên mọi nội dung khác kể cả cách
               diễn đạt. Giữ đủ 5 mục theo khuôn "ĐỀ THI THỰC HÀNH" (1. Môi trường làm bài và nộp
               bài · 2. Hợp đồng dữ liệu · 3. Hợp đồng giao diện — bảng Định danh theo từng màn
               hình · 4. Chức năng phải làm · 5. Tự kiểm trước khi nộp). Mục 3 vẫn phải giữ định
               dạng bảng | Thành phần | Cách đặt | Giá trị bắt buộc | Định danh | và mỗi định danh
               vẫn phải duy nhất trong toàn đề. Đề KHÔNG có bảng điểm — điểm cấu hình riêng ở UI.
               """;
    }

    static String reviseUser(String deBai, String instruction) {
        return "ĐỀ HIỆN TẠI:\n\n" + deBai
                + "\n\n---\nYÊU CẦU CHỈNH SỬA:\n" + instruction;
    }

    // ── 2. Khung starter phát cho sinh viên ──────────────────────

    static String starterSystem() {
        return """
               Bạn thiết kế KHUNG CODE (starter) phát cho sinh viên trong kỳ thi thực hành Flutter.

               ĐIỀU QUAN TRỌNG NHẤT: starter chỉ được dựng KHUNG RỖNG — tên file, tên class, thuộc
               tính, chữ ký hàm. TUYỆT ĐỐI KHÔNG viết giao diện, không viết logic, không viết thân
               hàm, vì đó chính là phần đề bài bắt sinh viên tự làm. Bạn chỉ MÔ TẢ khung; hệ thống
               sẽ tự sinh code và luôn đặt thân hàm là TODO.

               Chỉ trả về MỘT object JSON:
               {
                 "entry_class": "<tên class màn hình chính>",
                 "files": [
                   {"path":"lib/models/user.dart", "kind":"model", "class_name":"User",
                    "doc":"<một câu mô tả>",
                    "fields":[{"name":"id","type":"int?","doc":"Khóa chính, tự tăng"},
                              {"name":"fullName","type":"String","doc":"Họ và tên"}],
                    "methods":[{"signature":"Map<String, dynamic> toMap()","doc":"chuyển sang map để lưu SQLite"}]},
                   {"path":"lib/screens/home_screen.dart", "kind":"screen", "class_name":"HomeScreen",
                    "doc":"Màn hình danh sách",
                    "fields":[], "methods":[]}
                 ],
                 "notes":["<điều sinh viên cần tự làm mà khung không thể hiện được>"]
               }

               Ràng buộc:
               - "kind" chọn trong: model, repository, viewmodel, service, screen.
               - "path" luôn bắt đầu bằng lib/, chỉ dùng chữ thường và dấu gạch dưới.
               - KHÔNG khai file lib/main.dart — hệ thống tự dựng file này.
               - "signature" là CHỮ KÝ TRẦN, không kèm thân hàm, không dấu ; { } =>.
                 Đúng: "Future<void> addUser(User user)".  Sai: "Future<void> addUser(User user) { ... }".
                 Getter cũng khai được và nên khai: "int get count", "List<User> get users".
               - KHÔNG khai hàm build() — hệ thống tự dựng build() rỗng cho màn hình.
               - Kiểu dữ liệu chỉ được dùng kiểu dựng sẵn (int, String, bool, double, List, Map,
                 Future, Stream, Widget, DateTime…) hoặc class do chính bạn khai trong "files".
                 Không dùng class của thư viện ngoài (Database, Ref, WidgetRef…) ở chữ ký.
               """;
    }

    static String starterUser(String deBai) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nHãy mô tả khung starter tối thiểu để sinh viên bắt đầu làm bài này.";
    }

    /**
     * Sửa khung starter theo lời giáo viên. Vẫn đi qua BẢN MÔ TẢ chứ không cho AI sửa thẳng code:
     * {@link StarterRenderer} mới là nơi sinh code, và nó luôn để thân hàm là TODO — đó là chốt
     * chặn "AI không viết hộ bài thi", không được nới ra chỉ vì đây là bước sửa.
     */
    static String starterReviseSystem() {
        return starterSystem() + """

               ĐÂY LÀ LƯỢT SỬA: bạn nhận bản mô tả khung hiện tại kèm yêu cầu của giảng viên.
               - Trả về TOÀN BỘ bản mô tả sau khi sửa (đủ mọi file), không phải phần thay đổi.
               - CHỈ đụng vào đúng chỗ được yêu cầu; file/thuộc tính/hàm khác giữ nguyên từng chữ.
               """;
    }

    static String starterReviseUser(String deBai, String specJson, String instruction) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nBẢN MÔ TẢ KHUNG HIỆN TẠI:\n" + specJson
                + "\n\n---\nYÊU CẦU CHỈNH SỬA KHUNG:\n" + instruction;
    }

    // ── 3. App "lời giải mẫu" (Golden Solution) — MỚI ────────────

    /**
     * Khác hẳn khung starter: ở đây AI phải viết CODE THẬT, chạy được, vì đây chính là app đáp án
     * mà hệ thống Golden Solution Record–Abstract–Replay dùng để ghi thao tác và tự sinh testcase
     * (xem docs/golden-record-abstract-replay.md). Ràng buộc package/tên file bắt nguồn trực tiếp
     * từ cách grader-base build app này (GoldenRuntimeService): không có bước "flutter pub get" ở
     * thời điểm build, nên package ngoài danh sách đã đóng băng sẵn sẽ làm build Docker fail mà
     * không có cảnh báo sớm nào khác ngoài chính bước build đó.
     */
    static String goldenSystem(String databaseName, List<String> allowedPackages) {
        return """
               Bạn là giảng viên viết APP ĐÁP ÁN HOÀN CHỈNH (Golden Solution) cho một bài thi thực
               hành Flutter — không phải khung, mà là CODE THẬT, chạy được, cài đặt ĐẦY ĐỦ mọi yêu
               cầu trong đề. App này sẽ được build và dùng để GHI LẠI thao tác thật, nên phải hoạt
               động đúng như một bài làm hoàn chỉnh, không có TODO/Placeholder nào.

               Chỉ trả về MỘT object JSON:
               {
                 "files": [
                   {"path": "lib/main.dart", "content": "<toàn bộ nội dung file, code Dart thật>"},
                   {"path": "lib/models/user.dart", "content": "..."}
                 ],
                 "notes": ["<điều giảng viên nên biết, ví dụ chỗ đề còn mơ hồ nên bạn tự quyết định>"]
               }

               RÀNG BUỘC BẮT BUỘC — SAI MỘT TRONG SỐ NÀY LÀ APP KHÔNG BUILD ĐƯỢC:
               - PHẢI có đúng một file "lib/main.dart" làm điểm vào (void main() { runApp(...); }).
               - Chỉ được import CÁC PACKAGE SAU (đã đóng băng sẵn trong ảnh build, không "flutter
                 pub get" ở bước build nên package khác sẽ làm build lỗi mà không báo trước):
                 ALLOWED_PACKAGES
                 và các thư viện lõi của Dart (dart:core, dart:async, dart:convert,…).
               - TUYỆT ĐỐI KHÔNG tự import package của chính app này qua "package:<tên>/...";
                 CHỈ dùng import tương đối giữa các file trong lib/ (ví dụ "import 'models/user.dart';"
                 từ lib/main.dart, hoặc "import '../models/user.dart';" từ lib/screens/...). Tên
                 package khai trong pubspec sẽ bị hệ thống thay khi build nên import kiểu package:
                 tới chính app dễ gãy.
               - Nếu đề có lưu dữ liệu bằng SQLite (sqflite): PHẢI mở đúng file database tên
                 "DATABASE_NAME" (viết literal, ví dụ openDatabase('DATABASE_NAME') hoặc
                 join(dir, 'DATABASE_NAME')) — hệ thống đối chiếu đúng bằng chuỗi này với cấu hình bộ
                 chấm, sai tên là bị từ chối ngay khi tải lên.
               - Cài đặt ĐẦY ĐỦ, ĐÚNG hành vi mọi mục trong đề (mục 2,3,4): thêm/sửa/xoá dữ liệu
                 phải thật sự ghi xuống nơi lưu trữ đề yêu cầu, không giả lập trong bộ nhớ.
               - Với nút/thành phần DỄ BỊ TRÙNG (nút icon không chữ, các dòng lặp lại trong danh
                 sách, nhiều nút "Sửa"/"Xóa" giống hệt nhau…): PHẢI gắn thêm
                 Semantics(identifier: 'một-chuỗi-duy-nhất', child: ...) hoặc Key('...')/
                 ValueKey('...') để hệ thống ghi thao tác nhận diện đúng đích, không nhầm dòng này
                 sang dòng khác. Widget có nhãn/chữ RÕ RÀNG và DUY NHẤT trên màn hình thì không bắt
                 buộc phải gắn thêm.
               - Không viết test, không viết file ngoài lib/ (không cần pubspec.yaml, hệ thống tự
                 ghép).

               DB_NAME_HINT_TOKEN
               """
                .replace("ALLOWED_PACKAGES", String.join(", ", allowedPackages))
                .replace("DATABASE_NAME", databaseName == null ? "" : databaseName)
                .replace("DB_NAME_HINT_TOKEN", databaseName == null || databaseName.isBlank()
                        ? "Đề này chưa khai tên file database — nếu có lưu trữ SQLite, hãy chọn một "
                          + "tên hợp lý và nói rõ trong \"notes\" để giảng viên khai lại đúng tên đó "
                          + "ở Bước 1 trước khi tải app lên."
                        : "Tên file database bắt buộc dùng: " + databaseName + ".");
    }

    static String goldenUser(String deBai) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nHãy viết app đáp án đầy đủ, đúng hành vi cho đề này.";
    }

    static String goldenReviseSystem(String databaseName, List<String> allowedPackages) {
        return goldenSystem(databaseName, allowedPackages) + """

               ĐÂY LÀ LƯỢT SỬA: bạn nhận toàn bộ code hiện tại kèm yêu cầu của giảng viên.
               - Trả về TOÀN BỘ danh sách file sau khi sửa (đủ mọi file, kể cả file không đổi),
                 không phải phần thay đổi.
               - CHỈ đụng vào đúng chỗ được yêu cầu; phần khác giữ nguyên từng chữ nếu có thể.
               """;
    }

    static String goldenReviseUser(String deBai, String filesJson, String instruction) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nAPP HIỆN TẠI (danh sách file):\n" + filesJson
                + "\n\n---\nYÊU CẦU CHỈNH SỬA:\n" + instruction;
    }

    // ── 4. Database mẫu (phát cho SV) + database ẩn (chống hardcode) ─

    /**
     * Sinh dữ liệu cho ĐÚNG HAI database mà mục "Bảy thành phần của bộ chấm" cần
     * (STUDENT_DATABASE + HIDDEN_DATABASE): cùng cấu trúc bảng, khác dữ liệu — hệ thống chấm đối
     * chiếu schema hai bên phải giống hệt nhau ({@code BehaviorArtifactService#compareSqliteSchema}),
     * còn dữ liệu khác nhau mới chặn được sinh viên hardcode kết quả theo bộ mẫu công khai.
     *
     * <p>Không tự chạy SQL của AI trực tiếp lên database thật: {@code ExamService#saveDatabaseSeed}
     * chỉ chấp nhận đúng câu lệnh bắt đầu bằng "CREATE TABLE", còn dữ liệu luôn bind qua
     * PreparedStatement — AI không có đường nào thực thi SQL tuỳ ý.
     */
    static String seedSystem() {
        return """
               Bạn là giảng viên chuẩn bị HAI file SQLite cho một bài thi PRM393 đã ra đề xong:
               - Database PHÁT CHO SINH VIÊN: dữ liệu mẫu, sinh viên nhìn thấy khi mở app.
               - Database ẨN: CÙNG CẤU TRÚC BẢNG (tên bảng/cột/kiểu giống hệt), nhưng DỮ LIỆU KHÁC
                 hẳn — dùng để chấm bài mà sinh viên không đoán/hardcode được kết quả từ dữ liệu mẫu.

               Chỉ trả về MỘT object JSON:
               {
                 "tables": [
                   {
                     "name": "<tên bảng, đúng như trong mục Hợp đồng dữ liệu của đề>",
                     "create_sql": "<câu lệnh CREATE TABLE IF NOT EXISTS ... NGUYÊN VĂN từ đề>",
                     "columns": ["<cột 1>", "<cột 2>", "..."],
                     "student_rows": [[<giá trị cột 1>, <giá trị cột 2>, "..."], ["..."]],
                     "hidden_rows": [["..."], ["..."]]
                   }
                 ],
                 "notes": ["<vd: đổi hẳn ngày tháng nên tổng theo tháng của 2 bộ khác nhau>"]
               }

               QUY TẮC BẮT BUỘC:
               - "create_sql" PHẢI khớp NGUYÊN VĂN câu lệnh ở mục "Hợp đồng dữ liệu" của đề — sai
                 một chữ là hệ thống chấm đọc nhầm bảng, mất điểm oan cho sinh viên.
               - KHÔNG bịa thêm bảng nào ngoài những bảng đã khai trong "Hợp đồng dữ liệu".
               - Mỗi bảng: 5–10 dòng cho MỖI bộ (student_rows và hidden_rows tách riêng).
               - "columns" liệt kê đúng tên cột sẽ điền giá trị, ĐÚNG THỨ TỰ với từng phần tử trong
                 mỗi dòng của student_rows/hidden_rows. Được bỏ qua cột khóa chính tự tăng (vd "id")
                 để SQLite tự đánh số.
               - Dữ liệu phải THỰC TẾ, đúng kiểu (số là số, không để chữ vào cột số, ngày đúng định
                 dạng đề yêu cầu), đúng các mã cố định nếu đề có khai (vd mã danh mục viết hoa
                 không dấu).
               - "student_rows" và "hidden_rows" PHẢI khác nhau đủ nhiều (tên, số tiền, ngày tháng,
                 số dòng...) để không thể suy ra kết quả tính toán (tổng, đếm, lọc, sắp xếp...) của
                 bộ này từ bộ kia.
               """;
    }

    static String seedUser(String deBai) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nHãy soạn dữ liệu mẫu (student_rows) và dữ liệu ẩn (hidden_rows) cho đúng"
                + " (các) bảng đã khai ở mục Hợp đồng dữ liệu.";
    }

    private static void appendIf(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        String text = value instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList())
                : String.valueOf(value);
        if (text.isBlank()) return;
        sb.append("- ").append(label).append(": ").append(text.trim()).append('\n');
    }
}
