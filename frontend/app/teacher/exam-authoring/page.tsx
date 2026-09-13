"use client";

// Trang "Tạo đề" — CHỈ soạn đề bằng AI (chủ đề/kiến thức → đề bài → sửa bằng AI/sửa tay → lưu),
// tách khỏi wizard 4 bước cũ (AiAuthorPanel) theo yêu cầu: không cần tạo Suite/Golden App trước
// mới soạn được đề. Có thể tạo và lưu nhiều đề — mỗi đề là một mã đề gõ tay, lưu độc lập trong
// handout/<examId>/de_bai.md (xem ExamService#listAuthoredExamIds, không cần Suite/testcase nào).
//
// Bước "Tạo Golden" (khung starter, Golden Solution) đã chuyển sang trang riêng /teacher/golden-authoring.

import { useCallback, useEffect, useState } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import { downloadBlob } from "@/lib/mockup-image";
import {
  clearAiDraft, DRAFT_NO_EXAM, fetchAiDraftFromServer, pushAiDraftToServer, readAiDraft, writeAiDraft,
} from "@/lib/aiAuthorDrafts";
import AiSettingsPanel from "@/components/testcases/AiSettingsPanel";
import { Field, Step, inputClass, primaryBtn, ghostBtn } from "@/components/testcases/AiWizardWidgets";
import {
  Sparkles, Wand2, FileText, Loader2, Check, Upload, Download, RotateCcw, AlertTriangle, ListChecks, FilePlus2,
  Database, ShieldCheck,
} from "lucide-react";

interface AuthoredExam { exam_id: string; title: string; updated_at: string }
interface SeedTable {
  name: string; create_sql: string; columns: string[];
  student_rows: (string | number | boolean | null)[][];
  hidden_rows: (string | number | boolean | null)[][];
}

