package com.example.grader;

import com.example.grader.controller.ResultController;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingOutcome;
import com.example.grader.entity.GradingStatus;
import com.example.grader.repository.ExamResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.List;
import java.util.Map;

import static com.example.grader.VaiDuongDan.coNhom;
import static com.example.grader.VaiDuongDan.cua;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Bản người chấm phục vụ đúng những đường nào.
 *
 * <p>Cắt vai bằng cách giấu nút trên giao diện thì API vẫn mở — ai gõ thẳng địa chỉ là vào
 * được. Ở đây đọc BẢNG ĐƯỜNG DẪN THẬT mà Spring dựng lúc khởi động, không đọc mã nguồn rồi
 * suy ra. Kiểm luôn một ca dễ sai: /api/exam-setup bị tách làm hai controller (phần chung và
 * phần riêng của giảng viên), nên phải chắc bản này còn /list mà không có đường soạn đề nào.
 */
@SpringBootTest
@ActiveProfiles({"test", "nc"})
class VaiDuongDanNguoiChamTest {

    @Autowired RequestMappingHandlerMapping mapping;
    @Autowired ExamResultRepository results;
    @Autowired ResultController controller;

    @Test
    @Transactional
    void lichSuVanDocDuocDiemMayVaDiemTayQuaProjection() {
        ExamResult row = new ExamResult();
        row.setStudentId("HE_HISTORY");
        row.setExamId("PE_HISTORY_PROJECTION");
        row.setStatus(GradingStatus.DONE);
        row.setScore(6.5f);
        row.setManualScore(8f);
        row.setPreviousScore(5f);
        row.setResultJson("{}");
        row.setManualJson("{\"criteria\":[{\"points\":2,\"maxPoints\":2},{\"points\":1,\"maxPoints\":2}]}");
        results.saveAndFlush(row);

        List<?> history = assertInstanceOf(List.class, controller.getExamHistory(row.getExamId()).getBody());
        assertEquals(1, history.size());
        Map<?, ?> entry = assertInstanceOf(Map.class, history.get(0));
        assertEquals("HE_HISTORY", entry.get("studentId"));
        assertEquals(6.5f, entry.get("score"));
        assertEquals(8f, entry.get("manualScore"));
        assertEquals(5f, entry.get("previousScore"));
        assertEquals(GradingOutcome.SCORED, entry.get("outcome"));
        assertEquals(true, entry.get("hasJson"));
        assertEquals(1, entry.get("manualPass"));
        assertEquals(2, entry.get("manualTotal"));
        assertFalse(entry.containsKey("resultJson"));
        assertFalse(entry.containsKey("manualJson"));
        assertFalse(entry.containsKey("hasFeedback"));
    }

    @Test
    void chiCoPhanChamBaiVaMoiTruongCham() {
        Set<String> co = cua(mapping);

        assertTrue(coNhom(co, "/api/batch"), "phải có chấm bài");
        assertTrue(coNhom(co, "/api/results"), "phải có kết quả chấm");
        assertFalse(coNhom(co, "/api/feedback"), "đã bỏ hệ thống nhận xét AI");
        assertFalse(co.contains("/api/results/exam/{examId}/grading-sheet"), "đã bỏ phiếu chấm tay");
        assertFalse(co.contains("/api/batch/submission/{examId}/{studentId}/files"), "đã bỏ xem file bài nộp");
        assertFalse(co.contains("/api/batch/testcase/{examId}/{studentId}/files"), "đã bỏ xem file testcase");
        assertTrue(coNhom(co, "/api/grading-runtime"), "phải có cấu hình hiệu năng");
        assertTrue(coNhom(co, "/api/grading-env"), "Thư viện chấm là của chung, phải có");
        assertTrue(co.contains("/api/statistics/exams"),
                "ô chọn bộ đề của Lịch sử chấm gọi đường này");
        assertTrue(co.contains("/api/exam-setup/list"), "màn Chấm tự động cần danh sách đề");
        assertTrue(co.contains("/api/exam-setup/nhap-goi"), "phải có đường nhập gói bàn giao");
        // Màn Quản lý bộ testcase chỉ có hai việc: nhận gói và xóa bộ. Đường xóa từng nằm trong
        // controller riêng của giảng viên, nên bản này nhận nhầm một gói là kẹt luôn với nó.
        assertTrue(co.contains("/api/exam-setup/{examId}"), "phải có đường xóa bộ testcase");

        assertFalse(coNhom(co, "/api/behavior-authoring"), "không được có màn soạn bộ chấm");
        // Nhánh AI ra đề đã gộp vào (14/9/2026). Toàn bộ chức năng đó thuộc về giảng viên, nhưng
        // nó được viết TRƯỚC khi tách vai nên AiAuthorController không mang @Profile nào — để
        // nguyên là bản này có luôn /api/ai/settings (nơi giữ khoá API) và cả đường gọi mô hình
        // sinh đề. Git gộp trót lọt, không một dòng cảnh báo; chỉ dòng dưới đây mới nói ra, và nó
        // được đặt sẵn TỪ TRƯỚC lần gộp đúng vì lý do đó.
        assertFalse(coNhom(co, "/api/ai"),
                "soạn đề bằng AI là việc của giảng viên: gộp nhánh AI thì nhớ gắn "
                        + "@Profile(Vai.GIANG_VIEN) cho AiAuthorController");
        assertFalse(co.contains("/api/exam-setup/{examId}/starter-check"),
                "kiểm đồng bộ khung phát là việc của giảng viên");
        assertFalse(co.contains("/api/exam-setup/{examId}/xuat-goi"),
                "xuất gói bàn giao là việc của giảng viên");
        assertFalse(co.contains("/api/exam-setup/upload-testcase"),
                "đường tạo đề của giảng viên không được lọt sang");
    }
}
