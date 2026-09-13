package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.service.BehaviorAuthoringService;
import com.example.grader.service.ExamService;
import com.example.grader.service.ai.AiExamAuthorService;
import com.example.grader.service.ai.AiSettingsService;
import com.example.grader.service.ai.ExamDocumentReader;
import com.example.grader.service.ai.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Trợ lý AI soạn đề & sinh code cho trang "Behavior Authoring" (Golden Solution Record–Abstract–
 * Replay).
 *
 * <p>Port từ Grader_App1 nhưng ĐÃ BỎ {@code /keys/*} và {@code /testcases/propose}: hai nhóm
 * endpoint đó phục vụ hệ thống Item Key/Template testcase mà Grader_App không còn dùng. Thêm mới
 * {@code /golden/*} — sinh app "lời giải mẫu" (Golden Solution) đầy đủ, năng lực chưa từng có ở
 * Grader_App1.
 *
 * <p>Mọi endpoint ở đây chỉ TRẢ VỀ ĐỀ XUẤT. Không endpoint nào tự ghi vào đề đang chấm hay tự đăng
 * ký Golden Solution — giáo viên sửa rồi bấm chấp nhận/tải về thì mới đi qua các API lưu/upload đã
 * có sẵn.
 */
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*")
public class AiAuthorController {

    @Autowired private AiSettingsService settings;
    @Autowired private LlmService llm;
    @Autowired private AiExamAuthorService author;
    @Autowired private ExamDocumentReader documents;
    @Autowired private ExamService examService;
    @Autowired private BehaviorAuthoringService behaviorAuthoring;

    // ── Cấu hình LLM ─────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return handle(() -> settings.describe());
    }

    /** Body: { provider, apiKey?, model?, baseUrl?, timeoutSeconds?, clearApiKey? }. */
    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> body) {
        return handle(() -> settings.update(body, AppActor.DEFAULT));
    }

    /**
     * Gọi thử một lượt ngắn để biết key/model dùng được không. Luôn trả 200 kèm {ok,message}.
     *
     * <p>Body {model, apiKey, baseUrl} là cấu hình ĐANG GÕ: thử trên bản nháp và KHÔNG lưu gì.
     * Bỏ trống body thì thử chính cấu hình đã lưu.
     */
    @PostMapping("/settings/test")
    public ResponseEntity<?> testConnection(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(settings.withDraft(
                    str(body, "model"), str(body, "apiKey"), str(body, "baseUrl"),
                    () -> llm.testConnection()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    // ── Bước 1: đề bài ───────────────────────────────────────────

    /** Body: { topic, knowledge, screens, features, entity, storage, difficulty, duration, note,
     *          database_name?, allowed_packages? }. */
    @PostMapping("/exam/draft")
    public ResponseEntity<?> draftExam(@RequestBody Map<String, Object> body) {
        return handle(() -> author.draftExam(body, str(body, "database_name"), packages(body)));
    }

    /** Body: { de_bai, instruction } — giáo viên gõ yêu cầu sửa bằng lời. */
    @PostMapping("/exam/revise")
    public ResponseEntity<?> reviseExam(@RequestBody Map<String, Object> body) {
        return handle(() -> author.reviseExam(str(body, "de_bai"), str(body, "instruction")));
    }

    /**
     * Nhánh "đã có đề sẵn": tải file đề lên (.docx/.pdf/.txt/.md), bóc chữ ra để giáo viên xem lại
     * trước khi tốn một lượt AI. KHÔNG gọi LLM ở đây.
     */
    @PostMapping("/exam/import")
    public ResponseEntity<?> importExam(@RequestParam("file") MultipartFile file) {
        return handle(() -> {
            if (file == null || file.isEmpty())
                throw new IllegalArgumentException("Chưa chọn file đề để tải lên.");
            try {
                Map<String, Object> read = documents.read(file.getOriginalFilename(), file.getBytes());
                Map<String, Object> out = new LinkedHashMap<>(read);
                out.put("de_bai", read.get("text"));
                out.put("file_name", file.getOriginalFilename());
                return out;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Không đọc được file tải lên: " + e.getMessage());
            }
        });
    }

    // ── Bước 2: khung starter ────────────────────────────────────

    /** Body: { de_bai } → { files[{path,content,summary}], warnings[], notes[], syntax_ok }. */
    @PostMapping("/starter/propose")
    public ResponseEntity<?> proposeStarter(@RequestBody Map<String, Object> body) {
        return handle(() -> author.proposeStarter(str(body, "de_bai")));
    }

    /** Body: { files } → kiểm lại cú pháp sau khi giáo viên sửa tay. Không gọi AI. */
    @SuppressWarnings("unchecked")
    @PostMapping("/starter/check")
    public ResponseEntity<?> checkStarter(@RequestBody Map<String, Object> body) {
        return handle(() -> {
            Object files = body == null ? null : body.get("files");
            return author.checkStarterSyntax(files instanceof List ? (List<Map<String, Object>>) files : List.of());
        });
    }

    /**
     * Body: { de_bai, spec, instruction } → nhờ AI sửa khung bằng lời.
     * Lượt sửa SINH LẠI toàn bộ file (thân hàm vẫn luôn là TODO).
     */
    @PostMapping("/starter/revise")
    public ResponseEntity<?> reviseStarter(@RequestBody Map<String, Object> body) {
        return handle(() -> author.reviseStarter(
                str(body, "de_bai"), body == null ? null : body.get("spec"), str(body, "instruction")));
    }

    /**
     * Body: { files: [{path, content}], exam_id? } → ZIP khung starter ĐANG SOẠN để tải về ngay,
     * không cần lưu vào bộ testcase trước.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/starter/download")
    public ResponseEntity<?> downloadStarter(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("files");
        List<Map<String, Object>> files = raw instanceof List ? (List<Map<String, Object>>) raw : List.of();
        if (files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Chưa có file khung starter để tải."));
        List<Map<String, Object>> all = new java.util.ArrayList<>(files);
        all.addAll(scaffoldEntries());
        String examId = str(body, "exam_id");
        String name = examId == null || examId.isBlank() ? "starter" : examId.trim() + "_starter";
        return zipDownload(all, name);
    }

    // ── Bước 3 (MỚI): app "lời giải mẫu" (Golden Solution) ───────

    /** Body: { de_bai, database_name?, allowed_packages? } → { files[{path,content}], warnings[], notes[], syntax_ok }. */
    @SuppressWarnings("unchecked")
    @PostMapping("/golden/propose")
    public ResponseEntity<?> proposeGolden(@RequestBody Map<String, Object> body) {
        return handle(() -> author.proposeGoldenSolution(
                str(body, "de_bai"), str(body, "database_name"), packages(body)));
    }

    /** Body: { files } → kiểm lại cú pháp sau khi giáo viên sửa tay. Không gọi AI. */
    @SuppressWarnings("unchecked")
    @PostMapping("/golden/check")
    public ResponseEntity<?> checkGolden(@RequestBody Map<String, Object> body) {
        return handle(() -> {
            Object files = body == null ? null : body.get("files");
            return author.checkGoldenSyntax(files instanceof List ? (List<Map<String, Object>>) files : List.of());
        });
    }

    /** Body: { de_bai, spec (= files hiện tại), instruction, database_name?, allowed_packages? }. */
    @SuppressWarnings("unchecked")
    @PostMapping("/golden/revise")
    public ResponseEntity<?> reviseGolden(@RequestBody Map<String, Object> body) {
        return handle(() -> author.reviseGoldenSolution(
                str(body, "de_bai"), body == null ? null : body.get("spec"), str(body, "instruction"),
                str(body, "database_name"), packages(body)));
    }

    /** Body: {de_bai} → {tables:[{name,create_sql,columns,student_rows,hidden_rows}], notes[]}. */
    @PostMapping("/database/propose")
    public ResponseEntity<?> proposeDatabaseSeed(@RequestBody Map<String, Object> body) {
        return handle(() -> author.proposeDatabaseSeed(str(body, "de_bai")));
    }

    /**
     * Body: {exam_id} → tự tạo (hoặc trả về Suite/Golden App đã có) cho mã đề này, để trang
     * "Tạo Golden" gắn Golden Solution vào mà giáo viên không phải tự điền form Suite (database
     * contract, allowed packages…) như trang "Bộ chấm Golden" cũ. Quy ước MỘT Suite mặc định mỗi
     * mã đề — {@link BehaviorAuthoringService#listSuites} lọc theo exam_id, có rồi thì trả luôn,
     * chưa có thì tạo Golden App rồi Suite với giá trị mặc định (giáo viên chỉnh lại sau ở trang
     * "Bộ chấm Golden" nếu cần).
     */
    @PostMapping("/golden/ensure-suite")
    public ResponseEntity<?> ensureSuite(@RequestBody Map<String, Object> body) {
        return handle(() -> {
            String examId = str(body, "exam_id");
            if (examId == null || examId.isBlank()) throw new IllegalArgumentException("Thiếu mã đề.");
            examId = examId.trim();
            List<Map<String, Object>> existing = behaviorAuthoring.listSuites(examId);
            if (!existing.isEmpty()) return existing.get(0);

            String code = (examId + "_RAR").toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
            Map<String, Object> golden = behaviorAuthoring.registerGoldenApp(Map.of(
                    "name", examId + " - Golden", "exam_id", examId, "platform", "WEB"));
            return behaviorAuthoring.createSuite(Map.of(
                    "suite_code", code, "exam_id", examId, "golden_app_id", String.valueOf(golden.get("id")),
                    "name", examId, "description", "Tạo tự động từ trang \"Tạo Golden\""));
        });
    }

    /**
     * Body: { files: [{path, content}], exam_id? } → ZIP app lời giải mẫu để tải về, kèm sẵn
     * pubspec.yaml/pubspec.lock đúng môi trường chấm (giống starter/download). Giáo viên tự tải
     * ZIP này lên ô "Golden Solution" ở trang Behavior Authoring — endpoint này KHÔNG tự đăng ký gì.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/golden/download")
    public ResponseEntity<?> downloadGolden(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("files");
        List<Map<String, Object>> files = raw instanceof List ? (List<Map<String, Object>>) raw : List.of();
        if (files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Chưa có file app lời giải mẫu để tải."));

        List<Map<String, Object>> all = new java.util.ArrayList<>();
        for (Map<String, String> project : examService.starterProjectFiles()) {
            all.add(Map.of("path", project.get("name"), "content", project.get("content")));
        }
        all.addAll(files);
        all.addAll(scaffoldEntries());

        String examId = str(body, "exam_id");
        String name = examId == null || examId.isBlank() ? "golden_solution" : examId.trim() + "_golden_solution";
        return zipDownload(all, name);
    }

    /** {@link ExamService#flutterScaffoldFiles()} chuyển sang khuôn {path,content,encoding} của {@link #zipDownload}. */
    private List<Map<String, Object>> scaffoldEntries() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, String> file : examService.flutterScaffoldFiles()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", file.get("name"));
            row.put("content", file.get("content"));
            row.put("encoding", file.get("encoding"));
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> packages(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("allowed_packages");
        return raw instanceof List ? (List<String>) raw : null;
    }

    // ── Chung ────────────────────────────────────────────────────

    private ResponseEntity<?> zipDownload(List<Map<String, Object>> files, String baseName) {
        try {
            var bos = new java.io.ByteArrayOutputStream();
            try (var zos = new java.util.zip.ZipOutputStream(bos)) {
                for (Map<String, Object> file : files) {
                    String path = file == null ? null : String.valueOf(file.get("path"));
                    if (path == null || path.isBlank() || path.equals("null")) continue;
                    // Tên file đến từ trình duyệt: '..' hay '/' đầu dòng sẽ ghi ra ngoài thư mục
                    // giải nén trên máy người nhận (zip slip) → chặn ngay tại đây.
                    String clean = path.replace('\\', '/').trim();
                    if (clean.startsWith("/") || clean.contains("../"))
                        return ResponseEntity.badRequest().body(Map.of("error", "Tên file không hợp lệ: " + path));
                    zos.putNextEntry(new java.util.zip.ZipEntry(clean));
                    Object content = file.get("content");
                    String text = content == null ? "" : String.valueOf(content);
                    // Vỏ dự án Flutter (flutter-starter-scaffold, xem ExamService#flutterScaffoldFiles)
                    // có file nhị phân (icon PNG, gradle-wrapper.jar) nên được gửi base64 — ép UTF-8
                    // như văn bản thường sẽ hỏng file.
                    byte[] bytes = "base64".equals(file.get("encoding"))
                            ? java.util.Base64.getDecoder().decode(text)
                            : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    zos.write(bytes);
                    zos.closeEntry();
                }
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/zip")
                    .header("Content-Disposition", "attachment; filename=\"" + baseName + ".zip\"")
                    .body(bos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không nén được file."));
        }
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private ResponseEntity<?> handle(Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e));
        } catch (IllegalStateException e) {
            // Chưa cắm key / AI trả về rác / gọi mạng lỗi: lỗi của phía AI, không phải request sai.
            return ResponseEntity.status(502).body(error(e));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error(e));
        }
    }

    private Map<String, Object> error(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        return m;
    }
}
