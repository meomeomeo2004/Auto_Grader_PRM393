"use client";

import React, { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import Banner from "@/components/ui/Banner";
import { API_BASE } from "@/lib/config";
import {
  FileArchive, UploadCloud, Loader2, Trash2, AlertTriangle, CheckCircle2,
  ShieldAlert, Inbox,
} from "lucide-react";

/**
 * QUẢN LÝ BỘ TESTCASE — màn của bản người chấm.
 *
 * <p>Bản này không soạn được bộ nào: bộ chấm chỉ vào máy bằng gói .zip do bản giảng viên xuất
 * ra. Nên ở đây đúng hai việc, không hơn: NHẬN một gói, và XÓA một bộ không dùng nữa.
 *
 * <p>Trước đây ô nhận gói nằm nhờ trong màn Chấm tự động. Đó là chỗ sai: nhận gói là việc làm
 * một lần cho cả đợt chấm, còn màn kia là việc làm với từng lô bài — để chung thì mỗi lần vào
 * chấm lại thấy một ô upload không liên quan, và không có chỗ nào gỡ được bộ đã nhận nhầm.
 */

interface BoTestcase {
  examId: string;
  examName?: string;
  teacherNote?: string;
  starterCheck?: string;      // EXEMPT | OK | PENDING | STALE
  gradable?: boolean;
  hasTestcase?: boolean;
  resultCount?: number;
  createdAt?: string;
}

interface GoiThieu { thieu?: string[] }

interface KetQuaNap {
  exam_id?: string;
  exam_name?: string;
  cham_duoc?: boolean;
  base_image_cua_goi?: string;
  base_image_may_nay?: string;
  goi_thieu?: GoiThieu;
}

const gioVN = (iso?: string) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString("vi-VN");
};

/** Diễn giải dấu kiểm đồng bộ khung phát mà gói mang theo. */
function nhanTrangThai(b: BoTestcase) {
  if (!b.hasTestcase)
    return { tone: "rose", text: "Thiếu file testcase", moTa: "Gói nhận vào không đủ file — nhận lại gói khác." };
  if (b.gradable)
    return { tone: "emerald", text: "Chấm được", moTa: "" };
  if (b.starterCheck === "STALE")
    return { tone: "amber", text: "Dấu kiểm hết hạn", moTa: "Giảng viên đã sửa Golden sau lần kiểm — xin gói mới." };
  return { tone: "amber", text: "Chưa có dấu kiểm", moTa: "Gói chưa qua kiểm đồng bộ khung phát — xin giảng viên kiểm rồi gửi lại." };
}

const TONE_CHIP: Record<string, string> = {
  emerald: "border-emerald-200 bg-emerald-50 text-emerald-700",
  amber: "border-amber-200 bg-amber-50 text-amber-800",
  rose: "border-rose-200 bg-rose-50 text-rose-700",
};

