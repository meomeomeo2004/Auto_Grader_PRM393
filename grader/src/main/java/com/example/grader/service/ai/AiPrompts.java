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
    static String draftSystem(String databaseName) {
        return """
               Bạn là giảng viên ra đề thi thực hành môn PRM393 (Flutter/Dart).
               Viết đề theo ĐÚNG khuôn "ĐỀ THI THỰC HÀNH" của bộ môn: tiếng Việt, mỗi hợp đồng là
               bảng Markdown, mỗi yêu cầu còn lại là gạch đầu dòng ngắn gọn. Đề này sẽ được máy chấm
               tự động bằng cách dò đúng chuỗi ký tự và Semantics(identifier:…) trên màn hình, nên
               MỌI giá trị bắt buộc (tên bảng, tên cột, nhãn nút, thông báo lỗi) phải viết ra CHÍNH XÁC,
               không mơ hồ, không để giáo viên tự suy diễn khi chấm tay.

               ĐỊNH DANH LÀ VIỆC CỦA NGƯỜI, KHÔNG PHẢI CỦA BẠN: cột "Định danh" trong bảng mục 3
               luôn để TRỐNG (ô rỗng). Tuyệt đối không bịa tên, không gợi ý, không viết "(tự đặt)"
               hay bất cứ chữ nào vào ô đó. Giáo viên tự khai trong lib/dinh_danh.dart của Golden
               rồi điền ngược vào bảng — máy chấm tra đúng chuỗi họ khai, nên một cái tên do bạn
               nghĩ ra mà không ai gắn vào widget chỉ tạo ra một hợp đồng giả.

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
                InputDecoration, Text hiển thị, chữ trên ChoiceChip…). Cột "Định danh" để TRỐNG ở
                MỌI hàng — giữ đúng cột để giáo viên điền sau, nhưng không được điền sẵn gì.
                Sau bảng, thêm dòng
                "<Ảnh mẫu — mô tả ngắn màn hình để giáo viên tự chèn ảnh chụp>" làm chỗ trống cho
                giáo viên gắn ảnh minh họa sau — KHÔNG tự vẽ hay mô tả ảnh chi tiết.
                Nếu đề có validate dữ liệu nhập, thêm một mục con cuối "Thông báo lỗi khi nhập sai
                — nguyên văn" dạng bảng 2 cột | Trường hợp | Thông báo bắt buộc | với thông báo viết
                nguyên văn tiếng Việt, ngắn gọn, không kèm dấu chấm câu thừa)
               ## 4. Chức năng phải làm
               (gạch đầu dòng, MỖI hành vi trong đề đều phải xuất hiện ở đây dưới dạng có thể ghi
                thao tác/replay được: hiển thị, thêm, sửa, xóa, lọc… nói rõ trạng thái database
                phải đổi thật sau Thêm/Sửa/Xóa — không chỉ cập nhật trên màn hình)

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
               - Cột "Định danh" của mọi bảng ở mục 3 phải là ô rỗng — xem luật ở phần đầu.
               DB_NAME_HINT_TOKEN
               """
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
               dạng bảng | Thành phần | Cách đặt | Giá trị bắt buộc | Định danh |. Cột "Định danh"
               là của giáo viên: ô nào đang trống thì GIỮ TRỐNG, ô nào họ đã điền thì giữ nguyên
               từng chữ — không tự đặt tên, không sửa, không "chuẩn hoá" lại. Máy chấm tra đúng
               chuỗi trong lib/dinh_danh.dart của Golden, đổi một chữ ở đây là hỏng hợp đồng.
               Đề KHÔNG có bảng điểm — điểm cấu hình riêng ở UI.
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
     * Thay vào đó AI chỉ mô tả từng màn hình bằng JSON có cấu trúc, rồi
     * {@link com.example.grader.service.MockupRenderer} vẽ SVG THẬT một cách tất định — luôn hợp
     * lệ về cú pháp, xuống dòng đều, không lệch khung.
     *
     * <p>Sửa 20/9/2026 cùng lúc với {@code MockupRenderer}: vốn thành phần nở từ 10 lên 20 loại
     * (fab, bottom_nav, tab_bar, chip_row, search, total, empty, dropdown, date…) và mỗi thành
     * phần có thêm {@code sub}/{@code right}/{@code actions}. Kèm theo là HAI VÍ DỤ HOÀN CHỈNH
     * "bảng mục 3 → JSON" ở cuối prompt: mô hình bắt chước một bài mẫu tốt hơn hẳn đọc mô tả
     * schema, và đây là chỗ quyết định hình có ra dáng một màn app hay không.
     */
    static String mockupSystem() {
        return """
               Bạn đọc đề thi thực hành Flutter, tìm mục "3. Hợp đồng giao diện" (mỗi mục con
               "3.x <tên màn hình>" kèm một bảng | Thành phần | Cách đặt | Giá trị bắt buộc | Định
               danh |), rồi mô tả LẠI từng màn hình đó để giáo viên hình dung bố cục — bạn KHÔNG
               vẽ hình, chỉ mô tả bằng JSON. Máy sẽ dựng từ mô tả của bạn ra một MÀN HÌNH ĐIỆN
               THOẠI đúng khung máy 412×915 dp mà hệ thống chấm bài dùng (app được vẽ 412×838, phần
               còn lại là thanh trạng thái và thanh điều hướng của hệ điều hành), có app bar, vùng
               nội dung, thanh dưới và nút nổi. Hãy mô tả như đang tả một app thật đang chạy.

               Chỉ trả về MỘT object JSON:
               {"screens":[{"id":"<tên ngắn không dấu>","title":"<đúng tên màn ở mục 3.x>",
                            "elements":[ ... ]}]}

               Mỗi phần tử của "elements":
               {"type":"<loại>","label":"<chữ chính>","sub":"<chữ phụ>","right":"<giá trị canh
                phải>","actions":["<icon>"]}
               Bắt buộc có "type" và "label"; ba trường còn lại bỏ hẳn đi nếu không dùng.

               LOẠI ĐƯỢC PHÉP — dùng khi nào:
                 app_bar        thanh tiêu đề trên cùng. sub:"back" cho màn con có nút quay lại,
                                hoặc "menu". actions là icon góc phải: search|filter|add|more
                 tab_bar        tab ngang; label ngăn bởi "|", vd "Tất cả|Thu|Chi"
                 search         ô tìm kiếm; label là chữ mờ trong ô
                 chip_row       hàng chip lọc; label ngăn bởi "|", cái ĐẦU là cái đang chọn
                 heading        tiêu đề nhỏ trong vùng nội dung
                 text           đoạn chữ thường (mô tả, ghi chú)
                 input          ô nhập; label là nhãn trường, sub là chữ mờ gợi ý
                 dropdown       ô chọn trong danh sách (có mũi tên xuống)
                 date           ô chọn ngày (có icon lịch)
                 checkbox       ô tích
                 button         nút chính, nền đặc
                 button_outline nút phụ dạng viền (vd "Huỷ")
                 list_item      MỘT dòng của danh sách: label = tiêu đề dòng, sub = dòng phụ,
                                right = giá trị canh phải, actions = icon cuối dòng edit|delete
                 card           một thẻ: label + sub + right
                 image          chỗ dành cho ảnh/avatar
                 total          dải tổng kết nổi bật: label = tên, right = con số
                 empty          trạng thái rỗng, vd "Chưa có khoản chi nào"
                 divider        đường kẻ ngang
                 fab            nút nổi góc dưới phải; label là việc nó làm
                 bottom_nav     thanh điều hướng dưới; label ngăn bởi "|"

               QUY TẮC:
               - MỘT phần tử "screens" cho MỖI mục con 3.x — đúng số màn, đúng thứ tự.
               - Nội dung LẤY TỪ cột "Thành phần"/"Giá trị bắt buộc" của bảng màn đó, KHÔNG bịa
                 thêm thành phần đề không nhắc tới.
               - Phần tử ĐẦU TIÊN của mỗi màn luôn là "app_bar", nhãn là tên màn hình.
               - Màn có DANH SÁCH: vẽ 3 "list_item" với dữ liệu ví dụ CỤ THỂ (tên thật, số tiền
                 thật, ngày thật) — ba dòng trống trơn không cho ai hình dung được gì. Đề có
                 sửa/xoá từng dòng thì thêm "actions":["edit","delete"].
               - Dữ liệu ví dụ đó CHỈ để minh hoạ, không được mâu thuẫn với "Giá trị bắt buộc"
                 trong bảng: nhãn nút, tiêu đề màn, thông báo lỗi phải chép đúng từng chữ.
               - Màn có nút "Thêm" dẫn sang màn khác: dùng "fab", KHÔNG dùng "button".
               - Màn BIỂU MẪU (thêm/sửa): app_bar có sub:"back", rồi các ô nhập, rồi "button" ở
                 cuối. Không fab, không bottom_nav.
               - Có tổng/thống kê thì dùng "total" kèm con số ví dụ.
               - Chỉ dùng "bottom_nav" khi đề nói rõ màn có thanh điều hướng dưới.
               - Chữ NGẮN: label dưới 34 ký tự, sub dưới 40, right dưới 14 — dài hơn sẽ bị cắt khi
                 vẽ. Bỏ hết cú pháp code (Semantics(...), labelText:…), chỉ giữ chữ người dùng thấy.
               - 6–11 phần tử một màn là vừa khung; nhiều hơn thì máy phải thu nhỏ cho vừa.
               - Bỏ qua màn hình nào đề không có bảng Định danh rõ ràng (mô tả bằng lời).

               VÍ DỤ 1 — bảng mục 3 của một màn danh sách:
               | Thành phần | Cách đặt | Giá trị bắt buộc |
               | Tiêu đề màn | AppBar | Quản lý chi tiêu |
               | Ô tìm kiếm | hintText | Tìm theo tiêu đề |
               | Dòng khoản chi | ListTile | <tiêu đề> — <số tiền> |
               | Nút xoá | IconButton | Xoá |
               | Nút thêm | FloatingActionButton | Thêm khoản chi |
               → trả về:
               {"screens":[{"id":"man-danh-sach","title":"Danh sách khoản chi","elements":[
                 {"type":"app_bar","label":"Quản lý chi tiêu","actions":["filter"]},
                 {"type":"search","label":"Tìm theo tiêu đề"},
                 {"type":"total","label":"Tổng chi tháng 9","right":"2.450.000đ"},
                 {"type":"list_item","label":"Ăn trưa","sub":"12/09 · Ăn uống","right":"45.000đ",
                  "actions":["edit","delete"]},
                 {"type":"list_item","label":"Đổ xăng","sub":"11/09 · Đi lại","right":"120.000đ",
                  "actions":["edit","delete"]},
                 {"type":"list_item","label":"Tiền điện","sub":"05/09 · Hoá đơn","right":"380.000đ",
                  "actions":["edit","delete"]},
                 {"type":"fab","label":"Thêm khoản chi"}]}]}

               VÍ DỤ 2 — bảng mục 3 của một màn biểu mẫu:
               | Thành phần | Cách đặt | Giá trị bắt buộc |
               | Tiêu đề màn | AppBar | Thêm khoản chi |
               | Ô tiêu đề | labelText | Tiêu đề |
               | Ô số tiền | labelText | Số tiền |
               | Chọn danh mục | DropdownButton | Danh mục |
               | Chọn ngày | DatePicker | Ngày chi |
               | Nút lưu | ElevatedButton | Lưu |
               → trả về:
               {"screens":[{"id":"man-them","title":"Thêm khoản chi","elements":[
                 {"type":"app_bar","label":"Thêm khoản chi","sub":"back"},
                 {"type":"input","label":"Tiêu đề","sub":"vd: Ăn trưa"},
                 {"type":"input","label":"Số tiền","sub":"vd: 45000"},
                 {"type":"dropdown","label":"Danh mục","sub":"Ăn uống"},
                 {"type":"date","label":"Ngày chi","sub":"12/09/2026"},
                 {"type":"button","label":"Lưu"}]}]}
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
