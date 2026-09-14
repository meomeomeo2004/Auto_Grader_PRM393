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

/** Bản giảng viên phục vụ đúng những đường nào — xem chú thích ở {@link VaiDuongDanNguoiChamTest}. */
@SpringBootTest
@ActiveProfiles({"test", "gv"})
class VaiDuongDanGiangVienTest {

    @Autowired RequestMappingHandlerMapping mapping;

    @Test
    void chiCoPhanRaDeVaMoiTruongCham() {
        Set<String> co = cua(mapping);

        assertTrue(coNhom(co, "/api/behavior-authoring"), "phải có màn soạn bộ chấm");
        assertTrue(coNhom(co, "/api/syllabus"), "phải có Khung năng lực");
        assertTrue(co.contains("/api/exam-setup/{examId}/starter-check"),
                "phải có kiểm đồng bộ khung phát");
        assertTrue(co.contains("/api/exam-setup/{examId}/xuat-goi"),
                "phải có đường xuất gói bàn giao");
        assertTrue(coNhom(co, "/api/grading-env"), "Thư viện chấm là của chung, phải có");
        assertTrue(co.contains("/api/exam-setup/list"),
                "tách /list ra controller chung rồi thì bản này cũng phải còn");
        assertTrue(co.contains("/api/exam-setup/{examId}"),
                "đường xóa bộ chuyển sang controller chung, bản này không được mất theo");
        // Nhánh AI ra đề đã gộp vào (14/9/2026): toàn bộ chức năng đó thuộc giảng viên. Mặt kia —
        // "bản người chấm KHÔNG được có /api/ai" — chặn ở VaiDuongDanNguoiChamTest.
        assertTrue(coNhom(co, "/api/ai"), "soạn đề bằng AI là chức năng của giảng viên");

        assertFalse(coNhom(co, "/api/batch"), "không được có chấm bài");
        assertFalse(coNhom(co, "/api/results"), "không được có kết quả chấm");
        assertFalse(coNhom(co, "/api/feedback"), "không được có nhận xét AI");
        assertFalse(coNhom(co, "/api/statistics"), "không được có danh sách đề đã chấm");
    }
}
