"use client";

// Trang "Tạo đề" — CHỈ soạn đề bằng AI (chủ đề/kiến thức → đề bài → sửa bằng AI/sửa tay → lưu),
// tách khỏi wizard 4 bước cũ (AiAuthorPanel) theo yêu cầu: không cần tạo Suite/Golden App trước
// mới soạn được đề. Có thể tạo và lưu nhiều đề — mỗi đề là một mã đề gõ tay, lưu độc lập trong
// handout/<examId>/de_bai.md (xem ExamService#listAuthoredExamIds, không cần Suite/testcase nào).
//
// Hình minh họa giao diện (AI vẽ khung dây theo mục 3 của đề, xem MockupRenderer ở backend) sinh
// ngay tại đây — không còn trang "Tạo Golden" riêng (đã xoá 17/9/2026).

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import { downloadBlob, svgToPng } from "@/lib/mockup-image";
import {
  clearAiDraft, DRAFT_NO_EXAM, fetchAiDraftFromServer, pushAiDraftToServer, readAiDraft, writeAiDraft,
} from "@/lib/aiAuthorDrafts";
import AiSettingsPanel from "@/components/testcases/AiSettingsPanel";
import { Field, Step, inputClass, primaryBtn, ghostBtn } from "@/components/testcases/AiWizardWidgets";
import {
  Sparkles, Wand2, FileText, Loader2, Check, Upload, Download, AlertTriangle, ListChecks, FilePlus2,
  Image as ImageIcon,
} from "lucide-react";

interface AuthoredExam { exam_id: string; title: string; updated_at: string }
interface Mockup { id: string; title: string; svg: string }

