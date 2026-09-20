package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KhungMainDartTest {

    private static final String GOLDEN = """
            import 'package:flutter/material.dart';

            import 'screens/home_screen.dart';

            void main() {
              runApp(const ExpenseApp());
            }

            class ExpenseApp extends StatelessWidget {
              const ExpenseApp({super.key});

              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: 'Quan ly chi tieu ca nhan',
                  debugShowCheckedModeBanner: false,
                  theme: ThemeData(
                    colorSchemeSeed: const Color(0xFF0F6A63),
                  ),
                  home: const HomeScreen(),
                );
              }
            }
            """;

    @Test
    void mangTheoThemeVaTitleNhungThayHome() {
        String khung = KhungMainDart.sinh(GOLDEN);

        assertTrue(khung.contains("title: 'Quan ly chi tieu ca nhan'"), khung);
        assertTrue(khung.contains("colorSchemeSeed: const Color(0xFF0F6A63)"),
                "theme quyet dinh hinh hoc, phai cap cho sinh vien:\n" + khung);
        assertTrue(khung.contains("debugShowCheckedModeBanner: false"), khung);
        assertTrue(khung.contains("Bat dau lam bai tai day"), khung);
    }

    @Test
    void khongDeLoLoiGiaiVaoKhung() {
        String khung = KhungMainDart.sinh(GOLDEN);

        assertFalse(khung.contains("HomeScreen"), "man hinh cua Golden la loi giai:\n" + khung);
        assertFalse(khung.contains("screens/"), "duong dan thu muc cua Golden cung la goi y:\n" + khung);
        assertFalse(khung.contains("ExpenseApp"), "khung dung ten lop trung tinh:\n" + khung);
    }

    /** Golden khong khai co banner thi khung van phai co, vi anh chup phai giong nhau. */
    @Test
    void tuThemCoBannerKhiGoldenKhongKhai() {
        String khung = KhungMainDart.sinh(GOLDEN.replace("      debugShowCheckedModeBanner: false,\n", ""));

        assertEquals(1, demLan(khung, "debugShowCheckedModeBanner"), khung);
        assertTrue(khung.contains("debugShowCheckedModeBanner: false"), khung);
    }

    /** Golden bat banner thi van ep ve false — khong de anh chup hai ben khac nhau. */
    @Test
    void epBannerVeFalseDuGoldenBat() {
        String khung = KhungMainDart.sinh(GOLDEN.replace("debugShowCheckedModeBanner: false",
                "debugShowCheckedModeBanner: true"));

        assertTrue(khung.contains("debugShowCheckedModeBanner: false"), khung);
        assertFalse(khung.contains("debugShowCheckedModeBanner: true"), khung);
    }

    /** Dau phay long trong ThemeData(...) khong duoc lam vo phep tach tham so. */
    @Test
    void tachDungThamSoLongNhauVaChuoiCoDauPhay() {
        List<String> ts = KhungMainDart.thamSoMaterialApp("""
                MaterialApp(
                  title: 'Ghi chu, viec can lam',
                  theme: ThemeData(useMaterial3: true, colorSchemeSeed: Color(0xFF0F6A63)),
                  routes: <String, WidgetBuilder>{'/a': (_) => const A(), '/b': (_) => const B()},
                  home: const HomeScreen(),
                );
                """);

        assertEquals(4, ts.size(), ts.toString());
        assertEquals("title: 'Ghi chu, viec can lam'", ts.get(0));
        assertTrue(ts.get(1).startsWith("theme: ThemeData("), ts.get(1));
        assertTrue(ts.get(2).startsWith("routes:"), ts.get(2));
        assertEquals("home: const HomeScreen()", ts.get(3));
    }

    /** MaterialApp nam trong chu thich khong duoc tinh la loi goi that. */
    @Test
    void boQuaMaterialAppTrongChuThich() {
        String khung = KhungMainDart.sinh("""
                // return MaterialApp(title: 'cu', home: Cu());
                /* MaterialApp(title: 'cu hon') */
                Widget build(BuildContext context) {
                  return MaterialApp(title: 'that', home: const X());
                }
                """);

        assertTrue(khung.contains("title: 'that'"), khung);
        assertFalse(khung.contains("'cu'"), khung);
    }

    @Test
    void khongTimThayMaterialAppThiBaoLoiChuKhongDoanBua() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> KhungMainDart.sinh("void main() { runApp(const SizedBox()); }\n"));

        assertTrue(e.getMessage().contains("MaterialApp"), e.getMessage());
    }

    private int demLan(String trong, String tim) {
        int n = 0, i = trong.indexOf(tim);
        while (i >= 0) { n++; i = trong.indexOf(tim, i + 1); }
        return n;
    }
}
