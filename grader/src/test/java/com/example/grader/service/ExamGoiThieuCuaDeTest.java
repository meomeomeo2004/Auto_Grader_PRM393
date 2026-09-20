package com.example.grader.service;

import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Chấm được" là TÍNH SỐNG chứ không phải dấu đóng lúc nhận gói.
 *
 * <p>Cửa chặn ở khâu nạp chỉ là ảnh chụp một thời điểm; ảnh chấm đổi được sau đó — màn Thư viện
 * chấm có nút gỡ gói rồi dựng lại ảnh. Trước 19/9 cờ {@code gradable} đóng cứng {@code true} nên
 * nhánh "chưa chấm được" là mã chết, và ca gỡ gói hỏng hoàn toàn im lặng.
 */
class ExamGoiThieuCuaDeTest {

    @TempDir Path temp;

    private ExamService dichVu(String hopDong) throws Exception {
        Path tc = Files.createDirectories(temp.resolve("exams/DE/testcase"));
        if (hopDong != null) Files.writeString(tc.resolve("contract.json"), hopDong, StandardCharsets.UTF_8);
        ExamService s = new ExamService();
        // examsRoot() dựng từ templateDir trước rồi mới tới examsDir — thiếu cái đầu là NPE.
        ReflectionTestUtils.setField(s, "templateDir", Files.createDirectories(temp.resolve("tmpl")).toString());
        ReflectionTestUtils.setField(s, "examsDir", temp.resolve("exams").toString());
        // Không có bản ghi đề: testcaseDirOf rơi về đường quét đĩa, đúng hình dạng bên người
        // chấm sau khi nhập gói. Vẫn phải tiêm repo, nếu không nó NPE ngay dòng đầu.
        ExamRepository repo = mock(ExamRepository.class);
        when(repo.findByExamId(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(s, "examRepository", repo);
        return s;
    }

    @Test
    void deKhongCoThuMucTestcaseThiKhongKetLuanThieu() throws Exception {
        ExamService s = dichVu(HOP_DONG);

        assertEquals(List.of(), s.thieuGoiCuaDe("DE_KHONG_TON_TAI", Set.of("flutter")));
    }

    private static final String HOP_DONG =
            "{\"allowed_packages\":[\"flutter\",\"sqflite\",\"dio\",\"go_router\"]}";

    @Test
    void anhChamThieuGoiThiKeDungTenDangThieu() throws Exception {
        List<String> ra = dichVu(HOP_DONG).thieuGoiCuaDe("DE", Set.of("flutter", "sqflite", "path"));

        assertEquals(List.of("dio", "go_router"), ra);
    }

    @Test
    void anhChamDuGoiThiKhongThieuGi() throws Exception {
        List<String> ra = dichVu(HOP_DONG)
                .thieuGoiCuaDe("DE", Set.of("flutter", "sqflite", "dio", "go_router", "intl"));

        assertEquals(List.of(), ra);
    }

    /** Ca quan trọng nhất: Docker tắt → tập rỗng → phải là "không biết", không phải "thiếu hết". */
    @Test
    void khongDocDuocAnhChamThiKhongKetLuanThieu() throws Exception {
        assertEquals(List.of(), dichVu(HOP_DONG).thieuGoiCuaDe("DE", Set.of()),
                "Docker tắt mà quy về thiếu hết là chặn oan MỌI bộ đề");
    }

    @Test
    void deChuaCoHopDongThiKhongKetLuanThieu() throws Exception {
        assertEquals(List.of(), dichVu(null).thieuGoiCuaDe("DE", Set.of("flutter")));
    }

    @Test
    void hopDongHongThiKhongKetLuanThieu_khongNem() throws Exception {
        // Chặn oan vì JSON vỡ là sai chỗ: khâu publish/nạp mới chịu trách nhiệm chặn hợp đồng sai.
        assertEquals(List.of(), dichVu("{khong-phai-json").thieuGoiCuaDe("DE", Set.of("flutter")));
    }

    @Test
    void docCaRangBuocPhienBanOHopDongDoiMoi() throws Exception {
        ExamService s = dichVu("{\"allowed_packages\":[\"dio\"],"
                + "\"allowed_package_specs\":{\"dio\":\"^5.7.0\"}}");

        assertEquals(List.of("dio"), s.thieuGoiCuaDe("DE", Set.of("flutter")));
    }
}
