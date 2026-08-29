package com.example.grader.controller;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phiếu chấm tay in được, render TRỰC TIẾP từ skills_matrix.json của đề.
 *
 * Vì sao không viết phiếu thành văn bản rời: phiếu 17 dòng đời trước đã lệch khỏi
 * ma trận (còn nguyên 5 dòng unit-tier đã bỏ) ngay lần sửa đề đầu tiên. Phiếu này
 * đọc đúng file mà máy chấm đang dùng nên theo định nghĩa không thể lệch — đề đổi
 * thì bấm in lại là xong.
 *
 * Nguyên tắc đối chiếu người–máy (S-01): mỗi dòng chỉ Đạt/Không (nhị phân như máy),
 * điểm lẻ nổi lên ở cấp NHÓM, tổng /100 quy về /10 cùng công thức với merger.
 */
final class GradingSheetBuilder {

    private GradingSheetBuilder() {}

    /** Một phần của phiếu, theo thứ tự NGƯỜI CHẤM nên làm — UI màn gốc phải nhìn trước khi nhập dữ liệu. */
    private record Section(String key, String title, String hint) {}

    private static final List<Section> SECTIONS = List.of(
            new Section("ui", "PHẦN I — GIAO DIỆN MÀN GỐC",
                    "Nhìn NGAY khi vừa mở app, TRƯỚC khi nhập bất kỳ dữ liệu nào."),
            new Section("behavior", "PHẦN II — HÀNH VI CHỨC NĂNG",
                    "Thao tác trực tiếp trên app theo tên từng tiêu chí. Dòng ghi \"sau khi mở lại\": tắt hẳn app rồi mở lại để kiểm dữ liệu còn lưu."),
            new Section("static", "PHẦN III — MÃ NGUỒN & KIẾN TRÚC",
                    "Mở thư mục lib/ của bài nộp và đối chiếu theo tên tiêu chí."));

    static String render(String examId, String examName, Integer testcaseVersion, JsonNode matrix) {
        // Gom dòng theo nhóm, giữ thứ tự xuất hiện trong ma trận; nhóm xếp theo phần.
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        matrix.properties().forEach(entry -> {
            JsonNode row = entry.getValue();
            String layer = text(row, "layer");
            if (layer.isBlank()) {
                layer = "STATIC_ANALYSIS".equals(text(row, "runner")) ? "static" : "behavior";
            }
            String groupId = text(row, "group_id");
            if (groupId.isBlank()) groupId = "G_KHAC";
            String groupName = text(row, "group_name");
            if (groupName.isBlank()) groupName = "Tiêu chí khác";
            Map<String, Object> group = groups.computeIfAbsent(groupId, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("name", "");
                g.put("layer", "behavior");
                g.put("rows", new ArrayList<Map<String, Object>>());
                return g;
            });
            group.put("name", groupName);
            group.put("layer", layer);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", text(row, "name").isBlank() ? entry.getKey() : text(row, "name"));
            item.put("weight", row.path("weight").asDouble(0));
            rows(group).add(item);
        });

        double grandTotal = 0;
        StringBuilder body = new StringBuilder();
        int sectionIndex = 0;
        for (Section section : SECTIONS) {
            List<Map.Entry<String, Map<String, Object>>> inSection = groups.entrySet().stream()
                    .filter(e -> section.key().equals(e.getValue().get("layer")))
                    .toList();
            if (inSection.isEmpty()) continue;
            sectionIndex++;
            body.append("<div class=\"section\"><h2>").append(esc(section.title()))
                    .append("</h2><p class=\"hint\">").append(esc(section.hint())).append("</p>");
            int groupIndex = 0;
            for (var groupEntry : inSection) {
                groupIndex++;
                Map<String, Object> group = groupEntry.getValue();
                double groupTotal = rows(group).stream()
                        .mapToDouble(r -> (double) r.get("weight")).sum();
                grandTotal += groupTotal;
                body.append("<table class=\"group\"><thead><tr class=\"group-head\"><th colspan=\"2\">")
                        .append(sectionIndex).append(".").append(groupIndex).append("&nbsp; ")
                        .append(esc(String.valueOf(group.get("name"))))
                        .append("</th><th class=\"num\">Điểm</th><th class=\"tick\">Đạt</th>")
                        .append("<th class=\"tick\">Không</th><th class=\"note\">Ghi chú</th></tr></thead><tbody>");
                int rowIndex = 0;
                for (Map<String, Object> row : rows(group)) {
                    rowIndex++;
                    body.append("<tr><td class=\"stt\">").append(rowIndex)
                            .append("</td><td class=\"crit\">").append(esc(String.valueOf(row.get("name"))))
                            .append("</td><td class=\"num\">").append(num((double) row.get("weight")))
                            .append("</td><td class=\"tick\">&#9744;</td><td class=\"tick\">&#9744;</td>")
                            .append("<td class=\"note\"></td></tr>");
                }
                body.append("<tr class=\"subtotal\"><td colspan=\"2\">Điểm nhóm (tổng các dòng Đạt)</td>")
                        .append("<td class=\"num\">/ ").append(num(groupTotal))
                        .append("</td><td colspan=\"3\" class=\"fill\">= ______________</td></tr>")
                        .append("</tbody></table>");
            }
            body.append("</div>");
        }

