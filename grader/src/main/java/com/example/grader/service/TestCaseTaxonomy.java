package com.example.grader.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TRỤC PHÂN LOẠI TESTCASE cho `result.json` v2 — nguồn sự thật DUY NHẤT của {@code layer},
 * {@code rubric} và {@code expected} của testcase GROUP. Dùng chung cho lúc SINH đề
 * Dùng lúc ghép kết quả cũ trong {@link BatchGradingService}; Golden suite mới ghi layer trực tiếp
 * trong testcase definition nên không còn phụ thuộc thư viện template.
 * bên không lệch nhau.
 *
 * <p><b>layer</b> — tầng kiểm thử. Tiêu chí phân tầng là ĐIỀU ĐƯỢC KHẲNG ĐỊNH, không phải
 * cách dựng widget: trong engine chung mọi runner đều khởi động ứng dụng thật nên không thể
 * phân tầng theo cách pump.
 * <ul>
 *   <li>{@code widget} — mở màn hình rồi khẳng định trạng thái TĨNH (có/không, thuộc tính, kích thước)</li>
 *   <li>{@code integration} — có THAO TÁC (chạm/nhập) rồi khẳng định HỆ QUẢ</li>
 *   <li>{@code responsive} — khẳng định bố cục theo KÍCH THƯỚC màn hình</li>
 * </ul>
 *
 * <p><b>rubric</b> — nhóm CHỨC NĂNG theo cách sinh viên hiểu bài ("Thêm người dùng"), lấy từ
 * nhóm giáo viên đã gom trên UI. Lưu ý {@code testcase_group} (LOGIC/WIDGET/BEHAVIOR) KHÔNG
 * phải rubric: nó phân theo bản chất kiểm tra, cùng trục với {@code layer} — chỉ dùng làm
 * phương án chống rỗng.
 *
 * <p>Hợp đồng đầy đủ: {@code SPEC_grader_result_json/SPEC_result_json.md} mục 3.1 và 3.3.
 */
public final class TestCaseTaxonomy {

    private TestCaseTaxonomy() {}

    /** Enum đầy đủ; engine chung chỉ sinh ra widget/integration/responsive. */
    public static final Set<String> LAYERS = Set.of(
            "contract", "unit", "widget", "integration", "responsive",
            "persist", "architecture", "visual");

    /** Thang so sánh tầng — dùng khi testcase GROUP lấy tầng cao nhất của các con. */
    private static final Map<String, Integer> LAYER_RANK = Map.of(
            "contract", 0, "unit", 1, "widget", 2,
            "integration", 3, "responsive", 4, "persist", 5);

    /**
     * Runner "cửa sau" cho testcase giáo viên tự viết code. Có layer như mọi runner khác, nhưng
     * KHÔNG phải một năng lực của engine (hành vi là do code giáo viên quyết định) nên bị loại
     * khỏi {@link #commonRunners()} — xem javadoc ở đó.
     */
    public static final String CUSTOM_RUNNER = "CUSTOM_CODE";

    /**
     * 8 runner Ch.7 (bản 3.6.0, widget bố cục và hiển thị nâng cao) — có layer trong
     * {@link #RUNNER_LAYER} để {@link #layerForRunner} vẫn tra được ngay khi soạn đề dùng chúng,
     * nhưng CỐ Ý loại khỏi {@link #commonRunners()}: bộ fixture nghiệm thu
     * ({@code fixtures/result-json-v2}) chưa có testcase nào dùng 8 runner này, nên "chứng nhận"
     * chúng trên fixture lúc này sẽ nói dối về độ phủ — đúng lỗ hổng mà {@link #commonRunners()}
     * sinh ra để bịt (xem javadoc ở đó). Gỡ khỏi tập này ngay khi fixture có testcase thật cho cả
     * tám (cần thiết kế thêm màn hình demo Ch.7 trong app fixture — việc đang dở, xem lịch sử PR).
     */
    private static final Set<String> CH7_PENDING_FIXTURE_COVERAGE = Set.of(
            "SCROLL_DIRECTION", "SCROLL_TO_END", "STACK_LAYERS", "INDEXED_STACK_SWITCH",
            "BOTTOM_SHEET_FLOW", "TABLE_ROWS", "SLIVER_SCROLL_COLLAPSE", "EXPANDED_WIDGET");

