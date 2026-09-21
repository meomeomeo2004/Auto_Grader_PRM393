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
                "scenario_code":"THEM","scenario_name":"Thêm khoản chi"},
               {"test_id":"TC_02","name":"Thêm sinh viên","status":"failed","max_score":2,
                "scenario_code":"THEM","scenario_name":"Thêm khoản chi",
                "error_code":"WIDGET_NOT_FOUND","actual":"không thấy nút nào"},
               {"test_id":"TC_03","name":"Xoá sinh viên","status":"not_run","max_score":2,
                "scenario_code":"XOA","scenario_name":"Xóa khoản chi",
                "actual":"chưa chạy vì bộ test không khởi động được"},
               {"test_id":"TC_04","name":"Sửa sinh viên","status":"passed","max_score":2,
                "scenario_code":"SUA","scenario_name":"Sửa khoản chi"}
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
    void buildsXlsxAndLogWithoutBundledJson(@TempDir Path tmp) throws Exception {
        byte[] archive = new StudentReportArchiveBuilder(null, r -> tmp, null)
                .build("PE_PRM393_FA26", List.of(row()));

        Map<String, byte[]> entries = readAll(archive);
        String home = "Result_of_PE_PRM393_FA26/HE180037/";
        assertTrue(entries.keySet().stream().noneMatch(name -> name.endsWith(".json")));
        assertNotNull(entries.get(home + "HE180037.xlsx"), "thiếu file Excel cá nhân");
        assertNotNull(entries.get(home + "logs/grading.log"), "thiếu logs/grading.log");
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
            assertTrue(text.contains("Failed") && text.contains("Not run") && text.contains("Passed"));
            assertTrue(text.contains("Check point"));
            assertTrue(text.contains("không thấy nút nào"), "quan sát của dòng trượt phải có mặt");
            assertFalse(text.contains("SHA-256") || text.contains("a".repeat(64)));
            int details = findRow(sheet, "CHI TIẾT TIÊU CHÍ");
            long groupNames = java.util.stream.StreamSupport.stream(sheet.spliterator(), false)
                    .filter(line -> line.getRowNum() > details)
                    .flatMap(line -> java.util.stream.StreamSupport.stream(line.spliterator(), false))
                    .filter(c -> c.toString().equals("Thêm khoản chi")).count();
            assertEquals(1, groupNames, "tên nhóm chỉ xuất hiện một lần trong chi tiết");
        }

        // Log giữ nguyên vai trò đối chứng.
        String log = new String(entries.get(home + "logs/grading.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("[FAILED] TC_02 (WIDGET_NOT_FOUND)"), log);
        assertTrue(log.contains("[NOT_RUN] TC_03"), log);
        assertTrue(log.contains("a".repeat(64)));
        assertFalse(log.contains("file .json") || log.contains("file kết quả kèm theo"));
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

        byte[] archive = new StudentReportArchiveBuilder(goldens, r -> evidence, null)
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
                new StudentReportArchiveBuilder(null, r -> null, null).build("PE_OLD", List.of(legacy)));
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
    void linksHistoricalPrerequisitesWithActualStatusesAndKeepsZeroPointParents(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("behavior_plan.json"), """
                {"cases":[
                  {"test_id":"TC_02","scenario_id":"ADD","execution_code":"ADD_VP1",
                   "checkpoint":{"id":"child","requires":"boot"}},
                  {"test_id":"TC_01","scenario_id":"ADD","execution_code":"ADD_VP1",
                   "checkpoint":{"id":"boot"}},
                  {"test_id":"TC_03","scenario_id":"DELETE","execution_code":"DELETE_VP1",
                   "checkpoint":{"id":"del","requires":"missing"}},
                  {"test_id":"TC_04","scenario_id":"ADD","execution_code":"ADD_VP1",
                   "checkpoint":{"id":"edit","requires":"child"}}
                ]}
                """);
        ExamResult student = row();
        student.setResultJson(RESULT_JSON.replace("\"status\":\"passed\",\"max_score\":2",
                "\"status\":\"passed\",\"max_score\":0"));
        byte[] archive = new StudentReportArchiveBuilder(null, null, r -> tmp)
                .build("PE_PRM393_FA26", List.of(student));
        try (XSSFWorkbook wb = workbook(archive)) {
            XSSFSheet sheet = wb.getSheetAt(0);
            int parent = findRow(sheet, "App khởi động");
            int child = findRow(sheet, "  Thêm sinh viên");
            assertTrue(parent < child);
            assertEquals("1.1", sheet.getRow(parent).getCell(0).toString());
            assertEquals("0/0", sheet.getRow(parent).getCell(4).toString());
            assertEquals("1.1 · Passed", sheet.getRow(child).getCell(2).toString());
            assertEquals("'Ket qua'!B" + (parent + 1), sheet.getRow(child).getCell(2).getHyperlink().getAddress());
            int otherGroup = findRow(sheet, "Sửa sinh viên");
            assertEquals("1.2 · Failed · Thêm khoản chi", sheet.getRow(otherGroup).getCell(2).toString());
            assertEquals("missing (unresolved)", sheet.getRow(findRow(sheet, "Xoá sinh viên")).getCell(2).toString());
            assertEquals(4, java.util.stream.StreamSupport.stream(sheet.spliterator(), false)
                    .filter(line -> line.getRowNum() > findRow(sheet, "CHI TIẾT TIÊU CHÍ"))
                    .filter(line -> line.getCell(0) != null && line.getCell(0).toString().matches("\\d+\\.\\d+")).count());
        }
    }

    @Test
    void cyclesAmbiguousExecutionAndUnknownStatusesDoNotLoseRows(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("behavior_plan.json"), """
                {"cases":[
                  {"test_id":"TC_01","execution_code":"A","checkpoint":{"id":"one","requires":"two"}},
                  {"test_id":"TC_02","execution_code":"A","checkpoint":{"id":"two","requires":"one"}},
                  {"test_id":"TC_03","execution_code":"B","checkpoint":{"id":"three","requires":"one"}},
                  {"test_id":"TC_04","execution_code":"C","checkpoint":{"id":"one"}}
                ]}
                """);
        ExamResult student = row();
        student.setResultJson(RESULT_JSON.replace("\"status\":\"not_run\"", "\"status\":\"error\""));
        try (XSSFWorkbook wb = workbook(new StudentReportArchiveBuilder(null, null, r -> tmp)
                .build("PE_PRM393_FA26", List.of(student)))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertTrue(sheetText(sheet).contains("(cycle)"));
            int unrelated = findRow(sheet, "Xoá sinh viên");
            assertEquals("Error", sheet.getRow(unrelated).getCell(3).toString());
            assertEquals("one (unresolved)", sheet.getRow(unrelated).getCell(2).toString());
            assertNull(sheet.getRow(unrelated).getCell(2).getHyperlink());
            assertEquals(4, java.util.stream.StreamSupport.stream(sheet.spliterator(), false)
                    .filter(line -> line.getRowNum() > findRow(sheet, "CHI TIẾT TIÊU CHÍ"))
                    .filter(line -> line.getCell(0) != null && line.getCell(0).toString().matches("\\d+\\.\\d+")).count());
        }
    }

    private static XSSFWorkbook workbook(byte[] archive) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(readAll(archive)
                .get("Result_of_PE_PRM393_FA26/HE180037/HE180037.xlsx")));
    }

    @Test
    void matchesReferenceProfileAndFiveColumnSummaryWithoutChangingDetailLayout() throws Exception {
        ExamResult student = row();
        student.setStudentName("Nguyễn Văn An");
        student.setResultJson(RESULT_JSON.replace("không thấy nút nào", "first\\nsecond\\nthird"));
        try (XSSFWorkbook wb = workbook(new StudentReportArchiveBuilder(null, null, null)
                .build("PE_PRM393_FA26", List.of(student)))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals(14.5f, sheet.getDefaultRowHeightInPoints());
            int[] widths = {5, 26, 40, 12, 12, 60};
            for (int col = 0; col < widths.length; col++) assertEquals(widths[col] * 256, sheet.getColumnWidth(col));
            assertTrue(sheet.getMergedRegions().stream().anyMatch(region -> region.formatAsString().equals("A1:C1")));
            assertEquals(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER, sheet.getRow(0).getCell(0).getCellStyle().getAlignment());
            assertEquals("Calibri", sheet.getRow(0).getCell(0).getCellStyle().getFont().getFontName());
            assertEquals(13, sheet.getRow(0).getCell(0).getCellStyle().getFont().getFontHeightInPoints());
            for (int line = 1; line <= 6; line++) {
                assertEquals(14.5f, sheet.getRow(line).getHeightInPoints());
                assertEquals(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER, sheet.getRow(line).getCell(0).getCellStyle().getAlignment());
                int current = line;
                assertFalse(sheet.getMergedRegions().stream().anyMatch(region -> region.isInRange(current, 2)));
            }
            int score = findRow(sheet, "Điểm");
            assertEquals("6.5", sheet.getRow(score).getCell(2).toString());
            int summary = findRow(sheet, "ĐIỂM THEO NHÓM TIÊU CHÍ");
            assertTrue(sheet.getMergedRegions().stream().anyMatch(region -> region.getFirstRow() == summary
                    && region.getFirstColumn() == 0 && region.getLastColumn() == 3));
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.NONE, sheet.getRow(summary).getCell(0).getCellStyle().getBorderTop());
            assertEquals("FFEEF2FF", sheet.getRow(summary).getCell(4).getCellStyle().getFillForegroundXSSFColor().getARGBHex());
            var head = sheet.getRow(summary + 1);
            // Năm cột từ 21/9/2026: mỗi dòng là một LUỒNG, cột "Nhóm" chỉ ghi ở dòng đầu nhóm.
            assertEquals(List.of("STT", "Nhóm", "Luồng", "Check point", "Điểm"),
                    java.util.stream.IntStream.range(0, 5).mapToObj(col -> head.getCell(col).toString()).toList());
            assertEquals("Aptos Narrow", head.getCell(0).getCellStyle().getFont().getFontName());
            assertEquals(org.apache.poi.ss.usermodel.FillPatternType.NO_FILL, head.getCell(1).getCellStyle().getFillPattern());
            var first = sheet.getRow(summary + 2);
            assertEquals(1, first.getCell(0).getNumericCellValue());
            // Hồ sơ dựng từ kết quả CŨ (không có scenario_code/scenario_name): nhóm cũ thành
            // luồng, ô Nhóm ghi "—" để không ai tưởng nó nối tiếp nhóm phía trên.
            assertEquals("—", first.getCell(1).toString());
            assertEquals("Thêm khoản chi", first.getCell(2).toString());
            assertEquals("1/2", first.getCell(3).toString());
            assertEquals("2/4", first.getCell(4).toString());
            assertEquals(14.5f, first.getHeightInPoints());
            int total = findRow(sheet, "TỔNG");
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.THIN, sheet.getRow(total).getCell(0).getCellStyle().getBorderLeft());
            assertEquals("4/8", sheet.getRow(total).getCell(3).toString());
            assertTrue(sheet.getMergedRegions().stream().anyMatch(region -> region.getFirstRow() == total
                    && region.getFirstColumn() == 3 && region.getLastColumn() == 4));
            int details = findRow(sheet, "CHI TIẾT TIÊU CHÍ");
            assertEquals(List.of("Check point", "Prerequisite", "Status", "Score", "Observation"),
                    java.util.stream.IntStream.range(1, 6).mapToObj(col -> sheet.getRow(details + 1).getCell(col).toString()).toList());
            int band = details + 2;
            assertTrue(sheet.getMergedRegions().stream().anyMatch(region -> region.getFirstRow() == band
                    && region.getFirstColumn() == 1 && region.getLastColumn() == 5));
            assertEquals("1.1", sheet.getRow(band + 1).getCell(0).toString());
            assertEquals(43.5f, sheet.getRow(findRow(sheet, "Thêm sinh viên")).getHeightInPoints());
            for (var line : sheet) if (line.getRowNum() >= details) {
                assertEquals(0f, line.getHeightInPoints() % 14.5f);
                for (var cell : line) {
                    assertEquals("Calibri", wb.getFontAt(cell.getCellStyle().getFontIndex()).getFontName());
                    assertEquals(11, wb.getFontAt(cell.getCellStyle().getFontIndex()).getFontHeightInPoints());
                }
            }
        }
    }

    @Test
    void matchesEngineFallbackScopeAndPreservesTinyScores(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("behavior_plan.json"), """
                {"cases":[
                  {"test_id":"TC_01","execution_code":"  ","scenario_code":" LEGACY ",
                   "scenario_id":"irrelevant-one","checkpoint":{"id":"  "}},
                  {"test_id":"TC_02","execution_code":" LEGACY ","scenario_code":"other",
                   "scenario_id":"irrelevant-two","checkpoint":{"id":" child ","requires":" TC_01 "}},
                  {"test_id":"TC_03","scenario_code":" DIFFERENT ","scenario_id":"irrelevant-two",
                   "checkpoint":{"id":"delete","requires":" child "}}
                ]}
                """);
        ExamResult student = row();
        student.setResultJson(RESULT_JSON.replace("\"max_score\":2", "\"max_score\":0.001"));
        try (XSSFWorkbook wb = workbook(new StudentReportArchiveBuilder(null, null, r -> tmp)
                .build("PE_PRM393_FA26", List.of(student)))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            int parent = findRow(sheet, "App khởi động");
            assertEquals("0.001/0.001", sheet.getRow(parent).getCell(4).toString());
            int child = findRow(sheet, "  Thêm sinh viên");
            assertEquals("1.1 · Passed", sheet.getRow(child).getCell(2).toString());
            assertEquals("child (unresolved)", sheet.getRow(findRow(sheet, "Xoá sinh viên")).getCell(2).toString());
        }
    }

    private static String sheetText(XSSFSheet sheet) {
        StringBuilder sb = new StringBuilder();
        sheet.forEach(row -> row.forEach(cell -> sb.append(cell.toString()).append('\n')));
        return sb.toString();
    }

    private static int findRow(XSSFSheet sheet, String text) {
        for (var line : sheet) for (var cell : line) {
            if (cell.toString().equals(text)) return line.getRowNum();
        }
        throw new AssertionError("Không thấy: " + text);
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

    /**
     * Bảng điểm gộp ô Nhóm theo chiều DỌC: nhóm CRUD có ba luồng thì chữ CRUD nằm giữa cả ba
     * dòng. Luồng chưa xếp nhóm thì không gộp và không tô nền — nền kem nghĩa là "có nhóm".
     */
    @Test
    void gopONhomTheoChieuDocVaToNenChoNhom(@TempDir Path tmp) throws Exception {
        String json = """
                {"schema_version":"2",
                 "student":{"id":"HE180037"},
                 "grading_result":{"score":10,"passed_tests":4,"failed_tests":0,"total_tests":4},
                 "test_cases":[
                   {"test_id":"A1","name":"a","status":"passed","max_score":4.9666666666666666,
                    "group_id":"CRUD","scenario_code":"CRUD_THEM","scenario_name":"thêm chi tiêu"},
                   {"test_id":"A2","name":"b","status":"passed","max_score":4.9666666666666666,
                    "group_id":"CRUD","scenario_code":"CRUD_THEM","scenario_name":"thêm chi tiêu"},
                   {"test_id":"A3","name":"c","status":"passed","max_score":4.9666666666666666,
                    "group_id":"CRUD","scenario_code":"CRUD_THEM","scenario_name":"thêm chi tiêu"},
                   {"test_id":"B1","name":"d","status":"passed","max_score":2,
                    "group_id":"CRUD","scenario_code":"CRUD_XOA","scenario_name":"xóa chi tiêu"},
                   {"test_id":"ARCH_1","name":"Tách Model thành file riêng","status":"passed",
                    "max_score":6,"group_id":"Architecture"},
                   {"test_id":"C1","name":"e","status":"passed","max_score":3,
                    "scenario_code":"SAP_XEP","scenario_name":"sắp xếp"}
                 ]}
                """;
        ExamResult student = row();
        student.setExamId("PE_X");
        student.setScore(10f);
        student.setResultJson(json);

        Map<String, byte[]> entries = readAll(new StudentReportArchiveBuilder(null, r -> null, r -> tmp)
                .build("PE_X", List.of(student)));
        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(entries.get("Result_of_PE_X/HE180037/HE180037.xlsx")))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            int head = findRow(sheet, "ĐIỂM THEO NHÓM TIÊU CHÍ") + 1;
            int dau = head + 1;   // dòng luồng đầu tiên

            // CRUD gồm hai luồng ⇒ ô nhóm gộp đúng hai dòng.
            assertTrue(sheet.getMergedRegions().stream().anyMatch(v ->
                            v.getFirstRow() == dau && v.getLastRow() == dau + 1
                                    && v.getFirstColumn() == 1 && v.getLastColumn() == 1),
                    "ô Nhóm phải gộp dọc hết các luồng của nhóm");
            assertEquals("CRUD", sheet.getRow(dau).getCell(1).toString());
            assertEquals(13, sheet.getRow(dau).getCell(1).getCellStyle().getFont().getFontHeightInPoints());
            assertEquals("FFFFF2CC",
                    sheet.getRow(dau).getCell(1).getCellStyle().getFillForegroundXSSFColor().getARGBHex());

            // Luồng không nhóm: dồn xuống cuối, ghi "—", KHÔNG tô nền kem.
            var khongNhom = sheet.getRow(dau + 3);
            assertEquals("—", khongNhom.getCell(1).toString());
            assertEquals("sắp xếp", khongNhom.getCell(2).toString());
            assertNull(khongNhom.getCell(1).getCellStyle().getFillForegroundXSSFColor());
            assertTrue(sheet.getMergedRegions().stream().noneMatch(v ->
                    v.getFirstRow() == dau + 3 && v.getFirstColumn() == 1));

            // 4,9666…×3 cộng dồn ra 14.899999999999999 — bảng điểm không được in rác đó.
            assertEquals("14.9/14.9", sheet.getRow(dau).getCell(4).toString());
        }
    }
}
