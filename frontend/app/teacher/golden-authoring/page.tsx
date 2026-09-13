"use client";

// Trang "Tạo Golden" — chọn một mã đề đã soạn (trang "Tạo đề"), AI sinh khung starter ĐẦY ĐỦ dự
// án (android/, gradle, pubspec...) + app "lời giải mẫu" (Golden Solution), rồi gắn Golden Solution
// vào một Suite (tự tạo ngầm qua /ai/golden/ensure-suite — giáo viên không phải tự điền form Suite
// như trang "Bộ chấm Golden" cũ), build thử (Flutter Web trong Docker) và chụp ảnh minh họa từng
// màn hình để đính kèm đề bài.
//
// Sau bước này, sang trang "Bộ chấm Golden" (suite đã tự tạo) để ghi kịch bản Record–Abstract–Replay
// như quy trình cũ — trang này không đụng gì tới ghi kịch bản/publish.

import { useCallback, useEffect, useRef, useState } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import { downloadBlob } from "@/lib/mockup-image";
import AiSettingsPanel from "@/components/testcases/AiSettingsPanel";
import { Field, Step, SyntaxBanner, CodeEditor, inputClass, primaryBtn, ghostBtn } from "@/components/testcases/AiWizardWidgets";
import {
  Sparkles, Wand2, Loader2, Check, Download, RefreshCw, Info, AlertTriangle,
  FlaskConical, FileCode2, MonitorPlay, Camera, ExternalLink,
} from "lucide-react";

interface AuthoredExam { exam_id: string; title: string; updated_at: string }
interface StarterFile { path: string; content: string; summary: string }
interface GoldenFile { path: string; content: string }

