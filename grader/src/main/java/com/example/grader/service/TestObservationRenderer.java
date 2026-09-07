package com.example.grader.service;

import java.util.Map;
import java.util.Set;

/**
 * DIỄN ĐẠT QUAN SÁT → câu tiếng Việt cho `actual` của result.json (SPEC mục 5.3–5.4).
 *
 * <p>Engine chung phát ra quan sát dạng MÁY ĐỌC ({@code kind} + {@code subject} + số đo);
 * lớp này là nơi DUY NHẤT biến nó thành câu cho sinh viên đọc.
 *
 * <p><b>Vì sao render ở backend chứ không ở Dart:</b> {@code exam_test.dart} và
 * {@code grader.dart} bị chép ĐÓNG BĂNG vào thư mục testcase của từng đề lúc publish. Câu chữ
 * để trong đó thì sửa một chữ cũng phải nâng engine cho mọi đề đã publish; để ở đây thì sửa một
 * lần là đề cũ lẫn đề mới đều hưởng.
 *
 * <p><b>Ba điều tuyệt đối không được xuất hiện trong câu trả về</b> (SPEC mục 5.4, luật C3/C4/F1
 * của ACCEPTANCE):
 * <ol>
 *   <li>semantic key nội bộ ({@code field.name}, {@code action.save}) — sinh viên không kiểm
 *       chứng được;</li>
 *   <li>log tiếng Anh của Flutter, tên hàm test, đường dẫn file;</li>
 *   <li>suy đoán nguyên nhân trong code sinh viên — chỉ nói ĐIỀU QUAN SÁT ĐƯỢC.</li>
 * </ol>
 * Payload từ engine đã được thiết kế để không mang được ba thứ đó, nên ở đây chỉ cần ghép câu.
 */
public final class TestObservationRenderer {

    private TestObservationRenderer() {}

    /** Trần độ dài của `actual` theo luật C5. */
    private static final int MAX_LENGTH = 160;

    /**
     * `kind` → `error_code`. Đặt CÙNG CHỖ với bảng diễn đạt, không tách class riêng: hai bảng đều
     * nhận cùng một từ vựng `kind`, tách ra là tạo nguồn sự thật thứ hai — thêm `kind` mà quên một
     * bên thì lệch ngầm.
     *
     * <p><b>Vì sao suy `error_code` từ `kind`:</b> `error_code` vốn do {@link TestErrorClassifier}
     * bóc regex từ log tiếng Anh — đúng cơ chế P5 dựng ra để thay. Đo trên bộ mẫu thì hai khoá là
     * **song ánh 1–1** trên mọi test đã chạy thật, nên đổi nguồn KHÔNG làm lệch cách gom nhóm của
     * bên đọc ở dữ liệu hôm nay, mà chỉ sạch hơn ở dữ liệu mai.
     *
     * <p><b>Không ép N→1.</b> Bốn `kind` cuối được mã RIÊNG thay vì dồn hết vào
     * {@code VALUE_MISMATCH}: năm ca đó có năm cách sửa khác nhau (sửa chuỗi / sửa số đo / sửa kiểu
     * chữ / thêm nhãn trợ năng / sửa logic bật-tắt). Trùng mã ⇒ bên đọc gộp làm MỘT đoạn góp ý ⇒
     * sinh viên chỉ sửa một nửa.
     *
     * <p>`NOT_RUN_*` cố ý **không có mã**: chưa chạy thì không quan sát được gì để phân loại.
     */
    private static final Map<String, String> ERROR_CODE = Map.ofEntries(
            Map.entry("MISSING", "WIDGET_NOT_FOUND"),
            Map.entry("STILL_PRESENT", "WIDGET_UNEXPECTED"),
            // A2b: CÓ MẶT nhưng SAI LOẠI — cách sửa riêng (dùng đúng loại widget), khác hẳn
            // "thêm thành phần còn thiếu" của MISSING. Chín runner phát được qua _assertTargetType.
            Map.entry("TYPE_MISMATCH", "WIDGET_TYPE_MISMATCH"),
            Map.entry("COUNT_MISMATCH", "WIDGET_COUNT"),
            Map.entry("TEXT_MISMATCH", "VALUE_MISMATCH"),
            Map.entry("OVERFLOW", "LAYOUT_OVERFLOW"),
            Map.entry("LAYOUT_ERROR", "BUILD_ERROR"),
            Map.entry("BOOT_FAILED", "EXCEPTION_THROWN"),
            Map.entry("SOURCE_CONTRACT_VIOLATION", "CONTRACT_VIOLATION"),
            Map.entry("SOURCE_POLICY_VIOLATION", "SOURCE_POLICY_VIOLATION"),
            // Bốn mã MỚI ở A1 — chỉ xuất hiện khi các kind Mức 2 chạy thật.
            Map.entry("NUMBER_MISMATCH", "SIZE_MISMATCH"),
            Map.entry("STYLE_MISMATCH", "TEXT_STYLE_MISMATCH"),
            Map.entry("LABEL_MISMATCH", "SEMANTICS_MISMATCH"),
            Map.entry("SCROLL_DIRECTION_MISMATCH", "SCROLL_DIRECTION_MISMATCH"),
            Map.entry("ENABLED_MISMATCH", "ENABLED_MISMATCH"));

