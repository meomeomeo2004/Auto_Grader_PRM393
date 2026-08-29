package com.example.grader.service;

import com.example.grader.entity.Skill;
import com.example.grader.entity.SkillCategory;
import com.example.grader.entity.SyllabusMeta;
import com.example.grader.repository.SkillCategoryRepository;
import com.example.grader.repository.SkillRepository;
import com.example.grader.repository.SyllabusMetaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Quản lý SYLLABUS (danh mục năng lực): category + skill. Nguồn runtime là DB; lần đầu
 * (DB rỗng) seed từ resources/syllabus.json. Cung cấp tra cứu skill→category để
 * CRUD khung năng lực cho giảng viên.
 *
 * Tra cứu category cho 1 testcase theo 3 lớp (bền vững với đề cũ):
 *   1) skill_code (chuẩn mới) → category của skill đó
 *   2) tên skill tự do cũ (vd "Dart Logic") → alias → skill_code
 *   3) prefix mã testcase (TC_LOGIC → DART, TC_UI → UI ...) → category
 */
@Slf4j
@Service
public class SyllabusService {

    @Autowired private SkillCategoryRepository categoryRepo;
    @Autowired private SkillRepository skillRepo;
    @Autowired private SyllabusMetaRepository metaRepo;
    @Autowired private BotSyllabusSync botSync;   // ghi đồng thời sang kho skill của bot khi syllabus đổi

    /** Đồng bộ syllabus hiện tại sang kho skill của bot (gọi sau MỌI thay đổi + sau seed). */
    private void syncBot() { try { botSync.sync(categories(), skills()); } catch (Exception ignored) {} }

    private final ObjectMapper mapper = new ObjectMapper();

    /** Metadata không lưu DB (version/subject/difficulty levels/ngưỡng mặc định) — nạp từ seed. */
    private Map<String, Object> meta = new LinkedHashMap<>();

    /** Alias: tên skill TỰ DO cũ (lowercase) → skill_code chuẩn. Cho đề chưa remap. */
    private static final Map<String, String> LEGACY_SKILL_ALIAS = Map.ofEntries(
            Map.entry("dart model",        "DART_CLASSES"),
            Map.entry("model/oop",         "OOP_MODEL"),
            Map.entry("model",             "DART_CLASSES"),
            Map.entry("oop",               "OOP_INHERITANCE"),
            Map.entry("dart logic",        "DART_COLLECTIONS"),
            Map.entry("business logic",    "DART_COLLECTIONS"),
            Map.entry("logic",             "DART_COLLECTIONS"),
            Map.entry("dart basic",        "DART_SYNTAX"),
            Map.entry("flutter ui",        "UI_WIDGETS"),
            Map.entry("ui",                "UI_WIDGETS"),
            Map.entry("flutter ui/logic",  "STATE_LIFTING"),
            Map.entry("flutter ui + state","STATE_BASIC"),
            Map.entry("flutter ui/state",  "STATE_BASIC"),
            Map.entry("state",             "STATE_BASIC"),
            Map.entry("validation",        "FORM_VALIDATE"),
            Map.entry("validate",          "FORM_VALIDATE"),
            Map.entry("navigation",        "NAV_BASIC")
    );

