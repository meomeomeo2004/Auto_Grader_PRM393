"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useSearchParams } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  Check, CheckCircle2, ChevronDown, Circle, Code2, Copy, Database, FileArchive, FileJson,
  Loader2, MonitorPlay, Pencil, Play, Plus, Radio, Send, ShieldCheck,
  Square, Trash2, UploadCloud, X, XCircle,
} from "lucide-react";

type JsonMap = Record<string, unknown>;
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
interface Readiness { ready: boolean; missing: ArtifactType[]; artifacts: Partial<Record<ArtifactType, Artifact | null>> }
interface GoldenValidation { status: "NOT_RUN" | "RUNNING" | "PASSED" | "FAILED" | "UNAVAILABLE"; current: boolean; total_checkpoints?: number; passed_checkpoints?: number; log?: string }
interface RuntimeStatus { status: string; runtime_url?: string | null; runtime_path?: string | null; available?: boolean; cached?: boolean; message?: string; metadata?: JsonMap }
interface CodePreviewFile { name: string; description: string; scope: "SCENARIO" | "BUNDLE" | "ENGINE"; content: string }
interface CodePreview { suite_id: string; suite_code: string; selected_scenario_code?: string | null; scenario_count: number; criterion_count: number; files: CodePreviewFile[] }
interface StaticRuleGolden { passed: boolean | null; detail: string }
interface StaticRule { id: string; name: string; kind: "lint" | "source_pattern"; lint_code?: string; config?: JsonMap; weight: number; group_id: string; group_name: string; skill_code?: string; description?: string; golden?: StaticRuleGolden }
interface StaticRulesView { suite_id: string; golden_available: boolean; rules: StaticRule[]; presets: StaticRule[] }

const ARTIFACTS: { type: ArtifactType; title: string; owner: "teacher" | "system"; accept: string; hint: string; icon: typeof Database }[] = [
  { type: "STUDENT_DATABASE", title: "1. Database phát cho sinh viên", owner: "teacher", accept: ".db,.sqlite,.sqlite3", hint: "Dữ liệu mẫu công khai đi cùng đề.", icon: Database },
  { type: "HIDDEN_DATABASE", title: "2. Database ẩn", owner: "teacher", accept: ".db,.sqlite,.sqlite3", hint: "Cùng schema, dữ liệu khác để chống hardcode.", icon: ShieldCheck },
  { type: "GOLDEN_SOLUTION", title: "3. Golden Solution", owner: "teacher", accept: ".zip", hint: "ZIP đáp án chuẩn dùng để tạo oracle.", icon: FileArchive },
  { type: "AUTOMATION_RECORD", title: "4. Bản ghi thao tác", owner: "system", accept: ".json", hint: "Hệ thống sinh khi dừng phiên record.", icon: Radio },
  { type: "GRADING_ENVIRONMENT", title: "5. Môi trường chấm", owner: "system", accept: ".json", hint: "API, driver, browser và timeout của suite.", icon: MonitorPlay },
  { type: "TESTCASE_DEFINITION", title: "6. File testcase", owner: "system", accept: ".json", hint: "7 cột Stage, Attribute, AttributeValue, ValueType, Value, Action, Browser.", icon: FileJson },
  { type: "OUTPUT_DATABASE", title: "7. Output Database", owner: "system", accept: ".db,.sqlite,.sqlite3", hint: "Hệ thống replay Golden trên DB ẩn rồi tự capture trạng thái DB sau thao tác.", icon: Database },
];

// Khớp BASE_PACKAGES trong SubmissionPackagePolicy.java — dùng làm mặc định khi tạo suite mới và
// làm gợi ý bấm-thêm-nhanh, tránh lặp lại lỗi PE_PRM: contract sinh ra chỉ có 5 package tối thiểu,
// chặn nhầm bài dùng riverpod.
const DEFAULT_ALLOWED_PACKAGES = [
  "flutter", "flutter_test", "flutter_riverpod", "riverpod", "riverpod_annotation",
  "path", "sqflite", "sqflite_common", "sqflite_common_ffi", "sqflite_common_ffi_web",
  "path_provider", "sembast_web", "image_picker", "intl",
];

