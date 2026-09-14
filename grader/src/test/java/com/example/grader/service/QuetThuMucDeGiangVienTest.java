package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mặt kia của {@link QuetThuMucDeNguoiChamTest}: người ra đề vẫn phải thấy bộ mình vừa sinh ra
 * trên đĩa. Cùng một thư mục giả, hai vai cho hai câu trả lời ngược nhau — nếu cả hai cùng
 * đúng hoặc cùng sai thì phép kiểm rỗng, không chứng minh được gì.
 */
@SpringBootTest
@ActiveProfiles({"test", "gv"})
class QuetThuMucDeGiangVienTest {

    @Autowired ExamService examService;

    @DynamicPropertySource
    static void chiDuong(DynamicPropertyRegistry r) throws Exception {
        String duong = QuetThuMucDe.duong();
        r.add("grader.exams-dir", () -> duong);
    }

    @Test
    void thayDeChiCoTrenDia() {
        assertTrue(QuetThuMucDe.coTrongDanhSach(examService.listExams()),
                "người ra đề vẫn cần thấy bộ mình vừa sinh ra trên đĩa");
    }
}
