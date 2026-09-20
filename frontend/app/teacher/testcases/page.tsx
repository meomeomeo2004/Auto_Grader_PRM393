"use client";

import React, { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import Banner from "@/components/ui/Banner";
import { API_BASE } from "@/lib/config";
import {
  FileArchive, UploadCloud, Loader2, Trash2, AlertTriangle, CheckCircle2,
  ShieldAlert, Inbox, Plus,
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
  gradable?: boolean;
  hasTestcase?: boolean;
  /** Gói đề đòi mà ảnh chấm HIỆN TẠI không có. Tính lại mỗi lần mở danh sách. */
  thieuGoi?: string[];
  thieuGoiSpecs?: { name: string; version: string }[];
  resultCount?: number;
  createdAt?: string;
}

interface KetQuaNap {
  exam_id?: string;
  exam_name?: string;
  base_image_cua_goi?: string;
  base_image_may_nay?: string;
}

/** Một lần nạp bị từ chối. Giữ cả danh sách gói thiếu để còn dẫn thẳng sang Thư viện chấm. */
interface LoiNap {
  message: string;
  specs?: { name: string; version: string }[];
}

/**
 * Kết quả lần nạp gần nhất được GIỮ QUA ĐIỀU HƯỚNG, chỉ mất khi người dùng bấm dấu ×.
 *
 * <p>Trước đây nó là state thường: đổi sang màn khác rồi quay lại là sạch trơn. Mà lời báo
 * "gói này chưa nhận được, thiếu thư viện X" lại đúng là thứ người ta phải rời màn đi xử lý —
 * quay về thì không còn gì nhắc, tưởng xong rồi. Dùng sessionStorage chứ không localStorage:
 * đóng trình duyệt là hết, không lôi lời báo của tuần trước ra doạ.
 */
const KHOA_PHIEN = "nc.nhap-goi.v1";

const gioVN = (iso?: string) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString("vi-VN");
};

/**
 * Diễn giải trạng thái của một bộ đã nhận.
 *
 * <p>Nạp được không có nghĩa là chấm được MÃI MÃI: ảnh chấm đổi sau đó thì bộ cũ hỏng theo.
 * Đó là lý do duy nhất còn lại để một bộ đã nhận mang trạng thái khác "Chấm được" — và đúng
 * là lý do người dùng không tự nhìn ra được, nên phải nói thẳng thiếu gói nào.
 */