    /** 32 runner của engine chung COMMON_V1 (24 gốc + 8 Ch.7 bản 3.6.0). GROUP là dẫn xuất, xử lý riêng. */
    private static final Map<String, String> RUNNER_LAYER = Map.ofEntries(
            Map.entry("WIDGET_VISIBLE", "widget"),
            Map.entry("WIDGET_TYPE_VISIBLE", "widget"),
            Map.entry("WIDGET_TEXT_CONTENT", "widget"),
            Map.entry("WIDGET_TEXT_STYLE", "widget"),
            Map.entry("WIDGET_ENABLED", "widget"),
            Map.entry("WIDGET_SEMANTICS_LABEL", "widget"),
            Map.entry("WIDGET_DIMENSION", "widget"),
            Map.entry("WIDGET_PADDING", "widget"),
            Map.entry("WIDGET_GAP", "widget"),
            Map.entry("LIST_VISIBLE", "widget"),
            Map.entry("LIST_ITEM_COUNT", "widget"),
            // APP_BOOT chạy toàn tuyến main() → dữ liệu → khung hình đầu, không phải một màn hình.
            Map.entry("APP_BOOT", "integration"),
            Map.entry("BUTTON_ACTION", "integration"),
            Map.entry("NAVIGATION", "integration"),
            Map.entry("DIALOG_FLOW", "integration"),
            Map.entry("STATE_REACTIVE_FLOW", "integration"),
            Map.entry("FORM_REQUIRED_FIELDS", "integration"),
            Map.entry("FORM_VALIDATE_FIELDS", "integration"),
            Map.entry("FORM_PREFILL", "integration"),
            Map.entry("FORM_SUBMIT", "integration"),
            Map.entry("FORM_PERSISTENCE_FLOW", "persist"),
            Map.entry("CRUD_EDIT_FLOW", "integration"),
            Map.entry("CRUD_DELETE_FLOW", "integration"),
            Map.entry("CRUD_DETAIL_FLOW", "integration"),
            Map.entry("RESPONSIVE_GRID_FLOW", "responsive"),
            Map.entry("RESPONSIVE_NO_OVERFLOW", "responsive"),
            Map.entry("RESPONSIVE_TARGET", "responsive"),
            // Ch.7 (3.6.0) — widget bố cục và hiển thị nâng cao. Cùng tiêu chí phân tầng như
            // trên: có thao tác (kéo/chạm) rồi khẳng định hệ quả → integration; chỉ mở màn hình
            // rồi khẳng định trạng thái tĩnh → widget.
            Map.entry("SCROLL_DIRECTION", "widget"),
            Map.entry("SCROLL_TO_END", "integration"),
            Map.entry("STACK_LAYERS", "widget"),
            Map.entry("INDEXED_STACK_SWITCH", "integration"),
            Map.entry("BOTTOM_SHEET_FLOW", "integration"),
            Map.entry("TABLE_ROWS", "widget"),
            Map.entry("SLIVER_SCROLL_COLLAPSE", "integration"),
            Map.entry("EXPANDED_WIDGET", "widget"),
            // Code tay của giáo viên: engine khởi động app thật rồi chạy assert của họ — cùng lý lẽ
            // với APP_BOOT. Không có "custom" trong enum layer của SPEC, và tầng thật thì không suy
            // được từ code, nên quy về integration thay vì để rỗng.
            Map.entry(CUSTOM_RUNNER, "integration"));

    /**
     * Suy layer từ tiền tố test_id cho ĐỀ LEGACY (matrix không có runner).
     * Thứ tự quan trọng: UI_RESPONSIVE_/UI_LAYOUT_ phải đứng TRƯỚC luật UI_ chung.
     */
    private static final List<String[]> LEGACY_PREFIX_LAYER = List.of(
            new String[]{"CONTRACT_", "contract"},
            new String[]{"UI_RESPONSIVE_", "responsive"},
            new String[]{"UI_LAYOUT_", "responsive"},
            new String[]{"MODEL_", "unit"},
            new String[]{"REPOSITORY_", "unit"},
            new String[]{"VIEWMODEL_", "unit"},
            new String[]{"SQLITE_", "unit"},
            new String[]{"SCREEN_", "widget"},
            new String[]{"PERSIST_", "persist"},
            new String[]{"ARCH_", "architecture"},
            new String[]{"VISUAL_", "visual"},
            new String[]{"UI_", "integration"});

