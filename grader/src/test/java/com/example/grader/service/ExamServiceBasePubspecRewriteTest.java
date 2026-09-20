package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tái hiện đúng sự cố 18/9: thêm {@code dio} qua trang Thư viện chấm thì
 * {@code flutter pub get} chết với "Expected comment or line break" ở dòng
 * {@code sqflite: >=2.4.2+1 <2.4.3}.
 *
 * <p>Nguyên nhân: ràng buộc dạng khoảng bị ghi lại thành YAML plain scalar, mà dấu {@code >} mở
 * đầu giá trị là ký hiệu block-scalar của YAML.
 *
 * <p>Test cũ chỉ soi {@code formatDependencyLine} trên một YAML hai dòng tự dựng — không chạm tới
 * {@code writeDependenciesBlock}, vốn mới là chỗ ghi lại CẢ FILE và là chỗ đã vỡ. Ở đây dùng
 * {@code grader-base/pubspec.base.yaml} THẬT rồi parse lại toàn bộ tài liệu.
 */
class ExamServiceBasePubspecRewriteTest {

    @TempDir
    Path tempDir;

    @Test
    void themGoiMoiVanGiuNguyenRangBuocKhoangVaCaFileConParseDuoc() throws Exception {
        Path goc = Path.of("..", "grader-base", "pubspec.base.yaml");
        assumeTrue(Files.isRegularFile(goc), "Chưa có grader-base/pubspec.base.yaml để đối chiếu");

        Path ban = tempDir.resolve("pubspec.base.yaml");
        Files.copy(goc, ban);

        // Giữ nguyên mọi gói đang khai, chỉ thêm hai gói người dùng đã thử hôm 18/9.
        Map<String, Object> dangCo = PubspecDependencies.parse(Files.readString(ban, StandardCharsets.UTF_8));
        List<String[]> mong = new ArrayList<>();
        dangCo.forEach((ten, rang) -> {
            if (ten.equals("flutter")) return;          // gói lõi do writeDependenciesBlock tự ghi
            mong.add(new String[]{ten, String.valueOf(rang)});
        });
        mong.add(new String[]{"dio", "^5.7.0"});
        mong.add(new String[]{"go_router", "^14.7.1"});

        var ham = ExamService.class.getDeclaredMethod("writeDependenciesBlock", Path.class, List.class);
        ham.setAccessible(true);
        ham.invoke(new ExamService(), ban, mong);

        String sauKhiGhi = Files.readString(ban, StandardCharsets.UTF_8);

        // 1) CẢ TÀI LIỆU phải parse được — đây chính là phép thử đã vỡ hôm 18/9.
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object taiLieu = new Yaml(new SafeConstructor(options)).load(sauKhiGhi);
        assertTrue(taiLieu instanceof Map, "pubspec sinh ra phải là YAML hợp lệ:\n" + sauKhiGhi);

        // 2) Ràng buộc dạng khoảng phải còn nguyên nghĩa, không biến thành block scalar.
        Map<String, Object> deps = PubspecDependencies.parse(sauKhiGhi);
        assertEquals(">=2.4.2+1 <2.4.3", deps.get("sqflite"),
                "ràng buộc khoảng của sqflite phải sống sót qua vòng ghi lại");
        assertEquals("^5.7.0", deps.get("dio"));
        assertEquals("^14.7.1", deps.get("go_router"));

        // 3) Gói lõi và các section khác không được mất theo.
        assertNotNull(deps.get("flutter"), "flutter là gói lõi, không được biến mất");
        @SuppressWarnings("unchecked")
        Map<String, Object> goc2 = (Map<String, Object>) taiLieu;
        assertTrue(goc2.containsKey("dev_dependencies"), "dev_dependencies phải còn");
        assertTrue(goc2.containsKey("flutter"), "section flutter (assets/uses-material-design) phải còn");
        assertEquals("exam_project", goc2.get("name"));
    }
}
