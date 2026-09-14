"use client";

// Trợ lý AI soạn đề trong trang "Behavior Authoring" (Golden Solution Record–Abstract–Replay).
//
// Triết lý: AI chỉ ĐỀ XUẤT, giáo viên quyết định. Mỗi bước đều có chỗ sửa tay + ô nhắc AI sửa
// lại + nút chấp nhận/tải riêng; không bước nào tự ghi vào bộ đang chấm.
//
// Bản này port từ Grader_App1 (frontend/components/testcases/AiAuthorPanel.tsx) nhưng ĐÃ BỎ bước
// "phân tích Item Key + vẽ mockup" và "đề xuất testcase theo template": hai bước đó gắn với hệ
// thống Template/RunnerCatalog/Item-Key-Contract mà Grader_App không còn dùng (đã chuyển sang
// Golden Solution Record–Abstract–Replay, xem docs/golden-record-abstract-replay.md). Bù lại có
// thêm Bước 3 (MỚI): AI sinh app "lời giải mẫu" (Golden Solution) — năng lực chưa từng có ở
// Grader_App1, đánh dấu rõ là thử nghiệm vì AI viết cả một app hoàn chỉnh khó chắc build được ngay.
//
// Backend: /api/ai/* (xem AiAuthorController + AiExamAuthorService).

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { API_BASE } from "@/lib/config";
import { downloadBlob } from "@/lib/mockup-image";
import {
  clearAiDraft, DRAFT_NO_EXAM, fetchAiDraftFromServer, pushAiDraftToServer, readAiDraft, writeAiDraft,
} from "@/lib/aiAuthorDrafts";
import {
  Sparkles, Settings2, KeyRound, Wand2, FileText, Loader2, Check, ChevronDown, Save, Info,
  FileCode2, Upload, Download, RotateCcw, AlertTriangle, RefreshCw, FlaskConical,
} from "lucide-react";

interface StarterFile { path: string; content: string; summary: string }
interface GoldenFile { path: string; content: string }
interface AiModel { id: string; label: string; provider: string; vendor: string }
interface AiSettings {
  model: string; provider: string; vendor: string; keyUrl: string | null;
  hasApiKey: boolean; apiKeyMasked: string | null; keyWarning: string | null;
  apiKeyLength: number;
  baseUrl: string; customBaseUrl: boolean;
  timeoutSeconds: number; models: AiModel[]; ready: boolean;
}

interface Props {
  examId: string;
  /** Tên file SQLite của suite (ô "Bước 1" ở trang cha) — đưa vào prompt Golden Solution. */
  databaseName?: string;
  /** Package giáo viên đã khai được phép dùng — thu hẹp thêm bộ package cho AI, không mở rộng. */
  allowedPackages?: string[];
}

