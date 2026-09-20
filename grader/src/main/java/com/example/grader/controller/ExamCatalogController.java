package com.example.grader.controller;

import com.example.grader.service.BanGiaoService;
import com.example.grader.service.BatchGradingService;
import com.example.grader.service.ExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Phần DÙNG CHUNG của /api/exam-setup, không gắn nhãn vai nên bản nào cũng có.
 *
 * <p>ExamSetupController bên cạnh là của riêng bản giảng viên (tạo, sửa, xuất bản, kiểm đồng bộ
 * khung phát, xuất gói bàn giao). Nhưng ba việc dưới đây thì bản người chấm buộc phải có: đọc
 * danh sách đề để biết chấm đề nào, nạp gói bàn giao, và soi xem ảnh chấm trên máy mình còn
 * thiếu package nào so với đề vừa nhận.
 *
 * <p>Hai controller cùng gốc /api/exam-setup là hợp lệ, miễn không khai trùng đường con: nếu
 * trùng thì bài kiểm bật cả hai vai sẽ chết vì mapping nhập nhằng — đó chính là chỗ nó lộ ra.
 */
@RestController
@RequestMapping("/api/exam-setup")
@CrossOrigin(origins = "*")
public class ExamCatalogController {

    @Autowired
    private ExamService examService;
    @Autowired
    private BanGiaoService banGiaoService;
    @Autowired
    private BatchGradingService batchGradingService;

    /** Danh sách đề đã cấu hình — dùng cho ô chọn đề ở cả hai bản. */
    @GetMapping("/list")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(examService.listExams());
    }

    /**
     * NẠP GÓI BÀN GIAO do bản giảng viên xuất ra.
     *
     * <p>Trả kèm danh sách package mà ảnh chấm trên máy này chưa có, để màn hình cảnh báo ngay
     * thay vì đợi tới lúc chấm mới phát hiện bài sinh viên không biên dịch được.
     */
    @PostMapping(value = "/nhap-goi", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> nhapGoiBanGiao(@RequestPart("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(banGiaoService.nhapGoi(file.getBytes()));
        } catch (com.example.grader.service.PackageAvailabilityException e) {
            // PHẢI đứng trước IllegalArgumentException (nó là con của lớp đó): nhánh dưới chỉ
            // trả mỗi câu chữ, mất danh sách gói và ràng buộc phiên bản mà màn hình cần để mở
            // sẵn đúng những gói đó bên Thư viện chấm.
            return ResponseEntity.badRequest().body(e.response());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Không nạp được gói: " + e.getMessage()));
        }
    }

    /** Gộp phần thiếu của mọi bộ chấm trên máy — màn Thư viện chấm gọi một lần là đủ. */
    @GetMapping("/goi-con-thieu")
    public ResponseEntity<?> goiConThieuTatCa() {
        try {
            return ResponseEntity.ok(examService.goiConThieuTatCa());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đề này đòi package nào mà ảnh chấm trên máy hiện tại chưa có. */
    @GetMapping("/{examId}/goi-con-thieu")
    public ResponseEntity<?> goiConThieu(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.goiConThieuCuaDe(examId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * XÓA HẲN một bộ testcase: thư mục testcase, bài nộp đã lưu, mọi mẻ chấm và mọi kết quả
     * từng chấm bằng bộ đó. Không có thùng rác, không khôi phục được.
     *
     * <p>Phải dừng phiên chấm đang chạy TRƯỚC khi xóa, nếu không worker còn sống sẽ ghi kết
     * quả vào một bộ vừa bị xóa — xem {@link BatchGradingService#dungMoiPhienCuaDe}.
     *
     * <p>Ở bản người chấm đây là đường DUY NHẤT gỡ một bộ đã nhận nhầm, nên nó không thể là
     * của riêng bản giảng viên.
     */
    @DeleteMapping("/{examId}")
    public ResponseEntity<?> xoaBoTestcase(@PathVariable String examId) {
        try {
            int daDungPhien = batchGradingService.dungMoiPhienCuaDe(examId);
            Map<String, Object> ket = new java.util.LinkedHashMap<>(examService.deleteExam(examId));
            ket.put("stoppedBatches", daDungPhien);
            return ResponseEntity.ok(ket);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