    /**
     * Mã lỗi suy từ quan sát; {@code null} khi không suy được — bên gọi giữ giá trị của classifier.
     *
     * <p>Trả {@code null} cho `NOT_RUN_*` và cho `kind` lạ. Đừng đổi thành một mã "mặc định":
     * mã sai còn tệ hơn không có mã, vì bên đọc gom nhóm theo nó.
     */
    public static String errorCodeOf(Map<?, ?> observation) {
        if (observation == null) return null;
        String kind = text(observation.get("kind"));
        return kind == null ? null : ERROR_CODE.get(kind.toUpperCase());
    }

    /** Mọi giá trị `error_code` mà đường quan sát có thể phát — để test đối chiếu với SPEC. */
    public static Set<String> observationErrorCodes() {
        return Set.copyOf(ERROR_CODE.values());
    }

    /**
     * `kind` diễn đạt được nhưng **cố ý KHÔNG mang `error_code`**, để `error_code` giữ giá trị của
     * {@link TestErrorClassifier}. Hai lý do khác nhau:
     *
     * <ul>
     *   <li>`NOT_RUN_*` — chưa chạy thì chưa quan sát được gì để phân loại;</li>
     *   <li>`ACTION_FAILED` (A2c) — ứng dụng ném lỗi khi thao tác. Ở đây **classifier biết nhiều
     *       hơn** engine: nó bóc được LOẠI ngoại lệ (`RANGE_ERROR`, `NULL_ERROR`, `STATE_ERROR`…),
     *       độ mịn mà `kind` không mang được. Hai nguồn bổ sung nhau, không cạnh tranh — `kind` nói
     *       *tình huống*, `error_code` nói *loại lỗi*.</li>
     * </ul>
     */
    private static final Set<String> KINDS_WITHOUT_CODE =
            Set.of("NOT_RUN_BOOT", "NOT_RUN_SUITE", "ACTION_FAILED");

    /**
     * Mọi `kind` lớp này diễn đạt được — dùng để đối chiếu độ phủ của fixture (A2) và bảng SPEC 5.5.
     *
     * <p>Suy từ {@link #ERROR_CODE} chứ không khai lại thành danh sách thứ ba: thêm `kind` mà quên
     * cập nhật danh sách là đúng kiểu lệch ngầm mà cả A1 lẫn A2 đang dựng chốt chặn để chống.
     */
    public static Set<String> renderableKinds() {
        Set<String> kinds = new java.util.TreeSet<>(ERROR_CODE.keySet());
        kinds.addAll(KINDS_WITHOUT_CODE);
        return Set.copyOf(kinds);
    }

    /** `kind` cố ý không mang `error_code` — xem {@link #KINDS_WITHOUT_CODE}. */
    public static Set<String> kindsWithoutCode() {
        return KINDS_WITHOUT_CODE;
    }

