package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bản web của Golden dựng pubspec bằng cách lấy pubspec.base.yaml rồi NỐI thêm khối assets.
 * Từ khi pubspec nền tự khai "assets: - assets/" (để đường chấm nạp được ảnh của đề), phép nối
 * đó đẻ ra HAI khoá `assets:` trong cùng một mapping và yaml chết với "Duplicate mapping key" —
 * build hỏng mà thông báo không nói vì sao. Đây là bộ chặn cho đúng ca đó.
 */
class GoldenRuntimePubspecTest {

    @Test
    void boDungMotKhoiAssetsVaGiuNguyenPhanConLai() {
        String pubspec = """
                name: exam_project
                dependencies:
                  flutter:
                    sdk: flutter
                  sqflite: '>=2.4.2+1 <2.4.3'

                flutter:
                  uses-material-design: true
                  # Anh di kem de thi duoc mount vao /app/assets luc cham.
                  assets:
                    - assets/
                """;
        String sau = GoldenRuntimeService.boKhoiAssets(pubspec);
        assertEquals(0, demKhoiAssets(sau), "phải sạch khoá assets trước khi nối khối mới");
        assertTrue(sau.contains("uses-material-design: true"), "giữ nguyên phần còn lại");
        assertTrue(sau.contains("sqflite: '>=2.4.2+1 <2.4.3'"), "không đụng tới dependencies");
    }

    @Test
    void pubspecNenThatCuaKhoNoiThemKhoiMoiVanChiConMotKhoaAssets() throws Exception {
        Path nen = Path.of("..", "grader-base", "pubspec.base.yaml");
        if (!Files.isRegularFile(nen)) return; // chạy ngoài repo thì bỏ qua
        String goc = Files.readString(nen, StandardCharsets.UTF_8);
        assertEquals(1, demKhoiAssets(goc), "pubspec nền phải khai đúng một khối assets");

        // Mô phỏng đúng phép nối của GoldenRuntimeService.
        String web = GoldenRuntimeService.boKhoiAssets(goc).stripTrailing()
                + System.lineSeparator() + "  assets:" + System.lineSeparator()
                + "    - assets/" + System.lineSeparator();
        assertEquals(1, demKhoiAssets(web), "bản web chỉ được có một khoá assets");
    }

    private int demKhoiAssets(String pubspec) {
        int so = 0;
        for (String dong : pubspec.split(String.valueOf((char) 10), -1)) {
            if (dong.strip().equals("assets:")) so++;
        }
        return so;
    }
}
