package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.service.BatchGradingService;
import com.example.grader.service.GradingRuntimeSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cấu hình HIỆU NĂNG chấm bài cho trang "Chấm bài tự động": CPU/RAM mỗi container Docker và số
 * bài chấm song song. Đổi được lúc đang chạy, không cần sửa application.yml rồi khởi động lại.
 */
@Profile(Vai.NGUOI_CHAM)   // Cấu hình hiệu năng của máy chấm — chỉ bản người chấm.
@RestController
@RequestMapping("/api/grading-runtime")
@CrossOrigin(origins = "*")
public class GradingRuntimeController {

    @Autowired private GradingRuntimeSettingsService settings;
    @Autowired private BatchGradingService batchService;

    @GetMapping("/settings")
    public ResponseEntity<?> get() {
        try {
            return ResponseEntity.ok(withRuntime(settings.describe()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Lưu cấu hình mới. Body: { cpus, memoryMb, maxConcurrent, timeoutSeconds }. */
    @PostMapping("/settings")
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(withRuntime(settings.update(body, AppActor.DEFAULT)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Về đúng giá trị mặc định trong application.yml. */
    @PostMapping("/settings/reset")
    public ResponseEntity<?> reset() {
        try {
            return ResponseEntity.ok(withRuntime(settings.resetToDefaults(AppActor.DEFAULT)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Gắn thêm tình trạng THỰC TẾ của bộ chấm để người dùng thấy cấu hình đã có hiệu lực chưa. */
    private Map<String, Object> withRuntime(Map<String, Object> described) {
        Map<String, Object> res = new LinkedHashMap<>(described);
        res.put("runtime", batchService.workerStatus());
        return res;
    }
}
