package com.example.grader;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;

import static com.example.grader.VaiDuongDan.coNhom;
import static com.example.grader.VaiDuongDan.cua;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void chiCoPhanChamBaiVaMoiTruongCham() {
        Set<String> co = cua(mapping);

        assertTrue(coNhom(co, "/api/batch"), "phải có chấm bài");
        assertTrue(coNhom(co, "/api/results"), "phải có kết quả chấm");
        assertTrue(coNhom(co, "/api/feedback"), "phải có nhận xét AI");
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
