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
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object root = new Yaml(new SafeConstructor(options)).load(yaml);
            if (!(root instanceof Map<?, ?> document) || !(document.get("dependencies") instanceof Map<?, ?> deps)) {
                throw new IllegalArgumentException("pubspec.yaml phải khai dependencies hợp lệ");
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

    // Chỉ lấy pubspec cùng gốc với lib/main.dart; pubspec của ví dụ/package con không thay thế được.
    static Map<String, Object> readZip(Path file) {
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
                return parse(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) { throw e; }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Không đọc được pubspec.yaml trong Golden ZIP", e); }
    }

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
