package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Luật chấm TĨNH của một behavior suite (nhóm Kiến trúc + chất lượng mã).
 *
 * Nguồn sự thật là cột behavior_suites.static_rules_json; materializer đọc từ đây để
 * sinh dòng `runner: STATIC_ANALYSIS` trong skills_matrix.json — nhờ vậy republish
 * KHÔNG còn nuốt mất tiêu chí tĩnh như thời G_MA_SACH phải chép tay.
 *
 * LUẬT SẮT (giống tick giao diện): tiêu chí source_pattern phải ĐẠT trên chính
 * Golden Solution. Đề bắt sinh viên dùng Riverpod trong khi đáp án mẫu dùng setState
 * là đề tự mâu thuẫn — chặn ngay lúc lưu và chặn lại lần nữa lúc publish.
 * Việc đánh giá ở đây MÔ PHỎNG đúng ngữ nghĩa glob+regex của static_checks.dart
 * trong container; sửa một bên thì phải sửa bên kia.
 */
@Service
public class StaticRuleService {

    public static final String KIND_LINT = "lint";
    public static final String KIND_SOURCE_PATTERN = "source_pattern";

    private final BehaviorSuiteRepository suites;
    private final BehaviorArtifactService artifacts;
    private final ObjectMapper mapper = new ObjectMapper();

    public StaticRuleService(BehaviorSuiteRepository suites, BehaviorArtifactService artifacts) {
        this.suites = suites;
        this.artifacts = artifacts;
    }

    // ═══════════════════ PRESET ═══════════════════
    // Bộ luật dựng sẵn khớp phiếu chấm thật của giảng viên (MVVM + state management
    // + chất lượng mã). Giáo viên tick trong panel "Kiến trúc & luật tĩnh"; đề nào
    // golden không thỏa (badge đỏ) thì đề đó không được chấm luật ấy.

