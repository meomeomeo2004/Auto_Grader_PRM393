package com.example.grader.controller;

import com.example.grader.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * Màn hình Thống kê đã bỏ khi tách hai vai, nhưng đường /exams thì KHÔNG bỏ được: ô chọn bộ đề
 * của trang Lịch sử chấm đang gọi nó. Giữ nguyên đường dẫn để frontend không phải đổi.
 */
@Profile(Vai.NGUOI_CHAM)   // Danh sách đề đã chấm — chỉ bản người chấm dùng.
@RestController
@RequestMapping("/api/statistics")
@CrossOrigin(origins = "*")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    /** Danh sách đề đã có bài chấm, cho ô chọn bộ đề. */
    @GetMapping("/exams")
    public ResponseEntity<?> exams() {
        return ResponseEntity.ok(statisticsService.getExamOptions());
    }
}
