"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { createPortal } from "react-dom";
import { useRouter, useSearchParams } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  ArrowLeft, Check, CheckCircle2, ChevronDown, Circle, Code2, Copy, Database, Download, FileArchive, FileJson,
  Loader2, MonitorPlay, Pencil, Play, Plus, Radio, Send, ShieldCheck,
  Square, Trash2, UploadCloud, X, XCircle,
} from "lucide-react";

type JsonMap = Record<string, unknown>;
type DiemNhap = string | number;

// Giữ nguyên chuỗi khi gõ để ô trống và dấu thập phân không bị đổi thành điểm khác.
function diemSo(value: DiemNhap): number {
  const parsed = Number(String(value).trim().replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

function docDiem(value: DiemNhap, label: string, phaiDuong = false): number {
  const text = String(value).trim();
  if (!text) throw new Error(`${label} chưa nhập điểm.`);
  const score = Number(text.replace(",", "."));
  if (!/^-?(?:\d+(?:[.,]\d*)?|[.,]\d+)(?:e[+-]?\d+)?$/i.test(text) || !Number.isFinite(score) || score < 0) {
    throw new Error(`${label} phải là số điểm không âm hợp lệ.`);
  }
  if (phaiDuong && score === 0) throw new Error(`${label} phải lớn hơn 0 điểm.`);
  return score;
}

function kiemTraChiaDiem(total: DiemNhap, checkpoints: DiemNhap[], budget: number): number[] {
  const score = docDiem(total, "Hàm test", true);
  const weights = checkpoints.map((value, index) => docDiem(value, `Checkpoint ${index + 1}`));
  const sum = weights.reduce((tong, weight) => tong + weight, 0);
  if (weights.length === 1 && weights[0] === 0) throw new Error("Hàm test chỉ có 1 checkpoint thì checkpoint đó phải lớn hơn 0 điểm.");
  if (!Number.isFinite(sum) || sum <= 0 || Math.abs(sum - score) > Number.EPSILON * Math.max(sum, score) * Math.max(8, weights.length * 2)) throw new Error("Tổng điểm checkpoint phải bằng đúng điểm của hàm test.");
  if (score - budget > 1e-9) throw new Error(`Điểm hàm test vượt ngân sách: chỉ còn ${budget} điểm có thể phân bổ.`);
  return weights;
}

function phanBoDiem(total: number, weights: number[]): number[] {
  const sum = weights.reduce((tong, weight) => tong + weight, 0);
  if (sum <= 0 || total <= 0) return weights.map(() => 0);
  // Điểm đã chia đúng được giữ nguyên, kể cả điểm 0 và số lẻ do người soạn nhập.
  if (Math.abs(sum - total) <= Number.EPSILON * Math.max(sum, total) * 8) return [...weights];
  const [mantissa, exponent = "0"] = total.toString().split("e");
  const decimals = Math.max(3, (mantissa.split(".")[1]?.length ?? 0) - Number(exponent));
  if (decimals > 12) return weights.map((weight) => total * weight / sum);
  const scale = 10 ** decimals;
  const units = Math.round(total * scale);
  const parts = weights.map((weight) => Math.floor(units * weight / sum));
  let remainder = units - parts.reduce((tong, part) => tong + part, 0);
  const order = weights.map((weight, i) => ({ i, fraction: units * weight / sum - parts[i] }))
    .filter(({ i }) => weights[i] > 0).sort((a, b) => b.fraction - a.fraction);
  for (let i = 0; i < order.length && remainder > 0; i++, remainder--) parts[order[i].i]++;
  return parts.map((part) => part / scale);
}

function chiaDeu(total: number, n: number): number[] {
  return n > 0 ? phanBoDiem(total, Array(n).fill(1)) : [];
}

type ArtifactType =
  | "STUDENT_DATABASE"
  | "HIDDEN_DATABASE"
  | "GOLDEN_SOLUTION"
  | "AUTOMATION_RECORD"
  | "GRADING_ENVIRONMENT"
  | "TESTCASE_DEFINITION"
  | "OUTPUT_DATABASE";

interface GoldenApp { id: string; name: string; runtime_url?: string | null; status: string }
interface Suite { id: string; suite_code: string; exam_id?: string; golden_app_id: string; name: string; status: string; database_contract?: JsonMap; runtime_config?: JsonMap; recordings?: Recording[]; scenarios?: JsonMap[] }
interface Recording { id: string; suite_id: string; name: string; status: string; revision_scenario_id?: string | null; raw_trace?: JsonMap[]; initial_state?: JsonMap }
interface Artifact { id: string; type: ArtifactType; version: number; file_name: string; size_bytes: number; active: boolean; sha256: string }
interface MissingPackageSpec { name: string; version: string }
interface Readiness { ready: boolean; missing: ArtifactType[]; artifacts: Partial<Record<ArtifactType, Artifact | null>> }
/** Kết quả duyệt toàn bộ scenario xem cái nào còn khớp Golden hiện tại (không chạy Docker). */
interface SoatScenarioRow { id: string; scenario_code: string; name: string; ok: boolean; reasons: string[] }
interface SoatScenario { total: number; failed: string[]; golden_ready: boolean; scenarios: SoatScenarioRow[] }
interface GoldenValidation { status: "NOT_RUN" | "RUNNING" | "PASSED" | "FAILED" | "UNAVAILABLE"; current: boolean; total_checkpoints?: number; passed_checkpoints?: number; log?: string }
interface RuntimeStatus { status: string; runtime_url?: string | null; runtime_path?: string | null; available?: boolean; cached?: boolean; message?: string; metadata?: JsonMap }
interface CodePreviewFile { name: string; description: string; scope: "SCENARIO" | "BUNDLE" | "ENGINE"; content: string }
interface CodePreview { suite_id: string; suite_code: string; scenario_count: number; criterion_count: number; files: CodePreviewFile[] }
interface StaticRuleGolden { passed: boolean | null; detail: string }
interface StaticRule { id: string; name: string; kind: "lint" | "source_pattern"; lint_code?: string; config?: JsonMap; weight: number; group_id: string; skill_code?: string; description?: string; golden?: StaticRuleGolden }
interface StaticRulesView { suite_id: string; golden_available: boolean; rules: StaticRule[]; presets: StaticRule[] }

const ARTIFACTS: { type: ArtifactType; title: string; owner: "teacher" | "system"; accept: string; hint: string; icon: typeof Database }[] = [
  // Ô "Database phát cho sinh viên" đã bỏ hẳn: máy chấm chỉ nạp Database ẩn (engine đọc
  // hidden_fixture_path, không bao giờ mở student.db), còn việc canh cấu trúc bảng đã chuyển
  // sang khâu kiểm đồng bộ khung phát. Giữ một ô không ai dùng chỉ khiến người ra đề tưởng
  // mình thiếu bước.
  { type: "HIDDEN_DATABASE", title: "1. Database ẩn", owner: "teacher", accept: ".db,.sqlite,.sqlite3", hint: "Dữ liệu máy chấm nạp trước khi mở app. Cũng chính là dữ liệu bạn thấy trong khung Golden.", icon: ShieldCheck },
  { type: "GOLDEN_SOLUTION", title: "2. Golden Solution (lib + pubspec.yaml)", owner: "teacher", accept: ".zip", hint: "ZIP đáp án chuẩn phải có pubspec.yaml và thư mục lib/.", icon: FileArchive },
  { type: "AUTOMATION_RECORD", title: "3. Bản ghi thao tác", owner: "system", accept: ".json", hint: "Hệ thống sinh khi dừng phiên record.", icon: Radio },
  { type: "GRADING_ENVIRONMENT", title: "4. Môi trường chấm", owner: "system", accept: ".json", hint: "API, driver, browser và timeout của suite.", icon: MonitorPlay },
  { type: "TESTCASE_DEFINITION", title: "5. File testcase", owner: "system", accept: ".json", hint: "7 cột Stage, Attribute, AttributeValue, ValueType, Value, Action, Browser.", icon: FileJson },
  { type: "OUTPUT_DATABASE", title: "6. Output Database", owner: "system", accept: ".db,.sqlite,.sqlite3", hint: "Hệ thống replay Golden trên DB ẩn rồi tự capture trạng thái DB sau thao tác.", icon: Database },
];

const SUITE_STATUS_LABELS: Record<string, string> = {
  DRAFT: "Draft", RECORDING: "Recording", REVIEW: "In Review", PUBLISHED: "Published", DISABLED: "Disabled",
};

const SUITE_STATUS_STYLES: Record<string, string> = {
  DRAFT: "border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-800 dark:bg-amber-950/60 dark:text-amber-300",
  RECORDING: "border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-800 dark:bg-rose-950/60 dark:text-rose-300",
  REVIEW: "border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-800 dark:bg-blue-950/60 dark:text-blue-300",
  PUBLISHED: "border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300",
  DISABLED: "border-slate-200 bg-slate-100 text-slate-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400",
};

function SuiteStatusBadge({ status }: { status: string }) {
  return <span className={`inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-2 py-0.5 text-[11px] font-semibold ${SUITE_STATUS_STYLES[status] || SUITE_STATUS_STYLES.DISABLED}`}><span className="h-1.5 w-1.5 rounded-full bg-current" />{SUITE_STATUS_LABELS[status] || status}</span>;
}

const ACTIONS = [
  "boot", "boot_with_uri", "tap", "enter_text", "clear_text", "scroll", "drag", "back",
  "open_uri", "browser_back", "browser_forward", "reload", "restart",
  "wait_until", "wait_for_route",
];
// Đề chỉ còn MỘT hệ định danh. "valueKey" đã gỡ hẳn khỏi cả danh sách này lẫn engine
// (21/9/2026) — để lại một khoá thứ hai là mời người sau dựng lại hệ định danh song song.
const LOCATORS = ["semanticId", "label", "hint", "text", "text_prefix", "tooltip"];
const ACTION_LABELS: Record<string, string> = {
  boot: "Khởi động app",
  boot_with_uri: "Khởi động tại đường dẫn",
  tap: "Bấm",
  enter_text: "Nhập nội dung",
  clear_text: "Xóa nội dung",
  scroll: "Cuộn",
  drag: "Kéo",
  back: "Quay lại",
  open_uri: "Mở đường dẫn",
  browser_back: "Trình duyệt quay lại",
  browser_forward: "Trình duyệt tiến tới",
  reload: "Tải lại",
  restart: "Khởi động lại",
  wait_until: "Chờ điều kiện",
  wait_for_route: "Chờ đường dẫn",
  observe_ui: "Kiểm tra UI",
  observe_database: "Kiểm tra database",
  observe_route: "Kiểm tra đường dẫn",
  observe_layout: "Kiểm tra bố cục",
};
// Chữ hiển thị cho từng cách định vị. Giá trị gửi đi giữ nguyên; chỉ đổi chữ để không ai
// tưởng "semanticId" là ValueKey: máy chấm tìm nó bằng Semantics(identifier:).
function asJsonMap(value: unknown): JsonMap {
  return value && typeof value === "object" && !Array.isArray(value) ? value as JsonMap : {};
}

function readableValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

function summarizeExpectation(value: unknown): string {
  const expect = asJsonMap(value);
  const visible = Array.isArray(expect.visible_texts) ? expect.visible_texts.map(readableValue).filter(Boolean) : [];
  const hidden = Array.isArray(expect.hidden_texts) ? expect.hidden_texts.map(readableValue).filter(Boolean) : [];
  const semanticNodes = Array.isArray(expect.semantic_nodes) ? expect.semantic_nodes.length : 0;
  const parts: string[] = [];
  if (visible.length) parts.push(`Hiện: ${visible.join(", ")}`);
  if (hidden.length) parts.push(`Không hiện: ${hidden.join(", ")}`);
  if (semanticNodes) parts.push(`${semanticNodes} thành phần semantic`);
  if (expect.no_exception === true) parts.push("Không có exception");
  if (typeof expect.route === "string") parts.push(`Route: ${expect.route}`);
  // Ch.7 — in thẳng tên ô kỳ vọng của runner bố cục, lấy nhãn từ chính bảng dựng form
  // nên danh sách tiêu chí đọc lại được mà không phải mở JSON.
  const daIn = new Set<string>();
  for (const loai of CH7_LOAI) {
    for (const truong of loai.truong) {
      if (daIn.has(truong.khoa)) continue;
      const v = expect[truong.khoa];
      if (v === undefined || v === null || String(v).trim() === "") continue;
      daIn.add(truong.khoa);
      parts.push(`${truong.nhan}: ${String(v)}`);
    }
  }
  return parts.join(" · ") || (Object.keys(expect).length ? "Có dữ liệu kỳ vọng" : "");
}

const SEMANTIC_ROLES = [
  ["generic", "Không kiểm tra loại"],
  ["text_field", "Ô nhập liệu"],
  ["button", "Nút bấm"],
  ["checkbox", "Checkbox"],
  ["switch", "Switch"],
  ["radio", "Radio"],
  ["text", "Nội dung text"],
  ["image", "Hình ảnh / icon"],
  ["link", "Liên kết"],
] as const;

const LOCATOR_LABELS: Record<string, string> = {
  semanticId: "định danh — Semantics(identifier:)",
  label: "nhãn — Semantics(label:)",
  hint: "gợi ý ô nhập — hintText",
  text: "chữ hiển thị",
  text_prefix: "chữ bắt đầu bằng",
  tooltip: "tooltip",
};

/** Một ô nhập của tiêu chí bố cục Ch.7. */
type Ch7Truong = {
  khoa: string;
  nhan: string;
  batBuoc?: boolean;
  /** "dinhdanh" = gợi ý từ bảng Quét UI; "so"; "chon"; còn lại là chữ thường. */
  kieu?: "dinhdanh" | "so" | "chon" | "chu";
  chon?: [string, string][];
  goiY?: string;
  macDinh?: string;
};

/**
 * Tám runner bố cục của Ch.7. MỘT bảng lái tất cả: nhãn loại, nhãn ô đích, các ô
 * expect, phép kiểm trước khi gửi và câu cảnh báo. Thêm runner mới = thêm một dòng,
 * không phải sửa JSX.
 *
 * Đích LUÔN là định danh Semantics: backend từ chối lưu nếu thiếu target.semantic_id.
 */
const CH7_LOAI: {
  ma: string;
  ten: string;
  dichNhan: string;
  canhBao: string;
  truong: Ch7Truong[];
}[] = [
  {
    ma: "component_scroll_direction",
    ten: "Vùng cuộn đúng chiều",
    dichNhan: "Định danh bọc CHÍNH widget cuộn (ListView, GridView, SingleChildScrollView)",
    canhBao:
      "Đo được: bọc định danh quanh cụm con bên trong vùng cuộn thì tiêu chí trượt với câu “Không tìm thấy Scrollable bên trong”. Phải bọc đúng widget cuộn.",
    truong: [
      {
        khoa: "direction",
        nhan: "Chiều cuộn",
        batBuoc: true,
        kieu: "chon",
        chon: [["vertical", "dọc"], ["horizontal", "ngang"]],
      },
    ],
  },
  {
    ma: "component_scroll_to_end",
    ten: "Cuộn tới cuối thì thấy được phần tử",
    dichNhan: "Định danh vùng cuộn",
    canhBao:
      "Chỉ hỏi “cuộn xong có thấy không”, không hỏi “có nằm cuối không”, nên sinh viên sắp xếp khác bài mẫu vẫn không trượt oan. Đổi lại: danh sách gọn trong một màn thì tiêu chí này đạt sẵn với mọi bài.",
    truong: [
      {
        khoa: "target_key",
        nhan: "Định danh phần tử phải thấy được sau khi cuộn",
        batBuoc: true,
        kieu: "dinhdanh",
      },
      {
        khoa: "direction",
        nhan: "Chiều cuộn",
        kieu: "chon",
        chon: [["vertical", "dọc"], ["horizontal", "ngang"]],
      },
    ],
  },
  {
    ma: "component_stack_order",
    ten: "Chồng lớp đúng thứ tự (Stack)",
    dichNhan: "Định danh bọc Stack",
    canhBao: "Lớp trên phải thật sự đè lên lớp dưới, không chỉ khai sau trong children.",
    truong: [
      { khoa: "bottom_key", nhan: "Định danh lớp DƯỚI", batBuoc: true, kieu: "dinhdanh" },
      { khoa: "top_key", nhan: "Định danh lớp TRÊN", batBuoc: true, kieu: "dinhdanh" },
    ],
  },
  {
    ma: "component_indexed_switch",
    ten: "Đổi tab bằng IndexedStack",
    dichNhan: "Định danh bọc IndexedStack",
    canhBao: "Runner bấm lần lượt từng tab rồi kiểm đúng trang hiện ra.",
    truong: [
      {
        khoa: "tabs",
        nhan: "Các cặp tab:trang",
        batBuoc: true,
        goiY: "tab.mot:trang.mot, tab.hai:trang.hai",
      },
    ],
  },
  {
    ma: "component_bottom_sheet",
    ten: "Bảng trượt lên từ đáy",
    dichNhan: "Định danh NÚT MỞ sheet (runner sẽ bấm vào đây)",
    canhBao: "Sheet không được hiện sẵn trước khi bấm, nếu không tiêu chí trượt ngay.",
    truong: [
      { khoa: "sheet_key", nhan: "Định danh sheet", batBuoc: true, kieu: "dinhdanh" },
      { khoa: "close_key", nhan: "Định danh nút đóng sheet", kieu: "dinhdanh" },
    ],
  },
  {
    ma: "component_table",
    ten: "Bảng Table đúng số hàng",
    dichNhan: "Định danh bọc Table",
    canhBao: "Bài phải dùng Table() thật; ListView xếp thành bảng sẽ trượt.",
    truong: [
      { khoa: "row_count", nhan: "Số hàng", batBuoc: true, kieu: "so" },
      { khoa: "header_keys", nhan: "Định danh các ô tiêu đề", goiY: "bang.th.ten, bang.th.tuoi" },
      { khoa: "cell_keys", nhan: "Định danh các ô dữ liệu", goiY: "bang.o.1.1, bang.o.1.2" },
      { khoa: "cells", nhan: "Nội dung từng ô", goiY: "Tên,Tuổi|An,20|Bình,21 (dấu | ngăn hàng)" },
    ],
  },
  {
    ma: "component_sliver_collapse",
    ten: "SliverAppBar thu lại khi cuộn",
    dichNhan: "Định danh bọc CustomScrollView",
    canhBao:
      "SliverAppBar phải bọc bằng SliverSemantics(identifier:), không phải Semantics: sliver nằm ngoài cây widget thường.",
    truong: [
      { khoa: "appbar_key", nhan: "Định danh SliverAppBar", batBuoc: true, kieu: "dinhdanh" },
      {
        khoa: "collapse",
        nhan: "Bắt buộc thu lại khi cuộn",
        kieu: "chon",
        chon: [["", "không xét"], ["true", "có"], ["false", "không"]],
      },
      { khoa: "list_key", nhan: "Định danh SliverList/SliverGrid", kieu: "dinhdanh" },
    ],
  },
  {
    ma: "component_expanded",
    ten: "Widget nằm trong Expanded đúng flex",
    dichNhan: "Định danh widget CON nằm trong Expanded",
    canhBao:
      "Chỉ đo được widget đang hiện trên màn. Nhắm vào một dòng danh sách mà bài của sinh viên sắp xếp khác thì dòng đó có thể bị đẩy khỏi màn và trượt oan.",
    truong: [{ khoa: "flex", nhan: "flex", batBuoc: true, kieu: "so", macDinh: "1" }],
  },
];

/**
 * KHUNG MÁY CHẤM — cố định, không cho sửa. Là KHUNG APP THẬT NHẬN ĐƯỢC, không phải cỡ màn.
 *
 * Màn Pixel 7 là 1080×2400 pixel ở mật độ 2,625, tức 411,43×914,29 dp. Nhưng hệ điều hành
 * không giao cả màn cho app. Hỏi thẳng máy ảo Pixel 7 API 34 ngày 15/9/2026 bằng
 * `adb shell dumpsys window displays`:
 *
 *   mAppBounds=Rect(0, 136 - 1080, 2337)   overrideConfig: w411dp h838dp
 *
 * Thanh trạng thái giữ 136 px (cao vì lỗ camera), thanh cử chỉ giữ 63 px; chia cho 2,625 là
 * 51,8 + 24 = 77 dp. App thật chỉ còn 838,48 dp, làm tròn 838.
 *
 * Vì sao KHÔNG dàn ở 915 rồi che 77 dp cuối: máy thật không cắt bớt 915 — nó DÀN LẠI trong
 * 838. Đo 15/9/2026 trên bài Chi tiêu cá nhân: dàn ở 915 thì FAB nằm 843–899, dàn ở 838 thì
 * nằm 766–822. Che chỉ giấu nút đi, không đưa nó về đúng chỗ.
 *
 * Vì sao KHÔNG cho giảng viên gõ số: gõ một cỡ không máy nào có (ví dụ 412×700) thì bố cục
 * lúc chấm khác hẳn bố cục trên mọi máy thật, mà hậu quả chỉ lộ ra sau khi đã chấm. Đo
 * 9/9/2026 trên bài User Manager: ở 915 cả 8 dòng nằm gọn trong màn, ở 700 thì ba dòng cuối
 * chưa dựng — cùng một bài, hai kết quả.
 *
 * Làm tròn 838,48 → 838 không đổi điểm nào: expect.center_* và số đo bài sinh viên đều do
 * engine sinh ra với CÙNG một viewport, nên làm tròn dịch cả hai vế như nhau. Chênh 0,48 dp
 * so với hạn mức 5% của 838 (41,9 dp) là 1,1%.
 *
 * PHẢI KHỚP mặc định trong exam_test.dart._applyViewport và BehaviorSuiteMaterializer.
 */
const KHUNG_RONG = 412;
const KHUNG_CAO = 838;

/**
 * Các mức thu nhỏ khung xem thử. Chỉ đổi cỡ HIỂN THỊ bằng CSS transform; iframe vẫn dàn bố
 * cục ở đúng 412×838 nên thứ giảng viên nhìn là thứ máy chấm nhìn.
 */
const MUC_THU_NHO: [number, string][] = [
  [1, "1:1"],
  [0.8, "4:5"],
  [0.75, "3:4"],
  [0.6, "3:5"],
];

/**
 * KHUNG DESKTOP — chỉ dùng cho kĩ năng responsive.
 *
 * PHẢI khớp BehaviorSuiteMaterializer.KHUNG_DESKTOP. Lệch một số là giảng viên soạn ở khung
 * này còn máy chấm đo ở khung khác, mà hậu quả chỉ lộ ra sau khi đã chấm.
 */
const KHUNG_DESKTOP_RONG = 1280;
const KHUNG_DESKTOP_CAO = 800;

/** Một thành phần đo được trên màn; toạ độ theo pixel client của chính lượt đo đó. */
interface KhungThanhPhan {
  key: string; identifier: string; label: string; role: string;
  x: number; y: number; w: number; h: number; dup: number;
}
interface AnhChupKhung { screen: { w: number; h: number }; items: KhungThanhPhan[] }

/** Một cặp thành phần ĐỔI quan hệ khi chuyển từ khung điện thoại sang khung desktop. */
interface CapDoiBoCuc {
  a: KhungThanhPhan; b: KhungThanhPhan;
  dienThoai: string; desktop: string; checked: boolean;
}

/**
 * Một BẰNG CHỨNG co giãn THẬT — loại mà reflow miễn phí của Flutter không tạo ra được.
 *
 * Vì sao phải tách: Wrap tự xếp lại, chữ hết xuống dòng, Expanded nở ra — app KHÔNG viết một
 * dòng responsive nào vẫn đổi quan hệ bố cục khi màn rộng ra. Đo thật trên bài Chi tiêu cá
 * nhân: sáu chip tổng bề ngang ~526 dp, ở khung 412 xếp hai hàng, ở 1280 gọn một hàng — đổi
 * quan hệ mà chẳng ai viết breakpoint nào. Chấm cái đó là cho điểm một kĩ năng không tồn tại.
 *
 * Ba thứ dưới đây thì reflow miễn phí không làm được:
 *  - hien_them / bien_mat: đổi TẬP thành phần, phải có `if (width > ...)` mới xảy ra.
 *  - vi_tri_kim: nội dung KHÔNG nở theo màn, tức có ràng buộc bề ngang tối đa.
 *  - doi_cot: một nhóm lặp đổi số cột, tức list thành grid.
 */
interface BangChungCoGian {
  loai: "hien_them" | "bien_mat" | "vi_tri_kim";
  it: KhungThanhPhan;
  moTa: string;
  checked: boolean;
}
interface KetQuaDoCoGian {
  bangChung: BangChungCoGian[];
  /** Tỉ lệ bề ngang nội dung chiếm trên màn, ở hai khung. */
  beNgang: { dienThoai: number; desktop: number };
  /** Nhóm lặp đổi số cột: list thành grid. */
  doiCot: { key: string; nhan: string; dienThoai: number; desktop: number }[];
  capReflow: CapDoiBoCuc[];
}

/** Tên tiếng Việt của quan hệ, để giảng viên đọc được bảng gợi ý. */
const TEN_QUAN_HE: Record<string, string> = {
  above: "nằm trên", below: "nằm dưới", left_of: "bên trái", right_of: "bên phải",
  same_row: "cùng hàng", same_column: "cùng cột", inside: "nằm trong",
  contains: "bao ngoài", overlap: "chồng lên",
};

/**
 * Suy quan hệ bố cục giữa hai thành phần — CHÉP NGUYÊN luật của engine
 * (exam_test.dart `_deriveLayoutRelation`), kể cả cái đuôi trả về above/below chứ không phải
 * not_overlap.
 *
 * Vì sao phải chép đúng từng nhánh: bảng gợi ý mà dùng luật khác engine thì nó sẽ chỉ ra một
 * chỗ máy chấm không hề thấy đổi — giảng viên tick vào, và bài bố cục cứng vẫn ăn trọn điểm
 * responsive.
 *
 * Toạ độ vào theo pixel client, không phải dp. Quan hệ bất biến với tỉ lệ nên không sao, miễn
 * là hai thành phần đến từ CÙNG một lượt đo.
 */
const quanHeBoCuc = (a: KhungThanhPhan, b: KhungThanhPhan, man: { w: number; h: number }): string => {
  const tolX = man.w * 0.05;
  const tolY = man.h * 0.05;
  const no = Math.max(tolX, tolY);
  const namTrong = (ngoai: KhungThanhPhan, trong: KhungThanhPhan) =>
    trong.x >= ngoai.x - no && trong.y >= ngoai.y - no
    && trong.x + trong.w <= ngoai.x + ngoai.w + no
    && trong.y + trong.h <= ngoai.y + ngoai.h + no;
  if (namTrong(b, a)) return "inside";
  if (namTrong(a, b)) return "contains";
  if (a.y + a.h <= b.y && a.x + a.w >= b.x && a.x <= b.x + b.w) return "above";
  if (a.y >= b.y + b.h && a.x + a.w >= b.x && a.x <= b.x + b.w) return "below";
  if (a.x + a.w <= b.x && a.y + a.h >= b.y && a.y <= b.y + b.h) return "left_of";
  if (a.x >= b.x + b.w && a.y + a.h >= b.y && a.y <= b.y + b.h) return "right_of";
  const tamA = { x: a.x + a.w / 2, y: a.y + a.h / 2 };
  const tamB = { x: b.x + b.w / 2, y: b.y + b.h / 2 };
  if (Math.abs(tamA.y - tamB.y) <= tolY) return "same_row";
  if (Math.abs(tamA.x - tamB.x) <= tolX) return "same_column";
  const chongNhau = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
  return chongNhau ? "overlap" : (tamA.y < tamB.y ? "above" : "below");
};

/** Đích cho checkpoint: ưu tiên định danh, không có thì dùng nhãn. */
const dichCuaThanhPhan = (it: KhungThanhPhan): JsonMap =>
  it.identifier ? { semanticId: it.identifier } : { label: it.label };

/** Số thành phần nhiều nhất cùng nằm một hàng trong một nhóm lặp. */
const soCotToiDa = (rects: KhungThanhPhan[], tolY: number): number => {
  let max = 1;
  for (const r of rects) {
    const tam = r.y + r.h / 2;
    const cung = rects.filter((o) => Math.abs((o.y + o.h / 2) - tam) <= tolY).length;
    if (cung > max) max = cung;
  }
  return max;
};

/** So hai lượt đo, TÁCH bằng chứng co giãn thật khỏi reflow tự nhiên. */
const phanTichCoGian = (dienThoai: AnhChupKhung, desktop: AnhChupKhung): KetQuaDoCoGian => {
  const donP = dienThoai.items.filter((it) => it.dup === 1);
  const donD = desktop.items.filter((it) => it.dup === 1);
  const mapP = new Map(donP.map((it) => [it.key, it]));
  const mapD = new Map(donD.map((it) => [it.key, it]));
  const bangChung: BangChungCoGian[] = [];

  donD.filter((it) => !mapP.has(it.key)).forEach((it) => bangChung.push({
    loai: "hien_them", it, checked: false,
    moTa: `"${it.label || it.identifier}" chỉ hiện ở khung desktop`,
  }));

  // BIẾN MẤT chỉ tính khi CÒN thành phần nằm dưới nó vẫn hiện ở desktop. Nếu mọi thứ từ nó trở
  // xuống đều mất thì đó là hết màn (khung desktop thấp hơn: 800 so với 838), không phải app cố
  // ý giấu — tính vào là đếm nhầm cái fold thành mã responsive.
  donP.filter((it) => !mapD.has(it.key)).forEach((it) => {
    if (!donP.some((o) => o.y > it.y && mapD.has(o.key))) return;
    bangChung.push({
      loai: "bien_mat", it, checked: false,
      moTa: `"${it.label || it.identifier}" biến mất ở khung desktop`,
    });
  });

  const beNgangCua = (anh: AnhChupKhung, don: KhungThanhPhan[]) => {
    if (don.length === 0 || anh.screen.w <= 0) return 0;
    const trai = Math.min(...don.map((it) => it.x));
    const phai = Math.max(...don.map((it) => it.x + it.w));
    return (phai - trai) / anh.screen.w;
  };
  const beNgang = { dienThoai: beNgangCua(dienThoai, donP), desktop: beNgangCua(desktop, donD) };
  if (beNgang.dienThoai - beNgang.desktop > 0.15) {
    [...donD]
      .filter((it) => mapP.has(it.key))
      .map((it) => ({ it, lech: Math.abs(it.x / desktop.screen.w - mapP.get(it.key)!.x / dienThoai.screen.w) }))
      .sort((a, b) => b.lech - a.lech)
      .slice(0, 6)
      .forEach(({ it }) => bangChung.push({
        loai: "vi_tri_kim", it, checked: false,
        moTa: `"${it.label || it.identifier}" không bám mép như ở điện thoại — nội dung bị kìm bề ngang`,
      }));
  }

  const nhomLap = (items: KhungThanhPhan[]) => {
    const m = new Map<string, KhungThanhPhan[]>();
    items.filter((it) => it.dup > 1).forEach((it) => {
      const ds = m.get(it.key) || [];
      ds.push(it);
      m.set(it.key, ds);
    });
    return m;
  };
  const nhomP = nhomLap(dienThoai.items);
  const nhomD = nhomLap(desktop.items);
  const doiCot: KetQuaDoCoGian["doiCot"] = [];
  nhomP.forEach((dsP, key) => {
    const dsD = nhomD.get(key);
    if (!dsD) return;
    const cotP = soCotToiDa(dsP, dienThoai.screen.h * 0.02);
    const cotD = soCotToiDa(dsD, desktop.screen.h * 0.02);
    if (cotD > cotP) doiCot.push({ key, nhan: dsP[0].label || dsP[0].identifier || key, dienThoai: cotP, desktop: cotD });
  });

  const chung = donP.filter((it) => mapD.has(it.key));
  const capReflow: CapDoiBoCuc[] = [];
  for (let i = 0; i < chung.length; i++) {
    for (let j = i + 1; j < chung.length; j++) {
      const a = chung[i];
      const b = chung[j];
      const qhP = quanHeBoCuc(a, b, dienThoai.screen);
      const qhD = quanHeBoCuc(mapD.get(a.key)!, mapD.get(b.key)!, desktop.screen);
      if (qhP !== qhD) capReflow.push({ a, b, dienThoai: qhP, desktop: qhD, checked: false });
    }
  }
  const dangQuy = (c: CapDoiBoCuc) =>
    (c.dienThoai === "below" || c.dienThoai === "above")
      && (c.desktop === "same_row" || c.desktop === "right_of" || c.desktop === "left_of") ? 0 : 1;
  capReflow.sort((x, y) => dangQuy(x) - dangQuy(y));

  return { bangChung, beNgang, doiCot, capReflow: capReflow.slice(0, 40) };
};

/** Lam tron ve ba chu so thap phan — don vi nho nhat cua moi phep chia diem. */
const lamTron = (v: number) => Math.round(v * 1000) / 1000;

// Loại widget hay gặp nhất trong đề Flutter — gợi ý thôi, gõ tên khác vẫn chạy vì
// engine so bằng TÊN kiểu chứ không tra bảng cứng.
const WIDGET_GOI_Y = [
  "Switch", "SwitchListTile", "Checkbox", "CheckboxListTile",
  "Radio<String>", "RadioListTile<String>", "Slider",
  "FilterChip", "ChoiceChip", "TextField", "Icon", "Image",
  "BottomNavigationBar", "NavigationBar", "SegmentedButton<int>",
  "FilledButton", "ElevatedButton", "TextButton", "OutlinedButton",
  "IconButton", "FloatingActionButton", "Text",
];

// Thuoc tinh tra ve MAU thi so bang phep so mau (sai so theo kenh R/G/B). Danh sach
// nay phai khop `_laThuocTinhMau` trong exam_test.dart.
const laThuocTinhMau = (ma: string) =>
  ma === "color" || ma === "app_bar_background" || ma.startsWith("color_scheme.");

// Bốn mặt của kiểu chữ. Đọc từ đoạn văn bản THẬT SỰ ĐƯỢC VẼ nên bắt được cả chữ
// thừa kế từ theme (không đặt style trên widget) lẫn chữ đặt style thẳng.
const MAT_KIEU_CHU: Array<[string, string]> = [
  ["font_size", "Cỡ chữ"],
  ["font_weight", "Độ đậm (400, 700…)"],
  ["font_family", "Tên phông"],
  ["color", "Màu chữ"],
];

// Giá trị đọc được từ bảng chủ đề của app.
const GIA_TRI_CHU_DE: Array<[string, string]> = [
  ["color_scheme.primary", "Màu chính (primary)"],
  ["color_scheme.onPrimary", "Màu chữ trên nền chính (onPrimary)"],
  ["color_scheme.primaryContainer", "Nền phụ của màu chính (primaryContainer)"],
  ["color_scheme.secondary", "Màu phụ (secondary)"],
  ["color_scheme.tertiary", "Màu thứ ba (tertiary)"],
  ["color_scheme.error", "Màu báo lỗi (error)"],
  ["color_scheme.surface", "Màu nền mặt (surface)"],
  ["color_scheme.onSurface", "Màu chữ trên nền mặt (onSurface)"],
  ["color_scheme.outline", "Màu viền (outline)"],
  ["use_material3", "Có bật Material 3 không"],
  ["brightness", "Chế độ sáng hay tối"],
  ["font_family", "Phông chữ toàn app"],
  ["app_bar_background", "Màu nền thanh tiêu đề"],
  ["app_bar_center_title", "Thanh tiêu đề có căn giữa không"],
  ["text_theme.titleLarge.font_size", "Cấp titleLarge — cỡ chữ"],
  ["text_theme.titleLarge.font_weight", "Cấp titleLarge — độ đậm"],
  ["text_theme.bodyMedium.font_size", "Cấp bodyMedium — cỡ chữ"],
  ["text_theme.bodyMedium.font_weight", "Cấp bodyMedium — độ đậm"],
  ["text_theme.labelSmall.font_size", "Cấp labelSmall — cỡ chữ"],
  ["text_theme.labelSmall.font_weight", "Cấp labelSmall — độ đậm"],
];

// Thuộc tính engine biết đọc. Danh sách này phải khớp bảng trắng trong
// _docThuocTinhWidget của exam_test.dart — lệch là tiêu chí nổ lúc capture.
const THUOC_TINH_WIDGET: Array<[string, string]> = [
  ["ton_tai", "Chỉ cần đúng loại widget này (dùng cho \"phải dùng nút X\")"],
  ["value", "Giá trị — công tắc, ô tick, dải trượt, nội dung chữ"],
  ["da_chon", "Đang được chọn — chip, radio"],
  ["group_value", "Lựa chọn đang active của nhóm radio"],
  ["min", "Dải trượt — giá trị nhỏ nhất"],
  ["max", "Dải trượt — giá trị lớn nhất"],
  ["divisions", "Dải trượt — số nấc chia"],
  ["keyboard_type", "Ô nhập — loại bàn phím (number, text, multiline…)"],
  ["obscure_text", "Ô nhập — có che chữ như mật khẩu không"],
  ["max_lines", "Ô nhập — số dòng tối đa"],
  ["icon_code", "Icon — đúng biểu tượng nào"],
  ["image_source", "Ảnh — đường dẫn asset hoặc URL"],
  ["current_index", "Thanh điều hướng — đang ở tab thứ mấy"],
  ["enabled", "Đang bật hay bị khóa — nút, ô nhập, công tắc"],
  ["noi_dung", "Ô nhập — chữ đang có sẵn trong ô"],
];
async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: init?.body instanceof FormData ? init.headers : { "Content-Type": "application/json", ...init?.headers },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const failure = new Error(String(data.error || `HTTP ${response.status}`)) as Error & { missingPackages?: MissingPackageSpec[] };
    if (Array.isArray(data.missing_package_specs)) {
      failure.missingPackages = data.missing_package_specs.flatMap((item: unknown) => {
        if (!item || typeof item !== "object" || Array.isArray(item)) return [];
        const spec = item as Record<string, unknown>;
        return typeof spec.name === "string"
          ? [{ name: spec.name, version: typeof spec.version === "string" ? spec.version : "" }]
          : [];
      });
    }
    // Backend đời cũ chỉ trả tên; giữ fallback để giao diện vẫn dẫn đúng package.
    if (!failure.missingPackages?.length && Array.isArray(data.missing_packages)) {
      failure.missingPackages = data.missing_packages.map((name: unknown) => ({ name: String(name), version: "" }));
    }
    throw failure;
  }
  return data as T;
}