export default function GoldenAuthoringPage() {
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [exams, setExams] = useState<AuthoredExam[]>([]);
  const [examId, setExamId] = useState("");
  const [deBai, setDeBai] = useState("");
  const [databaseName, setDatabaseName] = useState("");
  const [allowedPackagesText, setAllowedPackagesText] = useState("flutter, flutter_test, path, sqflite, sqflite_common_ffi");

  const [suiteId, setSuiteId] = useState<string | null>(null);
  const [suiteCode, setSuiteCode] = useState<string | null>(null);

  // Khung starter
  const [starterFiles, setStarterFiles] = useState<StarterFile[]>([]);
  const [starterWarnings, setStarterWarnings] = useState<string[]>([]);
  const [syntax, setSyntax] = useState<{ ok: boolean | null; message: string } | null>(null);
  const [openFile, setOpenFile] = useState<string | null>(null);
  const [starterPrompt, setStarterPrompt] = useState("");
  const [starterSpec, setStarterSpec] = useState<unknown>(null);
  const [starterEdited, setStarterEdited] = useState(false);

  // Golden Solution
  const [goldenFiles, setGoldenFiles] = useState<GoldenFile[]>([]);
  const [goldenWarnings, setGoldenWarnings] = useState<string[]>([]);
  const [goldenSyntax, setGoldenSyntax] = useState<{ ok: boolean | null; message: string } | null>(null);
  const [openGoldenFile, setOpenGoldenFile] = useState<string | null>(null);
  const [goldenPrompt, setGoldenPrompt] = useState("");
  const [goldenEdited, setGoldenEdited] = useState(false);
  const [goldenAttached, setGoldenAttached] = useState(false);

  // Build & chụp ảnh
  const [runtimeUrl, setRuntimeUrl] = useState("");
  const [recorderReady, setRecorderReady] = useState(false);
  const [screenName, setScreenName] = useState("");
  const [capturedScreens, setCapturedScreens] = useState<string[]>([]);
  const goldenFrame = useRef<HTMLIFrameElement>(null);
  const captureResolver = useRef<((b64: string | null) => void) | null>(null);

  const loadExams = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/exam-setup/authored-list`);
      const data = await res.json();
      setExams(Array.isArray(data) ? data : []);
    } catch { setExams([]); }
  }, []);
  useEffect(() => { loadExams(); }, [loadExams]);

  // Nhận postMessage từ iframe Golden App (recorder bridge do GoldenRuntimeService tiêm sẵn).
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (!event.data || typeof event.data !== "object") return;
      if (event.data.type === "GOLDEN_RECORDER_READY") setRecorderReady(true);
      if (event.data.type === "GOLDEN_RECORDER_SCREENSHOT") {
        const resolve = captureResolver.current;
        captureResolver.current = null;
        if (resolve) resolve(event.data.payload?.png_base64 || null);
        if (event.data.payload?.error) setError(event.data.payload.error);
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, []);

  const call = async <T,>(path: string, body: unknown, label: string): Promise<T | null> => {
    setBusy(label); setError(null); setInfo(null);
    try {
      const res = await fetch(`${API_BASE}${path}`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
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

  const selectExam = async (id: string) => {
    setExamId(id);
    setSuiteId(null); setSuiteCode(null);
    setStarterFiles([]); setStarterSpec(null); setStarterWarnings([]); setSyntax(null);
    setGoldenFiles([]); setGoldenWarnings([]); setGoldenSyntax(null); setGoldenAttached(false);
    setRuntimeUrl(""); setRecorderReady(false); setCapturedScreens([]);
    if (!id) { setDeBai(""); return; }
    setBusy("load-exam"); setError(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(id)}/handout`);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không đọc được đề này.");
      setDeBai(String(data.de_bai || ""));
      if (!data.de_bai) setError(`Đề ${id} chưa có nội dung — sang trang "Tạo đề" soạn trước.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không đọc được đề này.");
    } finally {
      setBusy(null);
    }
  };

  const allowedPackages = () => allowedPackagesText.split(",").map((p) => p.trim()).filter(Boolean);

  // ── Khung starter ──────────────────────────────────────────────
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
    if (!deBai.trim()) { setError("Chưa có đề bài — chọn mã đề trước."); return; }
    const data = await call<Parameters<typeof takeStarter>[0]>("/ai/starter/propose", { de_bai: deBai }, "starter");
    if (data) takeStarter(data);
  };
  const reviseStarter = async () => {
    if (!starterPrompt.trim()) { setError("Hãy mô tả bạn muốn AI sửa gì trong khung starter."); return; }
    if (starterEdited && !confirm("AI sẽ sinh lại toàn bộ khung, phần code bạn vừa sửa tay sẽ bị thay. Tiếp tục?")) return;
    const data = await call<Parameters<typeof takeStarter>[0]>("/ai/starter/revise",
      { de_bai: deBai, spec: starterSpec, instruction: starterPrompt }, "starter-revise");
    if (data) { takeStarter(data); setStarterPrompt(""); setInfo("AI đã sửa khung starter."); }
  };
  const recheckStarter = async () => {
    const data = await call<{ syntax_ok: boolean | null; syntax_message: string }>(
      "/ai/starter/check", { files: starterFiles }, "starter-check");
    if (data) setSyntax({ ok: data.syntax_ok, message: data.syntax_message });
  };
  const downloadStarter = async () => {
    setBusy("starter-download"); setError(null);
    try {
      const res = await fetch(`${API_BASE}/ai/starter/download`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exam_id: examId, files: starterFiles }),
      });
      if (!res.ok) { const d = await res.json().catch(() => ({})); throw new Error(d?.error || "Không tải được khung starter."); }
      downloadBlob(await res.blob(), `${examId || "starter"}_starter.zip`);
      setInfo("Đã tải khung starter — đầy đủ android/, gradle, icon... mở được ngay bằng Android Studio hoặc `flutter run`.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được khung starter.");
    } finally { setBusy(null); }
  };

  // ── Golden Solution ────────────────────────────────────────────
  const takeGolden = (data: { files: GoldenFile[]; warnings: string[]; syntax_ok: boolean | null; syntax_message: string }) => {
    setGoldenFiles(data.files || []);
    setGoldenWarnings(data.warnings || []);
    setGoldenSyntax({ ok: data.syntax_ok, message: data.syntax_message });
    setOpenGoldenFile(data.files?.[0]?.path ?? null);
    setGoldenEdited(false);
    setGoldenAttached(false);
  };
  const proposeGolden = async () => {
    if (!deBai.trim()) { setError("Chưa có đề bài — chọn mã đề trước."); return; }
    const data = await call<Parameters<typeof takeGolden>[0]>("/ai/golden/propose", {
      de_bai: deBai, database_name: databaseName, allowed_packages: allowedPackages(),
    }, "golden");
    if (data) takeGolden(data);
  };
  const reviseGolden = async () => {
    if (!goldenPrompt.trim()) { setError("Hãy mô tả bạn muốn AI sửa gì trong app lời giải mẫu."); return; }
    if (goldenEdited && !confirm("AI sẽ sinh lại toàn bộ app, phần code bạn vừa sửa tay sẽ bị thay. Tiếp tục?")) return;
    const data = await call<Parameters<typeof takeGolden>[0]>("/ai/golden/revise", {
      de_bai: deBai, spec: goldenFiles, instruction: goldenPrompt,
      database_name: databaseName, allowed_packages: allowedPackages(),
    }, "golden-revise");
    if (data) { takeGolden(data); setGoldenPrompt(""); setInfo("AI đã sửa app lời giải mẫu."); }
  };
  const recheckGolden = async () => {
    const data = await call<{ syntax_ok: boolean | null; syntax_message: string }>(
      "/ai/golden/check", { files: goldenFiles }, "golden-check");
    if (data) setGoldenSyntax({ ok: data.syntax_ok, message: data.syntax_message });
  };

  const ensureSuite = async (): Promise<string | null> => {
    if (suiteId) return suiteId;
    if (!examId.trim()) { setError("Hãy chọn mã đề trước."); return null; }
    setBusy("ensure-suite"); setError(null);
    try {
      const res = await fetch(`${API_BASE}/ai/golden/ensure-suite`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ exam_id: examId }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không tạo được bộ chấm.");
      setSuiteId(data.id); setSuiteCode(data.suite_code);
      return data.id as string;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tạo được bộ chấm.");
      return null;
    } finally { setBusy(null); }
  };

  /** Gói code hiện có thành .zip (tái dùng /ai/golden/download — đã kèm sẵn khung dự án đầy đủ),
   *  rồi upload y nguyên zip đó lên đúng ô GOLDEN_SOLUTION của suite. */
  const attachGolden = async () => {
    if (goldenFiles.length === 0) { setError("Chưa có code Golden Solution để gắn."); return; }
    setBusy("attach"); setError(null); setInfo(null);
    try {
      const sid = await ensureSuite();
      if (!sid) return;
      const zipRes = await fetch(`${API_BASE}/ai/golden/download`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exam_id: examId, files: goldenFiles }),
      });
      if (!zipRes.ok) throw new Error("Không đóng gói được Golden Solution.");
      const blob = await zipRes.blob();
      const form = new FormData();
      form.append("file", blob, `${examId}_golden_solution.zip`);
      const upRes = await fetch(`${API_BASE}/behavior-authoring/suites/${sid}/artifacts/GOLDEN_SOLUTION`, {
        method: "POST", body: form,
      });
      const upData = await upRes.json().catch(() => ({}));
      if (!upRes.ok) throw new Error(upData?.error || "Không gắn được Golden Solution vào bộ chấm.");
      setGoldenAttached(true);
      setInfo(`Đã gắn Golden Solution vào bộ chấm ${suiteCode}. Bấm "Build & mở Golden" để chạy thử và chụp ảnh.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không gắn được Golden Solution.");
    } finally { setBusy(null); }
  };

  const deployGolden = async () => {
    const sid = suiteId || (await ensureSuite());
    if (!sid) return;
    if (!goldenAttached) { setError("Hãy bấm \"Gắn vào bộ chấm\" trước khi build."); return; }
    setBusy("deploy"); setError(null); setRuntimeUrl(""); setRecorderReady(false);
    try {
      const res = await fetch(`${API_BASE}/behavior-authoring/suites/${sid}/runtime/deploy`, { method: "POST" });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Build Golden runtime thất bại.");
      if (!data.available) throw new Error(data.message || "Golden runtime chưa sẵn sàng.");
      setRuntimeUrl(String(data.runtime_url || data.runtime_path || ""));
      setInfo(data.cached ? "Golden runtime đã sẵn sàng từ bản build hiện tại." : "Đã build xong — mở Golden App bên dưới để đi tới từng màn rồi chụp ảnh.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Build Golden runtime thất bại (mất vài phút, cần Docker đang chạy).");
    } finally { setBusy(null); }
  };

  /** Xin ảnh từ iframe qua postMessage, chờ tối đa 5s rồi báo lỗi thay vì treo mãi. */
  const requestScreenshotFromFrame = (): Promise<string | null> => {
    return new Promise((resolve) => {
      if (!goldenFrame.current?.contentWindow) { resolve(null); return; }
      captureResolver.current = resolve;
      goldenFrame.current.contentWindow.postMessage({ type: "GOLDEN_RECORDER_COMMAND", action: "capture_screenshot" }, "*");
      setTimeout(() => {
        if (captureResolver.current === resolve) { captureResolver.current = null; resolve(null); }
      }, 5000);
    });
  };

  const captureScreenshot = async () => {
    if (!suiteId) return;
    const name = screenName.trim() || `man_hinh_${capturedScreens.length + 1}`;
    setBusy("capture"); setError(null);
    try {
      const png = await requestScreenshotFromFrame();
      if (!png) throw new Error("Không chụp được — mở Golden App bên dưới rồi thử lại.");
      const res = await fetch(`${API_BASE}/behavior-authoring/suites/${suiteId}/golden-screenshot`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ screen_name: name, png_base64: png }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không lưu được ảnh.");
      setCapturedScreens((cur) => [...cur.filter((s) => s !== name), name]);
      setScreenName("");
      setInfo(`Đã chụp và lưu ảnh "${name}" — sẽ chèn vào cuối bản .docx của đề khi tải về ở trang "Tạo đề".`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không chụp được ảnh.");
    } finally { setBusy(null); }
  };

  return (
    <SidebarLayout activePath="/teacher/golden-authoring" title="Tạo Golden bằng AI"
      subtitle="Chọn một đề đã soạn — AI sinh khung starter đầy đủ dự án + Golden Solution + ảnh minh họa">
      <div className="space-y-6">
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

        <AiSettingsPanel />

        <section className="card p-5">
          <h2 className="mb-3 text-sm font-bold text-slate-800">Chọn đề</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Mã đề *">
              <select value={examId} onChange={(e) => selectExam(e.target.value)} className={`${inputClass} font-mono`}>
                <option value="">— chọn đề đã soạn —</option>
                {exams.map((x) => <option key={x.exam_id} value={x.exam_id}>{x.exam_id} — {x.title}</option>)}
              </select>
            </Field>
            <Field label="Tên file database SQLite (nếu đề có lưu trữ)">
              <input value={databaseName} onChange={(e) => setDatabaseName(e.target.value)}
                placeholder="VD: app.db" className={`${inputClass} font-mono`} />
            </Field>
            <div className="sm:col-span-2">
              <Field label="Package được phép dùng" hint="Chỉ để gợi ý AI — vẫn bị chặn lại theo danh sách đã đóng băng ở ảnh chấm">
                <input value={allowedPackagesText} onChange={(e) => setAllowedPackagesText(e.target.value)} className={`${inputClass} font-mono`} />
              </Field>
            </div>
          </div>
          {busy === "load-exam" && <p className="mt-2 flex items-center gap-2 text-xs text-slate-400"><Loader2 size={14} className="animate-spin" /> Đang tải đề…</p>}
          {suiteCode && (
            <p className="mt-3 flex items-center gap-2 text-xs text-indigo-600">
              <FlaskConical size={14} /> Đang dùng bộ chấm <span className="font-mono font-bold">{suiteCode}</span>
              <a href={`/teacher/archive?suite=${suiteId}`} target="_blank" rel="noreferrer" className="ml-1 inline-flex items-center gap-1 text-indigo-500 hover:underline">
                Mở trang Bộ chấm Golden <ExternalLink size={11} />
              </a>
            </p>
          )}
        </section>

        {!!deBai && (
          <>
            <Step index={1} icon={FileCode2} title="Khung starter (đầy đủ dự án — android/, gradle, icon...)" done={false}>
              <p className="mb-3 rounded-xl bg-slate-50 p-3 text-[11px] leading-relaxed text-slate-600">
                Khung chỉ gồm <strong>class, thuộc tính, chữ ký hàm</strong> (thân hàm luôn <span className="font-mono">TODO</span>).
                Tải về là một dự án Flutter mở được ngay bằng Android Studio hoặc <span className="font-mono">flutter run</span> —
                không chỉ vài file .dart rời.
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <button onClick={proposeStarter} disabled={busy !== null} className={primaryBtn}>
                  {busy === "starter" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                  {starterFiles.length ? "Sinh lại khung" : "Sinh khung starter"}
                </button>
                {starterFiles.length > 0 && (
                  <>
                    <button onClick={recheckStarter} disabled={busy !== null} className={ghostBtn}>
                      {busy === "starter-check" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />} Kiểm tra cú pháp
                    </button>
                    <button onClick={downloadStarter} disabled={busy !== null} className={ghostBtn}>
                      {busy === "starter-download" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />} Tải khung đầy đủ (.zip)
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
                <CodeEditor files={starterFiles} openPath={openFile} onOpen={setOpenFile}
                  onEdit={(path, content) => {
                    setStarterFiles((cur) => cur.map((x) => x.path === path ? { ...x, content } : x));
                    setSyntax(null); setStarterEdited(true);
                  }} />
              )}
              {starterFiles.length > 0 && (
                <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <input value={starterPrompt} onChange={(e) => setStarterPrompt(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseStarter(); } }}
                      placeholder="VD: thêm màn hình chi tiết, đổi tên hàm…" className={`${inputClass} min-w-[240px] flex-1`} />
                    <button onClick={reviseStarter} disabled={busy !== null || !starterSpec} className={ghostBtn}>
                      {busy === "starter-revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Nhờ AI sửa khung
                    </button>
                  </div>
                </div>
              )}
            </Step>

            <Step index={2} icon={FlaskConical} title="App lời giải mẫu (Golden Solution)" done={goldenAttached}>
              <p className="mb-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-[11px] leading-relaxed text-amber-800">
                Khác khung starter, ở đây AI viết <strong>code thật, chạy được</strong> — cả app đáp án
                để hệ thống ghi lại thao tác (Golden Solution Record–Abstract–Replay).
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <button onClick={proposeGolden} disabled={busy !== null} className={primaryBtn}>
                  {busy === "golden" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                  {goldenFiles.length ? "Sinh lại app" : "Sinh app lời giải mẫu"}
                </button>
                {goldenFiles.length > 0 && (
                  <>
                    <button onClick={recheckGolden} disabled={busy !== null} className={ghostBtn}>
                      {busy === "golden-check" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />} Kiểm tra cú pháp
                    </button>
                    <button onClick={attachGolden} disabled={busy !== null} className={primaryBtn}>
                      {busy === "attach" ? <Loader2 size={15} className="animate-spin" /> : <Check size={15} />} Gắn vào bộ chấm
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
                <CodeEditor files={goldenFiles.map((f) => ({ path: f.path, content: f.content, summary: "" }))}
                  openPath={openGoldenFile} onOpen={setOpenGoldenFile}
                  onEdit={(path, content) => {
                    setGoldenFiles((cur) => cur.map((x) => x.path === path ? { ...x, content } : x));
                    setGoldenSyntax(null); setGoldenEdited(true); setGoldenAttached(false);
                  }} />
              )}
              {goldenFiles.length > 0 && (
                <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
                  {goldenEdited && (
                    <p className="mb-2 text-[11px] font-semibold leading-relaxed text-amber-700">
                      Bạn đang có sửa tay chưa lưu — cần bấm lại "Gắn vào bộ chấm" để cập nhật.
                    </p>
                  )}
                  <div className="flex flex-wrap items-center gap-2">
                    <input value={goldenPrompt} onChange={(e) => setGoldenPrompt(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseGolden(); } }}
                      placeholder="VD: sửa lỗi build, thêm màn chi tiết…" className={`${inputClass} min-w-[240px] flex-1`} />
                    <button onClick={reviseGolden} disabled={busy !== null} className={ghostBtn}>
                      {busy === "golden-revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Nhờ AI sửa app
                    </button>
                  </div>
                </div>
              )}
            </Step>

            <Step index={3} icon={Camera} title="Build & chụp ảnh minh họa" done={capturedScreens.length > 0}>
              <div className="flex flex-wrap items-center gap-2">
                <button onClick={deployGolden} disabled={busy !== null || !goldenAttached} className={primaryBtn}
                  title={goldenAttached ? undefined : "Gắn Golden Solution vào bộ chấm trước (Bước 2)"}>
                  {busy === "deploy" ? <Loader2 size={15} className="animate-spin" /> : <MonitorPlay size={15} />} Build & mở Golden
                </button>
                {runtimeUrl && (
                  <>
                    <input value={screenName} onChange={(e) => setScreenName(e.target.value)}
                      placeholder="Tên màn đang mở, vd: danh_sach" className={`${inputClass} w-56`} />
                    <button onClick={captureScreenshot} disabled={busy !== null || !recorderReady} className={ghostBtn}
                      title={recorderReady ? undefined : "Chờ Golden App tải xong"}>
                      {busy === "capture" ? <Loader2 size={15} className="animate-spin" /> : <Camera size={15} />} Chụp màn đang hiện
                    </button>
                  </>
                )}
              </div>
              {capturedScreens.length > 0 && (
                <p className="mt-2 text-[11px] text-slate-500">Đã chụp: {capturedScreens.join(", ")}</p>
              )}
              {runtimeUrl && (
                <div className="mt-4 overflow-hidden rounded-xl border border-slate-300 bg-slate-100">
                  <iframe ref={goldenFrame} src={runtimeUrl} title="Golden App"
                    className="h-[600px] w-full border-0 bg-white" />
                </div>
              )}
              {!runtimeUrl && (
                <p className="mt-3 text-[11px] text-slate-400">
                  Build lần đầu build Flutter Web trong Docker, có thể mất vài phút. Cần Docker Desktop đang chạy.
                </p>
              )}
            </Step>
          </>
        )}
      </div>
    </SidebarLayout>
  );
}
