package com.example.grader.service;

import com.example.grader.dto.ExamOption;
import com.example.grader.repository.ExamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Sau khi tách hai vai (13/9/2026) màn hình Thống kê bị bỏ, nên lớp này chỉ còn đúng một việc:
 * trả danh sách đề đã có bài chấm cho ô chọn bộ đề của trang Lịch sử chấm. Tên lớp và đường
 * /api/statistics/exams giữ nguyên vì frontend đang gọi đúng đường đó.
 */
@Service
public class StatisticsService {

    @Autowired private ExamRepository examRepo;

    // Flag Pattern: đọc thẳng cờ has_results trên bảng exams (có index) → O(log N),
    // không quét/đếm bảng exam_results như trước.
    public List<ExamOption> getExamOptions() {
        List<ExamOption> out = new ArrayList<>();
        examRepo.findByHasResultsTrueOrderByExamNameAsc().forEach(e -> out.add(new ExamOption(
                e.getExamId(),
                e.getExamName() != null ? e.getExamName() : e.getExamId())));
        return out;
    }
}
