package com.example.grader.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bảng tick package trên màn soạn đề phải dựng từ ẢNH CHẤM THẬT, không phải từ một danh sách
 * chép tay. Bộ kiểm này chạy trên chính ảnh nền đang cấu hình; không có Docker thì bỏ qua.
 */
@SpringBootTest
@ActiveProfiles("test")
class GoiChoManSoanDeTest {

    @Autowired ExamService exams;

    @SuppressWarnings("unchecked")
    @Test
    void bangTickDungTuAnhChamVaDuChoBoDeHienTai() {
        Map<String, Object> ra = exams.goiChoManSoanDe();
        Assumptions.assumeTrue(Boolean.TRUE.equals(ra.get("image_read")),
                "Chưa đọc được ảnh chấm (Docker tắt hoặc chưa build) — bỏ qua");

        List<Map<String, Object>> khaiThang = (List<Map<String, Object>>) ra.get("direct");
        List<String> keoTheo = (List<String>) ra.get("transitive");
        assertFalse(khaiThang.isEmpty(), "phải đọc được khối dependencies của ảnh");
        // flutter_test nằm ở dev_dependencies. Chỉ đọc khối dependencies thì trang thư viện bảo
        // "flutter_test là gói lõi" mà không bày ra dòng nào, còn bảng chọn bên soạn đề thì thiếu
        // đúng gói mà mọi bài đều cần.
        assertTrue(tenKhaiThang(khaiThang).contains("flutter_test"),
                "flutter_test phải có mặt dù nằm ở dev_dependencies");
        khaiThang.stream()
                .filter(item -> "flutter_test".equals(item.get("name")) || "flutter_lints".equals(item.get("name")))
                .forEach(item -> assertEquals(Boolean.TRUE, item.get("protected"),
                        item.get("name") + " phải khoá, không cho xóa nhầm"));
        assertFalse(keoTheo.isEmpty(), "ảnh luôn có gói kéo theo");

        // Hai nhóm KHÔNG được chồng nhau, nếu không bảng tick hiện một gói hai lần và tick ở
        // chỗ này lại không thấy đổi ở chỗ kia.
        Set<String> tenKhai = tenKhaiThang(khaiThang);
        assertTrue(keoTheo.stream().noneMatch(tenKhai::contains), "hai nhóm phải rời nhau");

        // Mười bốn tên bộ đề PE_PRM393_FA26 đang cho phép phải tick được HẾT, nếu không lưu lại
        // bộ đề đó là bị chặn oan. sqflite_common và riverpod nằm ở nhóm kéo theo.
        Set<String> tickDuoc = new java.util.LinkedHashSet<>(tenKhai);
        tickDuoc.addAll(keoTheo);
        tickDuoc.add("flutter");
        tickDuoc.add("flutter_test");
        List<String> deFA26 = List.of("flutter", "flutter_test", "flutter_riverpod", "riverpod",
                "riverpod_annotation", "path", "sqflite", "sqflite_common", "sqflite_common_ffi",
                "sqflite_common_ffi_web", "path_provider", "sembast_web", "image_picker", "intl");
        List<String> thieu = deFA26.stream().filter(ten -> !tickDuoc.contains(ten)).toList();
        assertTrue(thieu.isEmpty(), "bộ đề FA26 phải tick được hết, đang thiếu: " + thieu);

        // Gói không có trong ảnh thì không xuất hiện ở đâu cả, nên không tick nhầm được.
        for (String ngoai : List.of("dio", "get", "provider", "bloc")) {
            assertFalse(tickDuoc.contains(ngoai), ngoai + " không có trong ảnh, không được bày ra");
        }
    }

    private static Set<String> tenKhaiThang(List<Map<String, Object>> khaiThang) {
        Set<String> ra = new java.util.LinkedHashSet<>();
        khaiThang.forEach(item -> ra.add(String.valueOf(item.get("name"))));
        return ra;
    }
}