    private static Map<String, Object> req(String label, List<String> paths, String contains, Integer min, Integer max) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("paths", paths);
        if (contains != null) out.put("contains", contains);
        if (min != null) out.put("min", min);
        if (max != null) out.put("max", max);
        return out;
    }

    private static Map<String, Object> preset(String id, String name, String kind, String lintCode,
                                              Map<String, Object> config, double weight,
                                              String groupId, String groupName, String skillCode,
                                              String moTa) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("kind", kind);
        if (lintCode != null) out.put("lint_code", lintCode);
        if (config != null) out.put("config", config);
        out.put("weight", weight);
        out.put("group_id", groupId);
        out.put("group_name", groupName);
        out.put("skill_code", skillCode);
        out.put("description", moTa);
        return out;
    }

    /** Preset bất biến — FE chỉ hiển thị và tick, không tự chế cấu hình. */
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(preset("ARCH_MODEL", "Tách Model thành file riêng", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(req("Tách Model thành file riêng",
                        List.of("lib/model/**", "lib/models/**", "lib/**/model/**", "lib/**/models/**",
                                "lib/**_model.dart", "lib/**.model.dart",
                                "lib/entity/**", "lib/entities/**", "lib/**/entity/**", "lib/**/entities/**"),
                        null, 1, null))),
                5, "G_KIENTRUC", "Kiến trúc mã nguồn", "PROJ_FOLDER_STRUCTURE",
                "Có file/thư mục model riêng (models/, entity/, *_model.dart) thay vì khai class lẫn trong màn hình."));
        out.add(preset("ARCH_DATA", "Tách tầng dữ liệu khỏi giao diện", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(req("Tách tầng dữ liệu khỏi giao diện",
                        List.of("lib/data/**", "lib/**/data/**",
                                "lib/repository/**", "lib/repositories/**", "lib/**/repository/**", "lib/**/repositories/**",
                                "lib/service/**", "lib/services/**", "lib/**/service/**", "lib/**/services/**",
                                "lib/db/**", "lib/**/db/**", "lib/database/**", "lib/**/database/**",
                                "lib/**_repository.dart", "lib/**_service.dart", "lib/**_store.dart",
                                "lib/**_dao.dart", "lib/**_helper.dart", "lib/**_db.dart", "lib/**_database.dart"),
                        null, 1, null))),
                5, "G_KIENTRUC", "Kiến trúc mã nguồn", "PROJ_FOLDER_STRUCTURE",
                "Truy cập DB/API nằm trong lớp riêng (data/, repository/, *_service.dart, db_helper...) chứ không viết thẳng trong widget."));
        out.add(preset("ARCH_SCREEN", "Tách màn hình thành file riêng", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(req("Tách màn hình thành file riêng",
                        List.of("lib/screen/**", "lib/screens/**", "lib/**/screen/**", "lib/**/screens/**",
                                "lib/view/**", "lib/views/**", "lib/**/view/**", "lib/**/views/**",
                                "lib/page/**", "lib/pages/**", "lib/**/page/**", "lib/**/pages/**",
                                "lib/ui/**", "lib/**/ui/**",
                                "lib/**_screen.dart", "lib/**_page.dart", "lib/**_view.dart"),
                        null, 1, null))),
                5, "G_KIENTRUC", "Kiến trúc mã nguồn", "PROJ_FOLDER_STRUCTURE",
                "Mỗi màn hình một file (screens/, pages/, *_screen.dart) thay vì dồn hết vào main.dart."));
        out.add(preset("ARCH_LOGIC", "Tách logic/ViewModel khỏi màn hình", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(req("Tách logic/ViewModel khỏi màn hình",
                        List.of("lib/logic/**", "lib/**/logic/**",
                                "lib/viewmodel/**", "lib/viewmodels/**", "lib/**/viewmodel/**", "lib/**/viewmodels/**",
                                "lib/view_model/**", "lib/view_models/**", "lib/**/view_model/**", "lib/**/view_models/**",
                                "lib/controller/**", "lib/controllers/**", "lib/**/controller/**", "lib/**/controllers/**",
                                "lib/provider/**", "lib/providers/**", "lib/**/provider/**", "lib/**/providers/**",
                                "lib/bloc/**", "lib/blocs/**", "lib/**/bloc/**", "lib/**/blocs/**",
                                "lib/cubit/**", "lib/**/cubit/**",
                                "lib/usecase/**", "lib/usecases/**", "lib/**/usecases/**",
                                "lib/**_viewmodel.dart", "lib/**_view_model.dart", "lib/**_controller.dart",
                                "lib/**_provider.dart", "lib/**_notifier.dart", "lib/**_bloc.dart",
                                "lib/**_cubit.dart", "lib/**_logic.dart", "lib/**_calculator.dart", "lib/**_usecase.dart"),
                        null, 1, null))),
                5, "G_KIENTRUC", "Kiến trúc mã nguồn", "PROJ_FOLDER_STRUCTURE",
                "Xử lý nghiệp vụ nằm trong lớp riêng (logic/, viewmodels/, controllers/, providers/...) chứ không trộn vào build()."));
        out.add(preset("STATE_RIVERPOD", "Dùng Riverpod quản lý state", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(
                        req("Import Riverpod", List.of("lib/**.dart"),
                                "import\\s+'package:(flutter_riverpod|hooks_riverpod|riverpod)", 1, null),
                        req("Sử dụng Riverpod (widget/provider/ref)", List.of("lib/**.dart"),
                                "(ConsumerWidget|ConsumerStatefulWidget|ProviderScope|ref\\.watch|ref\\.read"
                                        + "|StateNotifierProvider|NotifierProvider|StateProvider|FutureProvider"
                                        + "|StreamProvider|ChangeNotifierProvider)", 1, null))),
                10, "G_KIENTRUC", "Kiến trúc mã nguồn", "STATE_RIVERPOD",
                "Bài phải import flutter_riverpod VÀ thực sự dùng (ConsumerWidget, ref.watch, ...Provider). Chỉ tick khi Golden cũng dùng Riverpod."));
        out.add(preset("LINT_EMPTY_CATCHES", "Không nuốt lỗi — cấm khối catch rỗng", KIND_LINT, "empty_catches",
                null, 5, "G_MA_SACH", "Chất lượng mã nguồn", "CODE_QUALITY_LINT",
                "Luật dart analyze empty_catches trong lib/. Chỉ chấm được khi bài biên dịch."));
        return out;
    }

    // ═══════════════════ ĐỌC / LƯU ═══════════════════

    public Map<String, Object> view(String suiteId) {
        BehaviorSuite suite = require(suiteId);
        List<Map<String, Object>> rules = savedRules(suite);
        List<SourceFile> golden = goldenSources(suiteId);

        List<Map<String, Object>> presets = new ArrayList<>();
        for (Map<String, Object> p : presets()) {
            Map<String, Object> item = new LinkedHashMap<>(p);
            item.put("golden", verdict(p, golden));
            presets.add(item);
        }
        List<Map<String, Object>> saved = new ArrayList<>();
        for (Map<String, Object> r : rules) {
            Map<String, Object> item = new LinkedHashMap<>(r);
            item.put("golden", verdict(r, golden));
            saved.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suite_id", suiteId);
        out.put("golden_available", golden != null);
        out.put("rules", saved);
        out.put("presets", presets);
        return out;
    }

    @Transactional
    public Map<String, Object> save(String suiteId, Map<String, Object> body) {
        BehaviorSuite suite = require(suiteId);
        List<Map<String, Object>> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object raw : list(body.get("rules"))) {
            Map<String, Object> rule = normalizeRule(map(raw));
            String id = (String) rule.get("id");
            if (!seen.add(id)) throw new IllegalArgumentException("Trùng mã luật tĩnh: " + id);
            rules.add(rule);
        }

        // Luật sắt: source_pattern phải đạt trên chính Golden Solution.
        List<SourceFile> golden = goldenSources(suiteId);
        for (Map<String, Object> rule : rules) {
            requireGoldenPass(rule, golden);
        }

        try {
            suite.setStaticRulesJson(mapper.writeValueAsString(Map.of("rules", rules)));
        } catch (Exception e) {
            throw new IllegalStateException("Không mã hóa được luật tĩnh: " + e.getMessage(), e);
        }
        suites.save(suite);
        return view(suiteId);
    }

    /**
     * KIỂM ĐỊNH DANH của Golden trước khi publish: khai mà không gắn, gắn mà không khai.
     *
     * <p>Đặt ở lớp này vì nó giữ bộ đọc duy nhất của mã nguồn Golden ({@code goldenSources});
     * phép so thì nằm riêng ở {@link KiemDinhDanh}.
     *
     * <p>Vì sao chặn ở publish chứ không nhét vào Kiểm Golden: Kiểm Golden là một lượt Docker gần
     * hai phút và nó trả lời câu "bộ chấm chạy đúng trên Golden không". Hai lệch dưới đây KHÔNG làm
     * Golden sai — Golden luôn tự khớp với chính nó — nên bỏ vào đó thì vừa bắt người soạn chờ hai
     * phút cho một phép đọc file, vừa làm báo cáo mất tiêu điểm.
     */
    public void requireDinhDanhNhatQuan(String suiteId) {
        List<String> loi = kiemDinhDanh(suiteId);
        if (!loi.isEmpty()) {
            throw new IllegalStateException(
                    "Định danh của Golden chưa nhất quán:" + System.lineSeparator()
                            + "- " + String.join(System.lineSeparator() + "- ", loi));
        }
    }

    /**
     * Bản KHÔNG ném của phép kiểm trên — dùng để cảnh báo SỚM ngay lúc tải Golden lên.
     *
     * <p>Lúc đó người soạn còn đang mở Golden trong trình soạn thảo, sửa một dòng là xong. Đợi tới
     * publish mới báo thì họ đã ghi hình và chụp oracle xong hết rồi, sửa Golden là phải làm lại
     * từ đầu.
     *
     * <p>Không ném vì tải lên phải THÀNH CÔNG: chặn ngay ở đây thì người soạn không còn Golden nào
     * trên hệ thống để mà đối chiếu, mà Golden lệch định danh vẫn ghi hình được bình thường.
     */
    public List<String> kiemDinhDanh(String suiteId) {
        List<SourceFile> golden = goldenSources(suiteId);
        if (golden == null) return List.of();   // chưa có Golden thì cổng khác đã chặn trước rồi
        Map<String, String> nguon = new LinkedHashMap<>();
        for (SourceFile f : golden) {
            if (!f.relPath().endsWith(".dart")) continue;
            nguon.put(f.relPath(), new String(f.content(), StandardCharsets.UTF_8));
        }
        return KiemDinhDanh.kiem(nguon);
    }

    /** Gọi lại lúc publish: golden có thể đã được upload bản mới sau khi lưu luật. */
    public void requireGoldenCompliance(String suiteId) {
        BehaviorSuite suite = require(suiteId);
        List<Map<String, Object>> rules = savedRules(suite);
        if (rules.isEmpty()) return;
        List<SourceFile> golden = goldenSources(suiteId);
        for (Map<String, Object> rule : rules) {
            requireGoldenPass(rule, golden);
        }
    }

    /**
     * Dòng ma trận cho materializer: mỗi luật đã lưu → một tiêu chí
     * `runner: STATIC_ANALYSIS` đúng shape mà static_checks.dart và merger đọc.
     */
    public Map<String, Map<String, Object>> matrixRows(String suiteId, String suiteCode) {
        BehaviorSuite suite = require(suiteId);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> rule : savedRules(suite)) {
            String instanceId = ExamService.safeId(suiteCode + "_STATIC_" + rule.get("id"), "tiêu chí tĩnh")
                    .toUpperCase(Locale.ROOT);
            boolean lint = KIND_LINT.equals(rule.get("kind"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instance_id", instanceId);
            row.put("runner", "STATIC_ANALYSIS");
            row.put("scenario_code", null);
            row.put("execution_code", null);
            row.put("checkpoint_id", null);
            row.put("skill_code", rule.get("skill_code"));
            row.put("testcase_group", "STATIC");
            row.put("layer", "static");
            row.put("group_id", rule.get("group_id"));
            row.put("group_name", rule.get("group_name"));
            row.put("name", rule.get("name"));
            row.put("difficulty", "basic");
            row.put("weight", rule.get("weight"));
            row.put("static_rule", lint ? rule.get("lint_code") : KIND_SOURCE_PATTERN);
            if (!lint) row.put("static_config", rule.get("config"));
            out.put(instanceId, row);
        }
        return out;
    }

    // ═══════════════════ CHUẨN HÓA & KIỂM TRA ═══════════════════

    private Map<String, Object> normalizeRule(Map<String, Object> raw) {
        String id = text(raw, "id").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (id.isBlank() || id.length() > 40) {
            throw new IllegalArgumentException("Mã luật tĩnh phải là slug A-Z/0-9/_ tối đa 40 ký tự");
        }
        String name = text(raw, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Luật " + id + " thiếu tên hiển thị");
        String kind = text(raw, "kind");
        if (!KIND_LINT.equals(kind) && !KIND_SOURCE_PATTERN.equals(kind)) {
            throw new IllegalArgumentException("Luật " + id + " có kind không hợp lệ: " + kind);
        }
        double weight = number(raw.get("weight"));
        if (weight <= 0 || weight > 100) {
            throw new IllegalArgumentException("Luật " + id + " phải có trọng số trong (0, 100]");
        }
        String groupId = text(raw, "group_id");
        String groupName = text(raw, "group_name");
        if (groupId.isBlank() || groupName.isBlank()) {
            throw new IllegalArgumentException("Luật " + id + " thiếu nhóm điểm (group_id/group_name)");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("kind", kind);
        out.put("weight", weight);
        out.put("group_id", groupId);
        out.put("group_name", groupName);
        out.put("skill_code", text(raw, "skill_code").isBlank() ? "CODE_QUALITY_LINT" : text(raw, "skill_code"));
        if (!text(raw, "description").isBlank()) out.put("description", text(raw, "description"));

        if (KIND_LINT.equals(kind)) {
            String lintCode = text(raw, "lint_code");
            if (lintCode.isBlank()) throw new IllegalArgumentException("Luật lint " + id + " thiếu lint_code");
            out.put("lint_code", lintCode);
        } else {
            Map<String, Object> config = map(raw.get("config"));
            List<Object> require = list(config.get("require"));
            List<Object> anyOf = list(config.get("any_of"));
            if (require.isEmpty() && anyOf.isEmpty()) {
                throw new IllegalArgumentException("Luật " + id + " chưa khai require/any_of");
            }
            require.forEach(item -> validateRequirement(id, map(item)));
            for (Object branch : anyOf) {
                List<Object> reqs = list(branch);
                if (reqs.isEmpty()) throw new IllegalArgumentException("Luật " + id + " có nhánh any_of rỗng");
                reqs.forEach(item -> validateRequirement(id, map(item)));
            }
            out.put("config", config);
        }
        return out;
    }

    private void validateRequirement(String ruleId, Map<String, Object> req) {
        List<Object> paths = list(req.get("paths"));
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Luật " + ruleId + " có yêu cầu không khai paths");
        }
        String contains = text(req, "contains");
        if (!contains.isBlank()) {
            try {
                Pattern.compile(contains, Pattern.MULTILINE);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "Luật " + ruleId + " có regex nội dung không hợp lệ: " + e.getDescription());
            }
        }
    }

    private void requireGoldenPass(Map<String, Object> rule, List<SourceFile> golden) {
        if (KIND_LINT.equals(rule.get("kind"))) return; // lint cần dart analyze — Preflight lo
        if (golden == null) {
            throw new IllegalStateException(
                    "Chưa có Golden Solution để đối chứng luật tĩnh — upload golden trước khi khai luật.");
        }
        Verdict v = evaluate(map(rule.get("config")), golden);
        if (!v.passed) {
            throw new IllegalStateException("Luật tĩnh \"" + rule.get("name")
                    + "\" KHÔNG đạt trên chính Golden Solution (" + v.detail
                    + ") — sửa luật hoặc sửa golden trước.");
        }
    }

    private Map<String, Object> verdict(Map<String, Object> rule, List<SourceFile> golden) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (KIND_LINT.equals(rule.get("kind"))) {
            out.put("passed", null);
            out.put("detail", "Luật lint cần dart analyze — sẽ được xác nhận ở bước chạy thử Golden.");
            return out;
        }
        if (golden == null) {
            out.put("passed", null);
            out.put("detail", "Chưa có Golden Solution để đối chứng.");
            return out;
        }
        Verdict v = evaluate(map(rule.get("config")), golden);
        out.put("passed", v.passed);
        out.put("detail", v.detail);
        return out;
    }

    // ═══════════════════ ĐÁNH GIÁ TRÊN GOLDEN ZIP ═══════════════════
    // Bản Java của phần source_pattern trong static_checks.dart — cùng glob, cùng
    // min/max (chỉ khai max thì min mặc định 0), cùng thứ tự thông điệp.

    record SourceFile(String relPath, byte[] content) {
        String text() { return new String(content, StandardCharsets.UTF_8); }
    }

    private record Verdict(boolean passed, String detail) {}

    /** Đọc các file dưới lib/ trong Golden Solution zip; null nếu chưa có golden. */
    private List<SourceFile> goldenSources(String suiteId) {
        Optional<BehaviorArtifact> active =
                artifacts.activeOptional(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
        if (active.isEmpty()) return null;
        Path zipPath = Path.of(active.get().getStoragePath());
        if (!Files.isRegularFile(zipPath)) return null;
        List<SourceFile> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                // Zip có thể bọc một thư mục gốc — cắt về từ đoạn lib/ đầu tiên,
                // giống hệt việc backend chỉ giải nén lib/ của bài nộp vào /app/lib.
                String rel;
                if (name.startsWith("lib/")) rel = name;
                else {
                    int i = name.indexOf("/lib/");
                    if (i < 0) continue;
                    rel = name.substring(i + 1);
                }
                if (entry.getSize() > 2L * 1024 * 1024) continue; // file bất thường, bỏ qua
                try (var in = zip.getInputStream(entry)) {
                    out.add(new SourceFile(rel, in.readAllBytes()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được Golden Solution zip: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(SourceFile::relPath));
        return out;
    }

    private Verdict evaluate(Map<String, Object> config, List<SourceFile> files) {
        List<Object> require = list(config.get("require"));
        List<Object> anyOf = list(config.get("any_of"));
        List<String> passed = new ArrayList<>();
        for (Object raw : require) {
            Verdict v = evaluateRequirement(map(raw), files);
            if (!v.passed) return v;
            passed.add(v.detail);
        }
        if (!anyOf.isEmpty()) {
            List<String> branchFails = new ArrayList<>();
            boolean anyPassed = false;
            for (Object rawBranch : anyOf) {
                List<Object> reqs = list(rawBranch);
                Verdict firstFail = null;
                List<String> branchOk = new ArrayList<>();
                for (Object raw : reqs) {
                    Verdict v = evaluateRequirement(map(raw), files);
                    if (!v.passed) { firstFail = v; break; }
                    branchOk.add(v.detail);
                }
                if (firstFail == null) { anyPassed = true; passed.addAll(branchOk); break; }
                branchFails.add(firstFail.detail);
            }
            if (!anyPassed) {
                return new Verdict(false, branchFails.size() == 1
                        ? branchFails.get(0)
                        : "Không nhánh nào đạt: " + String.join(" / ", branchFails));
            }
        }
        return new Verdict(true, String.join(" ", passed));
    }

    private Verdict evaluateRequirement(Map<String, Object> req, List<SourceFile> files) {
        String label = text(req, "label");
        List<String> paths = list(req.get("paths")).stream()
                .map(String::valueOf).filter(p -> !p.isBlank()).toList();
        if (paths.isEmpty()) return new Verdict(false, "Yêu cầu \"" + label + "\" không khai paths.");
        List<Pattern> pathRes = paths.stream().map(StaticRuleService::globPattern).toList();
        Pattern contentRe = null;
        String contains = text(req, "contains");
        if (!contains.isBlank()) contentRe = Pattern.compile(contains, Pattern.MULTILINE);

        List<String> matched = new ArrayList<>();
        for (SourceFile f : files) {
            boolean pathOk = false;
            for (Pattern p : pathRes) {
                if (p.matcher(f.relPath()).matches()) { pathOk = true; break; }
            }
            if (!pathOk) continue;
            if (contentRe != null && !contentRe.matcher(f.text()).find()) continue;
            matched.add(f.relPath());
        }

        Integer max = req.get("max") instanceof Number n ? n.intValue() : null;
        int min = req.get("min") instanceof Number n ? n.intValue() : (max == null ? 1 : 0);
        String ten = label.isBlank() ? paths.get(0) : label;
        List<String> viDu = matched.stream().limit(3).toList();

        if (max != null && matched.size() > max) {
            return new Verdict(false, "\"" + ten + "\" " + (max == 0 ? "bị cấm" : "tối đa " + max)
                    + " nhưng tìm thấy " + matched.size() + " file (" + String.join(", ", viDu) + ").");
        }
        if (matched.size() < min) {
            return new Verdict(false, "Không thấy file nào"
                    + (contentRe == null ? "" : " có nội dung khớp yêu cầu")
                    + " cho \"" + ten + "\" (mẫu: " + String.join(", ", paths) + ").");
        }
        return new Verdict(true, "\"" + ten + "\": " + matched.size()
                + " file (" + String.join(", ", viDu) + ").");
    }

    /** Cùng phép đổi glob→regex với static_checks.dart: `**` xuyên cấp, `*` một cấp. */
    static Pattern globPattern(String glob) {
        String g = glob.replace('\\', '/');
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') { sb.append(".*"); i++; }
                else sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else if ("\\^$.|+()[]{}".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    // ═══════════════════ PHỤ TRỢ ═══════════════════

    private List<Map<String, Object>> savedRules(BehaviorSuite suite) {
        String json = suite.getStaticRulesJson();
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            Map<String, Object> root = map(mapper.readValue(json, Map.class));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object raw : list(root.get("rules"))) out.add(map(raw));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("static_rules_json của suite hỏng: " + e.getMessage(), e);
        }
    }

    private BehaviorSuite require(String suiteId) {
        return suites.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy behavior suite: " + suiteId));
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> source ? new ArrayList<>(source) : new ArrayList<>();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }
}