export default function AiAuthorPanel({ examId, databaseName, allowedPackages }: Props) {
  const [open, setOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [modelDraft, setModelDraft] = useState("");
  const [baseUrlDraft, setBaseUrlDraft] = useState("");
  const [customModel, setCustomModel] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  // Bước 1 — hai nhánh: nhờ AI soạn đề mới, hay tải đề có sẵn lên
  const [source, setSource] = useState<"ai" | "upload">("ai");
  const [importedName, setImportedName] = useState("");
  const [req, setReq] = useState({
    topic: "", knowledge: "", screens: "", features: "", entity: "",
    storage: "SQLite", difficulty: "Trung bình", duration: "90 phút", note: "",
  });

  // Bước 2 — đề bài
  const [deBai, setDeBai] = useState("");
  const [summary, setSummary] = useState("");
  const [revisePrompt, setRevisePrompt] = useState("");
  const [examAccepted, setExamAccepted] = useState(false);

  // Bước 3 — khung starter phát cho sinh viên
  const [starterFiles, setStarterFiles] = useState<StarterFile[]>([]);
  const [starterWarnings, setStarterWarnings] = useState<string[]>([]);
  const [syntax, setSyntax] = useState<{ ok: boolean | null; message: string } | null>(null);
  const [openFile, setOpenFile] = useState<string | null>(null);
  const [starterPrompt, setStarterPrompt] = useState("");
  const [starterSpec, setStarterSpec] = useState<unknown>(null);
  const [starterEdited, setStarterEdited] = useState(false);

  // Bước 4 (MỚI) — app "lời giải mẫu" (Golden Solution), thử nghiệm
  const [goldenFiles, setGoldenFiles] = useState<GoldenFile[]>([]);
  const [goldenWarnings, setGoldenWarnings] = useState<string[]>([]);
  const [goldenSyntax, setGoldenSyntax] = useState<{ ok: boolean | null; message: string } | null>(null);
  const [openGoldenFile, setOpenGoldenFile] = useState<string | null>(null);
  const [goldenPrompt, setGoldenPrompt] = useState("");
  const [goldenEdited, setGoldenEdited] = useState(false);

  // ── Bản nháp: giữ nguyên bước đang dở khi tải lại trang / quay lại sau ──
  const restoredFor = useRef<string | null>(null);
  const [restoredAt, setRestoredAt] = useState<number | null>(null);
  const [draftTrimmed, setDraftTrimmed] = useState<string[]>([]);
  const draftKey = examId.trim() || DRAFT_NO_EXAM;

  const loadSettings = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/ai/settings`);
      const data = (await res.json()) as AiSettings;
      if (!res.ok) throw new Error("Không đọc được cấu hình AI");
      setSettings(data);
      setModelDraft(data.model || "");
      setBaseUrlDraft(data.customBaseUrl ? data.baseUrl || "" : "");
      setCustomModel(!(data.models || []).some((m) => m.id === data.model));
    } catch {
      setSettings(null);
    }
  }, []);

  useEffect(() => { if (open && !settings) loadSettings(); }, [open, settings, loadSettings]);

  /** Khôi phục bản nháp của bộ đang mở — chạy khi đổi mã bộ testcase. */
  useEffect(() => {
    const previous = restoredFor.current;
    if (previous === draftKey) return;
    restoredFor.current = draftKey;
    setDraftTrimmed([]);
    let cancelled = false;

    (async () => {
      const local = readAiDraft(draftKey);
      const remote = await fetchAiDraftFromServer(API_BASE, draftKey);
      if (cancelled || restoredFor.current !== draftKey) return;
      const draft = !remote ? local
        : !local ? remote
        : (remote.updatedAt || 0) > (local.updatedAt || 0) ? remote : local;

      if (!draft) {
        if (previous !== null) {
          if (previous === DRAFT_NO_EXAM) clearAiDraft(DRAFT_NO_EXAM);
          return;
        }
        setRestoredAt(null);
        resetWizard();
        return;
      }
      applyDraft(draft);
    })();
    return () => { cancelled = true; };
  }, [draftKey]);

  const applyDraft = (draft: { updatedAt: number; state: Record<string, unknown> }) => {
    const s = draft.state as Record<string, never>;
    setSource(s.source ?? "ai");
    setImportedName(s.importedName ?? "");
    if (s.req) setReq((cur) => ({ ...cur, ...(s.req as object) }));
    setDeBai(s.deBai ?? "");
    setSummary(s.summary ?? "");
    setExamAccepted(!!s.examAccepted);
    setStarterFiles(s.starterFiles ?? []);
    setStarterSpec(s.starterSpec ?? null);
    setStarterWarnings(s.starterWarnings ?? []);
    setSyntax(s.syntax ?? null);
    setOpenFile(s.openFile ?? null);
    setGoldenFiles(s.goldenFiles ?? []);
    setGoldenWarnings(s.goldenWarnings ?? []);
    setGoldenSyntax(s.goldenSyntax ?? null);
    setOpenGoldenFile(s.openGoldenFile ?? null);
    setRestoredAt(draft.updatedAt);
    setOpen(true);
  };

  const resetWizard = () => {
    setDeBai(""); setSummary(""); setExamAccepted(false);
    setStarterFiles([]); setStarterSpec(null); setStarterWarnings([]);
    setSyntax(null); setOpenFile(null); setStarterEdited(false);
    setGoldenFiles([]); setGoldenWarnings([]); setGoldenSyntax(null);
    setOpenGoldenFile(null); setGoldenEdited(false);
    setImportedName(""); setRevisePrompt(""); setStarterPrompt(""); setGoldenPrompt("");
  };

  /** Ghi nháp mỗi khi có thay đổi đáng kể. Hoãn 800ms để khỏi ghi thẳng theo từng phím gõ. */
  useEffect(() => {
    if (restoredFor.current !== draftKey) return;
    const empty = !deBai && !starterFiles.length && !goldenFiles.length;
    const timer = setTimeout(() => {
      if (empty) {
        clearAiDraft(draftKey);
        pushAiDraftToServer(API_BASE, draftKey, null);
        setRestoredAt(null);
        return;
      }
      const state = {
        source, importedName, req, deBai, summary, examAccepted,
        starterFiles, starterSpec, starterWarnings, syntax, openFile,
        goldenFiles, goldenWarnings, goldenSyntax, openGoldenFile,
      };
      setDraftTrimmed(writeAiDraft(draftKey, state));
      pushAiDraftToServer(API_BASE, draftKey, state);
    }, 800);
    return () => clearTimeout(timer);
  }, [draftKey, source, importedName, req, deBai, summary, examAccepted,
    starterFiles, starterSpec, starterWarnings, syntax, openFile,
    goldenFiles, goldenWarnings, goldenSyntax, openGoldenFile]);

  const discardDraft = () => {
    if (!confirm("Bỏ bản nháp của bộ này và bắt đầu lại từ đầu?")) return;
    clearAiDraft(draftKey);
    pushAiDraftToServer(API_BASE, draftKey, null);
    resetWizard();
    setRestoredAt(null);
    setDraftTrimmed([]);
    setInfo("Đã bỏ bản nháp. Bắt đầu lại từ bước soạn đề.");
  };

  const call = async <T,>(path: string, body: unknown, label: string): Promise<T | null> => {
    setBusy(label); setError(null); setInfo(null);
    try {
      const res = await fetch(`${API_BASE}${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "AI trả về lỗi");
      return data as T;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không gọi được AI");
      return null;
    } finally {
      setBusy(null);
    }
  };

  // ── Cấu hình ───────────────────────────────────────────────────
  const persistSettings = async (label: string) => {
    const body: Record<string, unknown> = {
      model: modelDraft.trim(),
      baseUrl: baseUrlDraft.trim(),
    };
    if (apiKeyDraft.trim()) body.apiKey = apiKeyDraft.trim();
    const data = await call<AiSettings>("/ai/settings", body, label);
    if (data) {
      setSettings(data);
      setApiKeyDraft("");
      setModelDraft(data.model);
      setBaseUrlDraft(data.customBaseUrl ? data.baseUrl || "" : "");
    }
    return data;
  };

  const testAndSaveSettings = async () => {
    if (!apiKeyDraft.trim() && !settings?.hasApiKey) {
      setError(`Chưa có API key cho ${draftVendor}. Hãy dán key rồi thử lại.`);
      return;
    }
    const probe = await call<{ ok: boolean; message: string; elapsedMs: number }>(
      "/ai/settings/test",
      { model: modelDraft.trim(), apiKey: apiKeyDraft.trim(), baseUrl: baseUrlDraft.trim() },
      "settings");
    if (!probe) return;
    if (!probe.ok) {
      setError(probe.message || "Không kết nối được — chưa lưu gì cả.");
      return;
    }
    const data = await persistSettings("settings");
    if (data) {
      setInfo(`Kết nối thành công (${probe.elapsedMs} ms) và đã lưu. `
        + `Đang dùng ${data.model} (${data.vendor}).`);
    }
  };

  const importExam = async (file: File) => {
    setBusy("import");
    setError(null);
    setInfo(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const res = await fetch(`${API_BASE}/ai/exam/import`, { method: "POST", body: form });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không đọc được file đề.");
      setDeBai(String(data.de_bai || ""));
      setSummary("");
      setExamAccepted(false);
      setImportedName(String(data.file_name || file.name));
      const warnings: string[] = Array.isArray(data.warnings) ? data.warnings : [];
      setInfo(`Đã đọc ${file.name} (${String(data.de_bai || "").length} ký tự).`
        + (warnings.length ? ` ${warnings.join(" ")}` : " Hãy xem lại đề rồi bấm chấp nhận."));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không đọc được file đề.");
    } finally {
      setBusy(null);
    }
  };

  // ── Bước 1 & 2 ─────────────────────────────────────────────────
  const draftExam = async () => {
    if (!req.topic.trim()) { setError("Hãy nhập chủ đề / bài toán của đề."); return; }
    const data = await call<{ de_bai: string; summary: string }>("/ai/exam/draft", {
      ...req, database_name: databaseName, allowed_packages: allowedPackages,
    }, "draft");
    if (data) {
      setDeBai(data.de_bai);
      setSummary(data.summary);
      setExamAccepted(false);
    }
  };

  const reviseExam = async () => {
    if (!revisePrompt.trim()) { setError("Hãy mô tả bạn muốn AI sửa gì."); return; }
    const data = await call<{ de_bai: string; summary: string }>(
      "/ai/exam/revise", { de_bai: deBai, instruction: revisePrompt }, "revise");
    if (data) {
      setDeBai(data.de_bai);
      setSummary(data.summary);
      setRevisePrompt("");
      setInfo("AI đã sửa đề. Kiểm tra lại phần thay đổi trước khi chấp nhận.");
    }
  };

  // ── Bước 3: khung starter ────────────────────────────────────────
  const takeStarter = (data: { files: StarterFile[]; warnings: string[]; spec: unknown;
    syntax_ok: boolean | null; syntax_message: string }) => {
    setStarterFiles(data.files || []);
    setStarterWarnings(data.warnings || []);
    setStarterSpec(data.spec ?? null);
    setSyntax({ ok: data.syntax_ok, message: data.syntax_message });
    setOpenFile(data.files?.[0]?.path ?? null);
    setStarterEdited(false);
  };

  const proposeStarter = async () => {
    const data = await call<Parameters<typeof takeStarter>[0]>(
      "/ai/starter/propose", { de_bai: deBai }, "starter");
    if (data) takeStarter(data);
  };

  const reviseStarter = async () => {
    if (!starterPrompt.trim()) { setError("Hãy mô tả bạn muốn AI sửa gì trong khung starter."); return; }
    if (starterEdited && !confirm(
      "AI sẽ sinh lại toàn bộ khung, phần code bạn vừa sửa tay sẽ bị thay. Tiếp tục?")) return;
    const data = await call<Parameters<typeof takeStarter>[0]>("/ai/starter/revise",
      { de_bai: deBai, spec: starterSpec, instruction: starterPrompt }, "starter-revise");
    if (data) {
      takeStarter(data);
      setStarterPrompt("");
      setInfo("AI đã sửa khung starter. Xem lại code rồi lưu cho sinh viên.");
    }
  };

  const recheckStarter = async () => {
    const data = await call<{ syntax_ok: boolean | null; syntax_message: string }>(
      "/ai/starter/check", { files: starterFiles }, "starter-check");
    if (data) setSyntax({ ok: data.syntax_ok, message: data.syntax_message });
  };

  const saveAndDownloadStarter = async () => {
    if (!examId.trim()) { setError("Hãy nhập mã bộ testcase trước khi lưu khung starter."); return; }
    setBusy("starter-save"); setError(null); setInfo(null);
    try {
      const saveRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/starter`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ files: starterFiles.map((f) => ({ name: f.path, content: f.content })) }),
      });
      const saved = await saveRes.json().catch(() => ({}));
      if (!saveRes.ok) throw new Error(saved?.error || "Không lưu được khung starter.");

      const zipRes = await fetch(`${API_BASE}/ai/starter/download`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exam_id: examId.trim(), files: starterFiles }),
      });
      if (!zipRes.ok) {
        const data = await zipRes.json().catch(() => ({}));
        throw new Error(data?.error || "Đã lưu nhưng không tải được file .zip.");
      }
      downloadBlob(await zipRes.blob(), `${examId.trim()}_starter.zip`);
      setInfo(`Đã lưu khung starter (${saved.files?.length ?? starterFiles.length} file) vào bộ `
        + `${examId.trim()} và tải .zip về máy.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không lưu/tải được khung starter.");
    } finally {
      setBusy(null);
    }
  };

  // ── Bước 4 (MỚI): app "lời giải mẫu" (Golden Solution) ──────────
  const takeGolden = (data: { files: GoldenFile[]; warnings: string[];
    syntax_ok: boolean | null; syntax_message: string }) => {
    setGoldenFiles(data.files || []);
    setGoldenWarnings(data.warnings || []);
    setGoldenSyntax({ ok: data.syntax_ok, message: data.syntax_message });
    setOpenGoldenFile(data.files?.[0]?.path ?? null);
    setGoldenEdited(false);
  };

  const proposeGolden = async () => {
    const data = await call<Parameters<typeof takeGolden>[0]>("/ai/golden/propose", {
      de_bai: deBai, database_name: databaseName, allowed_packages: allowedPackages,
    }, "golden");
    if (data) takeGolden(data);
  };

  const reviseGolden = async () => {
    if (!goldenPrompt.trim()) { setError("Hãy mô tả bạn muốn AI sửa gì trong app lời giải mẫu."); return; }
    if (goldenEdited && !confirm(
      "AI sẽ sinh lại toàn bộ app, phần code bạn vừa sửa tay sẽ bị thay. Tiếp tục?")) return;
    const data = await call<Parameters<typeof takeGolden>[0]>("/ai/golden/revise", {
      de_bai: deBai, spec: goldenFiles, instruction: goldenPrompt,
      database_name: databaseName, allowed_packages: allowedPackages,
    }, "golden-revise");
    if (data) {
      takeGolden(data);
      setGoldenPrompt("");
      setInfo("AI đã sửa app lời giải mẫu. Xem lại code rồi tải lên ô \"Golden Solution\" phía dưới.");
    }
  };

  const recheckGolden = async () => {
    const data = await call<{ syntax_ok: boolean | null; syntax_message: string }>(
      "/ai/golden/check", { files: goldenFiles }, "golden-check");
    if (data) setGoldenSyntax({ ok: data.syntax_ok, message: data.syntax_message });
  };

  const downloadGolden = async () => {
    setBusy("golden-download"); setError(null); setInfo(null);
    try {
      const zipRes = await fetch(`${API_BASE}/ai/golden/download`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exam_id: examId.trim(), files: goldenFiles }),
      });
      if (!zipRes.ok) {
        const data = await zipRes.json().catch(() => ({}));
        throw new Error(data?.error || "Không tải được file .zip.");
      }
      const name = examId.trim() ? `${examId.trim()}_golden_solution.zip` : "golden_solution.zip";
      downloadBlob(await zipRes.blob(), name);
      setInfo("Đã tải app lời giải mẫu (.zip). Tải file này lên ô \"Golden Solution\" ở Bước 2 "
        + "phía dưới rồi bấm \"Build & mở Golden\" để kiểm tra thật.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được app lời giải mẫu.");
    } finally {
      setBusy(null);
    }
  };

  // ── Lưu bộ phát cho SV ─────────────────────────────────────────
  const saveHandout = async () => {
    if (!examId.trim()) { setError("Hãy nhập mã bộ testcase trước khi lưu đề bài."); return; }
    const exam = examId.trim();
    setBusy("handout"); setError(null); setInfo(null);
    try {
      const saveRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/handout`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ de_bai: deBai, mockups: [] }),
      });
      const saved = await saveRes.json().catch(() => ({}));
      if (!saveRes.ok) throw new Error(saved?.error || "Không lưu được đề bài.");

      const docxRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/de-bai/docx`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ images: [] }),
      });
      if (!docxRes.ok) {
        const data = await docxRes.json().catch(() => ({}));
        throw new Error(data?.error || "Đã lưu nhưng không tải được bản .docx.");
      }
      downloadBlob(await docxRes.blob(), `${exam}_de_bai.docx`);
      setInfo(`Đã lưu đề bài vào bộ ${exam} và tải bản .docx về máy.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không lưu/tải được đề bài.");
    } finally {
      setBusy(null);
    }
  };

  const modelsByVendor = useMemo(() => {
    const out: Record<string, AiModel[]> = {};
    (settings?.models || []).forEach((m) => {
      (out[m.vendor] ||= []).push(m);
    });
    return out;
  }, [settings]);

  const draftVendor = useMemo(() => {
    if (baseUrlDraft.trim()) return "Dịch vụ trung gian (giao thức OpenAI)";
    const id = modelDraft.trim().toLowerCase();
    if (!id) return "";
    if (id.startsWith("claude")) return "Claude (Anthropic)";
    if (id.startsWith("gemini")) return "Gemini (Google)";
    return "GPT (OpenAI)";
  }, [modelDraft, baseUrlDraft]);

  const vendorChanged = !!settings && !!draftVendor && draftVendor !== settings.vendor;

  return (
    <section className="card overflow-hidden">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-violet-50 via-indigo-50 to-sky-50 px-6 py-4 text-left"
      >
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-500 to-indigo-600 text-white shadow-sm">
          <Sparkles size={18} />
        </div>
        <div className="min-w-0 flex-1">
          <p className="eyebrow">Trợ lý</p>
          <h2 className="text-sm font-bold text-slate-800">Soạn đề &amp; sinh code bằng AI</h2>
        </div>
        {settings && (
          <span className={`hidden shrink-0 rounded-full px-2.5 py-1 text-[11px] font-bold sm:inline ${
            settings.ready ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}`}>
            {settings.ready ? settings.model : "Chưa có API key"}
          </span>
        )}
        <ChevronDown size={18} className={`shrink-0 text-slate-400 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>

      {open && (
        <div className="space-y-6 p-6">
          {error && (
            <p className="flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs font-medium leading-relaxed text-rose-700">
              <AlertTriangle size={14} className="mt-0.5 shrink-0" /> {error}
            </p>
          )}
          {info && (
            <p className="flex items-start gap-2 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-xs font-medium leading-relaxed text-emerald-700">
              <Check size={14} className="mt-0.5 shrink-0" /> {info}
            </p>
          )}
          {restoredAt !== null && (
            <div className="flex flex-wrap items-center gap-2 rounded-xl border border-indigo-200 bg-indigo-50 p-3 text-xs text-indigo-800">
              <RotateCcw size={14} className="shrink-0" />
              <span className="font-medium">
                {examId.trim()
                  ? <>Đang tiếp tục bản soạn dở của bộ <span className="font-mono font-bold">{examId.trim()}</span></>
                  : "Đang tiếp tục bản soạn dở (chưa đặt mã bộ testcase)"}
                {" · lưu lúc "}{new Date(restoredAt).toLocaleString("vi-VN")}
              </span>
              {draftTrimmed.length > 0 && (
                <span className="rounded bg-amber-100 px-1.5 py-0.5 font-semibold text-amber-800">
                  Bộ nhớ trình duyệt đầy nên không giữ được {draftTrimmed.join(" và ")} — bấm "Sinh lại" khi cần.
                </span>
              )}
              <button onClick={discardDraft}
                className="ml-auto rounded-lg border border-indigo-200 bg-white px-2.5 py-1 font-semibold text-indigo-700 hover:bg-indigo-100">
                Bắt đầu lại
              </button>
            </div>
          )}

          {/* ── Cấu hình LLM ── */}
          <div className="rounded-2xl border border-slate-200">
            <button
              onClick={() => setSettingsOpen((v) => !v)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left"
            >
              <Settings2 size={15} className="text-slate-500" />
              <span className="text-sm font-bold text-slate-700">Chọn model &amp; API key</span>
              {settings?.hasApiKey && (
                <span className="ml-2 font-mono text-[11px] text-slate-400">{settings.apiKeyMasked}</span>
              )}
              <ChevronDown size={16} className={`ml-auto text-slate-400 transition-transform ${settingsOpen ? "rotate-180" : ""}`} />
            </button>
            {settingsOpen && (
              <div className="space-y-3 border-t border-slate-100 p-4">
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <Field label="Model AI">
                    <select
                      value={customModel ? "__custom__" : modelDraft}
                      onChange={(e) => {
                        if (e.target.value === "__custom__") { setCustomModel(true); return; }
                        setCustomModel(false);
                        setModelDraft(e.target.value);
                      }}
                      className={inputClass}
                    >
                      {Object.entries(modelsByVendor).map(([vendor, list]) => (
                        <optgroup key={vendor} label={vendor}>
                          {list.map((m) => (
                            <option key={m.id} value={m.id}>{m.label}</option>
                          ))}
                        </optgroup>
                      ))}
                      <option value="__custom__">Tự nhập mã model khác…</option>
                    </select>
                  </Field>
                  <Field
                    label="API key"
                    hint={vendorChanged
                      ? `Đổi sang ${draftVendor} thì phải nhập key mới của hãng đó`
                      : undefined}
                  >
                    <div className="flex items-center gap-2">
                      <KeyRound size={14} className="shrink-0 text-slate-400" />
                      <input
                        type="text"
                        name="grader-ai-key"
                        value={apiKeyDraft}
                        onChange={(e) => setApiKeyDraft(e.target.value.replace(/[^\x21-\x7E]/g, ""))}
                        placeholder={settings?.hasApiKey && !vendorChanged
                          ? settings.apiKeyMasked || "••••" : "Dán API key vào đây"}
                        className={inputClass}
                        style={{ WebkitTextSecurity: "disc" } as React.CSSProperties}
                        autoComplete="off"
                        spellCheck={false}
                        data-lpignore="true"
                        data-1p-ignore
                        data-form-type="other"
                      />
                    </div>
                  </Field>
                  {customModel && (
                    <div className="sm:col-span-2">
                      <Field label="Mã model" hint="Bắt đầu bằng claude… / gpt… / gemini… để gọi đúng hãng">
                        <input
                          value={modelDraft}
                          onChange={(e) => setModelDraft(e.target.value)}
                          placeholder="VD: claude-sonnet-5"
                          className={`${inputClass} font-mono`}
                        />
                      </Field>
                    </div>
                  )}
                  <div className="sm:col-span-2">
                    <Field label="Endpoint riêng (tùy chọn)">
                      <input
                        value={baseUrlDraft}
                        onChange={(e) => setBaseUrlDraft(e.target.value)}
                        placeholder="https://api.dich-vu-cua-ban.com/v1"
                        className={`${inputClass} font-mono`}
                      />
                    </Field>
                  </div>
                </div>
                {settings?.keyWarning && (
                  <p className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-2.5 text-[11px] leading-relaxed text-amber-800">
                    <AlertTriangle size={13} className="mt-0.5 shrink-0" /> {settings.keyWarning}
                  </p>
                )}
                <div className="flex flex-wrap items-center gap-2">
                  <button onClick={testAndSaveSettings}
                    disabled={busy !== null || (!settings?.hasApiKey && !apiKeyDraft.trim())}
                    title="Gọi thử một lượt; gọi được mới lưu — thử hỏng thì cấu hình cũ giữ nguyên"
                    className={primaryBtn}>
                    {busy === "settings" ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />}
                    Kiểm tra &amp; lưu cấu hình
                  </button>
                  {settings?.apiKeyLength ? (
                    <span className="text-[11px] text-slate-400">
                      Key đang lưu: {settings.apiKeyMasked} · {settings.apiKeyLength} ký tự
                    </span>
                  ) : null}
                  {settings?.keyUrl && (
                    <a href={settings.keyUrl} target="_blank" rel="noreferrer"
                      className="text-[11px] font-semibold text-indigo-600 underline-offset-2 hover:underline">
                      Lấy API key của {settings.vendor}
                    </a>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* ── Bước 1: yêu cầu ── */}
          <Step index={1} icon={Wand2}
            title={source === "ai" ? "Mô tả yêu cầu đề" : "Tải đề có sẵn lên"} done={!!deBai}>
            <div className="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-2">
              {([
                { id: "ai" as const, icon: Sparkles, title: "Tạo đề bằng AI",
                  desc: "Mô tả yêu cầu, AI soạn đề rồi bạn sửa lại" },
                { id: "upload" as const, icon: Upload, title: "Tải đề có sẵn lên",
                  desc: "PDF, Word (.docx) hoặc .txt — AI đọc lại để bạn xem" },
              ]).map((choice) => (
                <button key={choice.id} type="button" onClick={() => setSource(choice.id)}
                  className={`flex items-start gap-2.5 rounded-xl border p-3 text-left transition-colors ${
                    source === choice.id
                      ? "border-indigo-300 bg-indigo-50/70 ring-1 ring-indigo-200"
                      : "border-slate-200 bg-white hover:border-slate-300"}`}>
                  <choice.icon size={16} className={`mt-0.5 shrink-0 ${
                    source === choice.id ? "text-indigo-600" : "text-slate-400"}`} />
                  <span className="min-w-0">
                    <span className="block text-sm font-bold text-slate-700">{choice.title}</span>
                    <span className="block text-[11px] leading-relaxed text-slate-500">{choice.desc}</span>
                  </span>
                </button>
              ))}
            </div>

            {source === "upload" ? (
              <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-5 text-center">
                <input id="ai-exam-file" type="file" className="hidden"
                  accept=".pdf,.docx,.txt,.md,.markdown"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    e.target.value = "";
                    if (file) importExam(file);
                  }} />
                <label htmlFor="ai-exam-file"
                  className={`${primaryBtn} mx-auto w-fit cursor-pointer ${busy ? "pointer-events-none opacity-60" : ""}`}>
                  {busy === "import" ? <Loader2 size={15} className="animate-spin" /> : <Upload size={15} />}
                  Chọn file đề
                </label>
                <p className="mt-2.5 text-[11px] leading-relaxed text-slate-500">
                  Nhận .docx, .pdf, .txt, .md. PDF bản scan (chỉ có ảnh) không bóc được chữ — hãy dùng .docx.
                </p>
                {importedName && (
                  <p className="mt-2 font-mono text-[11px] text-emerald-600">Đã đọc: {importedName}</p>
                )}
              </div>
            ) : (
            <>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Chủ đề / bài toán *">
                <input value={req.topic} onChange={(e) => setReq({ ...req, topic: e.target.value })}
                  placeholder="VD: Quản lý sinh viên (thêm/sửa/xóa)" className={inputClass} />
              </Field>
              <Field label="Kiến thức cần kiểm tra">
                <input value={req.knowledge} onChange={(e) => setReq({ ...req, knowledge: e.target.value })}
                  placeholder="MVVM, Riverpod, SQLite, validate form, responsive" className={inputClass} />
              </Field>
              <Field label="Các màn hình">
                <input value={req.screens} onChange={(e) => setReq({ ...req, screens: e.target.value })}
                  placeholder="Danh sách + Chi tiết" className={inputClass} />
              </Field>
              <Field label="Thực thể / dữ liệu">
                <input value={req.entity} onChange={(e) => setReq({ ...req, entity: e.target.value })}
                  placeholder="Student: id, fullName, email, avatar" className={inputClass} />
              </Field>
              <Field label="Chức năng bắt buộc">
                <input value={req.features} onChange={(e) => setReq({ ...req, features: e.target.value })}
                  placeholder="Thêm, sửa, xóa có xác nhận, điều hướng sang chi tiết" className={inputClass} />
              </Field>
              <Field label="Lưu trữ dữ liệu">
                <input value={req.storage} onChange={(e) => setReq({ ...req, storage: e.target.value })}
                  placeholder="SQLite / File / SharedPreferences" className={inputClass} />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Độ khó">
                  <select value={req.difficulty} onChange={(e) => setReq({ ...req, difficulty: e.target.value })} className={inputClass}>
                    <option>Dễ</option><option>Trung bình</option><option>Khó</option>
                  </select>
                </Field>
                <Field label="Thời lượng">
                  <input value={req.duration} onChange={(e) => setReq({ ...req, duration: e.target.value })} className={inputClass} />
                </Field>
              </div>
              <div className="sm:col-span-2">
                <Field label="Yêu cầu thêm">
                  <textarea value={req.note} onChange={(e) => setReq({ ...req, note: e.target.value })} rows={2}
                    placeholder="VD: bắt buộc dùng riverpod generator; danh sách hiển thị dạng grid trên tablet"
                    className={inputClass} />
                </Field>
              </div>
            </div>
            <button onClick={draftExam} disabled={busy !== null} className={`${primaryBtn} mt-3`}>
              {busy === "draft" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />} Sinh đề bài
            </button>
            </>
            )}
          </Step>

          {/* ── Bước 2: đề bài ── */}
          {!!deBai && (
            <Step index={2} icon={FileText} title="Xem lại &amp; sửa đề bài" done={examAccepted}>
              {summary && <p className="mb-2 rounded-xl bg-slate-50 p-3 text-xs leading-relaxed text-slate-600">{summary}</p>}
              <textarea
                value={deBai}
                onChange={(e) => { setDeBai(e.target.value); setExamAccepted(false); }}
                rows={16}
                className="custom-scrollbar w-full rounded-xl border border-slate-200 bg-white p-3 font-mono text-xs leading-relaxed text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <input
                  value={revisePrompt}
                  onChange={(e) => setRevisePrompt(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseExam(); } }}
                  placeholder="Nhắc AI sửa: thêm màn hình chi tiết, bỏ yêu cầu SQLite, chia lại điểm…"
                  className={`${inputClass} min-w-[240px] flex-1`}
                />
                <button onClick={reviseExam} disabled={busy !== null} className={ghostBtn}>
                  {busy === "revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Nhờ AI sửa
                </button>
                <button
                  onClick={() => { setExamAccepted(true); setInfo("Đã chốt đề bài. Sang bước sinh khung starter / app lời giải mẫu."); }}
                  className={primaryBtn}
                >
                  <Check size={15} /> Chấp nhận đề bài
                </button>
              </div>
            </Step>
          )}

          {/* ── Bước 3: khung starter ── */}
          {examAccepted && (
            <Step index={3} icon={FileCode2} title="Khung starter phát cho sinh viên" done={false}>
              <p className="mb-3 rounded-xl bg-slate-50 p-3 text-[11px] leading-relaxed text-slate-600">
                Khung chỉ gồm <strong>class, thuộc tính, chữ ký hàm</strong>. Thân hàm luôn là{" "}
                <span className="font-mono">TODO</span> và giao diện luôn là{" "}
                <span className="font-mono">Placeholder()</span> — phần UI và logic là bài thi của
                sinh viên, hệ thống không cho AI viết sẵn.
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <button onClick={proposeStarter} disabled={busy !== null} className={primaryBtn}>
                  {busy === "starter" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                  {starterFiles.length ? "Sinh lại khung" : "Sinh khung starter"}
                </button>
                {starterFiles.length > 0 && (
                  <>
                    <button onClick={recheckStarter} disabled={busy !== null} className={ghostBtn}>
                      {busy === "starter-check" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />}
                      Kiểm tra cú pháp
                    </button>
                    <button onClick={saveAndDownloadStarter} disabled={busy !== null || !examId.trim()} className={ghostBtn}
                      title="Lưu khung vào bộ testcase (phát cho SV) và tải .zip về máy">
                      {busy === "starter-save" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
                      Lưu &amp; tải khung (.zip)
                    </button>
                  </>
                )}
              </div>

              {starterWarnings.length > 0 && (
                <ul className="mt-3 space-y-1">
                  {starterWarnings.map((w, i) => (
                    <li key={i} className="flex items-start gap-2 rounded-lg bg-amber-50 p-2 text-[11px] leading-relaxed text-amber-800">
                      <Info size={12} className="mt-0.5 shrink-0" /> {w}
                    </li>
                  ))}
                </ul>
              )}

              {syntax && <SyntaxBanner syntax={syntax} />}

              {starterFiles.length > 0 && (
                <CodeEditor
                  files={starterFiles}
                  openPath={openFile}
                  onOpen={setOpenFile}
                  onEdit={(path, content) => {
                    setStarterFiles((cur) => cur.map((x) => x.path === path ? { ...x, content } : x));
                    setSyntax(null);
                    setStarterEdited(true);
                  }}
                />
              )}

              {starterFiles.length > 0 && (
                <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
                  {starterEdited && (
                    <p className="mb-2 text-[11px] font-semibold leading-relaxed text-amber-700">
                      Bạn đang có sửa tay chưa lưu — lượt AI sửa sẽ sinh lại toàn bộ file và thay phần đó.
                    </p>
                  )}
                  <div className="flex flex-wrap items-center gap-2">
                    <input
                      value={starterPrompt}
                      onChange={(e) => setStarterPrompt(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseStarter(); } }}
                      placeholder="VD: thêm màn hình chi tiết, bỏ lớp repository, đổi User.phone sang String?…"
                      className={`${inputClass} min-w-[240px] flex-1`}
                    />
                    <button onClick={reviseStarter} disabled={busy !== null || !starterSpec} className={ghostBtn}
                      title={starterSpec ? undefined : "Hãy bấm Sinh khung starter trước"}>
                      {busy === "starter-revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />}
                      Nhờ AI sửa khung
                    </button>
                  </div>
                </div>
              )}
            </Step>
          )}

          {/* ── Bước 4 (MỚI): app lời giải mẫu (Golden Solution) ── */}
          {examAccepted && (
            <Step index={4} icon={FlaskConical} title="App lời giải mẫu (Golden Solution) — thử nghiệm" done={false}>
              <p className="mb-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-[11px] leading-relaxed text-amber-800">
                <strong>Thử nghiệm.</strong> Khác với khung starter, ở đây AI viết{" "}
                <strong>code thật, chạy được</strong> — cả một app đáp án hoàn chỉnh để hệ thống
                ghi lại thao tác (Golden Solution Record–Abstract–Replay). AI sinh cả app khó chắc
                build được ngay lần đầu: hãy tải .zip, tự tải lên ô "Golden Solution" ở Bước 2 phía
                dưới, rồi bấm "Build &amp; mở Golden" để biết chắc chắn.
                {!databaseName?.trim() && (
                  <> Bạn chưa khai tên file database ở Bước 1 — AI sẽ tự chọn một tên và ghi chú lại,
                    hãy khai đúng tên đó sau khi xem code.</>
                )}
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <button onClick={proposeGolden} disabled={busy !== null} className={primaryBtn}>
                  {busy === "golden" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                  {goldenFiles.length ? "Sinh lại app" : "Sinh app lời giải mẫu"}
                </button>
                {goldenFiles.length > 0 && (
                  <>
                    <button onClick={recheckGolden} disabled={busy !== null} className={ghostBtn}>
                      {busy === "golden-check" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />}
                      Kiểm tra cú pháp
                    </button>
                    <button onClick={downloadGolden} disabled={busy !== null} className={ghostBtn}
                      title="Tải .zip để tự upload vào ô Golden Solution ở Bước 2">
                      {busy === "golden-download" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
                      Tải app (.zip)
                    </button>
                  </>
                )}
              </div>

              {goldenWarnings.length > 0 && (
                <ul className="mt-3 space-y-1">
                  {goldenWarnings.map((w, i) => (
                    <li key={i} className="flex items-start gap-2 rounded-lg bg-amber-50 p-2 text-[11px] leading-relaxed text-amber-800">
                      <Info size={12} className="mt-0.5 shrink-0" /> {w}
                    </li>
                  ))}
                </ul>
              )}

              {goldenSyntax && <SyntaxBanner syntax={goldenSyntax} />}

              {goldenFiles.length > 0 && (
                <CodeEditor
                  files={goldenFiles.map((f) => ({ path: f.path, content: f.content, summary: "" }))}
                  openPath={openGoldenFile}
                  onOpen={setOpenGoldenFile}
                  onEdit={(path, content) => {
                    setGoldenFiles((cur) => cur.map((x) => x.path === path ? { ...x, content } : x));
                    setGoldenSyntax(null);
                    setGoldenEdited(true);
                  }}
                />
              )}

              {goldenFiles.length > 0 && (
                <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
                  {goldenEdited && (
                    <p className="mb-2 text-[11px] font-semibold leading-relaxed text-amber-700">
                      Bạn đang có sửa tay chưa lưu — lượt AI sửa sẽ sinh lại toàn bộ file và thay phần đó.
                    </p>
                  )}
                  <div className="flex flex-wrap items-center gap-2">
                    <input
                      value={goldenPrompt}
                      onChange={(e) => setGoldenPrompt(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseGolden(); } }}
                      placeholder="VD: sửa lỗi build, thêm màn chi tiết, đổi tên biến…"
                      className={`${inputClass} min-w-[240px] flex-1`}
                    />
                    <button onClick={reviseGolden} disabled={busy !== null} className={ghostBtn}>
                      {busy === "golden-revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />}
                      Nhờ AI sửa app
                    </button>
                  </div>
                </div>
              )}
            </Step>
          )}

          {/* Đề phát cho SV */}
          {!!deBai && (
            <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-4">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-slate-700">Đề cho sinh viên</p>
              </div>
              <button onClick={saveHandout} disabled={busy !== null || !examId.trim()} className={primaryBtn}>
                {busy === "handout" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
                Lưu &amp; tải đề (.docx)
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

const inputClass =
  "w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100";
const primaryBtn =
  "flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-3.5 py-2 text-xs font-semibold text-white shadow-sm transition-all hover:from-violet-700 hover:to-indigo-700 active:scale-[0.98] disabled:cursor-not-allowed disabled:from-slate-300 disabled:to-slate-300";
const ghostBtn =
  "flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50";

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] font-bold uppercase tracking-wider text-slate-500">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[10px] text-slate-400">{hint}</span>}
    </label>
  );
}

/**
 * Một bước của trợ lý — THU GỌN ĐƯỢC. Bước nào đã chốt (done) thì tự gập lại, bấm tiêu đề mở lại.
 */
function Step({ index, icon: Icon, title, done, children }: {
  index: number; icon: React.ComponentType<{ size?: number; className?: string }>;
  title: string; done: boolean; children: React.ReactNode;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const wasDone = useRef(done);
  useEffect(() => {
    if (done && !wasDone.current) setCollapsed(true);
    wasDone.current = done;
  }, [done]);

  return (
    <div className="rounded-2xl border border-slate-200 p-4">
      <button type="button" onClick={() => setCollapsed((v) => !v)}
        aria-expanded={!collapsed}
        className={`flex w-full items-center gap-2 text-left ${collapsed ? "" : "mb-3"}`}>
        <span className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-bold ${
          done ? "bg-emerald-100 text-emerald-700" : "bg-indigo-100 text-indigo-700"}`}>
          {done ? <Check size={13} /> : index}
        </span>
        <Icon size={15} className="shrink-0 text-indigo-500" />
        <h3 className="text-sm font-bold text-slate-800">{title}</h3>
        <ChevronDown size={16}
          className={`ml-auto shrink-0 text-slate-400 transition-transform ${collapsed ? "-rotate-90" : ""}`} />
      </button>
      {!collapsed && children}
    </div>
  );
}

function SyntaxBanner({ syntax }: { syntax: { ok: boolean | null; message: string } }) {
  return (
    <p className={`mt-3 flex items-start gap-2 rounded-xl border p-2.5 text-[11px] leading-relaxed ${
      syntax.ok === true ? "border-emerald-200 bg-emerald-50 text-emerald-700"
        : syntax.ok === false ? "border-rose-200 bg-rose-50 text-rose-700"
          : "border-amber-200 bg-amber-50 text-amber-800"}`}>
      {syntax.ok === true ? <Check size={13} className="mt-0.5 shrink-0" />
        : <AlertTriangle size={13} className="mt-0.5 shrink-0" />}
      {syntax.message}
    </p>
  );
}

/** Trình soạn thảo kiểu IDE dùng chung cho khung starter và app lời giải mẫu: cây file bên trái, code bên phải. */
function CodeEditor({ files, openPath, onOpen, onEdit }: {
  files: { path: string; content: string; summary: string }[];
  openPath: string | null;
  onOpen: (path: string) => void;
  onEdit: (path: string, content: string) => void;
}) {
  const active = files.find((f) => f.path === openPath) || files[0];
  const lines = active.content.split("\n");
  return (
    <div className="mt-3 flex min-h-[22rem] overflow-hidden rounded-xl border border-slate-800 bg-slate-900">
      <div className="custom-scrollbar w-52 shrink-0 overflow-y-auto border-r border-slate-800 bg-slate-950/60 py-2">
        <p className="px-3 pb-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-500">
          {files.length} file
        </p>
        {files.map((f) => (
          <button key={f.path} type="button" onClick={() => onOpen(f.path)}
            title={f.summary ? `${f.path} · ${f.summary}` : f.path}
            className={`flex w-full items-center gap-1.5 px-3 py-1.5 text-left font-mono text-[11px] transition-colors ${
              f.path === active.path
                ? "bg-slate-800 text-indigo-300"
                : "text-slate-400 hover:bg-slate-800/60 hover:text-slate-200"}`}>
            <FileCode2 size={12} className="shrink-0" />
            <span className="truncate">{f.path}</span>
          </button>
        ))}
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center gap-2 border-b border-slate-800 px-3 py-2">
          <span className="font-mono text-[11px] font-semibold text-slate-200">{active.path}</span>
          {active.summary && <span className="truncate text-[10px] text-slate-500">{active.summary}</span>}
          <span className="ml-auto shrink-0 text-[10px] text-slate-600">{lines.length} dòng</span>
        </div>
        <div className="custom-scrollbar flex min-h-0 flex-1 overflow-auto">
          <pre aria-hidden className="select-none border-r border-slate-800 bg-slate-950/40 px-2 py-3 text-right font-mono text-[11px] leading-relaxed text-slate-600">
            {lines.map((_, i) => i + 1).join("\n")}
          </pre>
          <textarea
            value={active.content}
            onChange={(e) => onEdit(active.path, e.target.value)}
            spellCheck={false}
            wrap="off"
            className="min-h-full w-full resize-none bg-transparent px-3 py-3 font-mono text-[11px] leading-relaxed text-slate-100 outline-none"
            style={{ minHeight: `${lines.length * 1.5 + 1.5}rem` }}
          />
        </div>
      </div>
    </div>
  );
}