    /** Alias: skill_code CŨ (v2026.1) → skill_code MỚI (v2026.2). Cho đề chưa remap. */
    private static final Map<String, String> LEGACY_CODE_ALIAS = Map.ofEntries(
            Map.entry("DART_OOP",            "DART_CLASSES"),
            Map.entry("DART_LOGIC",          "DART_COLLECTIONS"),
            Map.entry("DART_BASIC",          "DART_SYNTAX"),
            Map.entry("DART_OOP_ADV",        "OOP_PATTERNS"),
            Map.entry("UI_BASIC",            "UI_WIDGETS"),
            Map.entry("UI_LAYOUT",           "LAYOUT_FLEX"),
            Map.entry("UI_LIST",             "UI_LISTS"),
            Map.entry("UI_FORM",             "FORM_INPUT"),
            Map.entry("UI_DIALOG",           "UI_PICKERS"),
            Map.entry("VAL_INPUT",           "FORM_VALIDATE"),
            Map.entry("VAL_BUSINESS",        "FORM_BUSINESS"),
            Map.entry("VAL_FORM",            "FORM_VALIDATE"),
            Map.entry("STATE_SETSTATE",      "STATE_BASIC"),
            Map.entry("STATE_MGMT",          "STATE_LIFTING"),
            Map.entry("NAV_ARGS",            "NAV_NAMED"),
            Map.entry("ASYNC_JSON",          "NET_JSON"),
            Map.entry("ASYNC_HTTP",          "NET_HTTP"),
            Map.entry("ASYNC_FUTUREBUILDER", "NET_FUTUREBUILDER")
    );

    /** Lớp cuối: prefix mã testcase → category code. */
    private static final List<Map.Entry<String, String>> TESTID_PREFIX = List.of(
            Map.entry("TC_LOGIC",  "DART_ESSENTIALS"),
            Map.entry("TC_DART",   "DART_ESSENTIALS"),
            Map.entry("TC_MODEL",  "DART_ESSENTIALS"),
            Map.entry("TC_OOP",    "OOP_ASYNC"),
            Map.entry("TC_ASYNC",  "OOP_ASYNC"),
            Map.entry("TC_UI",     "UI_FUNDAMENTALS"),
            Map.entry("TC_WIDGET", "UI_FUNDAMENTALS"),
            Map.entry("TC_VAL",    "FORMS_VALIDATION"),
            Map.entry("TC_VALID",  "FORMS_VALIDATION"),
            Map.entry("TC_FORM",   "FORMS_VALIDATION"),
            Map.entry("TC_STATE",  "NAV_STATE"),
            Map.entry("TC_NAV",    "NAV_STATE"),
            Map.entry("TC_LAYOUT", "LAYOUT_RESPONSIVE"),
            Map.entry("TC_NET",    "NETWORKING"),
            Map.entry("TC_STORE",  "STORAGE"),
            Map.entry("TC_AUTH",   "AUTH")
    );

    // ── Seed / re-seed lúc khởi động ─────────────────────────────
    /**
     * Seed khi DB rỗng; RE-SEED khi `version` trong syllabus.json đổi so với version đã lưu
     * (xóa sạch category/skill cũ rồi nạp lại từ file). Lưu ý: re-seed sẽ ghi đè chỉnh sửa
     * runtime qua CRUD — chỉ bump version khi muốn áp lại từ file.
     */
    @PostConstruct
    public void seedOnStartup() {
        try {
            loadMetaFromSeed();
            JsonNode root = readSeed();
            if (root == null) return;
            String fileVer = root.path("version").asText("");

            SyllabusMeta stored = metaRepo.findById(1L).orElse(null);
            boolean empty = categoryRepo.count() == 0;
            boolean versionChanged = stored == null || !fileVer.equals(stored.getVersion());

            if (!empty && !versionChanged) {
                log.info("Syllabus v{} đã khớp DB — bỏ qua seed", fileVer);
                syncBot();
                return;
            }
            if (!empty) {
                log.info("Syllabus đổi version (DB: {} → file: {}) — RE-SEED từ file",
                        stored != null ? stored.getVersion() : "?", fileVer);
                skillRepo.deleteAll();
                categoryRepo.deleteAll();
            }

            int nc = 0, ns = 0;
            for (JsonNode c : root.path("categories")) {
                SkillCategory cat = new SkillCategory();
                cat.setCode(c.path("code").asText());
                cat.setName(c.path("name").asText());
                cat.setCompetencyLabel(c.path("competency_label").asText(null));
                cat.setDescription(c.path("description").asText(null));
                cat.setDisplayOrder(c.path("order").asInt(0));
                // Chưa khai chapter thì để null; SkillCategory.resolveChapter() suy từ order.
                if (c.hasNonNull("chapter")) cat.setChapter(c.path("chapter").asInt());
                categoryRepo.save(cat);
                nc++;
            }
            int order = 0;
            for (JsonNode s : root.path("skills")) {
                Skill sk = new Skill();
                sk.setCode(s.path("code").asText());
                sk.setCategoryCode(s.path("category").asText());
                sk.setName(s.path("name").asText());
                sk.setDescription(s.path("description").asText(null));
                sk.setDefaultDifficulty(s.path("default_difficulty").asText("basic"));
                sk.setTestable(s.path("testable").asText("auto"));
                sk.setDisplayOrder(order++);
                if (s.has("resources")) sk.setResourcesJson(s.get("resources").toString());
                skillRepo.save(sk);
                ns++;
            }

            SyllabusMeta m = stored != null ? stored : new SyllabusMeta();
            m.setId(1L);
            m.setVersion(fileVer);
            metaRepo.save(m);

            log.info("✅ Seed syllabus v{}: {} category, {} skill", fileVer, nc, ns);
            syncBot();
        } catch (Exception e) {
            log.warn("Seed syllabus lỗi: {}", e.getMessage());
        }
    }

