package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm ĐIỂM GHÉP THẬT của result.json, không chỉ kiểm hàm tra bảng.
 *
 * <p>Trước đây tệp này còn kiểm cả tầng gắn nhãn phân loại (`rubric`, `layer`, `chapter`,
 * `category`, `skill_name`…). Toàn bộ tầng đó đã bị gỡ ngày 2026-08-22: nó tồn tại để thoả
 * một hợp đồng ngoài, và không nơi nào trong hệ thống đọc tới. Cái còn lại — và là cái
 * đáng kiểm — là những field ĐƯỢC SUY RA, cùng việc dọn sạch field chết.
 *
 * <p>Hàm private nên gọi qua reflection, cùng lối với {@code ResultControllerNormalizationTest}.
 */
class ResultTaxonomyAnnotationTest {

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> cases(Map<String, Object>... tcs) {
        return new ArrayList<>(List.of(tcs));
    }

    // ── enrichTestCases: chỉ còn bổ sung nhãn kỹ thuật ────────────
    private static void enrich(List<Map<String, Object>> tcs, Map<String, Object> matrix) throws Exception {
        Method m = BatchGradingService.class
                .getDeclaredMethod("enrichTestCases", List.class, Map.class);
        m.setAccessible(true);
        m.invoke(new BatchGradingService(), tcs, matrix);
    }

    @Test
    void chiDoTenLuongVaMaNhomTuMaTran() throws Exception {
        // skills_matrix.json chỉ còn sáu field có người đọc (21/9/2026). enrich không được
        // dựng lại những field đã gỡ, kể cả khi ai đó nhét chúng vào ma trận bằng tay.
        Map<String, Object> matrix = map("TC_ADD", map(
                "scenario_name", "thêm chi tiêu", "group_id", "CRUD",
                "skill_code", "STORAGE_SQLITE_CRUD", "difficulty", "basic", "group_name", "CRUD"));
        List<Map<String, Object>> tcs = cases(map("test_id", "TC_ADD"));

        enrich(tcs, matrix);

        assertEquals("thêm chi tiêu", tcs.get(0).get("scenario_name"));
        assertEquals("CRUD", tcs.get(0).get("group_id"));
        assertNull(tcs.get(0).get("skill_code"));
        assertNull(tcs.get(0).get("difficulty"));
        assertNull(tcs.get(0).get("group_name"));
    }

    @Test
    void doesNotOverwriteWhatTheGraderAlreadySent() throws Exception {
        // Grader chạy trong container biết rõ hơn file cấu hình trên đĩa.
        Map<String, Object> matrix = map("TC_ADD", map("group_id", "TU_MATRIX"));
        List<Map<String, Object>> tcs = cases(map("test_id", "TC_ADD", "group_id", "TU_GRADER"));

        enrich(tcs, matrix);

        assertEquals("TU_GRADER", tcs.get(0).get("group_id"));
    }

    @Test
    void noLongerRebuildsExpected() throws Exception {
        // `expected` đã gỡ khỏi result.json: câu đặc tả giống hệt nhau ở mọi bài nộp nên
        // không nói gì về BÀI NÀY. enrich không được dựng lại nó nữa.
        Map<String, Object> matrix = map("TC_ADD", map("expected", "Phải hiện nút Lưu."));
        List<Map<String, Object>> tcs = cases(map("test_id", "TC_ADD"));

        enrich(tcs, matrix);

        assertFalse(tcs.get(0).containsKey("expected"));
    }

    // ── Field dẫn xuất + dọn field chết ───────────────────────────
    private static List<Map<String, Object>> derive(List<Map<String, Object>> tcs) throws Exception {
        Method m = BatchGradingService.class.getDeclaredMethod("fillDerivedFields", List.class);
        m.setAccessible(true);
        m.invoke(new BatchGradingService(), tcs);
        return tcs;
    }

    @Test
    void derivesExecutedFromStatusForLegacyGraderOutput() throws Exception {
        // Đề legacy không gửi `executed`; phải suy tại backend, không được bỏ trống.
        List<Map<String, Object>> tcs = derive(cases(
                map("test_id", "A", "status", "passed"),
                map("test_id", "B", "status", "failed"),
                map("test_id", "C", "status", "not_run")));

        assertEquals(true,  tcs.get(0).get("executed"));
        assertEquals(true,  tcs.get(1).get("executed"));
        assertEquals(false, tcs.get(2).get("executed"));
    }

