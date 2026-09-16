"use client";

// Trang "Kho tài liệu đề" — lưu NGUYÊN file đề bài gốc (.docx/.pdf) giáo viên tự soạn ở
// ngoài. Khác "Tạo đề" (đề bài dạng văn bản do AI soạn/giáo viên gõ tay, de_bai.md) và khác
// "Bộ chấm Golden" ở /teacher/archive (testcase + Golden Solution) — đây chỉ là kho lưu trữ
// tài liệu tham chiếu, không qua AI, không đụng gì tới bộ chấm.

import { useCallback, useEffect, useState } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import Banner from "@/components/ui/Banner";
import { API_BASE } from "@/lib/config";
import {
  Check, ChevronDown, ChevronUp, Copy, Download, FileText, Loader2, RefreshCw, Sparkles, Trash2, UploadCloud,
} from "lucide-react";

interface ExamRow { examId: string; examName: string }
interface OriginalInfo { exists: boolean; file_name?: string; size_bytes?: number }

function bytes(n?: number) {
  if (!n) return "";
  const units = ["B", "KB", "MB"];
  const i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), units.length - 1);
  return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${units[i]}`;
}

export default function ExamDocumentsPage() {
  const [examId, setExamId] = useState("");
  const [examName, setExamName] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const [exams, setExams] = useState<ExamRow[]>([]);
  const [docs, setDocs] = useState<Record<string, OriginalInfo>>({});
  const [loadingDocs, setLoadingDocs] = useState(false);

  const loadDocs = useCallback(async () => {
    setLoadingDocs(true);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/list`);
      const list = await res.json().catch(() => []);
      const rows: ExamRow[] = Array.isArray(list) ? list : [];
      setExams(rows);
      const entries = await Promise.all(
        rows.map(async (e) => {
          try {
            const infoRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(e.examId)}/handout/original/info`);
            const info: OriginalInfo = await infoRes.json();
            return [e.examId, info] as const;
          } catch {
            return [e.examId, { exists: false }] as const;
          }
        }),
      );
      setDocs(Object.fromEntries(entries));
    } finally {
      setLoadingDocs(false);
    }
  }, []);

  useEffect(() => { void loadDocs(); }, [loadDocs]);

  const upload = async () => {
    setError(""); setNotice("");
    if (!examId.trim()) { setError("Nhập mã đề trước."); return; }
    if (!file) { setError("Chọn file .docx hoặc .pdf trước."); return; }
    setBusy(true);
    try {
      const form = new FormData();
      form.append("file", file);
      if (examName.trim()) form.append("examName", examName.trim());
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/handout/original`, {
        method: "POST",
        body: form,
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Tải lên thất bại");
      setNotice(`Đã lưu file đề bài cho "${examId.trim()}".`);
      setFile(null);
      await loadDocs();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Tải lên thất bại");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: string) => {
    if (!window.confirm(`Xoá file đề bài gốc đã tải lên của "${id}"?`)) return;
    setBusy(true);
    try {
      await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(id)}/handout/original`, { method: "DELETE" });
      await loadDocs();
    } finally {
      setBusy(false);
    }
  };

  /**
   * Nhân bản TOÀN BỘ handout của đề nguồn (de_bai.md + chính file .docx/.pdf gốc đã upload)
   * sang một mã đề mới, rồi mở "Tạo đề" để sửa tay hoặc nhờ AI sửa tiếp — đã verify qua API
   * thật: bản sao có đủ cả 2. Dùng chung đúng endpoint với nút Clone ở Kho đề
   * (behavior-authoring) — đề ở đây không cần có Suite nào.
   */
  const clone = async (id: string) => {
    const targetId = window.prompt(`Nhập mã đề MỚI cho bản sao đề bài của "${id}":`, `${id}_COPY`);
    if (!targetId || !targetId.trim()) return;
    setError(""); setNotice("");
    setBusy(true);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(id)}/clone-handout`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ target_exam_id: targetId.trim() }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Nhân bản thất bại");
      setNotice(`Đã nhân bản đề bài sang "${data.exam_id}" — đang mở "Tạo đề" để sửa tiếp.`);
      window.open(`/teacher/exam-authoring?examId=${encodeURIComponent(data.exam_id)}`, "_blank");
      await loadDocs();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Nhân bản thất bại");
    } finally {
      setBusy(false);
    }
  };

  // ── Chi tiết đề bài mở ngay tại chỗ khi bấm vào 1 dòng — xem/sửa tay/nhờ AI sửa, KHÔNG rời
  // khỏi Kho tài liệu đề. Dùng lại đúng 2 API đã có ở "Tạo đề" (đọc/lưu handout, sửa bằng AI),
  // chỉ khác là hiện ngay trong trang này thay vì điều hướng sang trang khác.
  const [expanded, setExpanded] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailDeBai, setDetailDeBai] = useState("");
  const [detailBusy, setDetailBusy] = useState<"save" | "revise" | null>(null);
  const [detailError, setDetailError] = useState("");
  const [detailInfo, setDetailInfo] = useState("");
  const [revisePrompt, setRevisePrompt] = useState("");

  const toggleDetail = async (id: string) => {
    if (expanded === id) { setExpanded(null); return; }
    setExpanded(id);
    setDetailError(""); setDetailInfo(""); setRevisePrompt("");
    setDetailLoading(true);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(id)}/handout`);
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không mở được đề bài.");
      setDetailDeBai(String(data.de_bai || ""));
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : "Không mở được đề bài.");
      setDetailDeBai("");
    } finally {
      setDetailLoading(false);
    }
  };

  const saveDetail = async (id: string) => {
    setDetailBusy("save"); setDetailError(""); setDetailInfo("");
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(id)}/handout`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ de_bai: detailDeBai, mockups: [] }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không lưu được đề bài.");
      setDetailInfo("Đã lưu đề bài.");
      await loadDocs();
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : "Không lưu được đề bài.");
    } finally {
      setDetailBusy(null);
    }
  };

  const reviseDetail = async () => {
    if (!revisePrompt.trim()) { setDetailError("Hãy mô tả bạn muốn AI sửa gì."); return; }
    setDetailBusy("revise"); setDetailError(""); setDetailInfo("");
    try {
      const res = await fetch(`${API_BASE}/ai/exam/revise`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ de_bai: detailDeBai, instruction: revisePrompt }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "AI trả về lỗi.");
      setDetailDeBai(String(data.de_bai || ""));
      setRevisePrompt("");
      setDetailInfo("AI đã sửa đề — kiểm tra lại nội dung rồi bấm Lưu để giữ thay đổi.");
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : "AI trả về lỗi.");
    } finally {
      setDetailBusy(null);
    }
  };

  const uploaded = exams.filter((e) => docs[e.examId]?.exists);

  return (
    <SidebarLayout title="Kho tài liệu đề" activePath="/teacher/exam-documents">
      {error && <Banner tone="error" onClose={() => setError("")}>{error}</Banner>}
      {notice && <Banner tone="ok" onClose={() => setNotice("")}>{notice}</Banner>}

      <div className="card mb-4 p-5">
        <h2 className="text-lg font-bold">Tải lên đề bài gốc (.docx / .pdf)</h2>
        <p className="mt-1 text-sm text-slate-500">
          Lưu nguyên file đề bài giáo viên tự soạn ở ngoài — mã đề có thể là đề đã có sẵn
          (gắn thêm file cho đề đó) hoặc mã hoàn toàn mới (hệ thống tự tạo đề nháp).
        </p>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <input
            value={examId}
            onChange={(e) => setExamId(e.target.value)}
            placeholder="Mã đề (đã có hoặc mới)"
            className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 dark:border-slate-700"
          />
          <input
            value={examName}
            onChange={(e) => setExamName(e.target.value)}
            placeholder="Tên đề (chỉ dùng khi tạo mới)"
            className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 dark:border-slate-700"
          />
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <label className="inline-flex cursor-pointer items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm font-bold hover:border-indigo-400 dark:border-slate-700">
            <UploadCloud size={16} /> {file ? file.name : "Chọn file .docx/.pdf"}
            <input type="file" accept=".docx,.pdf" className="hidden" onChange={(e) => setFile(e.target.files?.[0] || null)} />
          </label>
          <button
            onClick={() => void upload()}
            disabled={busy}
            className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50"
          >
            {busy ? <Loader2 className="animate-spin" size={16} /> : <UploadCloud size={16} />} Tải lên
          </button>
        </div>
      </div>

      <div className="card p-5">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold">Đề đã có file đề bài gốc</h2>
          <button
            onClick={() => void loadDocs()}
            disabled={loadingDocs}
            className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-indigo-600 disabled:opacity-50"
          >
            {loadingDocs ? <Loader2 className="animate-spin" size={14} /> : <RefreshCw size={14} />} Làm mới
          </button>
        </div>
        <p className="mt-1 text-xs text-slate-500">
          Bấm vào tên đề để xem chi tiết đề bài ngay tại đây — sửa tay hoặc nhờ AI sửa rồi lưu, không cần rời trang.
        </p>
        {uploaded.length === 0 && !loadingDocs && (
          <p className="mt-3 text-sm text-slate-500">Chưa có đề nào được tải lên file đề bài gốc.</p>
        )}
        <div className="mt-3 space-y-2">
          {uploaded.map((e) => {
            const info = docs[e.examId];
            const isOpen = expanded === e.examId;
            return (
              <div key={e.examId} className="rounded-xl border border-slate-200 dark:border-slate-700">
                <div className="flex items-center justify-between gap-3 px-4 py-3">
                  <button
                    onClick={() => void toggleDetail(e.examId)}
                    className="flex min-w-0 flex-1 items-center gap-2 text-left hover:opacity-80"
                    title="Xem chi tiết đề bài — sửa tay hoặc nhờ AI sửa ngay tại đây"
                  >
                    {isOpen ? (
                      <ChevronUp size={16} className="shrink-0 text-slate-400" />
                    ) : (
                      <ChevronDown size={16} className="shrink-0 text-slate-400" />
                    )}
                    <div className="min-w-0">
                      <p className="flex items-center gap-2 font-bold">
                        <FileText size={16} className="shrink-0 text-indigo-500" /> {e.examName || e.examId}
                      </p>
                      <p className="mt-0.5 truncate text-xs text-slate-500">
                        {e.examId} · {info?.file_name} · {bytes(info?.size_bytes)}
                      </p>
                    </div>
                  </button>
                  <div className="flex shrink-0 items-center gap-2">
                    <a
                      href={`${API_BASE}/exam-setup/${encodeURIComponent(e.examId)}/handout/original`}
                      className="rounded-lg border border-slate-300 p-2 text-slate-600 hover:border-indigo-400 dark:border-slate-700 dark:text-slate-300"
                      title="Tải xuống"
                    >
                      <Download size={16} />
                    </a>
                    <button
                      onClick={() => void clone(e.examId)}
                      disabled={busy}
                      className="rounded-lg border border-indigo-300 p-2 text-indigo-500 hover:bg-indigo-50 disabled:opacity-40 dark:border-indigo-800 dark:hover:bg-indigo-950"
                      title="Nhân bản đề bài sang mã đề mới"
                    >
                      <Copy size={16} />
                    </button>
                    <button
                      onClick={() => void remove(e.examId)}
                      disabled={busy}
                      className="rounded-lg border border-rose-300 p-2 text-rose-500 hover:bg-rose-50 disabled:opacity-40 dark:border-rose-800 dark:hover:bg-rose-950"
                      title="Xoá"
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>

                {isOpen && (
                  <div className="border-t border-slate-200 px-4 py-4 dark:border-slate-700">
                    {detailError && <Banner tone="error" onClose={() => setDetailError("")}>{detailError}</Banner>}
                    {detailInfo && <Banner tone="ok" onClose={() => setDetailInfo("")}>{detailInfo}</Banner>}
                    {detailLoading ? (
                      <div className="flex items-center gap-2 py-6 text-sm text-slate-500">
                        <Loader2 className="animate-spin" size={16} /> Đang tải đề bài…
                      </div>
                    ) : (
                      <>
                        <label className="text-xs font-bold uppercase text-slate-500">Nội dung đề bài</label>
                        <textarea
                          value={detailDeBai}
                          onChange={(ev) => setDetailDeBai(ev.target.value)}
                          rows={12}
                          className="mt-1 w-full rounded-xl border border-slate-300 bg-transparent p-3 font-mono text-sm outline-none focus:border-indigo-500 dark:border-slate-700"
                        />
                        <div className="mt-2 flex justify-end">
                          <button
                            onClick={() => void saveDetail(e.examId)}
                            disabled={detailBusy !== null}
                            className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50"
                          >
                            {detailBusy === "save" ? <Loader2 className="animate-spin" size={16} /> : <Check size={16} />} Lưu đề bài
                          </button>
                        </div>

                        <div className="mt-4 border-t border-dashed border-slate-200 pt-4 dark:border-slate-700">
                          <label className="text-xs font-bold uppercase text-slate-500">Nhờ AI sửa đề</label>
                          <textarea
                            value={revisePrompt}
                            onChange={(ev) => setRevisePrompt(ev.target.value)}
                            rows={3}
                            placeholder='Mô tả điều muốn AI sửa, vd: "Đổi bối cảnh đề sang quản lý thư viện thay vì bán hàng"'
                            className="mt-1 w-full rounded-xl border border-slate-300 bg-transparent p-3 text-sm outline-none focus:border-indigo-500 dark:border-slate-700"
                          />
                          <div className="mt-2 flex justify-end">
                            <button
                              onClick={() => void reviseDetail()}
                              disabled={detailBusy !== null}
                              className="inline-flex items-center gap-2 rounded-xl border border-indigo-300 px-4 py-2 text-sm font-bold text-indigo-600 hover:bg-indigo-50 disabled:opacity-50 dark:border-indigo-800 dark:hover:bg-indigo-950"
                            >
                              {detailBusy === "revise" ? <Loader2 className="animate-spin" size={16} /> : <Sparkles size={16} />} Nhờ AI sửa
                            </button>
                          </div>
                        </div>
                      </>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </SidebarLayout>
  );
}