export default function QuanLyBoTestcasePage() {
  const [ds, setDs] = useState<BoTestcase[]>([]);
  const [dangTai, setDangTai] = useState(true);
  const [loiTai, setLoiTai] = useState<string | null>(null);

  const [napping, setNapping] = useState(false);
  const [ketQuaNap, setKetQuaNap] = useState<KetQuaNap | null>(null);
  const [loiNap, setLoiNap] = useState<string | null>(null);

  // Bộ đang chờ xác nhận xóa. Xóa là không hoàn lại được, nên phải qua một bước riêng có kể
  // đúng những gì sẽ mất — không dùng window.confirm vì nó không kể được từng dòng.
  const [dinhXoa, setDinhXoa] = useState<BoTestcase | null>(null);
  const [goXacNhan, setGoXacNhan] = useState("");
  const [dangXoa, setDangXoa] = useState(false);
  const [loiXoa, setLoiXoa] = useState<string | null>(null);
  const [daXoa, setDaXoa] = useState<string | null>(null);

  const nap = useCallback(async () => {
    try {
      const r = await fetch(`${API_BASE}/exam-setup/list`);
      const d = await r.json();
      setDs(Array.isArray(d) ? d.filter((e: BoTestcase) => e?.examId) : []);
      setLoiTai(null);
    } catch (e) {
      setLoiTai("Không đọc được danh sách bộ testcase: " + (e as Error).message);
    } finally {
      setDangTai(false);
    }
  }, []);

  useEffect(() => { nap(); }, [nap]);

  const napGoi = async (file: File) => {
    setNapping(true);
    setKetQuaNap(null);
    setLoiNap(null);
    setDaXoa(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const res = await fetch(`${API_BASE}/exam-setup/nhap-goi`, { method: "POST", body: form });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
      setKetQuaNap(data);
      await nap();
    } catch (e) {
      setLoiNap((e as Error).message);
    } finally {
      setNapping(false);
    }
  };

  // Bộ đã có bài chấm thì bắt gõ lại mã bộ mới cho xóa: một cú bấm nhầm ở đây là mất cả bảng
  // điểm của một đợt chấm, mà không có bản sao nào để dựng lại.
  const canGoMa = (b: BoTestcase | null) => !!b && (b.resultCount || 0) > 0;
  const xoaDuoc = !dinhXoa ? false : (!canGoMa(dinhXoa) || goXacNhan.trim() === dinhXoa.examId);

  const xoa = async () => {
    if (!dinhXoa || !xoaDuoc) return;
    setDangXoa(true);
    setLoiXoa(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(dinhXoa.examId)}`, {
        method: "DELETE",
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
      const ten = dinhXoa.examId;
      setDinhXoa(null);
      setGoXacNhan("");
      setKetQuaNap(null);
      setDaXoa(`Đã xóa bộ ${ten}: ${data.resultsRemoved ?? 0} bài đã chấm, ${data.batchesRemoved ?? 0} phiên chấm.`);
      await nap();
    } catch (e) {
      setLoiXoa((e as Error).message);
    } finally {
      setDangXoa(false);
    }
  };

  const canhBaoNap = ketQuaNap
    ? [
        !ketQuaNap.cham_duoc
          ? "Gói này chưa mang dấu kiểm đồng bộ khung phát nên chưa chấm được. Báo giảng viên kiểm lại rồi gửi gói mới — bên này không tự kiểm được."
          : null,
        ketQuaNap.base_image_cua_goi && ketQuaNap.base_image_cua_goi !== ketQuaNap.base_image_may_nay
          ? `Ảnh chấm lệch: gói làm trên ${ketQuaNap.base_image_cua_goi}, máy này đang dùng ${ketQuaNap.base_image_may_nay}. Cùng một bài có thể ra hai kết quả khác nhau.`
          : null,
        ketQuaNap.goi_thieu?.thieu?.length
          ? `Ảnh chấm thiếu thư viện: ${ketQuaNap.goi_thieu.thieu.join(", ")}. Sang màn Thư viện chấm bấm vào đúng tên đó để thêm rồi dựng lại ảnh.`
          : null,
      ].filter(Boolean) as string[]
    : [];

  return (
    <SidebarLayout
      title="Quản lý bộ testcase"
      activePath="/teacher/testcases"
      contentClassName="max-w-[1200px]"
    >
      {/* ── Nhận gói ──────────────────────────────────────────────────────────── */}
      <div className="card mb-6 overflow-hidden">
        <div className="flex items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-blue-50 px-6 py-4">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 text-white shadow-sm">
            <Inbox size={18} />
          </div>
          <div>
            <h2 className="text-sm font-bold text-slate-800">Upload Testcase</h2>
          </div>
        </div>

        <div className="space-y-3 p-6">
          <label
            className={`flex w-full cursor-pointer items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-200 bg-slate-50 px-3 py-6 text-sm font-semibold text-slate-600 transition-colors hover:border-indigo-400 hover:bg-slate-100 hover:text-indigo-600 ${
              napping ? "pointer-events-none opacity-60" : ""
            }`}
          >
            {napping ? <Loader2 size={18} className="animate-spin" /> : <UploadCloud size={18} />}
            {napping ? "Uploading..." : "upload (.zip)"}
            <input
              type="file"
              accept=".zip"
              className="hidden"
              disabled={napping}
              onChange={(e) => {
                const f = e.target.files?.[0];
                e.target.value = "";
                if (f) napGoi(f);
              }}
            />
          </label>

          {loiNap && (
            <Banner tone="error" onClose={() => setLoiNap(null)}>
              <p className="font-bold">Không nạp được gói</p>
              <p className="mt-1">{loiNap}</p>
            </Banner>
          )}

          {ketQuaNap && (
            <Banner tone={canhBaoNap.length ? "info" : "ok"} onClose={() => setKetQuaNap(null)}>
              <p className="font-bold">
                Đã nhận {ketQuaNap.exam_id}
                {ketQuaNap.exam_name && ketQuaNap.exam_name !== ketQuaNap.exam_id
                  ? ` — ${ketQuaNap.exam_name}` : ""}
              </p>
              {canhBaoNap.length === 0 ? (
                <p className="mt-1">Bộ này đã sẵn sàng, sang màn Chấm tự động là chọn được.</p>
              ) : (
                <ul className="mt-2 space-y-2">
                  {canhBaoNap.map((c, i) => (
                    <li key={i} className="flex gap-2 rounded-lg border border-amber-300 bg-amber-50 p-2 text-amber-800">
                      <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                      <span>{c}</span>
                    </li>
                  ))}
                </ul>
              )}
            </Banner>
          )}

          {daXoa && <Banner tone="ok" onClose={() => setDaXoa(null)}>{daXoa}</Banner>}
        </div>
      </div>

      {/* ── Danh sách bộ đang có ──────────────────────────────────────────────── */}
      <div className="card overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/60 px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-200 text-slate-600">
              <FileArchive size={18} />
            </div>
            <div>
              <h2 className="text-sm font-bold text-slate-800">Bộ testcase trên máy này</h2>
            </div>
          </div>
          <span className="rounded-full bg-white px-3 py-1 text-xs font-semibold text-slate-500 ring-1 ring-slate-200">
            {ds.length} bộ
          </span>
        </div>

        {loiTai && <div className="px-6 pt-4"><Banner tone="error">{loiTai}</Banner></div>}

        {dangTai ? (
          <div className="p-10 text-center text-sm text-slate-400">
            <Loader2 size={22} className="mx-auto mb-2 animate-spin text-indigo-500" />
            Đang đọc danh sách...
          </div>
        ) : ds.length === 0 ? (
          <div className="p-10 text-center">
            <FileArchive size={32} className="mx-auto mb-3 text-slate-300" />
            <p className="text-sm font-semibold text-slate-600">Chưa có bộ testcase nào</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[680px] text-sm">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50/40 text-left text-xs font-bold uppercase tracking-wider text-slate-500">
                  <th className="px-6 py-3">Mã bộ</th>
                  <th className="px-6 py-3">Trạng thái</th>
                  <th className="px-6 py-3 text-right">Bài đã chấm</th>
                  <th className="px-6 py-3">Nhận lúc</th>
                  <th className="px-6 py-3 text-right">Xóa</th>
                </tr>
              </thead>
              <tbody>
                {ds.map((b) => {
                  const tt = nhanTrangThai(b);
                  return (
                    <tr key={b.examId} className="border-b border-slate-50 transition-colors last:border-0 hover:bg-slate-50/60">
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-3">
                          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-indigo-50 font-mono text-[11px] font-bold text-indigo-600">
                            {b.examId.slice(0, 2).toUpperCase()}
                          </span>
                          <div className="min-w-0">
                            <p className="truncate font-mono text-sm font-semibold text-slate-700">{b.examId}</p>
                            {b.examName && b.examName !== b.examId && (
                              <p className="truncate text-xs text-slate-400">{b.examName}</p>
                            )}
                          </div>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold ${TONE_CHIP[tt.tone]}`}>
                          {tt.tone === "emerald" ? <CheckCircle2 size={13} /> : <ShieldAlert size={13} />}
                          {tt.text}
                        </span>
                        {tt.moTa && <p className="mt-1 max-w-xs text-xs leading-relaxed text-slate-400">{tt.moTa}</p>}
                      </td>
                      <td className="px-6 py-4 text-right font-mono text-sm font-semibold text-slate-600">
                        {b.resultCount ?? 0}
                      </td>
                      <td className="px-6 py-4 text-xs text-slate-500">{gioVN(b.createdAt)}</td>
                      <td className="px-6 py-4 text-right">
                        <button
                          type="button"
                          onClick={() => { setDinhXoa(b); setGoXacNhan(""); setLoiXoa(null); }}
                          title={`Xóa bộ ${b.examId}`}
                          className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-slate-400 transition-colors hover:border-rose-300 hover:bg-rose-50 hover:text-rose-600"
                        >
                          <Trash2 size={15} />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── Hộp xác nhận xóa ────────────────────────────────────────────────────
          PORTAL ra body, không để nguyên tại chỗ. Khung nội dung của SidebarLayout mang lớp
          `animate-fade-in-up`, mà một phần tử ĐANG có transform thì thành khung quy chiếu cho
          mọi con `position: fixed` bên trong nó. Đo được: hộp này ra 1120x494 tại (288,96) thay
          vì phủ kín 1440x900 — nền mờ hụt mất thanh bên lẫn thanh tiêu đề, và hộp thì lệch
          xuống giữa vùng cuộn. Portal ra body thì `fixed` lại tính theo màn hình như mong đợi. */}
      {dinhXoa && createPortal((
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-2xl">
            <div className="flex items-start gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-rose-50 text-rose-600">
                <AlertTriangle size={20} />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="text-base font-bold text-slate-900">
                  Xóa bộ <span className="font-mono">{dinhXoa.examId}</span>?
                </h3>
                <p className="mt-1 text-sm text-slate-500">Không có thùng rác và không khôi phục lại được.</p>
              </div>
            </div>

            <ul className="mt-4 space-y-1.5 rounded-xl border border-slate-100 bg-slate-50 p-4 text-sm text-slate-600">
              <li>• Thư mục testcase của bộ này</li>
              <li>• <b>{dinhXoa.resultCount ?? 0}</b> bài đã chấm bằng bộ này, kèm điểm và nhận xét</li>
              <li>• Mọi phiên chấm của bộ này (kể cả phiên đang chạy — sẽ bị dừng trước khi xóa)</li>
              <li>• File .zip bài nộp đã lưu của bộ này</li>
            </ul>

            {canGoMa(dinhXoa) && (
              <div className="mt-4">
                <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">
                  Gõ lại mã bộ để xác nhận
                </label>
                <input
                  autoFocus
                  value={goXacNhan}
                  onChange={(e) => setGoXacNhan(e.target.value)}
                  placeholder={dinhXoa.examId}
                  className="w-full rounded-lg border border-slate-200 px-3 py-2 font-mono text-sm outline-none transition-colors focus:border-rose-400 focus:ring-2 focus:ring-rose-100"
                />
              </div>
            )}

            {loiXoa && <div className="mt-4"><Banner tone="error">{loiXoa}</Banner></div>}

            <div className="mt-6 flex justify-end gap-2">
              <button
                type="button"
                disabled={dangXoa}
                onClick={() => { setDinhXoa(null); setGoXacNhan(""); setLoiXoa(null); }}
                className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50"
              >
                Hủy
              </button>
              <button
                type="button"
                disabled={!xoaDuoc || dangXoa}
                onClick={xoa}
                className="inline-flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-rose-700 disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                {dangXoa ? <Loader2 size={15} className="animate-spin" /> : <Trash2 size={15} />}
                {dangXoa ? "Đang xóa..." : "Xóa vĩnh viễn"}
              </button>
            </div>
          </div>
        </div>
      ), document.body)}
    </SidebarLayout>
  );
}