const ACTIONS = [
  "boot", "boot_with_uri", "tap", "enter_text", "clear_text", "scroll", "drag", "back",
  "open_uri", "browser_back", "browser_forward", "reload", "restart",
  "wait_until", "wait_for_route",
];
// Bỏ hẳn "valueKey" khỏi danh sách chọn: đề chỉ còn MỘT hệ định danh. Engine vẫn đọc
// được khoá cũ để bộ đề đã ra không chết, nhưng không mời ai khai thêm cái mới.
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
// tưởng "semanticId" là ValueKey: máy chấm tìm nó bằng Semantics(identifier:), còn ValueKey
// thì recorder không bao giờ thấy (không ra tới DOM) nên chỉ dùng được khi gõ tay ở đây.
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
  valueKey: "ValueKey (recorder không thấy, chỉ gõ tay)",
  label: "nhãn — Semantics(label:)",
  hint: "gợi ý ô nhập — hintText",
  text: "chữ hiển thị",
  text_prefix: "chữ bắt đầu bằng",
  tooltip: "tooltip",
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
  if (!response.ok) throw new Error(String(data.error || `HTTP ${response.status}`));
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
  // Toast phải PORTAL ra document.body: layout có ancestor mang transform nên
  // position:fixed bị neo theo ancestor đó thay vì viewport — toast rơi ra ngoài
  // màn hình, người soạn không thấy lỗi và tưởng nút hỏng (ca có thật 29/8).
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const [examId, setExamId] = useState(() => search.get("exam") || "");
  const [name, setName] = useState("");
  const [runtimeUrl, setRuntimeUrl] = useState("");
  const [databaseName, setDatabaseName] = useState("");
  const [allowedPackages, setAllowedPackages] = useState<string[]>(DEFAULT_ALLOWED_PACKAGES);
  // Danh sách package CÓ THẬT trong ảnh chấm, đọc từ pubspec.lock của ảnh. Bảng tick dựng từ
  // đây nên người ra đề không gõ được tên không tồn tại — thứ mà thêm vào cũng chẳng cài gì.
  const [goiCuaAnh, setGoiCuaAnh] = useState<{
    imageRead: boolean;
    direct: { name: string; version: string; protected: boolean }[];
    transitive: string[];
  } | null>(null);
  const [moGoiChoPhep, setMoGoiChoPhep] = useState(false);
  const [moGoiKeoTheo, setMoGoiKeoTheo] = useState(false);
  const [suite, setSuite] = useState<Suite | null>(null);
  const [availableSuites, setAvailableSuites] = useState<Suite[]>([]);
  const [recording, setRecording] = useState<Recording | null>(null);
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [validation, setValidation] = useState<GoldenValidation | null>(null);
  // parseCheckpointLog đã lọc sẵn dòng trượt, nên đây là danh sách CHƯA ĐẠT chứ không phải toàn bộ.
  const checkpointFails = useMemo(() => parseCheckpointLog(validation?.log), [validation?.log]);
  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus | null>(null);
  const [recorderReady, setRecorderReady] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [action, setAction] = useState("tap");
  const [locator, setLocator] = useState("semanticId");
  const [locatorValue, setLocatorValue] = useState("");
  const [inputValue, setInputValue] = useState("");
  // Do doi cho `drag`, tinh bang dp. Mac dinh keo ngang 80 — du de doi mot Slider
  // 10 nac di hai nac (do o sa ban), va khong am tham keo doc.
  const [keoX, setKeoX] = useState(80);
  const [keoY, setKeoY] = useState(0);
  const [checkpointText, setCheckpointText] = useState("");
  const [thuGon, setThuGon] = useState(false);
  const [uiGroupWeight, setUiGroupWeight] = useState(0);
  const [viTriWeight, setViTriWeight] = useState(0);
  const [mauWeight, setMauWeight] = useState(0);
  const daKhoiTaoDiemUi = useRef(false);
  const [chiaDiemId, setChiaDiemId] = useState("");
  const [chiaDiemHam, setChiaDiemHam] = useState(0);
  const [chiaDiemChot, setChiaDiemChot] = useState<Record<string, number>>({});
  const [chiaDiemCha, setChiaDiemCha] = useState<Record<string, string>>({});
  const [hiddenCheckpointText, setHiddenCheckpointText] = useState("");
  const [checkpointMode, setCheckpointMode] = useState<"ui" | "database" | "route" | "layout">("ui");
  const [uiCheckpointType, setUiCheckpointType] = useState<"text" | "component" | "widget_state" | "text_style" | "theme_value" | "no_overflow" | "no_exception">("text");
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
  // KHÔNG điền sẵn: mã/tên luồng là danh tính của tiêu chí trong bảng điểm,
  // để mặc định thì mọi bộ chấm đều đầy "MAIN_FLOW/Luồng chính" vô nghĩa.
  const [scenarioCode, setScenarioCode] = useState("");
  const [scenarioName, setScenarioName] = useState("");
  const [scenarioWeight, setScenarioWeight] = useState(10);
  // Bảng tick thành phần giao diện — đổ về từ lệnh quét màn hình của bridge.
  // null = chưa quét; mảng = đang mở bảng tick.
  const [uiInventory, setUiInventory] = useState<{ attribute: string; value: string; role: string; identifier: string; count: number; checked: boolean }[] | null>(null);
  const [uiScreenName, setUiScreenName] = useState("");
  // Kiểm kê ICON của màn cuối luồng, do MÁY CHẤM đo lúc capture. Không quét được qua
  // DOM như bảng trên: nút chỉ có hình thì web không phơi aria-label nào, nên đúng những
  // nút cần chấm lại là những nút "Quét thành phần UI" không thấy.
  const [iconInventory, setIconInventory] = useState<
    { icon: string; count: number; perRow: boolean; buttonType: string; checked: boolean }[] | null
  >(null);
  const [iconScenario, setIconScenario] = useState<{ id: string; ma: string; checkpoints: JsonMap[] } | null>(null);
  // Chấm VỊ TRÍ và MÀU của từng thành phần đã tick. Sai số mặc định 5%: vị trí tính theo
  // % chiều rộng/cao màn hình, màu tính theo % của 255 trên từng kênh R/G/B.
  const [viTriOn, setViTriOn] = useState(true);
  const [viTriSaiSo, setViTriSaiSo] = useState(5);
  const [mauOn, setMauOn] = useState(true);
  const [mauSaiSo, setMauSaiSo] = useState(5);
  // Màu chủ đạo của app — MỘT dòng cho cả màn, đọc thẳng ColorScheme. Sai số rộng hơn
  // hẳn màu thành phần vì phép đo này chính xác tuyệt đối, không có nhiễu để chống.
  // Khung máy Android tầm trung (Pixel): 412×915 dp. Sinh viên làm bài trên máy ảo
  // Android nên đây là khung DUY NHẤT còn ý nghĩa; khung desktop đã bỏ hẳn.
  //
  // KHÔNG có ô mật độ điểm ảnh: mọi phép chấm bố cục đo bằng dp (tâm thành phần, sai số
  // theo % chiều rộng/cao), nên mật độ không đổi một điểm nào — nó chỉ quyết định ảnh
  // bằng chứng nét tới đâu và nặng bao nhiêu. Để cố định 1 cho ảnh gọn.
  const [viewportWidth, setViewportWidth] = useState(412);
  // Chấm ở chế độ tối: cờ nằm TRONG object viewport nên đi qua mọi tầng như rộng/cao màn.
  const [cheDoToi, setCheDoToi] = useState(false);
  const [viewportHeight, setViewportHeight] = useState(915);
  const [codePreview, setCodePreview] = useState<CodePreview | null>(null);
  const [previewFileName, setPreviewFileName] = useState("");
  const [editingScenarioId, setEditingScenarioId] = useState<string | null>(null);
  // Luật chấm tĩnh (Kiến trúc/lint): preset do backend cung cấp kèm đối chứng Golden.
  const [staticRules, setStaticRules] = useState<StaticRulesView | null>(null);
  const [staticSel, setStaticSel] = useState<Record<string, { checked: boolean; weight: number }>>({});
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
  const savedDatabaseName = String(
    suite?.database_contract?.database_name
      || suite?.database_contract?.path
      || suite?.database_contract?.name
      || "",
  ).trim();
  const databaseNameChanged = Boolean(suite && databaseName.trim() !== savedDatabaseName);
  const manualActionNeedsTarget = ["tap", "enter_text", "clear_text", "scroll", "wait_until"].includes(action);
  const manualActionNeedsUri = ["boot_with_uri", "open_uri", "wait_for_route"].includes(action);
  const savedAllowedPackages = Array.isArray(suite?.runtime_config?.allowed_packages)
    ? (suite.runtime_config.allowed_packages as unknown[]).map(String)
    : [];
  const allowedPackagesChanged = Boolean(
    suite && JSON.stringify([...allowedPackages].sort()) !== JSON.stringify([...savedAllowedPackages].sort()),
  );
  // flutter và flutter_test là lõi: bỏ chúng đi thì không bài nào biên dịch nổi.
  const GOI_LOI = ["flutter", "flutter_test"];
  const batTatPackage = (ten: string) => setAllowedPackages((current) => (GOI_LOI.includes(ten)
    ? current
    : current.includes(ten) ? current.filter((p) => p !== ten) : [...current, ten]));
  // TẤT CẢ gói có thể chọn, thứ tự cố định: lõi, thư viện khai thẳng trong ảnh, rồi gói kéo
  // theo. Danh sách này KHÔNG phụ thuộc đang tick gì, nhờ vậy bỏ một thẻ là nó nhảy sang cột
  // khả dụng chứ không biến mất — bỏ rồi chọn lại được ngay.
  const tenKhaiThang = (goiCuaAnh?.direct || []).map((p) => p.name);
  const thuTuGoi = [
    ...GOI_LOI,
    ...tenKhaiThang,
    ...(goiCuaAnh?.transitive || []),
    ...savedAllowedPackages,
    ...allowedPackages,
  ].filter((ten, i, ds) => ds.indexOf(ten) === i);
  const goiDangDung = thuTuGoi.filter((ten) => GOI_LOI.includes(ten) || allowedPackages.includes(ten));
  // Cột khả dụng mặc định chỉ bày thư viện của ảnh và gói bộ đề từng cho phép. Tám chục gói
  // kéo theo còn lại là ruột của Dart và của chính mấy thư viện trên (async, meta, collection),
  // bày hết thì che mất mười mấy cái thật sự đáng cân nhắc — gói vào một nút mở thêm.
  const goiChuaDung = thuTuGoi.filter((ten) => !GOI_LOI.includes(ten) && !allowedPackages.includes(ten));
  const goiKhaDung = moGoiKeoTheo
    ? goiChuaDung
    : goiChuaDung.filter((ten) => tenKhaiThang.includes(ten) || savedAllowedPackages.includes(ten));
  const soKeoTheoAn = goiChuaDung.length - goiKhaDung.length;

  useEffect(() => setRecorderReady(false), [previewUrl]);

  const activeByType = useMemo(() => {
    const result: Partial<Record<ArtifactType, Artifact>> = {};
    artifacts.filter((item) => item.active).forEach((item) => { result[item.type] = item; });
    return result;
  }, [artifacts]);
  const recordingInputsReady = Boolean(
    activeByType.STUDENT_DATABASE
      && activeByType.HIDDEN_DATABASE
      && activeByType.GOLDEN_SOLUTION,
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
    const contractName = String(
      suiteData.database_contract?.database_name
        || suiteData.database_contract?.path
        || suiteData.database_contract?.name
        || "",
    );
    setDatabaseName(contractName);
    void api<JsonMap>("/grading-env/importable-packages").then((data) => setGoiCuaAnh({
      imageRead: Boolean(data.image_read),
      direct: Array.isArray(data.direct) ? (data.direct as { name: string; version: string; protected: boolean }[]) : [],
      transitive: Array.isArray(data.transitive) ? (data.transitive as string[]) : [],
    })).catch(() => setGoiCuaAnh({ imageRead: false, direct: [], transitive: [] }));
    setAllowedPackages(Array.isArray(suiteData.runtime_config?.allowed_packages)
      ? (suiteData.runtime_config.allowed_packages as unknown[]).map(String)
      : DEFAULT_ALLOWED_PACKAGES);
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
    setBusy(key); setError(""); setNotice("");
    try { await task(); } catch (caught) { setError(caught instanceof Error ? caught.message : String(caught)); }
    finally { setBusy(""); }
  };

  const createSuite = () => run("create", async () => {
    const cleanExam = examId.trim();
    if (!databaseName.trim()) throw new Error("Cần nhập đúng tên file SQLite mà Golden App mở, ví dụ user_manager.db.");
    if (!cleanExam || !name.trim()) throw new Error("Cần nhập mã đề và tên bộ chấm.");
    if (!allowedPackages.length) throw new Error("Cần ít nhất 1 package được phép, ví dụ flutter, flutter_test.");
    const golden = await api<GoldenApp>("/behavior-authoring/golden-apps", {
      method: "POST",
      body: JSON.stringify({ name: `${name.trim()} - Golden`, exam_id: cleanExam, runtime_url: runtimeUrl.trim() || null, platform: "WEB", ready: Boolean(runtimeUrl.trim()) }),
    });
    const created = await api<Suite>("/behavior-authoring/suites", {
      method: "POST",
      body: JSON.stringify({
        suite_code: `${cleanExam}_RAR`.toUpperCase().replace(/[^A-Z0-9_-]/g, "_"),
        exam_id: cleanExam,
        golden_app_id: golden.id,
        name: name.trim(),
        description: "Bộ chấm Record–Abstract–Replay",
        database_contract: { enabled: true, driver: "sqlite", database_name: databaseName.trim(), ignore_columns: ["created_at", "updated_at"] },
        runtime_config: { allowed_packages: allowedPackages },
      }),
    });
    await refresh(created.id);
    setAvailableSuites((current) => [created, ...current.filter((item) => item.id !== created.id)]);
    window.history.replaceState(null, "", `/teacher/behavior-authoring?suite=${encodeURIComponent(created.id)}`);
    setNotice("Đã tạo bộ chấm. Hãy cung cấp 3 artifact đầu vào rồi record luồng Golden Solution.");
  });

  // Tự chạy khi rời ô (onBlur) — nút "Lưu DB" đã bỏ. Chưa đổi gì thì im lặng thoát,
  // để blur bình thường không bắn toast vô nghĩa. Backend giờ đối chiếu tên này với mã
  // Golden và từ chối nếu lệch, nên gõ sai là thấy lỗi đỏ ngay tại chỗ.
  const saveDatabaseContract = () => suite && databaseNameChanged && run("save-database-contract", async () => {
    const nextDatabaseName = databaseName.trim();
    if (!nextDatabaseName) {
      throw new Error("Tên file SQLite không được để trống.");
    }
    const nextContract: JsonMap = {
      ...(suite.database_contract || {}),
      enabled: true,
      driver: String(suite.database_contract?.driver || "sqlite"),
      database_name: nextDatabaseName,
      ignore_columns: Array.isArray(suite.database_contract?.ignore_columns)
        ? suite.database_contract.ignore_columns
        : ["created_at", "updated_at"],
    };
    // Runner ưu tiên path hơn database_name. Xóa alias cũ để tên vừa lưu
    // chắc chắn là giá trị duy nhất được dùng khi mount DB và replay.
    delete nextContract.path;
    delete nextContract.name;
    await api<Suite>(`/behavior-authoring/suites/${suite.id}`, {
      method: "PUT",
      body: JSON.stringify({ database_contract: nextContract }),
    });
    await refresh(suite.id);
    setNotice(`Đã đổi Database runtime thành ${nextDatabaseName}. Oracle cũ đã hết hiệu lực; cần sinh lại và chạy lại preflight.`);
  });

  const saveAllowedPackages = () => suite && run("save-allowed-packages", async () => {
    if (!allowedPackages.length) throw new Error("Cần ít nhất 1 package được phép, ví dụ flutter, flutter_test.");
    const nextRuntimeConfig: JsonMap = { ...(suite.runtime_config || {}), allowed_packages: allowedPackages };
    await api<Suite>(`/behavior-authoring/suites/${suite.id}`, {
      method: "PUT",
      body: JSON.stringify({ runtime_config: nextRuntimeConfig }),
    });
    await refresh(suite.id);
    setNotice(`Đã lưu ${allowedPackages.length} package (chưa áp dụng vào bài chấm). Không cần record/capture lại oracle, nhưng execution plan đã đổi nên cần chạy lại "Chạy thử trên Golden" ở Bước 5 rồi mới bấm "Publish bộ chấm" được — publish xong contract.json mới thật sự ghi ra đĩa.`);
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
    setValidation(null);
    setRuntimeStatus(null);
    setRuntimeUrl("");
    setDatabaseName("");
    setExamId("");
    setName("");
    window.history.replaceState(null, "", "/teacher/archive");
  };

  const deleteSuite = (selected: Suite) => {
    if (!window.confirm(`Xóa vĩnh viễn bộ chấm “${selected.name}” và toàn bộ record, oracle, artifact, runtime liên quan?`)) return;
    run(`delete-suite-${selected.id}`, async () => {
      await api(`/behavior-authoring/suites/${selected.id}`, { method: "DELETE" });
      setAvailableSuites((current) => current.filter((item) => item.id !== selected.id));
      if (suite?.id === selected.id) closeSuite();
      setNotice(`Đã xóa bộ chấm ${selected.suite_code}.`);
    });
  };

  const uploadArtifact = (type: ArtifactType, file?: File) => {
    if (!suite || !file) return;
    run(`upload-${type}`, async () => {
      const form = new FormData();
      form.append("file", file);
      await api(`/behavior-authoring/suites/${suite.id}/artifacts/${type}`, { method: "POST", body: form });
      await refresh(suite.id);
      setNotice(`Đã lưu ${file.name} thành version mới của ${type}.`);
    });
  };

  const startRecording = () => suite && run("record-start", async () => {
    setEditingScenarioId(null);
    resetRecorderTransport();
    const created = await api<Recording>(`/behavior-authoring/suites/${suite.id}/recordings`, {
      method: "POST", body: JSON.stringify({ name: scenarioName, viewport: { width: viewportWidth, height: viewportHeight, device_pixel_ratio: 1, brightness: cheDoToi ? "dark" : "light" }, initial_state: { reset_storage: true } }),
    });
    activeRecordingId.current = created.id;
    acceptsRecorderEvents.current = created.status === "ACTIVE";
    setRecording(created);
    await refresh(suite.id);
  });

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

  const appendLayoutCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !suite || recording?.status !== "ACTIVE" || !acceptsRecorderEvents.current) {
      setError("Phiên record không còn nhận checkpoint bố cục.");
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
        { bat: true, kind: "component_present", hau: "", nhan: "có", diem: uiGroupWeight, saiSo: 0 },
        { bat: viTriOn, kind: "component_position", hau: "_VITRI", nhan: "đúng vị trí", diem: viTriWeight, saiSo: viTriSaiSo },
        { bat: mauOn, kind: "component_color", hau: "_MAU", nhan: "đúng màu", diem: mauWeight, saiSo: mauSaiSo },
      ].filter((m) => m.bat && Number(m.diem) > 0);

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
              ui_group: {
                id: "G_UI_" + slug + mat.hau,
                name: `Giao diện — ${screen}${mat.hau === "" ? "" : mat.hau === "_VITRI" ? " (vị trí)" : " (màu sắc)"}`,
              },
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

  const maMan = (ten: string) => ten.normalize("NFD").replace(/[̀-ͯ]/g, "")
    .replace(/đ/g, "d").replace(/Đ/g, "D").replace(/[^a-zA-Z0-9]+/g, "_").toUpperCase().replace(/^_+|_+$/g, "");

  // Thêm tiêu chí cho các ICON vừa kiểm kê. Khác đường trên ở chỗ KHÔNG đi qua phiên
  // record (lúc này phiên đã đóng): ghi thẳng vào checkpoints của scenario, backend tự
  // chạy lại capture để nướng vị trí, màu và luật lặp.
  const luuTieuChiIcon = () => {
    if (!suite || !iconScenario) return;
    const chon = (iconInventory || []).filter((it) => it.checked);
    if (chon.length === 0) { setError("Chưa tick icon nào để chấm."); return; }
    run("icon-criteria", async () => {
      const man = uiScreenName.trim() || "Màn hình";
      const slug = maMan(man);
      const cu = iconScenario.checkpoints;
      // Số thứ tự tiếp theo: id checkpoint phải duy nhất trong scenario vì oracle nối
      // vào chính id đó.
      let so = cu.reduce((m, c) => Math.max(m, Number(String(c.id || "").replace(/\D+/g, "")) || 0), 0);
      const cacMat = [
        { bat: true, kind: "component_present", hau: "", nhan: "có", diem: uiGroupWeight, saiSo: 0 },
        { bat: viTriOn, kind: "component_position", hau: "_VITRI", nhan: "đúng vị trí", diem: viTriWeight, saiSo: viTriSaiSo },
        { bat: mauOn, kind: "component_color", hau: "_MAU", nhan: "đúng màu", diem: mauWeight, saiSo: mauSaiSo },
      ].filter((m) => m.bat && Number(m.diem) > 0);

      const them: JsonMap[] = [];
      for (const mat of cacMat) {
        const diemDong = chiaDeu(Number(mat.diem), chon.length);
        chon.forEach((it, i) => {
          so += 1;
          them.push({
            id: `checkpoint_${so}`, kind: mat.kind, checkpoint: true, stage: "ASSERT",
            action: "observe_ui", browser: "flutter_tester",
            target: { icon: it.icon }, visible: true,
            attribute: "icon", attributeValue: it.icon, valueType: "string", value: "",
            name: `${man} — ${mat.nhan} icon ${it.icon}`, weight: diemDong[i],
            ...(mat.saiSo > 0 ? { tolerance_pct: Math.min(50, Math.max(0.5, Number(mat.saiSo))) } : {}),
            ui_group: {
              id: "G_UI_" + slug + mat.hau,
              name: `Giao diện — ${man}${mat.hau === "" ? "" : mat.hau === "_VITRI" ? " (vị trí)" : " (màu sắc)"}`,
            },
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
    return tong + (laChot ? (Number(ev.weight) || 1) : 0);
  }, 0);
  const diemUiConLai = Math.max(0, lamTron(scenarioWeight - diemChotDaGhi));
  const tongCacMuc = lamTron(uiGroupWeight
    + (viTriOn ? viTriWeight : 0)
    + (mauOn ? mauWeight : 0));
  const vuotMucUi = tongCacMuc - diemUiConLai > 0.001;

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

  // Chia deu THAT: bac 0,001, phan du rai tung phan nghin MOT cho cac dong dau nen
  // chenh lech giua dong nang nhat va nhe nhat toi da 0,001. Truoc day lam tron ve
  // 0,25 roi don HET phan du vao dong cuoi — chia 10 cho 3 ra 3,25/3,25/3,5, dong
  // cuoi vo co nang hon han.
  const chiaDeu = (total: number, n: number) => {
    if (n <= 0) return [];
    const nghin = Math.max(n, Math.round(total * 1000)); // moi dong it nhat 0,001
    const moi = Math.floor(nghin / n);
    const du = nghin - moi * n;
    return Array.from({ length: n }, (_, i) => lamTron((moi + (i < du ? 1 : 0)) / 1000));
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
  const diemDaCho = lamTron(diemTinh + diemTungHam.reduce((t, x) => t + x.diem, 0));
  const diemNganSachConLai = Math.max(0, 100 - diemDaCho);

  const chanTrongSoHam = (v: number) => {
    const tran = Math.max(0.5, diemNganSachConLai + (editingScenarioId ? scenarioWeight : 0));
    if (v > tran) {
      setNotice(`Trọng số hàm đã hạ về ${tran} — cả bộ chấm chỉ còn ${diemNganSachConLai}đ trong ngân sách 100.`);
      return tran;
    }
    return Math.max(0.5, v);
  };

  const appendUiCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    const textReady = Boolean(checkpointText.trim() || hiddenCheckpointText.trim());
    const componentReady = Boolean(checkpointLocatorValue.trim());
    if (uiCheckpointType === "text" && !textReady) { setError("Cần nhập text mong đợi cho checkpoint."); return; }
    if (uiCheckpointType === "component" && !componentReady) { setError("Cần nhập giá trị nhận diện thành phần cho checkpoint."); return; }
    if (uiCheckpointType === "widget_state" && !wsWidget.trim()) { setError("Cần chọn loại widget cần đọc trạng thái."); return; }
    if (uiCheckpointType === "text_style" && !tsLocatorValue.trim()) { setError("Cần nhập dòng chữ cần đo kiểu chữ."); return; }
    if (uiCheckpointType === "widget_state" && !wsLocatorValue.trim() && wsProperty !== "ton_tai") { setError("Cần nhập giá trị nhận diện để biết đọc widget nào."); return; }
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

  /**
   * Khai giá trị nhập cho một bước gõ chữ.
   *
   * Đây là nguồn sự thật duy nhất cho nội dung gõ: recorder chỉ ghi được cú chạm vào ô,
   * còn đọc chữ từ DOM của Flutter Web đã hỏng đủ ba kiểu (cắt cụt, mất trắng, nhầm ô)
   * vì Flutter tráo phần tử input giữa chừng và xoá value khi rời ô.
   */
  const suaGiaTriEvent = async (sequence: number, value: string) => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !suite) return;
    try {
      await api(`/behavior-authoring/recordings/${recordingId}/events/${sequence}`, {
        method: "PUT", body: JSON.stringify({ value }),
      });
      await refresh(suite.id);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

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
    const recordingId = activeRecordingId.current;
    if (!recordingId || !recording || !suite) { setError("Không còn phiên record nào gắn với trang — hãy tải lại trang."); return; }
    if (!["ACTIVE", "STOPPED"].includes(recording.status)) {
      setError(`Phiên record đang ở trạng thái ${recording.status}, không sinh testcase được. Hãy tải lại trang.`);
      return;
    }
    if (!scenarioCode.trim() || !scenarioName.trim()) {
      setError("Cần nhập Mã luồng và Tên luồng (ô ngay trên nút này) trước khi sinh testcase.");
      return;
    }
    run("record-stop", async () => {
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
            scenario_code: scenarioCode.trim().toUpperCase(),
            name: scenarioName.trim(),
            weight: scenarioWeight,
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
            const dsIcon = Array.isArray(sinhXong.icons) ? (sinhXong.icons as JsonMap[]) : [];
            if (dsIcon.length > 0 && sinhXong.id) {
              setIconScenario({
                id: String(sinhXong.id),
                ma: String(sinhXong.scenario_code || sinhXong.name || ""),
                checkpoints: Array.isArray(sinhXong.checkpoints) ? (sinhXong.checkpoints as JsonMap[]) : [],
              });
              setIconInventory(dsIcon.map((it) => ({
                icon: String(it.icon || ""),
                count: Number(it.count || 1),
                perRow: Boolean(it.per_row),
                buttonType: String(it.button_type || ""),
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
      .map((p) => ({ ...p, weight: staticSel[p.id].weight, golden: undefined }));
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
    const result = await api<GoldenValidation>(`/behavior-authoring/suites/${suite.id}/validate-golden`, { method: "POST" });
    setValidation(result);
    await refresh(suite.id);
    if (result.status !== "PASSED") throw new Error(result.status === "UNAVAILABLE"
      ? "Không gọi được Docker để kiểm chứng Golden. Hãy bật Docker và thử lại."
      : `Golden preflight chưa pass (${result.passed_checkpoints || 0}/${result.total_checkpoints || 0} checkpoint).`);
    setNotice(`Golden Solution đã pass ${result.passed_checkpoints}/${result.total_checkpoints} checkpoint.`);
  });

  const openCodePreview = (selectedScenarioCode?: string) => suite && run("code-preview", async () => {
    const query = selectedScenarioCode
      ? `?scenarioCode=${encodeURIComponent(selectedScenarioCode)}`
      : "";
    const result = await api<CodePreview>(`/behavior-authoring/suites/${suite.id}/code-preview${query}`);
    setCodePreview(result);
    setPreviewFileName(result.files[0]?.name || "");
  });

  const moChiaDiem = (item: JsonMap) => {
    const id = String(item.id || "");
    if (chiaDiemId === id) { setChiaDiemId(""); return; }
    setChiaDiemId(id);
    setChiaDiemHam(Number(item.weight) || 1);
    const bang: Record<string, number> = {};
    const cha: Record<string, string> = {};
    const tatCa = Array.isArray(item.checkpoints) ? item.checkpoints as JsonMap[] : [];
    tatCa.forEach((c) => {
      bang[String(c.id)] = Number(c.weight) || 1;
      cha[String(c.id)] = String(c.requires || "");
    });
    // Hien DIEM THUC dang nhan, khong phai trong so tho: he thong chia trong so ham
    // theo ty le, nen mo panel phai quy doi san — bo cham chua tung chia (toan 1/1/1)
    // se hien luon muc chia deu, dung y "khong dong gi thi tu chia deu".
    const hanhViMo = tatCa;
    const tongThoMo = hanhViMo.reduce((t, c) => t + (bang[String(c.id)] || 1), 0);
    const tongHam = Number(item.weight) || 1;
    if (hanhViMo.length > 0 && tongThoMo > 0) {
      // Chua ai chia (trong so tho deu bang nhau) -> chia deu that. Da chia roi ->
      // quy doi theo ty le, phan du rai deu chu khong don het vao dong cuoi.
      const deuNhau = hanhViMo.every((c) => (bang[String(c.id)] || 1) === (bang[String(hanhViMo[0].id)] || 1));
      if (deuNhau) {
        const phan = chiaDeu(tongHam, hanhViMo.length);
        hanhViMo.forEach((c, i) => { bang[String(c.id)] = phan[i]; });
      } else {
        const nghin = Math.round(tongHam * 1000);
        const tho = hanhViMo.map((c) => bang[String(c.id)] || 1);
        const san = tho.map((w) => Math.floor(nghin * w / tongThoMo));
        let du = nghin - san.reduce((t, x) => t + x, 0);
        // Rai phan du cho cac dong co phan le lon nhat — chia Hare/Niemeyer, sai so
        // toi da 0,001 mot dong thay vi don het vao mot cho.
        const le = tho
          .map((w, i) => ({ i, le: nghin * w / tongThoMo - san[i] }))
          .sort((a, b) => b.le - a.le);
        for (let j = 0; j < le.length && du > 0; j++, du--) san[le[j].i] += 1;
        hanhViMo.forEach((c, i) => { bang[String(c.id)] = lamTron(Math.max(0.001, san[i]) / 1000); });
      }
    }
    setChiaDiemChot(bang);
    setChiaDiemCha(cha);
  };

  const luuChiaDiem = (item: JsonMap) => {
    if (!suite) return;
    const chots = Array.isArray(item.checkpoints) ? item.checkpoints as JsonMap[] : [];
    const chotDoi = chots.some((c) => (Number(c.weight) || 1) !== (chiaDiemChot[String(c.id)] ?? 1)
      || String(c.requires || "") !== (chiaDiemCha[String(c.id)] ?? ""));
    const hamDoi = (Number(item.weight) || 1) !== chiaDiemHam;
    if (!chotDoi && !hamDoi) { setChiaDiemId(""); return; }
    run("chia-diem", async () => {
      const body: JsonMap = {};
      if (hamDoi) body.weight = chiaDiemHam;
      if (chotDoi) {
        // Gui lai nguyen danh sach checkpoint voi weight moi — moi truong khac giu nguyen
        // (requires, expect, so do chuan...). Doi checkpoints la oracle cu het hieu luc,
        // backend tu chay lai capture.
        body.checkpoints = chots.map((c) => {
          const moi: JsonMap = { ...c, weight: chiaDiemChot[String(c.id)] ?? Number(c.weight) ?? 1 };
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
    run(`revise-scenario-${String(item.id)}`, async () => {
      const viewports = Array.isArray(item.viewports) ? item.viewports as JsonMap[] : [];
      // Chỉ còn khung điện thoại. Scenario cũ có thể còn viewport "desktop" — bỏ qua,
      // sinh lại testcase sẽ ghi đè bằng đúng một khung.
      const phone = viewports.find((viewport) => String(viewport.name || "").toLowerCase() === "phone") || viewports[0];
      setScenarioCode(String(item.scenario_code || ""));
      setScenarioName(String(item.name || item.scenario_code || ""));
      setScenarioWeight(Number(item.weight || 1));
      if (phone) {
        setViewportWidth(Number(phone.width || 412));
        setViewportHeight(Number(phone.height || 915));
      }
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
      if (runtimeOrigin && event.origin !== runtimeOrigin) return;
      if (!event.data || typeof event.data !== "object") return;
      if (event.data.type === "GOLDEN_RECORDER_READY") {
        setRecorderReady(true);
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
          <div className={`fixed right-0 top-1/3 z-40 flex items-start transition-transform ${thuGon ? "translate-x-[13.5rem]" : ""}`}>
            <button onClick={() => setThuGon(!thuGon)} aria-label={thuGon ? "Mở bảng điểm" : "Thu gọn bảng điểm"} className="mt-2 rounded-l-lg border border-r-0 border-slate-300 bg-white px-1.5 py-3 text-slate-600 shadow dark:border-slate-600 dark:bg-slate-800 dark:text-slate-200">{thuGon ? "◀" : "▶"}</button>
            <div className="w-54 rounded-l-xl border border-slate-300 bg-white p-3 shadow-xl dark:border-slate-600 dark:bg-slate-900" style={{ width: "13.5rem" }}>
              <p className="text-xs font-bold uppercase tracking-widest text-slate-500">Ngân sách điểm</p>
              <p className={`mt-1 text-2xl font-bold ${diemDaCho > 100 ? "text-rose-600" : diemDaCho === 100 ? "text-emerald-600" : "text-slate-800 dark:text-slate-100"}`}>{diemDaCho}<span className="text-sm font-medium text-slate-400"> / 100</span></p>
              {diemDaCho < 100 && <p className="text-xs text-amber-600">còn {diemNganSachConLai}đ chưa phân bổ</p>}
              {diemDaCho > 100 && <p className="text-xs font-bold text-rose-600">VƯỢT NGÂN SÁCH {lamTron(diemDaCho - 100)}đ</p>}
              <div className="mt-2 max-h-48 space-y-1 overflow-auto border-t border-slate-200 pt-2 text-xs dark:border-slate-700">
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
            <span className="font-medium">{error || notice}</span>
            <button onClick={() => { setError(""); setNotice(""); }} aria-label="Đóng thông báo" className="ml-1 shrink-0 rounded-md p-1 hover:bg-black/5 dark:hover:bg-white/10"><XCircle size={15} /></button>
          </div>,
          document.body,
        )}

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 1</p><h2 className="text-xl font-bold">{suite ? "Thông tin bộ chấm" : "Chọn hoặc khởi tạo Golden suite"}</h2></div><div className="flex flex-wrap items-center gap-2">{suite && <><span className="rounded-full bg-indigo-100 px-3 py-1 text-sm font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{suite.suite_code} · {suite.status}</span><button onClick={() => deleteSuite(suite)} disabled={Boolean(busy)} className="inline-flex items-center gap-1 rounded-lg border border-rose-400 px-3 py-2 text-sm font-bold text-rose-600 disabled:opacity-50 dark:text-rose-300"><Trash2 size={15} /> Xóa bộ chấm</button><button onClick={closeSuite} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-bold hover:border-indigo-400 dark:border-slate-700">Danh sách bộ chấm</button></>}</div></div>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <input value={suite?.exam_id || examId} disabled={Boolean(suite)} onChange={(e) => setExamId(e.target.value)} placeholder="Mã đề, ví dụ PE_PRM393" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <input value={suite?.name || name} disabled={Boolean(suite)} onChange={(e) => setName(e.target.value)} placeholder="Tên bộ chấm" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <input value={runtimeUrl} disabled={Boolean(suite)} onChange={(e) => setRuntimeUrl(e.target.value)} placeholder="URL Golden App đã deploy (không bắt buộc)" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <div className="min-w-0">
              <label className="sr-only" htmlFor="golden-database-name">Tên file database mở trong bài làm</label>
              <div className="flex min-w-0 gap-2">
                <input id="golden-database-name" value={databaseName} onChange={(e) => setDatabaseName(e.target.value)} onBlur={saveDatabaseContract} onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }} placeholder="Tên file database mở trong bài làm" className="min-w-0 flex-1 rounded-xl border border-slate-300 bg-transparent px-4 py-3 font-mono outline-none focus:border-indigo-500 dark:border-slate-700" />
              </div>
            </div>
          </div>
          {!suite && <><button onClick={createSuite} disabled={Boolean(busy)} className="mt-4 inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-5 py-3 font-bold text-white hover:bg-indigo-500 disabled:opacity-50">{busy === "create" ? <Loader2 className="animate-spin" size={18} /> : <Plus size={18} />} Tạo bộ chấm mới</button>{availableSuites.length > 0 && <div className="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-3">{availableSuites.map((item) => <div key={item.id} className="relative rounded-xl border border-slate-200 transition hover:border-indigo-400 hover:bg-indigo-50/50 dark:border-slate-700 dark:hover:bg-indigo-950/20"><button onClick={() => void openSuite(item)} className="block w-full p-4 pr-14 text-left"><div className="flex items-center justify-between gap-2"><span className="font-bold">{item.name}</span><span className="rounded-full bg-slate-100 px-2 py-1 text-xs font-bold text-slate-600 dark:bg-slate-800 dark:text-slate-300">{item.status}</span></div><p className="mt-1 font-mono text-xs text-indigo-500">{item.suite_code}</p><p className="mt-2 text-xs text-slate-500">Mã đề: {item.exam_id || "chưa gắn"}</p></button><button onClick={() => deleteSuite(item)} disabled={Boolean(busy)} title="Xóa bộ chấm" className="absolute bottom-3 right-3 rounded-lg border border-rose-300 p-2 text-rose-500 hover:bg-rose-50 disabled:opacity-40 dark:border-rose-800 dark:hover:bg-rose-950"><Trash2 size={16} /></button></div>)}</div>}</>}
        </section>

        {suite && <>
          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="mb-4 flex flex-wrap items-end justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 2</p><h2 className="text-xl font-bold">Bảy thành phần của bộ chấm</h2><p className="mt-1 text-sm text-slate-500">Chỉ cần tải Database phát sinh viên, Database ẩn và Golden Solution. Khi kết thúc record, hệ thống tự sinh Automation Record, Testcase Definition và replay Golden với Database ẩn để capture Output Database.</p></div><span className="text-sm font-semibold">{7 - (readiness?.missing.length || 0)}/7 hợp lệ</span></div>
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              {ARTIFACTS.map((item) => {
                const current = activeByType[item.type]; const Icon = item.icon; const uploading = busy === `upload-${item.type}`;
                return <div key={item.type} className={`relative rounded-xl border p-4 ${item.owner === "system" ? "border-violet-200 bg-violet-50/60 dark:border-violet-900 dark:bg-violet-950/20" : "border-slate-200 dark:border-slate-700"}`}>
                  <div className="flex items-start justify-between"><Icon size={21} className={item.owner === "system" ? "text-violet-500" : "text-indigo-500"} />{current ? <CheckCircle2 size={19} className="text-emerald-500" /> : <Circle size={19} className="text-slate-300" />}</div>
                  <h3 className="mt-3 font-bold">{item.title}</h3><p className="mt-1 min-h-10 text-xs text-slate-500">{item.hint}</p>
                  {current && <p className="mt-2 truncate text-xs font-medium text-emerald-600" title={current.sha256}>{current.file_name} · v{current.version} · {bytes(current.size_bytes)}</p>}
                  {item.owner === "teacher" ? <label className="mt-3 inline-flex cursor-pointer items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-xs font-bold hover:border-indigo-400 dark:border-slate-700">{uploading ? <Loader2 size={15} className="animate-spin" /> : <UploadCloud size={15} />} {current ? "Tạo version mới" : "Chọn file"}<input type="file" accept={item.accept} className="hidden" onChange={(e) => uploadArtifact(item.type, e.target.files?.[0])} /></label> : <span className="mt-3 inline-block rounded-lg bg-violet-100 px-3 py-2 text-xs font-bold text-violet-700 dark:bg-violet-900/50 dark:text-violet-200">Tự động sinh</span>}
                </div>;
              })}
            </div>
          </section>

          <section className="rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <button onClick={() => setMoGoiChoPhep((v) => !v)} className="flex w-full items-center gap-2 px-5 py-3 text-left">
              <ChevronDown size={16} className={`shrink-0 text-slate-400 transition-transform ${moGoiChoPhep ? "" : "-rotate-90"}`} />
              <h3 className="font-bold">Package bài sinh viên được phép dùng</h3>
              <span className="text-xs text-slate-500">{allowedPackages.length} gói</span>
              {allowedPackagesChanged && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-700 dark:bg-amber-950 dark:text-amber-300">chưa lưu</span>}
            </button>
            {moGoiChoPhep && <div className="border-t border-slate-200 px-5 py-4 dark:border-slate-700">
              {goiCuaAnh && !goiCuaAnh.imageRead && (
                <p className="mb-3 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-300">
                  Chưa đọc được thư viện của ảnh chấm (Docker chưa bật hoặc ảnh chưa build). Cột khả dụng vì thế đang trống; danh sách bên trái là những gì bộ đề đã lưu.
                </p>
              )}
              <div className="grid gap-3 sm:grid-cols-2">
                {([["dung", "Package được dùng", goiDangDung], ["kha", "Các package khả dụng", goiKhaDung]] as const).map(([ma, tieuDe, danhSach]) => (
                  <div key={ma}>
                    <p className="mb-1.5 text-xs font-bold uppercase tracking-widest text-slate-500">{tieuDe} ({danhSach.length})</p>
                    <div className="flex min-h-24 flex-wrap content-start gap-1.5 rounded-xl border border-dashed border-slate-300 p-2 dark:border-slate-700">
                      {danhSach.length === 0 && <span className="px-1 text-xs text-slate-400">trống</span>}
                      {danhSach.map((ten) => {
                        const loi = GOI_LOI.includes(ten);
                        return (
                          <button
                            key={ten}
                            type="button"
                            disabled={loi}
                            onClick={() => batTatPackage(ten)}
                            title={loi ? "Gói lõi, bỏ đi thì không bài nào biên dịch được" : (ma === "dung" ? "Bấm để bỏ khỏi đề" : "Bấm để cho phép")}
                            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 font-mono text-xs ${loi
                              ? "cursor-not-allowed bg-slate-100 text-slate-400 dark:bg-slate-800"
                              : ma === "dung"
                                ? "bg-indigo-100 font-semibold text-indigo-700 hover:bg-rose-100 hover:text-rose-700 dark:bg-indigo-950 dark:text-indigo-300"
                                : "border border-slate-300 text-slate-500 hover:border-indigo-400 hover:text-indigo-600 dark:border-slate-600 dark:text-slate-400"}`}
                          >
                            {ten}
                            {loi && <span className="text-[10px]">lõi</span>}
                            {!loi && tenKhaiThang.length > 0 && !tenKhaiThang.includes(ten)
                              && <span className="text-[10px] opacity-60" title="Không khai trong pubspec của ảnh chấm nhưng vẫn import được vì đi kèm một thư viện khác. Vì thế danh sách này dài hơn trang Thư viện chấm.">kéo theo</span>}
                          </button>
                        );
                      })}
                    </div>
                    {ma === "kha" && (soKeoTheoAn > 0 || moGoiKeoTheo) && (
                      <button type="button" onClick={() => setMoGoiKeoTheo((v) => !v)} className="mt-1.5 text-xs text-slate-500 hover:text-indigo-500">
                        {moGoiKeoTheo ? "Ẩn bớt gói kéo theo" : `Hiện thêm ${soKeoTheoAn} gói kéo theo`}
                      </button>
                    )}
                  </div>
                ))}
              </div>
              <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
                <p className="max-w-2xl text-xs text-slate-500">
                  Chỉ những gói bên trái mới được import trong bài; bài dùng gói khác bị chặn ngay trước khi biên dịch và
                  chấm 0đ. Cột phải là thư viện có thật trong ảnh chấm, nên chọn gì cũng chạy được. Sửa mục này không làm
                  mất oracle đã capture.
                </p>
                <button
                  type="button"
                  onClick={saveAllowedPackages}
                  disabled={!allowedPackagesChanged || !allowedPackages.length || Boolean(busy)}
                  className="inline-flex shrink-0 items-center gap-2 rounded-xl border border-indigo-400 px-3 py-2 text-sm font-bold text-indigo-600 hover:bg-indigo-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-indigo-700 dark:text-indigo-300 dark:hover:bg-indigo-950/40"
                >
                  {busy === "save-allowed-packages" ? <Loader2 size={16} className="animate-spin" /> : <ShieldCheck size={16} />}
                  Lưu package
                </button>
              </div>
            </div>}
          </section>

          <section className="grid min-w-0 gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)]">
            <div className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
              <p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 3</p><h2 className="text-xl font-bold">Thao tác trên Golden App</h2>
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
                      <input type="number" min={0.001} step={0.001} value={uiGroupWeight} onChange={(e) => setUiGroupWeight(Math.max(0, Number(e.target.value)))} className="w-20 rounded-lg border border-slate-300 bg-transparent px-2 py-1 text-sm dark:border-slate-600" />
                    </label>
                    <input value={uiScreenName} onChange={(e) => setUiScreenName(e.target.value)} placeholder="Tên màn (vd: Màn danh sách)" className="rounded-lg border border-slate-300 px-2 py-1 text-sm dark:border-slate-600 dark:bg-slate-800" />
                    <span className={`text-xs font-bold ${vuotMucUi ? "text-rose-600" : "text-slate-500"}`}>Đã chia {tongCacMuc}/{diemUiConLai}đ của hàm{vuotMucUi ? " — VƯỢT, hạ bớt mới lưu được" : ""}</span>
                    <button onClick={saveUiCriteria} disabled={Boolean(busy) || vuotMucUi || !uiInventory.some((it) => it.checked)} className="rounded-lg bg-emerald-600 px-3 py-1.5 text-sm font-bold text-white disabled:opacity-40">
                      Lưu {uiInventory.filter((it) => it.checked).length * (1 + (viTriOn ? 1 : 0) + (mauOn ? 1 : 0))} tiêu chí
                    </button>
                    <button onClick={() => setUiInventory(null)} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-bold text-slate-600 dark:border-slate-600 dark:text-slate-300">Đóng</button>
                  </div>
                  <label className="mt-2 flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border border-dashed border-emerald-400 px-2 py-1.5 text-sm dark:border-emerald-700">
                    <input type="checkbox" checked={viTriOn} onChange={() => setViTriOn((v) => !v)} />
                    <span className="font-bold">Chấm vị trí từng thành phần</span>
                    <span className="text-[11px] text-slate-500">(so tâm thành phần với Golden; sai số tính theo % chiều rộng/cao màn)</span>
                    <span className="ml-auto flex items-center gap-1 text-xs">
                      <input type="number" min={0.001} step={0.001} value={viTriWeight} onChange={(e) => setViTriWeight(Math.max(0, Number(e.target.value)))} className="w-16 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" /> điểm ·
                      sai số <input type="number" min={0.5} max={50} step={0.5} value={viTriSaiSo} onChange={(e) => setViTriSaiSo(Number(e.target.value))} className="w-14 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" />%
                    </span>
                  </label>
                  <label className="mt-1 flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border border-dashed border-emerald-400 px-2 py-1.5 text-sm dark:border-emerald-700">
                    <input type="checkbox" checked={mauOn} onChange={() => setMauOn((v) => !v)} />
                    <span className="font-bold">Chấm màu từng thành phần</span>
                    <span className="text-[11px] text-slate-500">(so màu chính với Golden; sai số tính theo % của 255 trên từng kênh R/G/B)</span>
                    <span className="ml-auto flex items-center gap-1 text-xs">
                      <input type="number" min={0.001} step={0.001} value={mauWeight} onChange={(e) => setMauWeight(Math.max(0, Number(e.target.value)))} className="w-16 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" /> điểm ·
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
              {previewUrl ? <div className="mt-4 w-full overflow-auto rounded-xl border border-slate-300 bg-slate-100 p-3 dark:border-slate-700 dark:bg-slate-950"><iframe ref={goldenFrame} title="Golden App" src={previewUrl} style={{ width: Math.min(viewportWidth, 900), minWidth: Math.min(viewportWidth, 900), height: Math.min(viewportHeight, 700) }} className="mx-auto block rounded-lg border border-slate-300 bg-white dark:border-slate-700" /></div> : <div className="mt-4 flex h-[300px] flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 text-center dark:border-slate-700"><MonitorPlay size={42} className="text-slate-400" /><p className="mt-3 font-bold">Golden Solution chưa được build để thao tác</p><p className="mt-1 max-w-md text-sm text-slate-500">Upload Golden ZIP rồi bấm “Build & mở Golden”. Hệ thống tự host app và ghi click/nhập liệu bằng semantic locator.</p></div>}
            </div>

            <div ref={authoringPanel} className="min-w-0 scroll-mt-24 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
              <div className="flex items-center justify-between"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 4</p><h2 className="text-xl font-bold">Record → Abstract</h2></div>{editingScenarioId && <span className="mr-3 rounded-full bg-indigo-100 px-2.5 py-1 text-xs font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300" title="Đang sửa một scenario đã có. Bước cũ nằm sẵn trong danh sách dưới; bấm Hủy sửa ở hàng nút cuối để thoát.">Đang sửa {scenarioCode || "scenario"}</span>}{recording ? <span className={`flex items-center gap-2 text-sm font-bold ${recording.status === "ACTIVE" ? "text-rose-500" : "text-amber-500"}`}><span className={`h-2 w-2 rounded-full ${recording.status === "ACTIVE" ? "animate-pulse bg-rose-500" : "bg-amber-500"}`} /> {recording.status === "ACTIVE" ? "RECORDING" : "Chờ sinh testcase"}</span> : <span className="text-sm text-slate-500">Chưa ghi</span>}</div>
              {iconInventory && iconScenario && (
                <div className="mt-4 rounded-xl border border-teal-300 bg-teal-50/40 p-3 dark:border-teal-800 dark:bg-teal-950/20">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-bold">Icon máy chấm thấy ở luồng {iconScenario.ma} — tick icon được tính điểm</span>
                    <input value={uiScreenName} onChange={(e) => setUiScreenName(e.target.value)} placeholder="Tên màn (vd: Màn danh sách)" className="rounded-lg border border-slate-300 px-2 py-1 text-sm dark:border-slate-600 dark:bg-slate-800" />
                    <button onClick={luuTieuChiIcon} disabled={Boolean(busy) || !iconInventory.some((it) => it.checked)} className="rounded-lg bg-teal-600 px-3 py-1.5 text-sm font-bold text-white disabled:opacity-40">
                      Thêm {iconInventory.filter((it) => it.checked).length * (1 + (viTriOn ? 1 : 0) + (mauOn ? 1 : 0))} tiêu chí
                    </button>
                    <button onClick={() => { setIconInventory(null); setIconScenario(null); }} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-bold text-slate-600 dark:border-slate-600 dark:text-slate-300">Đóng</button>
                  </div>
                  <p className="mt-2 text-[11px] text-slate-500">
                    Dành cho nút chỉ có hình (Thêm, Xóa): chấm đúng icon, đúng chỗ, đúng màu mà KHÔNG bắt sinh viên gắn nhãn ngữ nghĩa chỉ để máy tìm.
                    Bảng này do máy chấm đo trên cây widget lúc capture, không phải quét DOM — nút không nhãn thì DOM web không thấy.
                    Icon lặp ở mỗi dòng danh sách được chấm ở <b>mọi dòng</b>: thiếu một dòng là trượt, còn vị trí đo tương đối trong dòng nên dòng đầu và dòng cuối cùng một chuẩn.
                  </p>
                  <div className="mt-2 grid max-h-56 gap-1 overflow-auto pr-1">
                    {iconInventory.map((it, i) => (
                      <label key={it.icon} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1 text-sm hover:bg-teal-100/60 dark:hover:bg-teal-900/30">
                        <input type="checkbox" checked={it.checked} onChange={() => setIconInventory((prev) => prev ? prev.map((x, j) => (j === i ? { ...x, checked: !x.checked } : x)) : prev)} />
                        <span className="rounded bg-teal-100 px-1.5 py-0.5 font-mono text-[10px] font-bold text-teal-700 dark:bg-teal-950 dark:text-teal-300">icon</span>
                        <span className="truncate font-mono">{it.icon}</span>
                        {it.count > 1 && (
                          <span title="Icon này xuất hiện nhiều lần. Có 'lặp theo dòng' nghĩa là mỗi dòng danh sách đúng một cái — máy sẽ chấm đủ mọi dòng." className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold text-amber-700 dark:bg-amber-950 dark:text-amber-300">
                            ×{it.count}{it.perRow ? " · lặp theo dòng" : ""}
                          </span>
                        )}
                        {it.buttonType && <span className="ml-auto text-[10px] text-slate-400">{it.buttonType}</span>}
                      </label>
                    ))}
                  </div>
                  <p className="mt-2 text-[11px] text-slate-500">Ba mặt (có mặt · vị trí · màu) và số điểm lấy theo đúng các ô đã đặt ở bảng “Quét thành phần UI”. Lưu xong hệ thống tự capture lại để đo giá trị chuẩn.</p>
                </div>
              )}
              <div className="mt-4 grid gap-3 sm:grid-cols-3"><input value={scenarioCode} disabled={Boolean(editingScenarioId)} onChange={(e) => setScenarioCode(e.target.value)} placeholder="Mã luồng (vd: ADD, EDIT, FILTER_ALL)" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700" /><input value={scenarioName} onChange={(e) => setScenarioName(e.target.value)} placeholder="Tên luồng (vd: Thêm khoản chi)" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><input type="number" min={0.1} step={0.5} value={scenarioWeight} onChange={(e) => setScenarioWeight(chanTrongSoHam(Number(e.target.value)))} aria-label="Trọng số scenario" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /></div>
              <div className="mt-2 grid gap-2 sm:grid-cols-2"><label className="text-xs font-semibold text-slate-500">Rộng màn (dp)<input type="number" min={240} value={viewportWidth} onChange={(e) => setViewportWidth(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 dark:border-slate-700 dark:text-slate-100" /></label><label className="text-xs font-semibold text-slate-500">Cao màn (dp)<input type="number" min={320} value={viewportHeight} onChange={(e) => setViewportHeight(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 dark:border-slate-700 dark:text-slate-100" /></label><label className="flex items-center gap-2 text-xs font-semibold text-slate-500 sm:col-span-2"><input type="checkbox" checked={cheDoToi} onChange={() => setCheDoToi((v) => !v)} /> Chấm hàm này ở chế độ tối <span className="font-normal">— engine đặt platformBrightness = dark trước khi boot; bài có darkTheme sẽ tự đổi, giá trị chuẩn màu/kiểu chữ đo ở chế độ tối. Khung Golden bên trên vẫn hiện sáng.</span></label></div>
              <p className="mt-2 text-xs font-semibold text-slate-600 dark:text-slate-300">Khung máy Android, tính bằng dp. Mọi phép chấm bố cục đều đo bằng dp nên không cần pixel.</p>
              {!recording ? <><button onClick={startRecording} disabled={!recordingInputsReady || Boolean(busy)} className="mt-4 inline-flex items-center gap-2 rounded-xl bg-rose-600 px-4 py-2.5 font-bold text-white disabled:opacity-40"><Radio size={18} /> Bắt đầu record</button>{!recordingInputsReady && <p className="mt-2 text-xs text-amber-600">Cần đủ Database phát sinh viên, Database ẩn và Golden Solution.</p>}</> : <>
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
                      <button onClick={() => setCheckpointMode("layout")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "layout" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>Bố cục</button>
                    </div>
                  </div>
                  {checkpointMode === "ui" ? (
                    <div className="mt-3 space-y-2">
                      <select value={uiCheckpointType} onChange={(e) => setUiCheckpointType(e.target.value as "text" | "component" | "widget_state" | "text_style" | "theme_value" | "no_overflow" | "no_exception")} className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                        <option value="text">Nội dung text xuất hiện / không xuất hiện</option>
                        <option value="component">Thành phần UI và trạng thái semantic</option>
                        <option value="widget_state">Trạng thái thật bên trong widget</option>
                        <option value="text_style">Kiểu chữ của một dòng chữ</option>
                        <option value="theme_value">Bảng chủ đề của app (màu, phông, Material 3)</option>
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
                      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
                        <select value={layoutFirstLocator} onChange={(e) => setLayoutFirstLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item} A</option>)}</select>
                        <input value={layoutFirstValue} onChange={(e) => setLayoutFirstValue(e.target.value)} placeholder="Thành phần A" list="golden-layout-targets" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <select value={layoutSecondLocator} onChange={(e) => setLayoutSecondLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item} B</option>)}</select>
                        <input value={layoutSecondValue} onChange={(e) => setLayoutSecondValue(e.target.value)} placeholder="Thành phần B" list="golden-layout-targets" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                      </div>
                      <datalist id="golden-layout-targets">{(uiInventory || []).map((item) => <option key={`${item.attribute}:${item.value}`} value={item.value}>{item.attribute}</option>)}</datalist>
                      <div className="grid gap-2 sm:grid-cols-[1.5fr_1fr_auto]">
                        <select value={layoutRelation} onChange={(e) => setLayoutRelation(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                          <option value="auto">Tự suy ra quan hệ từ Golden</option><option value="above">A nằm trên B</option><option value="below">A nằm dưới B</option><option value="left_of">A bên trái B</option><option value="right_of">A bên phải B</option><option value="same_row">A và B cùng hàng</option><option value="same_column">A và B cùng cột</option><option value="inside">A nằm trong B</option><option value="contains">A chứa B</option><option value="not_overlap">A và B không chồng lấp</option><option value="overlap">A và B chồng lấp</option><option value="wider_than">A rộng hơn B</option><option value="taller_than">A cao hơn B</option>
                        </select>
                        <label className="flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-700">Sai số %<input type="number" min={0} max={50} step={0.5} value={layoutTolerance} onChange={(e) => setLayoutTolerance(Number(e.target.value))} className="min-w-0 flex-1 bg-transparent text-right outline-none" /></label>
                        <button onClick={appendLayoutCheckpoint} title="Lưu checkpoint quan hệ bố cục" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>
                      <p className="text-xs text-slate-500">Không so pixel. Runner chỉ đo Rect logic của hai semantic element. Hãy “Chụp semantic UI” trước để lấy gợi ý target; chế độ tự động sẽ nướng quan hệ đo được từ Golden vào oracle.</p>
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
                  const nhinThay = ["label", "text", "hint", "text_prefix", "tooltip", "valueKey"]
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
                      {dinhDanh && <span className="shrink-0 rounded bg-teal-100 px-1.5 py-0.5 font-mono text-[10px] font-bold text-teal-700 dark:bg-teal-950 dark:text-teal-300" title="Định danh Semantics(identifier:) — máy chấm tìm bằng nó trước, nhãn/chữ cạnh bên là đường lui.">{dinhDanh}</span>}
                      {laThaoTac && !dinhDanh && Object.keys(target).length > 0 && <span className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold text-amber-700 dark:bg-amber-950 dark:text-amber-300" title="Bước này còn tìm bằng nhãn/chữ. Gắn Semantics(identifier:) vào Golden rồi Sinh lại testcase là máy nướng vào.">chưa có định danh</span>}
                      {nhinThay && <span className="min-w-0 flex-1 truncate text-slate-500 dark:text-slate-400" title={nhinThay}>{nhinThay}</span>}
                      <button onClick={() => deleteRecordedEvent(sequence)} disabled={Boolean(busy)} title="Xóa thao tác/checkpoint này" className="ml-auto shrink-0 rounded-md p-1.5 text-rose-500 hover:bg-rose-50 disabled:opacity-40 dark:hover:bg-rose-950/40"><Trash2 size={14} /></button>
                    </div>
                    {(hienGiaTri || deltaText || expectation) && <div className="mt-2 grid gap-1.5 pl-8 text-slate-600 dark:text-slate-300">
                      {hienGiaTri && <div className="flex min-w-0 items-start gap-2">
                        <span className="w-20 shrink-0 text-slate-400">Giá trị nhập</span>
                        {laGoChu ? <input
                          defaultValue={String(item.value || "")}
                          placeholder="Gõ nội dung cho ô này"
                          title="Nội dung sẽ được gõ vào ô này lúc chấm. Sửa ở đây nếu recorder ghi chưa đúng."
                          onBlur={(e) => { const v = e.target.value; if (v !== String(item.value || "")) void suaGiaTriEvent(sequence, v); }}
                          onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }}
                          className={`w-64 shrink-0 rounded border bg-transparent px-2 py-1 ${String(item.value || "").trim() ? "border-slate-300 dark:border-slate-600" : "border-amber-400"}`}
                        /> : <span className="min-w-0 break-words font-semibold text-slate-700 dark:text-slate-200">{giaTri}</span>}
                      </div>}
                      {deltaText && <div className="flex min-w-0 items-start gap-2"><span className="w-20 shrink-0 text-slate-400">Độ cuộn</span><span>{deltaText}</span></div>}
                      {expectation && <div className="flex min-w-0 items-start gap-2"><span className="w-20 shrink-0 text-slate-400">Kỳ vọng</span><span className="min-w-0 break-words">{expectation}</span></div>}
                    </div>}
                  </div>;
                })}</div>
                {recording.status === "STOPPED" && error && <div className="mt-4 rounded-xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700 dark:border-rose-800 dark:bg-rose-950/30 dark:text-rose-200">Không thể sinh testcase: {error}. Phiên vẫn được giữ để bạn thử lại hoặc hủy.</div>}
                {buocThieuGiaTri > 0 && <p className="mt-3 rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-xs font-bold text-rose-700 dark:border-rose-800 dark:bg-rose-950/30 dark:text-rose-200">Còn {buocThieuGiaTri} bước gõ chữ chưa khai nội dung (ô viền đỏ ở trên). Điền xong mới sinh được testcase — nếu để trống, lúc chấm sẽ gõ chuỗi rỗng và mọi tiêu chí phía sau trượt theo.</p>}
                <div className="mt-4 flex flex-wrap gap-2"><button onClick={stopAndAbstract} disabled={Boolean(busy) || buocThieuGiaTri > 0} className="inline-flex items-center gap-2 rounded-xl bg-slate-800 px-4 py-2.5 font-bold text-white disabled:opacity-40 dark:bg-slate-700">{busy === "record-stop" ? <Loader2 size={17} className="animate-spin" /> : <Square size={17} />} {busy === "record-stop" ? "Đang replay Golden và sinh Output DB…" : recording.status === "STOPPED" ? "Thử sinh testcase lại" : editingScenarioId ? "Lưu sửa đổi và sinh lại testcase" : "Dừng, capture oracle và sinh testcase"}</button><button onClick={cancelActiveRecording} disabled={Boolean(busy)} className="rounded-xl border border-rose-300 px-4 py-2.5 font-bold text-rose-600 disabled:opacity-40 dark:border-rose-900">{editingScenarioId ? "Cancel" : "Cancel"}</button></div>
              </>}
            </div>
          </section>

          {staticRules && <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Luật tĩnh</p>
                <h2 className="text-xl font-bold">Kiến trúc &amp; chất lượng mã</h2>
                <p className="mt-1 text-sm text-slate-500">Chấm bằng soi mã nguồn (cấu trúc thư mục, import, lint) — không cần chạy app, chấm được cả bài không biên dịch. Luật phải ĐẠT trên chính Golden Solution: badge đỏ nghĩa là đáp án mẫu không thỏa nên không thể đem luật đó chấm sinh viên.</p>
              </div>
              <div className="flex items-center gap-3">
                <span className="rounded-full bg-indigo-100 px-3 py-1 text-sm font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{(staticRules.presets || []).reduce((sum, p) => sum + (staticSel[p.id]?.checked ? Number(staticSel[p.id].weight || 0) : 0), 0)} điểm</span>
                <button onClick={saveStaticRules} disabled={Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 font-bold text-white disabled:opacity-40">{busy === "static-rules" ? <Loader2 size={17} className="animate-spin" /> : <Check size={17} />} Lưu luật tĩnh</button>
              </div>
            </div>
            {!staticRules.golden_available && <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-700 dark:bg-amber-950/40 dark:text-amber-300">Chưa có Golden Solution — upload ở Bước 2 trước, hệ thống mới đối chứng được luật.</p>}
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
                      <input type="number" min={0.5} step={0.5} value={sel.weight} disabled={!sel.checked || Boolean(busy)}
                        onChange={(e) => setStaticSel((prev) => ({ ...prev, [p.id]: { checked: prev[p.id]?.checked ?? false, weight: Number(e.target.value) } }))}
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
            <div className="flex flex-wrap items-center justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 5</p><h2 className="text-xl font-bold">Kiểm chứng Golden và publish</h2><p className="mt-1 text-sm text-slate-500">Còn thiếu: {readiness?.missing.join(", ") || "không"}. Preflight chạy chính plan trên Golden; publish chỉ mở khi toàn bộ checkpoint pass.</p><p className={`mt-2 text-sm font-bold ${validation?.status === "PASSED" && validation.current ? "text-emerald-600" : "text-amber-600"}`}>Preflight: {validation?.status || "NOT_RUN"}{validation?.total_checkpoints !== undefined ? ` · ${validation.passed_checkpoints}/${validation.total_checkpoints}` : ""}{validation && !validation.current ? " · plan đã thay đổi" : ""}</p></div><div className="flex flex-wrap gap-2"><button onClick={() => openCodePreview()} disabled={!suite.scenarios?.length || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl border border-slate-300 px-5 py-3 font-bold text-slate-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-700 dark:text-slate-200">{busy === "code-preview" ? <Loader2 className="animate-spin" size={18} /> : <Code2 size={18} />} Xem code bộ chấm</button><button onClick={validateGolden} disabled={!readiness?.ready || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl border border-indigo-300 px-5 py-3 font-bold text-indigo-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-indigo-700 dark:text-indigo-300">{busy === "validate-golden" ? <Loader2 className="animate-spin" size={18} /> : <Play size={18} />} Chạy thử trên Golden</button><button onClick={publish} disabled={!readiness?.ready || !validation?.current || validation.status !== "PASSED" || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-5 py-3 font-bold text-white disabled:cursor-not-allowed disabled:opacity-40">{busy === "publish" ? <Loader2 className="animate-spin" size={18} /> : <Send size={18} />} Publish bộ chấm</button></div></div>
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
              {(suite.scenarios || []).map((item, index) => <div key={String(item.id || index)} className="rounded-xl border border-slate-200 px-4 py-3 dark:border-slate-700"><div className="flex items-center justify-between gap-2"><span className="font-bold">{String(item.name || item.scenario_code)}</span><span className="rounded-full bg-indigo-100 px-2 py-1 text-xs font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{String(item.weight)} điểm</span></div><p className="mt-1 font-mono text-[11px] text-indigo-500">{String(item.scenario_code || "")}</p>{(() => {
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
                  const tongHanhVi = hanhVi.reduce((t, c) => t + (chiaDiemChot[String(c.id)] ?? 1), 0);
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
                        <input type="number" min={0.001} step={0.001} value={w} onChange={(e) => setChiaDiemChot({ ...chiaDiemChot, [id]: Math.max(0.001, lamTron(Number(e.target.value))) })} className="w-16 shrink-0 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" />
                      </div>
                      {conCua(id).map((k) => dong(k, false, sau + 1))}
                    </div>;
                  };
                  return <div className="mt-2 space-y-1.5 rounded-lg border border-indigo-200 bg-indigo-50/40 p-2 dark:border-indigo-900 dark:bg-indigo-950/20">
                    <label className="flex items-center justify-between text-xs font-bold">Trọng số hàm
                      <input type="number" min={0.5} step={0.5} value={chiaDiemHam} onChange={(e) => setChiaDiemHam(Math.max(0.5, Number(e.target.value)))} className="w-20 rounded border border-slate-300 bg-transparent px-1.5 py-0.5 dark:border-slate-600" />
                    </label>
                    {dsGoc.map((c) => dong(c, false, 0))}
                    {hanhVi.length > 0 && Math.abs(tongHanhVi - chiaDiemHam) > 0.0005 && <p className="text-[11px] font-bold text-rose-600">Đã chia {lamTron(tongHanhVi)}/{chiaDiemHam}đ — tổng điểm checkpoint phải bằng đúng điểm của hàm mới lưu được.</p>}
                    <button onClick={() => luuChiaDiem(item)} disabled={Boolean(busy) || (hanhVi.length > 0 && Math.abs(tongHanhVi - chiaDiemHam) > 0.0005)} className="w-full rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-bold text-white disabled:opacity-40">
                      {busy === "chia-diem" ? "Đang lưu (đổi điểm checkpoint sẽ capture lại ~1 phút)…" : "Lưu chia điểm"}
                    </button>
                  </div>;
                })()}
                <div className="mt-3 flex flex-wrap gap-2"><button onClick={() => openCodePreview(String(item.scenario_code || ""))} disabled={Boolean(busy)} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-2.5 py-1.5 text-xs font-bold text-slate-700 hover:border-indigo-400 disabled:opacity-40 dark:border-slate-700 dark:text-slate-200"><Code2 size={14} /> Xem testcase</button><button onClick={() => moChiaDiem(item)} disabled={Boolean(busy) || Boolean(recording)} title="Sửa trọng số hàm và điểm từng checkpoint đã lưu" className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 px-2.5 py-1.5 text-xs font-bold text-emerald-700 hover:bg-emerald-50 disabled:opacity-40 dark:border-emerald-800 dark:text-emerald-300 dark:hover:bg-emerald-950"><Database size={14} /> Chia điểm</button><button onClick={() => openScenarioEditor(item)} disabled={Boolean(busy) || Boolean(recording)} title={recording ? "Hãy kết thúc phiên đang soạn trước" : "Nạp lại các bước vào khung record để chỉnh sửa"} className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-300 px-2.5 py-1.5 text-xs font-bold text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 dark:border-indigo-800 dark:text-indigo-300 dark:hover:bg-indigo-950"><Pencil size={14} /> Sửa thao tác</button><button onClick={() => deleteScenario(item)} disabled={Boolean(busy) || Boolean(recording)} className="inline-flex items-center gap-1.5 rounded-lg border border-rose-300 px-2.5 py-1.5 text-xs font-bold text-rose-600 hover:bg-rose-50 disabled:opacity-40 dark:border-rose-900 dark:hover:bg-rose-950"><Trash2 size={14} /> Xóa</button></div></div>)}
              {!suite.scenarios?.length && <p className="text-sm text-slate-500">Chưa có scenario. Hãy record ít nhất một luồng và sinh testcase.</p>}
            </div>
          </section>
        </>}
        {codePreview && previewFile && <div className="fixed inset-0 z-[100] flex items-center justify-center bg-slate-950/75 p-3 backdrop-blur-sm sm:p-6">
          <div className="flex h-[min(900px,94vh)] w-full max-w-[1500px] min-w-0 flex-col overflow-hidden rounded-2xl border border-slate-700 bg-slate-950 text-slate-100 shadow-2xl">
            <div className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-800 px-5 py-4">
              <div className="min-w-0"><p className="text-xs font-bold uppercase tracking-widest text-indigo-400">Code sinh theo bộ Golden</p><h2 className="mt-1 truncate text-xl font-bold">{codePreview.suite_code}{codePreview.selected_scenario_code ? ` · ${codePreview.selected_scenario_code}` : ""}</h2><p className="mt-1 text-sm text-slate-400">{codePreview.scenario_count} scenario · {codePreview.criterion_count} đầu điểm. File hiển thị được sinh từ cùng engine dùng khi publish.</p></div>
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