    /** Layer của một runner đơn lẻ; {@code null} nếu runner lạ hoặc là GROUP. */
    public static String layerForRunner(String runner) {
        return runner == null ? null : RUNNER_LAYER.get(runner.trim().toUpperCase());
    }

    /**
     * Tên mọi runner của engine chung, TRỪ {@code GROUP} (dẫn xuất, tầng lấy theo con) và
     * {@link #CUSTOM_RUNNER}.
     *
     * <p>Để fixture đối chiếu được độ phủ: runner nào chưa có testcase nào trong bộ đo thì nó là
     * năng lực CHƯA TỪNG CHẠY, không được công bố như đã kiểm chứng.
     *
     * <p>{@code CUSTOM_CODE} nằm ngoài phép đo đó vì nó không khẳng định điều gì cố định — đạt hay
     * hỏng là do code giáo viên viết, nên "chứng nhận" nó trên fixture không nói lên điều gì về
     * engine. Nhánh dispatch của nó trong {@code exam_test.dart} thì vẫn nên có bài đo riêng.
     *
     * <p>8 runner Ch.7 cũng tạm loại — xem {@link #CH7_PENDING_FIXTURE_COVERAGE}.
     */
    public static Set<String> commonRunners() {
        Set<String> out = new LinkedHashSet<>(RUNNER_LAYER.keySet());
        out.remove(CUSTOM_RUNNER);
        out.removeAll(CH7_PENDING_FIXTURE_COVERAGE);
        return out;
    }

    /**
     * Layer của một testcase, theo thứ tự ưu tiên: giá trị đã ghi sẵn trong matrix →
     * suy từ {@code runner} (GROUP lấy tầng cao nhất của các con) → tiền tố test_id (legacy).
     *
     * @param row     dòng cấu hình trong skills_matrix.json; có thể null
     * @param testId  dùng cho nhánh legacy
     * @return một giá trị trong {@link #LAYERS}, hoặc null nếu không suy được
     */
    public static String layerOf(Map<String, ?> row, String testId) {
        if (row != null) {
            // Chỉ nhận giá trị đã theo enum của SPEC. Template cũ ghi layer dạng
            // SCREEN/BLACKBOX (trục khác) nên phải bỏ qua, không được nhận nhầm.
            String declared = text(row.get("layer"));
            if (declared != null && LAYERS.contains(declared.toLowerCase())) {
                return declared.toLowerCase();
            }
            String runner = text(row.get("runner"));
            if (runner != null) {
                if ("GROUP".equalsIgnoreCase(runner.trim())) return groupLayer(row);
                String byRunner = layerForRunner(runner);
                if (byRunner != null) return byRunner;
            }
        }
        return legacyLayer(testId);
    }

    /** Testcase GROUP mang tầng CAO NHẤT trong các testcase con. */
    private static String groupLayer(Map<String, ?> row) {
        Object children = row.get("children");
        if (!(children instanceof List<?> list)) return null;
        String best = null;
        int bestRank = -1;
        for (Object child : list) {
            if (!(child instanceof Map<?, ?> childRow)) continue;
            String layer = layerForRunner(text(childRow.get("runner")));
            Integer rank = layer == null ? null : LAYER_RANK.get(layer);
            if (rank != null && rank > bestRank) {
                bestRank = rank;
                best = layer;
            }
        }
        return best;
    }

    private static String legacyLayer(String testId) {
        if (testId == null) return null;
        String id = testId.trim().toUpperCase();
        for (String[] rule : LEGACY_PREFIX_LAYER) {
            if (id.startsWith(rule[0])) return rule[1];
        }
        return null;
    }

    /**
     * Rubric của một testcase: nhóm giáo viên đã gom → {@code testcase_group} (chống rỗng) →
     * field {@code rubric} có sẵn của đề legacy.
     */
    public static String rubricOf(Map<String, ?> row) {
        if (row == null) return null;
        for (String key : new String[]{"group_id", "testcase_group", "rubric"}) {
            String value = text(row.get(key));
            if (value != null) return value;
        }
        return null;
    }

