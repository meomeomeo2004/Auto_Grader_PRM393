package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.service.BatchGradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@Profile(Vai.NGUOI_CHAM)   // Chấm bài chỉ có ở bản người chấm.
@RestController
@RequestMapping("/api/batch")
@CrossOrigin(origins = "*")
public class BatchController {

    @Autowired
    private BatchGradingService batchService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "usernames", required = false) List<String> usernames,
            @RequestParam(value = "examId",     defaultValue = "FLUTTER_PE_01") String examId) {

        if (files == null || files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Không có file"));

        try {
            return ResponseEntity.ok(batchService.enqueueBatch(files, usernames, examId, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Nạp THÊM bài vào phiên chấm đã có (thu bài nhiều đợt) — kể cả phiên đã chấm xong. */
    @PostMapping("/{batchId}/add")
    public ResponseEntity<?> addToBatch(
            @PathVariable String batchId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "usernames", required = false) List<String> usernames) {

        if (files == null || files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Không có file"));
        try {
            return ResponseEntity.ok(batchService.addToBatch(files, usernames, batchId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/progress/{batchId}")
    public ResponseEntity<?> getProgress(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(batchService.getBatchProgress(batchId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Các phiên chưa kết thúc để UI phục hồi tiến độ sau khi đổi trang hoặc tải lại. */
    @GetMapping("/active")
    public ResponseEntity<?> active() {
        try {
            return ResponseEntity.ok(batchService.activeBatches());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Tạm ngừng lấy bài mới khỏi hàng đợi; bài đang chạy được phép hoàn tất. */
    @PostMapping("/{batchId}/pause")
    public ResponseEntity<?> pause(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(batchService.pauseBatch(batchId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Tiếp tục một phiên đã tạm dừng. */
    @PostMapping("/{batchId}/resume")
    public ResponseEntity<?> resume(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(batchService.resumeBatch(batchId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** DỪNG phiên chấm: bỏ hàng đợi + giết container đang chạy, GIỮ kết quả đã chấm xong. */
    @PostMapping("/{batchId}/stop")
    public ResponseEntity<?> stop(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(batchService.stopBatch(batchId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** HỦY phiên chấm: dừng rồi XÓA kết quả + file bài nộp của phiên (coi như chưa chấm). */
    @PostMapping("/{batchId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(batchService.cancelBatch(batchId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Thông báo: các phiên chấm gần đây (cho icon chuông ở header). */
    @GetMapping("/recent")
    public ResponseEntity<?> recent() {
        try {
            return ResponseEntity.ok(batchService.recentBatches());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Chấm lại 1 bài từ zip đã lưu (không cần upload lại). Trả batchId mới để theo dõi tiến độ. */
    @PostMapping("/regrade/{examId}/{studentId}")
    public ResponseEntity<?> regrade(@PathVariable String examId, @PathVariable String studentId) {
        try {
            String batchId = batchService.regradeStudent(examId, studentId, AppActor.DEFAULT);
            return ResponseEntity.ok(Map.of("batchId", batchId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Chấm lại TOÀN BỘ bài đã nộp của 1 đề (gộp 1 batch). Trả batchId mới để theo dõi. */
    @PostMapping("/regrade-exam/{examId}")
    public ResponseEntity<?> regradeExam(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(batchService.regradeExam(examId, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Chấm lại NHIỀU bài cùng lúc (gộp 1 batch). Body: { studentIds:[...] }. */
    @SuppressWarnings("unchecked")
    @PostMapping("/regrade-batch/{examId}")
    public ResponseEntity<?> regradeBatch(@PathVariable String examId, @RequestBody Map<String, Object> body) {
        try {
            Object ids = body.get("studentIds");
            List<String> studentIds = (ids instanceof List)
                    ? ((List<Object>) ids).stream().map(String::valueOf).toList() : List.of();
            return ResponseEntity.ok(batchService.regradeStudents(examId, studentIds, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
