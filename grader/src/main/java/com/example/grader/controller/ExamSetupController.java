package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.repository.ExamRepository;
import com.example.grader.service.BanGiaoService;
import com.example.grader.service.ExamService;
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
    private BanGiaoService banGiaoService;
    @Autowired
    private ExamDocumentReader examDocumentReader;

    @PostMapping("/upload-testcase")
    public ResponseEntity<?> uploadTestcase(
            @RequestParam("examId")   String examId,
            @RequestParam(value = "examName",    required = false) String examName,
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.setupExam(examId, examName, zip));

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
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.importManualTestcase(
                    zip.getOriginalFilename(), zip.getBytes(), AppActor.DEFAULT));
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
            Object rawName = body == null ? null : body.get("exam_name");
            java.util.List<String> written = examService.saveDeBaiWithMockups(
                    examId, deBai == null ? null : String.valueOf(deBai),
                    rawName == null ? null : String.valueOf(rawName).trim(), mockups);
            return ResponseEntity.ok(Map.of("exam_id", examId, "files", written));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
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
                                                   @RequestParam(value = "moi", defaultValue = "false") boolean moi,
                                                   @RequestPart("file") MultipartFile file) {
        try {
            // Cờ `moi` do HỘP "TẠO ĐỀ" gửi: đây là đề mới tinh, trùng mã là từ chối. Không có cờ
            // thì đây là "tải đè" ở màn chi tiết — đường đó BẮT BUỘC phải ghi được lên đề đang
            // có, nên không được chặn cứng ở đây cho cả hai.
            //
            // Chặn ở máy chủ chứ không chỉ ở ô nhập: hàm dưới xoá file gốc cũ rồi mới ghi file
            // mới, nên một lần gõ trùng mã là mất hẳn bản Word của đề cũ, không có đường lùi.
            if (moi && examService.deDaTonTai(examId))
                return ResponseEntity.status(409).body(Map.of("error",
                        "Mã đề " + examId + " đã có rồi. Mở đề đó ra để sửa, hoặc dùng "
                                + "\"Nhân bản sang mã mới\" nếu muốn một bản riêng."));
            // KHÔNG bóc chữ ngầm nữa (20/9): xem chú thích ở saveOriginalHandoutFile. Muốn đưa
            // nội dung vào hệ thống thì gọi /handout/chuyen-vao-he-thong — một thao tác có tên,
            // có xác nhận, và ĐỔI LOẠI ĐỀ chứ không lặng lẽ đẻ ra một bản thứ hai.
            return ResponseEntity.ok(examService.saveOriginalHandoutFile(
                    examId, examName, file.getOriginalFilename(), file.getBytes()));
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
     * XEM TRƯỚC đề bài ĐANG SOẠN (chưa lưu) dưới dạng HTML. Body: { de_bai, mockups? }.
     *
     * <p>Vì sao phải đi vòng qua máy chủ thay vì dựng HTML ngay trong trình duyệt: repo build
     * offline nên không thêm được thư viện markdown cho frontend, mà tự viết một bộ dựng thứ hai
     * thì sớm muộn nó khác bộ đang dùng để sinh {@code de_bai.html} và bản .docx — giảng viên
     * xem một đằng, sinh viên nhận một nẻo. Đây dùng ĐÚNG {@link HandoutDocument#toHtml}, nên
     * cái nhìn thấy lúc soạn là cái sẽ ra.
     *
     * <p>Hàm thuần, không đọc/ghi đĩa: gõ tới đâu xem tới đó mà không đụng bản đã lưu.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{examId}/de-bai/xem-truoc")
    public ResponseEntity<?> xemTruocDeBai(@PathVariable String examId,
                                           @RequestBody Map<String, Object> body) {
        try {
            String md = body == null || body.get("de_bai") == null ? "" : String.valueOf(body.get("de_bai"));
            Object raw = body == null ? null : body.get("mockups");
            java.util.List<com.example.grader.service.HandoutDocument.Mockup> mockups = new java.util.ArrayList<>();
            if (raw instanceof java.util.List<?> ds) {
                for (Object o : ds) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    String id = String.valueOf(m.get("id"));
                    Object ten = m.get("title");
                    mockups.add(new com.example.grader.service.HandoutDocument.Mockup(
                            id, ten == null ? id : String.valueOf(ten), String.valueOf(m.get("svg"))));
                }
            }
            // Tên đề lấy từ bản ghi thật, KHÔNG dùng mã đề thay tên: bản xuất ra in tên đề ở
            // <h1>, nên truyền mã vào đây là bản xem trước và bản .docx lệch nhau ngay dòng
            // đầu — đúng loại lệch làm người soạn mất lòng tin vào ô xem trước.
            return ResponseEntity.ok(Map.of("html", com.example.grader.service.HandoutDocument.toHtml(
                    examId, examService.tenDeHienThi(examId), md, mockups)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
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

    /** Trang "Tạo đề" mở lại một đề đã soạn: đề bài + hình minh họa đã lưu (nếu có). */
    @GetMapping("/{examId}/handout")
    public ResponseEntity<?> readHandout(@PathVariable String examId) {
        try {
            String md = examService.readDeBai(examId);
            java.util.List<Map<String, String>> mockups = examService.readMockups(examId).stream()
                    .map(m -> Map.of("id", m.id(), "title", m.title(), "svg", m.svg()))
                    .toList();
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("exam_id", examId);
            out.put("de_bai", md == null ? "" : md);
            out.put("mockups", mockups);
            return ResponseEntity.ok(out);
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

    /**
     * TẢI KHUNG PHÁT cho sinh viên — dựng từ chính Golden đang dùng, tải cùng lúc với gói bàn giao.
     *
     * <p>Không có nút riêng ở màn recorder: khung và gói cho người chấm phải ra từ MỘT bản Golden,
     * mà cách chắc nhất là sinh cả hai trong cùng một thao tác.
     */
    @GetMapping("/{examId}/khung-phat")
    public ResponseEntity<?> taiKhungPhat(@PathVariable String examId) {
        try {
            return zipResponse(examId + "_khung_phat.zip", examService.zipKhungPhat(examId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Khung phát đã tải lần trước còn khớp Golden hiện tại không.
     *
     * <p>{@code lech = true} nghĩa là Golden đã đổi ở đúng những chỗ khung phát lấy về, nên bản
     * sinh viên đang cầm không còn giống bộ dùng để chấm — phải phát lại khung.
     */
    @GetMapping("/{examId}/khung-phat/trang-thai")
    public ResponseEntity<?> trangThaiKhungPhat(@PathVariable String examId) {
        try {
            String hienTai = examService.vanTayKhungPhat(examId);
            String daXuat = examRepo.findByExamId(examId)
                    .map(com.example.grader.entity.Exam::getKhungVanTay).orElse(null);
            Map<String, Object> ra = new java.util.LinkedHashMap<>();
            ra.put("van_tay", hienTai);
            ra.put("van_tay_da_xuat", daXuat);
            ra.put("da_tung_xuat", daXuat != null && !daXuat.isBlank());
            ra.put("lech", daXuat != null && !daXuat.isBlank() && !daXuat.equals(hienTai));
            return ResponseEntity.ok(ra);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
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

    /**
     * TẢI FILE MẪU {@code dinh_danh.dart}. Tĩnh, không gắn với đề nào — nên KHÔNG có {@code examId}
     * trên đường dẫn: người lần đầu dựng Golden chưa có đề nào để mà gắn vào.
     *
     * <p>Đặt ở màn đầu của Bộ chấm Golden, cạnh khung "Tạo bộ chấm", vì đó là lúc người ta cần nó:
     * trước khi viết Golden, không phải sau.
     *
     * <p>Đường phải có HAI đoạn: {@code ExamCatalogController} khai {@code @DeleteMapping("/{examId}")}
     * trên cùng gốc {@code /api/exam-setup}, nên một đoạn duy nhất sẽ bị khớp thành mã đề. Đo trên
     * bản đang chạy: GET /api/exam-setup/dinh-danh-mau trả 405 chứ không phải 404.
     */
    @GetMapping("/mau/dinh-danh")
    public ResponseEntity<?> taiDinhDanhMau() {
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dinh_danh.dart\"")
                    .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                    .body(examService.dinhDanhMau());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Không đọc được file mẫu: " + e.getMessage()));
        }
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
