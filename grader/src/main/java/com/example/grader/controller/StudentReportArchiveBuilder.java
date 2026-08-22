package com.example.grader.controller;

import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingOutcome;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * HỒ SƠ KẾT QUẢ PHÁT CHO SINH VIÊN — mỗi em một thư mục, giải nén ra:
 *
 * <pre>
 * Result_of_&lt;đề&gt;/
 * └── HE180037/
 *     ├── HE180037.json      kết quả đầy đủ (đúng bản "Xuất JSON")
 *     ├── HE180037.xlsx      bảng điểm theo NHÓM tiêu chí + chi tiết + ảnh màn hình đối chứng
 *     └── logs/grading.log   bằng chứng chấm: chẩn đoán, SHA đối chứng, danh sách testcase hỏng
 * </pre>
 *
 * <p>feedback.txt (nhận xét bot NLP) đã bỏ khỏi hồ sơ 2026-08-22 — hệ thống chỉ còn tập trung
 * vào chấm điểm và bằng chứng phúc khảo; nhận xét vẫn xuất riêng được ở nút "Sinh feedback".
 *
 * <p>Ảnh trong .xlsx: mỗi luồng thao tác một cặp <b>ảnh mẫu (Golden)</b> — <b>ảnh bài làm</b>,
 * chụp tại cùng điểm dừng, cùng container Docker, cùng font. Sinh viên phúc khảo nhìn thẳng
 * vào thứ máy đã nhìn.
 */
