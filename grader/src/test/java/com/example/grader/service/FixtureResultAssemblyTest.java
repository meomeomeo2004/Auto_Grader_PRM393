package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.Skill;
import com.example.grader.entity.SkillCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chạy CODE THẬT của khâu gắn nhãn trên DỮ LIỆU THẬT do grader sinh ra, rồi ghi
 * {@code target/fixture-result-<bài>.json} để {@code verify_result.py} nghiệm thu ngoài.
 *
 * <p>Không cần MySQL: {@link SyllabusService.Resolver} dựng thẳng từ {@code syllabus.json},
 * đúng như {@code seedOnStartup} nạp vào DB.
 *
 * <p>Phần bọc ngoài (student/exam/grading_result) được DỰNG LẠI giống
 * {@code BatchGradingService.assembleResultJson} — method đó là private và cần repository,
 * nên ở đây chỉ gọi thật các bước P1 đã đổi.
 *
 * <p>Test tự bỏ qua khi chưa chấm fixture (cần Docker): chạy
 * {@code fixtures/result-json-v2/run-fixture.sh} trước.
 */
class FixtureResultAssemblyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURE = Path.of("..", "fixtures", "result-json-v2");
    /** Sáu bài nộp của fixture — khai MỘT chỗ để ba test độ phủ không lệch nhau. */
    private static final List<String> FIXTURE_VARIANTS = List.of(
            "high", "medium", "sloppy", "unwired", "broken-action", "broken-boot", "broken-compile");
    /** Từ vựng nội bộ của hệ thống chấm — sinh viên không kiểm chứng được số lượng test. */
    private static final java.util.regex.Pattern INTERNAL_TEST_COUNT =
            java.util.regex.Pattern.compile("\\b\\d+\\s+(assert|testcase|test|phép kiểm)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    @Test
    void assemblesLabelsForEveryFixtureSubmission() throws Exception {
        SyllabusService.Resolver resolver = resolverFromSeedFile();
        int done = 0;

        for (String variant : FIXTURE_VARIANTS) {
            Path graderOut = FIXTURE.resolve(".build/out/" + variant + ".json");
            if (!Files.exists(graderOut)) continue;

            Map<String, Object> assembled = assemble(graderOut, resolver, variant);
            Path target = Path.of("target", "fixture-result-" + variant + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(assembled), StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tcs = (List<Map<String, Object>>) assembled.get("test_cases");
            assertEquals(30, tcs.size(), variant + ": số testcase");
            for (Map<String, Object> tc : tcs) {
                String id = String.valueOf(tc.get("test_id"));
                // A1: ba khoá phải CÓ MẶT ở mọi testcase, kể cả giá trị null.
                assertTrue(tc.containsKey("rubric"), variant + "/" + id + ": thiếu rubric");
                assertTrue(tc.containsKey("rubric_label"), variant + "/" + id + ": thiếu rubric_label");
                assertTrue(tc.containsKey("layer"), variant + "/" + id + ": thiếu layer");
                assertTrue(tc.containsKey("chapter"), variant + "/" + id + ": thiếu chapter");
                // A3: layer phải nằm trong enum của SPEC.
                assertTrue(TestCaseTaxonomy.LAYERS.contains(tc.get("layer")),
                        variant + "/" + id + ": layer = " + tc.get("layer"));
                // `expected` đi thẳng tới sinh viên nên không được chứa từ vựng nội bộ
                // của hệ thống chấm: chữ "assert", hay đếm số testcase/test.
                String expected = String.valueOf(tc.get("expected"));
                assertFalse(expected.toLowerCase().contains("assert"),
                        variant + "/" + id + ": expected lộ từ vựng nội bộ — " + expected);
                assertFalse(INTERNAL_TEST_COUNT.matcher(expected).find(),
                        variant + "/" + id + ": expected đếm số test — " + expected);
                // P2b — hai trường phải BIẾN MẤT khỏi mọi testcase. Chúng là câu tra bảng theo
                // mã lỗi, không phải điều quan sát được; `expected` + `actual` đã thay trọn.
                assertFalse(tc.containsKey("error"), variant + "/" + id + ": còn object error");
                assertFalse(tc.containsKey("student_safe_summary"),
                        variant + "/" + id + ": còn student_safe_summary");
                // Nhưng phần CÓ GIÁ TRỊ của chúng — mã cho máy gom nhóm — phải còn.
                assertTrue(tc.containsKey("error_code"), variant + "/" + id + ": thiếu error_code");
                if (String.valueOf(tc.get("status")).contains("fail")) {
                    assertNotNull(tc.get("error_code"), variant + "/" + id + ": error_code null ở test fail");
                }
                // P5: `actual` của mọi test KHÔNG đạt phải đến từ kênh quan sát có cấu trúc,
                // không phải từ bóc log tiếng Anh. Runner nào rơi lại về bóc log thì đỏ ở đây.
                if (!"passed".equals(tc.get("status"))) {
                    assertEquals("observation", tc.get("actual_source"),
                            variant + "/" + id + ": actual còn dựng từ log — " + tc.get("actual"));
                    // A2c — ĐIỀU ĐÁNG KHẲNG ĐỊNH THẬT SỰ. Bất biến trên chỉ nói `actual` ĐẾN TỪ
                    // ĐÂU, mà lúc nó xanh trên 6 mẫu tôi đã coi đó là "kênh quan sát phủ trọn" —
                    // thật ra nó xanh vì fixture chưa có bài nào ném ngoại lệ giữa lúc runner
                    // đang chạy. Cái phải giữ là NỘI DUNG: dù đến từ nguồn nào, `actual` tới tay
                    // sinh viên không được là log của bộ chấm (SPEC 5.4, luật C3/C4).
                    String actual = String.valueOf(tc.get("actual"));
                    for (String leak : List.of("EXCEPTION CAUGHT BY", "was thrown", ".dart",
                            "package:flutter", "#0 ", "TC_")) {
                        assertFalse(actual.contains(leak), variant + "/" + id
                                + ": actual lộ log bộ chấm (\"" + leak + "\") — " + actual);
                    }
                }
            }
            done++;
        }

        assumeTrue(done > 0, "Chưa chấm fixture — chạy fixtures/result-json-v2/run-fixture.sh trước");
    }

    @Test
    void labelsMatchSpecForKnownTestcases() throws Exception {
        Path graderOut = FIXTURE.resolve(".build/out/high.json");
        assumeTrue(Files.exists(graderOut), "Chưa chấm fixture bài high");

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tcs = (List<Map<String, Object>>)
                assemble(graderOut, resolverFromSeedFile(), "high").get("test_cases");
        for (Map<String, Object> tc : tcs) byId.put(String.valueOf(tc.get("test_id")), tc);

        // B1: layer suy từ runner
        assertEquals("integration", byId.get("TC_APP_BOOT").get("layer"));
        assertEquals("widget", byId.get("TC_LIST_VISIBLE").get("layer"));
        assertEquals("responsive", byId.get("TC_RESPONSIVE_TARGET").get("layer"));
        // B2: GROUP lấy tầng cao nhất của các con (WIDGET_VISIBLE + FORM_SUBMIT)
        assertEquals("integration", byId.get("TC_ADD_USER").get("layer"));

        // rubric = nhóm chức năng giáo viên gom, KHÔNG phải testcase_group
        assertEquals("XEM_DS", byId.get("TC_LIST_VISIBLE").get("rubric"));
        assertEquals("THEM_USER", byId.get("TC_ADD_USER").get("rubric"));
        assertEquals("RESPONSIVE", byId.get("TC_RESPONSIVE_TARGET").get("rubric"));

        // A5: chapter khớp category trong syllabus
        assertEquals("UI_BASIC_WIDGETS", byId.get("TC_APP_BOOT").get("category"));
        assertEquals(4, byId.get("TC_APP_BOOT").get("chapter"));
        assertEquals("ADVANCED_WIDGETS", byId.get("TC_LIST_VISIBLE").get("category"));
        assertEquals(7, byId.get("TC_LIST_VISIBLE").get("chapter"));
        assertEquals("STATE_MANAGEMENT", byId.get("TC_DELETE_CONFIRM").get("category"));
        assertEquals(6, byId.get("TC_DELETE_CONFIRM").get("chapter"));

        // GROUP có expected giáo viên viết tay: bản ghép máy KHÔNG được ghi đè, vì câu viết
        // tay bao giờ cũng sát đề hơn.
        assertEquals("Nhập họ tên và email hợp lệ rồi lưu thì người dùng mới phải xuất hiện "
                        + "trong danh sách và không còn thông báo lỗi.",
                byId.get("TC_ADD_USER").get("expected"));
    }

    /**
     * A2 — CHỐT CHẶN ĐỘ PHỦ RUNNER. Bộ đề fixture phải dùng đủ **mọi** runner của engine chung.
     *
     * <p>Vì sao khoá ở test chứ không chỉ ghi vào README: trước A2, mười runner CHƯA TỪNG chạy một
     * lần nào mà vẫn được công bố trong hợp đồng. Cứ thêm runner mà quên fixture là lặp lại y hệt —
     * bên đọc xây logic trên code chưa ai chạy. Test này chạy KHÔNG cần Docker, chỉ đọc matrix.
     */
    @Test
    void fixtureExercisesEveryCommonRunner() throws Exception {
        JsonNode matrix = MAPPER.readTree(
                Files.readString(FIXTURE.resolve("exam/skills_matrix.json"), StandardCharsets.UTF_8));
        Set<String> used = new java.util.LinkedHashSet<>();
        matrix.forEach(row -> {
            used.add(row.path("runner").asText());
            row.path("children").forEach(child -> used.add(child.path("runner").asText()));
        });

        Set<String> declared = new java.util.LinkedHashSet<>(TestCaseTaxonomy.commonRunners());
        declared.add("GROUP");   // dẫn xuất, không có layer riêng nên không nằm trong bảng
        Set<String> missing = new java.util.TreeSet<>(declared);
        missing.removeAll(used);
        assertTrue(missing.isEmpty(),
                "Runner của engine chung chưa có testcase nào trong fixture: " + missing);
    }

    /**
     * A2b — CHỐT CHẶN MẠNH HƠN: mỗi runner phải từng **ĐẠT** và từng **HỎNG** trên fixture.
     *
     * <p>Vì sao cần cái này khi đã có {@link #fixtureExercisesEveryCommonRunner}: test kia chỉ đòi
     * runner **có testcase**, nên "phủ 23/23 runner" của A2 vòng 1 chỉ có nghĩa *đã được gọi*. Đo
     * lại sau A2 thì tám runner **chưa từng đi qua đường hỏng** — nhánh phát quan sát của riêng
     * chúng vẫn là code chưa ai chạy, và chính chỗ đó là nơi hai lỗ hổng của A2b nằm (tap không
     * kiểm tồn tại, `_assertTargetType` fail trần). Đường ĐẠT và đường HỎNG là hai đường khác nhau;
     * phủ một cái không nói gì về cái kia.
     *
     * <p><b>FORM_SUBMIT được miễn</b> — nó chỉ tồn tại làm con của GROUP, mà `result.json` không
     * báo trạng thái từng testcase con, nên từ output KHÔNG quan sát được. Nó vẫn chạy cả hai
     * đường (đạt ở `high`, hỏng ở `unwired`), chỉ là quy về GROUP. Miễn có ghi lý do, không im lặng.
     */
    private static final Set<String> RUNNERS_ONLY_AS_GROUP_CHILD = Set.of("FORM_SUBMIT");

    @Test
    void everyRunnerHasBothAPassAndAFailSomewhere() throws Exception {
        JsonNode matrix = MAPPER.readTree(
                Files.readString(FIXTURE.resolve("exam/skills_matrix.json"), StandardCharsets.UTF_8));
        Map<String, String> runnerOf = new LinkedHashMap<>();
        matrix.properties().forEach(e ->
                runnerOf.put(e.getKey(), e.getValue().path("runner").asText()));

        Map<String, java.util.Set<String>> outcomes = new LinkedHashMap<>();
        int done = 0;
        for (String variant : FIXTURE_VARIANTS) {
            Path graderOut = FIXTURE.resolve(".build/out/" + variant + ".json");
            if (!Files.exists(graderOut)) continue;
            done++;
            JsonNode root = MAPPER.readTree(Files.readString(graderOut, StandardCharsets.UTF_8));
            for (JsonNode tc : root.path("test_cases")) {
                String runner = runnerOf.get(tc.path("test_id").asText());
                if (runner == null) continue;
                outcomes.computeIfAbsent(runner, k -> new java.util.TreeSet<>())
                        .add(tc.path("status").asText());
            }
        }
        assumeTrue(done > 0, "Chưa chấm fixture — chạy fixtures/result-json-v2/run-fixture.sh trước");

        java.util.List<String> gaps = new ArrayList<>();
        for (String runner : new java.util.TreeSet<>(TestCaseTaxonomy.commonRunners())) {
            if (RUNNERS_ONLY_AS_GROUP_CHILD.contains(runner)) continue;
            java.util.Set<String> seen = outcomes.getOrDefault(runner, java.util.Set.of());
            if (!seen.contains("passed")) gaps.add(runner + " chưa từng ĐẠT");
            if (!seen.contains("failed")) gaps.add(runner + " chưa từng HỎNG");
        }
        // GROUP không nằm trong bảng layer nên phải kiểm riêng.
        java.util.Set<String> group = outcomes.getOrDefault("GROUP", java.util.Set.of());
        if (!group.contains("passed")) gaps.add("GROUP chưa từng ĐẠT");
        if (!group.contains("failed")) gaps.add("GROUP chưa từng HỎNG");

        assertTrue(gaps.isEmpty(), "Runner chưa chạy đủ hai đường: " + gaps);
    }

    /**
     * A2 — CHỐT CHẶN ĐỘ PHỦ `kind`. Mọi giá trị `observation.kind` mà engine biết phát đều phải
     * XUẤT HIỆN THẬT trên ít nhất một bài nộp của fixture.
     *
     * <p>Đây là điều kiện để SPEC 5.5 công bố một `kind` ở **Mức 1**. Trước A2 chỉ 6/13 đạt; bài
     * `sloppy` được dựng riêng để 7 cái còn lại phát ra. Nếu ai gỡ một lỗi cấy trong `sloppy`, hoặc
     * thêm `kind` mới mà không cấy lỗi tương ứng, test này đỏ ngay.
     */
    @Test
    void fixtureEmitsEveryObservationKind() throws Exception {
        Set<String> seen = new java.util.TreeSet<>();
        int done = 0;
        for (String variant : FIXTURE_VARIANTS) {
            Path graderOut = FIXTURE.resolve(".build/out/" + variant + ".json");
            if (!Files.exists(graderOut)) continue;
            done++;
            JsonNode root = MAPPER.readTree(Files.readString(graderOut, StandardCharsets.UTF_8));
            for (JsonNode tc : root.path("test_cases")) {
                JsonNode kind = tc.path("observation").path("kind");
                if (kind.isTextual()) seen.add(kind.asText());
            }
        }
        assumeTrue(done > 0, "Chưa chấm fixture — chạy fixtures/result-json-v2/run-fixture.sh trước");

        Set<String> missing = new java.util.TreeSet<>(TestObservationRenderer.renderableKinds());
        missing.removeAll(seen);
        assertTrue(missing.isEmpty(), "`kind` engine khai nhưng fixture chưa phát thật: " + missing
                + " (đã phát: " + seen + ")");
    }

    // ── dựng lại luồng ghép, gọi THẬT các bước P1 đã đổi ───────────────────
    @SuppressWarnings("unchecked")
    private Map<String, Object> assemble(Path graderOut, SyllabusService.Resolver resolver,
                                         String variant) throws Exception {
        JsonNode grader = MAPPER.readTree(Files.readString(graderOut, StandardCharsets.UTF_8));

        List<Map<String, Object>> tcs = new ArrayList<>();
        for (JsonNode n : grader.get("test_cases")) tcs.add(MAPPER.convertValue(n, Map.class));

        BatchGradingService batch = new BatchGradingService();
        Exam exam = new Exam();
        exam.setTestcasePath(FIXTURE.resolve("exam").toAbsolutePath().toString());

        // ĐÚNG THỨ TỰ của assembleResultJson. Thiếu một bước là artifact không trung thực:
        // sanitizeTestCaseErrors mới là chỗ sinh `error` + `student_safe_summary`, nên bỏ nó
        // thì nhóm luật E của ACCEPTANCE xanh giả (từng làm samples/ sai, xem CHANGELOG_FOR_NLP).
        Object matrix = invoke(batch, "loadSkillsMatrix", new Class<?>[]{Exam.class}, exam);
        assertNotNull(matrix, "Không đọc được skills_matrix.json của fixture");
        invoke(batch, "enrichTestCases", new Class<?>[]{List.class, Map.class}, tcs, matrix);
        invoke(batch, "normalizeExpectedFields", new Class<?>[]{List.class}, tcs);
        invoke(batch, "sanitizeTestCaseErrors", new Class<?>[]{List.class}, tcs);
        // 2026-08-22: tầng gắn nhãn phân loại và tầng đánh giá năng lực đã bị gỡ khỏi
        // assembleResultJson. Chuỗi ở đây phải bám theo, nếu không artifact sinh ra mô tả
        // một hình dạng result.json không còn tồn tại.
        invoke(batch, "fillDerivedFields", new Class<?>[]{List.class}, tcs);

        Map<String, Object> gradingResult = MAPPER.convertValue(grader.get("grading_result"), Map.class);
        gradingResult.putIfAbsent("not_run_tests",
                invoke(batch, "countStatus", new Class<?>[]{List.class, String.class}, tcs, "not_run"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "2");
        root.put("student", Map.of("id", "FIXTURE_" + variant.toUpperCase(), "name", "Fixture " + variant));
        root.put("exam", Map.of("code", "FIXTURE_V2", "title", "Fixture result.json v2", "total_score", 10));
        root.put("grading_result", gradingResult);
        root.put("test_cases", tcs);
        // Backend phát hành cả khối này; thiếu nó thì bên đọc tưởng nó đã bị bỏ.
        root.put("teacher_note", "");
        return root;
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /** Dựng Resolver từ syllabus.json y như SyllabusService.seedOnStartup nạp vào DB. */
    private SyllabusService.Resolver resolverFromSeedFile() throws Exception {
        JsonNode root;
        try (var in = getClass().getResourceAsStream("/syllabus.json")) {
            assertNotNull(in, "Không tìm thấy syllabus.json trên classpath");
            root = MAPPER.readTree(in);
        }

        Map<String, SkillCategory> cats = new LinkedHashMap<>();
        for (JsonNode c : root.path("categories")) {
            SkillCategory cat = new SkillCategory();
            cat.setCode(c.path("code").asText());
            cat.setName(c.path("name").asText());
            cat.setCompetencyLabel(c.path("competency_label").asText(null));
            cat.setDisplayOrder(c.path("order").asInt(0));
            if (c.hasNonNull("chapter")) cat.setChapter(c.path("chapter").asInt());
            cats.put(cat.getCode(), cat);
        }

        Map<String, Skill> skills = new LinkedHashMap<>();
        for (JsonNode s : root.path("skills")) {
            Skill sk = new Skill();
            sk.setCode(s.path("code").asText());
            sk.setCategoryCode(s.path("category").asText());
            sk.setName(s.path("name").asText());
            sk.setDefaultDifficulty(s.path("default_difficulty").asText("basic"));
            skills.put(sk.getCode(), sk);
        }

        return new SyllabusService.Resolver(cats, skills);
    }
}
