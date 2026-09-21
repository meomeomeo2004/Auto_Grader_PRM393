package com.example.grader.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * BẢNG RUNNER CỦA ENGINE CHUNG — dùng để bộ fixture nghiệm thu đối chiếu ĐỘ PHỦ: runner nào
 * chưa có testcase nào trong bộ đo thì đó là năng lực chưa từng chạy, không được công bố như
 * đã kiểm chứng. Xem {@code FixtureResultAssemblyTest}.
 *
 * <p>Ngày 21/9/2026 lớp này bị cắt còn một phần ba. Phần bỏ đi là tầng SUY RA phân loại cho
 * `result.json` — {@code layerOf}, {@code rubricOf}, {@code rubricLabelOf}, {@code groupExpected}
 * cùng hai bảng tra legacy. Chúng đọc {@code layer}, {@code testcase_group}, {@code group_name}
 * của skills_matrix.json, mà ba field đó đã gỡ khỏi bảng (nay bảng chỉ còn sáu field có người
 * đọc — xem {@link BehaviorSuiteMaterializer}). Không một dòng nào trong hệ thống gọi tới chúng,
 * nên để lại chỉ khiến người sau tưởng ba field kia vẫn sống.
 */
public final class TestCaseTaxonomy {

    private TestCaseTaxonomy() {}

    /** Enum đầy đủ; engine chung chỉ sinh ra widget/integration/responsive. */
    public static final Set<String> LAYERS = Set.of(
            "contract", "unit", "widget", "integration", "responsive",
            "persist", "architecture", "visual");

    /**
     * Runner "cửa sau" cho testcase giáo viên tự viết code. Có layer như mọi runner khác, nhưng
     * KHÔNG phải một năng lực của engine (hành vi là do code giáo viên quyết định) nên bị loại
     * khỏi {@link #commonRunners()} — xem javadoc ở đó.
     */
    public static final String CUSTOM_RUNNER = "CUSTOM_CODE";

    /**
     * 8 runner Ch.7 (bản 3.6.0, widget bố cục và hiển thị nâng cao) — có mặt trong
     * {@link #RUNNER_LAYER} nhưng CỐ Ý loại khỏi {@link #commonRunners()}: bộ fixture nghiệm thu
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
}
