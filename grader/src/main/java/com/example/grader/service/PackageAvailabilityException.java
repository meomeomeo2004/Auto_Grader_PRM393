package com.example.grader.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Báo gói thiếu để người soạn chủ động mở Thư viện chấm, không sửa ảnh dùng chung khi upload. */
public final class PackageAvailabilityException extends IllegalArgumentException {
    private final Map<String, Object> missing;

    public PackageAvailabilityException(Map<String, Object> missing) {
        this(missing, "Golden dùng package chưa có trong ảnh chấm: " + String.join(", ", missing.keySet())
                + ". Mở Thư viện chấm để thêm gói rồi tải Golden lại.");
    }

    /**
     * Lời báo riêng cho từng cửa. Hai bên vào từ hai đường khác nhau nên câu "tải lại" cũng
     * phải khác: bên giảng viên tải lại Golden, bên người chấm nạp lại gói bàn giao. Chỉ đường
     * sai chỗ thì người dùng đi tìm một cái nút không có trên màn của họ.
     */
    public PackageAvailabilityException(Map<String, Object> missing, String message) {
        super(message);
        this.missing = new LinkedHashMap<>(missing);
    }

    public Map<String, Object> response() {
        List<String> names = List.copyOf(missing.keySet());
        List<Map<String, String>> specs = missing.entrySet().stream()
                .map(entry -> Map.of(
                        "name", entry.getKey(),
                        // Git/path là khai báo cấu trúc, không phải version để điền vào ô pub version.
                        "version", entry.getValue() instanceof String value ? value : ""))
                .toList();
        // KHONG tra "library_url" nua: frontend tu dung link tu missing_package_specs de mang
        // theo ca version, con truong nay chi co ten goi nen khong ai doc toi (do 19/9).
        return Map.of("error", getMessage(), "missing_packages", names,
                "missing_package_specs", specs);
    }
}
