package com.example.grader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.example.grader.service.GradingDiagnosticException.Origin.STUDENT;

/**
 * Cổng dependency trước khi compile bài sinh viên.
 *
 * <p>Runtime project luôn có tên {@code exam_project} vì Docker dùng package graph đã cache.
 * Import nội bộ theo tên starter được đổi sang {@code exam_project}; package ngoài danh sách
 * đề cho phép thì dừng chấm tự động và chuyển cho giảng viên chấm tay.</p>
 */
final class SubmissionPackagePolicy {

    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?m)^(?:\\x{FEFF})?\\s*(?:import|export)\\s+[^;]*;");
    private static final Pattern PACKAGE_URI = Pattern.compile(
            "['\"]package:([A-Za-z_][A-Za-z0-9_]*)/([^'\"]+)['\"]");

    /** Fallback cho đề cũ chưa lưu policy; đúng với dependency có sẵn trong grading-base. */
    private static final Set<String> BASE_PACKAGES = Set.of(
            "flutter", "flutter_test", "flutter_riverpod", "riverpod", "riverpod_annotation",
            "path", "sqflite", "sqflite_common", "sqflite_common_ffi",
            "sqflite_common_ffi_web", "path_provider", "sembast_web", "image_picker", "intl");

    private final ObjectMapper mapper = new ObjectMapper();

    record Policy(Set<String> allowedPackages, Set<String> localPackageNames, boolean explicit) {}

    Policy load(String testcasePath) {
        Set<String> allowed = new LinkedHashSet<>(BASE_PACKAGES);
        Set<String> localNames = new LinkedHashSet<>(Set.of("exam_project"));
        boolean explicit = false;
        try {
            if (testcasePath == null || testcasePath.isBlank()) {
                return new Policy(allowed, localNames, false);
            }
            Path file = Path.of(testcasePath).resolve("contract.json");
            if (!Files.exists(file)) return new Policy(allowed, localNames, false);
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode configuredAllowed = root.get("allowed_packages");
            if (configuredAllowed != null && configuredAllowed.isArray()) {
                allowed.clear();
                allowed.add("flutter");
                configuredAllowed.forEach(node -> addPackageName(allowed, node.asText("")));
                explicit = true;
            }
            JsonNode configuredLocal = root.get("local_package_names");
            if (configuredLocal != null && configuredLocal.isArray()) {
                configuredLocal.forEach(node -> addPackageName(localNames, node.asText("")));
            }
        } catch (Exception ignored) {
            // Policy hỏng không được âm thầm cấm nhầm bài; validator lúc publish chịu trách nhiệm chặn JSON sai.
        }
        return new Policy(Set.copyOf(allowed), Set.copyOf(localNames), explicit);
    }

    /** Kiểm tra package ngoài trước, sau đó mới chuẩn hóa import nội bộ trên bản giải nén tạm. */
    void validateAndNormalize(Path lib, Policy policy) throws Exception {
        Map<String, List<String>> externalUses = new LinkedHashMap<>();
        List<Path> dartFiles;
        try (Stream<Path> files = Files.walk(lib)) {
            dartFiles = files.filter(path -> path.toString().endsWith(".dart")).toList();
        }

        for (Path file : dartFiles) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            // Do not treat examples left in comments as real dependencies. Keep string
            // literals intact because the package URI itself is a string literal.
            String searchableSource = stripDartComments(source);
            Matcher declaration = DIRECTIVE.matcher(searchableSource);
            Matcher matcher = PACKAGE_URI.matcher(searchableSource);
            StringBuffer normalized = new StringBuffer();
            int copiedUntil = 0;
            // Dart chọn URI ở nhánh điều kiện lúc chạy; mọi nhánh import/export đều phải thuộc policy.
            while (declaration.find()) {
                matcher.region(declaration.start(), declaration.end());
                while (matcher.find()) {
                    String packageName = matcher.group(1);
                    String packagePath = matcher.group(2);
                    boolean allowed = policy.allowedPackages().contains(packageName);
                    boolean local = "exam_project".equals(packageName)
                            || policy.localPackageNames().contains(packageName)
                            || Files.exists(lib.resolve(packagePath).normalize());

                    if (!allowed && !local) {
                        externalUses.computeIfAbsent(packageName, ignored -> new ArrayList<>())
                                .add(lib.relativize(file).toString().replace('\\', '/') + ":" + packagePath);
                    }
                    normalized.append(source, copiedUntil, matcher.start());
                    if (local && !"exam_project".equals(packageName)) {
                        String replacement = source.substring(matcher.start(), matcher.end()).replaceFirst(
                                "package:" + Pattern.quote(packageName) + "/", "package:exam_project/");
                        normalized.append(replacement);
                    } else {
                        normalized.append(source, matcher.start(), matcher.end());
                    }
                    copiedUntil = matcher.end();
                }
            }
            normalized.append(source, copiedUntil, source.length());
            String fixed = normalized.toString();
            if (!fixed.equals(source)) Files.writeString(file, fixed, StandardCharsets.UTF_8);
        }

        if (!externalUses.isEmpty()) {
            String packages = String.join(", ", externalUses.keySet());
            String evidence = externalUses.entrySet().stream()
                    .map(entry -> entry.getKey() + " tại " + String.join(", ", entry.getValue()))
                    .reduce((a, b) -> a + "; " + b).orElse("");
            // Đề phát cho sinh viên một khung có sẵn pubspec: thêm package ngoài là làm sai
            // hướng dẫn, bằng chứng nằm ngay trong lib/. Đây là KẾT QUẢ 0 điểm, không phải sự cố
            // cần người chấm can thiệp — nên manualReview = false.
            throw new GradingDiagnosticException(
                    "EXTERNAL_PACKAGE",
                    STUDENT,
                    "DEPENDENCY_PREFLIGHT",
                    false,
                    "Bài dùng package ngoài danh sách đề cho phép: " + packages
                            + ". Chi tiết: " + evidence);
        }
    }

    private static void addPackageName(Set<String> target, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) target.add(name);
    }

    /** Removes Dart comments while preserving offsets and quoted import URIs. */
    private static String stripDartComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    out.append(current);
                } else out.append(' ');
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    out.append("  ");
                    i++;
                    blockComment = false;
                } else out.append(current == '\n' || current == '\r' ? current : ' ');
                continue;
            }
            if (singleQuote || doubleQuote) {
                out.append(current);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (singleQuote && current == '\'') singleQuote = false;
                else if (doubleQuote && current == '"') doubleQuote = false;
                continue;
            }
            if (current == '/' && next == '/') {
                out.append("  ");
                i++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                out.append("  ");
                i++;
                blockComment = true;
            } else {
                out.append(current);
                if (current == '\'') singleQuote = true;
                else if (current == '"') doubleQuote = true;
            }
        }
        return out.toString();
    }
}
