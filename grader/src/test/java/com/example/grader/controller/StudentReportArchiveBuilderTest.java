package com.example.grader.controller;

import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingStatus;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentReportArchiveBuilderTest {

    private static final String RESULT_JSON = """
            {"schema_version":"2",
             "student":{"id":"HE180037"},
             "grading_result":{"score":6.5,"passed_tests":2,"failed_tests":1,"total_tests":4,
                               "engine_version":"v9"},
             "test_cases":[
               {"test_id":"TC_01","name":"App khởi động","status":"passed","max_score":2,
                "group_id":"G_THEM","group_name":"Thêm khoản chi"},
               {"test_id":"TC_02","name":"Thêm sinh viên","status":"failed","max_score":2,
                "group_id":"G_THEM","group_name":"Thêm khoản chi",
                "error_code":"WIDGET_NOT_FOUND","actual":"không thấy nút nào"},
               {"test_id":"TC_03","name":"Xoá sinh viên","status":"not_run","max_score":2,
                "group_id":"G_XOA","group_name":"Xóa khoản chi",
                "actual":"chưa chạy vì bộ test không khởi động được"},
               {"test_id":"TC_04","name":"Sửa sinh viên","status":"passed","max_score":2,
                "group_id":"G_SUA","group_name":"Sửa khoản chi"}
             ]}
            """;

    /** 1×1 px PNG hợp lệ — đủ cho POI nhúng; không cần ảnh thật trong unit test. */
    private static final byte[] TINY_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static ExamResult row() {
        ExamResult row = new ExamResult();
        row.setStudentId("HE180037");
        row.setExamId("PE_PRM393_FA26");
        row.setBatchId("BATCH_9");
        row.setStatus(GradingStatus.DONE);
        row.setScore(6.5f);
        row.setResultJson(RESULT_JSON);
        row.setSubmissionHash("a".repeat(64));
        return row;
    }

    @Test
    void buildsJsonXlsxAndLogWithoutFeedbackTxt(@TempDir Path tmp) throws Exception {
        // Hồ sơ 2026-08-22: json + xlsx + logs. feedback.txt (bot NLP) đã bỏ hẳn.
        byte[] archive = new StudentReportArchiveBuilder(s -> s, null, r -> tmp)
                .build("PE_PRM393_FA26", List.of(row()));

        Map<String, byte[]> entries = readAll(archive);
        String home = "Result_of_PE_PRM393_FA26/HE180037/";
        assertNotNull(entries.get(home + "HE180037.json"), "thiếu file JSON cá nhân");
        assertNotNull(entries.get(home + "HE180037.xlsx"), "thiếu file Excel cá nhân");
        assertNotNull(entries.get(home + "logs/grading.log"), "thiếu logs/grading.log");
        assertNull(entries.get(home + "feedback.txt"), "feedback.txt phải bị bỏ khỏi hồ sơ");
        assertNull(entries.get(home + "HE180037.xls"), "định dạng cũ .xls phải biến mất");

        // .xlsx thật (không phải HTML đội lốt): POI mở được, có bảng nhóm + chi tiết.
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(entries.get(home + "HE180037.xlsx")))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            String text = sheetText(sheet);
            assertTrue(text.contains("HỒ SƠ KẾT QUẢ — HE180037"));
            assertTrue(text.contains("ĐIỂM THEO NHÓM TIÊU CHÍ"));
            // Nhóm Thêm: 1/2 đạt, 2/4 điểm — điểm lẻ nổi lên ở cấp nhóm, không ở từng dòng.
            assertTrue(text.contains("Thêm khoản chi") && text.contains("1/2") && text.contains("2/4"), text);
            assertTrue(text.contains("CHI TIẾT TIÊU CHÍ"));
            assertTrue(text.contains("Trượt") && text.contains("Chưa chấm") && text.contains("Đạt"));
            assertTrue(text.contains("không thấy nút nào"), "quan sát của dòng trượt phải có mặt");
            assertTrue(text.contains("a".repeat(64)), "SHA bài nộp phải nằm trong bảng tóm tắt");
        }

        // Log giữ nguyên vai trò đối chứng.
        String log = new String(entries.get(home + "logs/grading.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("[FAILED] TC_02 (WIDGET_NOT_FOUND)"), log);
        assertTrue(log.contains("[NOT_RUN] TC_03"), log);
        String expectedJsonHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(entries.get(home + "HE180037.json")));
        assertTrue(log.contains(expectedJsonHash), "hash file kết quả không khớp nội dung thật");
    }

    @Test
    void embedsEvidenceAndGoldenImagesWhenPresent(@TempDir Path tmp) throws Exception {
        // Ảnh bằng chứng của bài + ảnh chuẩn của đề cùng execution_code → nhúng vào xlsx.
        Path evidence = tmp.resolve("evidence");
        Files.createDirectories(evidence);
        Files.write(evidence.resolve("L1__VP_1.png"), TINY_PNG);
        Path goldens = tmp.resolve("screens");
        Files.createDirectories(goldens);
        Files.write(goldens.resolve("L1__VP_1.png"), TINY_PNG);

        byte[] archive = new StudentReportArchiveBuilder(s -> s, goldens, r -> evidence)
                .build("PE_PRM393_FA26", List.of(row()));
        byte[] xlsx = readAll(archive).get("Result_of_PE_PRM393_FA26/HE180037/HE180037.xlsx");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(2, wb.getAllPictures().size(), "phải nhúng cả ảnh mẫu lẫn ảnh bài làm");
            String text = sheetText(wb.getSheetAt(0));
            assertTrue(text.contains("ẢNH MÀN HÌNH — ĐỐI CHỨNG PHÚC KHẢO"));
            assertTrue(text.contains("Luồng: L1__VP_1"));
            assertTrue(text.contains("Ảnh mẫu (Golden)") && text.contains("Bài làm"));
        }
    }

    @Test
    void survivesMissingHashAndMissingImages() throws Exception {
        // Dữ liệu chấm TRƯỚC khi có cột hash + không có ảnh: hồ sơ vẫn phải dựng được.
        ExamResult legacy = new ExamResult();
        legacy.setStudentId("HE000009");
        legacy.setExamId("PE_OLD");
        legacy.setStatus(GradingStatus.DONE);
        legacy.setScore(5f);
        legacy.setResultJson(RESULT_JSON);

        Map<String, byte[]> entries = readAll(
                new StudentReportArchiveBuilder(s -> s, null, r -> null).build("PE_OLD", List.of(legacy)));
        String log = new String(entries.get("Result_of_PE_OLD/HE000009/logs/grading.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("(không ghi được)"));
        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(entries.get("Result_of_PE_OLD/HE000009/HE000009.xlsx")))) {
            assertEquals(0, wb.getAllPictures().size());
            assertFalse(sheetText(wb.getSheetAt(0)).contains("ĐỐI CHỨNG PHÚC KHẢO"),
                    "không có ảnh thì không mở mục ảnh");
        }
    }

    @Test
    void rendersCachedBotFeedbackForTheSeparateExportButton() {
        // renderFeedbackText vẫn phục vụ nút "Sinh feedback" riêng — không nằm trong hồ sơ.
        ExamResult row = new ExamResult();
        row.setStudentId("HE000001");
        row.setFeedbackJson("""
                {"studentId":"HE000001","scoreSummary":"7.0/10",
                 "feedbackText":"Bài làm tốt phần CRUD, cần xem lại validate.",
                 "teacherReviewRequired":true,"reviewReasons":["Điểm lệch giữa 2 lần chấm"]}
                """);
        String feedback = StudentReportArchiveBuilder.renderFeedbackText(row);
        assertTrue(feedback.contains("7.0/10"));
        assertTrue(feedback.contains("cần xem lại validate"));
        assertTrue(feedback.contains("Điểm lệch giữa 2 lần chấm"));
    }

    private static String sheetText(XSSFSheet sheet) {
        StringBuilder sb = new StringBuilder();
        sheet.forEach(row -> row.forEach(cell -> sb.append(cell.toString()).append('\n')));
        return sb.toString();
    }

    private static Map<String, byte[]> readAll(byte[] archive) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                out.put(e.getName(), zip.readAllBytes());
            }
        }
        assertEquals(false, out.isEmpty());
        return out;
    }
}
