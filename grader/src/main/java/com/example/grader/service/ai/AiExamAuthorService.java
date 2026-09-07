package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trợ lý soạn đề: sinh/sửa đề bài → sinh khung code starter phát cho sinh viên → (mới) sinh app
 * "lời giải mẫu" (Golden Solution) để giáo viên dùng cho quy trình Record–Abstract–Replay.
 *
 * <p>Port từ Grader_App1 nhưng ĐÃ BỎ bước phân tích Item Key/mockup và đề xuất testcase theo
 * template: hai bước đó phụ thuộc {@code TestcaseTemplateService}, một hệ thống Grader_App không
 * còn dùng (đã chuyển sang Golden Solution Record–Abstract–Replay, xem
 * docs/golden-record-abstract-replay.md).
 *
 * <p>Mỗi bước chỉ trả về BẢN ĐỀ XUẤT cho giáo viên sửa và bấm chấp nhận. Không bước nào tự ghi vào
 * đề đang chấm, và app Golden Solution do AI sinh KHÔNG tự động đăng ký — giáo viên phải tự tải
 * .zip lên qua đúng ô "Golden Solution" đã có sẵn để đi qua nguyên vẹn pipeline validate/build.
 */
@Slf4j
@Service
public class AiExamAuthorService {

    @Autowired private LlmService llm;
    @Autowired private StarterRenderer starterRenderer;
    @Autowired private com.example.grader.service.ExamService examService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Đúng khuôn BehaviorArtifactService.validateZip: lib/(...)/ten_file.dart. */
    private static final Pattern GOLDEN_FILE_PATH =
            Pattern.compile("^lib/(?:[a-zA-Z0-9_]+/)*[a-zA-Z0-9_]+\\.dart$");

    /** Đúng bộ package đã đóng băng trong grader-base/pubspec.base.yaml — build không "pub get". */
    private static final List<String> BASE_ALLOWED_PACKAGES = List.of(
            "flutter", "intl", "flutter_riverpod", "riverpod_annotation", "path",
            "sqflite", "sqflite_common_ffi", "sqflite_common_ffi_web",
            "image_picker", "path_provider", "sembast_web");

    // ── Bước 1: đề bài ───────────────────────────────────────────