export default function ExamAuthoringPage() {
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  // Danh sách đề đã soạn — để mở lại/tiếp tục.
  const [authored, setAuthored] = useState<AuthoredExam[]>([]);
  const [loadingList, setLoadingList] = useState(false);

  const [examId, setExamId] = useState("");
  const [source, setSource] = useState<"ai" | "upload">("ai");
  const [importedName, setImportedName] = useState("");
  const [req, setReq] = useState({
    topic: "", knowledge: "", screens: "", features: "", entity: "",
    storage: "SQLite", difficulty: "Trung bình", duration: "90 phút", note: "",
  });

  const [deBai, setDeBai] = useState("");
  const [summary, setSummary] = useState("");
  const [revisePrompt, setRevisePrompt] = useState("");
  const [examAccepted, setExamAccepted] = useState(false);

  const [seedTables, setSeedTables] = useState<SeedTable[]>([]);
  const [seedSaved, setSeedSaved] = useState(false);

  const loadAuthored = useCallback(async () => {
    setLoadingList(true);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/authored-list`);
      const data = await res.json();
      setAuthored(Array.isArray(data) ? data : []);
    } catch {
      setAuthored([]);
    } finally {
      setLoadingList(false);
    }
  }, []);

  useEffect(() => { loadAuthored(); }, [loadAuthored]);

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

  const resetForm = () => {
    setDeBai(""); setSummary(""); setExamAccepted(false);
    setImportedName(""); setRevisePrompt("");
    setSeedTables([]); setSeedSaved(false);
    setReq({ topic: "", knowledge: "", screens: "", features: "", entity: "",
      storage: "SQLite", difficulty: "Trung bình", duration: "90 phút", note: "" });
  };

  const startNew = () => {
    setExamId("");
    resetForm();
    setInfo("Đang soạn đề mới — đặt mã đề rồi bấm \"Sinh đề bài\".");
  };

  /** Mở lại một đề đã soạn: nạp de_bai.md + (nếu có) bản nháp form đã lưu trên server. */
  const openExam = async (id: string) => {
    setError(""); setBusy("open");
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(id)}/handout`);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không mở được đề này.");
      setExamId(id);
      setDeBai(String(data.de_bai || ""));
      setSummary("");
      setExamAccepted(true);
      setSeedTables([]); setSeedSaved(false);
      const draft = (await fetchAiDraftFromServer(API_BASE, id)) || readAiDraft(id);
      if (draft?.state?.req) setReq((cur) => ({ ...cur, ...(draft.state.req as object) }));
      setInfo(`Đã mở đề ${id}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không mở được đề này.");
    } finally {
      setBusy(null);
    }
  };

  // Ghi nháp form (chỉ req — de_bai đã có endpoint lưu riêng) mỗi khi đổi, hoãn 800ms.
  useEffect(() => {
    const id = examId.trim() || DRAFT_NO_EXAM;
    const timer = setTimeout(() => {
      const state = { req };
      writeAiDraft(id, state);
      pushAiDraftToServer(API_BASE, id, state);
    }, 800);
    return () => clearTimeout(timer);
  }, [examId, req]);

  const importExam = async (file: File) => {
    setBusy("import"); setError(null); setInfo(null);
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

  const draftExam = async () => {
    if (!examId.trim()) { setError("Hãy đặt mã đề trước (vd PE_PRM393_DEMO)."); return; }
    if (!req.topic.trim()) { setError("Hãy nhập chủ đề / bài toán của đề."); return; }
    const data = await call<{ de_bai: string; summary: string }>("/ai/exam/draft", req, "draft");
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
      setInfo("AI đã sửa đề. Kiểm tra lại phần thay đổi trước khi lưu.");
    }
  };

  const saveHandout = async () => {
    if (!examId.trim()) { setError("Hãy đặt mã đề trước khi lưu."); return; }
    if (!deBai.trim()) { setError("Chưa có nội dung đề bài để lưu."); return; }
    const exam = examId.trim();
    setBusy("handout"); setError(null); setInfo(null);
    try {
      const saveRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/handout`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ de_bai: deBai, mockups: [] }),
      });
      const saved = await saveRes.json().catch(() => ({}));
      if (!saveRes.ok) throw new Error(saved?.error || "Không lưu được đề bài.");
      setExamAccepted(true);
      clearAiDraft(exam);
      await loadAuthored();
      setInfo(`Đã lưu đề ${exam}. Sang trang "Tạo Golden" để sinh Golden Solution + starter cho đề này.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không lưu được đề bài.");
    } finally {
      setBusy(null);
    }
  };

  // ── Database mẫu (STUDENT_DATABASE) + database ẩn chống hardcode (HIDDEN_DATABASE) ──
  const proposeSeed = async () => {
    if (!deBai.trim()) { setError("Chưa có đề bài."); return; }
    const data = await call<{ tables: SeedTable[]; notes: string[] }>(
      "/ai/database/propose", { de_bai: deBai }, "seed");
    if (data) {
      setSeedTables(data.tables || []);
      setSeedSaved(false);
      setInfo("AI đã soạn dữ liệu mẫu — xem lại rồi bấm \"Lưu & tạo 2 file database\".");
    }
  };

  const saveSeed = async () => {
    if (!examId.trim() || seedTables.length === 0) return;
    setBusy("seed-save"); setError(null); setInfo(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/database-seed`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tables: seedTables }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không dựng được database.");
      setSeedSaved(true);
      setInfo("Đã dựng student.db + hidden.db. Tải về rồi đưa lên đúng ô STUDENT_DATABASE/HIDDEN_DATABASE ở trang \"Bộ chấm Golden\".");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không dựng được database.");
    } finally {
      setBusy(null);
    }
  };

  const downloadDb = async (kind: "student" | "hidden") => {
    const exam = examId.trim();
    setBusy(`download-${kind}`); setError(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/download/${kind}-db`);
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data?.error || "Không tải được file database.");
      }
      downloadBlob(await res.blob(), `${exam}_${kind}.db`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được file database.");
    } finally {
      setBusy(null);
    }
  };

  const downloadDocx = async () => {
    if (!examId.trim()) return;
    const exam = examId.trim();
    setBusy("docx"); setError(null);
    try {
      const docxRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/de-bai/docx`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ images: [] }),
      });
      if (!docxRes.ok) {
        const data = await docxRes.json().catch(() => ({}));
        throw new Error(data?.error || "Không tải được bản .docx — hãy lưu đề trước.");
      }
      downloadBlob(await docxRes.blob(), `${exam}_de_bai.docx`);
      setInfo(`Đã tải bản .docx của đề ${exam}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được bản .docx.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <SidebarLayout activePath="/teacher/exam-authoring" title="Tạo đề bằng AI"
      subtitle="Soạn đề PRM393 theo khuôn Golden Solution — mô tả yêu cầu, AI ra đề, sửa lại rồi lưu">
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

        {/* Danh sách đề đã soạn */}
        <section className="card p-5">
          <div className="mb-3 flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <ListChecks size={16} className="text-indigo-500" />
              <h2 className="text-sm font-bold text-slate-800">Đề đã soạn ({authored.length})</h2>
            </div>
            <button onClick={startNew} className={ghostBtn}>
              <FilePlus2 size={15} /> Soạn đề mới
            </button>
          </div>
          {loadingList ? (
            <p className="flex items-center gap-2 text-xs text-slate-400"><Loader2 size={14} className="animate-spin" /> Đang tải…</p>
          ) : authored.length === 0 ? (
            <p className="text-xs text-slate-400">Chưa có đề nào — soạn đề đầu tiên ở dưới.</p>
          ) : (
            <div className="grid gap-2 sm:grid-cols-2">
              {authored.map((item) => (
                <button key={item.exam_id} onClick={() => openExam(item.exam_id)}
                  className={`rounded-xl border p-3 text-left transition-colors ${
                    examId === item.exam_id ? "border-indigo-300 bg-indigo-50/60" : "border-slate-200 hover:border-indigo-300 hover:bg-indigo-50/30"}`}>
                  <p className="truncate font-mono text-xs font-bold text-indigo-600">{item.exam_id}</p>
                  <p className="mt-0.5 truncate text-xs text-slate-600">{item.title || "(chưa có tiêu đề)"}</p>
                  <p className="mt-1 text-[10px] text-slate-400">{new Date(item.updated_at).toLocaleString("vi-VN")}</p>
                </button>
              ))}
            </div>
          )}
        </section>

        {/* Bước 1: mô tả yêu cầu */}
        <Step index={1} icon={Wand2} title={source === "ai" ? "Mô tả yêu cầu đề" : "Tải đề có sẵn lên"} done={!!deBai}>
          <div className="mb-4">
            <Field label="Mã đề *" hint="Đặt tên ngắn gọn, ví dụ PE_PRM393_QLCT — dùng để mở lại đề này và để chọn sinh Golden Solution sau">
              <input value={examId} onChange={(e) => setExamId(e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, "_"))}
                placeholder="VD: PE_PRM393_QLCT" className={`${inputClass} font-mono`} />
            </Field>
          </div>
          <div className="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-2">
            {([
              { id: "ai" as const, icon: Sparkles, title: "Tạo đề bằng AI", desc: "Mô tả yêu cầu, AI soạn đề rồi bạn sửa lại" },
              { id: "upload" as const, icon: Upload, title: "Tải đề có sẵn lên", desc: "PDF, Word (.docx) hoặc .txt — AI đọc lại để bạn xem" },
            ]).map((choice) => (
              <button key={choice.id} type="button" onClick={() => setSource(choice.id)}
                className={`flex items-start gap-2.5 rounded-xl border p-3 text-left transition-colors ${
                  source === choice.id ? "border-indigo-300 bg-indigo-50/70 ring-1 ring-indigo-200" : "border-slate-200 bg-white hover:border-slate-300"}`}>
                <choice.icon size={16} className={`mt-0.5 shrink-0 ${source === choice.id ? "text-indigo-600" : "text-slate-400"}`} />
                <span className="min-w-0">
                  <span className="block text-sm font-bold text-slate-700">{choice.title}</span>
                  <span className="block text-[11px] leading-relaxed text-slate-500">{choice.desc}</span>
                </span>
              </button>
            ))}
          </div>

          {source === "upload" ? (
            <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-5 text-center">
              <input id="ai-exam-file" type="file" className="hidden" accept=".pdf,.docx,.txt,.md,.markdown"
                onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ""; if (file) importExam(file); }} />
              <label htmlFor="ai-exam-file"
                className={`${primaryBtn} mx-auto w-fit cursor-pointer ${busy ? "pointer-events-none opacity-60" : ""}`}>
                {busy === "import" ? <Loader2 size={15} className="animate-spin" /> : <Upload size={15} />}
                Chọn file đề
              </label>
              <p className="mt-2.5 text-[11px] leading-relaxed text-slate-500">
                Nhận .docx, .pdf, .txt, .md. PDF bản scan (chỉ có ảnh) không bóc được chữ — hãy dùng .docx.
              </p>
              {importedName && <p className="mt-2 font-mono text-[11px] text-emerald-600">Đã đọc: {importedName}</p>}
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
                    placeholder="SQLite, validate form, responsive" className={inputClass} />
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
                      placeholder="VD: có màn responsive trên tablet" className={inputClass} />
                  </Field>
                </div>
              </div>
              <button onClick={draftExam} disabled={busy !== null} className={`${primaryBtn} mt-3`}>
                {busy === "draft" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />} Sinh đề bài
              </button>
            </>
          )}
        </Step>

        {/* Bước 2: xem lại, sửa bằng AI hoặc sửa tay, lưu */}
        {!!deBai && (
          <Step index={2} icon={FileText} title="Xem lại, sửa &amp; lưu đề bài" done={examAccepted}>
            {summary && <p className="mb-2 rounded-xl bg-slate-50 p-3 text-xs leading-relaxed text-slate-600">{summary}</p>}
            <textarea
              value={deBai}
              onChange={(e) => { setDeBai(e.target.value); setExamAccepted(false); }}
              rows={20}
              className="custom-scrollbar w-full rounded-xl border border-slate-200 bg-white p-3 font-mono text-xs leading-relaxed text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <input
                value={revisePrompt}
                onChange={(e) => setRevisePrompt(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseExam(); } }}
                placeholder="Nhắc AI sửa: thêm màn hình chi tiết, bỏ yêu cầu SQLite…"
                className={`${inputClass} min-w-[240px] flex-1`}
              />
              <button onClick={reviseExam} disabled={busy !== null} className={ghostBtn}>
                {busy === "revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Nhờ AI sửa
              </button>
              <button onClick={saveHandout} disabled={busy !== null || !examId.trim()} className={primaryBtn}>
                {busy === "handout" ? <Loader2 size={15} className="animate-spin" /> : <Check size={15} />} Lưu đề
              </button>
              <button onClick={downloadDocx} disabled={busy !== null || !examAccepted} className={ghostBtn}
                title={examAccepted ? undefined : "Lưu đề trước đã"}>
                {busy === "docx" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />} Tải .docx
              </button>
            </div>
          </Step>
        )}

        {/* Bước 3: database mẫu phát cho sinh viên + database ẩn chống hardcode */}
        {examAccepted && (
          <Step index={3} icon={Database} title="Database mẫu &amp; database ẩn (chống hardcode)" done={seedSaved}>
            <p className="mb-3 rounded-xl bg-slate-50 p-3 text-[11px] leading-relaxed text-slate-600">
              AI đọc đúng bảng đã khai ở mục "Hợp đồng dữ liệu" của đề, soạn 2 bộ dữ liệu <strong>cùng cấu
              trúc bảng, khác dữ liệu</strong>: một bộ phát công khai cho sinh viên, một bộ ẩn để chấm —
              tránh sinh viên đoán/hardcode kết quả theo dữ liệu mẫu. Hai file <span className="font-mono">.db</span> dựng
              THẬT ngay ở đây, tải về rồi đưa lên đúng ô STUDENT_DATABASE/HIDDEN_DATABASE ở trang "Bộ chấm Golden".
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <button onClick={proposeSeed} disabled={busy !== null} className={primaryBtn}>
                {busy === "seed" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                {seedTables.length ? "Sinh lại dữ liệu" : "Sinh dữ liệu mẫu"}
              </button>
              {seedTables.length > 0 && (
                <button onClick={saveSeed} disabled={busy !== null} className={primaryBtn}>
                  {busy === "seed-save" ? <Loader2 size={15} className="animate-spin" /> : <ShieldCheck size={15} />}
                  Lưu &amp; tạo 2 file database
                </button>
              )}
              {seedSaved && (
                <>
                  <button onClick={() => downloadDb("student")} disabled={busy !== null} className={ghostBtn}>
                    {busy === "download-student" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />} Tải student.db
                  </button>
                  <button onClick={() => downloadDb("hidden")} disabled={busy !== null} className={ghostBtn}>
                    {busy === "download-hidden" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />} Tải hidden.db
                  </button>
                </>
              )}
            </div>

            {seedTables.length > 0 && (
              <div className="mt-4 space-y-3">
                {seedTables.map((t) => (
                  <div key={t.name} className="rounded-xl border border-slate-200 p-3">
                    <p className="font-mono text-xs font-bold text-indigo-600">{t.name}</p>
                    <p className="mt-1 font-mono text-[11px] text-slate-400">{t.columns.join(", ")}</p>
                    <div className="mt-2 grid gap-3 sm:grid-cols-2">
                      <div>
                        <p className="mb-1 text-[10px] font-bold uppercase tracking-wider text-slate-500">
                          Phát cho sinh viên ({t.student_rows.length} dòng)
                        </p>
                        <SeedPreview columns={t.columns} rows={t.student_rows} />
                      </div>
                      <div>
                        <p className="mb-1 text-[10px] font-bold uppercase tracking-wider text-slate-500">
                          Ẩn — dùng để chấm ({t.hidden_rows.length} dòng)
                        </p>
                        <SeedPreview columns={t.columns} rows={t.hidden_rows} />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Step>
        )}
      </div>
    </SidebarLayout>
  );
}

/** Xem trước vài dòng dữ liệu đầu — không phải trình soạn thảo, chỉ để giáo viên đối chiếu nhanh. */
function SeedPreview({ columns, rows }: { columns: string[]; rows: (string | number | boolean | null)[][] }) {
  const preview = rows.slice(0, 4);
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-100">
      <table className="w-full text-[11px]">
        <thead>
          <tr className="bg-slate-50">
            {columns.map((c) => <th key={c} className="whitespace-nowrap px-2 py-1 text-left font-mono font-semibold text-slate-500">{c}</th>)}
          </tr>
        </thead>
        <tbody>
          {preview.map((row, i) => (
            <tr key={i} className="border-t border-slate-100">
              {columns.map((c, j) => <td key={c} className="whitespace-nowrap px-2 py-1 text-slate-700">{String(row[j] ?? "")}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length > preview.length && (
        <p className="border-t border-slate-100 px-2 py-1 text-[10px] text-slate-400">… và {rows.length - preview.length} dòng khác</p>
      )}
    </div>
  );
}
