package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dựng KHUNG STARTER phát cho sinh viên từ bản mô tả của AI.
 *
 * <p>Nguyên tắc quan trọng nhất: <b>AI chỉ mô tả KHUNG</b> (tên file, tên class, danh sách thuộc
 * tính, chữ ký hàm) — <b>toàn bộ thân hàm do lớp này sinh</b> và luôn là {@code TODO} /
 * {@code UnimplementedError} / {@code Placeholder}. Nhờ vậy AI về nguyên tắc KHÔNG thể lén cài sẵn
 * phần UI hay logic mà đề bài đang bắt sinh viên tự làm, dù prompt có bị model hiểu sai thế nào.
 *
 * <p>Hệ quả thứ hai: code sinh ra luôn cùng một khuôn nên biên dịch được. Kiểu dữ liệu lạ (không
 * phải kiểu dựng sẵn, không phải class có trong chính bộ khung này) bị loại kèm cảnh báo thay vì
 * để sinh viên nhận một starter không build nổi.
 */
@Component
public class StarterRenderer {

    private static final Pattern PATH = Pattern.compile("^lib/(?:[a-z0-9_]+/)*[a-z0-9_]+\\.dart$");
    private static final Pattern CLASS_NAME = Pattern.compile("^[A-Z][A-Za-z0-9_]*$");
    private static final Pattern MEMBER_NAME = Pattern.compile("^[a-z_][A-Za-z0-9_]*$");
    private static final Pattern TYPE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(?:<[A-Za-z0-9_<>,?\\s]*>)?\\??$");
    /** Chữ ký GETTER trần: "int get count", "List&lt;User&gt; get users". */
    private static final Pattern GETTER = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_<>,?\\s]*?\\s+get\\s+(?<name>[a-z_][A-Za-z0-9_]*)$");

    /**
     * Kiểu dựng sẵn của Dart/Flutter được phép xuất hiện mà không cần import thêm.
     *
     * <p>Danh sách này từng chỉ có kiểu Dart lõi, nên khung starter bị loại đúng những thứ bài thi
     * Flutter nào cũng cần — {@code TextEditingController}, {@code GlobalKey<FormState>},
     * {@code ValueNotifier} — kèm một loạt cảnh báo "không có trong khung starter" đọc như thể AI
     * mô tả sai. Tất cả đều có sẵn trong {@code package:flutter/material.dart}.
     */
    private static final Set<String> BUILTIN_TYPES = Set.of(
            "int", "double", "num", "String", "bool", "void", "dynamic", "Object", "DateTime",
            "List", "Map", "Set", "Iterable", "Future", "Stream", "Widget", "BuildContext",
            "Key", "ValueKey", "Duration", "Uri",
            // Form & nhập liệu
            "GlobalKey", "FormState", "TextEditingController", "FocusNode", "ScrollController",
            // Quản lý trạng thái sẵn có trong Flutter
            "ValueNotifier", "ChangeNotifier", "Listenable",
            // Khung widget
            "StatelessWidget", "StatefulWidget", "State", "MaterialApp", "Scaffold",
            "TextStyle", "EdgeInsets", "Color", "IconData", "Size");

    private static final Set<String> SCREEN_KINDS = Set.of("screen", "page", "view", "widget");

    public record Rendered(List<Map<String, Object>> files, List<String> warnings) {}

    /**
     * @param spec        bản mô tả khung của AI: { entry_class, files: [...] }
     * @param examKeysDart nội dung lib/exam_keys.dart dựng sẵn từ hợp đồng (không do AI viết)
     * @param keyLabels   key → nhãn, để ghi chú key nào thuộc màn hình nào
     */
    public Rendered render(JsonNode spec, String examKeysDart, Map<String, String> keyLabels) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();

        // Vòng 1: lọc file hợp lệ và lập sổ class → đường dẫn (để tự tính import, không tin AI).
        List<JsonNode> valid = new ArrayList<>();
        Map<String, String> classPath = new LinkedHashMap<>();
        Set<String> paths = new LinkedHashSet<>();
        for (JsonNode file : spec == null ? List.<JsonNode>of() : iterable(spec.path("files"))) {
            String path = text(file.path("path"), "");
            String className = text(file.path("class_name"), "");
            if (!PATH.matcher(path).matches()) {
                warnings.add("Bỏ file có đường dẫn không hợp lệ: " + path);
                continue;
            }
            if ("lib/exam_keys.dart".equals(path) || "lib/main.dart".equals(path)) {
                continue;      // hai file này hệ thống tự dựng, không lấy bản của AI
            }
            if (!CLASS_NAME.matcher(className).matches()) {
                warnings.add("Bỏ file " + path + ": tên class không hợp lệ (" + className + ")");
                continue;
            }
            if (!paths.add(path)) {
                warnings.add("Bỏ file trùng đường dẫn: " + path);
                continue;
            }
            classPath.put(className, path);
            valid.add(file);
        }

        // Vòng 2: sinh code.
        for (JsonNode file : valid) {
            String path = text(file.path("path"), "");
            String className = text(file.path("class_name"), "");
            String kind = text(file.path("kind"), "other").toLowerCase();
            out.add(fileRow(path, renderClass(file, path, className, kind, classPath, keyLabels, warnings),
                    describeKind(kind, className)));
        }

        // File hằng số key: dựng từ hợp đồng đã chốt, KHÔNG qua AI.
        if (examKeysDart != null && !examKeysDart.isBlank()) {
            out.add(0, fileRow("lib/exam_keys.dart", examKeysDart,
                    "Hằng số ValueKey do đề thi quy định (tùy chọn dùng)"));
        }

        // Điểm vào ứng dụng: engine chấm phải chạy được main() thì mới kiểm tra được gì.
        String entryClass = text(spec == null ? null : spec.path("entry_class"), null);
        if (entryClass == null || !classPath.containsKey(entryClass)) {
            entryClass = valid.stream()
                    .filter(f -> SCREEN_KINDS.contains(text(f.path("kind"), "").toLowerCase()))
                    .map(f -> text(f.path("class_name"), ""))
                    .findFirst().orElse(null);
        }
        if (entryClass != null) {
            out.add(fileRow("lib/main.dart", renderMain(entryClass, classPath.get(entryClass)),
                    "Điểm vào ứng dụng"));
        } else {
            warnings.add("Không xác định được màn hình chính nên chưa dựng lib/main.dart — "
                    + "hãy thêm một file kind=screen rồi sinh lại.");
        }
        return new Rendered(out, warnings);
    }

    // ── Sinh từng loại file ──────────────────────────────────────

    private String renderClass(JsonNode file, String path, String className, String kind,
                               Map<String, String> classPath, Map<String, String> keyLabels,
                               List<String> warnings) {
        List<Map<String, String>> fields = fields(file, className, classPath, warnings);
        List<Map<String, String>> methods = methods(file, className, classPath, warnings);
        boolean screen = SCREEN_KINDS.contains(kind);

        Set<String> imports = new LinkedHashSet<>();
        if (screen) imports.add("package:flutter/material.dart");
        for (Map<String, String> f : fields) addImport(imports, f.get("type"), path, classPath);
        for (Map<String, String> m : methods) addImport(imports, m.get("types"), path, classPath);

        StringBuilder out = new StringBuilder();
        imports.forEach(i -> out.append("import '").append(i).append("';\n"));
        if (!imports.isEmpty()) out.append('\n');

        String doc = oneLineDoc(text(file.path("doc"), describeKind(kind, className)));
        out.append("/// ").append(doc).append('\n');
        List<String> keys = textList(file.path("keys"));
        if (!keys.isEmpty()) {
            out.append("///\n/// Key giao diện đề thi yêu cầu ở đây — gắn ĐÚNG chuỗi này bằng\n")
               .append("/// ValueKey('...') vào widget tương ứng, sai một ký tự là mất điểm:\n");
            for (String key : keys) {
                String label = keyLabels == null ? null : keyLabels.get(key);
                out.append("/// - ").append(key);
                if (label != null && !label.isBlank()) out.append(": ").append(label);
                out.append('\n');
            }
        }

        if (screen) {
            out.append("class ").append(className).append(" extends StatelessWidget {\n")
               .append("  const ").append(className).append("({super.key});\n\n");
            appendFields(out, fields);
            appendMethods(out, methods, className);
            out.append("  @override\n")
               .append("  Widget build(BuildContext context) {\n")
               .append("    // TODO(sinh viên): dựng giao diện theo yêu cầu của đề.\n")
               .append("    // Đổi sang StatefulWidget/ConsumerWidget nếu bạn cần.\n")
               .append("    return const Placeholder();\n")
               .append("  }\n")
               .append("}\n");
            return out.toString();
        }

        out.append("class ").append(className).append(" {\n");
        if (!fields.isEmpty() && "model".equals(kind)) {
            out.append("  const ").append(className).append("({");
            List<String> params = new ArrayList<>();
            for (Map<String, String> f : fields) {
                boolean nullable = f.get("type").endsWith("?");
                params.add((nullable ? "" : "required ") + "this." + f.get("name"));
            }
            out.append(String.join(", ", params)).append("});\n\n");
        }
        appendFields(out, fields);
        if (methods.isEmpty() && !"model".equals(kind)) {
            out.append("  // TODO(sinh viên): bổ sung thuộc tính và hàm cần thiết.\n");
        }
        appendMethods(out, methods, className);
        out.append("}\n");
        return out.toString();
    }

    private void appendFields(StringBuilder out, List<Map<String, String>> fields) {
        for (Map<String, String> f : fields) {
            String doc = f.get("doc");
            if (doc != null && !doc.isBlank()) out.append("  /// ").append(doc).append('\n');
            out.append("  final ").append(f.get("type")).append(' ').append(f.get("name")).append(";\n");
        }
        if (!fields.isEmpty()) out.append('\n');
    }

    /** Thân hàm LUÔN là UnimplementedError — đây là chốt chặn "AI không được viết logic". */
    private void appendMethods(StringBuilder out, List<Map<String, String>> methods, String className) {
        for (Map<String, String> m : methods) {
            String doc = m.get("doc");
            out.append("  /// TODO(sinh viên): ")
               .append(doc == null || doc.isBlank() ? "hoàn thiện theo yêu cầu của đề." : doc)
               .append('\n')
               .append("  ").append(m.get("signature")).append(" {\n")
               .append("    throw UnimplementedError('")
               .append(className).append('.').append(m.get("name"))
               .append(" — sinh viên hoàn thiện theo đề bài.');\n")
               .append("  }\n\n");
        }
    }

    private String renderMain(String entryClass, String entryPath) {
        String importPath = entryPath == null ? null : entryPath.substring("lib/".length());
        return "import 'package:flutter/material.dart';\n"
                + (importPath == null ? "" : "import '" + importPath + "';\n")
                + "\n"
                + "void main() {\n"
                + "  // TODO(sinh viên): bọc thêm ProviderScope (hoặc thứ đề yêu cầu) nếu cần.\n"
                + "  runApp(const MyApp());\n"
                + "}\n"
                + "\n"
                + "class MyApp extends StatelessWidget {\n"
                + "  const MyApp({super.key});\n"
                + "\n"
                + "  @override\n"
                + "  Widget build(BuildContext context) {\n"
                + "    return const MaterialApp(\n"
                + "      debugShowCheckedModeBanner: false,\n"
                + "      home: " + entryClass + "(),\n"
                + "    );\n"
                + "  }\n"
                + "}\n";
    }

    // ── Lọc thành phần AI mô tả ──────────────────────────────────

    private List<Map<String, String>> fields(JsonNode file, String className,
                                             Map<String, String> classPath, List<String> warnings) {
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode f : iterable(file.path("fields"))) {
            String name = text(f.path("name"), "");
            String type = text(f.path("type"), "");
            if (!MEMBER_NAME.matcher(name).matches() || !TYPE.matcher(type).matches()) {
                warnings.add("Bỏ thuộc tính không hợp lệ ở " + className + ": " + name + " " + type);
                continue;
            }
            if (!knownTypes(type, classPath)) {
                warnings.add("Bỏ thuộc tính " + className + "." + name
                        + " vì kiểu \"" + type + "\" không có trong khung starter.");
                continue;
            }
            if (!names.add(name)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("type", type);
            row.put("doc", oneLineDoc(text(f.path("doc"), "")));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, String>> methods(JsonNode file, String className,
                                              Map<String, String> classPath, List<String> warnings) {
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode m : iterable(file.path("methods"))) {
            String signature = text(m.path("signature"), "").trim();
            // Chữ ký phải là chữ ký TRẦN. Có '{', '=>' hay ';' nghĩa là AI đang cố nhét thân hàm.
            if (signature.isEmpty() || signature.contains("{") || signature.contains("}")
                    || signature.contains("=>") || signature.contains(";")
                    || signature.contains("\n")) {
                warnings.add("Bỏ hàm ở " + className + " vì chữ ký không hợp lệ hoặc kèm sẵn code: "
                        + shorten(signature));
                continue;
            }

            // GETTER ("int get count") không có ngoặc — đòi nó kết thúc bằng ')' là loại oan một
            // dạng thành viên hoàn toàn hợp lệ, mà `count`/`isEditMode` lại đúng là thứ khung
            // starter cần khai để sinh viên biết phải tự tính.
            java.util.regex.Matcher getter = GETTER.matcher(signature);
            String name;
            boolean factory = false;
            if (getter.matches()) {
                name = getter.group("name");
            } else if (signature.endsWith(")")) {
                int paren = signature.indexOf('(');
                String head = paren < 0 ? "" : signature.substring(0, paren).trim();
                int space = head.lastIndexOf(' ');
                if (space <= 0) {
                    // CONSTRUCTOR CÓ TÊN ("User.fromMap(Map<String, dynamic> map)") — không có kiểu
                    // trả về là ĐÚNG cú pháp Dart, loại nó đi là bỏ mất hàm dựng từ SQLite mà đề
                    // nào cũng cần. Sinh dạng factory: constructor thường bắt buộc gán hết field
                    // final ngay tại chỗ, mà thân hàm ở đây luôn là TODO.
                    if (head.startsWith(className + ".") && head.length() > className.length() + 1) {
                        name = head.substring(className.length() + 1).trim();
                        factory = true;
                    } else {
                        warnings.add("Bỏ hàm ở " + className + " vì thiếu kiểu trả về: " + shorten(signature));
                        continue;
                    }
                } else {
                    name = head.substring(space + 1).trim();
                }
            } else {
                warnings.add("Bỏ hàm ở " + className + " vì chữ ký không hợp lệ hoặc kèm sẵn code: "
                        + shorten(signature));
                continue;
            }
            if (!MEMBER_NAME.matcher(name).matches() || "build".equals(name)) {
                warnings.add("Bỏ hàm không hợp lệ ở " + className + ": " + shorten(signature));
                continue;
            }
            String types = signature.replaceAll("[(),]", " ");
            if (!knownTypes(types, classPath)) {
                warnings.add("Bỏ hàm " + className + "." + name
                        + " vì dùng kiểu dữ liệu không có trong khung starter.");
                continue;
            }
            if (!names.add(name)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("signature", factory ? "factory " + signature : signature);
            row.put("types", types);
            // Chú thích của AI KHÔNG được giữ: nó luôn là các bước làm bài ("validate form, gọi
            // ViewModel addUser, reset form nếu thành công") — chép nguyên vào khung thì sinh viên
            // chỉ còn việc gõ lại. Khung chỉ nói CHỖ NÀY PHẢI LÀM GÌ qua chính tên hàm.
            row.put("doc", "");
            out.add(row);
        }
        return out;
    }

    /** Mọi định danh viết hoa đầu trong chuỗi kiểu phải là kiểu dựng sẵn hoặc class của starter. */
    private boolean knownTypes(String raw, Map<String, String> classPath) {
        java.util.regex.Matcher m = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b").matcher(raw);
        while (m.find()) {
            String type = m.group();
            if (!BUILTIN_TYPES.contains(type) && !classPath.containsKey(type)) return false;
        }
        return true;
    }

    /** Import tương đối tính từ đường dẫn hai file — không dùng đường dẫn do AI tự khai. */
    private void addImport(Set<String> imports, String types, String fromPath,
                           Map<String, String> classPath) {
        if (types == null) return;
        java.util.regex.Matcher m = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b").matcher(types);
        while (m.find()) {
            String target = classPath.get(m.group());
            if (target == null || target.equals(fromPath)) continue;
            imports.add(relativeImport(fromPath, target));
        }
    }

    private String relativeImport(String from, String to) {
        String[] fromParts = from.split("/");
        String[] toParts = to.split("/");
        int common = 0;
        while (common < fromParts.length - 1 && common < toParts.length - 1
                && fromParts[common].equals(toParts[common])) common++;
        StringBuilder out = new StringBuilder();
        for (int i = common; i < fromParts.length - 1; i++) out.append("../");
        for (int i = common; i < toParts.length; i++) {
            out.append(toParts[i]);
            if (i < toParts.length - 1) out.append('/');
        }
        return out.toString();
    }

    private String describeKind(String kind, String className) {
        return switch (kind) {
            case "model" -> "Lớp dữ liệu " + className;
            case "repository" -> "Nơi truy xuất dữ liệu cho " + className;
            case "viewmodel", "notifier", "provider" -> "Quản lý trạng thái: " + className;
            case "service" -> "Dịch vụ " + className;
            case "screen", "page", "view", "widget" -> "Màn hình " + className;
            default -> "Lớp " + className;
        };
    }

    private Map<String, Object> fileRow(String path, String content, String summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("content", content);
        row.put("summary", summary);
        return row;
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        return node == null || !node.isArray() ? List.of() : node;
    }

    private List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : iterable(node)) {
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

    private String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 59) + "…";
    }

    /**
     * Chú thích rút về MỘT dòng ngắn.
     *
     * <p>Thân hàm đã bị ép thành TODO, nên chỗ duy nhất còn lại để AI tuồn lời giải vào khung là
     * ô "doc": một đoạn mô tả dài kèm từng bước làm cũng chẳng khác gì bài mẫu chép sẵn. Khung
     * starter chỉ cần một câu nói file/hàm này để làm gì.
     */
    private String oneLineDoc(String raw) {
        if (raw == null) return "";
        String one = raw.replaceAll("\\s+", " ").trim();
        // Lấy CÂU ĐẦU: phần sau dấu chấm đầu tiên gần như luôn là các bước làm bài.
        int stop = one.indexOf(". ");
        if (stop > 0) one = one.substring(0, stop + 1);
        // Cắt ở dấu hiệu CODE: chú thích có "(", ";" hay "=>" là AI đang viết code bằng lời —
        // "Gọi Navigator.push(context, MaterialPageRoute(...))" chính là lời giải, chỉ khác chỗ đặt.
        for (String marker : new String[]{"=>", "(", ";"}) {
            int at = one.indexOf(marker);
            if (at > 0) one = one.substring(0, at).trim();
        }
        one = one.replaceAll("[\\s,:-]+$", "");
        return one.length() <= 100 ? one : one.substring(0, 99) + "…";
    }
}