function bytes(value: number) {
  if (!value) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}

interface CheckpointResult {
  test_id: string;
  scenario_code: string;
  name: string;
  status: string;
  message: string;
}

/**
 * Bóc các tiêu chí TRƯỢT của preflight từ khối `GRADE_RESULT` trong log.
 *
 * KHÔNG bóc theo marker `###RAR_CHECKPOINT###`: từ ảnh chấm 4 tầng (2026-08-22),
 * `run_grader.sh` đẩy stdout của behavior runner vào `behavior.stdout.log` NẰM TRONG
 * container và thoát ngay sau `merge_grade_results.dart`; chỉ khối GRADE_RESULT mới ra tới
 * đây. Bóc theo marker thì bảng này luôn rỗng — xem chú thích cùng nội dung ở
 * `GoldenValidationService.parseCheckpointResults`.
 *
 * Khối GRADE_RESULT còn giàu hơn marker: câu `actual` do chính tầng chấm viết sẵn bằng
 * tiếng Việt, và `status` phân biệt `failed` (làm sai) với `not_run` (chưa chạy tới —
 * dấu hiệu lỗi dây chuyền do một bước trước đó đã hỏng).
 */
function parseCheckpointLog(log?: string): CheckpointResult[] {
  if (!log) return [];
  // Lấy khối CUỐI: log bị cắt chỉ giữ 200 KB đuôi và một lần preflight có thể in nhiều khối.
  const start = log.lastIndexOf("--- GRADE_RESULT_START ---");
  if (start < 0) return [];
  const end = log.indexOf("--- GRADE_RESULT_END ---", start);
  if (end < 0) return [];
  try {
    const parsed = JSON.parse(log.slice(start + "--- GRADE_RESULT_START ---".length, end).trim());
    const cases = Array.isArray(parsed?.test_cases) ? parsed.test_cases : [];
    return cases
      // Preflight tắt tầng tĩnh và không có tầng đơn vị, nên chỉ dòng hành vi mới khớp với
      // con số "n/m" hiện ở trên — lọc y hệt GoldenValidationService.parseMergedBehaviorResults.
      .filter((item: Record<string, unknown>) => String(item?.runner ?? "BEHAVIOR_REPLAY") === "BEHAVIOR_REPLAY")
      .filter((item: Record<string, unknown>) => String(item?.status ?? "") !== "passed")
      .map((item: Record<string, unknown>) => ({
        test_id: String(item?.test_id ?? ""),
        scenario_code: String(item?.scenario_code ?? ""),
        name: String(item?.name ?? ""),
        status: String(item?.status ?? ""),
        message: String(item?.actual ?? ""),
      }));
  } catch {
    // Khối JSON hỏng hoặc bị cắt mất đuôi — bỏ qua, để dòng "Preflight: n/m" ở trên tự báo.
    return [];
  }
}