    public Map<String, Object> draftExam(Map<String, Object> req) {
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.draftSystem()),
                LlmMessage.user(AiPrompts.draftUser(req == null ? Map.of() : req))));
        return examResult(res);
    }

    public Map<String, Object> reviseExam(String deBai, String instruction) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề để sửa.");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("Hãy nhập yêu cầu chỉnh sửa cho AI.");
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.reviseSystem()),
                LlmMessage.user(AiPrompts.reviseUser(deBai, instruction))));
        return examResult(res);
    }

    private Map<String, Object> examResult(JsonNode res) {
        String markdown = text(res.path("de_bai_markdown"), "");
        if (markdown.isBlank())
            throw new IllegalStateException("AI không trả về nội dung đề bài. Hãy thử lại.");
        List<Map<String, Object>> criteria = new ArrayList<>();
        double total = 0;
        for (JsonNode c : res.path("criteria")) {
            double points = c.path("points").asDouble(0);
            total += points;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", text(c.path("name"), ""));
            row.put("points", points);
            criteria.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("de_bai", markdown.trim());
        out.put("summary", text(res.path("summary"), ""));
        out.put("criteria", criteria);
        out.put("total_points", Math.round(total * 10) / 10.0);
        return out;
    }

    // ── Bước 2: khung starter phát cho sinh viên ─────────────────

    /**
     * Dựng khung starter: AI mô tả khung, {@link StarterRenderer} sinh code (thân hàm luôn là TODO),
     * rồi parse thật bằng Dart trong ảnh nền để chắc chắn sinh viên nhận được bộ code build được.
     */
    public Map<String, Object> proposeStarter(String deBai) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để dựng khung starter.");
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.starterSystem()),
                LlmMessage.user(AiPrompts.starterUser(deBai))));
        return starterResult(res);
    }

    /**
     * Sửa khung starter theo lời giáo viên ("thêm màn chi tiết", "bỏ repository", "đổi tên hàm").
     *
     * <p>Hệ quả cần nói rõ trên giao diện: lượt sửa SINH LẠI toàn bộ file, phần giáo viên gõ tay
     * trong trình soạn thảo sẽ bị thay.
     */
    public Map<String, Object> reviseStarter(String deBai, Object rawSpec, String instruction) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để sửa khung starter.");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("Hãy mô tả bạn muốn sửa gì trong khung starter.");
        JsonNode current = mapper.valueToTree(rawSpec);
        if (current == null || current.isMissingNode() || current.isNull() || !current.has("files"))
            throw new IllegalArgumentException("Chưa có khung starter để sửa — hãy sinh khung trước.");

        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.starterReviseSystem()),
                LlmMessage.user(AiPrompts.starterReviseUser(deBai, current.toString(), instruction))));
        return starterResult(res);
    }

    /** Dựng code + kiểm cú pháp cho cả lượt sinh mới lẫn lượt sửa. */
    private Map<String, Object> starterResult(JsonNode res) {
        StarterRenderer.Rendered rendered = starterRenderer.render(res, null, Map.of());
        if (rendered.files().isEmpty())
            throw new IllegalStateException("AI không mô tả được file nào hợp lệ cho khung starter.");

        // pubspec đi kèm khung: thiếu nó sinh viên không chạy nổi dự án, mà tự viết thì hay khai
        // package ảnh chấm không có → bài đúng vẫn 0 điểm.
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map<String, String> project : examService.starterProjectFiles()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", project.get("name"));
            row.put("content", project.get("content"));
            row.put("summary", "pubspec.yaml".equals(project.get("name"))
                    ? "Khai thư viện đúng bằng môi trường chấm (không sửa)"
                    : "Phiên bản đã khoá của môi trường chấm");
            files.add(row);
        }
        files.addAll(rendered.files());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", files);
        out.put("warnings", rendered.warnings());
        out.put("notes", textList(res.path("notes")));
        // Trả kèm bản mô tả để lượt sửa sau nối tiếp từ đúng bản này thay vì dựng lại từ đầu.
        out.put("spec", plain(res));
        out.putAll(checkStarterSyntax(rendered.files()));
        return out;
    }

    /** Kiểm cú pháp toàn bộ khung bằng một lần gọi Docker (bỏ import để ghép được nhiều file). */
    public Map<String, Object> checkStarterSyntax(List<Map<String, Object>> files) {
        SyntaxCheck check = tryCheckSyntax(files);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("syntax_ok", check.ok());
        out.put("syntax_message", check.ok() == null ? check.detail()
                : check.ok() ? "Đã parse bằng Dart trong ảnh chấm: khung starter đúng cú pháp."
                : "Khung starter sai cú pháp: " + check.detail());
        return out;
    }

    // ── Bước 3 (MỚI): app "lời giải mẫu" (Golden Solution) ───────

    /**
     * Sinh app đáp án ĐẦY ĐỦ, chạy được — khác hẳn khung starter, ở đây AI viết code thật.
     * Không tự đăng ký làm Golden Solution: chỉ trả file để giáo viên xem lại rồi tự tải ZIP lên
     * qua đúng ô upload sẵn có (giữ nguyên toàn bộ validate/zip-slip/build pipeline đã kiểm chứng).
     */
    public Map<String, Object> proposeGoldenSolution(String deBai, String databaseName,
                                                      List<String> allowedPackages) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để sinh app lời giải mẫu.");
        List<String> packages = effectivePackages(allowedPackages);
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.goldenSystem(databaseName, packages)),
                LlmMessage.user(AiPrompts.goldenUser(deBai))));
        return goldenResult(res);
    }

    public Map<String, Object> reviseGoldenSolution(String deBai, Object rawFiles, String instruction,
                                                     String databaseName, List<String> allowedPackages) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài.");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("Hãy mô tả bạn muốn AI sửa gì trong app lời giải mẫu.");
        JsonNode current = mapper.valueToTree(rawFiles);
        if (current == null || current.isMissingNode() || current.isNull()
                || !current.isArray() || current.isEmpty())
            throw new IllegalArgumentException("Chưa có app lời giải mẫu để sửa — hãy sinh trước.");

        List<String> packages = effectivePackages(allowedPackages);
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.goldenReviseSystem(databaseName, packages)),
                LlmMessage.user(AiPrompts.goldenReviseUser(deBai, current.toString(), instruction))));
        return goldenResult(res);
    }

    /**
     * Danh sách package giáo viên đã khai (ô "Package được phép dùng" ở trang Behavior Authoring)
     * là GỢI Ý/thu hẹp, KHÔNG BAO GIỜ được nới rộng ra ngoài bộ đã đóng băng sẵn ở ảnh build —
     * import package không có ở đó làm Docker build lỗi âm thầm, không có cảnh báo sớm nào khác.
     */
    private List<String> effectivePackages(List<String> allowedPackages) {
        if (allowedPackages == null || allowedPackages.isEmpty()) return BASE_ALLOWED_PACKAGES;
        List<String> filtered = new ArrayList<>();
        for (String p : allowedPackages) {
            if (p != null && BASE_ALLOWED_PACKAGES.contains(p.trim())) filtered.add(p.trim());
        }
        return filtered.isEmpty() ? BASE_ALLOWED_PACKAGES : filtered;
    }

    private Map<String, Object> goldenResult(JsonNode res) {
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        boolean hasMain = false;
        for (JsonNode f : res.path("files")) {
            String path = text(f.path("path"), "").replace('\\', '/').trim();
            String content = f.path("content").asText("");
            if (!GOLDEN_FILE_PATH.matcher(path).matches()) {
                warnings.add("Bỏ file có đường dẫn không hợp lệ: " + path);
                continue;
            }
            if (!paths.add(path)) {
                warnings.add("Bỏ file trùng đường dẫn: " + path);
                continue;
            }
            if (content.isBlank()) {
                warnings.add("Bỏ file rỗng: " + path);
                continue;
            }
            if ("lib/main.dart".equals(path)) hasMain = true;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path);
            row.put("content", content);
            files.add(row);
        }
        if (files.isEmpty())
            throw new IllegalStateException("AI không mô tả được file nào hợp lệ cho app lời giải mẫu.");
        if (!hasMain)
            throw new IllegalStateException("AI không sinh lib/main.dart — bắt buộc phải có file này "
                    + "để hệ thống build Golden Solution. Hãy thử lại hoặc nhờ AI sửa.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", files);
        out.put("warnings", warnings);
        out.put("notes", textList(res.path("notes")));
        // Danh sách file cũng chính là "bản mô tả" để lượt sửa sau nối tiếp — không cần renderer
        // riêng như starter, vì ở đây AI viết code thật chứ không chỉ mô tả khung.
        out.put("spec", files);
        out.putAll(checkGoldenSyntax(files));
        return out;
    }

    /**
     * Kiểm cú pháp app lời giải mẫu — TÁI DÙNG đúng cơ chế của {@link #checkStarterSyntax}
     * (Docker + {@code dart format}). CHỈ bắt lỗi cú pháp, không đảm bảo app build/chạy được — bắt
     * buộc phải tải lên ô "Golden Solution" rồi "Build & mở Golden" mới biết chắc.
     */
    public Map<String, Object> checkGoldenSyntax(List<Map<String, Object>> files) {
        SyntaxCheck check = tryCheckSyntax(files);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("syntax_ok", check.ok());
        out.put("syntax_message", check.ok() == null ? check.detail()
                : check.ok() ? "Đã parse bằng Dart trong ảnh chấm: app lời giải mẫu đúng cú pháp. "
                        + "Đây MỚI chỉ là kiểm cú pháp — hãy tải lên ô \"Golden Solution\" rồi bấm "
                        + "\"Build & mở Golden\" để biết app có thật sự build/chạy được không."
                : "App lời giải mẫu sai cú pháp: " + check.detail());
        return out;
    }

    /** @param ok null = chưa kiểm được (không có Docker); true/false = kết quả kiểm cú pháp thật. */
    private record SyntaxCheck(Boolean ok, String detail) {}

    private SyntaxCheck tryCheckSyntax(List<Map<String, Object>> files) {
        StringBuilder source = new StringBuilder();
        for (Map<String, Object> file : files) {
            // CHỈ ghép file .dart: pubspec.yaml đi kèm mà lọt vào đây thì trình phân tích Dart báo
            // sai cú pháp cho một file YAML hoàn toàn đúng.
            if (!String.valueOf(file.get("path")).toLowerCase().endsWith(".dart")) continue;
            for (String line : String.valueOf(file.get("content")).split("\n", -1)) {
                if (line.startsWith("import ") || line.startsWith("export ")) continue;
                source.append(line).append('\n');
            }
            source.append('\n');
        }
        try {
            String problem = examService.checkDartSyntax(source.toString());
            return new SyntaxCheck(problem == null, problem);
        } catch (Exception e) {
            return new SyntaxCheck(null, "Chưa kiểm tra được cú pháp (" + e.getMessage()
                    + "). Hãy mở Docker rồi kiểm tra lại nếu muốn chắc chắn.");
        }
    }

    /**
     * Đổi cây JsonNode (Jackson 2) sang Map/List thuần trước khi trả cho client.
     *
     * <p>Classpath có CẢ Jackson 2 lẫn Jackson 3 (xem CLAUDE.md). Bộ chuyển đổi HTTP của Spring
     * Boot 4 là Jackson 3, nó không nhận ra JsonNode của Jackson 2 nên serialize như một bean:
     * client nhận về {"array":false,"boolean":false,"nodeType":"OBJECT",…} thay vì nội dung thật.
     */
    private Object plain(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        return mapper.convertValue(node, Object.class);
    }

    private List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String s = n.asText("").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        String s = node.asText("").trim();
        return s.isEmpty() ? fallback : s;
    }
}