function ExamAuthoringEditor() {
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

  const [mockups, setMockups] = useState<Mockup[]>([]);
  const [mockupPrompt, setMockupPrompt] = useState("");

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
    setMockups([]); setMockupPrompt("");
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
      setMockups(Array.isArray(data.mockups) ? data.mockups : []);
      const draft = (await fetchAiDraftFromServer(API_BASE, id)) || readAiDraft(id);
      if (draft?.state?.req) setReq((cur) => ({ ...cur, ...(draft.state.req as object) }));
      setInfo(`Đã mở đề ${id}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không mở được đề này.");
    } finally {
      setBusy(null);
    }
  };

  // Mở sẵn 1 đề khi vào trang qua link có ?examId=... (vd từ nút "Clone" ở Kho đề).
  const searchParams = useSearchParams();
  useEffect(() => {
    const id = searchParams.get("examId");
    if (id) void openExam(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    if (!data) return;
    setDeBai(data.de_bai);
    setSummary(data.summary);
    setExamAccepted(false);
    // Vẽ hình minh họa NGAY khi sinh đề — dùng thẳng văn bản vừa nhận, không đọc state `deBai`
    // (state chưa kịp cập nhật trong cùng lượt gọi này).
    const mock = await drawMockups(data.de_bai);
    if (mock) {
      setInfo(`Đã sinh đề bài kèm ${mock.mockups?.length || 0} hình minh họa — xem lại rồi bấm "Lưu đề".`);
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
        body: JSON.stringify({
          de_bai: deBai,
          mockups: mockups.map((m) => ({ id: m.id, svg: m.svg })),
        }),
      });
      const saved = await saveRes.json().catch(() => ({}));
      if (!saveRes.ok) throw new Error(saved?.error || "Không lưu được đề bài.");
      setExamAccepted(true);
      clearAiDraft(exam);
      await loadAuthored();
      setInfo(`Đã lưu đề ${exam}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không lưu được đề bài.");
    } finally {
      setBusy(null);
    }
  };

  // ── Hình minh họa giao diện: AI đọc mục 3 (Hợp đồng giao diện) của đề, vẽ khung dây từng màn
  // hình (SVG dựng tất định ở backend qua MockupRenderer — AI chỉ mô tả cấu trúc, không tự vẽ
  // SVG). Tự vẽ ngay khi sinh đề (xem draftExam); lưu kèm đề bài khi bấm "Lưu đề", không lưu
  // ngay lúc vẽ. `instruction` = lời giáo viên nhờ AI vẽ lại theo ý muốn (đổi bố cục/thành phần).
  const drawMockups = async (deBaiText: string, instruction?: string) => {
    const data = await call<{ mockups: Mockup[] }>(
      "/ai/exam/mockup", { de_bai: deBaiText, instruction: instruction || undefined }, "mockup");
    if (data) setMockups(Array.isArray(data.mockups) ? data.mockups : []);
    return data;
  };

  const reviseMockups = async () => {
    if (!deBai.trim()) { setError("Chưa có đề bài để vẽ hình minh họa."); return; }
    const data = await drawMockups(deBai, mockupPrompt);
    if (data) {
      const theoYeuCau = mockupPrompt.trim().length > 0;
      setMockupPrompt("");
      setInfo(`AI đã vẽ lại ${data.mockups?.length || 0} hình minh họa`
        + (theoYeuCau ? " theo yêu cầu" : "") + ` — bấm "Lưu đề" để giữ lại.`);
    }
  };

  const downloadMockup = async (m: Mockup) => {
    try {
      const { png } = await svgToPng(m.svg);
      const res = await fetch(png);
      downloadBlob(await res.blob(), `${examId.trim() || "de"}_${m.id}.png`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được hình.");
    }
  };

  /** Đổi toàn bộ hình minh họa hiện có sang PNG (canvas trong trình duyệt) để nhúng vào .docx/.pdf —
   *  đúng khuôn {@code {id, png_base64, width, height}} mà ExamService#buildHandoutDocx/Pdf đọc. */
  const mockupImages = async () => {
    const out: { id: string; png_base64: string; width: number; height: number }[] = [];
    for (const m of mockups) {
      try {
        const { png, width, height } = await svgToPng(m.svg);
        out.push({ id: m.id, png_base64: png, width, height });
      } catch { /* 1 hình lỗi không chặn tải cả file */ }
    }
    return out;
  };

  const downloadDocx = async () => {
    if (!examId.trim()) return;
    const exam = examId.trim();
    setBusy("docx"); setError(null);
    try {
      const images = await mockupImages();
      const docxRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/de-bai/docx`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ images }),
      });
      if (!docxRes.ok) {
        const data = await docxRes.json().catch(() => ({}));
        throw new Error(data?.error || "Không tải được bản .docx — hãy lưu đề trước.");
      }
      downloadBlob(await docxRes.blob(), `${exam}_de_bai.docx`);
      setInfo(`Đã tải bản .docx của đề ${exam} (kèm ${images.length} hình minh họa).`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được bản .docx.");
    } finally {
      setBusy(null);
    }
  };

  const downloadPdf = async () => {
    if (!examId.trim()) return;
    const exam = examId.trim();
    setBusy("pdf"); setError(null);
    try {
      const images = await mockupImages();
      const pdfRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/de-bai/pdf`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ images }),
      });
      if (!pdfRes.ok) {
        const data = await pdfRes.json().catch(() => ({}));
        throw new Error(data?.error || "Không tải được bản .pdf — hãy lưu đề trước.");
      }
      downloadBlob(await pdfRes.blob(), `${exam}_de_bai.pdf`);
      setInfo(`Đã tải bản .pdf của đề ${exam} (kèm ${images.length} hình minh họa).`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được bản .pdf.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <SidebarLayout activePath="/teacher/exam-authoring" title="Tạo đề bằng AI">
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
            <Field label="Mã đề">
              <input value={examId} onChange={(e) => setExamId(e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, "_"))}
                placeholder="VD: PE_PRM393_QLCT" className={`${inputClass} font-mono`} />
            </Field>
          </div>
          <div className="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-2">
            {([
              { id: "ai" as const, icon: Sparkles, title: "Tạo đề bằng AI"},
              { id: "upload" as const, icon: Upload, title: "Tải đề có sẵn lên", desc: "PDF, Word (.docx) hoặc .txt" },
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
            </div>

            {/* Hình minh họa: AI tự vẽ ngay khi "Sinh đề bài" (xem draftExam) — không còn nút vẽ
                tay riêng. Xem lại/tải/nhờ AI vẽ lại theo ý muốn ở ĐÂY, ƯNG Ý rồi mới xuống dưới bấm
                "Lưu đề"/tải file — file tải về nhúng kèm đúng những hình đang thấy ở đây. */}
            <div className="mt-4 border-t border-dashed border-slate-200 pt-4">
              <div className="mb-2 flex items-center gap-2">
                <ImageIcon size={15} className="text-indigo-500" />
                <p className="text-[11px] font-bold uppercase tracking-wider text-slate-500">
                  Hình minh họa giao diện {mockups.length > 0 && `(${mockups.length})`}
                </p>
              </div>

              {busy === "mockup" && (
                <p className="mb-3 flex items-center gap-2 text-xs text-slate-500">
                  <Loader2 size={14} className="animate-spin" /> AI đang vẽ hình minh họa…
                </p>
              )}

              {mockups.length > 0 && (
                <div className="mb-3 grid gap-3 sm:grid-cols-2">
                  {mockups.map((m) => (
                    <div key={m.id} className="rounded-xl border border-slate-200 p-3">
                      <div className="mb-2 flex items-center justify-between gap-2">
                        <p className="truncate text-xs font-bold text-slate-600">{m.title || m.id}</p>
                        <button onClick={() => downloadMockup(m)} disabled={busy !== null}
                          className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 px-2 py-1 text-[11px] font-semibold text-slate-500 hover:border-indigo-300 hover:text-indigo-600 disabled:opacity-50">
                          <Download size={12} /> Tải hình
                        </button>
                      </div>
                      {/* SVG do MockupRenderer sinh ở backend, không phải chữ người dùng dán vào.
                          width/height gốc của nó là SỐ THẬT (để xuất .docx/.pdf/PNG đúng kích
                          thước — xem MockupRenderer#render), nên ở đây ép co giãn vừa khung bằng
                          CSS (luôn đè được thuộc tính width/height trên chính thẻ svg). */}
                      <div className="overflow-hidden rounded-lg border border-slate-100 bg-white [&>svg]:h-auto [&>svg]:w-full"
                        dangerouslySetInnerHTML={{ __html: m.svg }} />
                    </div>
                  ))}
                </div>
              )}

              <div className="flex flex-wrap items-center gap-2">
                <input
                  value={mockupPrompt}
                  onChange={(e) => setMockupPrompt(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseMockups(); } }}
                  placeholder="Nhờ AI vẽ lại hình: đổi bố cục dạng thẻ, bỏ thanh tiêu đề…"
                  className={`${inputClass} min-w-[240px] flex-1`}
                />
                <button onClick={reviseMockups} disabled={busy !== null} className={ghostBtn}>
                  {busy === "mockup" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />}
                  {mockups.length ? "Vẽ lại theo yêu cầu" : "Vẽ hình minh họa"}
                </button>
              </div>
            </div>

            {/* Ưng ý đề + hình rồi mới lưu/tải — file .docx/.pdf tải về nhúng kèm đúng các hình ở trên. */}
            <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-200 pt-4">
              <button onClick={saveHandout} disabled={busy !== null || !examId.trim()} className={primaryBtn}>
                {busy === "handout" ? <Loader2 size={15} className="animate-spin" /> : <Check size={15} />} Lưu đề
              </button>
              <button onClick={downloadDocx} disabled={busy !== null || !examAccepted} className={ghostBtn}
                title={examAccepted ? undefined : "Lưu đề trước đã"}>
                {busy === "docx" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />} Tải .docx (kèm hình)
              </button>
              <button onClick={downloadPdf} disabled={busy !== null || !examAccepted} className={ghostBtn}
                title={examAccepted ? undefined : "Lưu đề trước đã"}>
                {busy === "pdf" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />} Tải .pdf (kèm hình)
              </button>
            </div>
          </Step>
        )}
      </div>
    </SidebarLayout>
  );
}

export default function ExamAuthoringPage() {
  return (
    <Suspense fallback={
      <SidebarLayout activePath="/teacher/exam-authoring" title="Tạo đề bằng AI">
        <div className="flex min-h-[50vh] items-center justify-center text-slate-400">
          <Loader2 className="animate-spin" size={28} />
        </div>
      </SidebarLayout>
    }>
      <ExamAuthoringEditor />
    </Suspense>
  );
}