        String version = testcaseVersion == null ? "?" : String.valueOf(testcaseVersion);
        return """
                <!doctype html><html lang="vi"><head><meta charset="utf-8">
                <title>Phiếu chấm tay — %s</title>
                <style>
                  @page { size: A4; margin: 14mm 12mm; }
                  * { box-sizing: border-box; }
                  body { font: 13px/1.45 "Segoe UI", Arial, sans-serif; color: #111;
                         max-width: 860px; margin: 0 auto; padding: 16px; }
                  h1 { font-size: 19px; margin: 0 0 2px; }
                  h2 { font-size: 14.5px; margin: 18px 0 2px; border-bottom: 2px solid #111; padding-bottom: 3px; }
                  .sub { color: #444; margin: 0 0 10px; }
                  .meta { display: flex; gap: 24px; flex-wrap: wrap; margin: 10px 0 12px; font-size: 13px; }
                  .meta span { border-bottom: 1px dotted #666; min-width: 190px; display: inline-block; }
                  .rules { border: 1.5px solid #111; padding: 8px 12px; margin: 0 0 6px; }
                  .rules b { display: block; margin-bottom: 4px; }
                  .rules ol { margin: 0; padding-left: 18px; }
                  .hint { color: #444; margin: 2px 0 6px; font-style: italic; }
                  table.group { width: 100%%; border-collapse: collapse; margin: 6px 0 12px;
                                page-break-inside: avoid; }
                  th, td { border: 1px solid #333; padding: 4px 7px; vertical-align: top; }
                  tr.group-head th { background: #eee; text-align: left; font-size: 13px; }
                  td.stt { width: 26px; text-align: center; }
                  .num { width: 48px; text-align: center; white-space: nowrap; }
                  .tick { width: 44px; text-align: center; font-size: 16px; }
                  td.note, th.note { width: 150px; }
                  tr.subtotal td { font-weight: 700; background: #f6f6f6; }
                  td.fill { font-weight: 700; }
                  .total { border: 2px solid #111; padding: 10px 14px; margin: 14px 0;
                           font-size: 15px; font-weight: 700; display: flex; gap: 40px; flex-wrap: wrap; }
                  .sign { display: flex; justify-content: space-between; margin-top: 22px; }
                  .sign div { text-align: center; width: 45%%; }
                  .sign .line { margin-top: 54px; border-top: 1px solid #333; }
                  .stamp { color: #666; font-size: 11px; margin-top: 16px; }
                  @media print { body { padding: 0; } .noprint { display: none; } }
                </style></head><body>
                <h1>PHIẾU CHẤM TAY — %s</h1>
                <p class="sub">%s</p>
                <div class="meta">
                  <div>Mã SV: <span>&nbsp;</span></div>
                  <div>Người chấm: <span>&nbsp;</span></div>
                  <div>Ngày: <span>&nbsp;</span></div>
                </div>
                <div class="rules"><b>Cách chấm (để đối chiếu được với máy):</b><ol>
                  <li>Mỗi dòng chỉ tick <b>Đạt</b> hoặc <b>Không</b> — không cho điểm lẻ từng dòng. Đạt = trọn số điểm của dòng, Không = 0.</li>
                  <li>Chấm đúng thứ tự ba phần: giao diện màn gốc nhìn <b>trước khi nhập dữ liệu</b>, rồi mới thao tác chức năng, cuối cùng mở mã nguồn.</li>
                  <li>Điểm nhóm = tổng điểm các dòng Đạt trong nhóm. Điểm lẻ (ví dụ 15/20) nổi lên ở cấp nhóm, đúng như máy.</li>
                  <li>Không chấm được một dòng (app không chạy tới đó) thì tick Không và ghi lý do vào Ghi chú.</li>
                </ol></div>
                %s
                <div class="total">
                  <div>TỔNG: ______ / %s</div>
                  <div>Quy đổi hệ 10 (= tổng ÷ 10): ______</div>
                </div>
                <div class="sign"><div>Người chấm<br><span class="line"></span></div>
                <div>Đối chiếu máy (điểm máy: ______)<br><span class="line"></span></div></div>
                <p class="stamp">Sinh tự động từ skills_matrix.json — đề %s, phiên bản testcase v%s, in ngày %s.
                Phiếu luôn khớp bộ tiêu chí máy đang chấm; đề đổi thì in lại phiếu.</p>
                <p class="noprint"><b>Mẹo:</b> bấm Ctrl+P để in hoặc lưu PDF.</p>
                </body></html>
                """.formatted(
                esc(examId), esc(examId),
                esc(examName == null || examName.isBlank() ? "" : examName),
                body.toString(), num(grandTotal),
                esc(examId), esc(version), LocalDate.now());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("rows");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("").trim();
    }

    /** 5.0 → "5", 2.5 → "2.5" — phiếu in không cần đuôi .0. */
    private static String num(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String esc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