    private void loadMetaFromSeed() {
        try {
            JsonNode root = readSeed();
            if (root == null) return;
            meta = new LinkedHashMap<>();
            meta.put("version", root.path("version").asText(""));
            meta.put("subject", mapper.convertValue(root.path("subject"), Object.class));
            meta.put("difficulty_levels", mapper.convertValue(root.path("difficulty_levels"), Object.class));
            meta.put("level_thresholds_default", mapper.convertValue(root.path("level_thresholds_default"), Object.class));
        } catch (Exception ignored) {}
    }

    private JsonNode readSeed() {
        try (InputStream in = new ClassPathResource("syllabus.json").getInputStream()) {
            return mapper.readTree(in);
        } catch (Exception e) {
            log.warn("Không đọc được resources/syllabus.json: {}", e.getMessage());
            return null;
        }
    }

    // ── Đọc cho API ──────────────────────────────────────────────
    public Map<String, Object> meta() { return meta; }

    public List<SkillCategory> categories() {
        return categoryRepo.findAllByOrderByDisplayOrderAsc();
    }

    public List<Skill> skills() {
        return skillRepo.findAllByOrderByDisplayOrderAsc();
    }

    /** Cây đầy đủ cho frontend: meta + categories (mỗi category kèm skills). */
    public Map<String, Object> tree(boolean includeInactive) {
        List<SkillCategory> cats = categories();
        List<Skill> sks = skills();

        List<Map<String, Object>> catList = new ArrayList<>();
        for (SkillCategory c : cats) {
            if (!includeInactive && Boolean.FALSE.equals(c.getActive())) continue;
            Map<String, Object> cm = categoryToMap(c);
            List<Map<String, Object>> skillList = new ArrayList<>();
            for (Skill s : sks) {
                if (!c.getCode().equals(s.getCategoryCode())) continue;
                if (!includeInactive && Boolean.TRUE.equals(s.getDeprecated())) continue;
                skillList.add(skillToMap(s));
            }
            cm.put("skills", skillList);
            catList.add(cm);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("categories", catList);
        return root;
    }

    public Map<String, Object> categoryToMap(SkillCategory c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", c.getCode());
        m.put("name", c.getName());
        m.put("competency_label", c.getCompetencyLabel());
        m.put("description", c.getDescription());
        m.put("display_order", c.getDisplayOrder());
        m.put("chapter", c.resolveChapter());
        m.put("weak_threshold", c.getWeakThreshold());
        m.put("good_threshold", c.getGoodThreshold());
        m.put("active", c.getActive());
        return m;
    }

    public Map<String, Object> skillToMap(Skill s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.getCode());
        m.put("category", s.getCategoryCode());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("default_difficulty", s.getDefaultDifficulty());
        m.put("testable", s.getTestable());
        m.put("deprecated", s.getDeprecated());
        m.put("display_order", s.getDisplayOrder());
        Object res = parseResources(s.getResourcesJson());
        m.put("resources", res);
        return m;
    }

