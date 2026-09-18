package com.example.grader.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Báo gói thiếu để người soạn chủ động mở Thư viện chấm, không sửa ảnh dùng chung khi upload. */
public final class PackageAvailabilityException extends IllegalArgumentException {
    private final List<String> missing;

    public PackageAvailabilityException(List<String> missing) {
        super("Golden dùng package chưa có trong ảnh chấm: " + String.join(", ", missing)
                + ". Mở Thư viện chấm để thêm gói rồi tải Golden lại.");
        this.missing = List.copyOf(missing);
    }

    public Map<String, Object> response() {
        return Map.of("error", getMessage(), "missing_packages", missing,
                "library_url", "/teacher/libraries?packages=" + URLEncoder.encode(String.join(",", missing), StandardCharsets.UTF_8));
    }
}
