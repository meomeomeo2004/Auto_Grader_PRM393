package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Danh sách đề được gộp từ HAI nguồn: bản ghi trong cơ sở dữ liệu, và thư mục nằm sẵn trong
 * exams/ trên đĩa. Phép quét đĩa là di sản của thời một hệ thống làm cả hai việc — soạn xong
 * thì thư mục có đấy, liệt kê luôn cho tiện.
 *
 * <p>Sang bản người chấm thì nó sai về nguyên tắc: bộ chấm chỉ được vào bằng gói bàn giao, và
 * chính lúc nhập gói mới có dấu kiểm đồng bộ khung phát cùng phép đối chiếu thư viện. Một thư
 * mục tự xuất hiện trong exams/ mà cũng lên danh sách là đường vòng qua cả hai khâu đó — tệ
 * hơn nữa, nó khiến người dùng tưởng khâu bàn giao đã chạy được trong khi chưa hề chạy.
 */
@SpringBootTest
@ActiveProfiles({"test", "nc"})
class QuetThuMucDeNguoiChamTest {

    @Autowired ExamService examService;

    @DynamicPropertySource
    static void chiDuong(DynamicPropertyRegistry r) throws Exception {
        String duong = QuetThuMucDe.duong();
        r.add("grader.exams-dir", () -> duong);
    }

    @Test
    void khongThayDeChiCoTrenDia() {
        assertFalse(QuetThuMucDe.coTrongDanhSach(examService.listExams()),
                "bộ chấm chỉ được vào bằng gói bàn giao, không phải bằng cách bỏ thư mục vào exams/");
    }
}
