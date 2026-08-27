"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  Check, CheckCircle2, Circle, Code2, Copy, Database, FileArchive, FileJson,
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
interface Suite { id: string; suite_code: string; exam_id?: string; golden_app_id: string; name: string; status: string; database_contract?: JsonMap; recordings?: Recording[]; scenarios?: JsonMap[] }
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

const ACTIONS = ["boot", "tap", "enter_text", "clear_text", "scroll", "back", "restart", "wait_until"];
const LOCATORS = ["semanticId", "valueKey", "label", "hint", "text"];
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
  const [examId, setExamId] = useState(() => search.get("exam") || "");
  const [name, setName] = useState("");
  const [runtimeUrl, setRuntimeUrl] = useState("");
  const [databaseName, setDatabaseName] = useState("");
  const [suite, setSuite] = useState<Suite | null>(null);
  const [availableSuites, setAvailableSuites] = useState<Suite[]>([]);
  const [recording, setRecording] = useState<Recording | null>(null);
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [validation, setValidation] = useState<GoldenValidation | null>(null);
  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus | null>(null);
  const [recorderReady, setRecorderReady] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [action, setAction] = useState("tap");
  const [locator, setLocator] = useState("semanticId");
  const [locatorValue, setLocatorValue] = useState("");
  const [inputValue, setInputValue] = useState("");
  const [checkpointText, setCheckpointText] = useState("");
  const [hiddenCheckpointText, setHiddenCheckpointText] = useState("");
  const [checkpointMode, setCheckpointMode] = useState<"ui" | "database">("ui");
  const [uiCheckpointType, setUiCheckpointType] = useState<"text" | "component" | "no_exception">("text");
  const [checkpointLocator, setCheckpointLocator] = useState("semanticId");
  const [checkpointLocatorValue, setCheckpointLocatorValue] = useState("");
  const [checkpointVisible, setCheckpointVisible] = useState(true);
  const [checkpointRole, setCheckpointRole] = useState("generic");
  const [checkpointValue, setCheckpointValue] = useState("");
  const [checkpointEnabled, setCheckpointEnabled] = useState("ignore");
  const [checkpointChecked, setCheckpointChecked] = useState("ignore");
  const [databaseTable, setDatabaseTable] = useState("");
  const [databaseOperation, setDatabaseOperation] = useState("READ");
  const [databaseRow, setDatabaseRow] = useState("{}");
  const [databaseCount, setDatabaseCount] = useState("");
  const [scenarioCode, setScenarioCode] = useState("MAIN_FLOW");
  const [scenarioName, setScenarioName] = useState("Luồng chính");
  const [scenarioWeight, setScenarioWeight] = useState(10);
  // Bảng tick thành phần giao diện — đổ về từ lệnh quét màn hình của bridge.
  // null = chưa quét; mảng = đang mở bảng tick.
  const [uiInventory, setUiInventory] = useState<{ attribute: string; value: string; role: string; checked: boolean }[] | null>(null);
  const [uiScreenName, setUiScreenName] = useState("");
  const [uiGroupWeight, setUiGroupWeight] = useState(20);
  // Chấm VỊ TRÍ và MÀU của từng thành phần đã tick. Sai số mặc định 5%: vị trí tính theo
  // % chiều rộng/cao màn hình, màu tính theo % của 255 trên từng kênh R/G/B.
  const [viTriOn, setViTriOn] = useState(true);
  const [viTriWeight, setViTriWeight] = useState(5);
  const [viTriSaiSo, setViTriSaiSo] = useState(5);
  const [mauOn, setMauOn] = useState(true);
  const [mauWeight, setMauWeight] = useState(5);
  const [mauSaiSo, setMauSaiSo] = useState(5);
  // Màu chủ đạo của app — MỘT dòng cho cả màn, đọc thẳng ColorScheme. Sai số rộng hơn
  // hẳn màu thành phần vì phép đo này chính xác tuyệt đối, không có nhiễu để chống.
  const [mauAppOn, setMauAppOn] = useState(true);
  const [mauAppWeight, setMauAppWeight] = useState(2);
  const [mauAppSaiSo, setMauAppSaiSo] = useState(20);
  // Khung máy Android tầm trung (Pixel): 412×915 dp. Sinh viên làm bài trên máy ảo
  // Android nên đây là khung DUY NHẤT còn ý nghĩa; khung desktop đã bỏ hẳn.
  //
  // KHÔNG có ô mật độ điểm ảnh: mọi phép chấm bố cục đo bằng dp (tâm thành phần, sai số
  // theo % chiều rộng/cao), nên mật độ không đổi một điểm nào — nó chỉ quyết định ảnh
  // bằng chứng nét tới đâu và nặng bao nhiêu. Để cố định 1 cho ảnh gọn.
  const [viewportWidth, setViewportWidth] = useState(412);
  const [viewportHeight, setViewportHeight] = useState(915);
  const [codePreview, setCodePreview] = useState<CodePreview | null>(null);
  const [previewFileName, setPreviewFileName] = useState("");
  const [editingScenarioId, setEditingScenarioId] = useState<string | null>(null);
  // Luật chấm tĩnh (Kiến trúc/lint): preset do backend cung cấp kèm đối chứng Golden.
  const [staticRules, setStaticRules] = useState<StaticRulesView | null>(null);
  const [staticSel, setStaticSel] = useState<Record<string, { checked: boolean; weight: number }>>({});
  const goldenFrame = useRef<HTMLIFrameElement | null>(null);
  const authoringPanel = useRef<HTMLDivElement | null>(null);
  // The Golden iframe can still emit a debounced event immediately after Stop.
  // Keep an imperative session guard so those late events never reach a closed
  // recording while React is waiting for the next render/refresh.
  const activeRecordingId = useRef<string | null>(null);
  const acceptsRecorderEvents = useRef(false);
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
      }),
    });
    await refresh(created.id);
    setAvailableSuites((current) => [created, ...current.filter((item) => item.id !== created.id)]);
    window.history.replaceState(null, "", `/teacher/behavior-authoring?suite=${encodeURIComponent(created.id)}`);
    setNotice("Đã tạo bộ chấm. Hãy cung cấp 3 artifact đầu vào rồi record luồng Golden Solution.");
  });

  const saveDatabaseContract = () => suite && run("save-database-contract", async () => {
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

  const openSuite = async (selected: Suite) => {
    setError("");
    await refresh(selected.id);
    window.history.replaceState(null, "", `/teacher/behavior-authoring?suite=${encodeURIComponent(selected.id)}`);
  };

  const closeSuite = () => {
    activeRecordingId.current = null;
    acceptsRecorderEvents.current = false;
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
    const created = await api<Recording>(`/behavior-authoring/suites/${suite.id}/recordings`, {
      method: "POST", body: JSON.stringify({ name: scenarioName, viewport: { width: viewportWidth, height: viewportHeight, device_pixel_ratio: 1 }, initial_state: { reset_storage: true } }),
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

  const appendAction = (payload?: JsonMap) => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) return;
    run("record-event", async () => {
      const targetNeeded = ["tap", "enter_text", "clear_text", "scroll"].includes(action);
      const event = payload || {
        kind: "action",
        stage: "ACTION",
        action,
        target: targetNeeded ? { [locator]: locatorValue.trim() } : {},
        attribute: targetNeeded ? locator : "none",
        attributeValue: targetNeeded ? locatorValue.trim() : "",
        valueType: "string",
        value: action === "enter_text" ? inputValue : "",
        browser: "flutter_tester",
      };
      await api(`/behavior-authoring/recordings/${recordingId}/events`, { method: "POST", body: JSON.stringify(event) });
      await refresh(suite.id);
      setLocatorValue(""); setInputValue("");
    });
  };

  /** Chia `total` điểm cho `n` dòng theo bội 0,25; phần dư dồn vào dòng cuối để tổng khớp tuyệt đối. */
  const chiaDeu = (total: number, n: number) => {
    const per = Math.round((total / n) * 4) / 4;
    return Array.from({ length: n }, (_, i) =>
      i === n - 1 ? Math.round((total - per * (n - 1)) * 4) / 4 : per);
  };

  /**
   * Lưu các thành phần đã tick thành tiêu chí giao diện trên phiên ghi hiện tại.
   *
   * BA MẶT TÁCH RỜI, mỗi mặt một nhóm điểm riêng: NỘI DUNG (có mặt trên màn hình),
   * VỊ TRÍ (tâm thành phần so với Golden) và MÀU SẮC (màu chính so với Golden).
   * Tách ra vì một lỗi chỉ nên trừ đúng phần nó sai: tô lệch màu thì mất điểm màu chứ
   * không mất điểm nội dung.
   *
   * Vì sao KHÔNG còn nút "so bố cục với ảnh chuẩn": phép so cả màn bằng pixel tính luôn
   * nền trắng nên màn hình càng trống càng được điểm. Đo thật trên 19 bài của SP27 cho
   * thấy xếp hạng bị đảo — app SAI HẲN ĐỀ được 90,1% còn bài làm ĐÚNG chỉ 88,7%.
   * Không ngưỡng nào cứu được, nên bỏ hẳn thay vì chỉnh số.
   *
   * Giá trị chuẩn của vị trí và màu KHÔNG gõ tay: engine tự đo trên Golden lúc capture
   * oracle rồi nướng vào tiêu chí.
   *
   * Tiêu chí neo vào TRẠNG THÁI CUỐI của luồng đang ghi — muốn chấm màn nào, đưa app tới
   * màn đó rồi quét.
   */
  const saveUiCriteria = () => {
    const recordingId = activeRecordingId.current;
    const chosen = (uiInventory || []).filter((it) => it.checked);
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite || chosen.length === 0) return;
    run("record-ui-criteria", async () => {
      const screen = uiScreenName.trim() || "Màn hình";
      const slug = screen.normalize("NFD").replace(/[̀-ͯ]/g, "")
        .replace(/đ/g, "d").replace(/Đ/g, "D").replace(/[^a-zA-Z0-9]+/g, "_").toUpperCase().replace(/^_+|_+$/g, "");

      const cacMat = [
        { bat: true, kind: "component_present", hau: "", nhan: "có", diem: uiGroupWeight, saiSo: 0 },
        { bat: viTriOn, kind: "component_position", hau: "_VITRI", nhan: "đúng vị trí", diem: viTriWeight, saiSo: viTriSaiSo },
        { bat: mauOn, kind: "component_color", hau: "_MAU", nhan: "đúng màu", diem: mauWeight, saiSo: mauSaiSo },
      ].filter((m) => m.bat && Number(m.diem) > 0);

      let daLuu = 0;
      for (const mat of cacMat) {
        const diem = chiaDeu(Math.max(0.25, Number(mat.diem) || 0), chosen.length);
        for (let i = 0; i < chosen.length; i++) {
          const it = chosen[i];
          await api(`/behavior-authoring/recordings/${recordingId}/events`, {
            method: "POST",
            body: JSON.stringify({
              kind: mat.kind, checkpoint: true, stage: "ASSERT", action: "observe_ui",
              browser: "flutter_tester",
              target: { [it.attribute]: it.value }, visible: true,
              attribute: it.attribute, attributeValue: it.value, valueType: "string", value: "",
              name: `${screen} — ${mat.nhan} ${it.value}`, weight: diem[i],
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

      // MÀU CHỦ ĐẠO — một dòng duy nhất, không nhân theo thành phần: nó là màu của cả
      // app chứ không của riêng widget nào. Bù điểm mù của màu từng thành phần: đo thật
      // trên SP27 cho thấy app sai hẳn bảng màu chỉ mất 4/16 tiêu chí màu, vì 12 thành
      // phần còn lại chỉ có viền hoặc chữ nên vốn gần giống nhau ở mọi bảng màu.
      if (mauAppOn && Number(mauAppWeight) > 0) {
        await api(`/behavior-authoring/recordings/${recordingId}/events`, {
          method: "POST",
          body: JSON.stringify({
            kind: "theme_color", checkpoint: true, stage: "ASSERT", action: "observe_ui",
            browser: "flutter_tester",
            weight: Math.round(Number(mauAppWeight) * 4) / 4,
            tolerance_pct: Math.min(50, Math.max(0.5, Number(mauAppSaiSo))),
            name: `${screen} — dùng đúng màu chủ đạo của đề`,
            ui_group: { id: "G_UI_" + slug + "_MAU", name: `Giao diện — ${screen.toLowerCase()} (màu sắc)` },
          }),
        });
        daLuu++;
      }
      setUiInventory(null);
      setNotice(`Đã lưu ${daLuu} tiêu chí giao diện cho ${chosen.length} thành phần của màn "${screen}".`);
      await refresh(suite.id);
    });
  };

  const appendUiCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    const textReady = Boolean(checkpointText.trim() || hiddenCheckpointText.trim());
    const componentReady = Boolean(checkpointLocatorValue.trim());
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite
        || (uiCheckpointType === "text" && !textReady)
        || (uiCheckpointType === "component" && !componentReady)) return;
    run("record-checkpoint", async () => {
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
      setCheckpointText(""); setHiddenCheckpointText(""); setCheckpointLocatorValue("");
      setCheckpointValue(""); setCheckpointEnabled("ignore"); setCheckpointChecked("ignore");
    });
  };

  const deleteRecordedEvent = (sequence: number) => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) return;
    run(`delete-event-${sequence}`, async () => {
      await api(`/behavior-authoring/recordings/${recordingId}/events/${sequence}`, { method: "DELETE" });
      await refresh(suite.id);
      setNotice(`Đã xóa thao tác/checkpoint số ${sequence}.`);
    });
  };

  const appendDatabaseCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite || !databaseTable.trim()) return;
    run("record-db-checkpoint", async () => {
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
    if (!recordingId || !recording || !["ACTIVE", "STOPPED"].includes(recording.status) || !suite) return;
    // Close the client-side gate before the first network request. This is
    // intentionally earlier than the backend transition to prevent iframe
    // messages racing with /stop and /abstract.
    acceptsRecorderEvents.current = false;
    run("record-stop", async () => {
      if (recording.status === "ACTIVE") {
        await api(`/behavior-authoring/recordings/${recordingId}/stop`, { method: "POST", body: JSON.stringify({ final_observation: {} }) });
        setRecording((current) => current ? { ...current, status: "STOPPED" } : current);
      }
      await api(`/behavior-authoring/recordings/${recordingId}/abstract`, {
        method: "POST",
        body: JSON.stringify({
          scenario_code: scenarioCode.trim().toUpperCase(),
          name: scenarioName.trim(),
          weight: scenarioWeight,
          ...(editingScenarioId ? { replace_scenario_id: editingScenarioId } : {}),
          viewports: [
            // device_pixel_ratio để cố định 1: chấm bố cục đo bằng dp nên mật độ không
            // đổi điểm, chỉ làm ảnh bằng chứng nặng thêm.
            { width: viewportWidth, height: viewportHeight, device_pixel_ratio: 1, name: "phone" },
          ],
        }),
      });
      activeRecordingId.current = null;
      setRecording(null);
      await refresh(suite.id);
      setEditingScenarioId(null);
      setNotice(editingScenarioId
        ? "Đã cập nhật scenario, replay Golden trên Database ẩn và tạo lại oracle."
        : "Đã replay Golden trên Database ẩn, sinh Output Database, oracle và testcase-definition.json.");
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
    await api(`/behavior-authoring/suites/${suite.id}/publish`, { method: "POST" });
    await refresh(suite.id);
    setNotice("Bộ chấm đã publish và materialize thành runner có thể dùng khi chấm batch.");
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
      await api(`/behavior-authoring/recordings/${recordingId}`, { method: "DELETE" });
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
      if (event.data.type === "GOLDEN_RECORDER_INVENTORY") {
        const items = Array.isArray(event.data.payload?.items) ? event.data.payload.items : [];
        setUiInventory(items
          .filter((it: JsonMap) => typeof it?.attribute === "string" && typeof it?.attributeValue === "string")
          .map((it: JsonMap) => ({
            attribute: String(it.attribute), value: String(it.attributeValue), role: String(it.role || ""),
            // label/hint là thành phần ngữ nghĩa thật (nút, ô nhập) → tick sẵn. Chữ trần có
            // thể là DỮ LIỆU đang hiển thị chứ không phải khung màn hình — để giảng viên tự cân nhắc.
            checked: it.attribute === "label" || it.attribute === "hint",
          })));
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_WARNING") {
        const message = event.data.payload?.message;
        if (typeof message === "string") setNotice(message);
        return;
      }
      if (!acceptsRecorderEvents.current || !activeRecordingId.current || event.data.type !== "GOLDEN_RECORDER_EVENT") return;
      if (!event.data.payload || typeof event.data.payload !== "object" || Array.isArray(event.data.payload)) return;
      appendAction(event.data.payload as JsonMap);
    };
    window.addEventListener("message", receive);
    return () => window.removeEventListener("message", receive);
  }, [recording, runtimeOrigin]);

  return (
    <SidebarLayout activePath="/teacher/archive" title="Quản lý bộ chấm Golden" subtitle="Record thao tác thật, trừu tượng hóa hành vi và replay tự động trên bài sinh viên" contentClassName="!max-w-none">
      <div className="mx-auto max-w-[1500px] space-y-5 p-6 text-slate-800 dark:text-slate-100">
        {(error || notice) && (
          <div className={`flex items-center gap-3 rounded-xl border px-4 py-3 ${error ? "border-rose-300 bg-rose-50 text-rose-700 dark:border-rose-700 dark:bg-rose-950/40 dark:text-rose-200" : "border-emerald-300 bg-emerald-50 text-emerald-700 dark:border-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-200"}`}>
            {error ? <XCircle size={20} /> : <CheckCircle2 size={20} />}<span className="font-medium">{error || notice}</span>
          </div>
        )}

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 1</p><h2 className="text-xl font-bold">{suite ? "Thông tin bộ chấm" : "Chọn hoặc khởi tạo Golden suite"}</h2></div><div className="flex flex-wrap items-center gap-2">{suite && <><span className="rounded-full bg-indigo-100 px-3 py-1 text-sm font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{suite.suite_code} · {suite.status}</span><button onClick={() => deleteSuite(suite)} disabled={Boolean(busy)} className="inline-flex items-center gap-1 rounded-lg border border-rose-400 px-3 py-2 text-sm font-bold text-rose-600 disabled:opacity-50 dark:text-rose-300"><Trash2 size={15} /> Xóa bộ chấm</button><button onClick={closeSuite} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-bold hover:border-indigo-400 dark:border-slate-700">Danh sách bộ chấm</button></>}</div></div>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <input value={suite?.exam_id || examId} disabled={Boolean(suite)} onChange={(e) => setExamId(e.target.value)} placeholder="Mã đề, ví dụ PE_PRM393" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <input value={suite?.name || name} disabled={Boolean(suite)} onChange={(e) => setName(e.target.value)} placeholder="Tên bộ chấm" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <input value={runtimeUrl} disabled={Boolean(suite)} onChange={(e) => setRuntimeUrl(e.target.value)} placeholder="URL Golden App đã deploy (không bắt buộc)" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <div className="min-w-0">
              <label className="sr-only" htmlFor="golden-database-name">Database Golden App đang mở</label>
              <div className="flex min-w-0 gap-2">
                <input id="golden-database-name" value={databaseName} onChange={(e) => setDatabaseName(e.target.value)} placeholder="Database Golden, ví dụ user_manager.db" className="min-w-0 flex-1 rounded-xl border border-slate-300 bg-transparent px-4 py-3 font-mono outline-none focus:border-indigo-500 dark:border-slate-700" />
                {suite && (
                  <button
                    type="button"
                    onClick={saveDatabaseContract}
                    disabled={!databaseNameChanged || !databaseName.trim() || Boolean(busy)}
                    className="inline-flex shrink-0 items-center gap-2 rounded-xl border border-indigo-400 px-3 py-2 text-sm font-bold text-indigo-600 hover:bg-indigo-50 disabled:cursor-not-allowed disabled:opacity-40 dark:text-indigo-300 dark:hover:bg-indigo-950"
                  >
                    {busy === "save-database-contract" ? <Loader2 size={16} className="animate-spin" /> : <Database size={16} />}
                    Lưu DB
                  </button>
                )}
              </div>
              <p className="mt-1 text-xs text-slate-500">Phải trùng chính xác tên file trong <span className="font-mono">openDatabase(...)</span> của Golden/starter.</p>
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
                    <input value={uiScreenName} onChange={(e) => setUiScreenName(e.target.value)} placeholder="Tên màn (vd: Màn danh sách)" className="rounded-lg border border-slate-300 px-2 py-1 text-sm dark:border-slate-600 dark:bg-slate-800" />
                    <label className="flex items-center gap-1 text-sm">Điểm nhóm
                      <input type="number" min={0.25} step={0.25} value={uiGroupWeight} onChange={(e) => setUiGroupWeight(Number(e.target.value))} className="w-20 rounded-lg border border-slate-300 px-2 py-1 text-sm dark:border-slate-600 dark:bg-slate-800" />
                    </label>
                    <button onClick={saveUiCriteria} disabled={Boolean(busy) || !uiInventory.some((it) => it.checked)} className="rounded-lg bg-emerald-600 px-3 py-1.5 text-sm font-bold text-white disabled:opacity-40">
                      Lưu {uiInventory.filter((it) => it.checked).length * (1 + (viTriOn ? 1 : 0) + (mauOn ? 1 : 0)) + (mauAppOn ? 1 : 0)} tiêu chí
                    </button>
                    <button onClick={() => setUiInventory(null)} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-bold text-slate-600 dark:border-slate-600 dark:text-slate-300">Đóng</button>
                  </div>
                  <label className="mt-2 flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border border-dashed border-emerald-400 px-2 py-1.5 text-sm dark:border-emerald-700">
                    <input type="checkbox" checked={viTriOn} onChange={() => setViTriOn((v) => !v)} />
                    <span className="font-bold">Chấm vị trí từng thành phần</span>
                    <span className="text-[11px] text-slate-500">(so tâm thành phần với Golden; sai số tính theo % chiều rộng/cao màn — 8% ≈ {Math.round(viewportWidth * 0.08)}dp ngang, {Math.round(viewportHeight * 0.08)}dp dọc trên khung {viewportWidth}×{viewportHeight}. Đo thật trên SP27: 5% và 8% cho kết quả y hệt, 12% thì bài bố cục sai bắt đầu lọt)</span>
                    <span className="ml-auto flex items-center gap-1 text-xs">
                      <input type="number" min={0.25} step={0.25} value={viTriWeight} onChange={(e) => setViTriWeight(Number(e.target.value))} className="w-16 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" /> điểm ·
                      sai số <input type="number" min={0.5} max={50} step={0.5} value={viTriSaiSo} onChange={(e) => setViTriSaiSo(Number(e.target.value))} className="w-14 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" />%
                    </span>
                  </label>
                  <label className="mt-1 flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border border-dashed border-emerald-400 px-2 py-1.5 text-sm dark:border-emerald-700">
                    <input type="checkbox" checked={mauOn} onChange={() => setMauOn((v) => !v)} />
                    <span className="font-bold">Chấm màu từng thành phần</span>
                    <span className="text-[11px] text-slate-500">(so màu chính với Golden; sai số tính theo % của 255 trên từng kênh R/G/B — 5% bắt được cả lệch một nấc Material shade, xanh-vs-tím lệch tới 30%)</span>
                    <span className="ml-auto flex items-center gap-1 text-xs">
                      <input type="number" min={0.25} step={0.25} value={mauWeight} onChange={(e) => setMauWeight(Number(e.target.value))} className="w-16 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" /> điểm ·
                      sai số <input type="number" min={0.5} max={50} step={0.5} value={mauSaiSo} onChange={(e) => setMauSaiSo(Number(e.target.value))} className="w-14 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" />%
                    </span>
                  </label>
                  <label className="mt-1 flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border border-dashed border-emerald-400 px-2 py-1.5 text-sm dark:border-emerald-700">
                    <input type="checkbox" checked={mauAppOn} onChange={() => setMauAppOn((v) => !v)} />
                    <span className="font-bold">Chấm màu chủ đạo của app</span>
                    <span className="text-[11px] text-slate-500">(MỘT dòng cho cả màn, đọc thẳng ColorScheme nên chính xác tuyệt đối — sai số rộng là để tha sắc độ lân cận cùng họ màu, không phải để chống nhiễu; đo thật: xanh-vs-tím lệch 40%)</span>
                    <span className="ml-auto flex items-center gap-1 text-xs">
                      <input type="number" min={0.25} step={0.25} value={mauAppWeight} onChange={(e) => setMauAppWeight(Number(e.target.value))} className="w-16 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" /> điểm ·
                      sai số <input type="number" min={0.5} max={50} step={0.5} value={mauAppSaiSo} onChange={(e) => setMauAppSaiSo(Number(e.target.value))} className="w-14 rounded border border-slate-300 px-1.5 py-0.5 dark:border-slate-600 dark:bg-slate-800" />%
                    </span>
                  </label>
                  <div className="mt-2 grid max-h-56 gap-1 overflow-auto pr-1">
                    {uiInventory.map((it, i) => (
                      <label key={`${it.attribute}-${it.value}`} className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1 text-sm hover:bg-emerald-100/60 dark:hover:bg-emerald-900/30">
                        <input type="checkbox" checked={it.checked} onChange={() => setUiInventory((prev) => prev ? prev.map((x, j) => (j === i ? { ...x, checked: !x.checked } : x)) : prev)} />
                        <span className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-bold ${it.attribute === "label" ? "bg-indigo-100 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300" : it.attribute === "hint" ? "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300" : "bg-slate-100 text-slate-500 dark:bg-slate-800"}`}>{it.attribute}</span>
                        <span className="truncate">{it.value}</span>
                        {it.role && <span className="ml-auto text-[10px] text-slate-400">{it.role}</span>}
                      </label>
                    ))}
                  </div>
                  <p className="mt-2 text-[11px] text-slate-500">
                    Nút và ô nhập (label/hint) được tick sẵn. Chữ trần (text) có thể là dữ liệu đang hiển thị — chỉ tick khi nó là khung màn hình
                    (tiêu đề, nhãn cố định). Tiêu chí neo vào trạng thái cuối của luồng đang ghi: đưa app tới đúng màn cần chấm rồi mới quét.
                  </p>
                </div>
              )}
              {previewUrl ? <div className="mt-4 w-full overflow-auto rounded-xl border border-slate-300 bg-slate-100 p-3 dark:border-slate-700 dark:bg-slate-950"><iframe ref={goldenFrame} title="Golden App" src={previewUrl} style={{ width: Math.min(viewportWidth, 900), minWidth: Math.min(viewportWidth, 900), height: Math.min(viewportHeight, 700) }} className="mx-auto block rounded-lg border border-slate-300 bg-white dark:border-slate-700" /></div> : <div className="mt-4 flex h-[300px] flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 text-center dark:border-slate-700"><MonitorPlay size={42} className="text-slate-400" /><p className="mt-3 font-bold">Golden Solution chưa được build để thao tác</p><p className="mt-1 max-w-md text-sm text-slate-500">Upload Golden ZIP rồi bấm “Build & mở Golden”. Hệ thống tự host app và ghi click/nhập liệu bằng semantic locator.</p></div>}
            </div>

            <div ref={authoringPanel} className="min-w-0 scroll-mt-24 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
              <div className="flex items-center justify-between"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 4</p><h2 className="text-xl font-bold">Record → Abstract</h2></div>{recording ? <span className={`flex items-center gap-2 text-sm font-bold ${recording.status === "ACTIVE" ? "text-rose-500" : "text-amber-500"}`}><span className={`h-2 w-2 rounded-full ${recording.status === "ACTIVE" ? "animate-pulse bg-rose-500" : "bg-amber-500"}`} /> {recording.status === "ACTIVE" ? "Đang ghi" : "Chờ sinh testcase"}</span> : <span className="text-sm text-slate-500">Chưa ghi</span>}</div>
              {editingScenarioId && recording && <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-indigo-300 bg-indigo-50 px-4 py-3 text-sm dark:border-indigo-800 dark:bg-indigo-950/30"><div><p className="font-bold text-indigo-700 dark:text-indigo-300">Đang sửa scenario {scenarioCode}</p><p className="mt-1 text-xs text-slate-600 dark:text-slate-300">Toàn bộ bước cũ đã nằm trong danh sách bên dưới. Hãy thao tác thêm trên Golden App hoặc dùng các form thêm action/checkpoint; có thể xóa từng bước cũ.</p></div><button onClick={cancelActiveRecording} disabled={Boolean(busy)} className="rounded-lg border border-slate-300 px-3 py-2 text-xs font-bold disabled:opacity-40 dark:border-slate-700">Hủy sửa</button></div>}
              <div className="mt-4 grid gap-3 sm:grid-cols-3"><input value={scenarioCode} disabled={Boolean(editingScenarioId)} onChange={(e) => setScenarioCode(e.target.value)} placeholder="Mã luồng" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-700" /><input value={scenarioName} onChange={(e) => setScenarioName(e.target.value)} placeholder="Tên luồng" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><input type="number" min={0.1} step={0.5} value={scenarioWeight} onChange={(e) => setScenarioWeight(Number(e.target.value))} aria-label="Trọng số scenario" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /></div>
              <div className="mt-2 grid gap-2 sm:grid-cols-2"><label className="text-xs font-semibold text-slate-500">Rộng màn (dp)<input type="number" min={240} value={viewportWidth} onChange={(e) => setViewportWidth(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 dark:border-slate-700 dark:text-slate-100" /></label><label className="text-xs font-semibold text-slate-500">Cao màn (dp)<input type="number" min={320} value={viewportHeight} onChange={(e) => setViewportHeight(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 dark:border-slate-700 dark:text-slate-100" /></label></div>
              <p className="mt-2 text-xs font-semibold text-slate-600 dark:text-slate-300">Khung máy Android, tính bằng dp — cỡ mà Android Studio hiển thị cho máy ảo (Pixel: 412×915). Mọi phép chấm bố cục đều đo bằng dp nên không cần khai mật độ điểm ảnh.</p>
              {!recording ? <><button onClick={startRecording} disabled={!recordingInputsReady || Boolean(busy)} className="mt-4 inline-flex items-center gap-2 rounded-xl bg-rose-600 px-4 py-2.5 font-bold text-white disabled:opacity-40"><Radio size={18} /> Bắt đầu record</button>{!recordingInputsReady && <p className="mt-2 text-xs text-amber-600">Cần đủ Database phát sinh viên, Database ẩn và Golden Solution.</p>}</> : <>
                <div className="mt-5 rounded-xl border border-slate-200 p-4 dark:border-slate-700"><h3 className="font-bold">Thêm action</h3><div className="mt-3 grid gap-2 sm:grid-cols-2"><select value={action} onChange={(e) => setAction(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{ACTIONS.map((item) => <option key={item} value={item}>{item}</option>)}</select><select value={locator} onChange={(e) => setLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item}</option>)}</select><input value={locatorValue} onChange={(e) => setLocatorValue(e.target.value)} placeholder="Giá trị nhận diện" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><input value={inputValue} onChange={(e) => setInputValue(e.target.value)} placeholder="Dữ liệu nhập (nếu có)" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /></div><button onClick={() => appendAction()} className="mt-3 inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm font-bold text-white"><Plus size={16} /> Thêm action</button></div>
                <div className="mt-3 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
                  <div className="flex items-center justify-between gap-3">
                    <div><h3 className="font-bold">Thêm checkpoint</h3><p className="text-xs text-slate-500">Mỗi checkpoint trở thành một đầu điểm độc lập.</p></div>
                    <div className="flex rounded-lg bg-slate-100 p-1 text-xs font-bold dark:bg-slate-800">
                      <button onClick={() => setCheckpointMode("ui")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "ui" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>UI</button>
                      <button onClick={() => setCheckpointMode("database")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "database" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>Database</button>
                    </div>
                  </div>
                  {checkpointMode === "ui" ? (
                    <div className="mt-3 space-y-2">
                      <select value={uiCheckpointType} onChange={(e) => setUiCheckpointType(e.target.value as "text" | "component" | "no_exception")} className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                        <option value="text">Nội dung text xuất hiện / không xuất hiện</option>
                        <option value="component">Thành phần UI và trạng thái semantic</option>
                        <option value="no_exception">Luồng không phát sinh exception</option>
                      </select>
                      {uiCheckpointType === "text" && <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                        <input value={checkpointText} onChange={(e) => setCheckpointText(e.target.value)} placeholder="Text phải xuất hiện, cách nhau dấu phẩy" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <input value={hiddenCheckpointText} onChange={(e) => setHiddenCheckpointText(e.target.value)} placeholder="Text không được xuất hiện (tùy chọn)" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
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
                      {uiCheckpointType === "no_exception" && <div className="flex items-center justify-between gap-3 rounded-lg bg-slate-50 px-3 py-2 text-sm dark:bg-slate-800">
                        <span>Checkpoint pass khi luồng chạy tới đây mà ứng dụng không ném exception.</span>
                        <button onClick={appendUiCheckpoint} title="Lưu checkpoint không exception" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>}
                    </div>
                  ) : (
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
                      <p className="text-xs text-slate-500">Mục này dùng để bổ sung assertion DB có chủ đích. Dù bỏ qua, hệ thống vẫn tự replay Golden, so Hidden DB với Output DB và tách INSERT/UPDATE/DELETE thành checkpoint độc lập. Nếu checkpoint UI và row SQLite cùng chứa giá trị nhập cuối, hệ thống tự gộp thành checkpoint đối chiếu Input → UI → Database.</p>
                    </div>
                  )}
                </div>
                <div className="mt-4 max-h-48 space-y-2 overflow-auto">{(recording.raw_trace || []).map((item, index) => {
                  const sequence = Number(item.sequence || index + 1);
                  return <div key={sequence} className="flex items-center gap-3 rounded-lg bg-slate-50 px-3 py-2 text-xs dark:bg-slate-800">
                    <span className="font-mono text-indigo-500">{sequence}</span>
                    <span className="font-bold">{String(item.action || item.kind)}</span>
                    <span className="min-w-0 flex-1 truncate text-slate-500">{JSON.stringify(item.target || item.expect || {})}</span>
                    <button onClick={() => deleteRecordedEvent(sequence)} disabled={Boolean(busy)} title="Xóa thao tác/checkpoint này" className="rounded-md p-1.5 text-rose-500 hover:bg-rose-100 disabled:opacity-40 dark:hover:bg-rose-950"><Trash2 size={15} /></button>
                  </div>;
                })}</div>
                {recording.status === "STOPPED" && error && <div className="mt-4 rounded-xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700 dark:border-rose-800 dark:bg-rose-950/30 dark:text-rose-200">Không thể sinh testcase: {error}. Phiên vẫn được giữ để bạn thử lại hoặc hủy.</div>}
                <div className="mt-4 flex flex-wrap gap-2"><button onClick={stopAndAbstract} disabled={Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl bg-slate-800 px-4 py-2.5 font-bold text-white disabled:opacity-40 dark:bg-slate-700">{busy === "record-stop" ? <Loader2 size={17} className="animate-spin" /> : <Square size={17} />} {busy === "record-stop" ? "Đang replay Golden và sinh Output DB…" : recording.status === "STOPPED" ? "Thử sinh testcase lại" : editingScenarioId ? "Lưu sửa đổi và sinh lại testcase" : "Dừng, capture oracle và sinh testcase"}</button><button onClick={cancelActiveRecording} disabled={Boolean(busy)} className="rounded-xl border border-rose-300 px-4 py-2.5 font-bold text-rose-600 disabled:opacity-40 dark:border-rose-900">{editingScenarioId ? "Hủy sửa" : "Hủy record"}</button></div>
                <p className="mt-2 text-xs text-slate-500">Bước này có thể mất vài phút vì chạy chính Golden Solution trong Docker bằng Hidden DB; không cần tự xuất hoặc tải Output DB.</p>
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
            <p className="mt-3 text-xs text-slate-500">Nhóm điểm: các luật kiến trúc gộp vào «Kiến trúc mã nguồn», luật lint vào «Chất lượng mã nguồn» — hiện đúng như vậy trong lịch sử chấm và file Excel. Lưu xong phải <b>publish lại</b> thì bộ đề mới mang luật mới.</p>
          </section>}

          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="flex flex-wrap items-center justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 5</p><h2 className="text-xl font-bold">Kiểm chứng Golden và publish</h2><p className="mt-1 text-sm text-slate-500">Còn thiếu: {readiness?.missing.join(", ") || "không"}. Preflight chạy chính plan trên Golden; publish chỉ mở khi toàn bộ checkpoint pass.</p><p className={`mt-2 text-sm font-bold ${validation?.status === "PASSED" && validation.current ? "text-emerald-600" : "text-amber-600"}`}>Preflight: {validation?.status || "NOT_RUN"}{validation?.total_checkpoints !== undefined ? ` · ${validation.passed_checkpoints}/${validation.total_checkpoints}` : ""}{validation && !validation.current ? " · plan đã thay đổi" : ""}</p></div><div className="flex flex-wrap gap-2"><button onClick={() => openCodePreview()} disabled={!suite.scenarios?.length || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl border border-slate-300 px-5 py-3 font-bold text-slate-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-700 dark:text-slate-200">{busy === "code-preview" ? <Loader2 className="animate-spin" size={18} /> : <Code2 size={18} />} Xem code bộ chấm</button><button onClick={validateGolden} disabled={!readiness?.ready || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl border border-indigo-300 px-5 py-3 font-bold text-indigo-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-indigo-700 dark:text-indigo-300">{busy === "validate-golden" ? <Loader2 className="animate-spin" size={18} /> : <Play size={18} />} Chạy thử trên Golden</button><button onClick={publish} disabled={!readiness?.ready || !validation?.current || validation.status !== "PASSED" || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-5 py-3 font-bold text-white disabled:cursor-not-allowed disabled:opacity-40">{busy === "publish" ? <Loader2 className="animate-spin" size={18} /> : <Send size={18} />} Publish bộ chấm</button></div></div>
            <div className="mt-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
              {(suite.scenarios || []).map((item, index) => <div key={String(item.id || index)} className="rounded-xl border border-slate-200 px-4 py-3 dark:border-slate-700"><div className="flex items-center justify-between gap-2"><span className="font-bold">{String(item.name || item.scenario_code)}</span><span className="rounded-full bg-indigo-100 px-2 py-1 text-xs font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{String(item.weight)} điểm</span></div><p className="mt-1 font-mono text-[11px] text-indigo-500">{String(item.scenario_code || "")}</p><p className="mt-2 text-xs text-slate-500">{Array.isArray(item.steps) ? item.steps.length : 0} action · {Array.isArray(item.checkpoints) ? item.checkpoints.length : 0} checkpoint</p><div className="mt-3 flex flex-wrap gap-2"><button onClick={() => openCodePreview(String(item.scenario_code || ""))} disabled={Boolean(busy)} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-2.5 py-1.5 text-xs font-bold text-slate-700 hover:border-indigo-400 disabled:opacity-40 dark:border-slate-700 dark:text-slate-200"><Code2 size={14} /> Xem testcase</button><button onClick={() => openScenarioEditor(item)} disabled={Boolean(busy) || Boolean(recording)} title={recording ? "Hãy kết thúc phiên đang soạn trước" : "Nạp lại các bước vào khung record để chỉnh sửa"} className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-300 px-2.5 py-1.5 text-xs font-bold text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 dark:border-indigo-800 dark:text-indigo-300 dark:hover:bg-indigo-950"><Pencil size={14} /> Sửa thao tác</button><button onClick={() => deleteScenario(item)} disabled={Boolean(busy) || Boolean(recording)} className="inline-flex items-center gap-1.5 rounded-lg border border-rose-300 px-2.5 py-1.5 text-xs font-bold text-rose-600 hover:bg-rose-50 disabled:opacity-40 dark:border-rose-900 dark:hover:bg-rose-950"><Trash2 size={14} /> Xóa</button></div></div>)}
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
