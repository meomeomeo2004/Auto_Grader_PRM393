package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.repository.ExamRepository;
import com.example.grader.service.BanGiaoService;
import com.example.grader.service.ExamService;
import com.example.grader.service.StarterSyncService;
import com.example.grader.service.ExamDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Profile(Vai.GIANG_VIEN)   // Quản lý đề và bộ chấm — chỉ bản giảng viên.
@RestController
@RequestMapping("/api/exam-setup")
@CrossOrigin(origins = "*")
public class ExamSetupController {

    @Autowired
    private ExamService examService;
    @Autowired
    private ExamRepository examRepo;
    @Autowired
    private StarterSyncService starterSyncService;
    @Autowired
    private BanGiaoService banGiaoService;
    @Autowired
    private ExamDocumentReader examDocumentReader;

    /**
     * KIỂM ĐỒNG BỘ KHUNG PHÁT. Chọn một đề đã publish testcase, nạp gói khung phát cho sinh
     * viên, hệ thống đối chiếu với Golden đang gắn với đề đó.
     *
     * Golden LẤY THẲNG từ artifact của bộ chấm, không cho nạp lại: nạp lại thì người ra đề có
     * thể vô tình đưa một bản Golden khác với bản đã ghi hình, làm đổi nền tảng chấm mà không
     * ai thấy.
     *
     * Đạt thì đề mới được hiện ở phần chấm; trượt thì không, kèm báo cáo lệch chỗ nào.
     */
    @PostMapping(value = "/{examId}/starter-check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> kiemDongBoKhungPhat(@PathVariable String examId,
                                                 @RequestPart("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(starterSyncService.kiem(examId, file));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Trạng thái kiểm đồng bộ của một đề, để màn hình biết có hiện nút chấm hay không. */
    @GetMapping("/{examId}/starter-check")
    public ResponseEntity<?> trangThaiDongBo(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(starterSyncService.tomTat(examId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/upload-testcase")
    public ResponseEntity<?> uploadTestcase(
            @RequestParam("examId")   String examId,
            @RequestParam(value = "examName",    required = false) String examName,
            @RequestParam(value = "teacherNote", required = false) String teacherNote,
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.setupExam(examId, examName, teacherNote, zip));

        } catch (IllegalArgumentException e) {
            // Thiếu file bắt buộc, sai format...
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            // docker build thất bại...
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Build image thất bại: " + e.getMessage()));
        }
    }

    /**
     * Nhập bộ testcase viết thủ công. Mã/tên được suy ra từ tên ZIP; ZIP được giải nén rồi bỏ,
     * bộ nhập theo cách này không có testcase_config_json nên không mở lại bằng builder.
     */
    @PostMapping("/import-manual-testcase")
    public ResponseEntity<?> importManualTestcase(
            @RequestParam(value = "teacherNote", required = false) String teacherNote,
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.importManualTestcase(
                    zip.getOriginalFilename(), teacherNote, zip.getBytes(), AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Không nhập được bộ testcase: " + e.getMessage()));
        }
    }

    /** Build sandbox trực tiếp từ thư mục testcase đã lưu; không tạo hoặc giải nén ZIP trung gian. */
    @PostMapping("/{examId}/sandbox")
    public ResponseEntity<?> buildSandbox(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.buildSandbox(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Không build được sandbox: " + e.getMessage()));
        }
    }

    @GetMapping("/status/{examId}")
    public ResponseEntity<?> getStatus(@PathVariable String examId) {
        return examRepo.findByExamId(examId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Nhân bản CHỈ đề bài (không đụng testcase/Golden Suite) sang một mã đề mới — dùng cho nút
     * "Clone" ở Kho đề. Body: {@code { target_exam_id, exam_name? } }.
     */
    @PostMapping("/{examId}/clone-handout")
    public ResponseEntity<?> cloneHandout(@PathVariable String examId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        try {
            Object targetRaw = body == null ? null : body.get("target_exam_id");
            String targetId = targetRaw == null ? null : String.valueOf(targetRaw);
            Object nameRaw = body == null ? null : body.get("exam_name");
            String examName = nameRaw == null ? null : String.valueOf(nameRaw);
            return ResponseEntity.ok(examService.cloneExamHandout(examId, targetId, examName, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không nhân bản được đề bài: " + e.getMessage()));
        }
    }

    // Đường /list đã chuyển sang ExamCatalogController: cả hai vai đều cần nó (bản người chấm
    // dùng để chọn đề mà chấm), mà controller này thì chỉ bản giảng viên mới có.

    /**
     * Danh sách mã đề đã soạn bằng trợ lý AI (có de_bai.md), kể cả đề CHƯA có testcase/suite nào —
     * cho trang "Tạo đề" hiển thị để mở lại. Khác {@code /list}: cái đó chỉ trả đề đã có testcase
     * thật trong DB.
     */
    @GetMapping("/authored-list")
    public ResponseEntity<?> authoredList() {
        try {
            return ResponseEntity.ok(examService.listAuthoredExamIds());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không đọc được danh sách đề."));
        }
    }

    /** Rubric (danh sách tiêu chí) của đề — cho trang chấm tay. */
    @GetMapping("/criteria/{examId}")
    public ResponseEntity<?> getCriteria(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.getCriteria(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }


    /** Đổi mã và/hoặc tên bộ testcase. Khi đổi mã, toàn bộ dữ liệu liên quan được chuyển theo. */
    @PostMapping("/{examId}/rename")
    public ResponseEntity<?> renameTestcaseSet(@PathVariable String examId,
                                               @RequestBody Map<String, Object> body) {
        try {
            Object rawId = body == null ? null
                    : (body.get("new_exam_id") != null ? body.get("new_exam_id") : body.get("newExamId"));
            Object rawName = body == null ? null
                    : (body.get("exam_name") != null ? body.get("exam_name") : body.get("examName"));
            return ResponseEntity.ok(examService.renameExam(
                    examId,
                    rawId == null ? examId : String.valueOf(rawId).trim(),
                    rawName == null ? null : String.valueOf(rawName).trim()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }


    /**
     * Đọc các file testcase của 1 đề (exam_test.dart, skills_matrix.json, grader.dart).
     *
     * @param edit true = đọc để SỬA: trả nguyên vẹn, không cắt bớt (bản cắt mà lưu lại là mất dữ liệu)
     */
    @GetMapping("/{examId}/testcase")
    public ResponseEntity<?> testcaseFiles(@PathVariable String examId,
                                           @RequestParam(value = "edit", defaultValue = "false") boolean edit) {
        try {
            return ResponseEntity.ok(edit
                    ? examService.readEditableTestcaseFiles(examId)
                    : examService.readExamTestcaseFiles(examId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {     // mã đề không hợp lệ (allowlist)
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /**
     * Sửa trực tiếp file testcase của MỘT bộ bất kỳ. Body: { files: [{name, content}] }.
     * Bộ dựng bằng builder vẫn sửa được nhưng kèm cảnh báo (lần Lưu sau ở builder sẽ sinh đè).
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{examId}/testcase")
    public ResponseEntity<?> saveTestcaseFiles(@PathVariable String examId,
                                               @RequestBody Map<String, Object> body) {
        try {
            Object rawFiles = body == null ? null : body.get("files");
            java.util.List<Map<String, String>> files = rawFiles instanceof java.util.List
                    ? (java.util.List<Map<String, String>>) rawFiles : java.util.List.of();
            Map<String, Object> saved = new java.util.LinkedHashMap<>(
                    examService.saveExamTestcaseFiles(examId, files));
            saved.put("exam_id", examId);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Lưu ĐỀ BÀI + HÌNH MINH HỌA (do trợ lý AI soạn, giáo viên đã duyệt) vào bộ phát cho SV.
     * Body: { de_bai, mockups: [{id, svg}] }. Không đụng tới starter/lời giải mẫu đã có.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{examId}/handout")
    public ResponseEntity<?> saveHandout(@PathVariable String examId,
                                         @RequestBody Map<String, Object> body) {
        try {
            Object rawMockups = body == null ? null : body.get("mockups");
            java.util.List<Map<String, String>> mockups = rawMockups instanceof java.util.List
                    ? (java.util.List<Map<String, String>>) rawMockups : java.util.List.of();
            Object deBai = body == null ? null : body.get("de_bai");
            java.util.List<String> written = examService.saveDeBaiWithMockups(
                    examId, deBai == null ? null : String.valueOf(deBai), mockups);
            return ResponseEntity.ok(Map.of("exam_id", examId, "files", written));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Dựng THẬT file SQLite handout/hidden.db từ bản mô tả bảng+dữ liệu AI đã soạn
     * ({@code /api/ai/database/propose}). Body: { tables: [{name, create_sql, columns,
     * hidden_rows}] }.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{examId}/database-seed")
    public ResponseEntity<?> saveDatabaseSeed(@PathVariable String examId, @RequestBody Map<String, Object> body) {
        try {
            Object raw = body == null ? null : body.get("tables");
            java.util.List<Map<String, Object>> tables = raw instanceof java.util.List
                    ? (java.util.List<Map<String, Object>>) raw : java.util.List.of();
            examService.saveDatabaseSeed(examId, tables);
            return ResponseEntity.ok(Map.of("exam_id", examId, "ok", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Điểm tải handout/student.db đã gỡ cùng lúc với ô artifact "Database phát cho sinh viên":
    // saveDatabaseSeed không dựng file đó nữa (còn xoá nốt bản cũ), nên giữ lại chỉ là một
    // đường dẫn luôn trả 404.

    /** Tải database ẩn dùng để chấm chống hardcode (handout/hidden.db). 404 nếu chưa sinh. */
    @GetMapping("/{examId}/download/hidden-db")
    public ResponseEntity<?> downloadHiddenDb(@PathVariable String examId) {
        return downloadHandoutFile(examId, "hidden.db");
    }

    private ResponseEntity<?> downloadHandoutFile(String examId, String fileName) {
        try {
            byte[] bytes = examService.readHandoutFile(examId, fileName);
            if (bytes == null) return ResponseEntity.status(404)
                    .body(Map.of("error", "Đề " + examId + " chưa có " + fileName + " — hãy sinh database mẫu trước."));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + examId + "_" + fileName + "\"")
                    .contentType(MediaType.parseMediaType("application/x-sqlite3"))
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /**
     * Upload NGUYÊN file đề bài gốc (.docx/.pdf) giáo viên tự soạn ở ngoài — Kho tài liệu đề.
     * Nếu mã đề chưa tồn tại thì tự tạo một Exam nháp (dùng {@code examName} nếu có).
     *
     * <p>Nếu đề CHƯA có đề bài dạng văn bản ({@code de_bai.md}) — tức chỉ mới upload nguyên
     * file, chưa từng soạn qua "Tạo đề" — tự bóc chữ từ file vừa tải lên (tái dùng
     * {@link ExamDocumentReader}, engine sẵn có của {@code /ai/exam/import}) và lưu thành
     * {@code de_bai.md}, để đề hiện được ngay ở "Tạo đề"/"Tạo Golden" (hai nơi đó đọc danh
     * sách qua {@code /authored-list}, vốn CHỈ liệt kê đề đã có {@code de_bai.md} — thiếu bước
     * này thì đề vừa upload chỉ nằm im ở Kho tài liệu, không nơi nào khác thấy được). Bóc chữ
     * lỗi (PDF scan, file hỏng...) KHÔNG làm hỏng việc lưu file gốc — chỉ báo qua
     * {@code de_bai_extract_warning}, giáo viên vẫn tải/xem lại file gốc bình thường.
     * KHÔNG đè lên đề bài đã có sẵn — chỉ tự điền khi đề đang trống.
     */
    @PostMapping(value = "/{examId}/handout/original", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadOriginalHandout(@PathVariable String examId,
                                                   @RequestParam(value = "examName", required = false) String examName,
                                                   @RequestPart("file") MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            Map<String, Object> out = new java.util.LinkedHashMap<>(
                    examService.saveOriginalHandoutFile(examId, examName, file.getOriginalFilename(), bytes));
            String existing = examService.readDeBai(examId);
            if (existing == null || existing.isBlank()) {
                try {
                    Map<String, Object> extracted = examDocumentReader.read(file.getOriginalFilename(), bytes);
                    examService.saveDeBaiWithMockups(examId, String.valueOf(extracted.get("text")), java.util.List.of());
                    out.put("de_bai_extracted", true);
                } catch (Exception e) {
                    out.put("de_bai_extracted", false);
                    out.put("de_bai_extract_warning", e.getMessage());
                }
            } else {
                out.put("de_bai_extracted", false);
            }
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không lưu được file đề bài: " + e.getMessage()));
        }
    }

    /** Thông tin file đề bài gốc đã upload của 1 đề, để Kho tài liệu đề biết có hiện hay không. */
    @GetMapping("/{examId}/handout/original/info")
    public ResponseEntity<?> originalHandoutInfo(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.originalHandoutInfo(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Tải xuống nguyên file đề bài gốc đã upload. 404 nếu chưa upload. */
    @GetMapping("/{examId}/handout/original")
    public ResponseEntity<?> downloadOriginalHandout(@PathVariable String examId) {
        try {
            byte[] bytes = examService.readOriginalHandoutFile(examId);
            if (bytes == null) return ResponseEntity.status(404)
                    .body(Map.of("error", "Đề " + examId + " chưa có file đề bài gốc nào được tải lên."));
            String fileName = examService.originalHandoutFileName(examId);
            MediaType contentType = fileName != null && fileName.endsWith(".pdf")
                    ? MediaType.APPLICATION_PDF
                    : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + examId + "_" + fileName + "\"")
                    .contentType(contentType)
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Xoá file đề bài gốc đã upload của 1 đề. */
    @DeleteMapping("/{examId}/handout/original")
    public ResponseEntity<?> deleteOriginalHandout(@PathVariable String examId) {
        try {
            examService.deleteOriginalHandoutFile(examId);
            return ResponseEntity.ok(Map.of("exam_id", examId, "deleted", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /**
     * Lưu KHUNG STARTER (lib/…) phát cho sinh viên. Body: { files: [{name, content}] }.
     * Chỉ thay thư mục starter, không đụng đề bài/hình/lời giải mẫu.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{examId}/starter")
    public ResponseEntity<?> saveStarter(@PathVariable String examId,
                                         @RequestBody Map<String, Object> body) {
        try {
            Object rawFiles = body == null ? null : body.get("files");
            java.util.List<Map<String, String>> files = rawFiles instanceof java.util.List
                    ? (java.util.List<Map<String, String>>) rawFiles : java.util.List.of();
            return ResponseEntity.ok(Map.of("exam_id", examId,
                    "files", examService.saveStarterFiles(examId, files)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Phiên làm việc của TRỢ LÝ AI cho một bộ testcase (đề bài, khung starter, app lời giải mẫu…).
     *
     * <p>Nhờ nó mà bấm "Sửa" một bộ đã soạn bằng AI là mở lại đúng phiên đó và nhờ AI sửa tiếp
     * được ngay — kể cả khi mở trên máy khác hoặc đã dọn trình duyệt.
     */
    @GetMapping("/{examId}/ai-draft")
    public ResponseEntity<?> readAiDraft(@PathVariable String examId) {
        try {
            String json = examService.readAiAuthorDraft(examId);
            return ResponseEntity.ok(Map.of("exam_id", examId, "has_draft", json != null,
                    "draft", json == null ? "" : json));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Body: { draft: "<json>" } — chuỗi rỗng = xoá nháp AI của bộ này. */
    @PostMapping("/{examId}/ai-draft")
    public ResponseEntity<?> saveAiDraft(@PathVariable String examId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        try {
            Object draft = body == null ? null : body.get("draft");
            examService.saveAiAuthorDraft(examId, draft == null ? null : String.valueOf(draft));
            return ResponseEntity.ok(Map.of("exam_id", examId, "ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Trang "Xem đề": đề bài + hình minh họa đã gộp thành MỘT tài liệu HTML tự chứa.
     * Kèm luôn danh sách SVG để trình duyệt đổi sang PNG khi tải bản .docx.
     */
    @GetMapping("/{examId}/de-bai/view")
    public ResponseEntity<?> viewDeBai(@PathVariable String examId) {
        try {
            String md = examService.readDeBai(examId);
            java.util.List<Map<String, String>> mockups = examService.readMockups(examId).stream()
                    .map(m -> Map.of("id", m.id(), "title", m.title(), "svg", m.svg()))
                    .toList();
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("exam_id", examId);
            out.put("has_de_bai", md != null && !md.isBlank());
            out.put("de_bai", md == null ? "" : md);
            out.put("html", examService.buildHandoutHtml(examId));
            out.put("mockups", mockups);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Tải đề bài dạng .docx. Body: { images: [{ png_base64, width, height }] } — ảnh do trình
     * duyệt đổi từ SVG sang PNG (máy chủ không có thư viện rasterize). Bỏ trống = chỉ có chữ.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{examId}/de-bai/docx")
    public ResponseEntity<?> downloadDeBaiDocx(@PathVariable String examId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        try {
            Object raw = body == null ? null : body.get("images");
            java.util.List<Map<String, Object>> images = raw instanceof java.util.List
                    ? (java.util.List<Map<String, Object>>) raw : java.util.List.of();
            byte[] docx = examService.buildHandoutDocx(examId, images);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument"
                            + ".wordprocessingml.document")
                    .header("Content-Disposition", "attachment; filename=\"" + examId + "_de_bai.docx\"")
                    .body(docx);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Tải đề bài dạng .pdf — cùng body/nội dung với {@code /de-bai/docx}, khác định dạng xuất. */
    @SuppressWarnings("unchecked")
    @PostMapping("/{examId}/de-bai/pdf")
    public ResponseEntity<?> downloadDeBaiPdf(@PathVariable String examId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        try {
            Object raw = body == null ? null : body.get("images");
            java.util.List<Map<String, Object>> images = raw instanceof java.util.List
                    ? (java.util.List<Map<String, Object>>) raw : java.util.List.of();
            byte[] pdf = examService.buildHandoutPdf(examId, images);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename=\"" + examId + "_de_bai.pdf\"")
                    .body(pdf);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{examId}/handout")
    public ResponseEntity<?> readHandout(@PathVariable String examId) {
        try {
            String md = examService.readDeBai(examId);
            return ResponseEntity.ok(Map.of("exam_id", examId, "de_bai", md == null ? "" : md));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Tải ĐỀ BÀI (de_bai.md) phát cho SV. 404 nếu đề chưa lưu kèm. */
    @GetMapping("/{examId}/download/de-bai")
    public ResponseEntity<?> downloadDeBai(@PathVariable String examId) {
        try {
            String md = examService.readDeBai(examId);
            if (md == null)
                return ResponseEntity.status(404).body(Map.of("error",
                        "Đề này chưa có đề bài (de_bai.md)."));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + examId + "_de_bai.md\"")
                    .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                    .body(utf8WithBom(md));   // BOM để Notepad/Word trên Windows nhận đúng UTF-8 (không lỗi font TV)
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Tải EXAM_TEST: ZIP gồm ba file thực thi và contract.json được chuẩn hóa để upload lại không đổi hành vi chấm. */
    @GetMapping("/{examId}/download/exam-test")
    public ResponseEntity<?> downloadExamTest(@PathVariable String examId) {
        try {
            byte[] zip = examService.zipTestcase(examId);
            if (zip == null)
                return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy testcase của đề " + examId));
            return zipResponse(examId + "_exam_test.zip", zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /**
     * XUẤT GÓI BÀN GIAO cho người chấm: thư mục testcase đã xuất bản + tờ khai đi kèm.
     *
     * <p>Chặn ngay nếu đề chưa qua kiểm đồng bộ khung phát — bên nhận không có Golden để tự
     * kiểm, nên đây là nơi cuối cùng còn phán được, và cũng là nơi có mặt người sửa được.
     */
    @GetMapping("/{examId}/xuat-goi")
    public ResponseEntity<?> xuatGoiBanGiao(@PathVariable String examId) {
        try {
            byte[] zip = banGiaoService.xuatGoi(examId);
            return zipResponse(banGiaoService.tenTepGoi(examId), zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Không xuất được gói: " + e.getMessage()));
        }
    }

    /** Tải STARTER: ZIP khung code (lib/…) phát cho SV. 404 nếu đề chưa lưu kèm. */
    @GetMapping("/{examId}/download/starter")
    public ResponseEntity<?> downloadStarter(@PathVariable String examId) {
        try {
            byte[] zip = examService.zipStarter(examId);
            if (zip == null)
                return ResponseEntity.status(404).body(Map.of("error",
                        "Đề này chưa có khung starter."));
            return zipResponse(examId + "_starter.zip", zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Tải LỜI GIẢI MẪU: ZIP lib/ — KHÔNG phát SV, chỉ GV tham khảo. 404 nếu đề chưa lưu kèm. */
    @GetMapping("/{examId}/download/solution")
    public ResponseEntity<?> downloadSolution(@PathVariable String examId) {
        try {
            byte[] zip = examService.zipSolution(examId);
            if (zip == null)
                return ResponseEntity.status(404).body(Map.of("error",
                        "Đề này chưa có lời giải mẫu."));
            return zipResponse(examId + "_solution.zip", zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /**
     * Mã hoá chuỗi thành UTF-8 KÈM BOM (EF BB BF). File .md tiếng Việt thiếu BOM hay bị Notepad/Word/Excel
     * trên Windows đọc theo bảng mã ANSI (CP1258) → lỗi font. BOM giúp các app này tự nhận UTF-8.
     * (KHÔNG thêm BOM cho .dart/.json trong ZIP — một số parser/JSON nghiêm ngặt không chịu BOM.)
     */
    private byte[] utf8WithBom(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && s.charAt(0) == '﻿') return s.getBytes(StandardCharsets.UTF_8);   // đã có BOM
        byte[] text = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[3 + text.length];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(text, 0, out, 3, text.length);
        return out;
    }

    private ResponseEntity<byte[]> zipResponse(String filename, byte[] data) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(data);
    }

    // Đường xóa bộ testcase đã chuyển sang ExamCatalogController: bản người chấm cũng phải xóa
    // được bộ mình đã nhận (màn Quản lý bộ testcase), mà controller này thì chỉ giảng viên có.
}
