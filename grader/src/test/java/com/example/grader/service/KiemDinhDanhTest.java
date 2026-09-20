package com.example.grader.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phép kiểm này CHẶN publish, nên bài test lo hai phía như nhau: bắt đúng lệch thật, và tuyệt đối
 * không kêu oan trên Golden đúng. Báo oan một lần là lần sau không ai đọc cảnh báo nữa.
 */
class KiemDinhDanhTest {

    /** Dựng theo đúng cách bọc của Golden PE_PRM393_FA26 thật. */
    private static final String KHAI = """
            abstract final class DinhDanh {
              static const String them = 'chi_tieu.them';
              static String loc(String ma) => 'chi_tieu.loc.$ma';
              static String dong(int id) => 'chi_tieu.dong.$id';
              static String xoaDong(int id) => 'chi_tieu.dong.$id.xoa';
              static const String formTieuDe = 'chi_tieu.form.tieu_de';
            }
            """;

    private static final String MAN_HINH = """
            import '../dinh_danh.dart';
            class HomeScreen extends StatelessWidget {
              Widget build(BuildContext context) {
                return Scaffold(
                  floatingActionButton: Semantics(
                    identifier: DinhDanh.them, button: true,
                    child: FloatingActionButton(onPressed: _them, child: const Icon(Icons.add)),
                  ),
                  body: Column(children: [
                    for (final dm in kDanhMuc)
                      Semantics(identifier: DinhDanh.loc(dm), child: ChoiceChip(label: Text(dm))),
                    Semantics(
                      identifier: DinhDanh.dong(e.id ?? 0),
                      child: ListTile(
                        title: Text(e.title),
                        trailing: Semantics(
                          identifier: DinhDanh.xoaDong(e.id ?? 0),
                          label: 'Xoa khoan chi', button: true,
                          child: IconButton(icon: const Icon(Icons.delete), onPressed: _xoa),
                        ),
                      ),
                    ),
                    Semantics(identifier: DinhDanh.formTieuDe, child: TextFormField()),
                  ]),
                );
              }
            }
            """;

    private static Map<String, String> nguon(String khai, String manHinh) {
        Map<String, String> ra = new LinkedHashMap<>();
        if (khai != null) ra.put("lib/dinh_danh.dart", khai);
        if (manHinh != null) ra.put("lib/screens/home_screen.dart", manHinh);
        return ra;
    }

    @Test
    @DisplayName("Golden khai và gắn khớp nhau thì không kêu gì")
    void goldenDungThiImLang() {
        assertThat(KiemDinhDanh.kiem(nguon(KHAI, MAN_HINH))).isEmpty();
    }

    @Test
    @DisplayName("Hằng số khai thêm mà không widget nào dùng thì bị bắt")
    void khaiMaKhongGan() {
        String khaiThua = KHAI.replace("}", "  static const String formLuu = 'chi_tieu.form.luu';\n}");
        List<String> loi = KiemDinhDanh.kiem(nguon(khaiThua, MAN_HINH));
        assertThat(loi).hasSize(1);
        assertThat(loi.get(0)).contains("KHAI MÀ KHÔNG GẮN").contains("DinhDanh.formLuu");
    }

    @Test
    @DisplayName("Gõ thẳng chuỗi vào identifier: thay vì đi qua lớp hằng số thì bị bắt")
    void ganMaKhongKhai() {
        String goTay = MAN_HINH.replace("identifier: DinhDanh.formTieuDe",
                "identifier: 'chi_tieu.form.tieu_de'");
        List<String> loi = KiemDinhDanh.kiem(nguon(KHAI, goTay));
        // formTieuDe thanh khai ma khong gan, va chuoi go tay thanh gan ma khong khai.
        assertThat(loi).hasSize(2);
        assertThat(String.join("\n", loi))
                .contains("GẮN MÀ KHÔNG KHAI")
                .contains("chi_tieu.form.tieu_de");
    }

    @Test
    @DisplayName("Golden thiếu hẳn dinh_danh.dart thì báo đúng một câu, không đổ ra hàng loạt")
    void thieuTepKhai() {
        List<String> loi = KiemDinhDanh.kiem(nguon(null, MAN_HINH));
        assertThat(loi).hasSize(1);
        assertThat(loi.get(0)).contains("lib/dinh_danh.dart");
    }

    @Test
    @DisplayName("Dùng qua biến trung gian vẫn tính là đã gắn — không được kêu oan")
    void dungQuaBienTrungGianKhongKeuOan() {
        String quaBien = MAN_HINH.replace(
                "identifier: DinhDanh.them, button: true,",
                "identifier: maNutThem, button: true,");
        String them = quaBien.replace("class HomeScreen",
                "const String maNutThem = DinhDanh.them;\nclass HomeScreen");
        assertThat(KiemDinhDanh.kiem(nguon(KHAI, them))).isEmpty();
    }

    @Test
    @DisplayName("Chuỗi trong chú thích không bị tính là gắn thật")
    void chuThichKhongTinh() {
        String coChuThich = MAN_HINH.replace("body: Column(children: [",
                "// identifier: 'chi_tieu.ghi_chu' — ví dụ trong chú thích\n      body: Column(children: [");
        assertThat(KiemDinhDanh.kiem(nguon(KHAI, coChuThich))).isEmpty();
    }
}
