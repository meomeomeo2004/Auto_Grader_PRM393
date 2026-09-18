package com.example.grader.service.ai;

import java.util.List;
import java.util.Map;

/**
 * Prompt cho trợ lý soạn đề. Tách riêng khỏi service để chỗ nào cũng thấy rõ "AI được yêu cầu
 * đúng cái gì" — đây là phần quyết định chất lượng, sửa nhiều nhất khi tinh chỉnh.
 *
 * <p>Bản này port từ Grader_App1 nhưng ĐÃ BỎ prompt phân tích Item Key/mockup-theo-template và đề
 * xuất testcase theo template: hai bước đó gắn với hệ thống Template/RunnerCatalog/Item-Key-
 * Contract mà Grader_App không còn dùng (đã chuyển sang Golden Solution Record–Abstract–Replay).
 *
 * <p>ĐÃ BỎ (17/9/2026) prompt sinh khung starter + app "lời giải mẫu" (Golden Solution) — cùng
 * lúc xoá trang "Tạo Golden". Bù lại, file này có thêm {@link #mockupSystem} sinh hình minh họa
 * (khung dây) cho đề bài, dùng ngay ở trang "Tạo đề".
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
    static String draftSystem(String databaseName, List<String> imagePackages) {
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
               (CỐ ĐỊNH — chép gần như nguyên văn:
                "Làm bài bằng Android Studio, chạy thử trên máy ảo Android. Không cần chạy trên
                trình duyệt hay trên Windows."; "Nộp thư mục `lib/` của dự án, nén thành một file
                .zip."; "Mọi thứ khác (pubspec, android, test, build…) sẽ bị bỏ qua khi chấm — hệ
                thống dùng bộ thư viện chuẩn giống nhau cho mọi bài.";
                "Không được thêm package hoặc import package ngoài khung phát; vi phạm là 0 điểm.")
               Thân đề KHÔNG liệt kê danh sách package, ở mục 1 hay bất kỳ mục nào khác.
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
               DB_NAME_HINT_TOKEN
               """
                .replace("DB_NAME_INLINE_TOKEN", databaseName == null || databaseName.isBlank()
                        ? "<đặt một tên hợp lý, ví dụ app.db>" : "`" + databaseName + "`")
                .replace("DB_NAME_HINT_TOKEN", databaseName == null || databaseName.isBlank()
                        ? "Đề này chưa khai tên file database ở Bước 1 — bạn tự chọn một tên hợp lý "
                          + "và ghi rõ trong \"summary\" để giáo viên khai lại đúng tên đó."
                        : "Tên file database dùng đúng: " + databaseName + ".")
                + packageReference(imagePackages);
    }

    static String packageReference(List<String> imagePackages) {
        return """

               PHẠM VI THƯ VIỆN CỦA ẢNH CHẤM — KHÔNG CHÉP DANH SÁCH NÀY VÀO THÂN ĐỀ:
               Package đọc được từ ảnh chấm: IMAGE_TOKEN.
               Danh sách này chỉ để nhận ra yêu cầu cần thêm thư viện, không quyết định nội dung
               đề. AI được phép gợi ý package NGOÀI ảnh chấm khi yêu cầu của giảng viên cần nó.
               Khi cần package ngoài ảnh, đặt ghi chú ở dòng đầu tiên của de_bai_markdown,
               TRƯỚC tiêu đề đề thi, theo mẫu:
               [CẦN BỔ SUNG THƯ VIỆN] <tên package>: <lý do cần cho yêu cầu của giảng viên>.
               Có nhiều package thì ghi đủ tên và lý do cho từng gói trong ghi chú đầu đề.
               Không đẩy cảnh báo này vào summary thay cho đầu đề. Giảng viên tự quyết định
               có cần thêm gói hay không. Nếu chưa đọc được ảnh, nói rõ chưa thể xác nhận
               package được đề xuất trong ghi chú đầu đề; không khẳng định ảnh đang thiếu gói.
               Từ tiêu đề đề thi trở xuống, tuyệt đối không nhắc tên thư viện nào, kể cả khi
               giảng viên ghi tên thư viện trong yêu cầu lưu trữ. Mô tả bằng hành vi người dùng
               và hợp đồng dữ liệu. Các API Flutter lõi như Semantics, InputDecoration vẫn dùng
               bình thường. Không tự thêm chức năng ngoài yêu cầu của giảng viên.
               Mục 1 chỉ có câu cấm thêm package/import ngoài khung phát, vi phạm là 0 điểm.
               """.replace("IMAGE_TOKEN", imagePackages.isEmpty() ? "chưa đọc được ảnh" : String.join(", ", imagePackages));
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
               Nếu đề cũ có danh sách package thì bỏ danh sách đó; chỉ giữ câu cấm thêm package
               hoặc import package ngoài khung phát, vi phạm là 0 điểm. Không in danh sách package
               trong bất kỳ mục nào của thân đề. Ghi chú đầu đề cho giảng viên được xử lý theo
               phạm vi thư viện ảnh chấm bên dưới.
               """;
    }

    static String reviseUser(String deBai, String instruction) {
        return "ĐỀ HIỆN TẠI:\n\n" + deBai
                + "\n\n---\nYÊU CẦU CHỈNH SỬA:\n" + instruction;
    }

    // ── 2. Hình minh họa giao diện (MỚI) ─────────────────────────

    /**
     * AI KHÔNG được tự vẽ SVG trực tiếp — LLM sinh XML tự do rất hay ra thẻ hỏng/lệch toạ độ.
     * Thay vào đó AI chỉ mô tả từng màn hình bằng JSON có cấu trúc (loại thành phần + nhãn), rồi
     * {@link com.example.grader.service.MockupRenderer} vẽ SVG THẬT một cách tất định — luôn hợp
     * lệ về cú pháp, xuống dòng đều, không lệch khung.
     */
    static String mockupSystem() {
        return """
               Bạn đọc đề thi thực hành Flutter, tìm mục "3. Hợp đồng giao diện" (mỗi mục con
               "3.x <tên màn hình>" kèm một bảng | Thành phần | Cách đặt | Giá trị bắt buộc | Định
               danh |), rồi mô tả LẠI từng màn hình đó thành một khung dây (wireframe) đơn giản để
               giáo viên hình dung bố cục — bạn KHÔNG vẽ hình, chỉ mô tả bằng JSON có cấu trúc.

               Chỉ trả về MỘT object JSON:
               {
                 "screens": [
                   {
                     "id": "<tên ngắn không dấu, vd man-hinh-danh-sach>",
                     "title": "<đúng tên màn hình ở mục 3.x>",
                     "elements": [
                       {"type": "app_bar", "label": "<tiêu đề thanh trên cùng>"},
                       {"type": "heading", "label": "<tiêu đề phụ nếu có>"},
                       {"type": "input", "label": "<nhãn ô nhập, lấy từ cột Thành phần/Cách đặt>"},
                       {"type": "button", "label": "<chữ trên nút>"},
                       {"type": "list_item", "label": "<mẫu một dòng trong danh sách>"},
                       {"type": "card", "label": "<mẫu một thẻ nếu giao diện dạng thẻ>"},
                       {"type": "text", "label": "<đoạn chữ hiển thị thường>"},
                       {"type": "checkbox", "label": "<nhãn ô chọn>"},
                       {"type": "image", "label": "<mô tả ảnh/avatar nếu có>"},
                       {"type": "divider", "label": ""}
                     ]
                   }
                 ]
               }

               QUY TẮC:
               - MỘT phần tử "screens" cho MỖI mục con 3.x trong đề — đúng số màn, đúng thứ tự.
               - "elements" LẤY TỪ cột "Thành phần" của bảng Định danh màn hình đó, KHÔNG bịa thêm
                 thành phần đề không nhắc tới. Thứ tự phần tử theo đúng thứ tự dòng trong bảng.
               - Luôn mở đầu mỗi màn hình bằng một "app_bar" lấy nhãn là tên màn hình (mục 3.x),
                 trừ khi đề nói rõ màn hình không có thanh tiêu đề.
               - "type" CHỈ được chọn trong 10 loại đã liệt kê ở ví dụ trên.
               - "label" ngắn gọn (dưới 40 ký tự), lấy nguyên văn từ cột "Cách đặt"/"Giá trị bắt
                 buộc" của bảng khi có thể — đây là khung dây nội bộ, không phải đề chính thức, nên
                 không cần giữ dấu Semantics(...) hay cú pháp code.
               - Bỏ qua màn hình nào đề không có bảng Định danh rõ ràng (ví dụ mô tả bằng lời).
               """;
    }

    /** @param instruction lời giáo viên nhờ AI sửa lại cách vẽ (bố cục/thành phần) — có thể để trống. */
    static String mockupUser(String deBai, String instruction) {
        StringBuilder sb = new StringBuilder("ĐỀ BÀI:\n\n").append(deBai)
                .append("\n\nHãy mô tả khung dây cho từng màn hình ở mục 3 theo đúng schema JSON đã nêu.");
        if (instruction != null && !instruction.isBlank()) {
            sb.append("\n\n---\nYÊU CẦU THÊM CỦA GIÁO VIÊN VỀ CÁCH VẼ (ưu tiên áp dụng, vẫn theo đúng ")
              .append("schema JSON và vẫn phải bám sát mục 3 của đề):\n").append(instruction);
        }
        return sb.toString();
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
