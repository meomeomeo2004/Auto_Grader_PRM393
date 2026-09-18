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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * HỒ SƠ KẾT QUẢ PHÁT CHO SINH VIÊN — mỗi em một thư mục, giải nén ra:
 *
 * <pre>
 * Result_of_&lt;đề&gt;/
 * └── HE180037/
 *     ├── HE180037.xlsx      bảng điểm theo NHÓM tiêu chí + chi tiết + ảnh màn hình đối chứng
 *     └── logs/grading.log   bằng chứng chấm: chẩn đoán, SHA đối chứng, danh sách testcase hỏng
 * </pre>
 *
 * <p>Ảnh trong .xlsx: mỗi luồng thao tác một cặp <b>ảnh mẫu (Golden)</b> — <b>ảnh bài làm</b>,
 * chụp tại cùng điểm dừng, cùng container Docker, cùng font. Sinh viên phúc khảo nhìn thẳng
 * vào thứ máy đã nhìn.
 */
final class StudentReportArchiveBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/M/yyyy").withZone(ZoneId.systemDefault());

    /** fixtures/screens của bộ đề — ảnh chuẩn theo execution_code; null/không tồn tại = bỏ qua. */
    private final Path goldenScreensDir;
    /** Thư mục ảnh bằng chứng của TỪNG bài (submissions/&lt;đề&gt;/&lt;batch&gt;/_evidence/&lt;SV&gt;). */
    private final Function<ExamResult, Path> evidenceDirFor;
    /** Chỉ đọc snapshot của lô đã chấm để không gán tiên quyết từ phiên bản đề mới. */
    private final Function<ExamResult, Path> testcaseDirFor;

    StudentReportArchiveBuilder(Path goldenScreensDir,
                                Function<ExamResult, Path> evidenceDirFor,
                                Function<ExamResult, Path> testcaseDirFor) {
        this.goldenScreensDir = goldenScreensDir;
        this.evidenceDirFor = evidenceDirFor == null ? row -> null : evidenceDirFor;
        this.testcaseDirFor = testcaseDirFor == null ? row -> null : testcaseDirFor;
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
                dir(zip, home);
                binary(zip, home + sid + ".xlsx", studentXlsx(row, result));
                dir(zip, home + "logs/");
                file(zip, home + "logs/grading.log", gradingLog(examId, row, result));
            }
        }
        return bytes.toByteArray();
    }

    // ── .xlsx: điểm theo nhóm + chi tiết + ảnh đối chứng ────────────
    private byte[] studentXlsx(ExamResult row, JsonNode result) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Ket qua");
            sheet.setDefaultRowHeightInPoints(14.5f);
            int[] widths = {5, 26, 40, 12, 12, 60};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

            Styles st = new Styles(wb);
            int r = 0;

            // ── Khối tóm tắt ─────────────────────────────────────
            r = title(sheet, r, "HỒ SƠ KẾT QUẢ — " + nvl(row.getStudentId()), 2, st.profileTitle);
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
            r++;

            // ── Điểm theo NHÓM — đúng hình phân bổ điểm của hệ thống ──
            // Điểm lẻ của bài nổi lên ở cấp nhóm (đạt 3/4 thành phần = 15/20), nên đây là
            // bảng sinh viên cần đọc TRƯỚC: nó nói mất điểm Ở ĐÂU trước khi nói vì sao.
            Map<String, double[]> groups = new LinkedHashMap<>();   // {đạt, tổng, điểm, tối đa}
            Map<String, String> groupLabel = new LinkedHashMap<>();
            for (JsonNode tc : result.path("test_cases")) {
                String key = groupKey(tc);
                String label = tc.path("group_name").asText("");
                if (label.isBlank()) label = "Tiêu chí khác";
                groupLabel.putIfAbsent(key, label);
                double[] g = groups.computeIfAbsent(key, k -> new double[4]);
                boolean passed = "passed".equals(tc.path("status").asText(""));
                double max = tc.path("max_score").asDouble(0);
                g[1]++;
                g[3] += max;
                if (passed) { g[0]++; g[2] += max; }
            }
            r = summaryHeader(sheet, st, r);
            double earned = 0, total = 0;
            int groupNumber = 0;
            for (Map.Entry<String, double[]> e : groups.entrySet()) {
                double[] g = e.getValue();
                earned += g[2]; total += g[3];
                XSSFRow line = sheet.createRow(r++);
                cell(line, 0, "", st.summaryIndex);
                line.getCell(0).setCellValue(++groupNumber);
                cell(line, 1, groupLabel.get(e.getKey()), st.referencePlain);
                cell(line, 2, (int) g[0] + "/" + (int) g[1], st.center);
                cell(line, 3, num(g[2]) + "/" + num(g[3]), st.center);
            }
            XSSFRow sum = sheet.createRow(r++);
            cell(sum, 0, "", st.summaryIndex);
            cell(sum, 1, "TỔNG", st.summaryTotal);
            cell(sum, 2, num(earned) + "/" + num(total), st.boldCenter);
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 2, 3));
            r++;

            // ── Chi tiết từng tiêu chí ───────────────────────────
            int detailStart = r;
            r = header(sheet, st, r, "CHI TIẾT TIÊU CHÍ",
                    new String[]{"", "Check point", "Prerequisite", "Status", "Score", "Observation"});
            List<DetailCheckpoint> checkpoints = detailCheckpoints(row, result);
            Map<DetailCheckpoint, Integer> detailRows = new HashMap<>();
            groupNumber = 0;
            for (String group : groups.keySet()) {
                groupNumber++;
                XSSFRow band = sheet.createRow(r++);
                cell(band, 0, String.valueOf(groupNumber), st.section);
                cell(band, 1, groupLabel.get(group), st.section);
                sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 1, 5));
                List<DetailCheckpoint> ordered = new ArrayList<>();
                Set<DetailCheckpoint> visited = new HashSet<>();
                for (DetailCheckpoint checkpoint : checkpoints) {
                    if (groupKey(checkpoint.result).equals(group)) orderCheckpoint(checkpoint, group, visited, ordered);
                }
                int idx = 0;
                for (DetailCheckpoint checkpoint : ordered) {
                    JsonNode tc = checkpoint.result;
                    checkpoint.reference = groupNumber + "." + (++idx);
                    String status = tc.path("status").asText("");
                    XSSFCellStyle tone = switch (status) {
                        case "passed" -> st.pass;
                        case "failed" -> st.fail;
                        default -> st.notRun;
                    };
                    String max = num(tc.path("max_score").asDouble(0));
                    XSSFRow line = sheet.createRow(r++);
                    detailRows.put(checkpoint, line.getRowNum());
                    cell(line, 0, checkpoint.reference, tone);
                    cell(line, 1, "  ".repeat(checkpointDepth(checkpoint))
                            + tc.path("name").asText(tc.path("test_id").asText("")), tone);
                    cell(line, 2, "", tone);
                    cell(line, 3, statusLabel(status), tone);
                    cell(line, 4, ("passed".equals(status) ? max : "0") + "/" + max, tone);
                    cell(line, 5, "passed".equals(status) ? "—" : tc.path("actual").asText(""), tone);
                }
            }
            // Điền sau khi biết mọi vị trí, kể cả tiên quyết nằm ở nhóm phía dưới.
            for (DetailCheckpoint checkpoint : checkpoints) {
                if (checkpoint.requires.isBlank()) continue;
                XSSFCell target = sheet.getRow(detailRows.get(checkpoint)).getCell(2);
                if (checkpoint.parent == null) {
                    target.setCellValue(checkpoint.requires + " (unresolved)");
                } else {
                    DetailCheckpoint parent = checkpoint.parent;
                    target.setCellValue(parent.reference + " · "
                            + statusLabel(parent.result.path("status").asText(""))
                            + (groupKey(parent.result).equals(groupKey(checkpoint.result)) ? ""
                            : " · " + groupLabel.get(groupKey(parent.result)))
                            + (hasCycle(checkpoint) ? " (cycle)" : ""));
                    var link = wb.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.DOCUMENT);
                    link.setAddress("'Ket qua'!B" + (detailRows.get(parent) + 1));
                    target.setHyperlink(link);
                }
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

            fitWrappedRows(sheet, detailStart);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Chiều cao ảnh bằng chứng khi hiển thị trong sheet, tính bằng điểm ảnh. */
    private static final double ANH_CAO_PX = 380.0;

    /**
     * Chèn 1 ảnh PNG tại (col,row), thu về ĐÚNG {@link #ANH_CAO_PX} bất kể ảnh gốc to nhỏ;
     * trả số HÀNG ảnh chiếm (hàng mặc định 15pt = 20px).
     *
     * Vì sao không thu theo tỉ lệ cố định: bản cũ dùng {@code resize(0.45)} rồi tính số hàng
     * từ chiều cao ảnh GỐC, tức là ngầm giả định ảnh luôn cao 844px. Khung chấm nay là máy
     * Android 412×915 dp mật độ 2.625 nên ảnh cao 2402px — tỉ lệ cố định sẽ cho ra ảnh to
     * gấp gần ba và số hàng sai bét. Neo theo chiều cao hiển thị thì đổi khung máy bao nhiêu
     * lần nữa cũng không hỏng.
     */
    private int picture(XSSFWorkbook wb, Drawing<?> drawing, Path png, int col, int row) throws Exception {
        byte[] bytes = Files.readAllBytes(png);
        int index = wb.addPicture(bytes, Workbook.PICTURE_TYPE_PNG);
        ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
        anchor.setCol1(col);
        anchor.setRow1(row);
        Picture pic = drawing.createPicture(anchor, index);
        double caoGoc = pic.getImageDimension().getHeight();
        pic.resize(caoGoc > 0 ? ANH_CAO_PX / caoGoc : 0.45);
        return (int) Math.ceil(ANH_CAO_PX / 20.0);
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
        return title(sheet, r, text, 5, st.title);
    }

    private int title(XSSFSheet sheet, int r, String text, int lastColumn, XSSFCellStyle style) {
        XSSFRow row = sheet.createRow(r);
        cell(row, 0, text, style);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, lastColumn));
        return r + 1;
    }

    private int info(XSSFSheet sheet, Styles st, int r, String label, String value) {
        XSSFRow row = sheet.createRow(r);
        cell(row, 0, label, st.infoLabel);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 1));
        cell(row, 2, value, st.referencePlain);
        return r + 1;
    }

    private int summaryHeader(XSSFSheet sheet, Styles st, int r) {
        r = title(sheet, r, "ĐIỂM THEO NHÓM TIÊU CHÍ", 2, st.summarySection);
        cell(sheet.getRow(r - 1), 3, "", st.summaryTail);
        XSSFRow head = sheet.createRow(r++);
        String[] columns = {"STT", "Nhóm", "Check point", "Điểm"};
        for (int col = 0; col < columns.length; col++) {
            cell(head, col, columns[col], col == 0 ? st.summaryIndexHead : st.summaryHead);
        }
        return r;
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

    /** Excel không tự giãn hàng của file sinh bằng POI, nhất là ô đã gộp. */
    private static void fitWrappedRows(XSSFSheet sheet, int firstRow) {
        for (var row : sheet) {
            if (row.getRowNum() < firstRow) continue;
            int lines = 1;
            for (var cell : row) {
                double width = sheet.getColumnWidth(cell.getColumnIndex()) / 256.0;
                for (CellRangeAddress merged : sheet.getMergedRegions()) {
                    if (merged.getFirstRow() == row.getRowNum()
                            && merged.getFirstColumn() == cell.getColumnIndex()) {
                        width = 0;
                        for (int col = merged.getFirstColumn(); col <= merged.getLastColumn(); col++) {
                            width += sheet.getColumnWidth(col) / 256.0;
                        }
                        break;
                    }
                }
                // Chừa khoảng cho viền và chữ rộng; ưu tiên đọc hết hơn tiết kiệm một hàng.
                int capacity = Math.max(1, (int) (width * 0.85) - 1);
                int needed = 0;
                for (String paragraph : cell.toString().split("\\R", -1)) {
                    int used = 0;
                    needed++;
                    for (String word : paragraph.split("\\s+")) {
                        if (used > 0 && used + word.length() + 1 > capacity) {
                            needed++;
                            used = 0;
                        }
                        needed += Math.max(0, (word.length() - 1) / capacity);
                        used += word.length() + (used > 0 ? 1 : 0);
                    }
                }
                lines = Math.max(lines, needed);
            }
            row.setHeightInPoints(14.5f * lines);
        }
    }

    /** Bộ style dùng chung một workbook — POI giới hạn số style, không tạo mới theo ô. */
    private static final class Styles {
        final XSSFCellStyle title, section, head, infoLabel, plain, center,
                boldPlain, boldCenter, pass, fail, notRun, profileTitle,
                referencePlain, summarySection, summaryTail, summaryHead, summaryIndex, summaryIndexHead, summaryTotal;

        Styles(XSSFWorkbook wb) {
            title = base(wb, true, 13, null, HorizontalAlignment.LEFT);
            section = base(wb, true, 11, rgb(0xEE, 0xF2, 0xFF), HorizontalAlignment.LEFT);
            head = base(wb, true, 11, rgb(0xF1, 0xF5, 0xF9), HorizontalAlignment.CENTER);
            infoLabel = base(wb, true, 11, rgb(0xF8, 0xFA, 0xFC), HorizontalAlignment.CENTER);
            plain = base(wb, false, 11, null, HorizontalAlignment.LEFT);
            center = base(wb, false, 11, null, HorizontalAlignment.CENTER);
            boldPlain = base(wb, true, 11, null, HorizontalAlignment.LEFT);
            boldCenter = base(wb, true, 11, null, HorizontalAlignment.CENTER);
            pass = base(wb, false, 11, rgb(0xDC, 0xFC, 0xE7), HorizontalAlignment.LEFT);
            fail = base(wb, false, 11, rgb(0xFE, 0xE2, 0xE2), HorizontalAlignment.LEFT);
            notRun = base(wb, false, 11, rgb(0xF1, 0xF5, 0xF9), HorizontalAlignment.LEFT);
            // Phần đầu và bảng tổng hợp giữ bố cục QLCT, độc lập với khối checkpoint.
            profileTitle = base(wb, true, 13, null, HorizontalAlignment.CENTER);
            referencePlain = base(wb, false, 11, null, HorizontalAlignment.GENERAL);
            summarySection = base(wb, true, 11, rgb(0xEE, 0xF2, 0xFF), HorizontalAlignment.CENTER);
            summaryTail = base(wb, false, 11, rgb(0xEE, 0xF2, 0xFF), HorizontalAlignment.GENERAL);
            for (XSSFCellStyle caption : List.of(profileTitle, summarySection)) {
                caption.setBorderTop(BorderStyle.NONE);
                caption.setBorderRight(BorderStyle.NONE);
                caption.setBorderBottom(BorderStyle.NONE);
            }
            summaryTail.setBorderLeft(BorderStyle.NONE);
            summaryTail.setBorderTop(BorderStyle.NONE);
            summaryTail.setBorderRight(BorderStyle.NONE);
            summaryTail.setBorderBottom(BorderStyle.NONE);
            summaryHead = base(wb, true, 11, null, HorizontalAlignment.CENTER);
            summaryTotal = base(wb, true, 11, null, HorizontalAlignment.GENERAL);
            summaryIndex = summaryIndex(wb, false);
            summaryIndexHead = summaryIndex(wb, true);
        }

        private static XSSFCellStyle summaryIndex(XSSFWorkbook wb, boolean bold) {
            XSSFCellStyle style = base(wb, bold, 11, null, HorizontalAlignment.CENTER);
            XSSFFont font = wb.getFontAt(style.getFontIndex());
            font.setFontName("Aptos Narrow");
            font.setColor(bold ? org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex()
                    : org.apache.poi.ss.usermodel.IndexedColors.AUTOMATIC.getIndex());
            style.setVerticalAlignment(VerticalAlignment.BOTTOM);
            style.setWrapText(false);
            return style;
        }

        private static XSSFColor rgb(int r, int g, int b) {
            return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
        }

        private static XSSFCellStyle base(XSSFWorkbook wb, boolean bold, int size,
                                          XSSFColor fill, HorizontalAlignment align) {
            XSSFCellStyle style = wb.createCellStyle();
            XSSFFont font = wb.createFont();
            font.setFontName("Calibri");
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

    // ── logs/grading.log ────────────────────────────────────────────
    private String gradingLog(String examId, ExamResult row, JsonNode result) {
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

        // Giữ dấu vân tay bài gốc trong log để phục vụ đối chứng khi phúc khảo.
        sb.append("\n--- Đối chứng ---\n");
        sb.append("SHA-256 bài nộp (.zip lúc chấm): ")
          .append(row.getSubmissionHash() == null ? "(không ghi được)" : row.getSubmissionHash()).append("\n");
        sb.append("Đối chiếu: băm lại file .zip bài nộp lưu ở kho gốc, khớp chuỗi trên nghĩa là\n")
          .append("đúng bản đã được chấm.\n");

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

    /** Giữ cả điểm rất nhỏ; chỉ bỏ số 0 thừa thay vì làm tròn mất trọng số. */
    private static String num(double v) {
        return Double.isFinite(v) ? java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
                : String.valueOf(v);
    }

    private static String groupKey(JsonNode tc) {
        String id = tc.path("group_id").asText("");
        String label = tc.path("group_name").asText("");
        return id.isBlank() ? (label.isBlank() ? "KHAC" : label) : id;
    }

    private static String statusLabel(String status) {
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "passed", "pass" -> "Passed";
            case "failed", "fail" -> "Failed";
            case "not_run", "not run" -> "Not run";
            case "error" -> "Error";
            case "skipped" -> "Skipped";
            case "blocked" -> "Blocked";
            case "pending" -> "Pending";
            case "running" -> "Running";
            default -> status.isBlank() ? "Unknown" : status;
        };
    }

    private static final class DetailCheckpoint {
        final JsonNode result;
        String requires = "", reference;
        DetailCheckpoint parent;

        DetailCheckpoint(JsonNode result) { this.result = result; }
    }

    private List<DetailCheckpoint> detailCheckpoints(ExamResult row, JsonNode result) {
        List<DetailCheckpoint> checkpoints = new ArrayList<>();
        for (JsonNode tc : result.path("test_cases")) checkpoints.add(new DetailCheckpoint(tc));
        Path testcase = testcaseDirFor.apply(row);
        if (testcase == null) return checkpoints;
        Map<String, JsonNode> cases = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        try {
            JsonNode plan = MAPPER.readTree(Files.readString(testcase.resolve("behavior_plan.json"), StandardCharsets.UTF_8));
            for (JsonNode item : plan.path("cases")) {
                String id = planText(item, "test_id", "");
                if (!id.isBlank() && cases.putIfAbsent(id, item) != null) duplicateIds.add(id);
            }
        } catch (Exception unavailable) {
            return checkpoints;
        }
        for (DetailCheckpoint checkpoint : checkpoints) {
            String testId = planText(checkpoint.result, "test_id", "");
            JsonNode item = duplicateIds.contains(testId) ? null : cases.get(testId);
            if (item == null) continue;
            checkpoint.requires = planText(item.path("checkpoint"), "requires", "");
            if (checkpoint.requires.isBlank()) continue;
            List<DetailCheckpoint> matches = new ArrayList<>();
            for (DetailCheckpoint candidate : checkpoints) {
                String candidateId = planText(candidate.result, "test_id", "");
                JsonNode candidateCase = duplicateIds.contains(candidateId) ? null : cases.get(candidateId);
                if (candidateCase == null) continue;
                if (checkpoint.requires.equals(planText(candidateCase.path("checkpoint"), "id", candidateId))
                        && sameExecution(item, candidateCase)) matches.add(candidate);
            }
            // Trùng định danh thì không đoán; sinh viên vẫn thấy tên tiên quyết gốc.
            if (matches.size() == 1) checkpoint.parent = matches.get(0);
        }
        return checkpoints;
    }

    private static boolean sameExecution(JsonNode left, JsonNode right) {
        String execution = planText(left, "execution_code", planText(left, "scenario_code", ""));
        String otherExecution = planText(right, "execution_code", planText(right, "scenario_code", ""));
        return !execution.isEmpty() && execution.equals(otherExecution);
    }

    /** Cùng quy tắc _text của engine: chuỗi rỗng sau trim phải dùng giá trị dự phòng. */
    private static String planText(JsonNode item, String key, String fallback) {
        String value = item.path(key).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static void orderCheckpoint(DetailCheckpoint checkpoint, String group,
                                        Set<DetailCheckpoint> visited, List<DetailCheckpoint> ordered) {
        if (!visited.add(checkpoint)) return;
        if (checkpoint.parent != null && groupKey(checkpoint.parent.result).equals(group)) {
            orderCheckpoint(checkpoint.parent, group, visited, ordered);
        }
        ordered.add(checkpoint);
    }

    private static boolean hasCycle(DetailCheckpoint checkpoint) {
        Set<DetailCheckpoint> seen = new HashSet<>();
        for (DetailCheckpoint cursor = checkpoint; cursor != null; cursor = cursor.parent) {
            if (!seen.add(cursor)) return true;
        }
        return false;
    }

    private static int checkpointDepth(DetailCheckpoint checkpoint) {
        if (hasCycle(checkpoint)) return 0;
        int depth = 0;
        for (DetailCheckpoint parent = checkpoint.parent; parent != null; parent = parent.parent) {
            if (!groupKey(parent.result).equals(groupKey(checkpoint.result))) break;
            depth++;
        }
        return Math.min(depth, 6);
    }
}