    @Test
    void keepsExecutedSentByEngine() throws Exception {
        // Engine biết rõ hơn backend: nó đã chạy testcase rồi mới kết luận failed.
        List<Map<String, Object>> tcs = derive(cases(
                map("test_id", "A", "status", "not_run", "executed", true)));

        assertEquals(true, tcs.get(0).get("executed"));
    }

    @Test
    void flattensErrorCodeWithoutInventingOne() throws Exception {
        // Rút `error.code` của đề legacy ra field phẳng TRƯỚC khi bỏ object `error`;
        // không có mã thì để null chứ không bịa.
        List<Map<String, Object>> tcs = derive(cases(
                map("test_id", "A", "status", "failed", "error", map("code", "ASSERTION_FAILED")),
                map("test_id", "B", "status", "failed", "error", map("message", "khong co code"))));

        assertEquals("ASSERTION_FAILED", tcs.get(0).get("error_code"));
        assertNull(tcs.get(1).get("error_code"));
        assertFalse(tcs.get(0).containsKey("error"), "object error phải bị gỡ sau khi rút mã");
    }

    @Test
    void dropsScoreBecauseItIsDerivable() throws Exception {
        // Hệ thống KHÔNG chấm điểm một phần: đạt là trọn max_score, không đạt là 0.
        // Giữ hai con số cho cùng một thông tin chỉ tạo cơ hội cho chúng lệch nhau.
        List<Map<String, Object>> tcs = derive(cases(
                map("test_id", "A", "status", "passed", "score", 5.0, "max_score", 5.0)));

        assertFalse(tcs.get(0).containsKey("score"));
        assertEquals(5.0, tcs.get(0).get("max_score"));
    }

    @Test
    void dropsFieldsNobodyReads() throws Exception {
        // Nhãn của hợp đồng cũ. Đã rà toàn bộ frontend và backend: không nơi nào đọc.
        List<Map<String, Object>> tcs = derive(cases(map(
                "test_id", "A", "status", "failed",
                "blocked_by", null, "rubric", "ADD_USER", "rubric_label", "Thêm người dùng",
                "layer", "behavior", "chapter", 3, "category", "STORAGE",
                "category_label", "Lưu trữ", "skill_name", "SQLite CRUD",
                "difficulty_label", "Trung bình", "expected", "Câu đặc tả cũ")));

        Map<String, Object> tc = tcs.get(0);
        for (String dead : List.of("blocked_by", "rubric", "rubric_label", "layer", "chapter",
                "category", "category_label", "skill_name", "difficulty_label", "expected")) {
            assertFalse(tc.containsKey(dead), "field chết còn sót: " + dead);
        }
        // Phần lõi phải nguyên vẹn.
        assertEquals("A", tc.get("test_id"));
        assertEquals("failed", tc.get("status"));
        assertTrue(tc.containsKey("executed"));
    }

    @Test
    void stripsErrorOnlyFieldsFromPassedRows() throws Exception {
        // Dòng đạt không có lỗi để mô tả. Để chúng nằm đó với giá trị null nghĩa là
        // "có chỗ cho thông tin này nhưng chưa biết" — sai, sự thật là "không có".
        List<Map<String, Object>> tcs = derive(cases(map(
                "test_id", "A", "status", "passed",
                "observation", map("kind", "X"), "violations", List.of(),
                "error_origin", "STUDENT", "error_stage", "TESTCASE_EXECUTION",
                "error_code", "ASSERTION_FAILED")));

        Map<String, Object> tc = tcs.get(0);
        for (String k : List.of("observation", "violations", "error_origin", "error_stage", "error_code")) {
            assertFalse(tc.containsKey(k), "dòng đạt còn sót field lỗi: " + k);
        }
        // Cờ cần chấm tay VẪN phải có mặt: vắng mặt thì bên đọc phải tự đoán mặc định.
        assertEquals(false, tc.get("requires_manual_review"));
    }

    @Test
    void keepsErrorFieldsOnFailedRows() throws Exception {
        // Đúng những field vừa gỡ khỏi dòng đạt là thứ phúc khảo cần ở dòng trượt.
        List<Map<String, Object>> tcs = derive(cases(map(
                "test_id", "A", "status", "failed",
                "observation", map("kind", "BEHAVIOR_CHECKPOINT_FAILED"),
                "error_origin", "STUDENT", "error_stage", "TESTCASE_EXECUTION")));

        Map<String, Object> tc = tcs.get(0);
        assertTrue(tc.containsKey("observation"));
        assertEquals("STUDENT", tc.get("error_origin"));
        assertEquals("TESTCASE_EXECUTION", tc.get("error_stage"));
    }
}
