package com.example.grader.controller;

import com.example.grader.service.ExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Quản lý THƯ VIỆN môi trường chấm (pubspec.base.yaml của ảnh nền grading-base).
 *  - Đọc (GET): mở cho frontend hiển thị.
 *  - Ghi (POST /apply): ghi pubspec + cập nhật thư viện vào ảnh nền HIỆN CÓ
 *    (docker commit, chạy nền — không tạo ảnh mới), lỗi thì hoàn tác.
 */
@RestController
@RequestMapping("/api/grading-env")
@CrossOrigin(origins = "*")
public class GradingEnvController {

    @Autowired private ExamService examService;

    /** Danh sách package + trạng thái build hiện tại. */
    @GetMapping("/packages")
    public ResponseEntity<?> packages() {
        return ResponseEntity.ok(Map.of(
                "packages", examService.listManagedPackages(),
                "build", examService.buildStatus()));
    }

    /**
     * Mọi package bài sinh viên IMPORT ĐƯỢC trong ảnh chấm, để màn soạn đề bày ra cho tick.
     *
     * <p>Chia hai nhóm vì hai ý nghĩa khác nhau. {@code direct} là thư viện khai thẳng trong
     * pubspec của ảnh, tức thứ đề thường cho phép. {@code transitive} là gói kéo theo: import
     * được nhưng hiếm khi đề nhắc tới, để riêng cho khỏi ngập bảng tick.
     *
     * <p>{@code image_read=false} nghĩa là chưa đọc được ảnh (Docker tắt, chưa build). Lúc đó
     * màn soạn đề phải giữ nguyên danh sách đã lưu chứ không được coi là "ảnh không có gì".
     */
    @GetMapping("/importable-packages")
    public ResponseEntity<?> importablePackages() {
        return ResponseEntity.ok(examService.goiChoManSoanDe());
    }

    /** Trạng thái build (cho frontend poll khi đang build lại). */
    @GetMapping("/build-status")
    public ResponseEntity<?> buildStatus() {
        return ResponseEntity.ok(examService.buildStatus());
    }

    /** Áp dụng danh sách package SỬA ĐƯỢC rồi build lại ảnh nền. Body: { packages:[{name, version?}] }. */
    @SuppressWarnings("unchecked")
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> body) {
        try {
            Object pk = body.get("packages");
            List<Map<String, Object>> desired =
                    (pk instanceof List) ? (List<Map<String, Object>>) pk : List.of();
            return ResponseEntity.ok(examService.applyPackages(desired));
        } catch (IllegalStateException e) {              // đang build dở
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
