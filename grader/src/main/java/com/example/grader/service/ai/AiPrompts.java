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
 */
final class AiPrompts {

    private AiPrompts() {}

    // ── 1. Soạn đề bài ───────────────────────────────────────────

    /**
     * Khuôn "YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER" mà bộ môn đang phát cho sinh viên: 5 mục đánh
     * số, mỗi mục là danh sách gạch đầu dòng. Nội dung từng gạch đầu dòng do wizard quyết định
     * (chủ đề, số màn, kiến thức, lưu trữ…), riêng BỐ CỤC thì cố định để mọi đề nhìn như một.
     */
    static String draftSystem() {
        return """
               Bạn là giảng viên ra đề thi thực hành môn PRM393 (Flutter/Dart) của FPT University.
               Viết đề theo ĐÚNG khuôn "YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER" của bộ môn: tiếng
               Việt, mỗi mục là danh sách gạch đầu dòng ngắn gọn, mọi yêu cầu đều KIỂM TRA ĐƯỢC
               bằng test tự động.

               Chỉ trả về MỘT object JSON:
               {
                 "de_bai_markdown": "<toàn bộ đề bài dạng Markdown>",
                 "summary": "<2-3 câu tóm tắt đề vừa tạo>",
                 "criteria": [ {"name": "<tiêu chí>", "points": <số điểm>} ]
               }

               "de_bai_markdown" PHẢI có đủ 5 mục, đúng thứ tự và đúng tiêu đề:

               # YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER: <TÊN BÀI VIẾT HOA>
               ## 1. Yêu cầu kỹ thuật
               (mỗi gạch đầu dòng một yêu cầu: loại đối tượng phải quản lý, kiến trúc, quản lý
                trạng thái, cách lưu trữ, responsive, Form + GlobalKey<FormState>, hình ảnh…)
               ## 2. Dữ liệu và Validation
               (Model và từng thuộc tính; khóa chính/ID tự tăng; trường bắt buộc; ràng buộc và
                thông báo lỗi hiện Ở ĐÂU; chỉ cho lưu khi toàn bộ hợp lệ)
               ## 3. Chức năng chính
               (hiển thị danh sách, Thêm, Sửa, Xóa, điều hướng sang màn Chi tiết, đồng bộ dữ liệu
                giữa giao diện – ViewModel – tầng lưu trữ; nói rõ hành vi mong đợi sau mỗi thao tác)
               ## 4. Giao diện
               (bố cục từng màn hình, thành phần bắt buộc của mỗi item, yêu cầu responsive)
               ## 5. Đánh giá
               (một gạch đầu dòng liệt kê các nhóm tiêu chí, rồi bảng Markdown 2 cột
                | Tiêu chí | Điểm | với các dòng cộng lại ĐÚNG BẰNG 100)

               LUẬT QUAN TRỌNG NHẤT — CHỈ VIẾT NHỮNG GÌ ĐƯỢC YÊU CẦU:
               Bạn CHỈ mượn BỐ CỤC 5 mục ở trên. Toàn bộ NỘI DUNG phải suy ra từ phần mô tả yêu cầu
               của giảng viên, KHÔNG thêm bất cứ yêu cầu nào không được nhắc tới, kể cả khi bạn thấy
               nó là "thông lệ tốt" hay "đề Flutter nào cũng có".
               - Giảng viên KHÔNG nhắc kiến trúc ⇒ không viết MVVM, Repository, Clean Architecture.
               - KHÔNG nhắc quản lý trạng thái ⇒ không viết Provider, Riverpod, StateNotifier,
                 ValueNotifier, Bloc, setState.
               - KHÔNG nhắc responsive/tablet ⇒ không viết yêu cầu responsive hay mốc dp nào.
               - KHÔNG nhắc thông báo lỗi ⇒ không bịa ra yêu cầu hiện lỗi dưới ô nhập.
               - KHÔNG nhắc lưu trữ ⇒ không viết SQLite/SharedPreferences/File.
               - KHÔNG nhắc chức năng nào (sửa, tìm kiếm, sắp xếp, xác nhận xoá, màn chi tiết…)
                 ⇒ tuyệt đối không đưa nó vào đề.
               Ô nào giảng viên để trống thì mục tương ứng chỉ viết đúng phần suy ra được từ các ô
               đã điền — thà đề ngắn còn hơn đề có yêu cầu sinh viên không được báo trước.

               Nguyên tắc còn lại:
               - Số màn hình phải ĐÚNG con số giảng viên ghi; một màn thì không được tách thành hai.
               - Mỗi tiêu chí ở mục 5 phải tương ứng với một hành vi đã mô tả ở mục 2, 3 hoặc 4 —
                 và hành vi đó phải bắt nguồn từ yêu cầu của giảng viên.
               - Không ra yêu cầu chỉ đánh giá được bằng mắt ("giao diện đẹp") mà không kèm tiêu chí
                 cụ thể (kích thước, số cột, thành phần phải có).
               """;
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
        appendIf(sb, "Kiến trúc & quản lý trạng thái", req.get("architecture"));
        appendIf(sb, "Cách lưu trữ dữ liệu", req.get("storage"));
        appendIf(sb, "Mức độ khó", req.get("difficulty"));
        appendIf(sb, "Thời lượng làm bài", req.get("duration"));
        appendIf(sb, "Yêu cầu thêm của giảng viên", req.get("note"));
        sb.append("\nTổng điểm của bảng thang điểm: 100.");
        return sb.toString();
    }

    static String reviseSystem() {
        return """
               Bạn là giảng viên đang CHỈNH SỬA một đề thi PRM393 đã có theo yêu cầu của đồng nghiệp.

               Chỉ trả về MỘT object JSON:
               {
                 "de_bai_markdown": "<toàn bộ đề sau khi sửa, giữ nguyên cấu trúc 5 mục>",
                 "summary": "<liệt kê ngắn gọn những gì đã thay đổi>",
                 "criteria": [ {"name": "<tiêu chí>", "points": <số điểm>} ]
               }

               Quy tắc: CHỈ sửa đúng phần được yêu cầu, giữ nguyên mọi nội dung khác kể cả cách
               diễn đạt. Giữ đủ 5 mục theo khuôn "YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER"
               (1. Yêu cầu kỹ thuật · 2. Dữ liệu và Validation · 3. Chức năng chính · 4. Giao diện
               · 5. Đánh giá) và bảng thang điểm vẫn cộng đúng 100 điểm.
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

               DATABASE_NAME_NOTE
               """
                .replace("ALLOWED_PACKAGES", String.join(", ", allowedPackages))
                .replace("DATABASE_NAME", databaseName == null ? "" : databaseName)
                .replace("DATABASE_NAME_NOTE", databaseName == null || databaseName.isBlank()
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

    private static void appendIf(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        String text = value instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList())
                : String.valueOf(value);
        if (text.isBlank()) return;
        sb.append("- ").append(label).append(": ").append(text.trim()).append('\n');
    }
}