function nhanTrangThai(b: BoTestcase) {
  if (!b.hasTestcase)
    return { tone: "rose", text: "Thiếu file testcase", moTa: "Gói nhận vào không đủ file — nhận lại gói khác." };
  if (b.thieuGoi && b.thieuGoi.length > 0)
    return {
      tone: "rose",
      text: "Thiếu thư viện",
      moTa: `Thư viện chấm không có: ${b.thieuGoi.join(", ")}.`,
    };
  if (b.gradable)
    return { tone: "emerald", text: "Chấm được", moTa: "" };
  return { tone: "amber", text: "Chưa chấm được", moTa: "Bộ chưa đủ dữ kiện để chấm." };
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
  const [ketQuaNap, datKetQuaNap] = useState<KetQuaNap | null>(null);
  const [loiNap, datLoiNap] = useState<LoiNap | null>(null);

  // Đọc lại lời báo của lần nạp trước NGAY khi vào màn. Chạy một lần, sau khi đã dựng xong
  // cây React — đụng sessionStorage lúc render đầu là lệch server/client, Next kêu hydration.
  useEffect(() => {
    try {
      const luu = sessionStorage.getItem(KHOA_PHIEN);
      if (!luu) return;
      const d = JSON.parse(luu) as { loi?: LoiNap; ketQua?: KetQuaNap };
      if (d.loi) datLoiNap(d.loi);
      if (d.ketQua) datKetQuaNap(d.ketQua);
    } catch { /* phiên cũ hỏng hay bị sửa tay: bỏ, không đáng làm hỏng cả màn */ }
  }, []);

  const ghiPhien = (loi: LoiNap | null, ketQua: KetQuaNap | null) => {
    try {
      if (!loi && !ketQua) sessionStorage.removeItem(KHOA_PHIEN);
      else sessionStorage.setItem(KHOA_PHIEN, JSON.stringify({ loi, ketQua }));
    } catch { /* chế độ riêng tư chặn ghi: mất tính bền, còn lại vẫn chạy */ }
  };
  const setLoiNap = (v: LoiNap | null) => { datLoiNap(v); ghiPhien(v, v ? null : ketQuaNap); };
  const setKetQuaNap = (v: KetQuaNap | null) => { datKetQuaNap(v); ghiPhien(v ? null : loiNap, v); };

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
      if (!res.ok) {
        // Thiếu thư viện thì backend từ chối CẢ GÓI (không có bộ nào được dựng) và gửi kèm
        // tên + ràng buộc phiên bản — mang thẳng sang Thư viện chấm, vì lúc này chưa có bản
        // ghi đề nào nên màn bên đó không tự tra ra được danh sách này.
        const specs = Array.isArray(data.missing_package_specs)
          ? (data.missing_package_specs as unknown[]).flatMap((item) => {
              if (!item || typeof item !== "object") return [];
              const s = item as Record<string, unknown>;
              return typeof s.name === "string" && s.name
                ? [{ name: s.name, version: typeof s.version === "string" ? s.version : "" }]
                : [];
            })
          : undefined;
        setLoiNap({ message: data.error || `HTTP ${res.status}`, specs: specs?.length ? specs : undefined });
        return;
      }
      setKetQuaNap(data);
      await nap();
    } catch (e) {
      setLoiNap({ message: (e as Error).message });
    } finally {
      setNapping(false);
    }
  };

  // LUÔN bắt gõ lại mã, kể cả bộ chưa có bài chấm nào (19/9). Trước đây bộ "trống" xóa bằng
  // một cú bấm, nhưng trống là xét theo BẢNG ĐIỂM — bộ vẫn mang engine, hợp đồng, ảnh mẫu và
  // database ẩn của cả đề, mà bên người chấm không dựng lại được: phải xin gói bàn giao mới.
  const canGoMa = (b: BoTestcase | null) => !!b;
  const xoaDuoc = !dinhXoa ? false : goXacNhan.trim() === dinhXoa.examId;

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

  // Thiếu thư viện KHÔNG còn nằm ở đây: nó đã thành lý do từ chối cả gói, không phải ghi chú
  // trên một bộ đã nhận. Còn dấu kiểm đồng bộ khung phát thì đã bỏ hẳn 19/9 — khung phát nay
  // sinh từ chính Golden lúc xuất gói nên không có dấu nào để mang theo.
  const canhBaoNap = ketQuaNap
    && ketQuaNap.base_image_cua_goi
    && ketQuaNap.base_image_cua_goi !== ketQuaNap.base_image_may_nay
    ? [`Ảnh chấm lệch: gói làm trên ${ketQuaNap.base_image_cua_goi}, máy này đang dùng `
       + `${ketQuaNap.base_image_may_nay}. Cùng một bài có thể ra hai kết quả khác nhau.`]
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
              <p className="font-bold">Không nạp được gói — chưa có bộ nào được thêm</p>
              <p className="mt-1">{loiNap.message}</p>
              {loiNap.specs && loiNap.specs.length > 0 && (
                <a
                  className="mt-2 inline-block font-semibold underline underline-offset-2"
                  href={`/teacher/libraries?package_specs=${encodeURIComponent(JSON.stringify(loiNap.specs))}`}
                >
                  Mở Thư viện chấm để thêm {loiNap.specs.map((s) => s.name).join(", ")}
                </a>
              )}
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
              <h2 className="text-sm font-bold text-slate-800">Danh sách bộ testcase</h2>
            </div>
          </div>
          <span className="rounded-full bg-blue px-3 py-1 text-xs font-semibold text-slate-500 ring-1 ring-slate-200">
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
                <tr className="border-b border-slate-100 bg-slate-50/40 text-center text-xs font-bold uppercase tracking-wider text-slate-500">
                  <th className="w-[25%] px-6 py-3 text-center">Mã bộ</th>
                  <th className="w-[30%] px-6 py-3 text-center">Trạng thái</th>
                  <th className="w-[15%] px-6 py-3 text-center">Bài đã chấm</th>
                  <th className="w-[25%] px-6 py-3 text-center">Thời gian Upload</th>
                  <th className="w-[15%] px-6 py-3 text-center">Chức năng</th>
                </tr>
              </thead>
              <tbody>
                {ds.map((b) => {
                  const tt = nhanTrangThai(b);
                  return (
                    <tr key={b.examId} className="border-b border-slate-50 transition-colors last:border-0 hover:bg-slate-50/60">
                      <td className="px-6 py-4 text-center">
                        <div className="flex items-center justify-center gap-3">
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
                      <td className="px-6 py-4 text-center">
                        <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold ${TONE_CHIP[tt.tone]}`}>
                          {tt.tone === "emerald" ? <CheckCircle2 size={13} /> : <ShieldAlert size={13} />}
                          {tt.text}
                        </span>
                        {tt.moTa && <p className="mx-auto mt-1 max-w-xs text-xs leading-relaxed text-slate-400">{tt.moTa}</p>}
                      </td>
                      <td className="px-6 py-4 text-center font-mono text-sm font-semibold text-slate-600">
                        {b.resultCount ?? 0}
                      </td>
                      <td className="px-6 py-4 text-center text-xs text-slate-500">{gioVN(b.createdAt)}</td>
                      <td className="px-6 py-4">
                        <div className="flex items-center justify-center gap-2">
                          {/* Đường sửa nằm NGAY CẠNH chỗ báo hỏng. Bên người chấm không có ô
                              thêm package tay, nên nếu không dẫn từ đây thì họ biết thiếu mà
                              không biết thêm ở đâu. Mang theo cả ràng buộc phiên bản vì version
                              nay là bắt buộc — bắt họ tự đoán là đẩy vào đúng chỗ sai. */}
                          {b.thieuGoiSpecs && b.thieuGoiSpecs.length > 0 && (
                            <a
                              href={`/teacher/libraries?package_specs=${encodeURIComponent(JSON.stringify(b.thieuGoiSpecs))}`}
                              title={`Thêm ${b.thieuGoiSpecs.map((s) => s.name).join(", ")} vào ảnh chấm`}
                              className="inline-flex items-center gap-1.5 rounded-lg border border-amber-300 bg-amber-50 px-2.5 py-1.5 text-xs font-bold text-amber-800 transition-colors hover:bg-amber-100"
                            >
                              <Plus size={13} /> Bổ sung package
                            </a>
                          )}
                          <button
                            type="button"
                            onClick={() => { setDinhXoa(b); setGoXacNhan(""); setLoiXoa(null); }}
                            title={`Xóa bộ ${b.examId}`}
                            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-slate-400 transition-colors hover:border-rose-300 hover:bg-rose-50 hover:text-rose-600"
                          >
                            <Trash2 size={15} />
                          </button>
                        </div>
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