    /** Loại thành phần theo cách sinh viên hiểu — bảng ĐÓNG, khớp `_subject` của engine. */
    private static final Map<String, String> SUBJECT = Map.ofEntries(
            Map.entry("field", "ô nhập"),
            Map.entry("input", "ô nhập"),
            Map.entry("button", "nút"),
            Map.entry("list", "danh sách"),
            // "mục" chứ không phải "mục trong danh sách": mọi câu ở đây đều ghép chủ ngữ là TÊN
            // YÊU CẦU rồi mới tới danh từ này, nên cụm dài làm câu sai ngữ pháp ở khuôn "không
            // thấy <X> nào" — A2 đo được câu "không thấy mục trong danh sách nào". Danh từ trong
            // bảng này phải là danh từ ĐƠN, ghép được vào mọi khuôn câu.
            Map.entry("item", "mục"),
            Map.entry("dialog", "hộp thoại"),
            Map.entry("screen", "màn hình"),
            Map.entry("error", "thông báo lỗi"),
            Map.entry("text", "nội dung chữ"),
            Map.entry("image", "ảnh"),
            Map.entry("icon", "biểu tượng"),
            // A2: ô chọn không phải "nút" — cách sửa khác nhau (đổi `onChanged` chứ không
            // phải `onPressed`), nên phải có từ riêng thay vì để rơi về fallback.
            Map.entry("checkbox", "ô chọn"),
            Map.entry("widget", "thành phần"));

    /** Bối cảnh kiểm — nói ở ĐÂU quan sát được, giúp sinh viên tái hiện trên máy mình. */
    private static final Map<String, String> WHERE = Map.of(
            "portrait", "ở màn hình dọc",
            "landscape", "ở màn hình ngang",
            "after_action", "sau khi thực hiện thao tác");

    /**
     * Ghép câu `actual`.
     *
     * @param name        tên testcase (tiếng Việt, giáo viên nhập) — làm chủ ngữ của câu
     * @param observation payload từ engine; có thể null
     * @return câu tiếng Việt, hoặc {@code null} khi không diễn đạt được (bên gọi giữ giá trị cũ)
     */
    public static String render(String name, Map<?, ?> observation) {
        if (observation == null) return null;
        String kind = text(observation.get("kind"));
        if (kind == null) return null;

        String detail = detail(kind, observation);
        if (detail == null) return null;

        String subject = text(name);
        // Chủ ngữ là tên yêu cầu giáo viên đặt. Không có thì để câu quan sát đứng một mình,
        // KHÔNG thay bằng test_id — đó là định danh nội bộ (luật C2).
        String sentence = subject == null ? capitalize(detail) : subject + ": " + detail;
        return shorten(sentence);
    }

