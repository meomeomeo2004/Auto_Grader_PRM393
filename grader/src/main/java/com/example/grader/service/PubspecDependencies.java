package com.example.grader.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Đọc khai báo package, không dùng phiên bản đã resolve thay ràng buộc của giảng viên. */
final class PubspecDependencies {
    private PubspecDependencies() {}
    static Map<String, Object> read(Path projectRoot) {
        Path file = projectRoot.resolve("pubspec.yaml");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Thiếu pubspec.yaml trong " + projectRoot.getFileName());
        try { return parse(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Không đọc được pubspec.yaml", e); }
    }

    static Map<String, Object> parse(String yaml) {
        Map<String, Object> deps = khoi(yaml, "dependencies");
        if (deps == null) throw new IllegalArgumentException("pubspec.yaml phải khai dependencies hợp lệ");
        return deps;
    }

    /** Khối dev_dependencies; RỖNG khi không khai — khác dependencies, thiếu nó không phải lỗi. */
    static Map<String, Object> parseDev(String yaml) {
        Map<String, Object> deps = khoi(yaml, "dev_dependencies");
        return deps == null ? Map.of() : deps;
    }

    /** Một khối package trong pubspec; null khi khối đó không có hoặc không phải ánh xạ. */
    private static Map<String, Object> khoi(String yaml, String ten) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object root = new Yaml(new SafeConstructor(options)).load(yaml);
            if (!(root instanceof Map<?, ?> document) || !(document.get(ten) instanceof Map<?, ?> deps)) {
                return null;
            }
            Map<String, Object> result = new TreeMap<>();
            for (var entry : deps.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Tên package không hợp lệ: " + name);
                result.put(name, canonical(entry.getValue()));
            }
            return result;
        } catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException e) { throw new IllegalArgumentException("pubspec.yaml không hợp lệ: " + e.getMessage(), e); }
    }

    static Map<String, Object> readZip(Path file) {
        return parse(docTrongZip(file));
    }

    /** dev_dependencies của chính dự án trong ZIP; rỗng khi không khai. */
    static Map<String, Object> readZipDev(Path file) {
        return parseDev(docTrongZip(file));
    }

    // Chỉ lấy pubspec cùng gốc với lib/main.dart; pubspec của ví dụ/package con không thay thế được.
    static String docTrongZip(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<String> roots = zip.stream().filter(e -> !e.isDirectory()).map(e -> e.getName().replace('\\', '/'))
                    .filter(n -> n.equals("lib/main.dart") || n.endsWith("/lib/main.dart"))
                    .map(n -> n.substring(0, n.length() - "lib/main.dart".length())).toList();
            if (roots.size() != 1) throw new IllegalArgumentException("Golden ZIP phải chứa đúng một dự án có lib/main.dart và pubspec.yaml");
            var entry = zip.getEntry(roots.get(0) + "pubspec.yaml");
            if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("Golden ZIP thiếu pubspec.yaml bên cạnh thư mục lib/");
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("pubspec.yaml vượt quá 1 MB");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) { throw e; }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Không đọc được pubspec.yaml trong Golden ZIP", e); }
    }

    /**
     * Ten package ma ma nguon trong lib/ THAT SU import — de doi chieu voi khoi dependencies.
     *
     * <p>Neo vao dau dong nen dong da bi chu thich bang "//" khong tinh la dung. Khai mot goi
     * ma khong import no thi khung phat bat sinh vien tai ve mot thu vien vo dung, va
     * contract.json cung mo cong cho no — nen dang mot loi canh bao.
     */
    static Set<String> importedInZip(Path file) {
        Set<String> ra = new TreeSet<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var duyet = zip.entries();
            while (duyet.hasMoreElements()) {
                var entry = duyet.nextElement();
                if (entry.isDirectory()) continue;
                String ten = entry.getName().replace('\\', '/');
                if (!ten.endsWith(".dart")) continue;
                if (!ten.equals("lib/main.dart") && !ten.contains("/lib/") && !ten.startsWith("lib/")) continue;
                try (var input = zip.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(2 * 1024 * 1024);
                    var m = GOI_TRONG_IMPORT.matcher(new String(bytes, StandardCharsets.UTF_8));
                    while (m.find()) ra.add(m.group(1));
                }
            }
        } catch (java.io.IOException e) {
            // Khong doc duoc thi coi nhu khong biet: canh bao la viec phu, khong duoc lam hong upload.
            return Set.of();
        }
        return ra;
    }

    private static final java.util.regex.Pattern GOI_TRONG_IMPORT = java.util.regex.Pattern.compile(
            "(?m)^\\s*(?:import|export)\\s+['\"]package:([A-Za-z_][A-Za-z0-9_]*)/");

    private static Object canonical(Object value) {
        if (value == null) return "any";
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new TreeMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof String || value instanceof Number) return String.valueOf(value).trim();
        throw new IllegalArgumentException("Ràng buộc package trong pubspec.yaml không hợp lệ");
    }
}