final class StudentReportArchiveBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/M/yyyy").withZone(ZoneId.systemDefault());

    /** Chuẩn hoá JSON trước khi ghi (controller đưa {@code pretty} của nó vào để dùng chung). */
    private final UnaryOperator<String> jsonNormalizer;
    /** fixtures/screens của bộ đề — ảnh chuẩn theo execution_code; null/không tồn tại = bỏ qua. */
    private final Path goldenScreensDir;
    /** Thư mục ảnh bằng chứng của TỪNG bài (submissions/&lt;đề&gt;/&lt;batch&gt;/_evidence/&lt;SV&gt;). */
    private final Function<ExamResult, Path> evidenceDirFor;

    StudentReportArchiveBuilder(UnaryOperator<String> jsonNormalizer,
                                Path goldenScreensDir,
                                Function<ExamResult, Path> evidenceDirFor) {
        this.jsonNormalizer = jsonNormalizer;
        this.goldenScreensDir = goldenScreensDir;
        this.evidenceDirFor = evidenceDirFor == null ? row -> null : evidenceDirFor;
    }

    byte[] build(String examId, List<ExamResult> rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String root = "Result_of_" + safe(examId) + "/";
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            dir(zip, root);
            for (ExamResult row : rows) {
                String json = row.getResultJson();
                if (json == null || json.isBlank()) continue;
                JsonNode result;
                try {
                    result = MAPPER.readTree(json);
                } catch (Exception broken) {
                    continue;   // JSON hỏng: bỏ qua em này thay vì hỏng cả gói
                }
                String sid = safe(row.getStudentId() == null ? "student" : row.getStudentId());
                String home = root + sid + "/";
                String studentJson = jsonNormalizer.apply(json);
                dir(zip, home);
                file(zip, home + sid + ".json", studentJson);
                binary(zip, home + sid + ".xlsx", studentXlsx(row, result));
                dir(zip, home + "logs/");
                file(zip, home + "logs/grading.log", gradingLog(examId, row, result, sha256(studentJson)));
            }
        }
        return bytes.toByteArray();
    }

    // ── .xlsx: điểm theo nhóm + chi tiết + ảnh đối chứng ────────────
    private byte[] studentXlsx(ExamResult row, JsonNode result) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Ket qua");
            int[] widths = {5, 26, 40, 12, 12, 60};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

            Styles st = new Styles(wb);
            int r = 0;

            // ── Khối tóm tắt ─────────────────────────────────────
            r = title(sheet, st, r, "HỒ SƠ KẾT QUẢ — " + nvl(row.getStudentId()));
            int[] manual = manualPassCounts(row.getManualJson());
            JsonNode grading = result.path("grading_result");
            boolean edited = row.getManualScore() != null;
            r = info(sheet, st, r, "Mã sinh viên", nvl(row.getStudentId()));
            if (row.getStudentName() != null && !row.getStudentName().isBlank()) {
                r = info(sheet, st, r, "Họ tên", row.getStudentName());
            }
            r = info(sheet, st, r, "Bộ đề", nvl(row.getExamId()));
            r = info(sheet, st, r, "Điểm", edited
                    ? fmt(row.getManualScore()) + " (máy chấm: " + fmt(row.getScore()) + ")"
                    : fmt(row.getScore()));
            r = info(sheet, st, r, "Tiêu chí đạt", manual != null && edited
                    ? manual[0] + "/" + manual[1] + " (chấm tay)"
                    : grading.path("passed_tests").asInt(0) + "/" + grading.path("total_tests").asInt(0));
            r = info(sheet, st, r, "Thời gian chấm",
                    row.getUpdatedAt() == null ? "" : TIME.format(row.getUpdatedAt()));
            r = info(sheet, st, r, "SHA-256 bài nộp",
                    row.getSubmissionHash() == null ? "(không ghi được)" : row.getSubmissionHash());
            r++;

            // ── Điểm theo NHÓM — đúng hình phân bổ điểm của hệ thống ──
            // Điểm lẻ của bài nổi lên ở cấp nhóm (đạt 3/4 thành phần = 15/20), nên đây là
            // bảng sinh viên cần đọc TRƯỚC: nó nói mất điểm Ở ĐÂU trước khi nói vì sao.
            Map<String, double[]> groups = new LinkedHashMap<>();   // {đạt, tổng, điểm, tối đa}
            Map<String, String> groupLabel = new LinkedHashMap<>();
            for (JsonNode tc : result.path("test_cases")) {
                String key = tc.path("group_id").asText("");
                String label = tc.path("group_name").asText("");
                if (key.isBlank()) key = label.isBlank() ? "KHAC" : label;
                if (label.isBlank()) label = "Tiêu chí khác";
                groupLabel.putIfAbsent(key, label);
                double[] g = groups.computeIfAbsent(key, k -> new double[4]);
                boolean passed = "passed".equals(tc.path("status").asText(""));
                double max = tc.path("max_score").asDouble(0);
                g[1]++;
                g[3] += max;
                if (passed) { g[0]++; g[2] += max; }
            }
            r = header(sheet, st, r, "ĐIỂM THEO NHÓM TIÊU CHÍ",
                    new String[]{"", "Nhóm", "", "Đạt", "Điểm", ""});
            double earned = 0, total = 0;
            for (Map.Entry<String, double[]> e : groups.entrySet()) {
                double[] g = e.getValue();
                earned += g[2]; total += g[3];
                XSSFRow line = sheet.createRow(r++);
                cell(line, 1, groupLabel.get(e.getKey()), st.plain);
                sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 1, 2));
                cell(line, 3, (int) g[0] + "/" + (int) g[1], st.center);
                cell(line, 4, num(g[2]) + "/" + num(g[3]), st.center);
            }
            XSSFRow sum = sheet.createRow(r++);
            cell(sum, 1, "TỔNG", st.boldPlain);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 1, 2));
            cell(sum, 4, num(earned) + "/" + num(total), st.boldCenter);
            r++;

            // ── Chi tiết từng tiêu chí ───────────────────────────
            r = header(sheet, st, r, "CHI TIẾT TIÊU CHÍ",
                    new String[]{"STT", "Nhóm", "Tiêu chí", "Trạng thái", "Điểm", "Quan sát được"});
            int idx = 0;
            for (JsonNode tc : result.path("test_cases")) {
                idx++;
                String status = tc.path("status").asText("");
                XSSFCellStyle tone = switch (status) {
                    case "passed" -> st.pass;
                    case "failed" -> st.fail;
                    default -> st.notRun;
                };
                String label = switch (status) {
                    case "passed" -> "Đạt";
                    case "failed" -> "Trượt";
                    default -> "Chưa chấm";
                };
                String max = num(tc.path("max_score").asDouble(0));
                XSSFRow line = sheet.createRow(r++);
                cell(line, 0, String.valueOf(idx), tone);
                cell(line, 1, tc.path("group_name").asText(""), tone);
                cell(line, 2, tc.path("name").asText(tc.path("test_id").asText("")), tone);
                cell(line, 3, label, tone);
                cell(line, 4, ("passed".equals(status) ? max : "0") + "/" + max, tone);
                cell(line, 5, "passed".equals(status) ? "—" : tc.path("actual").asText(""), tone);
            }
            r++;

            // ── Ảnh đối chứng: mẫu (Golden) và bài làm, cùng điểm dừng ──
            Path evidence = evidenceDirFor.apply(row);
            List<Path> shots = listPngs(evidence);
            if (!shots.isEmpty()) {
                r = title(sheet, st, r, "ẢNH MÀN HÌNH — ĐỐI CHỨNG PHÚC KHẢO");
                XSSFRow note = sheet.createRow(r++);
                cell(note, 1, "Chụp tự động tại cuối mỗi luồng thao tác, trong cùng môi trường chấm.", st.plain);
                sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 1, 5));
                Drawing<?> drawing = sheet.createDrawingPatriarch();
                for (Path shot : shots) {
                    String exec = shot.getFileName().toString().replaceFirst("\\.png$", "");
                    r++;
                    XSSFRow cap = sheet.createRow(r++);
                    cell(cap, 1, "Luồng: " + exec, st.boldPlain);
                    XSSFRow sub = sheet.createRow(r++);
                    Path golden = goldenScreensDir == null ? null : goldenScreensDir.resolve(exec + ".png");
                    boolean hasGolden = golden != null && Files.isRegularFile(golden);
                    if (hasGolden) cell(sub, 1, "Ảnh mẫu (Golden)", st.plain);
                    cell(sub, hasGolden ? 3 : 1, "Bài làm", st.plain);
                    int span = 0;
                    if (hasGolden) span = Math.max(span, picture(wb, drawing, golden, 1, r));
                    span = Math.max(span, picture(wb, drawing, shot, hasGolden ? 3 : 1, r));
                    r += Math.max(span, 1) + 1;
                }
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Chèn 1 ảnh PNG tại (col,row), thu về ~45% cỡ thật; trả số HÀNG ảnh chiếm. */
    private int picture(XSSFWorkbook wb, Drawing<?> drawing, Path png, int col, int row) throws Exception {
        byte[] bytes = Files.readAllBytes(png);
        int index = wb.addPicture(bytes, Workbook.PICTURE_TYPE_PNG);
        ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
        anchor.setCol1(col);
        anchor.setRow1(row);
        Picture pic = drawing.createPicture(anchor, index);
        pic.resize(0.45);
        // Hàng mặc định 15pt = 20px; ảnh 844px × 0.45 ≈ 380px ≈ 19 hàng.
        double heightPx = pic.getImageDimension().getHeight();
        return (int) Math.ceil(heightPx / 20.0);
    }

    private List<Path> listPngs(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".png")).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Khối dựng sheet ─────────────────────────────────────────────
    private int title(XSSFSheet sheet, Styles st, int r, String text) {
        XSSFRow row = sheet.createRow(r);
        cell(row, 0, text, st.title);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 5));
        return r + 1;
    }

    private int info(XSSFSheet sheet, Styles st, int r, String label, String value) {
        XSSFRow row = sheet.createRow(r);
        cell(row, 0, label, st.infoLabel);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 1));
        cell(row, 2, value, st.plain);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 2, 5));
        return r + 1;
    }

    private int header(XSSFSheet sheet, Styles st, int r, String section, String[] columns) {
        XSSFRow cap = sheet.createRow(r++);
        cell(cap, 0, section, st.section);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 5));
        XSSFRow head = sheet.createRow(r++);
        for (int i = 0; i < columns.length; i++) {
            if (!columns[i].isBlank()) cell(head, i, columns[i], st.head);
        }
        return r;
    }

    private void cell(XSSFRow row, int col, String value, XSSFCellStyle style) {
        XSSFCell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        if (style != null) c.setCellStyle(style);
    }

    /** Bộ style dùng chung một workbook — POI giới hạn số style, không tạo mới theo ô. */
    private static final class Styles {
        final XSSFCellStyle title, section, head, infoLabel, plain, center,
                boldPlain, boldCenter, pass, fail, notRun;

        Styles(XSSFWorkbook wb) {
            title = base(wb, true, 13, null, HorizontalAlignment.LEFT);
            section = base(wb, true, 11, rgb(0xEE, 0xF2, 0xFF), HorizontalAlignment.LEFT);
            head = base(wb, true, 11, rgb(0xF1, 0xF5, 0xF9), HorizontalAlignment.CENTER);
            infoLabel = base(wb, true, 11, rgb(0xF8, 0xFA, 0xFC), HorizontalAlignment.LEFT);
            plain = base(wb, false, 11, null, HorizontalAlignment.LEFT);
            center = base(wb, false, 11, null, HorizontalAlignment.CENTER);
            boldPlain = base(wb, true, 11, null, HorizontalAlignment.LEFT);
            boldCenter = base(wb, true, 11, null, HorizontalAlignment.CENTER);
            pass = base(wb, false, 11, rgb(0xDC, 0xFC, 0xE7), HorizontalAlignment.LEFT);
            fail = base(wb, false, 11, rgb(0xFE, 0xE2, 0xE2), HorizontalAlignment.LEFT);
            notRun = base(wb, false, 11, rgb(0xF1, 0xF5, 0xF9), HorizontalAlignment.LEFT);
        }

        private static XSSFColor rgb(int r, int g, int b) {
            return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
        }

        private static XSSFCellStyle base(XSSFWorkbook wb, boolean bold, int size,
                                          XSSFColor fill, HorizontalAlignment align) {
            XSSFCellStyle style = wb.createCellStyle();
            XSSFFont font = wb.createFont();
            font.setBold(bold);
            font.setFontHeightInPoints((short) size);
            style.setFont(font);
            style.setAlignment(align);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setWrapText(true);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            if (fill != null) {
                style.setFillForegroundColor(fill);
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            return style;
        }
    }

    /**
     * Nội dung feedback.txt của 1 SV — vẫn dùng cho nút "Sinh feedback" (ZIP .txt theo MSSV);
     * hồ sơ phúc khảo KHÔNG còn kèm file này.
     */
    static String renderFeedbackText(ExamResult row) {
        String cached = row.getFeedbackJson();
        if (cached == null || cached.isBlank()) return "";
        try {
            JsonNode fb = MAPPER.readTree(cached);
            StringBuilder sb = new StringBuilder();
            sb.append("NHẬN XÉT BÀI LÀM — ").append(row.getStudentId()).append("\n");
            String summary = fb.path("scoreSummary").asText("");
            if (!summary.isBlank()) sb.append("Điểm: ").append(summary).append("\n");
            sb.append("\n").append(fb.path("feedbackText").asText("")).append("\n");
            if (fb.path("teacherReviewRequired").asBoolean(false)) {
                sb.append("\n[Bot khuyến nghị giảng viên xem lại bài này]\n");
                for (JsonNode reason : fb.path("reviewReasons")) {
                    sb.append("  - ").append(reason.asText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception broken) {
            return cached;   // JSON lạ thì trả nguyên văn còn hơn nuốt mất
        }
    }

    // ── logs/grading.log ────────────────────────────────────────────
    private String gradingLog(String examId, ExamResult row, JsonNode result, String resultJsonHash) {
        JsonNode grading = result.path("grading_result");
        StringBuilder sb = new StringBuilder();
        sb.append("=== GRADING LOG ===\n");
        sb.append("Đề: ").append(examId)
          .append(" | SV: ").append(row.getStudentId())
          .append(" | Batch: ").append(nvl(row.getBatchId())).append("\n");
        sb.append("Thời gian chấm: ")
          .append(row.getUpdatedAt() == null ? "?" : TIME.format(row.getUpdatedAt())).append("\n");
        sb.append("Trạng thái: ").append(row.getStatus())
          .append(" (").append(GradingOutcome.of(row.getStatus())).append(")")
          .append(" | Điểm tự động: ").append(fmt(row.getScore()));
        if (row.getManualScore() != null) sb.append(" | Điểm chấm tay: ").append(fmt(row.getManualScore()));
        if (row.getPreviousScore() != null) sb.append(" | Điểm lần chấm trước: ").append(fmt(row.getPreviousScore()));
        sb.append("\n");
        sb.append("Engine: ").append(grading.path("engine_version").asText("?"))
          .append(" | Schema: ").append(result.path("schema_version").asText("1")).append("\n");

        // ĐỐI CHỨNG — hai chuỗi này là thứ duy nhất trong hồ sơ chứng minh được "bài nào đã được
        // chấm" và "file kết quả có bị sửa sau khi phát hay không".
        sb.append("\n--- Đối chứng ---\n");
        sb.append("SHA-256 bài nộp (.zip lúc chấm): ")
          .append(row.getSubmissionHash() == null ? "(không ghi được)" : row.getSubmissionHash()).append("\n");
        sb.append("SHA-256 file kết quả kèm theo:   ").append(resultJsonHash).append("\n");
        sb.append("Đối chiếu: băm lại file .zip bài nộp lưu ở kho gốc, khớp chuỗi trên nghĩa là\n")
          .append("đúng bản đã được chấm. Băm lại file .json cùng thư mục để kiểm tra toàn vẹn.\n");

        if (row.getDiagnosticCode() != null && !row.getDiagnosticCode().isBlank()) {
            sb.append("Chẩn đoán: [").append(row.getDiagnosticCode())
              .append("][").append(nvl(row.getDiagnosticOrigin()))
              .append("][").append(nvl(row.getDiagnosticStage())).append("]\n");
        }
        if (row.getErrorLog() != null && !row.getErrorLog().isBlank()) {
            sb.append("Error log: ").append(row.getErrorLog()).append("\n");
        }
        String runnerError = grading.path("runner_error").asText("");
        if (!runnerError.isBlank()) sb.append("Runner error: ").append(runnerError).append("\n");

        sb.append("\n--- Testcase không đạt ---\n");
        boolean any = false;
        for (JsonNode tc : result.path("test_cases")) {
            String status = tc.path("status").asText("");
            if ("passed".equals(status)) continue;
            any = true;
            sb.append("[").append(status.toUpperCase()).append("] ")
              .append(tc.path("test_id").asText("?"));
            String code = tc.path("error_code").asText("");
            if (!code.isBlank()) sb.append(" (").append(code).append(")");
            String actual = tc.path("actual").asText("");
            if (!actual.isBlank()) sb.append(": ").append(actual);
            sb.append("\n");
        }
        if (!any) sb.append("(không có — tất cả testcase đều đạt)\n");
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────
    /** Đếm tiêu chí đạt TRỌN điểm trong manual_json → {đạt, tổng}; null nếu chưa chấm tay. */
    static int[] manualPassCounts(String manualJson) {
        if (manualJson == null || manualJson.isBlank()) return null;
        try {
            JsonNode criteria = MAPPER.readTree(manualJson).path("criteria");
            if (!criteria.isArray() || criteria.isEmpty()) return null;
            int pass = 0;
            for (JsonNode c : criteria) {
                double max = c.path("maxPoints").asDouble(0);
                boolean ok = max > 0
                        ? c.path("points").asDouble(0) >= max - 1e-6
                        : c.path("passed").asBoolean(false);
                if (ok) pass++;
            }
            return new int[]{pass, criteria.size()};
        } catch (Exception e) {
            return null;
        }
    }

    private void dir(ZipOutputStream zip, String name) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.closeEntry();
    }

    private void file(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void binary(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String safe(String value) {
        String s = value == null ? "x" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isBlank() ? "x" : s;
    }

    private static String fmt(Float v) {
        return v == null ? "—" : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String nvl(String v) {
        return v == null ? "?" : v;
    }

    /** 15.0 → "15", 3.75 giữ nguyên — trọng số hay mang .0 thừa. */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
                : String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }

    /** SHA-256 dạng hex của nội dung file kết quả — băm ĐÚNG chuỗi được ghi vào zip. */
    private static String sha256(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "(không tính được)";
        }
    }
}