    /**
     * NHÃN HIỂN THỊ của {@link #rubricOf} — tên nhóm chức năng giáo viên đặt trên UI.
     *
     * <p>`rubric` là MÃ (`THEM_USER`), không đưa cho sinh viên đọc được. Nhãn phải do phía **sở
     * hữu dữ liệu** phát ra: bên đọc tự dựng bảng `THEM_USER → "Thêm người dùng"` là tạo ra nguồn
     * sự thật thứ hai, đúng loại lỗi hai bên đã bỏ ở chỗ khác.
     *
     * @return `group_name`; {@code null} nếu chưa có (đề legacy, hoặc nhóm chưa được đặt tên) —
     *         cố ý KHÔNG trả về mã, vì mã đã nằm ở `rubric` rồi
     */
    public static String rubricLabelOf(Map<?, ?> row) {
        if (row == null) return null;
        String label = text(row.get("group_name"));
        if (label == null) return null;
        // Chưa đặt tên thì UI để group_name = group_id; đó là mã, không phải nhãn.
        return label.equalsIgnoreCase(text(row.get("group_id"))) ? null : label;
    }

    // ── expected của testcase GROUP ───────────────────────────────

    /** Câu `expected` mà bản cũ tự sinh cho testcase GROUP — phải thay khi gặp lại. */
    private static final Pattern LEGACY_GROUP_EXPECTED =
            Pattern.compile("^Tất cả\\s+\\d+\\s+assert trong nhóm phải đạt\\.?$", Pattern.CASE_INSENSITIVE);

    /**
     * `expected` của testcase GROUP — DẪN XUẤT từ tên nhóm ghép với `expected` của các
     * testcase con, vì một nhóm chỉ có MỘT kết quả nên yêu cầu của nó là hợp của các con.
     *
     * <p>Trả về {@code null} khi KHÔNG cần thay: row không phải GROUP, hoặc `expected` hiện tại
     * là nội dung đáng giữ (giáo viên viết tay lúc ra đề — bao giờ cũng sát đề hơn bản ghép máy).
     * Chỉ dựng câu mới khi `expected` còn trống, hoặc còn là câu tự sinh của bản cũ
     * ({@code "Tất cả N assert trong nhóm phải đạt."}): câu đó đếm số testcase và dùng chữ
     * "assert" — đều là từ vựng nội bộ của hệ thống chấm, sinh viên không kiểm chứng được trên
     * bài của mình, mà `expected` thì đi thẳng tới sinh viên qua bản nhận xét.
     *
     * <p>Gọi ở CẢ HAI đầu — lúc sinh matrix và lúc ghép result.json — nên đề đã publish trước
     * bản sửa cũng không còn phát ra câu cũ, giáo viên không phải publish lại.
     *
     * <p>Lưu ý: UI hiện KHÔNG có ô cho giáo viên nhập `expected` của nhóm. Nếu sau này thêm,
     * phải phân biệt "trống vì chưa nhập" với "giáo viên xoá có ý" ngay tại đây.
     */
    public static String groupExpected(Map<?, ?> row) {
        if (row == null) return null;
        if (!"GROUP".equalsIgnoreCase(String.valueOf(row.get("runner")).trim())) return null;

        String current = text(row.get("expected"));
        if (current != null && !LEGACY_GROUP_EXPECTED.matcher(current).matches()) return null;

        // Trùng nội dung thì chỉ lấy một lần, giữ nguyên thứ tự giáo viên xếp testcase.
        Set<String> parts = new LinkedHashSet<>();
        if (row.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (!(child instanceof Map<?, ?> childRow)) continue;
                String childExpected = text(childRow.get("expected"));
                if (childExpected != null) parts.add(sentence(childExpected));
            }
        }

        String title = groupTitle(row);
        if (parts.isEmpty()) {
            return title == null ? null : "Phải thực hiện đúng yêu cầu \"" + title + "\".";
        }
        String body = String.join(" ", parts);
        return title == null ? body : title + ": " + body;
    }

    /** Tên nhóm giáo viên đặt; null khi chỉ là mã kỹ thuật, để không ghép mã vào câu cho SV đọc. */
    private static String groupTitle(Map<?, ?> row) {
        String name = text(row.get("name"));
        return name == null || name.equalsIgnoreCase(text(row.get("group_id"))) ? null : name;
    }

    /** Bảo đảm mỗi vế là câu trọn vẹn trước khi nối nhiều `expected` lại với nhau. */
    private static String sentence(String s) {
        return ".!?…:".indexOf(s.charAt(s.length() - 1)) >= 0 ? s : s + ".";
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