    private static String detail(String kind, Map<?, ?> obs) {
        String subject = subjectOf(obs);
        String where = WHERE.get(text(obs.get("where")) == null ? "" : text(obs.get("where")));
        String suffix = where == null ? "" : " " + where;

        return switch (kind.toUpperCase()) {
            case "MISSING" -> "không thấy " + subject + " nào" + suffix + ".";
            // Nói RÕ là có thứ gì ở đó nhưng sai loại — nếu chỉ nói "không thấy" thì sinh viên đi
            // thêm một thành phần nữa vào chỗ đã có sẵn một cái.
            case "TYPE_MISMATCH" -> "chỗ này đang là thành phần khác, không phải "
                    + subject + " như đề yêu cầu" + suffix + ".";
            case "STILL_PRESENT" -> subject + " lẽ ra phải biến mất thì vẫn còn"
                    + (where == null ? "" : " " + where) + ".";
            case "TEXT_MISMATCH" -> textMismatch(obs);
            case "COUNT_MISMATCH" -> countMismatch(obs, subject);
            case "NUMBER_MISMATCH" -> numberMismatch(obs);
            case "LABEL_MISMATCH" -> {
                String seen = text(obs.get("seen"));
                yield seen == null
                        ? "chưa có nhãn trợ năng cho thành phần này."
                        : "nhãn trợ năng đang là \"" + seen + "\".";
            }
            case "STYLE_MISMATCH" -> {
                String dimension = text(obs.get("dimension"));
                yield (dimension == null ? "kiểu chữ" : dimension) + " chưa đúng yêu cầu.";
            }
            case "ENABLED_MISMATCH" -> subject + " đang ở trạng thái "
                    + ("enabled".equals(text(obs.get("seen"))) ? "bấm được" : "không bấm được") + ".";
            case "SCROLL_DIRECTION_MISMATCH" -> {
                String seen = text(obs.get("seen"));
                yield "đang cuộn theo chiều " + ("horizontal".equals(seen) ? "ngang" : "dọc") + ".";
            }
            case "OVERFLOW" -> "giao diện bị tràn khung" + suffix + ".";
            case "LAYOUT_ERROR" -> "bố cục dựng không xong" + suffix + ".";
            case "BOOT_FAILED" -> "ứng dụng không mở được, chưa hiện được nội dung nào.";
            case "SOURCE_CONTRACT_VIOLATION" ->
                    "mã nguồn không đáp ứng public contract mà đề đã công bố.";
            case "SOURCE_POLICY_VIOLATION" ->
                    "mã nguồn chứa class hoặc nội dung mà contract của đề không cho phép.";
            // NGUYÊN NHÂN GỐC, không phải triệu chứng. Trước A2c chỗ này không tồn tại: app ném
            // lỗi lúc bấm thì báo cáo nói "không thấy hộp thoại nào" — sinh viên đi tìm widget
            // thiếu trong khi lỗi là truy cập ngoài phạm vi. Câu này chỉ nói ĐIỀU QUAN SÁT ĐƯỢC
            // (app báo lỗi khi thao tác); LOẠI lỗi nằm ở `error_code` do classifier bóc ra.
            case "ACTION_FAILED" -> "ứng dụng báo lỗi khi thực hiện thao tác, "
                    + "nên chưa kiểm được tới cuối yêu cầu.";
            // Hai ca `not_run`: nêu VÌ SAO chưa chạy, không nêu chẩn đoán (SPEC mục 5.4).
            case "NOT_RUN_BOOT" -> "chưa chạy vì ứng dụng không mở được, bộ chấm chưa kiểm tới đây.";
            case "NOT_RUN_SUITE" -> "chưa chạy vì bộ test không khởi động được.";
            default -> null;
        };
    }

    private static String textMismatch(Map<?, ?> obs) {
        String seen = text(obs.get("seen"));
        // Chữ sinh viên ĐANG hiển thị được phép in nguyên văn — em ấy tự đối chiếu được.
        if (seen != null) return "đang hiển thị \"" + seen + "\".";
        // Phân biệt hai ca mà `text()` gộp lại: engine KHÔNG GỬI chữ, và chữ gửi về là RỖNG.
        // "Đang để trống" là chẩn đoán khác hẳn, và là ca thường gặp nhất của FORM_PREFILL —
        // sinh viên nối nút sửa nhưng chưa nạp dữ liệu vào form.
        return obs.containsKey("seen") ? "đang để trống." : "nội dung chữ không đúng yêu cầu.";
    }

    private static String countMismatch(Map<?, ?> obs, String subject) {
        Integer found = number(obs.get("found"));
        Integer expected = number(obs.get("expected"));
        if (found == null || expected == null) return "số lượng " + subject + " không đúng.";
        return "đếm được " + found + " " + subject + ", cần " + expected + ".";
    }

    private static String numberMismatch(Map<?, ?> obs) {
        String dimension = text(obs.get("dimension"));
        Integer found = number(obs.get("found"));
        Integer expected = number(obs.get("expected"));
        String unit = text(obs.get("unit"));
        if (dimension == null || found == null || expected == null) {
            return "kích thước không đúng yêu cầu.";
        }
        String u = unit == null ? "" : unit;
        return dimension + " đo được " + found + u + ", cần " + expected + u + ".";
    }

    private static String subjectOf(Map<?, ?> obs) {
        String raw = text(obs.get("subject"));
        return SUBJECT.getOrDefault(raw == null ? "" : raw.toLowerCase(), "thành phần");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String shorten(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > MAX_LENGTH ? t.substring(0, MAX_LENGTH - 1).strip() + "…" : t;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer number(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? null : (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
