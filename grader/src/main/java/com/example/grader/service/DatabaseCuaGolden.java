package com.example.grader.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Golden có dùng database không, và nếu có thì MỞ file tên gì — đọc thẳng từ {@code lib/**.dart}
 * trong ZIP. ZIP là nguồn sự thật: màn soạn đề không cho khai hay sửa tay điều này.
 *
 * <p>Vì sao phải biết "không dùng": có những đề không cần dữ liệu nào cả (máy tính cộng trừ,
 * đổi đơn vị…). Trước 26/9/2026 hệ thống đòi mọi Golden phải lộ ra đúng một tên {@code .db},
 * nên những đề đó bị chặn ngay ở cửa tải Golden — rồi còn đòi Database ẩn trước khi cho ghi
 * thao tác, đòi Output Database trước khi publish, đòi {@code database_helper.dart} lúc dựng
 * khung phát.
 *
 * <p>Vì sao luật "không dùng" phải CHẶT tới mức này: dây an toàn tên database sinh ra sau vụ
 * 29/8 (hợp đồng ghi hidden.db, Golden mở app.db — chấm sai mà không báo gì). Nếu cứ "không dò
 * ra tên" là coi như không có database, thì một Golden CÓ dùng sqflite nhưng ghép tên động
 * ({@code '$ten.db'}) sẽ lọt sang chế độ không database: người soạn nhìn app trống dữ liệu
 * mà không biết vì sao, đúng loại hỏng im lặng dây này được đặt ra để chặn. Nên chỉ khi
 * {@code lib/} KHÔNG có một dấu vết nào — không import sqflite, không gọi hàm mở database,
 * không cả một chuỗi {@code .db} nào kể cả đường dẫn asset — mới là đề không dùng database.
 */
final class DatabaseCuaGolden {

    /**
     * @param tenFile     tên file {@code .db} TRẦN (không có '/') — đó là file Golden mở ra.
     *                    Chuỗi có '/' như {@code 'assets/hidden.db'} là nguồn NẠP, không phải file mở.
     * @param nhacDb      có ít nhất một chuỗi {@code '*.db'} bất kỳ, kể cả đường dẫn asset.
     * @param dungSqflite import {@code package:sqflite…} hoặc gọi hàm mở/tìm database trong code.
     */
    record KetQua(Set<String> tenFile, boolean nhacDb, boolean dungSqflite) {
        boolean khongDungDatabase() {
            return !nhacDb && !dungSqflite;
        }
    }

    /** Một chuỗi đứng riêng là tên hoặc đường dẫn file .db. */
    private static final Pattern CHUOI_DB = Pattern.compile("['\"]([-A-Za-z0-9_./]+[.]db)['\"]");
    /** Chuỗi import của cả ba gói sqflite dùng chung biến toàn cục databaseFactory. */
    private static final Pattern CHUOI_IMPORT_SQFLITE = Pattern.compile("['\"]package:sqflite[^'\"]*['\"]");
    /** Lời gọi đụng tới database, tìm trong phần CODE (đã bỏ chuỗi và chú thích). */
    private static final Pattern GOI_DATABASE = Pattern.compile(
            "\\b(openDatabase|openReadOnlyDatabase|getDatabasesPath|databaseFactory\\w*)\\b");
    /**
     * Chuỗi hoặc chú thích. Bỏ chú thích để câu "bản cũ mở 'old.db'" hay "không dùng sqflite" trong
     * chú thích không bị tính là dùng database; tách chuỗi ra để tên hàm nằm trong một chuỗi văn
     * bản không bị tính là lời gọi.
     */
    private static final Pattern CHUOI_HOAC_CHU_THICH = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/");

    private DatabaseCuaGolden() {}

    static KetQua quet(Path zip) throws IOException {
        Set<String> ten = new LinkedHashSet<>();
        boolean nhacDb = false;
        boolean dungSqflite = false;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !entryName.endsWith(".dart") || !entryName.contains("lib/")) continue;
                String source = new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);

                StringBuilder code = new StringBuilder(source.length());
                Matcher token = CHUOI_HOAC_CHU_THICH.matcher(source);
                int cuoi = 0;
                while (token.find()) {
                    code.append(source, cuoi, token.start()).append(' ');
                    cuoi = token.end();
                    String t = token.group();
                    if (t.startsWith("//") || t.startsWith("/*")) continue;
                    Matcher db = CHUOI_DB.matcher(t);
                    if (db.matches()) {
                        nhacDb = true;
                        String name = db.group(1);
                        if (!name.contains("/") && !name.contains("\\")) ten.add(name);
                    }
                    if (CHUOI_IMPORT_SQFLITE.matcher(t).matches()) dungSqflite = true;
                }
                code.append(source, cuoi, source.length());
                if (GOI_DATABASE.matcher(code).find()) dungSqflite = true;
            }
        }
        return new KetQua(Collections.unmodifiableSet(ten), nhacDb, dungSqflite);
    }
}
