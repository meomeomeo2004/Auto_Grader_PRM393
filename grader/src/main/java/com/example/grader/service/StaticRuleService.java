package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

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

    /** Nhóm điểm mặc định của mọi luật tĩnh — người soạn không phải khai (chốt 21/9/2026). */
    private static final String NHOM_MAC_DINH = "Architecture";

    private static Map<String, Object> preset(String id, String name, String kind, String lintCode,
                                              Map<String, Object> config, double weight,
                                              String groupId, String skillCode,
                                              String moTa) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("kind", kind);
        if (lintCode != null) out.put("lint_code", lintCode);
        if (config != null) out.put("config", config);
        out.put("weight", weight);
        out.put("group_id", groupId);
        out.put("skill_code", skillCode);
        out.put("description", moTa);
        return out;
    }

    /**
     * Từ vựng TÊN THƯ MỤC của một tầng → danh sách glob. Mỗi tên sinh đúng hai mẫu:
     * {@code lib/<ten>/**} (thư mục đặt ngay dưới lib) và {@code lib/**}{@code /<ten>/**}
     * (thư mục lồng trong feature/module). KHÔNG sinh mẫu hậu tố tên file — xem lý do ở
     * khối chú thích của {@link #presets()}.
     */
    private static List<String> thuMuc(String... ten) {
        List<String> out = new ArrayList<>();
        for (String t : ten) {
            out.add("lib/" + t + "/**");
            out.add("lib/**/" + t + "/**");
        }
        return out;
    }

    // ── DẤU HIỆU NỘI DUNG CỦA TỪNG TẦNG ────────────────────────────────────────────────────
    // Chỉ xét tên thư mục thì đặt nhầm file vào đúng thư mục vẫn ăn điểm: đo thật 22/9/2026,
    // để `book.dart` vào lib/data và `database_helper.dart` vào lib/model thì CẢ HAI luật đều
    // đạt. Nên mỗi tầng phải có thêm dấu hiệu đọc từ NỘI DUNG file (`contains` của engine).

    /**
     * Truy cập dữ liệu THÔ — dấu hiệu mạnh, đủ chắc để dùng làm vế CẤM.
     * Cố ý KHÔNG có {@code .insert(} ở đây: `list.insert(0, x)` là API List của Dart, cấm theo
     * nó thì một model quản lý danh sách sẽ trượt oan.
     */
    private static final String DAU_HIEU_DU_LIEU_THO =
            "sqflite|openDatabase|getDatabasesPath|rawQuery|rawInsert|rawUpdate|rawDelete"
                    + "|http\\s*\\.|Dio\\s*\\(|SharedPreferences|[Hh]ive\\.|[Ff]irebase|cloud_firestore"
                    + "|[Dd]rift|Isar|Realm|ObjectBox";

    /**
     * Rộng hơn: thêm lời gọi CRUD qua đối tượng kho ({@code db.insert(...)},
     * {@code repo.query(...)}). Chỉ dùng cho vế KHẲNG ĐỊNH, nơi bắt hụt thì thiệt cho đề chứ
     * không oan cho sinh viên.
     */
    private static final String DAU_HIEU_DU_LIEU =
            DAU_HIEU_DU_LIEU_THO + "|\\.(insert|update|delete|query)\\s*\\(";

    private static final String DAU_HIEU_WIDGET =
            "extends\\s+(StatelessWidget|StatefulWidget|ConsumerWidget|ConsumerStatefulWidget|State<)"
                    + "|Widget\\s+build\\s*\\(";

    /** Regex chạy ở chế độ MULTILINE cả hai bên (Java và Dart) nên `^` là đầu DÒNG. */
    private static final String DAU_HIEU_CLASS = "^\\s*(abstract\\s+)?class\\s+\\w+";

    /** Preset bất biến — FE chỉ hiển thị và tick, không tự chế cấu hình. */
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        // VÌ SAO CHỈ CÒN MẪU THƯ MỤC, KHÔNG CÒN MẪU HẬU TỐ TÊN FILE (sửa 22/9/2026):
        //
        // Mẫu cũ có `lib/**_helper.dart`, dịch ra regex là `^lib/.*_helper\.dart$`, nên
        // `lib/database_helper.dart` NẰM PHẲNG ở gốc cũng khớp. Mà chính khung phát đặt file
        // cho sẵn phẳng ở gốc (ExamService.zipKhungPhat: `tep.put("lib/" + ten, noiDung)`),
        // nên MỌI bài chỉ cần giải nén khung là đã "đạt" ARCH_DATA: tiêu chí không thể trượt.
        // Đo thật trên 13 bài QLCT: HE231604 và HE230429 dồn phẳng toàn bộ lib/ (8 file, không
        // một thư mục nào) — trượt ARCH_MODEL/SCREEN/LOGIC nhưng vẫn ĐẠT ARCH_DATA, chỉ nhờ
        // đúng một file mà đề phát cho họ và còn ghi "CHO SAN — KHONG SUA".
        //
        // Ba luật kia trượt cũng chỉ vì hai bài đó tình cờ đặt tên tiếng Việt (trang_chinh.dart,
        // tinh_toan.dart, expense.dart). Cùng cấu trúc phẳng ấy mà đặt `home_screen.dart` +
        // `expense_model.dart` + `expense_controller.dart` thì ĐẠT CẢ BỐN mà vẫn không có thư
        // mục nào: luật cũ chấm THÓI QUEN ĐẶT TÊN chứ không chấm kiến trúc.
        //
        // TỪ VỰNG BỐN TẦNG RỜI NHAU: không tên thư mục nào xuất hiện ở hai tầng, nên yêu cầu
        // "mỗi tầng một thư mục riêng" thành đúng theo CẤU TẠO, không cần engine biết khái niệm
        // đó. Dồn cả bốn tầng vào `lib/app/` là trượt cả bốn; `lib/data/book.dart` không bao giờ
        // được tính là Model vì `data` không nằm trong từ vựng của tầng Model.
        //
        // ĐÁNH ĐỔI đã biết: bố cục feature-first (`lib/features/expense/expense_repository.dart`)
        // nay trượt. Chấp nhận được vì phiếu chấm của đề này đòi chia theo TẦNG; muốn chấm
        // feature-first thì phải thêm preset khác chứ không nới bốn luật này.
        List<String> tmModel = thuMuc("model", "models", "entity", "entities", "dto", "dtos");
        out.add(preset("ARCH_MODEL", "Tách Model thành thư mục riêng", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(
                        req("Thư mục Model riêng", tmModel, null, 1, null),
                        req("Thư mục Model phải có khai báo class", tmModel, DAU_HIEU_CLASS, 1, null),
                        req("Thư mục Model không được chứa mã truy cập dữ liệu hay widget",
                                tmModel, DAU_HIEU_DU_LIEU_THO + "|" + DAU_HIEU_WIDGET, null, 0))),
                5, NHOM_MAC_DINH, "PROJ_FOLDER_STRUCTURE",
                "Class dữ liệu nằm trong thư mục riêng: models/, entity/, dto/ (được lồng trong thư mục con). "
                        + "Trong đó phải có khai báo class, và KHÔNG được chứa mã DB/API hay widget — "
                        + "nhét database_helper.dart vào models/ là không đạt."));
        List<String> tmData = thuMuc("data", "datasource", "datasources", "repository", "repositories", "repo",
                "service", "services", "db", "database", "dao", "storage", "api");
        out.add(preset("ARCH_DATA", "Tách tầng dữ liệu khỏi giao diện", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(
                        req("Thư mục tầng dữ liệu riêng", tmData, null, 1, null),
                        req("Thư mục tầng dữ liệu phải có mã đọc/ghi dữ liệu thật",
                                tmData, DAU_HIEU_DU_LIEU, 1, null))),
                5, NHOM_MAC_DINH, "PROJ_FOLDER_STRUCTURE",
                "Truy cập DB/API nằm trong thư mục riêng: data/, repository/, service/, db/, dao/, api/. "
                        + "Thư mục đó phải chứa mã đọc/ghi thật (sqflite, http, SharedPreferences...) — "
                        + "để database_helper.dart phẳng ở gốc lib/, hoặc chỉ ném file model vào data/, đều không đạt."));
        List<String> tmScreen = thuMuc("screen", "screens", "view", "views", "page", "pages", "ui");
        out.add(preset("ARCH_SCREEN", "Tách màn hình thành thư mục riêng", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(
                        req("Thư mục màn hình riêng", tmScreen, null, 1, null),
                        req("Thư mục màn hình phải có widget màn hình",
                                tmScreen, DAU_HIEU_WIDGET, 1, null))),
                5, NHOM_MAC_DINH, "PROJ_FOLDER_STRUCTURE",
                "Màn hình nằm trong thư mục riêng: screens/, pages/, views/, ui/ — thay vì dồn vào main.dart "
                        + "hoặc rải phẳng ở gốc lib/. Trong đó phải thật sự có widget màn hình."));
        List<String> tmLogic = thuMuc("logic", "viewmodel", "viewmodels", "view_model", "view_models",
                "controller", "controllers", "provider", "providers",
                "bloc", "blocs", "cubit", "cubits",
                "usecase", "usecases", "use_case", "use_cases", "notifier", "notifiers");
        out.add(preset("ARCH_LOGIC", "Tách logic/ViewModel khỏi màn hình", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(
                        req("Thư mục logic/ViewModel riêng", tmLogic, null, 1, null),
                        // Không cấm `.insert(` ở tầng này: controller gọi `repo.insert(...)` là ĐÚNG
                        // kiến trúc. Chỉ cấm nói chuyện thẳng với DB/HTTP.
                        req("Thư mục logic không được chứa widget hay gọi DB/API trực tiếp",
                                tmLogic, DAU_HIEU_DU_LIEU_THO + "|" + DAU_HIEU_WIDGET, null, 0))),
                5, NHOM_MAC_DINH, "PROJ_FOLDER_STRUCTURE",
                "Xử lý nghiệp vụ nằm trong thư mục riêng: logic/, viewmodels/, controllers/, providers/, bloc/ "
                        + "— không trộn vào build(), không để chung thư mục với màn hình, và không gọi thẳng DB/API."));
        out.add(preset("STATE_RIVERPOD", "Dùng Riverpod quản lý state", KIND_SOURCE_PATTERN, null,
                Map.of("require", List.of(
                        req("Import Riverpod", List.of("lib/**.dart"),
                                "import\\s+'package:(flutter_riverpod|hooks_riverpod|riverpod)", 1, null),
                        req("Sử dụng Riverpod (widget/provider/ref)", List.of("lib/**.dart"),
                                "(ConsumerWidget|ConsumerStatefulWidget|ProviderScope|ref\\.watch|ref\\.read"
                                        + "|StateNotifierProvider|NotifierProvider|StateProvider|FutureProvider"
                                        + "|StreamProvider|ChangeNotifierProvider)", 1, null))),
                10, NHOM_MAC_DINH, "STATE_RIVERPOD",
                "Bài phải import flutter_riverpod VÀ thực sự dùng (ConsumerWidget, ref.watch, ...Provider). Chỉ tick khi Golden cũng dùng Riverpod."));
        out.add(preset("LINT_EMPTY_CATCHES", "Không nuốt lỗi — cấm khối catch rỗng", KIND_LINT, "empty_catches",
                null, 5, NHOM_MAC_DINH, "CODE_QUALITY_LINT",
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
     * KIỂM ĐỊNH DANH — soát CHÍNH file vừa tải lên, TRƯỚC khi nó được lưu.
     *
     * <p>Phải đọc từ luồng chứ không đọc từ kho: mục đích là từ chối nhận file, mà từ chối thì
     * không được để lại dấu vết nào trong kho artifact. Đọc lại kho thì đã muộn — bản lệch đã thành
     * bản đang hoạt động, và nếu bộ chấm đang có một Golden tốt thì nó vừa bị đè mất.
     *
     * <p>Bỏ qua entry lớn bất thường (cùng ngưỡng 2 MB với {@code goldenSources}): file Dart của
     * bài giải không có cái nào cỡ đó, còn zip nhồi thì không được phép làm treo khâu tải lên.
     *
     * @return {@code null} khi không lệch; khác null là câu báo NGẮN để từ chối nhận file
     */
    public String loiDinhDanhTrongZip(InputStream zip) {
        Map<String, String> nguon = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(zip, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String ten = entry.getName().replace('\\', '/');
                if (!ten.endsWith(".dart")) continue;
                String rel;
                if (ten.startsWith("lib/")) rel = ten;
                else {
                    int i = ten.indexOf("/lib/");
                    if (i < 0) continue;
                    rel = ten.substring(i + 1);
                }
                byte[] noiDung = in.readNBytes(2 * 1024 * 1024);
                nguon.put(rel, new String(noiDung, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // Zip hỏng thì để khâu tải lên báo bằng câu của nó; đừng đổ tội cho định danh.
            return null;
        }
        if (nguon.isEmpty()) return null;   // không có lib/: khâu kia lo
        KiemDinhDanh.KetQua kq = KiemDinhDanh.kiem(nguon);
        return kq.coLech() ? kq.gon() : null;
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
            // Cùng bộ field với dòng hành vi (xem BehaviorSuiteMaterializer.buildMatrix): chỉ
            // giữ thứ có người đọc. Riêng static_rule/static_config là ruột của runner tĩnh
            // (grader-base/scripts/static_checks.dart) nên phải ở lại.
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runner", "STATIC_ANALYSIS");
            row.put("group_id", rule.get("group_id"));
            row.put("name", rule.get("name"));
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
        // Luật tĩnh mặc định thuộc nhóm "Architecture" (chốt 21/9/2026) — không bắt khai nữa.
        // `group_name` đã gỡ hẳn: nó luôn trùng group_id nên không mang tin gì.
        String groupId = text(raw, "group_id");
        if (groupId.isBlank()) groupId = NHOM_MAC_DINH;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("kind", kind);
        out.put("weight", weight);
        out.put("group_id", groupId);
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
            for (Object raw : list(root.get("rules"))) out.add(theoPresetMoiNhat(map(raw)));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("static_rules_json của suite hỏng: " + e.getMessage(), e);
        }
    }

    /**
     * Luật mang mã của một preset thì ĐỊNH NGHĨA luôn lấy từ preset hiện tại, không lấy bản
     * đã đóng băng trong DB.
     *
     * <p>Vì sao: giáo viên không sửa được cấu hình preset ở giao diện — họ chỉ tick và chia
     * điểm — nên bản trong DB chỉ là ảnh chụp của preset lúc bấm Lưu. Giữ ảnh chụp đó nghĩa là
     * mọi đề đã soạn vẫn republish ra luật LỎNG cũ (mẫu hậu tố `lib/**_helper.dart`) dù code đã
     * siết, mà chẳng có dấu hiệu gì trên màn hình. Đúng bẫy này từng làm FA26 chấm sai.
     *
     * <p>Giữ nguyên thứ THUỘC VỀ NGƯỜI SOẠN: trọng số, nhóm điểm và tiên quyết. Luật tự khai
     * (mã không trùng preset nào) thì không đụng tới.
     */
    private Map<String, Object> theoPresetMoiNhat(Map<String, Object> rule) {
        String id = text(rule, "id");
        Map<String, Object> preset = presets().stream()
                .filter(p -> id.equals(text(p, "id")))
                .findFirst().orElse(null);
        if (preset == null || !text(preset, "kind").equals(text(rule, "kind"))) return rule;
        Map<String, Object> out = new LinkedHashMap<>(rule);
        out.put("name", preset.get("name"));
        out.put("description", preset.get("description"));
        out.put("skill_code", preset.get("skill_code"));
        if (preset.get("config") != null) out.put("config", preset.get("config"));
        if (preset.get("lint_code") != null) out.put("lint_code", preset.get("lint_code"));
        return out;
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
