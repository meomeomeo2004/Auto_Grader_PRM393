package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.dto.ExamHistoryRow;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingStatus;
import com.example.grader.repository.ExamResultRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Cung cấp JSON kết quả đầy đủ cho lịch sử, năng lực và xuất dữ liệu. */
@Profile(Vai.NGUOI_CHAM)   // Kết quả chấm chỉ có ở bản người chấm.
@RestController
@RequestMapping("/api/results")
@CrossOrigin(origins = "*")
public class ResultController {

    /** JSON luôn khai báo UTF-8 để client không tự đoán theo mã ký tự của máy. */
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");

    @Autowired
    private ExamResultRepository resultRepo;

    @Autowired
    private com.example.grader.repository.ExamRepository examRepo;

    @Autowired
    private com.example.grader.service.ExamService examService;

    @org.springframework.beans.factory.annotation.Value("${grader.submissions-dir:submissions}")
    private String submissionsDir;

    private final ObjectMapper mapper = new ObjectMapper();

    // /search đã bỏ cùng ô tìm kiếm trên thanh tiêu đề — nó không có người gọi nào khác.

    /** Chi tiết 1 bài cho trang chấm tay: result_json (test_cases) + điểm tự động + điểm tay đã lưu. */
    @GetMapping("/{examId}/{studentId}/detail")
    public ResponseEntity<?> getDetail(@PathVariable String examId, @PathVariable String studentId) {
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy bài nộp"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentId", r.getStudentId());
        m.put("studentName", r.getStudentName());
        m.put("examId", r.getExamId());
        m.put("status", r.getStatus());
        m.put("autoScore", r.getScore());
        m.put("resultJson", normalizeJsonString(r.getResultJson())); // chuỗi JSON đầy đủ (chỉ có expected)
        m.put("details", r.getDetails());
        m.put("errorLog", r.getErrorLog());
        m.put("diagnosticCode", r.getDiagnosticCode());
        m.put("diagnosticOrigin", r.getDiagnosticOrigin());
        m.put("diagnosticStage", r.getDiagnosticStage());
        m.put("requiresManualReview", r.isRequiresManualReview());
        m.put("manualScore", r.getManualScore());
        m.put("manualJson", r.getManualJson());
        m.put("manualBy", r.getManualBy());
        m.put("manualAt", r.getManualAt());
        m.put("batchId", r.getBatchId());
        return ResponseEntity.ok(m);
    }

    /** Lưu điểm chấm TAY theo tiêu chí. Body: { score, criteria:[...], note }. */
    @PostMapping("/{examId}/{studentId}/manual")
    public ResponseEntity<?> saveManual(@PathVariable String examId, @PathVariable String studentId,
                                        @RequestBody Map<String, Object> body) {
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy bài nộp"));
        try {
            Object score = body.get("score");
            if (score != null) r.setManualScore(Float.parseFloat(score.toString()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("criteria", body.get("criteria"));
            payload.put("note", body.get("note"));
            r.setManualJson(mapper.writeValueAsString(payload));
            r.setManualBy(AppActor.DEFAULT);         // app không đăng nhập → tên người chấm cố định
            r.setManualAt(java.time.Instant.now());
            resultRepo.save(r);
            return ResponseEntity.ok(Map.of("ok", true, "manualScore",
                    r.getManualScore() == null ? 0f : r.getManualScore()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Lịch sử chấm theo ĐỀ: danh sách bài đã chấm (gọn nhẹ, KHÔNG kèm result_json lớn). */
    @GetMapping("/exam/{examId}")
    public ResponseEntity<?> getExamHistory(@PathVariable String examId) {
        List<ExamHistoryRow> rows = resultRepo.findHistoryRowsByExamIdAndMode(examId, "submit");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExamHistoryRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("studentId", r.studentId());
            m.put("studentName", r.studentName());
            m.put("score", r.score());
            m.put("manualScore", r.manualScore());
            m.put("previousScore", r.previousScore());
            m.put("status", r.status());
            // Kết luận cho người chấm (SCORED / SYSTEM_BLOCKED / STOPPED). Thiếu nó thì trang
            // Lịch sử phải tự suy lại từ status như trước — đúng thứ đang gỡ bỏ.
            m.put("outcome", r.outcome());
            m.put("batchId", r.batchId());
            m.put("submittedAt", r.submittedAt());
            m.put("updatedAt", r.updatedAt());
            m.put("gradingStartedAt", r.gradingStartedAt());
            m.put("gradingFinishedAt", r.gradingFinishedAt());
            m.put("details", r.details());     // JSON gon cua grader: soTestPass / tongSoTest
            m.put("errorLog", r.errorLog());
            m.put("diagnosticCode", r.diagnosticCode());
            m.put("diagnosticOrigin", r.diagnosticOrigin());
            m.put("diagnosticStage", r.diagnosticStage());
            m.put("requiresManualReview", r.requiresManualReview());
            m.put("hasJson", Boolean.TRUE.equals(r.hasJson()));
            // Số tiêu chí ĐẠT sau chấm tay — để trang Lịch sử hiện "pass mới" cạnh "pass cũ".
            // Đếm tại đây rồi bỏ manualJson, không phát hành nguyên văn breakdown ra API này.
            int[] manualPass = manualPassCounts(r.manualJson());
            m.put("manualPass", manualPass == null ? null : manualPass[0]);
            m.put("manualTotal", manualPass == null ? null : manualPass[1]);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Đếm tiêu chí đạt sau chấm tay — logic nằm ở {@link StudentReportArchiveBuilder} vì gói hồ sơ
     * cũng cần đúng con số này; hai bản chép tay kiểu gì cũng lệch nhau.
     */
    private int[] manualPassCounts(String manualJson) {
        return StudentReportArchiveBuilder.manualPassCounts(manualJson);
    }

    /** JSON GỘP toàn bộ bài đã chấm xong của 1 ĐỀ: { examId, count, results:[...] } — in đẹp. */
    @GetMapping("/exam/{examId}/full")
    public ResponseEntity<String> getExamResultsFull(@PathVariable String examId) {
        List<ExamResult> rows = resultRepo.findByExamIdAndModeOrderByUpdatedAtDesc(examId, "submit");

        ArrayNode results = mapper.createArrayNode();
        for (ExamResult r : rows) {
            String rj = r.getResultJson();
            if (rj == null || rj.isBlank()) continue;   // bài chưa chấm xong/ lỗi → bỏ qua
            try {
                results.add(normalizeResultNode(mapper.readTree(rj)));
            } catch (Exception ignored) { /* JSON hỏng → bỏ qua phần tử này */ }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("examId", examId);
        root.put("count", results.size());
        root.set("results", results);

        String out;
        try {
            out = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            out = "{\"examId\":\"" + examId + "\",\"count\":0,\"results\":[]}";
        }
        return ResponseEntity.ok().contentType(JSON_UTF8).body(out);
    }

    /** JSON đầy đủ của 1 bài (1 SV + 1 đề) — in đẹp. */
    @GetMapping("/{examId}/{studentId}")
    public ResponseEntity<String> getResult(@PathVariable String examId,
                                            @PathVariable String studentId) {
        return resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit")
                .map(ExamResult::getResultJson)
                .filter(json -> json != null && !json.isBlank())
                .map(json -> ResponseEntity.ok()
                        .contentType(JSON_UTF8)
                        .body(pretty(json)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Kết quả CẢ batch: { batchId, count, results: [ ... ] } — in đẹp, gồm TẤT CẢ bài. */
    @GetMapping("/batch/{batchId}")
    public ResponseEntity<String> getBatchResults(@PathVariable String batchId) {
        List<ExamResult> rows = resultRepo.findByBatchIdOrderByStudentId(batchId);

        ArrayNode results = mapper.createArrayNode();
        for (ExamResult r : rows) {
            String rj = r.getResultJson();
            if (rj == null || rj.isBlank()) continue;   // bài chưa chấm xong/ lỗi → bỏ qua
            try {
                results.add(normalizeResultNode(mapper.readTree(rj)));
            } catch (Exception ignored) { /* JSON hỏng → bỏ qua phần tử này */ }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("batchId", batchId);
        root.put("count", results.size());
        root.set("results", results);

        String out;
        try {
            out = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            out = "{\"batchId\":\"" + batchId + "\",\"count\":0,\"results\":[]}";
        }
        return ResponseEntity.ok().contentType(JSON_UTF8).body(out);
    }

    /**
     * Xuất một thư mục kết quả theo lô. HTTP không truyền trực tiếp được thư mục nên ZIP chỉ là
     * lớp vận chuyển; bên trong luôn là một folder và mỗi sinh viên có đúng một file JSON riêng.
     */
    @GetMapping(value = "/batch/{batchId}/archive", produces = "application/zip")
    public ResponseEntity<byte[]> getBatchResultsArchive(@PathVariable String batchId) {
        return resultsArchive(batchId, resultRepo.findByBatchIdOrderByStudentId(batchId));
    }

    /**
     * Thư mục kết quả theo ĐỀ (mọi lô đã chấm) — bản song sinh của endpoint trên cho trang Lịch sử.
     * Cũng chỉ gồm bài ĐÃ CHẤM XONG; xem {@link #exportable}.
     */
    @GetMapping(value = "/exam/{examId}/archive", produces = "application/zip")
    public ResponseEntity<byte[]> getExamResultsArchive(@PathVariable String examId) {
        return resultsArchive(examId, resultRepo.findByExamIdAndModeOrderByUpdatedAtDesc(examId, "submit"));
    }

    /**
     * HỒ SƠ PHÁT CHO SINH VIÊN: Result_of_&lt;đề&gt;/&lt;MSSV&gt;/{xlsx có ảnh đối chứng, logs/}.
     * Chỉ bài đã chấm xong; xem {@link StudentReportArchiveBuilder} cho cấu trúc và lý do từng file.
     */
    @GetMapping(value = "/exam/{examId}/report-package", produces = "application/zip")
    public ResponseEntity<byte[]> getStudentReportPackage(@PathVariable String examId) {
        List<ExamResult> rows = resultRepo.findByExamIdAndModeOrderByUpdatedAtDesc(examId, "submit")
                .stream().filter(ResultController::exportable).toList();
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        try {
            // Ảnh chuẩn của bộ đề + ảnh bằng chứng từng bài — hai nguồn ảnh nhúng vào .xlsx.
            java.nio.file.Path goldenScreens = examRepo.findByExamId(examId)
                    .map(exam -> exam.getTestcasePath())
                    .filter(path -> path != null && !path.isBlank())
                    .map(path -> java.nio.file.Path.of(path).resolve("fixtures").resolve("screens"))
                    .orElse(null);
            java.nio.file.Path submissionsRoot = examService.resolveSibling(submissionsDir).resolve(examId);
            byte[] archive = new StudentReportArchiveBuilder(goldenScreens,
                    row -> row.getBatchId() == null ? null
                            : submissionsRoot.resolve(row.getBatchId())
                                    .resolve("_evidence").resolve(row.getStudentId()),
                    row -> row.getBatchId() == null ? null
                            : submissionsRoot.resolve(row.getBatchId()).resolve("_testcase"))
                    .build(examId, rows);
            String downloadName = "Result_of_" + safeArchivePart(examId) + ".zip";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(downloadName, StandardCharsets.UTF_8).build().toString())
                    .body(archive);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private ResponseEntity<byte[]> resultsArchive(String name, List<ExamResult> rows) {
        if (rows.stream().noneMatch(ResultController::exportable))
            return ResponseEntity.notFound().build();
        try {
            byte[] archive = buildBatchResultsArchive(name, rows);
            String downloadName = ARCHIVE_ROOT + ".zip";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(downloadName, StandardCharsets.UTF_8).build().toString())
                    .body(archive);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Chỉ bài CHẤM XONG mới được xuất.
     *
     * <p>Trước đây điều kiện chỉ là "có result_json", nên bài {@code MANUAL_REVIEW} — máy chấm
     * không cho ra điểm nhưng vẫn kịp ghi JSON dở — cũng lọt vào thư mục kết quả. Bên nhận không
     * có cách nào biết file đó là kết quả chưa tin được.
     */
    private static boolean exportable(ExamResult row) {
        return row.getStatus() == GradingStatus.DONE
                && row.getResultJson() != null && !row.getResultJson().isBlank();
    }

    /**
     * Tên thư mục bên trong ZIP. Cố định "Json" theo đúng cấu trúc đã chốt: giải nén ra là thấy
     * ngay danh sách file, không phải lách qua một lớp thư mục mang tên lô/đề.
     */
    private static final String ARCHIVE_ROOT = "Json";

    byte[] buildBatchResultsArchive(String batchId, List<ExamResult> rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String root = ARCHIVE_ROOT + "/";
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(root));
            zip.closeEntry();
            for (ExamResult row : rows) {
                if (!exportable(row)) continue;
                String json = row.getResultJson();
                // Đặt tên theo MÃ SV, không theo tên thư mục sinh viên nộp: mã là thứ duy nhất
                // đối chiếu được với bảng điểm, còn tên thư mục mỗi lớp nộp một kiểu.
                String base = safeArchivePart(row.getStudentId() == null ? "student" : row.getStudentId());
                String name = base + ".json";
                if (!usedNames.add(name.toLowerCase())) {
                    name = base + "_" + usedNames.size() + ".json";
                    usedNames.add(name.toLowerCase());
                }
                zip.putNextEntry(new ZipEntry(root + name));
                zip.write(pretty(json).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String safeArchivePart(String value) {
        String safe = value == null ? "result" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isBlank() ? "result" : safe;
    }

    private String pretty(String json) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(normalizeResultNode(mapper.readTree(json)));
        } catch (Exception e) {
            return json;   // không parse được thì trả nguyên văn
        }
    }

    /** Giữ schema JSON ổn định cho cả kết quả cũ chưa có expected/actual. */
    private JsonNode normalizeResultNode(JsonNode root) {
        if (root instanceof ObjectNode object) {
            JsonNode rawCases = object.get("test_cases");
            if (rawCases instanceof ArrayNode cases) {
                for (JsonNode rawCase : cases) {
                    if (!(rawCase instanceof ObjectNode tc)) continue;

                    String expected = textOrBlank(tc.get("expected"));
                    if (expected.isBlank()) expected = textOrBlank(tc.get("expect"));
                    if (expected.isBlank()) expected = "PASS";
                    tc.put("expected", expected);
                    tc.remove("expect"); // dữ liệu cũ vẫn đọc được nhưng không phát hành alias này nữa

                    String actual = normalizeActual(textOrBlank(tc.get("actual")));
                    if (actual.isBlank()) {
                        String status = textOrBlank(tc.get("status")).toLowerCase();
                        tc.put("actual", status.contains("pass") ? "Đã đáp ứng yêu cầu" : "Không đáp ứng yêu cầu");
                    } else {
                        tc.put("actual", actual);
                    }

                    // Thứ tự bắt buộc: rút `error_code` ra TRƯỚC khi xoá object `error`.
                    addContractKeysWithoutGuessing(tc);
                    dropRetiredErrorFields(tc);
                }
            }
        }
        removeNullObjectFields(root);
        return root;
    }

    /**
     * JSON tải xuống chỉ giữ thông tin thực sự có giá trị. Field tùy chọn vẫn xuất hiện khi có dữ liệu,
     * nhưng không phát hành hàng nghìn cặp key:null làm file khó đọc.
     */
    private void removeNullObjectFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> nullFields = new ArrayList<>();
            object.properties().forEach(entry -> {
                if (entry.getValue() == null || entry.getValue().isNull()) nullFields.add(entry.getKey());
                else removeNullObjectFields(entry.getValue());
            });
            nullFields.forEach(object::remove);
            return;
        }
        if (node instanceof ArrayNode array) {
            for (JsonNode child : array) removeNullObjectFields(child);
        }
    }

    /**
     * Bổ sung khoá hợp đồng cho JSON ĐÃ LƯU — chỉ những khoá suy được mà KHÔNG phải đoán.
     *
     * <p>Cố ý KHÔNG bơm `executed` và `schema_version`: dữ liệu chấm trước P4 ghi mọi test chưa
     * chạy thành `failed`, nên gán `executed = true` cho chúng là nói sai. Sự VẮNG MẶT của hai
     * khoá đó chính là dấu hiệu "dữ liệu bản 1" mà bên đọc dựa vào — bơm vào là xoá mất dấu hiệu.
     */
    private void addContractKeysWithoutGuessing(ObjectNode tc) {
        // Hoãn tới P4b nên luôn null; đặt khoá để bên đọc không phải đoán schema.
        if (!tc.has("blocked_by")) tc.putNull("blocked_by");
        // Mã lỗi phẳng: chép từ error.code đang có, không sinh giá trị mới.
        if (!tc.has("error_code")) {
            JsonNode error = tc.get("error");
            String code = error instanceof ObjectNode obj ? textOrBlank(obj.get("code")) : "";
            if (code.isBlank()) tc.putNull("error_code");
            else tc.put("error_code", code);
        }
    }

    /**
     * Dữ liệu cũ từng ghi cả prefix "Ném lỗi:" và nhầm dòng "The test description was: <id>"
     * vào actual. Khi tải JSON, sửa tại lớp biên để các kết quả đã lưu vẫn có schema sạch.
     */
    private String normalizeActual(String value) {
        String actual = value == null ? "" : value.trim();
        if (actual.isBlank()) return "";
        if (actual.matches("(?is)^Ném lỗi:\\s*The test description was:?\\s*.*$")
                || actual.matches("(?is)^The test description was:?\\s*.*$")) {
            return "Không có giá trị actual — testcase dừng do exception";
        }
        if (actual.regionMatches(true, 0, "Ném lỗi:", 0, "Ném lỗi:".length())) {
            actual = actual.substring("Ném lỗi:".length()).trim();
        }
        if (actual.equalsIgnoreCase("Test failed. See exception logs above.")
                || actual.equalsIgnoreCase("Test failed. See exception logs above")) {
            return "Không có giá trị actual — testcase dừng do exception";
        }
        return actual;
    }

    /**
     * P2b — GỠ HẲN `error` + `student_safe_summary` khỏi mọi kết quả trả ra, kể cả dữ liệu ĐÃ LƯU.
     *
     * <p>Trước đây chỗ này làm điều tệ hơn cả không gỡ: nó **tự bơm lại** `student_safe_summary`
     * bằng một câu tra bảng theo mã lỗi khi tải JSON. Gỡ ở nơi sinh mà bỏ chỗ này thì vô hiệu —
     * đúng bẫy số 1 trong sổ thi công.
     *
     * <p>Lọc cả dữ liệu cũ (không chỉ ngừng bơm) để hợp đồng không nói một đằng dữ liệu một nẻo:
     * bên đọc đã bỏ khai hai trường này, gửi thêm là làm lưới "không field nào bị nuốt" của họ đỏ.
     * `error.code` được rút sang `error_code` trước khi xoá — xem {@link #addContractKeysWithoutGuessing}.
     */
    private void dropRetiredErrorFields(ObjectNode tc) {
        tc.remove("error");
        tc.remove("student_safe_summary");
    }

    private String normalizeJsonString(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(normalizeResultNode(mapper.readTree(json)));
        } catch (Exception e) {
            return json;
        }
    }

    private String textOrBlank(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }
}