    private Object parseResources(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, List.class); }
        catch (Exception e) { return List.of(); }
    }

    // ── CRUD category ────────────────────────────────────────────
    public SkillCategory saveCategory(Map<String, Object> body, boolean isNew) {
        String code = str(body.get("code"));
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("Thiếu mã category (code)");
        code = code.trim().toUpperCase();
        Optional<SkillCategory> existing = categoryRepo.findById(code);
        if (isNew && existing.isPresent())
            throw new IllegalArgumentException("Category đã tồn tại: " + code);
        SkillCategory c = existing.orElseGet(SkillCategory::new);
        c.setCode(code);   // code KHÔNG đổi khi update (path quyết định)
        if (body.containsKey("name"))             c.setName(str(body.get("name")));
        if (body.containsKey("competency_label")) c.setCompetencyLabel(str(body.get("competency_label")));
        if (body.containsKey("description"))      c.setDescription(str(body.get("description")));
        if (body.containsKey("display_order"))    c.setDisplayOrder(toInt(body.get("display_order"), c.getDisplayOrder()));
        if (body.containsKey("chapter"))          c.setChapter(toInt(body.get("chapter"), c.getChapter()));
        if (body.containsKey("weak_threshold"))   c.setWeakThreshold(toDouble(body.get("weak_threshold"), c.getWeakThreshold()));
        if (body.containsKey("good_threshold"))   c.setGoodThreshold(toDouble(body.get("good_threshold"), c.getGoodThreshold()));
        if (body.containsKey("active"))           c.setActive(toBool(body.get("active"), true));
        if (c.getName() == null || c.getName().isBlank())
            throw new IllegalArgumentException("Thiếu tên category (name)");
        SkillCategory saved = categoryRepo.save(c);
        syncBot();
        return saved;
    }

    /** Xóa mềm category: active=false. Chặn nếu còn skill active thuộc category. */
    public void softDeleteCategory(String code) {
        SkillCategory c = categoryRepo.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy category: " + code));
        boolean hasActiveSkill = skillRepo.findByCategoryCodeOrderByDisplayOrderAsc(code)
                .stream().anyMatch(s -> !Boolean.TRUE.equals(s.getDeprecated()));
        if (hasActiveSkill)
            throw new IllegalArgumentException(
                    "Còn skill đang dùng trong category này — hãy deprecate/đổi category các skill trước.");
        c.setActive(false);
        categoryRepo.save(c);
        syncBot();
    }

    // ── CRUD skill ───────────────────────────────────────────────
    public Skill saveSkill(Map<String, Object> body, boolean isNew) {
        String code = str(body.get("code"));
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("Thiếu mã skill (code)");
        code = code.trim().toUpperCase();
        Optional<Skill> existing = skillRepo.findById(code);
        if (isNew && existing.isPresent())
            throw new IllegalArgumentException("Skill đã tồn tại: " + code);
        Skill s = existing.orElseGet(Skill::new);
        s.setCode(code);
        if (body.containsKey("category")) {
            String cat = str(body.get("category"));
            if (cat != null) cat = cat.trim().toUpperCase();
            if (cat == null || categoryRepo.findById(cat).isEmpty())
                throw new IllegalArgumentException("Category không hợp lệ: " + cat);
            s.setCategoryCode(cat);
        }
        if (s.getCategoryCode() == null)
            throw new IllegalArgumentException("Thiếu category cho skill");
        if (body.containsKey("name"))               s.setName(str(body.get("name")));
        if (body.containsKey("description"))        s.setDescription(str(body.get("description")));
        if (body.containsKey("default_difficulty")) s.setDefaultDifficulty(normDifficulty(str(body.get("default_difficulty"))));
        if (body.containsKey("testable"))           s.setTestable("manual".equalsIgnoreCase(str(body.get("testable"))) ? "manual" : "auto");
        if (body.containsKey("display_order"))      s.setDisplayOrder(toInt(body.get("display_order"), s.getDisplayOrder()));
        if (body.containsKey("deprecated"))         s.setDeprecated(toBool(body.get("deprecated"), false));
        if (body.containsKey("resources")) {
            try { s.setResourcesJson(mapper.writeValueAsString(body.get("resources"))); }
            catch (Exception ignored) {}
        }
        if (s.getName() == null || s.getName().isBlank())
            throw new IllegalArgumentException("Thiếu tên skill (name)");
        if (s.getDefaultDifficulty() == null) s.setDefaultDifficulty("basic");
        if (s.getTestable() == null) s.setTestable("auto");
        Skill saved = skillRepo.save(s);
        syncBot();
        return saved;
    }

    /** Xóa mềm skill: deprecated=true (đề cũ trỏ vào vẫn map được). */
    public void softDeleteSkill(String code) {
        Skill s = skillRepo.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy skill: " + code));
        s.setDeprecated(true);
        skillRepo.save(s);
        syncBot();
    }

    // ── Validate skills_matrix.json khi upload đề ────────────────
    /**
     * Trả về danh sách VẤN ĐỀ (rỗng = hợp lệ). Mỗi item: {testId, skillCode, issue}.
     * Chỉ soi các testcase CÓ skill_code: nếu code không tồn tại / đã deprecated → báo.
     * Testcase không khai skill_code (đề cũ) được bỏ qua (không chặn) để tương thích ngược.
     */
    public List<Map<String, Object>> validateSkillsMatrix(String skillsMatrixJson) {
        List<Map<String, Object>> problems = new ArrayList<>();
        // An toàn: nếu syllabus chưa có skill nào (seed lỗi) thì KHÔNG chặn upload.
        if (skillRepo.count() == 0) return problems;
        try {
            JsonNode matrix = mapper.readTree(skillsMatrixJson);
            var it = matrix.fields();
            while (it.hasNext()) {
                var e = it.next();
                JsonNode v = e.getValue();
                if (!v.isObject()) continue;          // dạng số cũ → bỏ qua
                JsonNode codeNode = v.get("skill_code");
                if (codeNode == null || codeNode.asText().isBlank()) continue;  // không khai → bỏ qua
                String code = codeNode.asText().trim().toUpperCase();
                Skill sk = skillRepo.findById(code).orElse(null);
                if (sk == null) {                          // thử alias code cũ → code mới
                    String aliased = LEGACY_CODE_ALIAS.get(code);
                    if (aliased != null) sk = skillRepo.findById(aliased).orElse(null);
                }
                if (sk == null) {
                    problems.add(problem(e.getKey(), code, "skill_code không tồn tại trong syllabus"));
                } else if (Boolean.TRUE.equals(sk.getDeprecated())) {
                    problems.add(problem(e.getKey(), code, "skill_code đã bị deprecate"));
                }
                // difficulty hợp lệ?
                JsonNode diff = v.get("difficulty");
                String normDiff = diff != null ? normDifficulty(diff.asText()) : null;
                if (diff != null && !diff.asText().isBlank() && normDiff == null) {
                    problems.add(problem(e.getKey(), code, "difficulty không hợp lệ: " + diff.asText()));
                }
                // CẢNH BÁO (không chặn): weight nên = points(difficulty) (basic=1/intermediate=2/advanced=3).
                // grader đã tự suy weight từ difficulty khi chấm, nên đây chỉ là nhắc GV sửa matrix cho khớp.
                JsonNode w = v.get("weight");
                if (normDiff != null && w != null && w.isNumber()) {
                    int expected = difficultyPoints(normDiff);
                    if (w.asDouble() != expected) {
                        problems.add(problem(e.getKey(), code,
                                "weight=" + w.asText() + " lệch độ khó " + normDiff
                                        + " (nên là " + expected + ")", "warning"));
                    }
                }
            }
        } catch (Exception ex) {
            // JSON hỏng → để validateRequiredFiles của ExamService lo; ở đây không chặn thêm
            log.debug("validateSkillsMatrix bỏ qua (parse lỗi): {}", ex.getMessage());
        }
        return problems;
    }

    private Map<String, Object> problem(String testId, String code, String issue) {
        return problem(testId, code, issue, "error");
    }

    private Map<String, Object> problem(String testId, String code, String issue, String severity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("testId", testId);
        m.put("skillCode", code);
        m.put("issue", issue);
        m.put("severity", severity);   // "error" = chặn upload; "warning" = chỉ nhắc, vẫn cho upload
        return m;
    }

    // ── Đánh giá ĐỘ PHỦ của 1 bộ testcase (đề) theo SYLLABUS ─────
    /**
     * Soi skills_matrix.json của 1 đề và đối chiếu với syllabus HIỆN TẠI (resolve trực tiếp →
     * sửa syllabus là phản chiếu ngay). Trả: từng testcase (kiến thức/độ khó/điểm/trạng thái),
     * độ phủ theo category & độ khó, các skill auto CHƯA được phủ (gaps), và issues.
     */
    public Map<String, Object> evaluateCoverage(String skillsMatrixJson) {
        Resolver r = resolver();
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> testcases = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>();

        // Gom theo category
        Map<String, double[]> catWeight = new LinkedHashMap<>();   // [weight, count]
        Map<String, java.util.LinkedHashSet<String>> catSkills = new LinkedHashMap<>();
        Map<String, int[]> diff = new LinkedHashMap<>();           // diffCode -> [count]
        java.util.Set<String> coveredSkills = new java.util.HashSet<>();
        double totalWeight = 0;

        try {
            JsonNode matrix = mapper.readTree(skillsMatrixJson);
            var it = matrix.fields();
            while (it.hasNext()) {
                var e = it.next();
                String testId = e.getKey();
                JsonNode v = e.getValue();

                String rawCode = null, legacy = null, diffStr = null;
                double weight;
                if (v.isNumber()) {
                    weight = v.asDouble();
                } else {
                    rawCode = textOrNull(v, "skill_code");
                    legacy  = textOrNull(v, "skill");
                    diffStr = normDifficulty(textOrNull(v, "difficulty"));
                    weight  = v.has("weight") ? v.path("weight").asDouble(0) : difficultyPoints(diffStr);
                }

                String resolved = r.resolveSkillCode(testId, rawCode, legacy);
                Skill s = r.skill(resolved);
                SkillCategory c = r.resolveCategory(testId, rawCode, legacy);
                if (diffStr == null) diffStr = s != null ? normDifficulty(s.getDefaultDifficulty()) : null;
                if (diffStr == null) diffStr = "basic";
                if (!v.isNumber() && !v.has("weight")) weight = difficultyPoints(diffStr);

                // Trạng thái map
                String status;
                if (rawCode != null) {
                    if (r.skill(rawCode.trim().toUpperCase()) != null) status = "ok";
                    else if (resolved != null) status = "aliased";
                    else status = "unknown";
                } else {
                    status = resolved != null ? "inferred" : "unmapped";
                }
                if (s != null && Boolean.TRUE.equals(s.getDeprecated())) status = "deprecated";

                if ("unknown".equals(status))
                    issues.add(problem(testId, rawCode, "skill_code không có trong syllabus"));
                else if ("aliased".equals(status))
                    issues.add(problem(testId, rawCode, "dùng code cũ → nên cập nhật thành " + resolved));
                else if ("deprecated".equals(status))
                    issues.add(problem(testId, resolved, "skill đã bị deprecate"));

                String catCode  = c != null ? c.getCode() : "OTHER";
                String catLabel = c != null ? (c.getCompetencyLabel() != null && !c.getCompetencyLabel().isBlank()
                        ? c.getCompetencyLabel() : c.getName()) : "Khác";

                catWeight.computeIfAbsent(catCode, k -> new double[]{0, 0});
                catWeight.get(catCode)[0] += weight;
                catWeight.get(catCode)[1] += 1;
                catSkills.computeIfAbsent(catCode, k -> new java.util.LinkedHashSet<>());
                if (resolved != null) { coveredSkills.add(resolved); catSkills.get(catCode).add(resolved); }
                diff.computeIfAbsent(diffStr, k -> new int[]{0})[0] += 1;
                totalWeight += weight;

                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("test_id", testId);
                tc.put("skill_code", resolved != null ? resolved : rawCode);
                tc.put("skill_name", s != null ? s.getName() : null);
                tc.put("category", c != null ? c.getCode() : null);
                tc.put("category_label", c != null ? catLabel : null);
                tc.put("difficulty", diffStr);
                tc.put("weight", weight);
                tc.put("status", status);
                testcases.add(tc);
            }
        } catch (Exception ex) {
            report.put("error", "Đọc skills_matrix lỗi: " + ex.getMessage());
            return report;
        }

        // byCategory (theo thứ tự syllabus)
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (SkillCategory c : categories()) {
            double[] w = catWeight.get(c.getCode());
            if (w == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", c.getCode());
            m.put("label", c.getCompetencyLabel() != null && !c.getCompetencyLabel().isBlank() ? c.getCompetencyLabel() : c.getName());
            m.put("test_count", (int) w[1]);
            m.put("weight", round2(w[0]));
            m.put("percent", totalWeight > 0 ? round2(w[0] / totalWeight * 100) : 0);
            m.put("skills_covered", new ArrayList<>(catSkills.getOrDefault(c.getCode(), new java.util.LinkedHashSet<>())));
            byCategory.add(m);
        }
        if (catWeight.containsKey("OTHER")) {
            double[] w = catWeight.get("OTHER");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", "OTHER");
            m.put("label", "Khác (chưa map)");
            m.put("test_count", (int) w[1]);
            m.put("weight", round2(w[0]));
            m.put("percent", totalWeight > 0 ? round2(w[0] / totalWeight * 100) : 0);
            m.put("skills_covered", List.of());
            byCategory.add(m);
        }

        // byDifficulty
        Map<String, Object> byDifficulty = new LinkedHashMap<>();
        for (String d : new String[]{"basic", "intermediate", "advanced"}) {
            int[] cnt = diff.get(d);
            byDifficulty.put(d, cnt != null ? cnt[0] : 0);
        }

        // gaps: skill auto, chưa deprecate, chưa được phủ — gom theo category
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (SkillCategory c : categories()) {
            List<Map<String, Object>> missing = new ArrayList<>();
            for (Skill s : skillRepo.findByCategoryCodeOrderByDisplayOrderAsc(c.getCode())) {
                if (Boolean.TRUE.equals(s.getDeprecated())) continue;
                if (!"auto".equalsIgnoreCase(s.getTestable())) continue;
                if (!coveredSkills.contains(s.getCode()))
                    missing.add(Map.of("code", s.getCode(), "name", s.getName()));
            }
            if (!missing.isEmpty()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("category", c.getCode());
                g.put("label", c.getCompetencyLabel() != null && !c.getCompetencyLabel().isBlank() ? c.getCompetencyLabel() : c.getName());
                g.put("missing_skills", missing);
                gaps.add(g);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_testcases", testcases.size());
        summary.put("total_weight", round2(totalWeight));
        summary.put("categories_covered", byCategory.stream().filter(m -> !"OTHER".equals(m.get("category"))).count());
        summary.put("categories_total", categories().size());
        summary.put("issues", issues.size());

        report.put("summary", summary);
        report.put("by_category", byCategory);
        report.put("by_difficulty", byDifficulty);
        report.put("gaps", gaps);
        report.put("issues", issues);
        report.put("testcases", testcases);
        return report;
    }

    /** Điểm theo độ khó (khớp difficulty_levels.points trong syllabus.json). */
    private static int difficultyPoints(String diff) {
        if (diff == null) return 1;
        switch (diff.toLowerCase()) {
            case "advanced": return 3;
            case "intermediate": return 2;
            default: return 1;
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }

    // ── Resolver (snapshot cho 1 lần tính competency) ────────────
    public Resolver resolver() {
        Map<String, SkillCategory> catByCode = new LinkedHashMap<>();
        for (SkillCategory c : categories()) catByCode.put(c.getCode(), c);
        Map<String, Skill> skillByCode = new LinkedHashMap<>();
        for (Skill s : skills()) skillByCode.put(s.getCode(), s);
        return new Resolver(catByCode, skillByCode);
    }

    /** Snapshot tra cứu skill/category dùng cho 1 bài chấm (tránh nhiều query). */
    public static class Resolver {
        private final Map<String, SkillCategory> catByCode;
        private final Map<String, Skill> skillByCode;

        Resolver(Map<String, SkillCategory> catByCode, Map<String, Skill> skillByCode) {
            this.catByCode = catByCode;
            this.skillByCode = skillByCode;
        }

        public SkillCategory category(String code) { return code == null ? null : catByCode.get(code); }
        public Skill skill(String code) { return code == null ? null : skillByCode.get(code); }

        /** Giải mã skill_code chuẩn từ (skillCode | alias code cũ | tên skill cũ | prefix testId). */
        public String resolveSkillCode(String testId, String skillCode, String legacySkill) {
            if (skillCode != null && !skillCode.isBlank()) {
                String up = skillCode.trim().toUpperCase();
                if (skillByCode.containsKey(up)) return up;
                String aliased = LEGACY_CODE_ALIAS.get(up);            // code cũ → code mới
                if (aliased != null && skillByCode.containsKey(aliased)) return aliased;
            }
            if (legacySkill != null && !legacySkill.isBlank()) {
                String alias = LEGACY_SKILL_ALIAS.get(legacySkill.trim().toLowerCase());
                if (alias != null && skillByCode.containsKey(alias)) return alias;
            }
            return null;
        }

        /** Tìm CATEGORY cho 1 testcase theo 3 lớp dự phòng. */
        public SkillCategory resolveCategory(String testId, String skillCode, String legacySkill) {
            String code = resolveSkillCode(testId, skillCode, legacySkill);
            if (code != null) {
                Skill s = skillByCode.get(code);
                if (s != null) return catByCode.get(s.getCategoryCode());
            }
            if (testId != null) {
                String up = testId.toUpperCase();
                for (Map.Entry<String, String> p : TESTID_PREFIX) {
                    if (up.startsWith(p.getKey())) return catByCode.get(p.getValue());
                }
            }
            return null;
        }
    }

    // ── Helpers ép kiểu ──────────────────────────────────────────
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static Integer toInt(Object o, Integer def) {
        try { return o == null ? def : (int) Double.parseDouble(o.toString()); } catch (Exception e) { return def; }
    }
    private static Double toDouble(Object o, Double def) {
        try { return o == null ? def : Double.parseDouble(o.toString()); } catch (Exception e) { return def; }
    }
    private static Boolean toBool(Object o, Boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }
    /** Chuẩn hóa difficulty; trả null nếu không hợp lệ. */
    private static String normDifficulty(String d) {
        if (d == null) return null;
        switch (d.trim().toLowerCase()) {
            case "basic": case "co ban": case "cơ bản": case "1": return "basic";
            case "intermediate": case "trung binh": case "trung bình": case "2": return "intermediate";
            case "advanced": case "nang cao": case "nâng cao": case "3": return "advanced";
            default: return null;
        }
    }
}
