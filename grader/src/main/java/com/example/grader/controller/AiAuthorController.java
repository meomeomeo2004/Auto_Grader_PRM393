package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import com.example.grader.service.ai.AiExamAuthorService;
import com.example.grader.service.ai.AiSettingsService;
import com.example.grader.service.ExamDocumentReader;
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
 * Trợ lý AI soạn đề — sinh/sửa đề bài, sinh hình minh họa giao diện, database mẫu.
 *
 * <p>Port từ Grader_App1 nhưng ĐÃ BỎ {@code /keys/*} và {@code /testcases/propose}: hai nhóm
 * endpoint đó phục vụ hệ thống Item Key/Template testcase mà Grader_App không còn dùng.
 *
 * <p>ĐÃ BỎ {@code /starter/*} và {@code /golden/*} (17/9/2026): trang "Tạo Golden" — sinh khung
 * starter + app lời giải mẫu bằng AI, build Docker và chụp ảnh màn hình — bị xoá theo yêu cầu.
 * Giáo viên vẫn soạn/upload Golden Solution thủ công ở trang "Bộ chấm Golden"
 * ({@code /teacher/archive}), hoàn toàn không phụ thuộc lớp này.
 *
 * <p>Mọi endpoint ở đây chỉ TRẢ VỀ ĐỀ XUẤT. Không endpoint nào tự ghi vào đề đang chấm — giáo
 * viên sửa rồi bấm chấp nhận/lưu thì mới đi qua các API lưu đã có sẵn.
 *
 * <p>GẮN VAI GIẢNG VIÊN (14/9/2026, lúc gộp nhánh này vào bản đã tách vai). Lớp này viết TRƯỚC khi
 * hệ thống tách làm hai bản nên nó không mang nhãn vai nào, mà không nhãn thì Spring nạp ở CẢ HAI —
 * bản người chấm sẽ có luôn đường gọi mô hình sinh đề và {@code /settings}, nơi giữ khoá API. Git
 * gộp trót lọt, không một dòng cảnh báo; chỉ {@code VaiDuongDanNguoiChamTest} mới nói ra, và bài
 * kiểm đó đã chờ sẵn từ trước lần gộp này.
 */
@Profile(Vai.GIANG_VIEN)   // Soạn đề — chỉ bản giảng viên. Xem chú thích dưới đây.
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*")
public class AiAuthorController {

    @Autowired private AiSettingsService settings;
    @Autowired private LlmService llm;
    @Autowired private AiExamAuthorService author;
    @Autowired private ExamDocumentReader documents;

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
     *          database_name? }. */
    @PostMapping("/exam/draft")
    public ResponseEntity<?> draftExam(@RequestBody Map<String, Object> body) {
        return handle(() -> author.draftExam(body, str(body, "database_name")));
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

    // ── Hình minh họa giao diện ────────────────────────────────────

    /**
     * Body: {de_bai, instruction?} → {mockups:[{id,title,svg}]}. AI đọc mục "3. Hợp đồng giao diện"
     * của đề bài, mô tả từng màn hình bằng JSON có cấu trúc (KHÔNG tự vẽ SVG — dễ ra XML hỏng);
     * server vẽ SVG thật bằng {@link com.example.grader.service.MockupRenderer}, tất định, luôn
     * hợp lệ. "instruction" (tuỳ chọn) là lời giáo viên nhờ AI sửa lại cách vẽ — vẽ LẠI TỪ ĐẦU
     * theo yêu cầu mới, không phải vá đè lên bản cũ.
     */
    @PostMapping("/exam/mockup")
    public ResponseEntity<?> proposeMockup(@RequestBody Map<String, Object> body) {
        return handle(() -> {
            List<com.example.grader.service.HandoutDocument.Mockup> mockups =
                    author.proposeMockups(str(body, "de_bai"), str(body, "instruction"));
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (var m : mockups) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", m.id());
                row.put("title", m.title());
                row.put("svg", m.svg());
                out.add(row);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("mockups", out);
            return res;
        });
    }

    // ── Chung ────────────────────────────────────────────────────

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