function absoluteRuntimeUrl(value?: string | null) {
  if (!value) return "";
  try {
    if (/^https?:\/\//i.test(value)) return value;
    return new URL(value, new URL(API_BASE).origin).toString();
  } catch {
    return value;
  }
}

function BehaviorAuthoringEditor() {
  const search = useSearchParams();
  const router = useRouter();
  // Toast phải PORTAL ra document.body: layout có ancestor mang transform nên
  // position:fixed bị neo theo ancestor đó thay vì viewport — toast rơi ra ngoài
  // màn hình, người soạn không thấy lỗi và tưởng nút hỏng (ca có thật 29/8).
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const [examId, setExamId] = useState(() => search.get("exam") || "");
  const [name, setName] = useState("");
  const [runtimeUrl, setRuntimeUrl] = useState("");
  const [suite, setSuite] = useState<Suite | null>(null);
  const [availableSuites, setAvailableSuites] = useState<Suite[]>([]);
  const [recording, setRecording] = useState<Recording | null>(null);
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [soatScenario, setSoatScenario] = useState<SoatScenario | null>(null);
  const [validation, setValidation] = useState<GoldenValidation | null>(null);
  // KIỂM ĐỒNG BỘ KHUNG PHÁT. Khung được bóc ra từ Golden sau cùng, nên nó rất dễ tụt lại
  // phía sau; lệch schema hay lệch định danh làm hỏng cả lớp mà không khâu nào khác thấy.
  const [deDaPublish, setDeDaPublish] = useState<JsonMap[]>([]);
  const [deGiao, setDeGiao] = useState("");
  // parseCheckpointLog đã lọc sẵn dòng trượt, nên đây là danh sách CHƯA ĐẠT chứ không phải toàn bộ.
  const checkpointFails = useMemo(() => parseCheckpointLog(validation?.log), [validation?.log]);
  /** Scenario bị "Duyệt lại scenario" gạch tên, kèm lý do để hiện thẳng trên thẻ của nó. */
  /**
   * Mã nhóm đã dùng trong bộ chấm — nguồn cho dropdown của ô Mã nhóm. Luồng đầu tiên gõ tay,
   * từ luồng thứ hai chỉ việc chọn — gõ tay mỗi lần là sớm muộn có FILTER và FILTERS nằm cạnh nhau.
   */
  const maNhomCoSan = useMemo(() => {
    const ra = new Set<string>();
    for (const sc of suite?.scenarios || []) {
      const ma = String((sc as JsonMap).group_code || "").trim();
      if (ma) ra.add(ma);
    }
    return [...ra].sort();
  }, [suite]);
  /**
   * Thẻ luồng xếp theo nhóm: cùng mã nhóm thì đứng liền nhau, luồng chưa có nhóm dồn xuống
   * cuối. `dauNhom` đánh dấu thẻ đầu của mỗi nhóm để chèn tiêu đề chạy hết chiều ngang lưới.
   */
  const scenarioSapXep = useMemo(() => {
    const ds = (suite?.scenarios || []).map((item, index) => ({ item, index }));
    const nhomCua = (x: { item: JsonMap }) => String(x.item.group_code || "").trim();
    const thuTuNhom: string[] = [];
    for (const x of ds) {
      const n = nhomCua(x);
      if (n && !thuTuNhom.includes(n)) thuTuNhom.push(n);
    }
    const sapXep = [
      ...thuTuNhom.flatMap((n) => ds.filter((x) => nhomCua(x) === n)),
      ...ds.filter((x) => !nhomCua(x)),
    ];
    let truoc = "";
    let dauTien = true;
    return sapXep.map((x) => {
      const nhanNhom = nhomCua(x) || "Chưa xếp nhóm";
      const dauNhom = dauTien || nhanNhom !== truoc;
      truoc = nhanNhom;
      dauTien = false;
      return { ...x, dauNhom, nhanNhom };
    });
  }, [suite]);

  const scenarioHong = useMemo(() => {
    const ra = new Map<string, string[]>();
    for (const row of soatScenario?.scenarios || []) if (!row.ok) ra.set(String(row.id), row.reasons || []);
    return ra;
  }, [soatScenario]);
  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus | null>(null);
  const [recorderReady, setRecorderReady] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [missingGoldenPackages, setMissingGoldenPackages] = useState<MissingPackageSpec[]>([]);
  const [action, setAction] = useState("tap");
  const [locator, setLocator] = useState("semanticId");
  const [locatorValue, setLocatorValue] = useState("");
  const [inputValue, setInputValue] = useState("");
  // Do doi cho `drag`, tinh bang dp. Mac dinh keo ngang 80 — du de doi mot Slider
  // 10 nac di hai nac (do o sa ban), va khong am tham keo doc.
  const [keoX, setKeoX] = useState(80);
  const [keoY, setKeoY] = useState(0);
  const [checkpointText, setCheckpointText] = useState("");
  const [thuGon, setThuGon] = useState(true);
  const [bangDiemY, setBangDiemY] = useState<number | null>(null);
  const bangDiemRef = useRef<HTMLDivElement>(null);
  const keoBangDiem = useRef<{ startY: number; startTop: number; moved: boolean } | null>(null);
  const chanBamBangDiem = useRef(false);
  const keoDanhSach = useRef<{ startY: number; startScroll: number; moved: boolean } | null>(null);
  const gioiHanBangDiemY = useCallback((y: number) => Math.max(8, Math.min(y,
    window.innerHeight - (thuGon ? 56 : bangDiemRef.current?.getBoundingClientRect().height || 48) - 8)), [thuGon]);

  useEffect(() => {
    if (!suite?.id) return;
    const canViTri = () => setBangDiemY((y) => gioiHanBangDiemY(y ?? window.innerHeight / 3));
    canViTri();
    // Thêm tiêu chí có thể làm bảng cao hơn dù cửa sổ không đổi kích thước.
    const theoDoiKhung = new ResizeObserver(canViTri);
    if (bangDiemRef.current) theoDoiKhung.observe(bangDiemRef.current);
    window.addEventListener("resize", canViTri);
    return () => { theoDoiKhung.disconnect(); window.removeEventListener("resize", canViTri); };
  }, [suite?.id, thuGon, gioiHanBangDiemY]);

  const batDauKeoBangDiem = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0) return;
    chanBamBangDiem.current = false;
    keoBangDiem.current = { startY: event.clientY, startTop: bangDiemRef.current?.getBoundingClientRect().top || 8, moved: false };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const diChuyenBangDiem = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const keo = keoBangDiem.current;
    if (!keo || (Math.abs(event.clientY - keo.startY) < 4 && !keo.moved)) return;
    keo.moved = true;
    // Kéo và bấm dùng chung tay nắm; kéo xong không được vô tình mở/đóng bảng.
    chanBamBangDiem.current = true;
    setBangDiemY(gioiHanBangDiemY(keo.startTop + event.clientY - keo.startY));
  };
  const batDauKeoDanhSach = (event: ReactPointerEvent<HTMLDivElement>) => {
    keoDanhSach.current = event.pointerType === "mouse" && event.button === 0 && event.currentTarget.scrollHeight > event.currentTarget.clientHeight
      ? { startY: event.clientY, startScroll: event.currentTarget.scrollTop, moved: false } : null;
  };
  const cuonKeoDanhSach = (event: ReactPointerEvent<HTMLDivElement>) => {
    const keo = keoDanhSach.current;
    if (!keo || (Math.abs(event.clientY - keo.startY) < 5 && !keo.moved)) return;
    keo.moved = true;
    event.currentTarget.setPointerCapture(event.pointerId);
    event.preventDefault();
    event.currentTarget.scrollTop = keo.startScroll + keo.startY - event.clientY;
  };
  const [uiGroupWeight, setUiGroupWeight] = useState<DiemNhap>(0);
  const [viTriWeight, setViTriWeight] = useState<DiemNhap>(0);
  const [mauWeight, setMauWeight] = useState<DiemNhap>(0);
  const daKhoiTaoDiemUi = useRef(false);
  const [chiaDiemId, setChiaDiemId] = useState("");
  const [chiaDiemHam, setChiaDiemHam] = useState<DiemNhap>(0);
  const [chiaDiemChot, setChiaDiemChot] = useState<Record<string, DiemNhap>>({});
  const [chiaDiemCha, setChiaDiemCha] = useState<Record<string, string>>({});
  const [hiddenCheckpointText, setHiddenCheckpointText] = useState("");
  const [checkpointMode, setCheckpointMode] = useState<"ui" | "database" | "route" | "layout">("ui");
  const [uiCheckpointType, setUiCheckpointType] = useState<"text" | "component" | "widget_state" | "text_style" | "theme_value" | "preferences" | "no_overflow" | "no_exception">("text");
  const [checkpointLocator, setCheckpointLocator] = useState("semanticId");
  const [checkpointLocatorValue, setCheckpointLocatorValue] = useState("");
  const [checkpointVisible, setCheckpointVisible] = useState(true);
  const [checkpointRole, setCheckpointRole] = useState("generic");
  const [checkpointValue, setCheckpointValue] = useState("");
  const [checkpointEnabled, setCheckpointEnabled] = useState("ignore");
  const [checkpointChecked, setCheckpointChecked] = useState("ignore");
  const [wsLocator, setWsLocator] = useState("label");
  const [wsLocatorValue, setWsLocatorValue] = useState("");
  const [wsWidget, setWsWidget] = useState("");
  const [wsProperty, setWsProperty] = useState("value");
  const [tsLocator, setTsLocator] = useState("text");
  const [tsLocatorValue, setTsLocatorValue] = useState("");
  const [tsProperty, setTsProperty] = useState("font_size");
  const [tvProperty, setTvProperty] = useState("color_scheme.primary");
  // Sai so CHI danh cho thuoc tinh mau. Co chu, do dam, bat/tat deu la con so nguoi
  // ra de quy dinh chu khong phai phep do co nhieu, nen so tuyet doi — them % vao do
  // chi tao cho de lot bai sai.
  const [saiSoMau, setSaiSoMau] = useState(20);
  // Tiêu chí GIÁ TRỊ ĐÃ LƯU: đọc kho SharedPreferences sau khi luồng chạy xong. Phân biệt
  // bài lưu thật với bài chỉ đổi giao diện bằng setState.
  const [prefsKey, setPrefsKey] = useState("");
  const [prefsAbsent, setPrefsAbsent] = useState(false);
  // Bộ nhớ ĐÃ CÓ SẴN lúc mở app, khai theo dòng "khoá=giá trị". Dùng cho luồng kiểu
  // "mở app khi người dùng đã bật chế độ tối từ lần trước".
  const [prefsBanDau, setPrefsBanDau] = useState("");
  const [databaseTable, setDatabaseTable] = useState("");
  const [databaseOperation, setDatabaseOperation] = useState("READ");
  const [databaseRow, setDatabaseRow] = useState("{}");
  const [databaseCount, setDatabaseCount] = useState("");
  const [currentRoute, setCurrentRoute] = useState("/");
  // Khung "Thêm action" khai tay: đóng sẵn vì hầu hết thao tác ghi thẳng từ Golden App,
  // chỉ mở khi cần chèn một bước mà bấm trên app không ghi ra được.
  const [moThemAction, setMoThemAction] = useState(false);
  const [routeExpected, setRouteExpected] = useState("/");
  const [routeCanPop, setRouteCanPop] = useState("ignore");
  const [layoutFirstLocator, setLayoutFirstLocator] = useState("semanticId");
  const [layoutFirstValue, setLayoutFirstValue] = useState("");
  const [layoutSecondLocator, setLayoutSecondLocator] = useState("semanticId");
  const [layoutSecondValue, setLayoutSecondValue] = useState("");
  const [layoutRelation, setLayoutRelation] = useState("auto");
  const [layoutTolerance, setLayoutTolerance] = useState(5);
  // Tab "Bố cục" phục vụ hai thứ: quan hệ A/B (layout_relation) và 8 runner Ch.7.
  // "quan_he" giữ nguyên hành vi cũ, các mã còn lại tra thẳng trong CH7_LOAI.
  const [ch7Kind, setCh7Kind] = useState("quan_he");
  const [ch7Target, setCh7Target] = useState("");
  const [ch7Expect, setCh7Expect] = useState<Record<string, string>>({});
  // Định danh đang có trên màn Golden, KỂ CẢ của widget không chữ. Bảng Quét UI không
  // liệt kê được chúng (không nhãn thì không thành một dòng tick), mà đúng chúng mới là
  // đích của tiêu chí bố cục.
  const [dinhDanhTrenMan, setDinhDanhTrenMan] = useState<string[]>([]);
  // KHÔNG điền sẵn: mã/tên luồng là danh tính của tiêu chí trong bảng điểm,
  // để mặc định thì mọi bộ chấm đều đầy "MAIN_FLOW/Luồng chính" vô nghĩa.
  // Mã NHÓM (FILTER, UI, CRUD…) — để trống là luồng không thuộc nhóm nào. Không còn ô "Mã
  // luồng": mã đó nay do máy dựng từ mã nhóm + tên luồng, xem BehaviorAuthoringService.
  const [groupCode, setGroupCode] = useState("");
  const [scenarioName, setScenarioName] = useState("");
  const [scenarioWeight, setScenarioWeight] = useState<DiemNhap>(10);
  // Bảng tick thành phần giao diện — đổ về từ lệnh quét màn hình của bridge.
  // null = chưa quét; mảng = đang mở bảng tick.
  const [uiInventory, setUiInventory] = useState<{ attribute: string; value: string; role: string; identifier: string; count: number; checked: boolean }[] | null>(null);
  const [uiScreenName, setUiScreenName] = useState("");
  // Kiểm kê ICON của màn cuối luồng, do MÁY CHẤM đo lúc capture. Không quét được qua
  // DOM như bảng trên: nút chỉ có hình thì web không phơi aria-label nào, nên đúng những
  // nút cần chấm lại là những nút "Quét thành phần UI" không thấy.
  const [iconInventory, setIconInventory] = useState<
    { loai: "icon" | "image"; ten: string; count: number; perRow: boolean; buttonType: string; checked: boolean }[] | null
  >(null);
  const [iconScenario, setIconScenario] = useState<{ id: string; ma: string; checkpoints: JsonMap[] } | null>(null);
  // Ba mặt RIÊNG cho bảng icon/ảnh, không dùng chung với bảng quét UI. Màu mặc định TẮT:
  // chấm "màu chủ đạo" của một tấm ảnh cho sẵn là vô nghĩa — ảnh nào cũng là ảnh đó, và
  // cùng một ảnh cắt ở hai cỡ khác nhau cho hai màu khác nhau.
  const [iconCoMatOn, setIconCoMatOn] = useState(true);
  const [iconViTriOn, setIconViTriOn] = useState(true);
  const [iconMauOn, setIconMauOn] = useState(false);
  // Chấm VỊ TRÍ và MÀU của từng thành phần đã tick. Sai số mặc định 5%: vị trí tính theo
  // % chiều rộng/cao màn hình, màu tính theo % của 255 trên từng kênh R/G/B.
  const [viTriOn, setViTriOn] = useState(true);
  const [viTriSaiSo, setViTriSaiSo] = useState(5);
  const [mauOn, setMauOn] = useState(true);
  const [mauSaiSo, setMauSaiSo] = useState(5);
  // Màu chủ đạo của app — MỘT dòng cho cả màn, đọc thẳng ColorScheme. Sai số rộng hơn
  // hẳn màu thành phần vì phép đo này chính xác tuyệt đối, không có nhiễu để chống.
  // Khung app thật trên máy ảo Pixel 7: 412×838 dp (màn 412×915 trừ 77 dp thanh hệ thống).
  // Sinh viên làm bài trên máy ảo Android nên đây là khung DUY NHẤT còn ý nghĩa.
  //
  // KHÔNG có ô mật độ điểm ảnh: mọi phép chấm bố cục đo bằng dp (tâm thành phần, sai số
  // theo % chiều rộng/cao), nên mật độ không đổi một điểm nào — nó chỉ quyết định ảnh
  // bằng chứng nét tới đâu và nặng bao nhiêu. Để cố định 1 cho ảnh gọn.
  const viewportWidth = KHUNG_RONG;
  // Chấm ở chế độ tối: cờ nằm TRONG object viewport nên đi qua mọi tầng như rộng/cao màn.
  const [cheDoToi, setCheDoToi] = useState(false);
  const viewportHeight = KHUNG_CAO;
  // Chỉ là cỡ NHÌN của khung xem thử, không đi vào bộ chấm.
  const [thuNho, setThuNho] = useState(0.75);
  // KĨ NĂNG RESPONSIVE. khungDesktop chỉ đổi cỡ khung XEM THỬ; tiêu chí thường vẫn soạn ở
  // khung điện thoại, còn tiêu chí sinh từ bảng dò luôn mang cờ khung desktop.
  const [khungDesktop, setKhungDesktop] = useState(false);
  const [respScenario, setRespScenario] = useState<{ id: string; ma: string; checkpoints: JsonMap[] } | null>(null);
  const [respKq, setRespKq] = useState<KetQuaDoCoGian | null>(null);
  const [respDiem, setRespDiem] = useState<DiemNhap>(8);
  const khungRongXem = khungDesktop ? KHUNG_DESKTOP_RONG : KHUNG_RONG;
  const khungCaoXem = khungDesktop ? KHUNG_DESKTOP_CAO : KHUNG_CAO;
  // Khung desktop rộng 1280 nên Ratio của khung điện thoại không dùng được — thu cố định cho
  // vừa cột trái, còn app bên trong vẫn dàn ở đúng 1280×800 như máy chấm.
  const tiLeXem = khungDesktop ? 0.45 : thuNho;
  const [codePreview, setCodePreview] = useState<CodePreview | null>(null);
  const [previewFileName, setPreviewFileName] = useState("");
  const [editingScenarioId, setEditingScenarioId] = useState<string | null>(null);
  // Luật chấm tĩnh (Kiến trúc/lint): preset do backend cung cấp kèm đối chứng Golden.
  const [staticRules, setStaticRules] = useState<StaticRulesView | null>(null);
  const [staticSel, setStaticSel] = useState<Record<string, { checked: boolean; weight: DiemNhap }>>({});
  const goldenFrame = useRef<HTMLIFrameElement | null>(null);
  const authoringPanel = useRef<HTMLDivElement | null>(null);
  // Iframe có thể còn phát event chốt input ngay trước Stop. Guard imperative giữ
  // event đó trong đúng phiên khi React vẫn đang chờ render/refresh kế tiếp.
  const activeRecordingId = useRef<string | null>(null);
  const acceptsRecorderEvents = useRef(false);
  // Event từ iframe phải được ghi tuần tự. Nếu /stop chạy trước request enter_text
  // cuối cùng, backend đổi phiên khỏi ACTIVE và action hợp lệ bị mất do race.
  const recorderEventQueue = useRef<Promise<void>>(Promise.resolve());
  const recorderQueueError = useRef<Error | null>(null);
  const flushWaiters = useRef(new Map<string, {
    resolve: () => void;
    reject: (error: Error) => void;
    timer: number;
  }>());
  // Chỗ hẹn cho một lượt đo khung đang chờ trả lời. Một lượt tại một thời điểm là đủ: phép dò
  // co giãn chụp tuần tự hai khung chứ không song song.
  const respChoDo = useRef<((v: AnhChupKhung) => void) | null>(null);
  /** Chỗ hẹn cho lượt chờ Golden khởi động lại xong (GOLDEN_RECORDER_READY). */
  const choGoldenSanSang = useRef<(() => void) | null>(null);
  const requestedSuite = search.get("suite");
  const previewUrl = useMemo(() => absoluteRuntimeUrl(runtimeUrl), [runtimeUrl]);
  const runtimeOrigin = useMemo(() => {
    try { return previewUrl ? new URL(previewUrl).origin : ""; }
    catch { return ""; }
  }, [previewUrl]);
  const previewFile = useMemo(
    () => codePreview?.files.find((file) => file.name === previewFileName) || codePreview?.files[0] || null,
    [codePreview, previewFileName],
  );
  const databaseName = String(
    suite?.database_contract?.path
      || suite?.database_contract?.database_name
      || suite?.database_contract?.name
      || "",
  ).trim().replace(/\\/g, "/").split("/").pop() || "";
  const manualActionNeedsTarget = ["tap", "enter_text", "clear_text", "scroll", "wait_until"].includes(action);
  const manualActionNeedsUri = ["boot_with_uri", "open_uri", "wait_for_route"].includes(action);
  useEffect(() => setRecorderReady(false), [previewUrl]);

  // Khung web đã mở và đúng recorder của nó phải sẵn sàng trước cả chạy thử lẫn publish.
  const goldenReady = runtimeStatus?.status === "READY" && runtimeStatus.available === true
    && Boolean(previewUrl) && recorderReady;
  const preflightPassed = validation?.current === true && validation.status === "PASSED"
    && (validation.total_checkpoints || 0) > 0
    && validation.passed_checkpoints === validation.total_checkpoints;

  const activeByType = useMemo(() => {
    const result: Partial<Record<ArtifactType, Artifact>> = {};
    artifacts.filter((item) => item.active).forEach((item) => { result[item.type] = item; });
    return result;
  }, [artifacts]);
  // Database phát sinh viên KHÔNG còn là điều kiện: engine chỉ nạp database ẩn, không bao
  // giờ mở student.db, và việc canh cấu trúc đã chuyển sang khâu kiểm đồng bộ khung phát.
  const recordingInputsReady = Boolean(
    activeByType.HIDDEN_DATABASE && activeByType.GOLDEN_SOLUTION,
  );
  const refresh = useCallback(async (suiteId: string) => {
    const [suiteData, artifactData, readyData, validationData, runtimeData] = await Promise.all([
      api<Suite>(`/behavior-authoring/suites/${suiteId}`),
      api<Artifact[]>(`/behavior-authoring/suites/${suiteId}/artifacts`),
      api<Readiness>(`/behavior-authoring/suites/${suiteId}/artifacts/readiness`),
      api<GoldenValidation>(`/behavior-authoring/suites/${suiteId}/validate-golden`),
      api<RuntimeStatus>(`/behavior-authoring/suites/${suiteId}/runtime`),
    ]);
    setSuite(suiteData);
    setArtifacts(artifactData);
    setReadiness(readyData);
    setValidation(validationData);
    setRuntimeStatus(runtimeData);
    // KHÔI PHỤC PHIÊN SOẠN ĐANG DỞ sau khi F5.
    //
    // Mã luồng, tên luồng và "đang sửa scenario nào" trước đây chỉ nằm trong state React, nên
    // tải lại trang là mất sạch — trong khi phiên record vẫn sống nguyên ở máy chủ. Giảng viên
    // thấy ô trống, gõ lại mã khác, và sinh ra một scenario thứ hai thay vì sửa cái cũ.
    //
    // Chỉ khôi phục khi trình duyệt CHƯA giữ phiên nào (activeRecordingId rỗng), để lần refresh
    // giữa một luồng đang chạy không đè lên trạng thái vừa đặt.
    if (!activeRecordingId.current) {
      const dangSoan = (suiteData.recordings || []).find(
        (r) => r.status === "ACTIVE" || r.status === "STOPPED");
      if (dangSoan) {
        activeRecordingId.current = dangSoan.id;
        acceptsRecorderEvents.current = dangSoan.status === "ACTIVE";
        setRecording(dangSoan);
        const suaId = dangSoan.revision_scenario_id || "";
        setEditingScenarioId(suaId || null);
        const scenarioCu = (suiteData.scenarios || []).find((s) => String(s.id || "") === suaId);
        if (scenarioCu) {
          setGroupCode(String(scenarioCu.group_code || ""));
          setScenarioName(String(scenarioCu.name || scenarioCu.scenario_code || ""));
          setScenarioWeight(Number(scenarioCu.weight || 1));
        } else if (dangSoan.name) {
          setScenarioName(dangSoan.name);
        }
      }
    }
    // Luật tĩnh tải riêng và chịu lỗi độc lập: panel ẩn đi chứ không kéo sập cả trang.
    try {
      const rulesView = await api<StaticRulesView>(`/behavior-authoring/suites/${suiteId}/static-rules`);
      setStaticRules(rulesView);
      const sel: Record<string, { checked: boolean; weight: number }> = {};
      rulesView.presets.forEach((p) => { sel[p.id] = { checked: false, weight: p.weight }; });
      rulesView.rules.forEach((r) => { sel[r.id] = { checked: true, weight: r.weight }; });
      setStaticSel(sel);
    } catch {
      setStaticRules(null);
    }
    const orderedRecordings = suiteData.recordings || [];
    const active = orderedRecordings.find((item) => item.status === "ACTIVE");
    // Chỉ khôi phục STOPPED khi đó là lần thao tác mới nhất. Một phiên lỗi cũ
    // không được che khung soạn sau khi giáo viên đã tạo scenario mới thành công.
    const latest = orderedRecordings[0];
    const pending = active || (latest?.status === "STOPPED" ? latest : undefined);
    activeRecordingId.current = pending?.id || null;
    acceptsRecorderEvents.current = pending?.status === "ACTIVE";
    setRecording(pending || null);
    setEditingScenarioId(pending?.revision_scenario_id || null);
    if (suiteData.golden_app_id) {
      const golden = await api<GoldenApp>(`/behavior-authoring/golden-apps/${suiteData.golden_app_id}`);
      const hasUploadedGolden = artifactData.some((item) => item.active && item.type === "GOLDEN_SOLUTION");
      setRuntimeUrl(runtimeData.available
        ? (runtimeData.runtime_url || runtimeData.runtime_path || "")
        : (!hasUploadedGolden ? (golden.runtime_url || "") : ""));
    }
  }, []);

  useEffect(() => {
    if (suite) return;
    const load = async () => {
      try {
        const requestedExam = search.get("exam");
        const rows = await api<Suite[]>(`/behavior-authoring/suites${requestedExam ? `?examId=${encodeURIComponent(requestedExam)}` : ""}`);
        setAvailableSuites(rows);
        if (requestedSuite) {
          await refresh(requestedSuite);
          return;
        }
        if (requestedExam && rows.length) await refresh(rows[0].id);
      } catch (caught) {
        setError(caught instanceof Error ? caught.message : String(caught));
      }
    };
    void load();
  }, [refresh, requestedSuite, search, suite]);

  const run = async (key: string, task: () => Promise<void>) => {
    setBusy(key); setError(""); setNotice(""); setMissingGoldenPackages([]);
    try { await task(); } catch (caught) { setError(caught instanceof Error ? caught.message : String(caught)); }
    finally { setBusy(""); }
  };

  const createSuite = () => run("create", async () => {
    const cleanExam = examId.trim();
    // Tên DB được dò khi tải ZIP Golden; không tạo hợp đồng từ dữ liệu nhập tay.
    if (!cleanExam || !name.trim()) throw new Error("Cần nhập mã đề và tên bộ chấm.");
    const golden = await api<GoldenApp>("/behavior-authoring/golden-apps", {
      method: "POST",
      body: JSON.stringify({ name: `${name.trim()} - Golden`, exam_id: cleanExam, platform: "WEB", ready: false }),
    });
    const created = await api<Suite>("/behavior-authoring/suites", {
      method: "POST",
      body: JSON.stringify({
        suite_code: `${cleanExam}_RAR`.toUpperCase().replace(/[^A-Z0-9_-]/g, "_"),
        exam_id: cleanExam,
        golden_app_id: golden.id,
        name: name.trim(),
        description: "Bộ chấm Record–Abstract–Replay",
        database_contract: { enabled: true, driver: "sqlite", ignore_columns: ["created_at", "updated_at"] },
      }),
    });
    await refresh(created.id);
    setAvailableSuites((current) => [created, ...current.filter((item) => item.id !== created.id)]);
    window.history.replaceState(null, "", `/teacher/behavior-authoring?suite=${encodeURIComponent(created.id)}`);
    setNotice("Đã tạo bộ chấm. Hãy tải Database ẩn và ZIP Golden để bắt đầu ghi thao tác.");
  });

  const openSuite = async (selected: Suite) => {
    setError("");
    await refresh(selected.id);
    window.history.replaceState(null, "", `/teacher/behavior-authoring?suite=${encodeURIComponent(selected.id)}`);
  };

  const closeSuite = () => {
    activeRecordingId.current = null;
    acceptsRecorderEvents.current = false;
    resetRecorderTransport();
    setSuite(null);
    setRecording(null);
    setArtifacts([]);
    setReadiness(null);
    setSoatScenario(null);
    setValidation(null);
    setRuntimeStatus(null);
    setRuntimeUrl("");
    setExamId("");
    setName("");
    setEditingScenarioId(null);
    // PHẢI đi qua router, không được dùng history.replaceState.
    //
    // replaceState đổi thanh địa chỉ nhưng KHÔNG báo cho router, nên useSearchParams vẫn trả
    // ?suite=... cũ. Effect nạp bộ chấm thấy suite vừa bị xoá khỏi state mà tham số vẫn còn
    // liền nạp lại ngay — bấm "Danh sách bộ chấm" lần đầu không có tác dụng, lần hai mới về.
    router.replace("/teacher/archive", { scroll: false });
  };

  const deleteSuite = (selected: Suite) => {
    if (!window.confirm(`Xóa vĩnh viễn bộ chấm “${selected.name}” và toàn bộ record, oracle, artifact, runtime liên quan?`)) return;
    run(`delete-suite-${selected.id}`, async () => {
      await api(`/behavior-authoring/suites/${selected.id}`, { method: "DELETE" });
      setAvailableSuites((current) => current.filter((item) => item.id !== selected.id));
      if (suite?.id === selected.id) closeSuite();
      setNotice(`Đã xóa bộ chấm ${selected.name}.`);
    });
  };

  /**
   * Nhân bản CHỈ đề bài (không đụng testcase/Golden Suite đang chạy) sang một mã đề mới, rồi mở
   * thẳng sang "Tạo đề" để sửa tay hoặc nhờ AI sửa tiếp — không nhân bản chính bộ chấm này.
   */
  const cloneSuiteExam = (selected: Suite) => {
    if (!selected.exam_id) { setError("Bộ này chưa gắn mã đề, không có đề bài để nhân bản."); return; }
    const targetId = window.prompt(`Nhập mã đề MỚI cho bản sao đề bài của "${selected.exam_id}":`, `${selected.exam_id}_COPY`);
    if (!targetId || !targetId.trim()) return;
    run(`clone-exam-${selected.id}`, async () => {
      const result = await api<{ exam_id: string }>(`/exam-setup/${selected.exam_id}/clone-handout`, {
        method: "POST",
        body: JSON.stringify({ target_exam_id: targetId.trim() }),
      });
      setNotice(`Đã nhân bản đề bài sang "${result.exam_id}" — đang mở "Tạo đề" để sửa tiếp.`);
      window.open(`/teacher/exam-authoring?examId=${encodeURIComponent(result.exam_id)}`, "_blank");
    });
  };

  const uploadArtifact = (type: ArtifactType, file?: File) => {
    if (!suite || !file) return;
    run(`upload-${type}`, async () => {
      const form = new FormData();
      form.append("file", file);
      try {
        await api(`/behavior-authoring/suites/${suite.id}/artifacts/${type}`, { method: "POST", body: form });
      } catch (caught) {
        const missing = (caught as Error & { missingPackages?: MissingPackageSpec[] }).missingPackages;
        if (type === "GOLDEN_SOLUTION" && missing?.length) setMissingGoldenPackages(missing);
        throw caught;
      }
      await refresh(suite.id);
      setNotice(`Đã lưu ${file.name} thành version mới của ${type}.`);
    });
  };

  /**
   * ĐƯA GOLDEN VỀ MÀN ĐẦU VỚI DỮ LIỆU GỐC — bắt buộc trước mỗi phiên soạn.
   *
   * Vì sao: máy chấm luôn replay mỗi hàm TỪ ĐẦU, trên database ẩn nguyên bản. Nếu giảng viên
   * ghi hình lúc Golden đang ở màn khác, hoặc dữ liệu đã bị hàm ADD trước đó thêm vào, thì
   * mọi bước ghi được đều tính từ một trạng thái mà máy chấm không bao giờ gặp — replay chắc
   * chắn trượt, mà lỗi chỉ lộ ra ở khâu capture oracle.
   *
   * Nạp lại bằng lệnh bridge chứ không phải iframe.contentWindow.location.reload(): khung
   * Golden ở cổng khác (8090) nên trình duyệt chặn truy cập chéo nguồn. Lệnh reload chạy BÊN
   * TRONG iframe, và recorder entry sẽ ghi lại hidden.db đè lên app.db lúc khởi động.
   */
  const napLaiGolden = async () => {
    const frame = goldenFrame.current?.contentWindow;
    if (!frame) throw new Error("Khung Golden chưa sẵn sàng.");
    setRecorderReady(false);
    const cho = new Promise<void>((resolve, reject) => {
      const hetGio = window.setTimeout(() => {
        choGoldenSanSang.current = null;
        reject(new Error("Golden không khởi động lại kịp sau 25 giây. Thử bấm “Build & mở Golden” rồi record lại."));
      }, 25000);
      choGoldenSanSang.current = () => { window.clearTimeout(hetGio); resolve(); };
    });
    frame.postMessage(
      { type: "GOLDEN_RECORDER_COMMAND", action: "perform_route_action", route_action: "reload" },
      runtimeOrigin || "*",
    );
    await cho;
  };

  const startRecording = () => {
    if (!suite) return;
    // Chưa build Golden thì không có gì để bám thao tác: recorder nằm TRONG bản web của Golden.
    // Trước đây nút vẫn bấm được và phiên record vẫn mở ra, chỉ là không ghi nổi một bước nào.
    if (!previewUrl) {
      setError("Chưa mở Golden. Bấm “Build & mở Golden” trong phần Thao tác trên Golden App trước khi ghi thao tác.");
      return;
    }
    run("record-start", async () => {
      setEditingScenarioId(null);
      resetRecorderTransport();
      await napLaiGolden();
      const created = await api<Recording>(`/behavior-authoring/suites/${suite.id}/recordings`, {
        method: "POST", body: JSON.stringify({ name: scenarioName, viewport: { width: viewportWidth, height: viewportHeight, device_pixel_ratio: 1, brightness: cheDoToi ? "dark" : "light" }, initial_state: { reset_storage: true, preferences: docBoNhoBanDau(prefsBanDau) } }),
      });
      activeRecordingId.current = created.id;
      acceptsRecorderEvents.current = created.status === "ACTIVE";
      setRecording(created);
      await refresh(suite.id);
    });
  };

  const deployGoldenRuntime = () => suite && run("runtime-deploy", async () => {
    setRuntimeUrl("");
    setRecorderReady(false);
    const deployed = await api<RuntimeStatus>(`/behavior-authoring/suites/${suite.id}/runtime/deploy`, { method: "POST" });
    setRuntimeStatus(deployed);
    if (!deployed.available) throw new Error(deployed.message || "Golden runtime chưa sẵn sàng.");
    setRuntimeUrl(deployed.runtime_url || deployed.runtime_path || "");
    setNotice(deployed.cached ? "Golden runtime đã sẵn sàng từ bản build hiện tại." : "Đã build Golden App và gắn semantic recorder.");
  });

  const captureUiSnapshot = () => {
    if (!recording || !goldenFrame.current?.contentWindow) return;
    goldenFrame.current.contentWindow.postMessage(
      { type: "GOLDEN_RECORDER_COMMAND", action: "snapshot_ui" },
      runtimeOrigin || "*",
    );
  };

  /** Hỏi Golden xem màn đang mở có những định danh nào. Rẻ, không ghi gì, gọi lại thoải mái. */
  const layDinhDanhTrenMan = () => {
    if (!goldenFrame.current?.contentWindow) return;
    goldenFrame.current.contentWindow.postMessage(
      { type: "GOLDEN_RECORDER_COMMAND", action: "snapshot_identifiers" },
      runtimeOrigin || "*",
    );
  };

  const resetRecorderTransport = () => {
    recorderEventQueue.current = Promise.resolve();
    recorderQueueError.current = null;
    flushWaiters.current.forEach((waiter) => window.clearTimeout(waiter.timer));
    flushWaiters.current.clear();
  };

  /** Ghi event iframe theo đúng thứ tự; lỗi ghi event phải chặn Stop thay vì mất action âm thầm. */
  const enqueueRecorderEvent = (event: JsonMap, recordingId: string, suiteId: string) => {
    recorderEventQueue.current = recorderEventQueue.current.then(async () => {
      if (recorderQueueError.current) return;
      try {
        await api(`/behavior-authoring/recordings/${recordingId}/events`, {
          method: "POST",
          body: JSON.stringify(event),
        });
        // Refresh chỉ để cập nhật danh sách đang nhìn; event đã lưu thành công thì lỗi
        // refresh không được biến thành lỗi dữ liệu và khóa cả phiên record.
        try { await refresh(suiteId); }
        catch { setNotice("Đã lưu thao tác nhưng chưa làm mới được danh sách hiển thị."); }
      } catch (caught) {
        const failure = caught instanceof Error ? caught : new Error(String(caught));
        recorderQueueError.current = failure;
        setError(`Không lưu được thao tác record: ${failure.message}`);
      }
    });
    return recorderEventQueue.current;
  };

  /** Yêu cầu iframe chốt ô đang nhập và xác nhận đã phát event enter_text. */
  const requestRecorderFlush = () => {
    const frame = goldenFrame.current?.contentWindow;
    if (!frame || !recorderReady || !recording || recording.status !== "ACTIVE") return Promise.resolve();
    const requestId = globalThis.crypto?.randomUUID?.()
      || `flush-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        flushWaiters.current.delete(requestId);
        reject(new Error("Golden recorder không xác nhận được giá trị ô nhập cuối. Hãy Build & mở Golden lại rồi thử tiếp."));
      }, 3_000);
      flushWaiters.current.set(requestId, { resolve, reject, timer });
      frame.postMessage(
        { type: "GOLDEN_RECORDER_COMMAND", action: "flush_input", request_id: requestId },
        runtimeOrigin || "*",
      );
    });
  };

  const awaitRecorderEvents = async () => {
    await recorderEventQueue.current;
    if (recorderQueueError.current) throw recorderQueueError.current;
  };

  const appendAction = (payload?: JsonMap) => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) {
      setError("Phiên record không còn nhận thao tác — hãy tải lại trang để nối lại phiên.");
      return;
    }
    const targetNeeded = ["tap", "enter_text", "clear_text", "scroll", "drag", "wait_until"].includes(action);
    const uriNeeded = ["boot_with_uri", "open_uri", "wait_for_route"].includes(action);
    if (!payload && targetNeeded && !locatorValue.trim()) {
      setError(`Action ${action} cần giá trị nhận diện semantic.`);
      return;
    }
    if (!payload && uriNeeded && !inputValue.trim()) {
      setError(`Action ${action} cần URI/path, ví dụ /movies/42?tab=cast.`);
      return;
    }
    const event = payload || {
      kind: "action",
      stage: "ACTION",
      action,
      ...(action === "drag" ? { delta: { x: Number(keoX) || 0, y: Number(keoY) || 0 } } : {}),
      target: targetNeeded ? { [locator]: locatorValue.trim() } : {},
      attribute: targetNeeded ? locator : "none",
      attributeValue: targetNeeded ? locatorValue.trim() : "",
      valueType: "string",
      value: action === "enter_text" || uriNeeded ? inputValue.trim() : "",
      ...(uriNeeded ? { uri: inputValue.trim() } : {}),
      browser: "flutter_tester",
    };
    if (payload) {
      void enqueueRecorderEvent(event, recordingId, suite.id);
      return;
    }
    run("record-event", async () => {
      // Action thêm tay cũng là ranh giới logic: lưu input còn chờ trước action mới.
      await requestRecorderFlush();
      await awaitRecorderEvents();
      await enqueueRecorderEvent(event, recordingId, suite.id);
      await awaitRecorderEvents();
      if (["boot_with_uri", "open_uri", "browser_back", "browser_forward", "reload"].includes(action)) {
        goldenFrame.current?.contentWindow?.postMessage(
          { type: "GOLDEN_RECORDER_COMMAND", action: "perform_route_action", route_action: action, uri: inputValue.trim() },
          runtimeOrigin || "*",
        );
      }
      setLocatorValue(""); setInputValue("");
    });
  };

  const appendRouteCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !suite || recording?.status !== "ACTIVE" || !acceptsRecorderEvents.current) {
      setError("Phiên record không còn nhận checkpoint route.");
      return;
    }
    if (!routeExpected.trim() && routeCanPop === "ignore") {
      setError("Cần nhập URI/path hoặc chọn trạng thái canPop.");
      return;
    }
    run("record-route-checkpoint", async () => {
      await requestRecorderFlush();
      await awaitRecorderEvents();
      const expect: JsonMap = {};
      if (routeExpected.trim()) expect.uri = routeExpected.trim();
      if (routeCanPop !== "ignore") expect.can_pop = routeCanPop === "true";
      await api(`/behavior-authoring/recordings/${recordingId}/events`, {
        method: "POST",
        body: JSON.stringify({
          kind: "route_state", checkpoint: true, stage: "ASSERT", action: "observe_route",
          browser: "flutter_tester", expect,
        }),
      });
      await refresh(suite.id);
    });
  };

  /** Loại Ch.7 đang chọn; null nghĩa là đang ở tiêu chí quan hệ A/B cũ. */
  const loaiCh7Hien = CH7_LOAI.find((x) => x.ma === ch7Kind) || null;

  /**
   * Định danh đã thấy trên màn Golden — nguồn gợi ý cho mọi ô "định danh" của Ch.7.
   * Gõ tay vẫn được, nhưng gõ sai một chữ là tiêu chí trượt oan lúc chấm nên mặc định
   * phải cho chọn từ thứ máy chấm THẬT SỰ nhìn thấy.
   */
  const dinhDanhDaThay = useMemo(() => {
    const ra = new Set<string>(dinhDanhTrenMan);
    for (const it of uiInventory || []) {
      if (it.attribute === "semantic_id" || it.attribute === "semanticId") ra.add(it.value);
      if (it.identifier) ra.add(it.identifier);
    }
    return [...ra].filter(Boolean).sort();
  }, [uiInventory, dinhDanhTrenMan]);

  /**
   * Định danh gõ vào mà màn Golden đang mở KHÔNG có. Gần như luôn là gõ sai một chữ, mà sai
   * một chữ thì phải tới lượt Kiểm Golden mới lộ — lúc đó câu lỗi chỉ nói "không tìm thấy",
   * không nói là do chính tả.
   *
   * CHỈ cảnh báo, KHÔNG chặn: ảnh chụp định danh là của MÀN ĐANG MỞ (lệnh snapshot_identifiers),
   * nên đích nằm trong hộp thoại chưa bật hay ở màn chưa vào vẫn hợp lệ dù chưa thấy. Chặn cứng
   * là chặn luôn việc đúng.
   */
  const dinhDanhLa = (gt: string) => {
    const v = gt.trim();
    return v.length > 0 && dinhDanhDaThay.length > 0 && !dinhDanhDaThay.includes(v);
  };
  const vienDinhDanh = (gt: string) =>
    dinhDanhLa(gt) ? "border-amber-500 ring-1 ring-amber-400" : "border-slate-300 dark:border-slate-700";
  const NHAC_DINH_DANH_LA = "Chưa thấy định danh này trên màn Golden đang mở — kiểm lại chính tả, "
    + "hoặc mở tới màn có nó rồi bấm “Đọc lại định danh”.";

  /** Đổi loại thì nạp sẵn giá trị mặc định, để ô đang hiện trên màn cũng là ô sẽ gửi đi. */
  const doiLoaiCh7 = (ma: string) => {
    setCh7Kind(ma);
    // Đọc cho MỌI loại, kể cả "quan_he": loại này cũng gõ định danh vào ô A/B nên cũng cần
    // danh sách gợi ý. Bỏ sót nó thì ở loại hay dùng nhất, cảnh báo gõ sai không bao giờ bật.
    layDinhDanhTrenMan();
    const loai = CH7_LOAI.find((x) => x.ma === ma);
    const macDinh: Record<string, string> = {};
    for (const t of loai?.truong || []) {
      if (t.macDinh) macDinh[t.khoa] = t.macDinh;
      else if (t.batBuoc && t.kieu === "chon" && t.chon?.length) macDinh[t.khoa] = t.chon[0][0];
    }
    setCh7Expect(macDinh);
  };

  const appendLayoutCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !suite || recording?.status !== "ACTIVE" || !acceptsRecorderEvents.current) {
      setError("Phiên record không còn nhận checkpoint bố cục.");
      return;
    }
    // Ch.7 đi đường riêng: đích luôn là định danh, còn ô kỳ vọng thì tra từ CH7_LOAI
    // nên mọi phép kiểm ở đây đúng bằng phép kiểm backend sẽ làm lúc lưu.
    const loaiCh7 = CH7_LOAI.find((x) => x.ma === ch7Kind);
    if (loaiCh7) {
      if (!ch7Target.trim()) {
        setError(`Cần định danh đích: ${loaiCh7.dichNhan}.`);
        return;
      }
      const thieu = loaiCh7.truong.find((t) => t.batBuoc && !(ch7Expect[t.khoa] || "").trim());
      if (thieu) {
        setError(`Tiêu chí "${loaiCh7.ten}" còn thiếu ô "${thieu.nhan}".`);
        return;
      }
      // Engine ném lỗi lúc CHẤM nếu tabs ít hơn 2 cặp. Chặn ngay ở đây để người soạn
      // không phải chạy thử mới biết mình gõ thiếu.
      if (ch7Kind === "component_indexed_switch") {
        const cap = (ch7Expect.tabs || "").split(",").map((s) => s.trim()).filter(Boolean);
        if (cap.length < 2 || cap.some((c) => c.split(":").filter((p) => p.trim()).length !== 2)) {
          setError('Ô "Các cặp tab:trang" cần ít nhất 2 cặp dạng tab.mot:trang.mot, ngăn nhau bằng dấu phẩy.');
          return;
        }
      }
      const expectCh7: JsonMap = {};
      for (const t of loaiCh7.truong) {
        const v = (ch7Expect[t.khoa] || "").trim();
        if (!v) continue;
        expectCh7[t.khoa] = t.khoa === "collapse" ? v === "true" : v;
      }
      run("record-layout-checkpoint", async () => {
        await requestRecorderFlush();
        await awaitRecorderEvents();
        await api(`/behavior-authoring/recordings/${recordingId}/events`, {
          method: "POST",
          body: JSON.stringify({
            kind: ch7Kind, checkpoint: true, stage: "ASSERT", action: "observe_ui",
            browser: "flutter_tester",
            target: { semantic_id: ch7Target.trim() },
            expect: expectCh7,
            name: `${loaiCh7.ten} — "${ch7Target.trim()}"`,
          }),
        });
        await refresh(suite.id);
        setCh7Target("");
        setCh7Expect({});
      });
      return;
    }
    if (!layoutFirstValue.trim() || !layoutSecondValue.trim()) {
      setError("Cần chọn/nhập đủ hai thành phần để so quan hệ bố cục.");
      return;
    }
    run("record-layout-checkpoint", async () => {
      await requestRecorderFlush();
      await awaitRecorderEvents();
      await api(`/behavior-authoring/recordings/${recordingId}/events`, {
        method: "POST",
        body: JSON.stringify({
          kind: "layout_relation", checkpoint: true, stage: "ASSERT", action: "observe_ui",
          browser: "flutter_tester",
          target: { [layoutFirstLocator]: layoutFirstValue.trim() },
          relative_to: { [layoutSecondLocator]: layoutSecondValue.trim() },
          relation: layoutRelation,
          tolerance_pct: Math.min(50, Math.max(0, Number(layoutTolerance))),
        }),
      });
      await refresh(suite.id);
      setLayoutFirstValue(""); setLayoutSecondValue("");
    });
  };


  const saveUiCriteria = () => {
    const recordingId = activeRecordingId.current;
    const chosen = (uiInventory || []).filter((it) => it.checked);
    // Guard KHÔNG được im lặng: nút bấm mà không có gì xảy ra thì người soạn tưởng
    // trang hỏng và tải lại từ đầu — đúng ca đã xảy ra thật ngày 29/8.
    if (chosen.length === 0) { setError("Chưa tick thành phần nào để lưu."); return; }
    if (!recordingId || !suite) { setError("Không còn phiên record nào gắn với trang — hãy tải lại trang."); return; }
    if (recording?.status !== "ACTIVE" || !acceptsRecorderEvents.current) {
      setError(`Phiên record đang ở trạng thái ${recording?.status || "không rõ"}, không nhận thêm tiêu chí. Hãy tải lại trang để nối lại phiên.`);
      return;
    }
    run("record-ui-criteria", async () => {
      docDiem(scenarioWeight, "Hàm test", true);
      const presence = docDiem(uiGroupWeight, "Tiêu chí hiện diện");
      const position = viTriOn ? docDiem(viTriWeight, "Tiêu chí vị trí") : 0;
      const color = mauOn ? docDiem(mauWeight, "Tiêu chí màu sắc") : 0;
      if (presence + position + color - diemUiConLai > 1e-9) throw new Error("Tổng điểm tiêu chí giao diện vượt phần điểm còn lại của hàm test.");
      // Checkpoint phải đứng sau giá trị cuối của ô đang nhập. Không dựa vào thời
      // gian nghỉ gõ: bridge chốt theo lệnh và queue bảo đảm POST đúng thứ tự.
      await requestRecorderFlush();
      await awaitRecorderEvents();
      const screen = uiScreenName.trim() || "Màn hình";
      const slug = screen.normalize("NFD").replace(/[̀-ͯ]/g, "")
        .replace(/đ/g, "d").replace(/Đ/g, "D").replace(/[^a-zA-Z0-9]+/g, "_").toUpperCase().replace(/^_+|_+$/g, "");

      // Diem cua tung MUC do nguoi soan dat (tong bi rang vao phan con lai cua ham),
      // roi chia deu xuong cac thanh phan duoc tick. Vao materializer chung mot luat
      // ty le voi moi checkpoint khac nen tong ruot van bang dung trong so ham.
      const cacMat = [
        { bat: true, kind: "component_present", hau: "", nhan: "có", diem: presence, saiSo: 0 },
        { bat: viTriOn, kind: "component_position", hau: "_VITRI", nhan: "đúng vị trí", diem: position, saiSo: viTriSaiSo },
        { bat: mauOn, kind: "component_color", hau: "_MAU", nhan: "đúng màu", diem: color, saiSo: mauSaiSo },
      ].filter((m) => m.bat);

      let daLuu = 0;
      for (const mat of cacMat) {
        const diemDong = chiaDeu(Number(mat.diem), chosen.length);
        for (let i = 0; i < chosen.length; i++) {
          const it = chosen[i];
          await api(`/behavior-authoring/recordings/${recordingId}/events`, {
            method: "POST",
            body: JSON.stringify({
              kind: mat.kind, checkpoint: true, stage: "ASSERT", action: "observe_ui",
              browser: "flutter_tester",
              target: { [it.attribute]: it.value }, visible: true,
              attribute: it.attribute, attributeValue: it.value, valueType: "string", value: "",
              name: `${screen} — ${mat.nhan} ${it.value}`, weight: diemDong[i],
              ...(mat.saiSo > 0 ? { tolerance_pct: Math.min(50, Math.max(0.5, Number(mat.saiSo))) } : {}),
            }),
          });
          daLuu++;
        }
      }

      setUiInventory(null);
      setNotice(`Đã lưu ${daLuu} tiêu chí giao diện cho ${chosen.length} thành phần của màn "${screen}".`);
      await refresh(suite.id);
    });
  };

  // "khoa=gia tri" moi dong mot cap. true/false/so nhan dung kieu, con lai la chuoi —
  // de nguoi ra de khong phai biet JSON.
  const docBoNhoBanDau = (chuoi: string): JsonMap => {
    const ra: JsonMap = {};
    chuoi.split(/\r?\n/).forEach((dong) => {
      const cat = dong.indexOf("=");
      if (cat <= 0) return;
      const khoa = dong.slice(0, cat).trim();
      const tho = dong.slice(cat + 1).trim();
      if (!khoa) return;
      if (tho === "true" || tho === "false") ra[khoa] = tho === "true";
      else if (tho !== "" && !Number.isNaN(Number(tho))) ra[khoa] = Number(tho);
      else ra[khoa] = tho;
    });
    return ra;
  };

  // Danh sách đề đã publish testcase, để chọn đề cần bàn giao.
  useEffect(() => {
    fetch(`${API_BASE}/exam-setup/list`)
      .then((r) => (r.ok ? r.json() : []))
      .then((ds) => {
        const loc = (Array.isArray(ds) ? ds : []).filter(
          (d: JsonMap) => String(d.testcaseStatus || "") === "PUBLISHED");
        setDeDaPublish(loc);
        setDeGiao((truoc) => truoc || String(loc[0]?.examId || ""));
      })
      .catch(() => setDeDaPublish([]));
  }, []);

  /** Trình duyệt không cho gọi thẳng "lưu file", phải mượn một thẻ <a> ẩn. */
  const taiBlob = (blob: Blob, ten: string) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = ten;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  const tepTuApi = async (duong: string) => {
    const res = await fetch(`${API_BASE}${duong}`);
    if (!res.ok) {
      const d = await res.json().catch(() => ({} as JsonMap));
      throw new Error(String(d.error || `HTTP ${res.status}`));
    }
    return res.blob();
  };

  /**
   * GIAO BỘ CHẤM: tải về HAI file trong một lần bấm — gói cho người chấm và khung phát cho sinh viên.
   *
   * Sinh cùng lúc là cố ý: khung phát dựng từ chính Golden đang xuất, nên hai thứ chắc chắn ra từ
   * một bản Golden. Tách thành hai lần bấm là mở lại đúng cái cửa sổ để chúng trôi khỏi nhau.
   *
   * Hai file riêng chứ không lồng vào nhau: cửa nạp bên người chấm đòi các file testcase nằm ở
   * GỐC zip, bọc thêm một lớp là gãy.
   */
  const xuatGoiBanGiao = () => {
    if (!deGiao) { setError("Chưa chọn đề để giao."); return; }
    run("xuat-goi", async () => {
      const truoc = await fetch(
        `${API_BASE}/exam-setup/${encodeURIComponent(deGiao)}/khung-phat/trang-thai`,
      ).then((r) => (r.ok ? r.json() : null)).catch(() => null);

      taiBlob(await tepTuApi(`/exam-setup/${encodeURIComponent(deGiao)}/xuat-goi`),
        `bo-cham-${deGiao}.zip`);
      taiBlob(await tepTuApi(`/exam-setup/${encodeURIComponent(deGiao)}/khung-phat`),
        `khung-phat-${deGiao}.zip`);

      setNotice(
        `Đã tải 2 file của đề ${deGiao}: bo-cham-${deGiao}.zip gửi người chấm, `
        + `khung-phat-${deGiao}.zip phát cho sinh viên.`
        + (truoc?.lech
          ? " ⚠ Golden đã đổi so với lần xuất trước — khung phát cũ sinh viên đang cầm KHÔNG còn"
            + " khớp bộ dùng để chấm, nhớ phát lại khung mới."
          : ""),
      );
    });
  };

  const maMan = (ten: string) => ten.normalize("NFD").replace(/[̀-ͯ]/g, "")
    .replace(/đ/g, "d").replace(/Đ/g, "D").replace(/[^a-zA-Z0-9]+/g, "_").toUpperCase().replace(/^_+|_+$/g, "");

  // Thêm tiêu chí cho các ICON vừa kiểm kê. Khác đường trên ở chỗ KHÔNG đi qua phiên
  // record (lúc này phiên đã đóng): ghi thẳng vào checkpoints của scenario, backend tự
  // chạy lại capture để nướng vị trí, màu và luật lặp.
  const luuTieuChiIcon = () => {
    if (!suite || !iconScenario) return;
    const chon = (iconInventory || []).filter((it) => it.checked);
    if (chon.length === 0) { setError("Chưa tick icon hay ảnh nào để chấm."); return; }
    run("icon-criteria", async () => {
      const presence = iconCoMatOn ? docDiem(uiGroupWeight, "Tiêu chí hiện diện") : 0;
      const position = iconViTriOn ? docDiem(viTriWeight, "Tiêu chí vị trí") : 0;
      const color = iconMauOn ? docDiem(mauWeight, "Tiêu chí màu sắc") : 0;
      const man = uiScreenName.trim() || "Màn hình";
      const slug = maMan(man);
      const cu = iconScenario.checkpoints;
      // Số thứ tự tiếp theo: id checkpoint phải duy nhất trong scenario vì oracle nối
      // vào chính id đó.
      let so = cu.reduce((m, c) => Math.max(m, Number(String(c.id || "").replace(/\D+/g, "")) || 0), 0);
      const cacMat = [
        { bat: iconCoMatOn, kind: "component_present", hau: "", nhan: "có", diem: presence, saiSo: 0 },
        { bat: iconViTriOn, kind: "component_position", hau: "_VITRI", nhan: "đúng vị trí", diem: position, saiSo: viTriSaiSo },
        { bat: iconMauOn, kind: "component_color", hau: "_MAU", nhan: "đúng màu", diem: color, saiSo: mauSaiSo },
      ].filter((m) => m.bat);
      if (cacMat.length === 0) { setError("Chưa chọn mặt nào để chấm (có mặt, vị trí, màu)."); return; }

      const them: JsonMap[] = [];
      for (const mat of cacMat) {
        const diemDong = chiaDeu(Number(mat.diem), chon.length);
        chon.forEach((it, i) => {
          so += 1;
          them.push({
            id: `checkpoint_${so}`, kind: mat.kind, checkpoint: true, stage: "ASSERT",
            action: "observe_ui", browser: "flutter_tester",
            target: it.loai === "icon" ? { icon: it.ten } : { image: it.ten }, visible: true,
            attribute: it.loai, attributeValue: it.ten, valueType: "string", value: "",
            name: `${man} — ${mat.nhan} ${it.loai === "icon" ? "icon" : "ảnh"} ${it.ten}`,
            weight: diemDong[i],
            ...(mat.saiSo > 0 ? { tolerance_pct: Math.min(50, Math.max(0.5, Number(mat.saiSo))) } : {}),
          });
        });
      }
      await api(`/behavior-authoring/scenarios/${iconScenario.id}`, {
        method: "PUT", body: JSON.stringify({ checkpoints: [...cu, ...them] }),
      });
      setIconInventory(null);
      setIconScenario(null);
      setNotice(`Đã thêm ${them.length} tiêu chí icon và capture lại oracle. Icon lặp theo dòng được chấm ở mọi dòng.`);
      await refresh(suite.id);
    });
  };




  // Phan diem cua ham con trong cho tieu chi giao dien: tru phan cac checkpoint
  // da ghi trong phien (moi cai giu cho 1d mac dinh, chia lai sau o bang Chia diem).
  const diemChotDaGhi = (recording?.raw_trace || []).reduce((tong, ev) => {
    const kind = String(ev.kind || "");
    const laChot = kind !== "action" && kind !== "";
    return tong + (laChot ? Number(ev.weight ?? 1) : 0);
  }, 0);
  const diemUiConLai = Math.max(0, diemSo(scenarioWeight) - diemChotDaGhi);
  const tongCacMuc = diemSo(uiGroupWeight)
    + (viTriOn ? diemSo(viTriWeight) : 0)
    + (mauOn ? diemSo(mauWeight) : 0);
  const vuotMucUi = tongCacMuc - diemUiConLai > 1e-9;

  // Mo bang quet la chia deu phan con lai cho cac muc dang bat — KHONG fix cung con so
  // nao; nguoi soan chinh tay tuy y, mien tong khong vuot phan con lai cua ham.
  useEffect(() => {
    if (uiInventory && !daKhoiTaoDiemUi.current) {
      daKhoiTaoDiemUi.current = true;
      const soMuc = 1 + (viTriOn ? 1 : 0) + (mauOn ? 1 : 0);
      // Dung chinh chiaDeu de bang quet va bang Chia diem khong bao gio lech nhau.
      const phan = chiaDeu(diemUiConLai, soMuc);
      let k = 0;
      setUiGroupWeight(phan[k++]);
      setViTriWeight(viTriOn ? phan[k++] : 0);
      setMauWeight(mauOn ? phan[k++] : 0);
    }
    if (!uiInventory) daKhoiTaoDiemUi.current = false;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [uiInventory]);

  /** Xin khung Golden đo một lượt và chờ kết quả. */
  const doMotLuotKhung = () => new Promise<AnhChupKhung>((resolve, reject) => {
    const frame = goldenFrame.current?.contentWindow;
    if (!frame) { reject(new Error("Khung Golden chưa sẵn sàng")); return; }
    const hetGio = window.setTimeout(() => {
      respChoDo.current = null;
      reject(new Error("Khung Golden không trả lời lệnh đo. Bản build có thể là bản cũ — bấm Build & mở Golden lại."));
    }, 8000);
    respChoDo.current = (v) => { window.clearTimeout(hetGio); resolve(v); };
    frame.postMessage({ type: "GOLDEN_RECORDER_COMMAND", action: "snapshot_rects" }, "*");
  });

  /** Chờ Flutter dàn lại sau khi đổi cỡ iframe. Hai frame là đủ, cộng biên an toàn. */
  const choDanLai = (ms: number) => new Promise((r) => window.setTimeout(r, ms));

  /**
   * DÒ CHỖ CO GIÃN: đo ở khung điện thoại, đo lại ở khung desktop, giữ những cặp ĐỔI quan hệ.
   *
   * Vì sao chỉ giữ cặp đổi: đó chính là bằng chứng app có co giãn. Cặp không đổi thì bài bố cục
   * cứng cũng đạt, tick vào là cho không điểm.
   */
  const doCoGian = (item: JsonMap) => {
    if (!previewUrl) { setError("Chưa build Golden nên chưa đo được."); return; }
    run("resp-scan", async () => {
      setRespKq(null);
      setKhungDesktop(false);
      await choDanLai(500);
      const dienThoai = await doMotLuotKhung();
      setKhungDesktop(true);
      await choDanLai(1200);
      const desktop = await doMotLuotKhung();
      setKhungDesktop(false);

      const kq = phanTichCoGian(dienThoai, desktop);
      setRespScenario({
        id: String(item.id || ""),
        ma: String(item.scenario_code || item.name || ""),
        checkpoints: Array.isArray(item.checkpoints) ? (item.checkpoints as JsonMap[]) : [],
      });
      setRespKq(kq);
      const manh = kq.bangChung.length + kq.doiCot.length;
      if (manh === 0) {
        setNotice("KHÔNG tìm thấy dấu hiệu co giãn thật nào: không thành phần nào hiện thêm hay biến mất, nội dung vẫn nở hết bề ngang, không nhóm nào đổi số cột. Golden này gần như chắc chắn KHÔNG có mã responsive — mọi thứ đổi chỗ bên dưới chỉ là Wrap tự xếp lại. Đừng chấm kĩ năng này cho bộ đề đó.");
      } else {
        setNotice(`Tìm được ${manh} dấu hiệu co giãn thật và ${kq.capReflow.length} cặp đổi quan hệ. Tick thứ muốn chấm rồi khai điểm.`);
      }
    });
  };

  /** Biến các cặp đã tick thành checkpoint layout_relation mang cờ khung desktop. */
  const luuTieuChiResponsive = () => {
    if (!suite || !respScenario || !respKq) return;
    const manh = respKq.bangChung.filter((b) => b.checked);
    const yeu = respKq.capReflow.filter((c) => c.checked);
    if (manh.length + yeu.length === 0) { setError("Chưa tick mục nào."); return; }
    run("resp-save", async () => {
      const score = docDiem(respDiem, "Tiêu chí responsive");
      const cu = respScenario.checkpoints;
      let so = cu.reduce((m, c) => Math.max(m, Number(String(c.id || "").replace(/\D+/g, "")) || 0), 0);
      const diemDong = chiaDeu(score, manh.length + yeu.length);
      // Nền chung của mọi tiêu chí responsive. Cờ `khung` là thứ materializer đọc để đẩy case
      // sang 1280×800; thiếu nó là tiêu chí âm thầm chạy ở khung điện thoại và luôn đạt.
      // KHÔNG gắn ui_group nữa (21/9/2026): nhóm điểm chỉ đến từ MÃ NHÓM của luồng. Nhãn cũ
      // lấy tên MÀN làm nhãn, nên hai màn cùng tên bị gộp một rọ, kéo tiêu chí của nhiều luồng
      // khác nhau vào chung một dòng điểm.
      const nen = {
        checkpoint: true, scope: "ui", stage: "ASSERT", action: "observe_ui",
        browser: "flutter_tester", khung: "desktop",
      };
      const them: JsonMap[] = [];
      manh.forEach((b, i) => {
        so += 1;
        const ten = b.it.label || b.it.identifier;
        if (b.loai === "vi_tri_kim") {
          them.push({
            ...nen, id: `checkpoint_${so}`, kind: "component_position",
            target: dichCuaThanhPhan(b.it), tolerance_pct: 5, weight: diemDong[i],
            name: `Responsive — ${ten} đúng chỗ khi nội dung bị kìm bề ngang`,
          });
          return;
        }
        them.push({
          ...nen, id: `checkpoint_${so}`, kind: "component_present",
          target: dichCuaThanhPhan(b.it), visible: b.loai === "hien_them", weight: diemDong[i],
          name: `Responsive — ${ten} ${b.loai === "hien_them" ? "phải hiện" : "phải ẩn"} ở khung desktop`,
        });
      });
      yeu.forEach((c, i) => {
        so += 1;
        them.push({
          ...nen, id: `checkpoint_${so}`, kind: "layout_relation",
          // auto = lấy chuẩn từ chính Golden lúc capture, ở đúng khung desktop.
          relation: "auto",
          target: dichCuaThanhPhan(c.a), relative_to: dichCuaThanhPhan(c.b),
          weight: diemDong[manh.length + i],
          name: `Responsive — ${c.a.label || c.a.identifier} và ${c.b.label || c.b.identifier} đổi từ "${TEN_QUAN_HE[c.dienThoai] || c.dienThoai}" sang "${TEN_QUAN_HE[c.desktop] || c.desktop}"`,
        });
      });
      await api(`/behavior-authoring/scenarios/${respScenario.id}`, {
        method: "PUT", body: JSON.stringify({ checkpoints: [...cu, ...them] }),
      });
      setRespKq(null);
      setRespScenario(null);
      setNotice(`Đã thêm ${them.length} tiêu chí responsive (${respDiem}đ chia đều). Phải capture lại oracle: lượt capture sẽ chạy thêm một lần ở khung desktop.`);
      await refresh(suite.id);
    });
  };

  // Bước gõ chữ chưa khai giá trị: replay sẽ gõ chuỗi rỗng và mọi tiêu chí phía sau
  // trượt theo, nên khoá nút sinh testcase cho tới khi điền đủ.
  const buocThieuGiaTri = (recording?.raw_trace || []).filter(
    (ev) => String(ev.action || "") === "enter_text" && !String(ev.value || "").trim(),
  ).length;

  // Ngan sach 100 diem cua ca bo cham: luat tinh + trong so tung ham + tieu chi
  // giao dien (mang diem TUYET DOI, cong rieng — khong an vao trong so ham).
  const diemTinh = staticRules ? staticRules.rules.reduce((t, r) => t + (Number(r.weight) || 0), 0) : 0;
  // Mot luat duy nhat: diem cua ham = trong so ham, ruot chia nhau ben trong.
  const diemTungHam = (suite?.scenarios || []).map((sc) => (
    { code: String(sc.scenario_code || ""), diem: Number(sc.weight) || 0 }
  ));
  const diemDaCho = diemTinh + diemTungHam.reduce((t, x) => t + x.diem, 0);
  const diemNganSachConLai = Math.max(0, 100 - diemDaCho);

  const appendUiCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    const textReady = Boolean(checkpointText.trim() || hiddenCheckpointText.trim());
    const componentReady = Boolean(checkpointLocatorValue.trim());
    if (uiCheckpointType === "text" && !textReady) { setError("Cần nhập text mong đợi cho checkpoint."); return; }
    if (uiCheckpointType === "component" && !componentReady) { setError("Cần nhập giá trị nhận diện thành phần cho checkpoint."); return; }
    if (uiCheckpointType === "widget_state" && !wsWidget.trim()) { setError("Cần chọn loại widget cần đọc trạng thái."); return; }
    if (uiCheckpointType === "text_style" && !tsLocatorValue.trim()) { setError("Cần nhập dòng chữ cần đo kiểu chữ."); return; }
    if (uiCheckpointType === "widget_state" && !wsLocatorValue.trim() && wsProperty !== "ton_tai") { setError("Cần nhập giá trị nhận diện để biết đọc widget nào."); return; }
    if (uiCheckpointType === "preferences" && !prefsKey.trim()) { setError("Cần nhập tên khoá trong bộ nhớ của app."); return; }
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) {
      setError("Phiên record không còn nhận checkpoint — hãy tải lại trang để nối lại phiên.");
      return;
    }
    run("record-checkpoint", async () => {
          // Chốt giá trị nhập còn chờ trước khi ghi checkpoint: thứ tự phải đúng như
          // người soạn thấy trên màn (luật của bản v20).
          await requestRecorderFlush();
          await awaitRecorderEvents();
      // TRẠNG THÁI WIDGET đi đường riêng: nó không mô tả "có gì trên màn hình" mà đọc
      // một giá trị thật bên trong widget, nên hình dạng event khác hẳn ba loại kia.
      if (uiCheckpointType === "widget_state") {
        const nhan = THUOC_TINH_WIDGET.find(([ma]) => ma === wsProperty)?.[1] ?? wsProperty;
        await api(`/behavior-authoring/recordings/${recordingId}/events`, {
          method: "POST",
          body: JSON.stringify({
            kind: "widget_state", stage: "ASSERT", action: "observe_ui", browser: "flutter_tester",
            target: wsLocatorValue.trim() ? { [wsLocator]: wsLocatorValue.trim() } : {},
            widget: wsWidget.trim(),
            property: wsProperty,
            name: `${wsWidget.trim()}${wsLocatorValue.trim() ? ` "${wsLocatorValue.trim()}"` : ""} — ${nhan}`,
          }),
        });
        await refresh(suite.id);
        setWsLocatorValue("");
        return;
      }
      // KIỂU CHỮ và CHỦ ĐỀ: cùng khuôn "đọc một giá trị rồi so", giá trị chuẩn do hệ
      // thống tự đo trên Golden lúc sinh testcase.
      if (uiCheckpointType === "no_overflow") {
        await api(`/behavior-authoring/recordings/${recordingId}/events`, {
          method: "POST",
          body: JSON.stringify({
            kind: "no_overflow", stage: "ASSERT", action: "observe_ui", browser: "flutter_tester",
            name: "Cả luồng không vỡ bố cục",
          }),
        });
        await refresh(suite.id);
        return;
      }
      if (uiCheckpointType === "preferences") {
        await api(`/behavior-authoring/recordings/${recordingId}/events`, {
          method: "POST",
          body: JSON.stringify({
            kind: "preferences_observation", stage: "ASSERT", action: "observe_ui", browser: "flutter_tester",
            key: prefsKey.trim(),
            ...(prefsAbsent ? { expect: { absent: true } } : {}),
            name: prefsAbsent
              ? `Bộ nhớ app — đã xoá khoá "${prefsKey.trim()}"`
              : `Bộ nhớ app — đã lưu khoá "${prefsKey.trim()}"`,
          }),
        });
        await refresh(suite.id);
        setPrefsKey("");
        return;
      }
      if (uiCheckpointType === "text_style" || uiCheckpointType === "theme_value") {
        const laChu = uiCheckpointType === "text_style";
        const bang = laChu ? MAT_KIEU_CHU : GIA_TRI_CHU_DE;
        const ma = laChu ? tsProperty : tvProperty;
        const nhan = bang.find(([k]) => k === ma)?.[1] ?? ma;
        await api(`/behavior-authoring/recordings/${recordingId}/events`, {
          method: "POST",
          body: JSON.stringify({
            kind: uiCheckpointType, stage: "ASSERT", action: "observe_ui", browser: "flutter_tester",
            target: laChu ? { [tsLocator]: tsLocatorValue.trim() } : {},
            property: ma,
            ...(laThuocTinhMau(ma)
              ? { tolerance_pct: Math.min(50, Math.max(0.5, Number(saiSoMau))) }
              : {}),
            name: laChu ? `Chữ "${tsLocatorValue.trim()}" — ${nhan}` : `Chủ đề app — ${nhan}`,
          }),
        });
        await refresh(suite.id);
        setTsLocatorValue("");
        return;
      }
      const event: JsonMap = {
        kind: "checkpoint", stage: "ASSERT", action: "observe_ui", browser: "flutter_tester",
        attribute: uiCheckpointType === "component" ? checkpointLocator : uiCheckpointType,
        attributeValue: uiCheckpointType === "component" ? checkpointLocatorValue.trim() : checkpointText.trim(),
        valueType: uiCheckpointType === "no_exception" ? "boolean" : "string",
        value: uiCheckpointType === "no_exception" ? true : checkpointText.trim(),
      };
      if (uiCheckpointType === "text") {
        event.expect = {
          visible_texts: checkpointText.split(",").map((item) => item.trim()).filter(Boolean),
          hidden_texts: hiddenCheckpointText.split(",").map((item) => item.trim()).filter(Boolean),
          no_exception: true,
        };
      } else if (uiCheckpointType === "component") {
        const semanticNode: JsonMap = {
          target: { [checkpointLocator]: checkpointLocatorValue.trim() },
          role: checkpointRole,
          visible: checkpointVisible,
        };
        if (checkpointValue.trim()) semanticNode.value = checkpointValue;
        if (checkpointEnabled !== "ignore") semanticNode.enabled = checkpointEnabled === "true";
        if (checkpointChecked !== "ignore") semanticNode.checked = checkpointChecked === "true";
        event.attribute = "semantic_nodes";
        event.attributeValue = checkpointLocatorValue.trim();
        event.valueType = "json";
        event.value = [semanticNode];
        event.expect = { semantic_nodes: [semanticNode], no_exception: true };
      } else {
        event.no_exception = true;
        event.expect = { no_exception: true };
      }
      await api(`/behavior-authoring/recordings/${recordingId}/events`, {
        method: "POST",
        body: JSON.stringify(event),
      });
      await refresh(suite.id);
      setCheckpointText(""); setHiddenCheckpointText("");
    });
  };

  // ĐÃ GỠ suaGiaTriEvent (20/9/2026): ô cho sửa tay nội dung của một bước gõ chữ.
  //
  // Nó có từ thời recorder chưa đọc được chữ từ DOM Flutter Web. Nay đường đọc đã có ba
  // nguồn (input còn sống -> node semantics -> snapshot cuối của event input) và đo trên
  // Golden thật ngày 20/9 thì vào đủ, không mất dấu tiếng Việt: "Mua đèn học", "55000".
  //
  // Giữ ô sửa tay thì mở đúng một đường cho plan lệch khỏi thứ người soạn thật sự làm trên
  // app — mà đó là thứ duy nhất bộ chấm được phép mô tả. Ghi sai thì xóa bước rồi gõ lại.

  const deleteRecordedEvent = (sequence: number) => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) {
      setError("Phiên record không còn sửa được — hãy tải lại trang để nối lại phiên.");
      return;
    }
    run(`delete-event-${sequence}`, async () => {
      await api(`/behavior-authoring/recordings/${recordingId}/events/${sequence}`, { method: "DELETE" });
      await refresh(suite.id);
      setNotice(`Đã xóa thao tác/checkpoint số ${sequence}.`);
    });
  };

  const appendDatabaseCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    if (!databaseTable.trim()) { setError("Cần nhập tên bảng cho checkpoint database."); return; }
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) {
      setError("Phiên record không còn nhận checkpoint — hãy tải lại trang để nối lại phiên.");
      return;
    }
    run("record-db-checkpoint", async () => {
      await requestRecorderFlush();
      await awaitRecorderEvents();
      let row: JsonMap = {};
      try {
        const parsed = JSON.parse(databaseRow || "{}");
        if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Error();
        row = parsed as JsonMap;
      } catch {
        throw new Error("Row mong đợi phải là JSON object, ví dụ {\"uid\":\"SV01\"}.");
      }
      const count = databaseCount.trim() === "" ? undefined : Number(databaseCount);
      if (count !== undefined && (!Number.isInteger(count) || count < 0)) throw new Error("Count phải là số nguyên không âm.");
      await api(`/behavior-authoring/recordings/${recordingId}/events`, {
        method: "POST",
        body: JSON.stringify({
          kind: "database_observation", checkpoint: true, scope: "database",
          stage: "ASSERT", attribute: "table", attributeValue: databaseTable.trim(),
          valueType: "json", value: row, action: "observe_database", browser: "sqlite",
          table: databaseTable.trim(), operation: databaseOperation, row,
          absent: databaseOperation === "DELETE", ...(count === undefined ? {} : { count }),
        }),
      });
      await refresh(suite.id); setDatabaseRow("{}"); setDatabaseCount("");
    });
  };

  const stopAndAbstract = () => {
    // Kết quả duyệt cũ hết giá trị ngay khi có một scenario được sinh lại: để nguyên thì thẻ
    // vừa sửa xong vẫn đỏ. Xoá đi, người soạn bấm "Duyệt lại scenario" để xem còn sót cái nào.
    setSoatScenario(null);
    const recordingId = activeRecordingId.current;
    if (!recordingId || !recording || !suite) { setError("Không còn phiên record nào gắn với trang — hãy tải lại trang."); return; }
    if (!["ACTIVE", "STOPPED"].includes(recording.status)) {
      setError(`Phiên record đang ở trạng thái ${recording.status}, không sinh testcase được. Hãy tải lại trang.`);
      return;
    }
    if (!scenarioName.trim()) {
      setError("Cần nhập Tên luồng (ô ngay trên nút này) trước khi sinh testcase. Mã nhóm để trống được.");
      return;
    }
    run("record-stop", async () => {
      const score = docDiem(scenarioWeight, "Hàm test", true);
      const recordedCheckpoints = (recording.raw_trace || []).filter((event) => event.kind && event.kind !== "action");
      const weights = recordedCheckpoints.map((event, index) => docDiem(event.weight as DiemNhap ?? 1, `Checkpoint ${index + 1}`));
      if (weights.length > 0 && !weights.some((weight) => weight > 0)) {
        throw new Error(weights.length === 1
          ? "Hàm test chỉ có 1 checkpoint thì checkpoint đó phải lớn hơn 0 điểm."
          : "Hàm test phải có ít nhất một checkpoint lớn hơn 0 điểm.");
      }
      const oldWeight = Number(suite.scenarios?.find((sc) => String(sc.id) === editingScenarioId)?.weight ?? 0);
      if (diemDaCho - oldWeight + score > 100 + 1e-9) throw new Error("Điểm hàm test vượt ngân sách 100 điểm của bộ chấm.");
      let backendStopped = recording.status === "STOPPED";
      try {
        if (recording.status === "ACTIVE") {
          // Iframe phát enter_text cuối rồi ACK; sau ACK mới đóng cổng nhận và đợi
          // toàn bộ POST event hoàn tất. Đây là barrier chống /stop vượt request cuối.
          await requestRecorderFlush();
          acceptsRecorderEvents.current = false;
          await awaitRecorderEvents();
          await api(`/behavior-authoring/recordings/${recordingId}/stop`, { method: "POST", body: JSON.stringify({ final_observation: {} }) });
          backendStopped = true;
          setRecording((current) => current ? { ...current, status: "STOPPED" } : current);
        } else {
          acceptsRecorderEvents.current = false;
          await awaitRecorderEvents();
        }
        const sinhXong = await api<JsonMap>(`/behavior-authoring/recordings/${recordingId}/abstract`, {
          method: "POST",
          body: JSON.stringify({
            group_code: groupCode.trim(),
            name: scenarioName.trim(),
            weight: score,
            ...(editingScenarioId ? { replace_scenario_id: editingScenarioId } : {}),
            viewports: [
              // device_pixel_ratio để cố định 1: chấm bố cục đo bằng dp nên mật độ không
              // đổi điểm, chỉ làm ảnh bằng chứng nặng thêm.
              { width: viewportWidth, height: viewportHeight, device_pixel_ratio: 1, name: "phone", brightness: cheDoToi ? "dark" : "light" },
            ],
          }),
        });
        activeRecordingId.current = null;
        setRecording(null);
        resetRecorderTransport();
        await refresh(suite.id);
        setEditingScenarioId(null);
            // Số bước vừa nhận định danh từ Golden (Gói 2): 0 nghĩa là Golden chưa gắn
            // Semantics(identifier:) hoặc mọi bước đã có sẵn — cả hai đều không phải lỗi.
            const soDinhDanh = Number(sinhXong.identifier_step_count || 0);
            const duoiDinhDanh = soDinhDanh > 0 ? ` Đã nướng định danh vào ${soDinhDanh} bước.` : "";
            // Icon máy chấm nhìn thấy ở màn cuối luồng — mở bảng tick ngay, vì đây là lần duy
            // nhất trong luồng soạn đề mà thông tin này tồn tại.
            const dsIcon: { loai: "icon" | "image"; ten: string; tho: JsonMap }[] = [
              ...(Array.isArray(sinhXong.icons) ? (sinhXong.icons as JsonMap[]) : [])
                .map((it) => ({ loai: "icon" as const, ten: String(it.icon || ""), tho: it })),
              // Ảnh cũng phải bày ra: một tấm ảnh không nhãn thì DOM web không thấy, y hệt icon.
              ...(Array.isArray(sinhXong.images) ? (sinhXong.images as JsonMap[]) : [])
                .map((it) => ({ loai: "image" as const, ten: String(it.image || ""), tho: it })),
            ];
            if (dsIcon.length > 0 && sinhXong.id) {
              setIconScenario({
                id: String(sinhXong.id),
                ma: String(sinhXong.scenario_code || sinhXong.name || ""),
                checkpoints: Array.isArray(sinhXong.checkpoints) ? (sinhXong.checkpoints as JsonMap[]) : [],
              });
              setIconInventory(dsIcon.map((it) => ({
                loai: it.loai,
                ten: it.ten,
                count: Number(it.tho.count || 1),
                perRow: Boolean(it.tho.per_row),
                buttonType: String(it.tho.button_type || it.tho.widget_type || ""),
                checked: false,
              })));
            }
        if (sinhXong.capture_warning) {
          // Sinh testcase THÀNH CÔNG nhưng có mùi hỏng-im-lặng — phải đỏ để không bị bỏ qua.
          setError(`Đã sinh testcase, NHƯNG: ${String(sinhXong.capture_warning)}`);
        } else {
          setNotice((editingScenarioId
            ? "Đã cập nhật scenario, replay Golden trên Database ẩn và tạo lại oracle."
            : "Đã replay Golden trên Database ẩn, sinh Output Database, oracle và testcase-definition.json.") + duoiDinhDanh);
        }
      } catch (caught) {
        // Flush/lưu event lỗi trước khi backend /stop thì phiên vẫn ACTIVE và phải cho
        // người dùng sửa/thử lại. Nếu /stop đã thành công, state refresh sẽ đưa về STOPPED.
        if (!backendStopped && recording.status === "ACTIVE") acceptsRecorderEvents.current = true;
        throw caught;
      }
    });
  };

  const saveStaticRules = () => suite && run("static-rules", async () => {
    const chosen = (staticRules?.presets || [])
      .filter((p) => staticSel[p.id]?.checked)
      .map((p) => ({ ...p, weight: docDiem(staticSel[p.id].weight, `Luật tĩnh ${p.name}`, true), golden: undefined }));
    if (diemTungHam.reduce((sum, item) => sum + item.diem, 0) + chosen.reduce((sum, rule) => sum + rule.weight, 0) > 100 + 1e-9) {
      throw new Error("Tổng điểm luật tĩnh và hàm test vượt ngân sách 100 điểm của bộ chấm.");
    }
    const view = await api<StaticRulesView>(`/behavior-authoring/suites/${suite.id}/static-rules`, {
      method: "PUT",
      body: JSON.stringify({ rules: chosen }),
    });
    setStaticRules(view);
    const sel: Record<string, { checked: boolean; weight: number }> = {};
    view.presets.forEach((p) => { sel[p.id] = { checked: false, weight: p.weight }; });
    view.rules.forEach((r) => { sel[r.id] = { checked: true, weight: r.weight }; });
    setStaticSel(sel);
    const total = view.rules.reduce((sum, r) => sum + Number(r.weight || 0), 0);
    setNotice(`Đã lưu ${view.rules.length} luật tĩnh (${total} điểm). Publish lại bộ chấm để đưa vào đề.`);
  });

  const publish = () => suite && run("publish", async () => {
    if (!goldenReady) throw new Error("Hãy Build & mở Golden và chờ recorder kết nối trước khi publish.");
    if (!preflightPassed) throw new Error("Hãy chạy thử trên Golden và đạt toàn bộ checkpoint trước khi publish.");
    const daPublish = await api<JsonMap>(`/behavior-authoring/suites/${suite.id}/publish`, { method: "POST" });
    await refresh(suite.id);
    // Độ phủ định danh toàn bộ đề (backend đếm lúc publish): chỉ để biết, không chặn.
    const phu = (daPublish.identifier_coverage || {}) as JsonMap;
    const co = Number(phu.steps_with_identifier || 0);
    const tong = Number(phu.steps_total || 0);
    const duoi = tong > 0
      ? ` Định danh: ${co}/${tong} bước${co < tong ? "; bước còn lại được tìm bằng nhãn/chữ." : "."}`
      : "";
    setNotice("Bộ chấm đã publish và materialize thành runner có thể dùng khi chấm batch." + duoi);
  });

  const validateGolden = () => suite && run("validate-golden", async () => {
    if (!goldenReady) throw new Error("Hãy Build & mở Golden và chờ recorder kết nối trước khi chạy thử.");
    const result = await api<GoldenValidation>(`/behavior-authoring/suites/${suite.id}/validate-golden`, { method: "POST" });
    setValidation(result);
    await refresh(suite.id);
    if (result.status !== "PASSED") throw new Error(result.status === "UNAVAILABLE"
      ? "Không gọi được Docker để kiểm chứng Golden. Hãy bật Docker và thử lại."
      : `Golden preflight chưa pass (${result.passed_checkpoints || 0}/${result.total_checkpoints || 0} checkpoint).`);
    setNotice(`Golden Solution đã pass ${result.passed_checkpoints}/${result.total_checkpoints} checkpoint.`);
  });

  /**
   * Bấm MỘT lần thay cho việc mở từng luồng bấm "Sửa thao tác" rồi "Sinh lại testcase".
   *
   * Phải hỏi trước: mỗi luồng là một lượt chạy Docker nên bộ nhiều luồng mất vài phút, và
   * nó ghi đè oracle của toàn bộ bộ chấm.
   */
  const sinhLaiToanBo = () => {
    if (!suite) return;
    const soLuong = suite.scenarios?.length || 0;
    if (!window.confirm(`Sinh lại ${soLuong} scenario trên bản Golden hiện tại?\n\n`
      + `Thay cho ${soLuong} lần bấm “Sửa thao tác” + “Sinh lại testcase”. `
      + `Điểm đã chia và ràng buộc đã gắn được giữ nguyên.\n\n`
      + `Mỗi luồng là một lượt chạy Docker nên có thể mất vài phút.`)) return;
    run("sinh-lai-tat", async () => {
      const result = await api<SoatScenario>(
        `/behavior-authoring/suites/${suite.id}/scenarios/recapture-all`, { method: "POST" });
      setSoatScenario(result);
      await refresh(suite.id);
      if (result.total === 0) { setNotice("Bộ chấm chưa có scenario nào đang bật."); return; }
      if (result.failed.length === 0) {
        setNotice(`Đã sinh lại ${result.total}/${result.total} scenario trên Golden hiện tại. `
          + `Hãy chạy thử trên Golden trước khi publish.`);
        return;
      }
      throw new Error(`Đã sinh lại ${result.total - result.failed.length}/${result.total} scenario. `
        + `Không xong: ${result.failed.join(", ")} — lý do in ngay trên thẻ từng luồng.`);
    });
  };

  const openCodePreview = () => suite && run("code-preview", async () => {
    const result = await api<CodePreview>(`/behavior-authoring/suites/${suite.id}/code-preview`);
    setCodePreview(result);
    setPreviewFileName(result.files[0]?.name || "");
  });

  const moChiaDiem = (item: JsonMap) => {
    const id = String(item.id || "");
    if (chiaDiemId === id) { setChiaDiemId(""); return; }
    setChiaDiemId(id);
    setChiaDiemHam(Number(item.weight ?? 1));
    const bang: Record<string, DiemNhap> = {};
    const cha: Record<string, string> = {};
    const tatCa = Array.isArray(item.checkpoints) ? item.checkpoints as JsonMap[] : [];
    tatCa.forEach((c) => {
      bang[String(c.id)] = Number(c.weight ?? 1);
      cha[String(c.id)] = String(c.requires || "");
    });
    // Quy đổi trọng số cũ thành điểm thực; điểm 0 không tham gia nhận phần dư.
    const phan = phanBoDiem(Number(item.weight ?? 1), tatCa.map((c) => Number(c.weight ?? 1)));
    tatCa.forEach((c, i) => { bang[String(c.id)] = phan[i]; });
    setChiaDiemChot(bang);
    setChiaDiemCha(cha);
  };

  const luuChiaDiem = (item: JsonMap) => {
    if (!suite) return;
    const chots = Array.isArray(item.checkpoints) ? item.checkpoints as JsonMap[] : [];
    let weights: number[];
    let score: number;
    try {
      weights = kiemTraChiaDiem(chiaDiemHam, chots.map((c) => chiaDiemChot[String(c.id)] ?? Number(c.weight ?? 1)),
        100 - diemDaCho + Number(item.weight ?? 1));
      score = docDiem(chiaDiemHam, "Hàm test", true);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Điểm chưa hợp lệ.");
      return;
    }
    const chotDoi = chots.some((c, index) => Number(c.weight ?? 1) !== weights[index]
      || String(c.requires || "") !== (chiaDiemCha[String(c.id)] ?? ""));
    const hamDoi = Number(item.weight ?? 1) !== score;
    if (!chotDoi && !hamDoi) { setChiaDiemId(""); return; }
    run("chia-diem", async () => {
      const body: JsonMap = {};
      if (hamDoi) body.weight = score;
      if (chotDoi) {
        // Gui lai nguyen danh sach checkpoint voi weight moi — moi truong khac giu nguyen
        // (requires, expect, so do chuan...). Doi checkpoints la oracle cu het hieu luc,
        // backend tu chay lai capture.
        body.checkpoints = chots.map((c, index) => {
          const moi: JsonMap = { ...c, weight: weights[index] };
          const cha = chiaDiemCha[String(c.id)] ?? "";
          if (cha) moi.requires = cha; else delete moi.requires;
          return moi;
        });
      }
      await api(`/behavior-authoring/scenarios/${String(item.id)}`, { method: "PUT", body: JSON.stringify(body) });
      setChiaDiemId("");
      setNotice(chotDoi
        ? "Đã chia lại điểm và capture lại oracle. Hãy chạy thử trên Golden trước khi publish."
        : "Đã đổi trọng số hàm.");
      await refresh(suite.id);
    });
  };

  const openScenarioEditor = (item: JsonMap) => {
    if (!suite || !item.id || recording) return;
    if (!previewUrl) {
      setError("Chưa mở Golden. Bấm “Build & mở Golden” trong phần Thao tác trên Golden App trước khi sửa thao tác.");
      return;
    }
    run(`revise-scenario-${String(item.id)}`, async () => {
      setGroupCode(String(item.group_code || ""));
      setScenarioName(String(item.name || item.scenario_code || ""));
      setScenarioWeight(Number(item.weight || 1));
      // Đưa Golden về màn đầu với dữ liệu gốc — cùng lý lẽ với lúc bắt đầu record: máy chấm
      // replay hàm này TỪ ĐẦU, nên thao tác thêm vào phải nối tiếp đúng trạng thái đó.
      await napLaiGolden();
      // KHÔNG nạp lại khung màn của scenario cũ nữa: khung nay cố định 412×838. Scenario
      // nào trót ghi ở cỡ khác thì sinh lại testcase sẽ ghi đè bằng đúng khung chuẩn, và
      // đó chính là điều ta muốn — mỗi bộ đề chỉ có một bố cục để đối chiếu.
      const created = await api<Recording>(`/behavior-authoring/scenarios/${String(item.id)}/revision-recording`, { method: "POST" });
      resetRecorderTransport();
      activeRecordingId.current = created.id;
      acceptsRecorderEvents.current = true;
      setEditingScenarioId(String(item.id));
      setRecording(created);
      await refresh(suite.id);
      setNotice("Đã nạp các action/checkpoint cũ vào khung record. Có thể thao tác thêm trên Golden App, thêm checkpoint hoặc xóa bước rồi sinh lại testcase.");
      window.setTimeout(() => authoringPanel.current?.scrollIntoView({ behavior: "smooth", block: "start" }), 50);
    });
  };

  const cancelActiveRecording = () => {
    const recordingId = activeRecordingId.current;
    if (!suite || !recordingId || !recording) return;
    if (!window.confirm(editingScenarioId
      ? "Hủy chỉnh sửa? Scenario cũ vẫn được giữ nguyên."
      : "Hủy phiên record hiện tại?")) return;
    acceptsRecorderEvents.current = false;
    activeRecordingId.current = null;
    run("record-cancel", async () => {
      // Đợi request đã gửi xong để không còn appendEvent chạy đua với DELETE.
      await recorderEventQueue.current;
      await api(`/behavior-authoring/recordings/${recordingId}`, { method: "DELETE" });
      resetRecorderTransport();
      setRecording(null);
      setEditingScenarioId(null);
      await refresh(suite.id);
      setNotice("Đã hủy phiên soạn; scenario đã publish trước đó không bị thay đổi.");
    });
  };

  const deleteScenario = (item: JsonMap) => {
    if (!suite || !item.id) return;
    const scenarioName = String(item.name || item.scenario_code || "scenario");
    if (!window.confirm(`Xóa scenario “${scenarioName}”, oracle và bản record nguồn của nó?`)) return;
    const scenarioId = String(item.id);
    run(`delete-scenario-${scenarioId}`, async () => {
      await api(`/behavior-authoring/scenarios/${scenarioId}`, { method: "DELETE" });
      await refresh(suite.id);
      setNotice(`Đã xóa scenario ${scenarioName}.`);
    });
  };

  useEffect(() => {
    const receive = (event: MessageEvent) => {
      if (event.source !== goldenFrame.current?.contentWindow) return;
      if (runtimeOrigin && event.origin !== runtimeOrigin) return;
      if (!event.data || typeof event.data !== "object") return;
      if (event.data.type === "GOLDEN_RECORDER_READY") {
        setRecorderReady(true);
        const cho = choGoldenSanSang.current;
        choGoldenSanSang.current = null;
        if (cho) cho();
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_FLUSHED") {
        const requestId = event.data.payload?.request_id;
        if (typeof requestId !== "string") return;
        const waiter = flushWaiters.current.get(requestId);
        if (!waiter) return;
        window.clearTimeout(waiter.timer);
        flushWaiters.current.delete(requestId);
        waiter.resolve();
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_RECTS") {
        const cho = respChoDo.current;
        respChoDo.current = null;
        if (cho) cho(event.data.payload as unknown as AnhChupKhung);
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_IDENTIFIERS") {
        const ds = Array.isArray(event.data.payload?.identifiers) ? event.data.payload.identifiers : [];
        setDinhDanhTrenMan(ds.map((x: unknown) => String(x)).filter(Boolean));
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_INVENTORY") {
        const items = Array.isArray(event.data.payload?.items) ? event.data.payload.items : [];
        setUiInventory(items
          .filter((it: JsonMap) => typeof it?.attribute === "string" && typeof it?.attributeValue === "string")
          .map((it: JsonMap) => ({
            attribute: String(it.attribute), value: String(it.attributeValue), role: String(it.role || ""),
                // Định danh recorder đọc được, chỉ để HIỆN. Không đưa vào target checkpoint: tiêu
                // chí "màn hình có X" phải tiếp tục kiểm nội dung bằng nhãn/chữ (quyết định Q2).
                identifier: typeof it.identifier === "string" ? it.identifier : "",
                // Số thành phần mang cùng khoá này. Nút Xóa của mọi dòng danh sách gộp thành
                // MỘT dòng ở đây; máy chấm biết chấm theo nhóm (mỗi dòng một cái, vị trí đo
                // tương đối trong dòng) nên tick một lần là đủ cho cả sáu.
                count: Number(it.count || 1),
                // label/hint là thành phần ngữ nghĩa thật (nút, ô nhập) → tick sẵn. Chữ trần có
            // thể là DỮ LIỆU đang hiển thị chứ không phải khung màn hình — để giảng viên tự cân nhắc.
            checked: it.attribute === "semanticId" || it.attribute === "label" || it.attribute === "hint",
          })));
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_WARNING") {
        const message = event.data.payload?.message;
        if (typeof message === "string") setError(message);
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_ROUTE") {
        const uri = event.data.payload?.uri;
        if (typeof uri === "string" && uri) {
          setCurrentRoute(uri);
          setRouteExpected(uri);
        }
        return;
      }
      if (!acceptsRecorderEvents.current || !activeRecordingId.current || event.data.type !== "GOLDEN_RECORDER_EVENT") return;
      if (!event.data.payload || typeof event.data.payload !== "object" || Array.isArray(event.data.payload)) return;
      appendAction(event.data.payload as JsonMap);
    };
    window.addEventListener("message", receive);
    return () => window.removeEventListener("message", receive);
    // appendAction intentionally reads the latest recording/locator state. Rebinding the
    // window listener for every keystroke would create a short interval with no recorder.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recording, runtimeOrigin]);

  return (
    <SidebarLayout activePath="/teacher/archive" title="Quản lý bộ chấm Golden" contentClassName="!max-w-none">
      <div className="mx-auto max-w-[1500px] space-y-5 p-6 text-slate-800 dark:text-slate-100">
        {mounted && suite && createPortal(
          <div ref={bangDiemRef} style={{ top: bangDiemY ?? "33vh" }} className={`fixed right-0 z-40 flex items-start transition-transform ${thuGon ? "translate-x-[13.5rem]" : ""}`}>
            <button onPointerDown={batDauKeoBangDiem} onPointerMove={diChuyenBangDiem} onPointerUp={() => { keoBangDiem.current = null; }} onPointerCancel={() => { keoBangDiem.current = null; }} onClick={() => { if (chanBamBangDiem.current) { chanBamBangDiem.current = false; return; } setThuGon(!thuGon); }} onKeyDown={(event) => { if (event.key === "ArrowUp" || event.key === "ArrowDown") { event.preventDefault(); setBangDiemY(gioiHanBangDiemY((bangDiemY ?? window.innerHeight / 3) + (event.key === "ArrowUp" ? -16 : 16))); } }} aria-label={thuGon ? "Mở bảng điểm" : "Thu gọn bảng điểm"} aria-expanded={!thuGon} title="Bấm để mở/thu gọn; kéo lên xuống để đổi vị trí" className="mt-2 touch-none select-none rounded-l-lg border border-r-0 border-slate-300 bg-white px-1.5 py-3 text-slate-600 shadow hover:bg-slate-50 active:cursor-grabbing dark:border-slate-600 dark:bg-slate-800 dark:text-slate-200">{thuGon ? "◀" : "▶"}</button>
            <div className="flex max-h-[calc(100dvh-16px)] flex-col overflow-hidden rounded-l-xl border border-slate-300 bg-white p-3 shadow-xl dark:border-slate-600 dark:bg-slate-900" style={{ width: "13.5rem" }}>
              <p className="text-xs font-bold uppercase tracking-widest text-slate-500">Ngân sách điểm</p>
              <p className={`mt-1 text-2xl font-bold ${diemDaCho > 100 ? "text-rose-600" : diemDaCho === 100 ? "text-emerald-600" : "text-slate-800 dark:text-slate-100"}`}>{diemDaCho}<span className="text-sm font-medium text-slate-400"> / 100</span></p>
              {diemDaCho < 100 && <p className="text-xs text-amber-600">còn {diemNganSachConLai}đ chưa phân bổ</p>}
              {diemDaCho > 100 && <p className="text-xs font-bold text-rose-600">VƯỢT NGÂN SÁCH {lamTron(diemDaCho - 100)}đ</p>}
              <div className="mt-2 min-h-0 max-h-48 space-y-1 overflow-auto border-t border-slate-200 pt-2 text-xs dark:border-slate-700">
                {diemTinh > 0 && <div className="flex justify-between"><span className="text-slate-500">Luật tĩnh</span><b>{diemTinh}đ</b></div>}
                {diemTungHam.map((x) => <div key={x.code} className="flex justify-between gap-2"><span className="truncate text-slate-500">{x.code}</span><b className="shrink-0">{lamTron(x.diem)}đ</b></div>)}
              </div>
            </div>
          </div>,
          document.body,
        )}
        {mounted && (error || notice) && createPortal(
          <div className={`fixed bottom-6 left-1/2 z-50 flex max-w-[min(92vw,44rem)] -translate-x-1/2 items-start gap-3 rounded-xl border px-4 py-3 shadow-xl ${error ? "border-rose-300 bg-rose-50 text-rose-700 dark:border-rose-700 dark:bg-rose-950 dark:text-rose-200" : "border-emerald-300 bg-emerald-50 text-emerald-700 dark:border-emerald-700 dark:bg-emerald-950 dark:text-emerald-200"}`} role="alert">
            {error ? <XCircle size={20} className="mt-0.5 shrink-0" /> : <CheckCircle2 size={20} className="mt-0.5 shrink-0" />}
            <div className="min-w-0 font-medium">{error || notice}
              {error && missingGoldenPackages.length > 0 && <a className="mt-2 block underline underline-offset-2" href={`/teacher/libraries?package_specs=${encodeURIComponent(JSON.stringify(missingGoldenPackages))}`}>Mở Thư viện chấm để thêm {missingGoldenPackages.map((item) => item.name).join(", ")}</a>}
            </div>
            <button onClick={() => { setError(""); setNotice(""); setMissingGoldenPackages([]); }} aria-label="Đóng thông báo" className="ml-1 shrink-0 rounded-md p-1 hover:bg-black/5 dark:hover:bg-white/10"><XCircle size={15} /></button>
          </div>,
          document.body,
        )}

        {suite ? (
          <>
            <button type="button" onClick={closeSuite} disabled={Boolean(busy)} className="inline-flex w-fit items-center gap-2 rounded-xl border border-indigo-300 bg-indigo-50 px-4 py-2.5 text-sm font-bold text-indigo-700 shadow-sm transition hover:border-indigo-400 hover:bg-indigo-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500 disabled:cursor-not-allowed disabled:opacity-50 dark:border-indigo-700 dark:bg-indigo-950/50 dark:text-indigo-300 dark:hover:bg-indigo-950">
              <ArrowLeft size={18} /> Quay lại danh sách bộ chấm
            </button>
            <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
              <div className="flex flex-wrap items-center justify-between gap-4">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-3">
                    <h2 className="break-words text-xl font-bold">{suite.name}</h2>
                    <SuiteStatusBadge status={suite.status} />
                  </div>
                  <p className="mt-2 break-all text-sm text-slate-500">Mã đề <span className="font-mono font-medium text-slate-700 dark:text-slate-300">{suite.exam_id || "Chưa gắn"}</span></p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <button onClick={() => deleteSuite(suite)} disabled={Boolean(busy)} className="inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium text-slate-500 hover:bg-rose-50 hover:text-rose-600 disabled:opacity-50 dark:hover:bg-rose-950/30 dark:hover:text-rose-300"><Trash2 size={16} /> Xóa bộ chấm</button>
                </div>
              </div>
            </section>
          </>
        ) : (
          <>
            <div className="grid items-start gap-5 xl:grid-cols-[320px_minmax(0,1fr)]">
              <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
                <div className="mb-5 flex items-center gap-3">
                  <span className="rounded-xl bg-indigo-50 p-2.5 text-indigo-600 dark:bg-indigo-950 dark:text-indigo-300"><Plus size={20} /></span>
                  <h2 className="text-lg font-bold">Tạo bộ chấm</h2>
                </div>
                <form onSubmit={(event) => { event.preventDefault(); void createSuite(); }} className="space-y-4">
                  <div>
                    <label htmlFor="golden-exam-id" className="mb-1.5 block text-sm font-semibold">Mã đề</label>
                    <input id="golden-exam-id" value={examId} onChange={(e) => setExamId(e.target.value)} required disabled={Boolean(busy)} placeholder="Ví dụ: PE_PRM393_FA26" className="w-full rounded-xl border border-slate-300 bg-transparent px-3 py-2.5 text-sm outline-none focus:border-indigo-500 disabled:opacity-50 dark:border-slate-700" />
                  </div>
                  <div>
                    <label htmlFor="golden-suite-name" className="mb-1.5 block text-sm font-semibold">Tên bộ chấm</label>
                    <input id="golden-suite-name" value={name} onChange={(e) => setName(e.target.value)} required disabled={Boolean(busy)} placeholder="Ví dụ: Quản lý chi tiêu cá nhân" className="w-full rounded-xl border border-slate-300 bg-transparent px-3 py-2.5 text-sm outline-none focus:border-indigo-500 disabled:opacity-50 dark:border-slate-700" />
                  </div>
                  <button type="submit" disabled={Boolean(busy) || !examId.trim() || !name.trim()} className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 disabled:cursor-not-allowed disabled:opacity-50">{busy === "create" ? <Loader2 className="animate-spin" size={17} /> : <Plus size={17} />} Tạo bộ chấm mới</button>
                </form>
              </section>
              <section className="@container min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-900">
                <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-700">
                  <h2 className="text-lg font-bold">Bộ chấm của bạn</h2>
                  <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-500 dark:bg-slate-800">{availableSuites.length} bộ chấm</span>
                </div>
                <div className="p-3">{availableSuites.length ? <div role="region" aria-label="Danh sách bộ chấm Golden" tabIndex={0} onPointerDownCapture={batDauKeoDanhSach} onPointerMoveCapture={cuonKeoDanhSach} onPointerUpCapture={() => { if (keoDanhSach.current && !keoDanhSach.current.moved) keoDanhSach.current = null; }} onPointerCancel={() => { keoDanhSach.current = null; }} onClickCapture={(event) => { if (keoDanhSach.current?.moved && event.detail > 0) { event.preventDefault(); event.stopPropagation(); keoDanhSach.current = null; } }} className="grid max-h-[312px] auto-rows-[72px] grid-cols-1 gap-2 overflow-y-auto overscroll-contain outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-500 @min-[520px]:grid-cols-2">
                  {availableSuites.map((item) => (
                    <div key={item.id} className="flex min-w-0 select-none items-center gap-2 rounded-xl border border-slate-200 px-3 transition hover:border-indigo-300 hover:bg-indigo-50/40 dark:border-slate-700 dark:hover:border-indigo-700 dark:hover:bg-indigo-950/20">
                      <button onClick={() => void run(`open-${item.id}`, () => openSuite(item))} disabled={Boolean(busy)} className="group min-w-0 flex-1 py-2 text-left disabled:opacity-50">
                        <div className="flex min-w-0 items-center gap-2"><p className="min-w-0 truncate text-sm font-semibold group-hover:text-indigo-600 dark:group-hover:text-indigo-300" title={item.name}>{item.name}</p><SuiteStatusBadge status={item.status} /></div>
                        <p className="mt-1 truncate font-mono text-[11px] text-slate-500" title={item.exam_id || "Chưa gắn mã đề"}>{item.exam_id || "Chưa gắn mã đề"}</p>
                      </button>
                      <div className="flex shrink-0 items-center gap-1">
                        <button onClick={() => cloneSuiteExam(item)} disabled={Boolean(busy) || !item.exam_id} aria-label={`Nhân bản đề bài ${item.exam_id || item.name}`} title={item.exam_id ? "Nhân bản đề bài sang mã đề mới" : "Bộ này chưa gắn mã đề"} className="rounded-lg p-1.5 text-slate-400 hover:bg-indigo-50 hover:text-indigo-600 disabled:opacity-40 dark:hover:bg-indigo-950">{busy === `clone-exam-${item.id}` ? <Loader2 className="animate-spin" size={14} /> : <Copy size={14} />}</button>
                        <button onClick={() => deleteSuite(item)} disabled={Boolean(busy)} aria-label={`Xóa bộ chấm ${item.name}`} title="Xóa bộ chấm" className="rounded-lg p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600 disabled:opacity-40 dark:hover:bg-rose-950"><Trash2 size={14} /></button>
                      </div>
                    </div>
                  ))}
                </div> : <div className="px-6 py-12 text-center"><FileArchive size={28} className="mx-auto text-slate-300" /><h3 className="mt-3 font-semibold">Chưa có bộ chấm</h3><p className="mt-1 text-sm text-slate-500">Tạo bộ đầu tiên để soạn các luồng chấm từ Golden của bạn.</p></div>}</div>
              </section>
            </div>
            <div className="grid items-start gap-5 lg:grid-cols-2">
              <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
                <p className="text-sm font-bold">Giao bộ chấm cho người chấm</p>
                <p className="mt-2 text-sm leading-relaxed text-slate-500">
                  Tải bộ testcase đã xuất bản để nạp vào bản người chấm. Đề cần đạt kiểm đồng bộ khung phát trước khi bàn giao.
                </p>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <select aria-label="Đề cần xuất gói bàn giao" value={deGiao} onChange={(e) => setDeGiao(e.target.value)} className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-sm dark:border-slate-700">
                    {deDaPublish.length === 0 && <option value="">Chưa có đề nào publish testcase</option>}
                    {deDaPublish.map((d) => (
                      <option key={String(d.examId)} value={String(d.examId)}>
                        {String(d.examId)}
                      </option>
                    ))}
                  </select>
                  <button onClick={xuatGoiBanGiao} disabled={Boolean(busy) || !deGiao}
                    className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm font-bold text-white disabled:opacity-50">
                    {busy === "xuat-goi" ? <Loader2 className="animate-spin" size={16} /> : <Download size={16} />}
                    Tải gói bàn giao + khung phát
                  </button>
                </div>
              </div>

              {/* Đặt ở MÀN NÀY chứ không phải trong màn soạn: file cần TRƯỚC khi viết Golden, mà
                  màn soạn chỉ mở được sau khi đã có Golden để tải lên. Trước nằm trong ô "Tạo bộ
                  chấm"; tách ra thành thẻ riêng vì nó không phải một bước của việc tạo bộ. */}
              <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
                <div className="flex items-center gap-2 text-slate-700 dark:text-slate-200">
                  <Code2 size={16} className="shrink-0 text-slate-400" />
                  <h3 className="text-sm font-bold">Lần đầu dựng Golden?</h3>
                </div>
                <p className="mt-2 text-sm leading-relaxed text-slate-500">
                  Đây là file identifier mẫu đi kèm hướng dẫn. Bạn có thể tải về, copy vào Golden và sửa theo hướng dẫn để có thể tạo identifier đúng cách.
                </p>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <a
                    href={`${API_BASE}/exam-setup/mau/dinh-danh`}
                    className="inline-flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 transition hover:border-indigo-400 hover:text-indigo-700 dark:border-slate-600 dark:text-slate-200 dark:hover:border-indigo-600 dark:hover:text-indigo-300"
                  >
                    <Download size={16} /> Tải dinh_danh.dart mẫu
                  </a>
                </div>
              </div>
            </div>
          </>
        )}

        {suite && <>
          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div><h2 className="mt-1 text-lg font-bold">Dữ liệu đầu vào</h2></div>
              <span className={`text-xs font-semibold ${recordingInputsReady ? "text-emerald-600" : "text-slate-500"}`}>{ARTIFACTS.filter((item) => item.owner === "teacher" && activeByType[item.type]).length}/2 file đã tải</span>
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              {ARTIFACTS.filter((item) => item.owner === "teacher").map((item) => {
                const current = activeByType[item.type]; const Icon = item.icon; const uploading = busy === `upload-${item.type}`;
                return <div key={item.type} className="min-w-0 rounded-xl border border-slate-200 px-3 py-2.5 dark:border-slate-700">
                  <div className="flex items-center gap-2">
                    <span className="rounded-lg bg-indigo-50 p-1.5 text-indigo-600 dark:bg-indigo-950 dark:text-indigo-300"><Icon size={17} /></span>
                    <div className="min-w-0 flex-1"><h3 className="text-sm font-semibold" title={item.hint}>{item.title.replace(/^\d+\.\s*/, "")}</h3></div>
                    {current ? <CheckCircle2 size={18} className="shrink-0 text-emerald-500" /> : <Circle size={18} className="shrink-0 text-slate-300" />}
                  </div>
                  <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                    <div className="min-w-0 flex-1">{current ? <><p className="truncate text-sm font-medium" title={current.file_name}>{current.file_name}</p><p className="mt-1 text-xs text-slate-500">Bản {current.version} · {bytes(current.size_bytes)}</p></> : <p className="text-sm text-slate-400">Chưa tải file</p>}</div>
                    <label className={`inline-flex shrink-0 items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-xs font-semibold dark:border-slate-700 ${busy ? "cursor-not-allowed opacity-50" : "cursor-pointer hover:border-indigo-400 hover:text-indigo-600"}`}>{uploading ? <Loader2 size={15} className="animate-spin" /> : <UploadCloud size={15} />} {current ? "Thay file" : "Tải file"}<input aria-label={`Tải ${item.title.replace(/^\d+\.\s*/, "")}`} type="file" accept={item.accept} disabled={Boolean(busy)} className="hidden" onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ""; if (file) uploadArtifact(item.type, file); }} /></label>
                  </div>
                  {item.type === "GOLDEN_SOLUTION" && current && <p className="mt-2 text-xs text-slate-500">Database: {databaseName ? <code className="break-all font-semibold text-slate-700 dark:text-slate-300">{databaseName}</code> : <span className="text-amber-600">Chưa xác định — hãy tải lại ZIP Golden</span>}<span className="ml-2 text-slate-400">· Tự động nhận diện</span></p>}
                </div>;
              })}
            </div>
            <details className="group mt-4 border-t border-slate-100 pt-4 dark:border-slate-800">
              <summary className="flex cursor-pointer list-none items-center gap-2 text-sm font-medium text-slate-500 [&::-webkit-details-marker]:hidden"><ChevronDown size={15} className="-rotate-90 transition-transform group-open:rotate-0" /> Thành phần hệ thống tự sinh<span className="ml-auto text-xs">{ARTIFACTS.filter((item) => item.owner === "system" && activeByType[item.type]).length}/4 đã có</span></summary>
              <div className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
                {ARTIFACTS.filter((item) => item.owner === "system").map((item) => {
                  const current = activeByType[item.type]; const Icon = item.icon;
                  return <div key={item.type} className="min-w-0 rounded-lg bg-slate-50 p-3 dark:bg-slate-800/50"><div className="flex items-center gap-2"><Icon size={15} className="shrink-0 text-slate-400" /><span className="flex-1 text-xs font-semibold">{item.title.replace(/^\d+\.\s*/, "")}</span>{current && <CheckCircle2 size={15} className="shrink-0 text-emerald-500" />}</div><p className="mt-2 text-xs leading-relaxed text-slate-500">{current ? <span className="block truncate" title={current.file_name}>{current.file_name} · bản {current.version}</span> : item.hint}</p></div>;
                })}
              </div>
            </details>
          </section>



          <section className="grid min-w-0 gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)]">
            <div className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
                <h1 className="text-xl font-bold">Thao tác trên Golden App</h1>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <button onClick={deployGoldenRuntime} disabled={!activeByType.GOLDEN_SOLUTION || Boolean(busy)} className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-bold text-white disabled:opacity-40">{busy === "runtime-deploy" ? <Loader2 size={16} className="animate-spin" /> : <MonitorPlay size={16} />} Build & mở Golden</button>
                <span className={`rounded-full px-3 py-1 text-xs font-bold ${recorderReady ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300" : "bg-slate-100 text-slate-500 dark:bg-slate-800"}`}>{recorderReady ? "Recorder đã kết nối" : runtimeStatus?.status || "Chưa build"}</span>
                {recording && recorderReady && <button onClick={captureUiSnapshot} title="Liệt kê thành phần của màn hình đang mở trong Golden App để tick thành tiêu chí giao diện" className="inline-flex items-center gap-2 rounded-lg border border-emerald-400 px-3 py-2 text-xs font-bold text-emerald-700 dark:text-emerald-300"><Check size={15} /> Quét thành phần UI</button>}
              </div>
              {uiInventory && (
                <div className="mt-3 rounded-xl border border-emerald-300 bg-emerald-50/40 p-3 dark:border-emerald-800 dark:bg-emerald-950/20">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-bold">Tiêu chí giao diện — tick thành phần được tính điểm</span>
                    <label className="flex items-center gap-1 text-sm">Điểm hiện diện
                      <input type="text" inputMode="decimal" value={uiGroupWeight} onChange={(e) => setUiGroupWeight(e.target.value)} className="w-20 rounded-lg border border-slate-300 bg-transparent px-2 py-1 text-sm dark:border-slate-600" />
                    </label>
                    <input value={uiScreenName} onChange={(e) => setUiScreenName(e.target.value)} placeholder="Tên màn (vd: Màn danh sách)" className="rounded-lg border border-slate-300 px-2 py-1 text-sm dark:border-slate-600 dark:bg-slate-800" />
                    <span className={`text-xs font-bold ${vuotMucUi ? "text-rose-600" : "text-slate-500"}`}>Đã chia {tongCacMuc}/{diemUiConLai}đ của hàm{vuotMucUi ? " — VƯỢT, hạ bớt mới lưu được" : ""}</span>
                    <button onClick={saveUiCriteria} disabled={Boolean(busy) || !uiInventory.some((it) => it.checked)} className="rounded-lg bg-emerald-600 px-3 py-1.5 text-sm font-bold text-white disabled:opacity-40">
                      Lưu {uiInventory.filter((it) => it.checked).length * (1 + (viTriOn ? 1 : 0) + (mauOn ? 1 : 0))} tiêu chí
                    </button>
                    <button onClick={() => setUiInventory(null)} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-bold text-slate-600 dark:border-slate-600 dark:text-slate-300">Đóng</button>
                  </div>
                  <label className="mt-2 flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border border-dashed border-emerald-400 px-2 py-1.5 text-sm dark:border-emerald-700">
                    <input type="checkbox" checked={viTriOn} onChange={() => setViTriOn((v) => !v)} />
                    <span className="font-bold">Chấm vị trí từng thành phần</span>
                    <span className="text-[11px] text-slate-500">(so tâm thành phần với Golden; sai số tính theo % chiều rộng/cao màn)</span>
                    <span className="ml-auto flex items-center gap-1 text-xs">
                      <input type="text" inputMode="decimal" value={viTriWeight} onChange={(e) => setViTriWeight(e.target.value)} className="w-16 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" /> điểm ·
                      sai số <input type="number" min={0.5} max={50} step={0.5} value={viTriSaiSo} onChange={(e) => setViTriSaiSo(Number(e.target.value))} className="w-14 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" />%
                    </span>
                  </label>
                  <label className="mt-1 flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border border-dashed border-emerald-400 px-2 py-1.5 text-sm dark:border-emerald-700">
                    <input type="checkbox" checked={mauOn} onChange={() => setMauOn((v) => !v)} />
                    <span className="font-bold">Chấm màu từng thành phần</span>
                    <span className="text-[11px] text-slate-500">(so màu chính với Golden; sai số tính theo % của 255 trên từng kênh R/G/B)</span>
                    <span className="ml-auto flex items-center gap-1 text-xs">
                      <input type="text" inputMode="decimal" value={mauWeight} onChange={(e) => setMauWeight(e.target.value)} className="w-16 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" /> điểm ·
                      sai số <input type="number" min={0.5} max={50} step={0.5} value={mauSaiSo} onChange={(e) => setMauSaiSo(Number(e.target.value))} className="w-14 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" />%
                    </span>
                  </label>
                  <div className="mt-2 grid max-h-56 gap-1 overflow-auto pr-1">
                    {uiInventory.map((it, i) => (
                      <label key={`${it.attribute}-${it.value}`} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1 text-sm hover:bg-emerald-100/60 dark:hover:bg-emerald-900/30">
                        <input type="checkbox" checked={it.checked} onChange={() => setUiInventory((prev) => prev ? prev.map((x, j) => (j === i ? { ...x, checked: !x.checked } : x)) : prev)} />
                        <span className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-bold ${it.attribute === "label" ? "bg-indigo-100 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300" : it.attribute === "hint" ? "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300" : "bg-slate-100 text-slate-500 dark:bg-slate-800"}`}>{it.attribute}</span>
                        <span className="truncate">{it.value}</span>
                        {it.count > 1 && <span className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold text-amber-700 dark:bg-amber-950 dark:text-amber-300" title="Thành phần này lặp lại trên màn hình, thường là mỗi dòng danh sách một cái. Tick một lần là chấm cả nhóm: mỗi dòng phải có đúng một, vị trí đo tương đối trong dòng, màu đo trên mọi thể hiện.">×{it.count} · lặp</span>}
                        {it.identifier && <span className="shrink-0 rounded bg-teal-100 px-1.5 py-0.5 font-mono text-[10px] font-bold text-teal-700 dark:bg-teal-950 dark:text-teal-300" title="Định danh Semantics(identifier:) của thành phần. Checkpoint tạo từ đây vẫn kiểm nội dung bằng nhãn/chữ.">{it.identifier}</span>}
                        {it.role && <span className="ml-auto text-[10px] text-slate-400">{it.role}</span>}
                      </label>
                    ))}
                  </div>
                </div>
              )}
              {previewUrl ? <div className="mt-4 w-full overflow-auto rounded-xl border border-slate-300 bg-slate-100 p-3 dark:border-slate-700 dark:bg-slate-950">
                <div className="mb-2 flex flex-wrap items-center justify-center gap-3 text-xs">
                  <label className="flex items-center gap-1.5 font-bold text-slate-700 dark:text-slate-200">Ratio
                    <select value={thuNho} onChange={(e) => setThuNho(Number(e.target.value))} disabled={khungDesktop} title={khungDesktop ? "Khung desktop thu cố định cho vừa cột" : undefined} className="rounded border border-slate-400 bg-transparent px-1.5 py-0.5 font-bold text-slate-800 disabled:opacity-40 dark:border-slate-500 dark:text-slate-100">
                      {MUC_THU_NHO.map(([ti, nhan]) => <option key={ti} value={ti}>{nhan}</option>)}
                    </select>
                  </label>
                  <div className="flex overflow-hidden rounded border border-slate-400 font-bold dark:border-slate-500">
                    <button onClick={() => setKhungDesktop(false)} className={`px-2 py-0.5 ${!khungDesktop ? "bg-indigo-600 text-white" : "text-slate-600 dark:text-slate-300"}`}>Điện thoại</button>
                    <button onClick={() => setKhungDesktop(true)} title="Chỉ để xem app co giãn thế nào. Tiêu chí thường vẫn soạn ở khung điện thoại; tiêu chí responsive sinh từ nút Dò responsive ở danh sách luồng." className={`px-2 py-0.5 ${khungDesktop ? "bg-indigo-600 text-white" : "text-slate-600 dark:text-slate-300"}`}>Desktop</button>
                  </div>
                  {khungDesktop && <span className="font-bold text-indigo-600 dark:text-indigo-400">1280×800 — khung chấm responsive</span>}
                </div>
                {/* Hộp ngoài mang cỡ ĐÃ thu nhỏ để chiếm đúng chỗ trên trang; iframe bên trong
                    vẫn là 412×838 thật rồi mới scale, nên app dàn bố cục y như lúc chấm. */}
                <div className="mx-auto overflow-hidden rounded-lg border border-slate-300 bg-white dark:border-slate-700" style={{ width: khungRongXem * tiLeXem, height: khungCaoXem * tiLeXem }}>
                  <iframe ref={goldenFrame} title="Golden App" src={previewUrl} style={{ width: khungRongXem, height: khungCaoXem, border: 0, transform: `scale(${tiLeXem})`, transformOrigin: "top left" }} className="block bg-white" />
                </div>
              </div> :<div className="mt-4 flex h-[300px] flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 text-center dark:border-slate-700"><MonitorPlay size={42} className="text-slate-400" /><p className="mt-3 font-bold">Golden Solution chưa được build để thao tác</p><p className="mt-1 max-w-md text-sm text-slate-500">Upload Golden ZIP rồi bấm “Build & mở Golden”. Hệ thống tự host app và ghi click/nhập liệu bằng semantic locator.</p></div>}
            </div>

            <div ref={authoringPanel} className="min-w-0 scroll-mt-24 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
              <div className="flex items-center justify-between"><div><h1 className="text-xl font-bold">Ghi thao tác & soạn tiêu chí</h1></div>{editingScenarioId && <span className="mr-3 rounded-full bg-indigo-100 px-2.5 py-1 text-xs font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300" title="Đang sửa một scenario đã có. Bước cũ nằm sẵn trong danh sách dưới; bấm Hủy sửa ở hàng nút cuối để thoát.">Đang sửa {scenarioName || "luồng"}</span>}{recording ? <span className={`flex items-center gap-2 text-sm font-bold ${recording.status === "ACTIVE" ? "text-rose-500" : "text-amber-500"}`}><span className={`h-2 w-2 rounded-full ${recording.status === "ACTIVE" ? "animate-pulse bg-rose-500" : "bg-amber-500"}`} /> {recording.status === "ACTIVE" ? "RECORDING" : "Chờ sinh testcase"}</span> : <span className="text-sm text-slate-500">Chưa ghi</span>}</div>
              {iconInventory && iconScenario && (
                <div className="mt-4 rounded-xl border border-teal-300 bg-teal-50/40 p-3 dark:border-teal-800 dark:bg-teal-950/20">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-bold">Icon và ảnh máy chấm thấy ở luồng {iconScenario.ma} — tick thứ được tính điểm</span>
                    <input value={uiScreenName} onChange={(e) => setUiScreenName(e.target.value)} placeholder="Tên màn (vd: Màn danh sách)" className="rounded-lg border border-slate-300 px-2 py-1 text-sm dark:border-slate-600 dark:bg-slate-800" />
                    <button onClick={luuTieuChiIcon} disabled={Boolean(busy) || !iconInventory.some((it) => it.checked)} className="rounded-lg bg-teal-600 px-3 py-1.5 text-sm font-bold text-white disabled:opacity-40">
                      Thêm {iconInventory.filter((it) => it.checked).length * ((iconCoMatOn ? 1 : 0) + (iconViTriOn ? 1 : 0) + (iconMauOn ? 1 : 0))} tiêu chí
                    </button>
                    <button onClick={() => { setIconInventory(null); setIconScenario(null); }} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-bold text-slate-600 dark:border-slate-600 dark:text-slate-300">Đóng</button>
                  </div>
                  <p className="mt-2 text-[11px] text-slate-500">
                    Dành cho nút chỉ có hình và cho ảnh (avatar): chấm đúng hình, đúng chỗ, đúng màu mà KHÔNG bắt sinh viên gắn nhãn ngữ nghĩa chỉ để máy tìm.
                    Bảng này do máy chấm đo trên cây widget lúc capture, không phải quét DOM — nút không nhãn thì DOM web không thấy.
                    Icon lặp ở mỗi dòng danh sách được chấm ở <b>mọi dòng</b>: thiếu một dòng là trượt, còn vị trí đo tương đối trong dòng nên dòng đầu và dòng cuối cùng một chuẩn.
                  </p>
                  <div className="mt-2 grid max-h-56 gap-1 overflow-auto pr-1">
                    {iconInventory.map((it, i) => (
                      <label key={it.loai + it.ten} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1 text-sm hover:bg-teal-100/60 dark:hover:bg-teal-900/30">
                        <input type="checkbox" checked={it.checked} onChange={() => setIconInventory((prev) => prev ? prev.map((x, j) => (j === i ? { ...x, checked: !x.checked } : x)) : prev)} />
                        <span className="rounded bg-teal-100 px-1.5 py-0.5 font-mono text-[10px] font-bold text-teal-700 dark:bg-teal-950 dark:text-teal-300">{it.loai === "icon" ? "icon" : "ảnh"}</span>
                        <span className="truncate font-mono">{it.ten}</span>
                        {it.count > 1 && (
                          <span title="Icon này xuất hiện nhiều lần. Có 'lặp theo dòng' nghĩa là mỗi dòng danh sách đúng một cái — máy sẽ chấm đủ mọi dòng." className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold text-amber-700 dark:bg-amber-950 dark:text-amber-300">
                            ×{it.count}{it.perRow ? " · lặp theo dòng" : ""}
                          </span>
                        )}
                        {it.buttonType && <span className="ml-auto text-[10px] text-slate-400">{it.buttonType}</span>}
                      </label>
                    ))}
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-4 rounded-lg border border-dashed border-teal-400 px-3 py-2 text-sm dark:border-teal-700">
                  <span className="font-bold">Chấm mặt nào:</span>
                  <label className="flex cursor-pointer items-center gap-1"><input type="checkbox" checked={iconCoMatOn} onChange={() => setIconCoMatOn((v) => !v)} /> có mặt</label>
                  <label className="flex cursor-pointer items-center gap-1"><input type="checkbox" checked={iconViTriOn} onChange={() => setIconViTriOn((v) => !v)} /> vị trí</label>
                  <label className="flex cursor-pointer items-center gap-1" title="Với ảnh cho sẵn thì màu không nói lên điều gì: sinh viên không vẽ ra tấm ảnh đó.">
                    <input type="checkbox" checked={iconMauOn} onChange={() => setIconMauOn((v) => !v)} /> màu
                  </label>
                  <span className="text-[11px] text-slate-500">Số điểm mỗi mặt lấy theo các ô đã đặt ở bảng “Quét thành phần UI”.</span>
                </div>
                <p className="mt-2 text-[11px] text-slate-500">Với ảnh cho sẵn, nên để <b>tắt</b> phần màu: sinh viên không vẽ ra tấm ảnh đó nên màu của nó không phản ánh bài làm. Lưu xong hệ thống tự capture lại để đo giá trị chuẩn.</p>
                </div>
              )}
              <div className="mt-4 grid gap-3 sm:grid-cols-3"><div><input value={groupCode} onChange={(e) => setGroupCode(e.target.value)} list="ma-nhom-co-san" title="Gõ mã nhóm mới hoặc chọn một mã đã dùng. Để trống nếu luồng này không thuộc nhóm nào." placeholder="Mã nhóm (vd: FILTER — để trống nếu không nhóm)" className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><datalist id="ma-nhom-co-san">{maNhomCoSan.map((ma) => <option key={ma} value={ma} />)}</datalist></div><input value={scenarioName} onChange={(e) => setScenarioName(e.target.value)} placeholder="Tên luồng (vd: Thêm khoản chi)" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><input type="text" inputMode="decimal" value={scenarioWeight} onChange={(e) => setScenarioWeight(e.target.value)} aria-label="Trọng số scenario" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /></div>
              <div className="mt-2 grid gap-2 sm:grid-cols-2">
                  <div className="rounded-lg border border-blue-800 bg-blue-800 px-3 py-2 text-xs font-bold text-white sm:col-span-2 dark:border-blue-600 dark:bg-blue-730">
  Khung app: {KHUNG_RONG} × {KHUNG_CAO} dp — máy Pixel 7 (màn 412×915 dp, đã trừ 77 dp thanh trạng thái và thanh cử chỉ)
</div>
                  <label className="flex items-center gap-2 text-xs font-semibold text-slate-500 sm:col-span-2"><input type="checkbox" checked={cheDoToi} onChange={() => setCheDoToi((v) => !v)} /> Chấm hàm này ở chế độ tối <span className="font-normal">— engine đặt platformBrightness = dark trước khi boot; bài có darkTheme sẽ tự đổi, giá trị chuẩn màu/kiểu chữ đo ở chế độ tối. Khung Golden bên trên vẫn hiện sáng.</span></label><label className="text-xs font-semibold text-slate-500 sm:col-span-2">Bộ nhớ app đã có sẵn khi mở (SharedPreferences)<textarea value={prefsBanDau} onChange={(e) => setPrefsBanDau(e.target.value)} rows={2} placeholder="che_do_toi=true" className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 font-mono text-xs text-slate-800 dark:border-slate-700 dark:text-slate-100" /><span className="font-normal">Mỗi dòng một cặp <code>khoá=giá trị</code>. Dùng cho luồng kiểu &ldquo;mở app khi người dùng đã bật chế độ tối từ lần trước&rdquo;. Để trống thì app mở với bộ nhớ rỗng.</span></label></div>
              {!recording ? <><button onClick={startRecording} disabled={!recordingInputsReady || Boolean(busy)} className="mt-4 inline-flex items-center gap-2 rounded-xl bg-rose-600 px-4 py-2.5 font-bold text-white disabled:opacity-40"><Radio size={18} /> Bắt đầu record</button>{!recordingInputsReady && <p className="mt-2 text-xs text-amber-600">Cần đủ Database ẩn và Golden Solution.</p>}</> : <>
                <div className="mt-5 rounded-xl border border-slate-200 dark:border-slate-700">
                  <button onClick={() => setMoThemAction((v) => !v)} className="flex w-full items-center gap-2 px-4 py-2.5 text-left">
                    <ChevronDown size={16} className={`shrink-0 text-slate-400 transition-transform ${moThemAction ? "" : "-rotate-90"}`} />
                    <h3 className="font-bold">Thêm action</h3>
                    <span className="text-xs text-slate-500">khai tay</span>
                  </button>
                  {moThemAction && <div className="border-t border-slate-200 px-4 py-3 dark:border-slate-700">
                    <div className="grid gap-2 sm:grid-cols-2">
                        <select value={action} onChange={(e) => setAction(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{ACTIONS.map((item) => <option key={item} value={item}>{item}</option>)}</select>
                        {manualActionNeedsTarget && <select value={locator} onChange={(e) => setLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item}</option>)}</select>}
                        {manualActionNeedsTarget && <input value={locatorValue} onChange={(e) => setLocatorValue(e.target.value)} placeholder="Giá trị nhận diện semantic" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />}
                        {(action === "enter_text" || manualActionNeedsUri) && <input value={inputValue} onChange={(e) => setInputValue(e.target.value)} placeholder={manualActionNeedsUri ? "URI/path, ví dụ /movies/42?tab=cast" : "Dữ liệu nhập"} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />}
                    </div>
                          {action === "drag" && <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-slate-500"><span>Độ dời (dp):</span><label className="flex items-center gap-1">ngang <input type="number" step={10} value={keoX} onChange={(e) => setKeoX(Number(e.target.value))} className="w-20 rounded border border-slate-300 bg-transparent px-2 py-1 dark:border-slate-600" /></label><label className="flex items-center gap-1">dọc <input type="number" step={10} value={keoY} onChange={(e) => setKeoY(Number(e.target.value))} className="w-20 rounded border border-slate-300 bg-transparent px-2 py-1 dark:border-slate-600" /></label></div>}
                    <button onClick={() => appendAction()} className="mt-3 inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm font-bold text-white"><Plus size={16} /> Thêm action</button>
                  </div>}
                </div>
                <div className="mt-3 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
                  <div className="flex items-center justify-between gap-3">
                    <div><h3 className="font-bold">Thêm checkpoint</h3></div>
                    <div className="flex rounded-lg bg-slate-100 p-1 text-xs font-bold dark:bg-slate-800">
                      <button onClick={() => setCheckpointMode("ui")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "ui" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>UI</button>
                      <button onClick={() => setCheckpointMode("database")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "database" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>Database</button>
                      <button onClick={() => setCheckpointMode("route")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "route" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>Route</button>
                      {/* Mở tab là đọc luôn, để danh sách gợi ý đã sẵn trước khi người soạn gõ
                          chữ đầu tiên — không ai nghĩ tới việc phải bấm một nút nạp dữ liệu. */}
                      <button onClick={() => { setCheckpointMode("layout"); layDinhDanhTrenMan(); }} className={`rounded-md px-3 py-1.5 ${checkpointMode === "layout" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>Bố cục</button>
                    </div>
                  </div>
                  {checkpointMode === "ui" ? (
                    <div className="mt-3 space-y-2">
                      <select value={uiCheckpointType} onChange={(e) => setUiCheckpointType(e.target.value as "text" | "component" | "widget_state" | "text_style" | "theme_value" | "preferences" | "no_overflow" | "no_exception")} className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                        <option value="text">Nội dung text xuất hiện / không xuất hiện</option>
                        <option value="component">Thành phần UI và trạng thái semantic</option>
                        <option value="widget_state">Trạng thái thật bên trong widget</option>
                        <option value="text_style">Kiểu chữ của một dòng chữ</option>
                        <option value="theme_value">Bảng chủ đề của app (màu, phông, Material 3)</option>
                        <option value="preferences">Giá trị app đã lưu (SharedPreferences)</option>
                        <option value="no_overflow">Không vỡ bố cục (RenderFlex overflow)</option>
                        <option value="no_exception">Luồng không phát sinh exception</option>
                      </select>
                      {uiCheckpointType === "text" && <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                        <div className="flex min-w-0 gap-1"><input value={checkpointText} onChange={(e) => setCheckpointText(e.target.value)} placeholder="Text phải xuất hiện, cách nhau dấu phẩy" className="min-w-0 flex-1 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><button type="button" onClick={() => setCheckpointText((t) => t + "₫")} title="Chèn ký hiệu đồng (₫) — bàn phím Việt không gõ được U+20AB, mà engine so text tuyệt đối" className="shrink-0 rounded-lg border border-slate-300 px-2.5 text-sm font-bold text-slate-600 hover:border-indigo-400 dark:border-slate-600 dark:text-slate-300">₫</button></div>
                        <div className="flex min-w-0 gap-1"><input value={hiddenCheckpointText} onChange={(e) => setHiddenCheckpointText(e.target.value)} placeholder="Text không được xuất hiện (tùy chọn)" className="min-w-0 flex-1 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><button type="button" onClick={() => setHiddenCheckpointText((t) => t + "₫")} title="Chèn ký hiệu đồng (₫)" className="shrink-0 rounded-lg border border-slate-300 px-2.5 text-sm font-bold text-slate-600 hover:border-indigo-400 dark:border-slate-600 dark:text-slate-300">₫</button></div>
                        <button onClick={appendUiCheckpoint} title="Lưu checkpoint UI" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>}
                      {uiCheckpointType === "component" && <div className="space-y-2">
                        <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
                          <select value={checkpointLocator} onChange={(e) => setCheckpointLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item}</option>)}</select>
                          <input value={checkpointLocatorValue} onChange={(e) => setCheckpointLocatorValue(e.target.value)} placeholder="Giá trị nhận diện thành phần" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                          <select value={checkpointRole} onChange={(e) => setCheckpointRole(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{SEMANTIC_ROLES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
                          <select value={checkpointVisible ? "visible" : "hidden"} onChange={(e) => setCheckpointVisible(e.target.value === "visible")} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700"><option value="visible">Phải hiển thị</option><option value="hidden">Không được hiển thị</option></select>
                        </div>
                        <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-[1.4fr_1fr_1fr_auto]">
                          <input value={checkpointValue} onChange={(e) => setCheckpointValue(e.target.value)} placeholder="Giá trị mong đợi (tùy chọn)" disabled={!checkpointVisible} className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 disabled:opacity-40 dark:border-slate-700" />
                          <select value={checkpointEnabled} onChange={(e) => setCheckpointEnabled(e.target.value)} disabled={!checkpointVisible} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 disabled:opacity-40 dark:border-slate-700"><option value="ignore">Không xét enabled</option><option value="true">Phải được bật</option><option value="false">Phải bị khóa</option></select>
                          <select value={checkpointChecked} onChange={(e) => setCheckpointChecked(e.target.value)} disabled={!checkpointVisible || !["checkbox", "switch", "radio"].includes(checkpointRole)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 disabled:opacity-40 dark:border-slate-700"><option value="ignore">Không xét checked</option><option value="true">Phải được chọn</option><option value="false">Không được chọn</option></select>
                          <button onClick={appendUiCheckpoint} title="Lưu checkpoint semantic" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                        </div>
                        <p className="text-xs text-slate-500">Có thể chỉ kiểm tra sự tồn tại, hoặc kiểm tra thêm đúng loại widget, giá trị, enabled và checked.</p>
                      </div>}
                      {uiCheckpointType === "widget_state" && <div className="space-y-2">
                        <p className="text-xs text-slate-500">Đọc một giá trị thật bên trong widget: công tắc đang bật hay tắt, dải trượt bao nhiêu, ô nhập có dùng bàn phím số không, nút có đúng loại không. Giá trị chuẩn do hệ thống tự đo trên bài Golden lúc sinh testcase — không phải gõ tay.</p>
                        <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-[auto_1.2fr_1.2fr_1.6fr_auto]">
                          <select value={wsLocator} onChange={(e) => setWsLocator(e.target.value)} title="Cách nhận diện điểm neo trên màn hình" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{LOCATOR_LABELS[item] || item}</option>)}</select>
                          <input value={wsLocatorValue} onChange={(e) => setWsLocatorValue(e.target.value)} placeholder="Chữ hoặc nhãn để tìm" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                          <input list="ds-widget" value={wsWidget} onChange={(e) => setWsWidget(e.target.value)} placeholder="Loại widget, ví dụ Slider" title="Tên kiểu widget mang giá trị cần đọc. Hệ thống tìm cả bên trong lẫn bao quanh điểm neo." className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                          <datalist id="ds-widget">{WIDGET_GOI_Y.map((w) => <option key={w} value={w} />)}</datalist>
                          <select value={wsProperty} onChange={(e) => setWsProperty(e.target.value)} className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{THUOC_TINH_WIDGET.map(([ma, nhan]) => <option key={ma} value={ma}>{nhan}</option>)}</select>
                          <button onClick={appendUiCheckpoint} title="Lưu tiêu chí trạng thái" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                        </div>
                      </div>}
                      {uiCheckpointType === "text_style" && <div className="space-y-2">
                        <p className="text-xs text-slate-500">Đo kiểu chữ thật sự được vẽ, nên bắt được cả chữ thừa kế từ theme lẫn chữ đặt style thẳng trên widget. Cỡ chữ và độ đậm so tuyệt đối; màu chữ so theo sai số 20% như mọi tiêu chí màu.</p>
                        <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-[auto_1.6fr_1.4fr_auto]">
                          <select value={tsLocator} onChange={(e) => setTsLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{LOCATOR_LABELS[item] || item}</option>)}</select>
                          <input value={tsLocatorValue} onChange={(e) => setTsLocatorValue(e.target.value)} placeholder="Dòng chữ cần đo" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                          <select value={tsProperty} onChange={(e) => setTsProperty(e.target.value)} className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{MAT_KIEU_CHU.map(([ma, nhan]) => <option key={ma} value={ma}>{nhan}</option>)}</select>
                          {laThuocTinhMau(tsProperty) && <label className="flex items-center gap-1 whitespace-nowrap text-xs text-slate-500">sai số <input type="number" min={0.5} max={50} step={0.5} value={saiSoMau} onChange={(e) => setSaiSoMau(Number(e.target.value))} className="w-14 rounded border border-slate-300 bg-transparent px-1.5 py-1 dark:border-slate-600" />%</label>}
                          <button onClick={appendUiCheckpoint} title="Lưu tiêu chí kiểu chữ" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                        </div>
                      </div>}
                      {uiCheckpointType === "theme_value" && <div className="space-y-2">
                        <p className="text-xs text-slate-500">Đọc thẳng bảng chủ đề trong cây widget — không lấy mẫu pixel, nên đây là đúng giá trị bài làm khai. Không cần chỉ thành phần nào vì chủ đề là của cả app. Sai số chỉ hiện với thuộc tính màu; cỡ chữ, độ đậm và bật/tắt đều so tuyệt đối.</p>
                        <div className="grid gap-2 sm:grid-cols-[1fr_auto_auto]">
                          <select value={tvProperty} onChange={(e) => setTvProperty(e.target.value)} className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{GIA_TRI_CHU_DE.map(([ma, nhan]) => <option key={ma} value={ma}>{nhan}</option>)}</select>
                          {laThuocTinhMau(tvProperty) && <label className="flex items-center gap-1 whitespace-nowrap text-xs text-slate-500">sai số <input type="number" min={0.5} max={50} step={0.5} value={saiSoMau} onChange={(e) => setSaiSoMau(Number(e.target.value))} className="w-14 rounded border border-slate-300 bg-transparent px-1.5 py-1 dark:border-slate-600" />%</label>}
                          <button onClick={appendUiCheckpoint} title="Lưu tiêu chí chủ đề" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                        </div>
                      </div>}
                      {uiCheckpointType === "preferences" && <div className="space-y-2">
                        <p className="text-xs text-slate-500">Đọc kho SharedPreferences sau khi luồng chạy xong, không cần tắt mở lại app. Nhờ vậy phân biệt được bài <b>lưu thật</b> với bài chỉ đổi giao diện bằng setState. Giá trị chuẩn do máy đo trên Golden, không phải gõ tay.</p>
                        <div className="grid gap-2 sm:grid-cols-[1fr_auto_auto]">
                          <input value={prefsKey} onChange={(e) => setPrefsKey(e.target.value)} placeholder='Tên khoá, ví dụ che_do_toi' className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                          <label className="flex items-center gap-1 whitespace-nowrap text-xs text-slate-500" title="Dùng cho tiêu chí kiểu đăng xuất phải xoá khoá ghi nhớ.">
                            <input type="checkbox" checked={prefsAbsent} onChange={() => setPrefsAbsent((v) => !v)} /> phải bị xoá
                          </label>
                          <button onClick={appendUiCheckpoint} title="Lưu tiêu chí giá trị đã lưu" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                        </div>
                      </div>}
                      {uiCheckpointType === "no_overflow" && <div className="flex items-center justify-between gap-3 rounded-lg bg-slate-50 px-3 py-2 text-sm dark:bg-slate-800">
                        <span>Đạt khi không màn nào trong luồng bị tràn (sọc vàng-đen). Từ nay tràn bố cục <b>không còn làm chết cả hàm</b> — nó chỉ trượt đúng tiêu chí này.</span>
                        <button onClick={appendUiCheckpoint} title="Lưu tiêu chí không vỡ bố cục" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>}
                      {uiCheckpointType === "no_exception" && <div className="flex items-center justify-between gap-3 rounded-lg bg-slate-50 px-3 py-2 text-sm dark:bg-slate-800">
                        <span>Checkpoint pass khi luồng chạy tới đây mà ứng dụng không ném exception.</span>
                        <button onClick={appendUiCheckpoint} title="Lưu checkpoint không exception" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>}
                    </div>
                  ) : checkpointMode === "database" ? (
                    <div className="mt-3 space-y-2">
                      <div className="grid gap-2 sm:grid-cols-3">
                        <input value={databaseTable} onChange={(e) => setDatabaseTable(e.target.value)} placeholder="Tên bảng, ví dụ users" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <select value={databaseOperation} onChange={(e) => setDatabaseOperation(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700"><option>READ</option><option>INSERT</option><option>UPDATE</option><option>DELETE</option></select>
                        <input value={databaseCount} onChange={(e) => setDatabaseCount(e.target.value)} placeholder="Tổng row (tùy chọn)" inputMode="numeric" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                      </div>
                      <div className="flex gap-2">
                        <textarea value={databaseRow} onChange={(e) => setDatabaseRow(e.target.value)} rows={2} placeholder={'Row JSON, ví dụ {"uid":"SV01"}'} className="min-w-0 flex-1 rounded-lg border border-slate-300 bg-transparent px-3 py-2 font-mono text-xs dark:border-slate-700" />
                        <button onClick={appendDatabaseCheckpoint} title="Luu checkpoint database" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>
                    </div>
                  ) : checkpointMode === "route" ? (
                    <div className="mt-3 space-y-2">
                      <div className="grid gap-2 sm:grid-cols-[1.6fr_1fr_auto]">
                        <input value={routeExpected} onChange={(e) => setRouteExpected(e.target.value)} placeholder="URI/path mong đợi, ví dụ /movies/42?tab=cast" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <select value={routeCanPop} onChange={(e) => setRouteCanPop(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700"><option value="ignore">Không xét canPop</option><option value="true">Phải back được</option><option value="false">Không được back</option></select>
                        <button onClick={appendRouteCheckpoint} title="Lưu checkpoint route" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>
                      <p className="text-xs text-slate-500">Đang đọc từ Golden: <code>{currentRoute}</code>. Có thể sửa giá trị để kiểm deep link/path/query khác.</p>
                    </div>
                  ) : (
                    <div className="mt-3 space-y-2">
                      <select value={ch7Kind} onChange={(e) => doiLoaiCh7(e.target.value)} className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                        <option value="quan_he">Quan hệ vị trí giữa hai thành phần (trên / dưới / trái / phải…)</option>
                        {CH7_LOAI.map((l) => <option key={l.ma} value={l.ma}>{l.ten}</option>)}
                      </select>
                      <datalist id="golden-layout-targets">{(uiInventory || []).map((item) => <option key={`${item.attribute}:${item.value}`} value={item.value}>{item.attribute}</option>)}</datalist>
                      <datalist id="golden-dinh-danh">{dinhDanhDaThay.map((ma) => <option key={ma} value={ma} />)}</datalist>
                      {/* Hàng này phải nằm NGOÀI nhánh loại. Trước đây nó nằm trong nhánh Ch.7
                          đặc biệt, nên ở "Quan hệ vị trí" — loại mặc định, dùng nhiều nhất — không
                          có nút nào nạp danh sách: ô A/B không xổ gợi ý và cảnh báo gõ sai câm. */}
                      <div className="flex flex-wrap items-center gap-2">
                        <button onClick={layDinhDanhTrenMan} title="Đọc lại định danh của màn Golden đang mở" className="inline-flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-xs font-bold text-slate-600 dark:border-slate-600 dark:text-slate-300">Đọc lại định danh</button>
                        <span className="text-xs text-slate-500">
                          {dinhDanhDaThay.length > 0
                            ? `${dinhDanhDaThay.length} định danh đang có trên màn Golden, bấm vào ô để chọn.`
                            : "Chưa đọc được định danh nào: mở màn cần chấm trong Golden rồi bấm “Đọc lại định danh”."}
                        </span>
                      </div>
                      {!loaiCh7Hien ? (
                        <>
                          <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
                            <select value={layoutFirstLocator} onChange={(e) => setLayoutFirstLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item} A</option>)}</select>
                            <input value={layoutFirstValue} onChange={(e) => setLayoutFirstValue(e.target.value)} placeholder="Thành phần A" list={layoutFirstLocator === "semanticId" ? "golden-dinh-danh" : "golden-layout-targets"} className={`min-w-0 rounded-lg border bg-transparent px-3 py-2 ${layoutFirstLocator === "semanticId" ? vienDinhDanh(layoutFirstValue) : "border-slate-300 dark:border-slate-700"}`} />
                            <select value={layoutSecondLocator} onChange={(e) => setLayoutSecondLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item} B</option>)}</select>
                            <input value={layoutSecondValue} onChange={(e) => setLayoutSecondValue(e.target.value)} placeholder="Thành phần B" list={layoutSecondLocator === "semanticId" ? "golden-dinh-danh" : "golden-layout-targets"} className={`min-w-0 rounded-lg border bg-transparent px-3 py-2 ${layoutSecondLocator === "semanticId" ? vienDinhDanh(layoutSecondValue) : "border-slate-300 dark:border-slate-700"}`} />
                          </div>
                          {((layoutFirstLocator === "semanticId" && dinhDanhLa(layoutFirstValue))
                            || (layoutSecondLocator === "semanticId" && dinhDanhLa(layoutSecondValue)))
                            && <p className="text-xs text-amber-700 dark:text-amber-300">{NHAC_DINH_DANH_LA}</p>}
                          <div className="grid gap-2 sm:grid-cols-[1.5fr_1fr_auto]">
                            <select value={layoutRelation} onChange={(e) => setLayoutRelation(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                              <option value="auto">Tự suy ra quan hệ từ Golden</option><option value="above">A nằm trên B</option><option value="below">A nằm dưới B</option><option value="left_of">A bên trái B</option><option value="right_of">A bên phải B</option><option value="same_row">A và B cùng hàng</option><option value="same_column">A và B cùng cột</option><option value="inside">A nằm trong B</option><option value="contains">A chứa B</option><option value="not_overlap">A và B không chồng lấp</option><option value="overlap">A và B chồng lấp</option><option value="wider_than">A rộng hơn B</option><option value="taller_than">A cao hơn B</option>
                            </select>
                            <label className="flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-700">Sai số %<input type="number" min={0} max={50} step={0.5} value={layoutTolerance} onChange={(e) => setLayoutTolerance(Number(e.target.value))} className="min-w-0 flex-1 bg-transparent text-right outline-none" /></label>
                            <button onClick={appendLayoutCheckpoint} title="Lưu checkpoint quan hệ bố cục" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                          </div>
                        </>
                      ) : (
                        <>
                          <label className="block space-y-1">
                            <span className="block text-xs font-bold text-slate-500">Đích — {loaiCh7Hien.dichNhan}</span>
                            <input value={ch7Target} onChange={(e) => setCh7Target(e.target.value)} placeholder="định danh Semantics, ví dụ nguoi_dung.danh_sach" list="golden-dinh-danh" className={`w-full rounded-lg border bg-transparent px-3 py-2 font-mono text-sm ${vienDinhDanh(ch7Target)}`} />
                            {dinhDanhLa(ch7Target) && <span className="block text-xs text-amber-700 dark:text-amber-300">{NHAC_DINH_DANH_LA}</span>}
                          </label>
                          <div className="grid gap-2 sm:grid-cols-2">
                            {loaiCh7Hien.truong.map((t) => (
                              <label key={t.khoa} className="block space-y-1">
                                <span className="block text-xs font-bold text-slate-500">{t.nhan}{t.batBuoc ? " *" : " (tùy chọn)"}</span>
                                {t.kieu === "chon" ? (
                                  <select value={ch7Expect[t.khoa] ?? ""} onChange={(e) => setCh7Expect((cu) => ({ ...cu, [t.khoa]: e.target.value }))} className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                                    {(t.chon || []).map(([ma, nhan]) => <option key={ma} value={ma}>{nhan}</option>)}
                                  </select>
                                ) : (
                                  <input
                                    value={ch7Expect[t.khoa] ?? ""}
                                    onChange={(e) => setCh7Expect((cu) => ({ ...cu, [t.khoa]: e.target.value }))}
                                    inputMode={t.kieu === "so" ? "numeric" : undefined}
                                    list={t.kieu === "dinhdanh" ? "golden-dinh-danh" : undefined}
                                    placeholder={t.goiY || (t.kieu === "dinhdanh" ? "định danh Semantics" : "")}
                                    className={`w-full rounded-lg border bg-transparent px-3 py-2 font-mono text-sm ${
                                      t.kieu === "dinhdanh"
                                        ? vienDinhDanh(ch7Expect[t.khoa] ?? "")
                                        : "border-slate-300 dark:border-slate-700"
                                    }`}
                                  />
                                )}
                                {t.kieu === "dinhdanh" && dinhDanhLa(ch7Expect[t.khoa] ?? "")
                                  && <span className="block text-xs text-amber-700 dark:text-amber-300">{NHAC_DINH_DANH_LA}</span>}
                              </label>
                            ))}
                          </div>
                          <p className="rounded-lg border border-dashed border-amber-400 px-3 py-2 text-xs text-amber-700 dark:border-amber-700 dark:text-amber-300">{loaiCh7Hien.canhBao}</p>
                          <div className="flex flex-wrap items-center gap-2">
                            <button onClick={appendLayoutCheckpoint} disabled={Boolean(busy)} className="inline-flex items-center gap-2 rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white disabled:opacity-40"><Check size={16} /> Lưu tiêu chí bố cục</button>
                          </div>
                        </>
                      )}
                    </div>
                  )}
                </div>
                <div className="mt-4 max-h-72 space-y-2 overflow-auto pr-1">{(recording.raw_trace || []).map((item, index) => {
                  const sequence = Number(item.sequence || index + 1);
                  const actionCode = String(item.action || item.kind || "event");
                  const target = asJsonMap(item.target);
                  const laThaoTac = String(item.kind || "") === "action";
                  // Định danh hiện thành huy hiệu riêng, KHÔNG in kèm nhãn dự phòng: đó là thứ
                  // máy chấm dùng để tìm, nhìn một cái là biết bước này đã được neo chắc chưa.
                  const dinhDanh = readableValue(target.semantic_id || target.semanticId);
                  // Chuỗi người soạn NHÌN THẤY trên màn (nhãn, chữ, gợi ý...). Bỏ hai khoá định
                  // danh ra khỏi danh sách này để khỏi in hai lần cùng một giá trị.
                  const nhinThay = ["label", "text", "hint", "text_prefix", "tooltip"]
                    .map((key) => readableValue(target[key]))
                    .find((value) => value.trim().length > 0) || "";
                  const laGoChu = String(item.action || "") === "enter_text";
                  const giaTri = readableValue(item.value);
                  const delta = asJsonMap(item.delta);
                  const deltaText = Object.keys(delta).length
                    ? `x: ${readableValue(delta.x) || "0"}, y: ${readableValue(delta.y) || "0"}`
                    : "";
                  const expectation = summarizeExpectation(item.expect);
                  // Giá trị nhập CHỈ có nghĩa với thao tác. Checkpoint UI cũng mang `value` bằng
                  // chính chuỗi chữ cần kiểm, in ra là lặp y hệt dòng "Kỳ vọng" ngay dưới.
                  const hienGiaTri = laThaoTac && (laGoChu || giaTri.trim().length > 0);
                  return <div key={sequence} className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-xs dark:border-slate-700 dark:bg-slate-800/80">
                    <div className="flex items-center gap-2">
                      <span className="flex h-6 min-w-6 shrink-0 items-center justify-center rounded-full bg-indigo-100 px-1.5 font-mono font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{sequence}</span>
                      <span className="shrink-0 font-bold text-slate-800 dark:text-slate-100">{ACTION_LABELS[actionCode] || actionCode}</span>
                      <code className="shrink-0 rounded bg-slate-200 px-1.5 py-0.5 text-[10px] text-slate-500 dark:bg-slate-700 dark:text-slate-400">{actionCode}</code>
                      {/* Tám runner bố cục đều đi qua observe_ui nên nhãn thao tác không phân biệt
                          được chúng — in thêm tên loại thì danh sách mới đọc ra là tiêu chí gì. */}
                      {(() => {
                        const loai = CH7_LOAI.find((l) => l.ma === String(item.kind || ""));
                        return loai ? <span className="shrink-0 rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-bold text-violet-700 dark:bg-violet-950 dark:text-violet-300">{loai.ten}</span> : null;
                      })()}
                      {dinhDanh && <span className="shrink-0 rounded bg-teal-100 px-1.5 py-0.5 font-mono text-[10px] font-bold text-teal-700 dark:bg-teal-950 dark:text-teal-300" title="Định danh Semantics(identifier:) — máy chấm tìm bằng nó trước, nhãn/chữ cạnh bên là đường lui.">{dinhDanh}</span>}
                      {laThaoTac && !dinhDanh && Object.keys(target).length > 0 && <span className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold text-amber-700 dark:bg-amber-950 dark:text-amber-300" title="Bước này còn tìm bằng nhãn/chữ. Gắn Semantics(identifier:) vào Golden rồi Sinh lại testcase là máy nướng vào.">chưa có định danh</span>}
                      {nhinThay && <span className="min-w-0 flex-1 truncate text-slate-500 dark:text-slate-400" title={nhinThay}>{nhinThay}</span>}
                      <button onClick={() => deleteRecordedEvent(sequence)} disabled={Boolean(busy)} title="Xóa thao tác/checkpoint này" className="ml-auto shrink-0 rounded-md p-1.5 text-rose-500 hover:bg-rose-50 disabled:opacity-40 dark:hover:bg-rose-950/40"><Trash2 size={14} /></button>
                    </div>
                    {(hienGiaTri || deltaText || expectation) && <div className="mt-2 grid gap-1.5 pl-8 text-slate-600 dark:text-slate-300">
                      {hienGiaTri && <div className="flex min-w-0 items-start gap-2">
                        <span className="w-20 shrink-0 text-slate-400">Giá trị nhập</span>
                        {/* CHỈ HIỂN THỊ, không sửa được. Recorder đọc đúng nội dung đã gõ (đo
                            20/9/2026 trên Golden thật: "Mua đèn học" và "55000" vào đủ, không
                            mất dấu), nên một ô cho sửa tay chỉ mở đường cho plan lệch khỏi thứ
                            người soạn thật sự làm trên app. Ghi sai thì ghi lại bước đó. */}
                        {laGoChu && !String(item.value || "").trim()
                          ? <span className="min-w-0 break-words font-semibold text-rose-600 dark:text-rose-400">(recorder không đọc được nội dung — ghi lại bước này)</span>
                          : <span className="min-w-0 break-words font-semibold text-slate-700 dark:text-slate-200">{giaTri}</span>}
                      </div>}
                      {deltaText && <div className="flex min-w-0 items-start gap-2"><span className="w-20 shrink-0 text-slate-400">Độ cuộn</span><span>{deltaText}</span></div>}
                      {expectation && <div className="flex min-w-0 items-start gap-2"><span className="w-20 shrink-0 text-slate-400">Kỳ vọng</span><span className="min-w-0 break-words">{expectation}</span></div>}
                    </div>}
                  </div>;
                })}</div>
                {recording.status === "STOPPED" && error && <div className="mt-4 rounded-xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700 dark:border-rose-800 dark:bg-rose-950/30 dark:text-rose-200">Không thể sinh testcase: {error}. Phiên vẫn được giữ để bạn thử lại hoặc hủy.</div>}
                {buocThieuGiaTri > 0 && <p className="mt-3 rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-xs font-bold text-rose-700 dark:border-rose-800 dark:bg-rose-950/30 dark:text-rose-200">Còn {buocThieuGiaTri} bước gõ chữ không đọc được nội dung (dòng đỏ ở trên). Xóa bước đó rồi gõ lại trên Golden — để trống thì lúc chấm sẽ gõ chuỗi rỗng và mọi tiêu chí phía sau trượt theo.</p>}
                <div className="mt-4 flex flex-wrap gap-2"><button onClick={stopAndAbstract} disabled={Boolean(busy) || buocThieuGiaTri > 0} className="inline-flex items-center gap-2 rounded-xl bg-slate-800 px-4 py-2.5 font-bold text-white disabled:opacity-40 dark:bg-slate-700">{busy === "record-stop" ? <Loader2 size={17} className="animate-spin" /> : <Square size={17} />} {busy === "record-stop" ? "Đang replay Golden và sinh Output DB…" : recording.status === "STOPPED" ? "Thử sinh testcase lại" : editingScenarioId ? "Lưu sửa đổi và sinh lại testcase" : "Dừng, capture oracle và sinh testcase"}</button><button onClick={cancelActiveRecording} disabled={Boolean(busy)} className="rounded-xl border border-rose-300 px-4 py-2.5 font-bold text-rose-600 disabled:opacity-40 dark:border-rose-900">{editingScenarioId ? "Cancel" : "Cancel"}</button></div>
              </>}
            </div>
          </section>

          {staticRules && <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <h2 className="text-xl font-bold">Luật Tĩnh: Mô hình kiến trúc </h2>  
                <p className="mt-1 text-sm text-slate-500">Chấm bằng soi mã nguồn (cấu trúc thư mục, import, lint). Luật phải ĐẠT trên chính Golden Solution: badge đỏ nghĩa là đáp án mẫu không thỏa nên không thể đem luật đó chấm sinh viên.</p>
              </div>
              <div className="flex items-center gap-3">
                <span className="rounded-full bg-indigo-100 px-3 py-1 text-sm font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{(staticRules.presets || []).reduce((sum, p) => sum + (staticSel[p.id]?.checked ? diemSo(staticSel[p.id].weight) : 0), 0)} điểm</span>
                <button onClick={saveStaticRules} disabled={Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 font-bold text-white disabled:opacity-40">{busy === "static-rules" ? <Loader2 size={17} className="animate-spin" /> : <Check size={17} />} Lưu luật tĩnh</button>
              </div>
            </div>
            {!staticRules.golden_available && <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-700 dark:bg-amber-950/40 dark:text-amber-300">Chưa có Golden Solution — tải ZIP tại phần Dữ liệu đầu vào để hệ thống đối chứng luật.</p>}
            <div className="mt-4 grid gap-2 md:grid-cols-2">
              {(staticRules.presets || []).map((p) => {
                const sel = staticSel[p.id] || { checked: false, weight: p.weight };
                const goldenFailed = p.golden?.passed === false;
                const disabled = goldenFailed || (!staticRules.golden_available && p.kind === "source_pattern");
                return (
                  <label key={p.id} className={`flex items-start gap-3 rounded-xl border px-4 py-3 ${sel.checked ? "border-indigo-400 bg-indigo-50/50 dark:border-indigo-700 dark:bg-indigo-950/30" : "border-slate-200 dark:border-slate-700"} ${disabled ? "opacity-60" : "cursor-pointer"}`}>
                    <input type="checkbox" checked={sel.checked} disabled={disabled || Boolean(busy)}
                      onChange={(e) => setStaticSel((prev) => ({ ...prev, [p.id]: { checked: e.target.checked, weight: prev[p.id]?.weight ?? p.weight } }))}
                      className="mt-1 h-4 w-4 accent-indigo-600" />
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="font-bold">{p.name}</span>
                        {p.golden?.passed === true && <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-bold text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300" title={p.golden.detail}>Golden ✓</span>}
                        {goldenFailed && <span className="rounded-full bg-rose-100 px-2 py-0.5 text-[11px] font-bold text-rose-700 dark:bg-rose-950 dark:text-rose-300" title={p.golden?.detail}>Golden ✗</span>}
                        {p.golden?.passed == null && <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-bold text-slate-500 dark:bg-slate-800" title={p.golden?.detail}>Preflight kiểm</span>}
                      </span>
                      <span className="mt-1 block text-xs text-slate-500">{p.description}</span>
                      {goldenFailed && <span className="mt-1 block text-xs text-rose-500">{p.golden?.detail}</span>}
                    </span>
                    <span className="flex shrink-0 items-center gap-1 text-sm">
                      <input type="text" inputMode="decimal" value={sel.weight} disabled={!sel.checked || Boolean(busy)}
                        onChange={(e) => setStaticSel((prev) => ({ ...prev, [p.id]: { checked: prev[p.id]?.checked ?? false, weight: e.target.value } }))}
                        onClick={(e) => e.preventDefault()}
                        className="w-16 rounded-lg border border-slate-300 bg-transparent px-2 py-1 text-right disabled:opacity-40 dark:border-slate-700" />
                      <span className="text-xs text-slate-500">điểm</span>
                    </span>
                  </label>
                );
              })}
            </div>
          </section>}

          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <h2 className="text-xl font-bold">Kiểm tra Golden và publish</h2>
                <p className="mt-1 text-sm text-slate-500">Build & mở Golden → Recorder kết nối → Chạy thử đạt toàn bộ → Publish.</p>
                {!readiness?.ready && <p className="mt-1 text-sm text-amber-600">Còn thiếu: {readiness?.missing.join(", ") || "đang kiểm tra artifact"}.</p>}
                {!goldenReady && <p className="mt-1 text-sm text-amber-600">{runtimeStatus?.status !== "READY" || !runtimeStatus.available
                  ? "Hãy Build & mở Golden trước khi chạy thử."
                  : "Đang chờ recorder kết nối với Golden đang mở."}</p>}
                <p className={`mt-2 text-sm font-bold ${preflightPassed ? "text-emerald-600" : "text-amber-600"}`}>
                  Preflight: {validation?.status || "NOT_RUN"}{validation?.total_checkpoints !== undefined ? ` · ${validation.passed_checkpoints}/${validation.total_checkpoints}` : ""}
                  {validation && validation.status !== "NOT_RUN" && !validation.current ? " · bộ chấm hoặc dữ liệu đã thay đổi, cần chạy lại" : ""}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <button onClick={() => openCodePreview()} disabled={!suite.scenarios?.length || Boolean(busy)} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-700 dark:text-slate-200">
                  {busy === "code-preview" ? <Loader2 className="animate-spin" size={16} /> : <Code2 size={16} />} Xem code
                </button>
                {/* Chạy được cả khi recorder chưa nối: capture là Docker thuần, không cần iframe.
                    Chỉ cần Golden App đã build xong — backend tự chặn và nói nếu chưa. */}
                <button onClick={sinhLaiToanBo} disabled={!suite.scenarios?.length || Boolean(busy)} title="Bấm một lần thay cho việc mở từng luồng bấm “Sửa thao tác” rồi “Sinh lại testcase”" className="inline-flex items-center gap-1.5 rounded-lg border border-amber-300 px-3 py-2 text-sm font-bold text-amber-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-amber-700 dark:text-amber-300">
                  {busy === "sinh-lai-tat" ? <Loader2 className="animate-spin" size={16} /> : <ShieldCheck size={16} />} {busy === "sinh-lai-tat" ? "Đang sinh lại…" : "Sinh lại toàn bộ"}
                </button>
                <button onClick={validateGolden} disabled={!readiness?.ready || !goldenReady || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-300 px-3 py-2 text-sm font-bold text-indigo-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-indigo-700 dark:text-indigo-300">
                  {busy === "validate-golden" ? <Loader2 className="animate-spin" size={16} /> : <Play size={16} />} Chạy thử trên Golden
                </button>
                <button onClick={publish} disabled={!readiness?.ready || !goldenReady || !preflightPassed || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-40">
                  {busy === "publish" ? <Loader2 className="animate-spin" size={16} /> : <Send size={16} />} Publish
                </button>
              </div>
            </div>

            {checkpointFails.length > 0 && (
              <div className="mt-3 rounded-xl border border-rose-200 dark:border-rose-900">
                <div className="flex items-center justify-between gap-2 border-b border-rose-200 px-4 py-2 dark:border-rose-900">
                  <p className="text-xs font-bold text-rose-600">Tiêu chí chưa đạt ({checkpointFails.length})</p>
                  <p className="text-[11px] text-slate-500">chưa chạy = bước trước đó đã hỏng</p>
                </div>
                <div className="max-h-64 space-y-1.5 overflow-auto p-3">
                  {checkpointFails.map((c, index) => (
                    <div key={`${c.test_id}-${index}`} className="flex items-start gap-2 rounded-lg bg-rose-50 px-2.5 py-1.5 text-xs dark:bg-rose-950/30">
                      <XCircle size={15} className="mt-0.5 shrink-0 text-rose-500" />
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className="rounded bg-indigo-100 px-1.5 py-0.5 font-mono font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{c.scenario_code}</span>
                          <span className="font-mono text-slate-500">{c.test_id}</span>
                          {c.status === "not_run" && <span className="rounded bg-amber-100 px-1.5 py-0.5 font-bold text-amber-700 dark:bg-amber-950 dark:text-amber-300">chưa chạy</span>}
                        </div>
                        {c.name && c.name !== c.test_id && <p className="mt-1 break-words font-medium text-slate-600 dark:text-slate-300">{c.name}</p>}
                        {c.message && <p className="mt-1 break-words text-rose-700 dark:text-rose-300">{c.message}</p>}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
            <div className="mt-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
              {/* Các hàm chứa ref phía dưới chỉ chạy trong onClick, không chạy trong render. */}
              {/* eslint-disable-next-line react-hooks/refs */}
              {scenarioSapXep.flatMap(({ item, index, dauNhom, nhanNhom }) => [dauNhom ? <p key={`nhom-${nhanNhom}`} className="col-span-full mt-2 border-b border-slate-200 pb-1 text-xs font-bold uppercase tracking-wide text-slate-500 dark:border-slate-700">{nhanNhom}</p> : null, <div key={String(item.id || index)} className={`rounded-xl border px-4 py-3 ${scenarioHong.has(String(item.id)) ? "border-rose-400 bg-rose-50/40 dark:border-rose-800 dark:bg-rose-950/20" : "border-slate-200 dark:border-slate-700"}`}><div className="flex items-center justify-between gap-2"><span className="font-bold">{String(item.name || item.scenario_code)}</span><span className="rounded-full bg-indigo-100 px-2 py-1 text-xs font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{String(item.weight)} điểm</span></div>{(scenarioHong.get(String(item.id)) || []).map((lyDo, i) => <p key={i} className="mt-1 rounded bg-rose-100 px-2 py-1 text-[11px] font-bold text-rose-700 dark:bg-rose-950/50 dark:text-rose-300">{lyDo}</p>)}{(() => {
                // Độ phủ định danh của kịch bản: bước có target mà chưa có định danh vẫn chấm được
                // bằng nhãn, nhưng người soạn cần thấy để biết kịch bản nào chưa hưởng định danh
                // (gắn vào Golden rồi "Sinh lại testcase" là máy nướng vào, không ghi hình lại).
                const steps = Array.isArray(item.steps) ? (item.steps as JsonMap[]) : [];
                const coTarget = steps.filter((st) => st && typeof st === "object" && Object.keys((st.target as JsonMap) || {}).length > 0);
                const coId = coTarget.filter((st) => { const t = (st.target as JsonMap) || {}; return Boolean(t.semantic_id || t.semanticId); });
                const du = coTarget.length > 0 && coId.length === coTarget.length;
                return <p className="mt-2 text-xs text-slate-500">{steps.length} action · {Array.isArray(item.checkpoints) ? item.checkpoints.length : 0} checkpoint{coTarget.length > 0 && <> · <span className={du ? "font-bold text-teal-600 dark:text-teal-300" : "font-bold text-amber-600 dark:text-amber-300"} title={du ? "Mọi bước đều có định danh Semantics(identifier:)." : "Bước chưa có định danh được tìm bằng nhãn/chữ. Gắn Semantics(identifier:) vào Golden rồi Sinh lại testcase để máy nướng vào."}>{coId.length}/{coTarget.length} bước có định danh</span></>}</p>;
              })()}{chiaDiemId === String(item.id) && (() => {
                  const chots = Array.isArray(item.checkpoints) ? item.checkpoints as JsonMap[] : [];
                  const hanhVi = chots;
                  const tongHanhVi = hanhVi.reduce((t, c) => t + diemSo(chiaDiemChot[String(c.id)] ?? 1), 0);
                  // Con cua ai thi hien duoi cha do, thut vao — nhin phat biet ngay quan he.
                  const conCua = (chaId: string) => chots.filter((c) => String(chiaDiemCha[String(c.id)] ?? c.requires ?? "") === chaId);
                  const dsGoc = chots.filter((c) => !(chiaDiemCha[String(c.id)] ?? c.requires));
                  const tuTruong = (id: string, tap: Set<string>) => {
                    tap.add(id);
                    conCua(id).forEach((c) => tuTruong(String(c.id), tap));
                    return tap;
                  };
                  // Di chuot vao dong la nho lai checkpoint nay kiem gi — nguoi soan khong
                  // phai nho id khô khan trong dau.
                  const moTaChot = (c: JsonMap) => {
                    const phan: string[] = [];
                    if (c.name) phan.push(String(c.name));
                    phan.push(`loại: ${String(c.kind)}`);
                    const exp = (c.expect || {}) as JsonMap;
                    const thay_ = Array.isArray(exp.visible_texts) ? exp.visible_texts.filter(Boolean) : [];
                    const an = Array.isArray(exp.hidden_texts) ? exp.hidden_texts.filter(Boolean) : [];
                    if (thay_.length) phan.push(`phải thấy: ${thay_.join(" · ")}`);
                    const tienTo = Array.isArray(exp.visible_text_prefixes) ? exp.visible_text_prefixes.filter(Boolean) : [];
                    if (tienTo.length) phan.push(`bắt đầu bằng: ${tienTo.join(" · ")}`);
                    if (an.length) phan.push(`không được thấy: ${an.join(" · ")}`);
                    if (c.table) phan.push(`bảng ${String(c.table)} · ${String(c.operation || "")}${c.count !== undefined ? ` · tổng row ${String(c.count)}` : ""}`);
                    if (c.row && Object.keys(c.row as JsonMap).length) phan.push(`row: ${JSON.stringify(c.row)}`);
                    if (c.target && Object.keys(c.target as JsonMap).length) phan.push(`target: ${JSON.stringify(c.target)}`);
                    if (c.widget) phan.push(`đọc ${String(c.property || "")} của ${String(c.widget)}`);
                    else if (c.property) phan.push(`đọc ${String(c.property)}`);
                    if ((c.expect as JsonMap)?.value !== undefined) phan.push(`giá trị chuẩn: ${String((c.expect as JsonMap).value)}`);
                    if (c.tolerance_pct !== undefined) phan.push(`sai số: ${String(c.tolerance_pct)}%`);
                    return phan.join("\n");
                  };
                  const dong = (c: JsonMap, tuyetDoi: boolean, sau: number) => {
                    const id = String(c.id);
                    const w = chiaDiemChot[id] ?? 1;
                    const cam = tuTruong(id, new Set<string>());
                    return <div key={id}>
                      <div className="flex items-center gap-2 text-xs" style={{ paddingLeft: `${sau * 1.1}rem` }} title={moTaChot(c)}>
                        {sau > 0 && <span className="text-amber-600">↳</span>}
                        <span className="min-w-0 flex-1 truncate text-slate-500">{id} · {String(c.kind)}</span>
                        <select value={String(chiaDiemCha[id] ?? c.requires ?? "")} onChange={(e) => setChiaDiemCha({ ...chiaDiemCha, [id]: e.target.value })} title="Ràng vào checkpoint cha: cha trượt thì checkpoint này không được tính điểm dù tự nó đạt" className="w-32 shrink-0 rounded border border-amber-300 bg-transparent px-1 py-0.5 text-amber-700 dark:border-amber-800 dark:text-amber-300">
                          <option value="">độc lập</option>
                          {chots.filter((k) => !cam.has(String(k.id))).map((k) => <option key={String(k.id)} value={String(k.id)}>↳ {String(k.id)}</option>)}
                        </select>
                        <input type="text" inputMode="decimal" value={w} aria-label={`Điểm checkpoint ${id}`} onChange={(e) => setChiaDiemChot({ ...chiaDiemChot, [id]: e.target.value })} className="w-16 shrink-0 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" />
                      </div>
                      {conCua(id).map((k) => dong(k, false, sau + 1))}
                    </div>;
                  };
                  return <div className="mt-2 space-y-1.5 rounded-lg border border-indigo-200 bg-indigo-50/40 p-2 dark:border-indigo-900 dark:bg-indigo-950/20">
                    <label className="flex items-center justify-between text-xs font-bold">Trọng số hàm
                      <input type="text" inputMode="decimal" value={chiaDiemHam} aria-label="Điểm hàm test" onChange={(e) => setChiaDiemHam(e.target.value)} className="w-20 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" />
                    </label>
                    {dsGoc.map((c) => dong(c, false, 0))}
                    {hanhVi.length > 0 && Math.abs(tongHanhVi - diemSo(chiaDiemHam)) > 1e-9 && <p className="text-[11px] font-bold text-rose-600">Đã chia {lamTron(tongHanhVi)}/{chiaDiemHam}đ — tổng điểm checkpoint phải bằng đúng điểm của hàm mới lưu được.</p>}
                    <button onClick={() => luuChiaDiem(item)} disabled={Boolean(busy)} className="w-full rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-bold text-white disabled:opacity-40">
                      {busy === "chia-diem" ? "Đang lưu (đổi điểm checkpoint sẽ capture lại ~1 phút)…" : "Lưu chia điểm"}
                    </button>
                  </div>;
                })()}
                <div className="mt-3 flex flex-wrap gap-2"><button onClick={() => moChiaDiem(item)} disabled={Boolean(busy) || Boolean(recording)} title="Sửa trọng số hàm và điểm từng checkpoint đã lưu" className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 px-2.5 py-1.5 text-xs font-bold text-emerald-700 hover:bg-emerald-50 disabled:opacity-40 dark:border-emerald-800 dark:text-emerald-300 dark:hover:bg-emerald-950"><Database size={14} /> Chia điểm</button><button onClick={() => openScenarioEditor(item)} disabled={Boolean(busy) || Boolean(recording)} title={recording ? "Hãy kết thúc phiên đang soạn trước" : "Nạp lại các bước vào khung record để chỉnh sửa"} className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-300 px-2.5 py-1.5 text-xs font-bold text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 dark:border-indigo-800 dark:text-indigo-300 dark:hover:bg-indigo-950"><Pencil size={14} /> Sửa thao tác</button><button onClick={() => doCoGian(item)} disabled={Boolean(busy) || Boolean(recording) || !previewUrl} title="Đo bố cục ở khung điện thoại rồi đo lại ở khung desktop 1280×800, chỉ ra cặp thành phần ĐỔI quan hệ — đó là chỗ app thật sự co giãn." className="inline-flex items-center gap-1.5 rounded-lg border border-sky-300 px-2.5 py-1.5 text-xs font-bold text-sky-700 hover:bg-sky-50 disabled:opacity-40 dark:border-sky-800 dark:text-sky-300 dark:hover:bg-sky-950">{busy === "resp-scan" ? <Loader2 className="animate-spin" size={14} /> : <MonitorPlay size={14} />} Dò responsive</button><button onClick={() => deleteScenario(item)} disabled={Boolean(busy) || Boolean(recording)} className="inline-flex items-center gap-1.5 rounded-lg border border-rose-300 px-2.5 py-1.5 text-xs font-bold text-rose-600 hover:bg-rose-50 disabled:opacity-40 dark:border-rose-900 dark:hover:bg-rose-950"><Trash2 size={14} /> Xóa</button></div></div>])}
              {respKq && respScenario && (() => {
                const soManh = respKq.bangChung.length + respKq.doiCot.length;
                const daTick = respKq.bangChung.filter((b) => b.checked).length + respKq.capReflow.filter((c) => c.checked).length;
                return (
                <div className="mt-3 rounded-xl border border-sky-300 bg-sky-50/40 p-3 dark:border-sky-800 dark:bg-sky-950/20">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-bold">Responsive — luồng {respScenario.ma}</span>
                    <label className="flex items-center gap-1 text-sm">Điểm kĩ năng
                      <input type="text" inputMode="decimal" value={respDiem} onChange={(e) => setRespDiem(e.target.value)} className="w-16 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" />đ
                    </label>
                    <button onClick={luuTieuChiResponsive} disabled={Boolean(busy) || daTick === 0} className="rounded-lg bg-sky-600 px-3 py-1.5 text-sm font-bold text-white disabled:opacity-40">Thêm {daTick} tiêu chí</button>
                    <button onClick={() => { setRespKq(null); setRespScenario(null); }} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-bold text-slate-600 dark:border-slate-600 dark:text-slate-300">Đóng</button>
                  </div>

                  {soManh === 0 ? (
                    <p className="mt-2 rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-xs font-bold text-rose-700 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300">
                      Golden này KHÔNG có dấu hiệu co giãn thật nào — không thành phần nào hiện thêm hay biến mất, nội dung vẫn nở hết bề ngang, không nhóm nào đổi số cột.
                      Mọi thứ đổi chỗ ở danh sách dưới chỉ là <b>Wrap tự xếp lại</b>, app không viết một dòng responsive nào cũng có. Chấm chúng là cho điểm một kĩ năng không tồn tại.
                    </p>
                  ) : (
                    <div className="mt-2">
                      <p className="text-xs font-bold text-emerald-700 dark:text-emerald-400">CO GIÃN THẬT — {soManh} dấu hiệu. Reflow tự nhiên không tạo ra được những thứ này.</p>
                      {respKq.doiCot.map((d) => (
                        <p key={d.key} className="mt-1 text-[11px] text-emerald-700 dark:text-emerald-400">· Nhóm &ldquo;{d.nhan}&rdquo; đổi từ {d.dienThoai} cột sang {d.desktop} cột — list thành grid</p>
                      ))}
                      <div className="mt-1 grid max-h-44 gap-1 overflow-auto pr-1">
                        {respKq.bangChung.map((b, i) => (
                          <label key={`${b.loai}|${b.it.key}`} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1 text-sm hover:bg-emerald-100/60 dark:hover:bg-emerald-900/30">
                            <input type="checkbox" checked={b.checked} onChange={() => setRespKq((prev) => prev ? { ...prev, bangChung: prev.bangChung.map((x, j) => (j === i ? { ...x, checked: !x.checked } : x)) } : prev)} />
                            <span className="shrink-0 rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-bold text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300">
                              {b.loai === "hien_them" ? "hiện thêm" : b.loai === "bien_mat" ? "biến mất" : "kìm bề ngang"}
                            </span>
                            <span className="truncate">{b.moTa}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  )}

                  <p className="mt-3 text-xs font-bold text-amber-700 dark:text-amber-400">
                    REFLOW TỰ NHIÊN — {respKq.capReflow.length} cặp đổi quan hệ. Wrap tự xếp lại, chữ hết xuống dòng, Expanded nở ra đều tạo ra được,
                    nên tick vào là chấm &ldquo;bài có dùng cùng widget với Golden không&rdquo;, không phải chấm kĩ năng responsive.
                  </p>
                  <p className="mt-2 text-sm leading-relaxed text-slate-500">
                    Nội dung chiếm {Math.round(respKq.beNgang.dienThoai * 100)}% bề ngang ở khung điện thoại và {Math.round(respKq.beNgang.desktop * 100)}% ở khung desktop.
                    Điểm khai ở trên chia đều cho MỌI mục đã tick ở cả hai phần, lấy từ ngân sách của luồng {respScenario.ma}.
                  </p>
                  <div className="mt-1 grid max-h-44 gap-1 overflow-auto pr-1">
                    {respKq.capReflow.map((c, i) => (
                      <label key={`${c.a.key}|${c.b.key}`} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1 text-sm hover:bg-amber-100/60 dark:hover:bg-amber-900/30">
                        <input type="checkbox" checked={c.checked} onChange={() => setRespKq((prev) => prev ? { ...prev, capReflow: prev.capReflow.map((x, j) => (j === i ? { ...x, checked: !x.checked } : x)) } : prev)} />
                        <span className="truncate font-medium">{c.a.label || c.a.identifier}</span>
                        <span className="shrink-0 text-slate-400">↔</span>
                        <span className="truncate font-medium">{c.b.label || c.b.identifier}</span>
                        <span className="ml-auto shrink-0 rounded bg-slate-200 px-1.5 py-0.5 text-[10px] font-bold text-slate-600 dark:bg-slate-800 dark:text-slate-300">{TEN_QUAN_HE[c.dienThoai] || c.dienThoai}</span>
                        <span className="shrink-0 text-slate-400">→</span>
                        <span className="shrink-0 rounded bg-amber-200 px-1.5 py-0.5 text-[10px] font-bold text-amber-800 dark:bg-amber-900 dark:text-amber-200">{TEN_QUAN_HE[c.desktop] || c.desktop}</span>
                      </label>
                    ))}
                  </div>
                </div>
                );
              })()}
              {!suite.scenarios?.length && <p className="text-sm text-slate-500">Chưa có scenario. Hãy record ít nhất một luồng và sinh testcase.</p>}
            </div>
          </section>
        </>}
        {codePreview && previewFile && <div className="fixed inset-0 z-[100] flex items-center justify-center bg-slate-950/75 p-3 backdrop-blur-sm sm:p-6">
          <div className="flex h-[min(900px,94vh)] w-full max-w-[1500px] min-w-0 flex-col overflow-hidden rounded-2xl border border-slate-700 bg-slate-950 text-slate-100 shadow-2xl">
            <div className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-800 px-5 py-4">
              <div className="min-w-0"><p className="text-xs font-bold uppercase tracking-widest text-indigo-400">Code sinh theo bộ Golden</p><h2 className="mt-1 truncate text-xl font-bold">{suite?.name || name}</h2><p className="mt-1 text-sm text-slate-400">{codePreview.scenario_count} scenario · {codePreview.criterion_count} đầu điểm. File hiển thị được sinh từ cùng engine dùng khi publish.</p></div>
              <button onClick={() => setCodePreview(null)} title="Đóng bản xem code" className="rounded-lg border border-slate-700 p-2 text-slate-300 hover:bg-slate-800"><X size={20} /></button>
            </div>
            <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
              <aside className="flex shrink-0 gap-2 overflow-x-auto border-b border-slate-800 p-3 lg:w-72 lg:flex-col lg:overflow-y-auto lg:border-b-0 lg:border-r">
                {codePreview.files.map((file) => <button key={file.name} onClick={() => setPreviewFileName(file.name)} className={`min-w-max rounded-lg border px-3 py-2 text-left transition lg:min-w-0 ${previewFile.name === file.name ? "border-indigo-500 bg-indigo-500/15 text-indigo-200" : "border-slate-800 text-slate-400 hover:border-slate-600 hover:text-slate-200"}`}><span className="block font-mono text-xs font-bold">{file.name}</span><span className="mt-1 hidden text-[11px] leading-4 lg:block">{file.description}</span></button>)}
              </aside>
              <main className="flex min-h-0 min-w-0 flex-1 flex-col">
                <div className="flex items-center justify-between gap-3 border-b border-slate-800 px-4 py-3"><div className="min-w-0"><p className="truncate font-mono text-sm font-bold text-indigo-300">{previewFile.name}</p><p className="truncate text-xs text-slate-400">{previewFile.description}</p></div><button onClick={() => void navigator.clipboard.writeText(previewFile.content)} className="inline-flex shrink-0 items-center gap-2 rounded-lg border border-slate-700 px-3 py-2 text-xs font-bold hover:bg-slate-800"><Copy size={15} /> Sao chép</button></div>
                <pre className="min-h-0 flex-1 overflow-auto whitespace-pre p-4 font-mono text-xs leading-5 text-slate-200"><code>{previewFile.content}</code></pre>
              </main>
            </div>
          </div>
        </div>}
      </div>
    </SidebarLayout>
  );
}

export default function BehaviorAuthoringPage() {
  return (
    <Suspense fallback={
      <SidebarLayout activePath="/teacher/archive" title="Golden Solution Recorder" subtitle="Đang nạp bộ soạn hành vi…">
        <div className="flex min-h-[50vh] items-center justify-center text-slate-400">
          <Loader2 className="animate-spin" size={28} />
        </div>
      </SidebarLayout>
    }>
      <BehaviorAuthoringEditor />
    </Suspense>
  );
}
