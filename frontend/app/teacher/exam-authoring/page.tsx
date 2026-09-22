"use client";

/**
 * MÀN "ĐỀ BÀI" — gộp từ ba màn cũ (20/9/2026): Tạo đề, Kho tài liệu đề, Xem đề.
 *
 * Vì sao gộp: cả ba cùng ghi vào `handout/<mã đề>/`, nhưng mỗi màn hiểu "nội dung đề" một kiểu.
 * Kho tài liệu giữ file Word gốc, còn ô sửa bên dưới nó lại sửa `de_bai.md` — bấm Lưu KHÔNG
 * đụng gì tới file Word. Upload thì bóc chữ ngầm, nên một đề tự nhiên có hai bản mà không ai
 * báo. Giảng viên phải tự nhớ mình đang sửa bản nào rồi tải bản nào.
 *
 * Thêm nữa, hai màn đọc hai API khác nhau (`authored-list` và `list`) nên thấy hai danh sách
 * khác nhau — đo ngày 19/9: 4 đề chỉ hiện ở một bên, trong đó có PE_PRM393_FA26 là đề thật
 * đang dùng, không hề hiện ở màn soạn đề.
 *
 * Nay mỗi đề mang đúng MỘT bản chính, và loại đề do HÀNH ĐỘNG quyết định:
 *   NGOAI — file Word/PDF vừa tải lên, chưa ai sửa. File đó là đề bài; màn này đọc thẳng
 *           cấu trúc từ nó nên vẫn xem và sửa được ngay, không phải "đưa vào hệ thống" trước.
 *   TRONG — đã có người sửa và LƯU. `de_bai.md` + hình thành bản chính, .docx là bản xuất ra.
 * Lưu lần đầu chính là lúc đổi loại, một chiều, không có đường quay lại.
 *
 * Bố cục: danh sách (`/teacher/exam-authoring`) → chi tiết (`?de=<mã đề>`).
 */

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import Banner from "@/components/ui/Banner";
import { API_BASE } from "@/lib/config";
import { downloadBlob, imageFileToSvg, svgToPng } from "@/lib/mockup-image";
import AiSettingsPanel from "@/components/testcases/AiSettingsPanel";
import { Field, inputClass, primaryBtn, ghostBtn } from "@/components/testcases/AiWizardWidgets";
import {
  AlertTriangle, ArrowLeft, Check, Copy, Download, FileText, FileUp, Image as ImageIcon,
  Loader2, MoreHorizontal, Pencil, Plus, RefreshCw, Search, Settings2, Sparkles, Trash2, Wand2, X,
} from "lucide-react";

type LoaiDe = "NGOAI" | "TRONG";

interface DongDe {
  examId: string;
  examName?: string;
  loaiDe?: LoaiDe;
  hasDeBai?: boolean;
  hasFileGoc?: boolean;
  soHinh?: number;
  hasTestcase?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

interface Mockup { id: string; title: string; svg: string }

/**
 * Hình do giáo viên TẢI ẢNH lên, không phải AI vẽ — nhận ra qua tiền tố mã.
 *
 * <p>AI chỉ mô tả màn hình bằng JSON rồi máy chủ dựng khung dây, nên hình AI bao giờ cũng là
 * khung dây. Muốn hình đúng thiết kế thật thì tải thẳng ảnh chụp/bản thiết kế lên — ảnh được
 * bọc trong SVG (xem `imageFileToSvg`) nên đi tiếp bằng đúng đường ống cũ.
 *
 * <p>Đánh dấu để "Vẽ lại" không cuốn mất: nút đó thay TOÀN BỘ hình bằng bản AI mới, mà ảnh giáo
 * viên tự tải thì AI không thể dựng lại được — mất là mất hẳn.
 */
const laAnhTaiLen = (m: Mockup) => m.id.startsWith("anh-");

/** So tên màn hình: bỏ dấu, bỏ ký tự lạ. "Màn hình Danh Sách" và "man hinh danh sach" là một. */
const chuanHoaTen = (s: string) =>
  s.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/gi, "d")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

/**
 * Tên các màn hình khai ở mục 3 của đề bài (`## 3.1 Màn hình danh sách`, `### 3.2 …`).
 *
 * <p>Dùng để hỏi "ảnh này là màn nào?" khi giáo viên tải ảnh lên. Đọc thẳng từ đề chứ không giữ
 * một danh sách màn riêng: đề là thứ sinh viên đọc và máy chấm bám theo, danh sách nào khác cũng
 * chỉ là bản sao sớm muộn lệch khỏi nó.
 */
const tenManTuDeBai = (md: string): string[] => {
  const ra: string[] = [];
  for (const dong of md.split(/\r?\n/)) {
    // Gỡ vỏ Markdown trước rồi mới soi số mục: AI khi thì "### 3.1 Màn…", khi thì "**3.1 Màn…**",
    // khi thì viết trần. Bắt cứng một dạng là hôm nào AI đổi cách viết thì danh sách màn rỗng
    // trơn, mà rỗng thì không báo lỗi gì — chỉ là ô gợi ý biến mất, rất lâu mới có người nhận ra.
    const sach = dong.trim().replace(/^#{1,6}\s*/, "").replace(/^\*\*|\*\*$/g, "").trim();
    const ten = /^3\.\d+\.?\s+(.+)$/.exec(sach)?.[1]?.trim();
    // Chặn độ dài: một đoạn văn lỡ mở đầu bằng "3.1 " thì không thành "tên màn hình" dài ba dòng.
    if (ten && ten.length <= 80 && !ra.some((x) => chuanHoaTen(x) === chuanHoaTen(ten))) ra.push(ten);
  }
  return ra;
};

const gioVN = (iso?: string) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString("vi-VN");
};

const maDeHopLe = (s: string) => /^[A-Za-z0-9_-]{2,50}$/.test(s);

/**
 * Bỏ các thẻ chỉ dẫn căn lề `<!-- layout:right:40 -->` khỏi ô soạn.
 *
 * <p>Chúng do bộ đọc Word sinh ra để giữ căn lề và thụt lề; người soạn không cần thấy, và thấy
 * thì chỉ tổ gõ nhầm vào. Đây là đánh đổi CÓ Ý: sửa tay xong thì căn lề lấy từ Word không còn,
 * đoạn văn về canh trái như mọi Markdown khác. Giữ lại thẻ để "cứu" căn lề nghĩa là phải neo
 * từng thẻ vào đúng đoạn cũ — mà đoạn thì người ta vừa sửa, nên neo kiểu gì cũng có lúc trật,
 * và trật kiểu đó thì im lặng, không ai thấy cho tới lúc in đề ra giấy.
 */
const boTheLayout = (md: string) =>
  md
    .split(/\r?\n/)
    .filter((d) => !/^\s*<!--\s*layout:(left|center|right|justify):\d{1,3}\s*-->\s*$/.test(d))
    .join("\n")
    .replace(/\n{3,}/g, "\n\n");

async function doc(path: string, init?: RequestInit) {
  const res = await fetch(`${API_BASE}${path}`, init);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((data as { error?: string })?.error || `HTTP ${res.status}`);
  return data;
}

async function gui(path: string, body: unknown) {
  return doc(path, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
}

// ══════════════════════════════════════════════════════════════════════════════
//  TRANG
// ══════════════════════════════════════════════════════════════════════════════

export default function TrangDeBai() {
  // SidebarLayout dựng ĐÚNG MỘT LẦN, ở NGOÀI Suspense.
  //
  // Trước 20/9 nó nằm cả trong fallback lẫn trong từng nhánh con, nên khi nhánh con hiện ra
  // trang có HAI <main> và HAI <aside> cùng lúc: đo được vỏ của fallback rộng 64px còn vỏ
  // thật co về 0px. Đó chính là "giao diện bị vỡ" — chữ dồn cục, chip xuống ba bốn dòng, hai
  // cột soạn/xem bẹp dí. Không phải lỗi bề rộng hay breakpoint như tôi tưởng lúc đầu.
  return (
    <SidebarLayout title="Đề bài" activePath="/teacher/exam-authoring" contentClassName="max-w-[1200px]">
      <Suspense fallback={<KhungCho />}>
        <DieuHuong />
      </Suspense>
    </SidebarLayout>
  );
}

function KhungCho() {
  return <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={24} className="animate-spin" /></div>;
}

function DieuHuong() {
  const params = useSearchParams();
  const maDe = params.get("de");
  // Hai nhánh là hai component riêng chứ không phải hai khối JSX: mỗi nhánh có bộ hook của
  // mình, gộp lại thì nhánh này phải giữ hook của nhánh kia cho đủ thứ tự.
  return maDe
    ? <ChiTietDe maDe={maDe} moiTao={params.get("moi") || ""} tenMoi={params.get("ten") || ""} />
    : <DanhSachDe />;
}

// ══════════════════════════════════════════════════════════════════════════════
//  DANH SÁCH
// ══════════════════════════════════════════════════════════════════════════════

function DanhSachDe() {
  const router = useRouter();
  const [ds, setDs] = useState<DongDe[]>([]);
  const [dangTai, setDangTai] = useState(true);
  const [loi, setLoi] = useState<string | null>(null);
  const [bao, setBao] = useState<string | null>(null);
  const [tim, setTim] = useState("");
  const [moTao, setMoTao] = useState(false);
  const [menuMo, setMenuMo] = useState<string | null>(null);
  const [menuViTri, setMenuViTri] = useState({ top: 0, left: 0 });
  const [dinhXoa, setDinhXoa] = useState<DongDe | null>(null);
  const [nhanBan, setNhanBan] = useState<DongDe | null>(null);

  const nap = useCallback(async () => {
    setDangTai(true);
    try {
      const d = await doc("/exam-setup/list");
      setDs(Array.isArray(d) ? (d as DongDe[]).filter((e) => e?.examId) : []);
      setLoi(null);
    } catch (e) {
      setLoi("Không đọc được danh sách đề: " + (e as Error).message);
    } finally {
      setDangTai(false);
    }
  }, []);

  useEffect(() => { void nap(); }, [nap]);

  // Đóng menu ⋯ khi bấm ra ngoài — menu mở mà cuộn trang thì nó treo lơ lửng.
  useEffect(() => {
    if (!menuMo) return;
    const dong = () => setMenuMo(null);
    window.addEventListener("click", dong);
    window.addEventListener("scroll", dong, true);
    window.addEventListener("resize", dong);
    return () => {
      window.removeEventListener("click", dong);
      window.removeEventListener("scroll", dong, true);
      window.removeEventListener("resize", dong);
    };
  }, [menuMo]);

  const loc = useMemo(() => {
    const q = tim.trim().toLowerCase();
    if (!q) return ds;
    return ds.filter((e) => e.examId.toLowerCase().includes(q)
      || (e.examName || "").toLowerCase().includes(q));
  }, [ds, tim]);

  return (
    <>
      {loi && <Banner tone="error" onClose={() => setLoi(null)}>{loi}</Banner>}
      {bao && <Banner tone="ok" onClose={() => setBao(null)}>{bao}</Banner>}

      <div className="mb-5 flex flex-wrap items-center gap-3">
        <div className="relative min-w-[220px] flex-1">
          <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={tim}
            onChange={(e) => setTim(e.target.value)}
            placeholder="Tìm theo tên hoặc mã đề…"
            className="w-full rounded-lg border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <button onClick={() => setMoTao(true)} className={primaryBtn}>
          <Plus size={16} /> Tạo đề
        </button>
      </div>

      <div className="card overflow-visible">
        <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] text-center text-sm">
          <thead>
            <tr className="border-b border-slate-100 text-[10px] uppercase tracking-wider text-slate-400">
              <th className="px-5 py-3 text-center">Mã đề</th>
              <th className="px-5 py-3 text-center">Đề bài</th>
              <th className="px-5 py-3 text-center">Bản chính</th>
              <th className="px-5 py-3 text-center">Cập nhật</th>
              <th className="px-5 py-3 text-center">Thao tác</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-50">
            {dangTai && <tr><td colSpan={5}><KhungCho /></td></tr>}
            {!dangTai && loc.length === 0 && (
              <tr><td colSpan={5} className="px-5 py-12 text-center text-sm text-slate-400">
                {tim ? "Không có đề nào khớp." : "Chưa có đề nào. Bấm “Tạo đề” để bắt đầu."}
              </td></tr>
            )}
            {loc.map((e) => (
              // CẢ DÒNG mở màn chi tiết, không riêng mấy chữ tên đề.
              //
              // Trước đây chỉ <button> tên đề bắt được cú bấm. Đo trên bản đang chạy: nút rộng
              // 93×40 nằm trong một dòng 720×87 — 6% diện tích dòng. Bấm vào mã đề, vào chip
              // "có bộ chấm", vào ô ngày cập nhật, hay chỉ lệch sang khoảng trống cạnh tên đều
              // không có gì xảy ra; nên người dùng kết luận đúng theo những gì họ thấy là chỉ
              // vào được bằng nút "Mở".
              <tr key={e.examId}
                onClick={() => router.push(`/teacher/exam-authoring?de=${encodeURIComponent(e.examId)}`)}
                className="cursor-pointer hover:bg-slate-50/60">
                <td className="px-5 py-3 text-center font-mono text-xs text-slate-600">{e.examId}</td>
                <td className="px-5 py-3 text-center">
                  {/* Vẫn giữ <button> chứ không hạ thành <span>: <tr> không Tab tới được, bỏ nút
                      đi là màn này mất hẳn đường vào bằng bàn phím. Chặn nổi bọt cho khỏi push
                      hai lần cùng một đường. */}
                  <button onClick={(ev) => { ev.stopPropagation(); router.push(`/teacher/exam-authoring?de=${encodeURIComponent(e.examId)}`); }}
                    className="font-semibold text-slate-700 hover:text-indigo-600">
                    {e.examName || e.examId}
                  </button>
                  <p className="mt-0.5 flex flex-wrap items-center justify-center gap-2 text-xs text-slate-400">
                    {e.hasTestcase && <span className="rounded bg-emerald-50 px-1.5 py-0.5 font-medium text-emerald-700">có bộ chấm</span>}
                    {(e.soHinh || 0) > 0 && <span>{e.soHinh} hình</span>}
                    {e.hasFileGoc && e.loaiDe === "TRONG" && <span>1 tài liệu đính kèm</span>}
                  </p>
                </td>
                <td className="px-5 py-3 text-center"><ChipLoai dong={e} /></td>
                <td className="px-5 py-3 text-center text-xs text-slate-500">{gioVN(e.updatedAt || e.createdAt)}</td>
                {/* Chặn nổi bọt ở đây, không phải ở từng nút: menu ⋯ tuy portal ra body nhưng
                    sự kiện React vẫn nổi theo CÂY REACT, tức vẫn đi qua đúng ô này. */}
                <td className="px-5 py-3" onClick={(ev) => ev.stopPropagation()}>
                  <div className="flex items-center justify-center gap-1">
                    <button onClick={() => router.push(`/teacher/exam-authoring?de=${encodeURIComponent(e.examId)}`)}
                      className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-bold text-slate-600 hover:border-indigo-300 hover:text-indigo-600">
                      Mở
                    </button>
                    <div className="relative">
                      <button
                        onClick={(ev) => {
                          ev.stopPropagation();
                          const rect = ev.currentTarget.getBoundingClientRect();
                          setMenuViTri({
                            top: rect.bottom + 4 + 132 > window.innerHeight ? Math.max(8, rect.top - 136) : rect.bottom + 4,
                            left: Math.max(8, rect.right - 208),
                          });
                          setMenuMo(menuMo === e.examId ? null : e.examId);
                        }}
                        className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                        title="Thao tác khác"
                      >
                        <MoreHorizontal size={16} />
                      </button>
                      {/* Portal giữ menu không bị khung cuộn bảng cắt mất ở các dòng cuối. */}
                      {menuMo === e.examId && createPortal(
                        <div onClick={(ev) => ev.stopPropagation()}
                          style={menuViTri}
                          className="fixed z-50 w-52 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-xl">
                          <button onClick={() => { setMenuMo(null); setNhanBan(e); }}
                            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50">
                            <Copy size={14} /> Nhân bản sang mã mới
                          </button>
                          <a href={`${API_BASE}/exam-setup/${encodeURIComponent(e.examId)}/handout/original`}
                            onClick={() => setMenuMo(null)}
                            className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-slate-50 ${
                              e.hasFileGoc ? "text-slate-700" : "pointer-events-none text-slate-300"}`}>
                            <Download size={14} /> Tải file gốc
                          </a>
                          <button onClick={() => { setMenuMo(null); setDinhXoa(e); }}
                            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50">
                            <Trash2 size={14} /> Xoá đề
                          </button>
                        </div>, document.body
                      )}
                    </div>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </div>

      {moTao && <HopTaoDe
        maDeDaCo={ds.map((e) => e.examId)}
        onDong={() => setMoTao(false)}
        onXong={(id, ten, moi) => {
          // Tên đi kèm trên URL vì lúc này đề CHƯ TỒN TẠI: đường "Nhờ AI soạn" chỉ tạo hàng
          // trong DB ở lần Lưu đầu tiên (xem `luu`). Không mang theo thì tên vừa gõ rơi mất
          // ngay khi chuyển màn, và đề sinh ra đã không tên.
          const q = new URLSearchParams({ de: id, ten });
          if (moi) q.set("moi", moi);
          router.push(`/teacher/exam-authoring?${q.toString()}`);
        }}
        onDaTaiLen={(id) => { setMoTao(false); setBao(`Đã tạo đề ${id} từ file tải lên.`); void nap(); }}
      />}

      {nhanBan && <HopNhanBan nguon={nhanBan} onDong={() => setNhanBan(null)}
        onXong={(id) => { setNhanBan(null); setBao(`Đã nhân bản sang ${id}.`); void nap(); }} />}

      {dinhXoa && <HopXoaDe de={dinhXoa} onDong={() => setDinhXoa(null)}
        onXong={(id) => { setDinhXoa(null); setBao(`Đã xoá đề ${id}.`); void nap(); }} />}
    </>
  );
}

function ChipLoai({ dong }: { dong: DongDe }) {
  if (dong.loaiDe === "NGOAI") {
    return (
      <span className="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border border-sky-200 bg-sky-50 px-2.5 py-1 text-xs font-semibold text-sky-700"
        title="File Word/PDF bạn soạn ngoài là đề bài. Sửa trong Word rồi tải đè.">
        <FileUp size={12} /> File tải lên
      </span>
    );
  }
  if (!dong.hasDeBai) {
    return <span className="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-xs font-semibold text-slate-500">
      <AlertTriangle size={12} /> Chưa có nội dung
    </span>;
  }
  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-emerald-700"
      title="Nội dung nằm trong hệ thống — sửa được tại đây, bản .docx là bản xuất ra.">
      <FileText size={12} /> Soạn trong hệ thống
    </span>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  HỘP THOẠI
// ══════════════════════════════════════════════════════════════════════════════

/** Portal ra body: SidebarLayout có transform nên `position: fixed` bám nhầm khung nội dung. */
function Hop({ tieuDe, onDong, children, rong }: {
  tieuDe: string; onDong: () => void; children: React.ReactNode; rong?: string;
}) {
  return createPortal((
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-900/50 p-4 py-10">
      <div className={`w-full ${rong || "max-w-lg"} rounded-2xl bg-white p-6 shadow-2xl`}>
        <div className="mb-4 flex items-start justify-between gap-4">
          <h3 className="text-base font-bold text-slate-800">{tieuDe}</h3>
          <button onClick={onDong} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600">
            <X size={18} />
          </button>
        </div>
        {children}
      </div>
    </div>
  ), document.body);
}

/**
 * Tạo đề: hỏi mã đề + tên, rồi chọn MỘT trong ba đường vào. Cả ba đổ về cùng màn chi tiết.
 *
 * Form soạn đề bằng AI KHÔNG nằm ở đây — nó dài, và nhét vào hộp thoại thì vừa chật vừa mất
 * chỗ xem lại kết quả. Chọn "Nhờ AI soạn" là sang thẳng màn chi tiết, nơi có cả trang.
 */
function HopTaoDe({ maDeDaCo, onDong, onXong, onDaTaiLen }: {
  /** Mã đề đang có — để chặn trùng ngay tại ô nhập, không đợi đến lúc đã chọn file. */
  maDeDaCo: string[];
  onDong: () => void;
  onXong: (maDe: string, ten: string, moi: string) => void;
  /** Đường TẢI FILE: xong là về thẳng danh sách, không mở màn chi tiết. */
  onDaTaiLen: (maDe: string) => void;
}) {
  const [maDe, setMaDe] = useState("");
  const [ten, setTen] = useState("");
  const [busy, setBusy] = useState(false);
  const [loi, setLoi] = useState<string | null>(null);
  const oFile = useRef<HTMLInputElement>(null);

  /**
   * Bắt buộc CẢ mã đề LẪN tên đề (22/9/2026, theo yêu cầu).
   *
   * <p>Tên đề là chữ hiện ở cột "Đề bài" của danh sách. Bỏ trống thì máy chủ lấy luôn mã đề
   * làm tên (xem `ensureExamStub`), và danh sách in ra "(chưa đặt tên)" — một trạng thái không
   * nói lên điều gì ngoài chuyện có người bấm bỏ qua một ô, nhưng lại nằm chình ình ở cột
   * chính. Bắt nhập ngay tại đây thì trạng thái đó không còn đường nào sinh ra nữa.
   */
  const kiemTra = () => {
    if (!maDeHopLe(maDe.trim())) { setLoi("Mã đề chỉ gồm chữ, số, gạch dưới và gạch ngang (2–50 ký tự)."); return false; }
    // So KHÔNG PHÂN BIỆT HOA THƯỜNG: thư mục trên Windows và cột mã đề trong MySQL đều
    // không phân biệt, nên "pe213" và "PE213" là cùng một đề ở hai nơi chứa thật.
    if (maDeDaCo.some((m) => m.toLowerCase() === maDe.trim().toLowerCase())) {
      setLoi(`Mã đề ${maDe.trim()} đã có rồi. Mở đề đó ra để sửa, hoặc dùng "Nhân bản sang mã mới" nếu muốn một bản riêng.`);
      return false;
    }
    if (!ten.trim()) { setLoi("Hãy đặt tên đề — đây là tên hiện ở danh sách."); return false; }
    setLoi(null);
    return true;
  };

  const taiLen = async (f: File) => {
    if (!kiemTra()) return;
    setBusy(true);
    try {
      const form = new FormData();
      form.append("file", f);
      // moi=true: nói cho máy chủ biết đây là đề MỚI, trùng mã thì từ chối. Danh sách ở màn
      // có thể đã cũ (mở từ lâu, hoặc đề sinh ra từ tab khác), nên cửa chặn thật phải nằm ở
      // máy chủ — đường này GHI ĐÈ file Word của đề cũ, sai một lần là mất bản gốc.
      const res = await fetch(
        `${API_BASE}/exam-setup/${encodeURIComponent(maDe.trim())}/handout/original`
          + `?moi=true&examName=${encodeURIComponent(ten.trim())}`,
        { method: "POST", body: form });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data?.error || "Không tải lên được file.");
      // KHÔNG mở màn chi tiết sau khi tải lên (20/9, theo yêu cầu): file Word vừa tải LÀ đề
      // bài rồi, chẳng còn bước nào phải làm tiếp. Đẩy người dùng vào màn chi tiết chỉ để họ
      // nhìn một thẻ mời "đưa vào hệ thống" — thứ họ vừa cố ý KHÔNG chọn.
      onDaTaiLen(maDe.trim());
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Hop tieuDe="Tạo đề mới" onDong={onDong}>
      {loi && <Banner tone="error" onClose={() => setLoi(null)}>{loi}</Banner>}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Mã đề *">
          <input value={maDe} onChange={(e) => setMaDe(e.target.value)} placeholder="PE_PRM393_DEMO" className={inputClass} />
        </Field>
        <Field label="Tên đề *">
          <input value={ten} onChange={(e) => setTen(e.target.value)} placeholder="Quản lý chi tiêu cá nhân" className={inputClass} />
        </Field>
      </div>

      <p className="mb-2 mt-5 text-xs font-bold uppercase tracking-wider text-slate-400">Chọn cách tạo</p>
      <div className="space-y-2">
        <button
          onClick={() => { if (kiemTra()) oFile.current?.click(); }}
          disabled={busy}
          className="flex w-full items-start gap-3 rounded-xl border border-slate-200 p-4 text-left transition-colors hover:border-sky-300 hover:bg-sky-50/50 disabled:opacity-50"
        >
          <FileUp size={20} className="mt-0.5 shrink-0 text-sky-600" />
          <span>
            <span className="block text-sm font-bold text-slate-800">Dùng file có sẵn</span>
            <span className="block text-xs leading-relaxed text-slate-500">
              Tải Word/PDF lên và giữ NGUYÊN bản đó làm đề bài — không bóc chữ, không mất bố cục.
              Sửa thì sửa trong Word rồi tải đè.
            </span>
          </span>
        </button>
        <input ref={oFile} type="file" accept=".docx,.pdf" className="hidden"
          onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void taiLen(f); }} />

        <button
          onClick={() => { if (kiemTra()) onXong(maDe.trim(), ten.trim(), "ai"); }}
          disabled={busy}
          className="flex w-full items-start gap-3 rounded-xl border border-slate-200 p-4 text-left transition-colors hover:border-indigo-300 hover:bg-indigo-50/50 disabled:opacity-50"
        >
          <Sparkles size={20} className="mt-0.5 shrink-0 text-indigo-600" />
          <span>
            <span className="block text-sm font-bold text-slate-800">Nhờ AI soạn</span>
            <span className="block text-xs leading-relaxed text-slate-500">
              Khai chủ đề và yêu cầu, nhận bản nháp để xem và sửa. Có hình minh hoạ, sửa được bằng AI.
            </span>
          </span>
        </button>

        {/* "Tự gõ" đã bỏ (20/9, theo yêu cầu): mở một trang trắng rồi tự gõ Markdown là đường
            không ai đi — có AI soạn nháp rồi sửa thì nhanh hơn hẳn. Đề đã tạo vẫn sửa tay
            được bình thường ở nút Sửa bên màn chi tiết. */}
      </div>
      {busy && <p className="mt-3 flex items-center gap-2 text-xs text-slate-500"><Loader2 size={13} className="animate-spin" /> Đang tải lên…</p>}
    </Hop>
  );
}

function HopNhanBan({ nguon, onDong, onXong }: { nguon: DongDe; onDong: () => void; onXong: (id: string) => void }) {
  const [maMoi, setMaMoi] = useState(`${nguon.examId}_COPY`);
  const [ten, setTen] = useState(nguon.examName || "");
  const [busy, setBusy] = useState(false);
  const [loi, setLoi] = useState<string | null>(null);

  const chay = async () => {
    if (!maDeHopLe(maMoi.trim())) { setLoi("Mã đề mới không hợp lệ."); return; }
    // Cùng luật với hộp Tạo đề: không để đường nào đẻ ra một đề không tên.
    if (!ten.trim()) { setLoi("Hãy đặt tên cho đề mới."); return; }
    setBusy(true); setLoi(null);
    try {
      await gui(`/exam-setup/${encodeURIComponent(nguon.examId)}/clone-handout`,
        { target_exam_id: maMoi.trim(), exam_name: ten.trim() });
      onXong(maMoi.trim());
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Hop tieuDe={`Nhân bản đề ${nguon.examId}`} onDong={onDong}>
      {loi && <Banner tone="error" onClose={() => setLoi(null)}>{loi}</Banner>}
      <p className="mb-4 text-sm leading-relaxed text-slate-500">
        Chép đề bài, hình minh hoạ và file gốc sang một mã đề mới. Bộ chấm KHÔNG được chép —
        đề mới bắt đầu từ chỗ chưa có bộ chấm nào.
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Mã đề mới *">
          <input value={maMoi} onChange={(e) => setMaMoi(e.target.value)} className={inputClass} />
        </Field>
        <Field label="Tên đề *">
          <input value={ten} onChange={(e) => setTen(e.target.value)} className={inputClass} />
        </Field>
      </div>
      <div className="mt-5 flex justify-end gap-2">
        <button onClick={onDong} className={ghostBtn}>Hủy</button>
        <button onClick={chay} disabled={busy} className={primaryBtn}>
          {busy ? <Loader2 size={15} className="animate-spin" /> : <Copy size={15} />} Nhân bản
        </button>
      </div>
    </Hop>
  );
}

/** Xoá đề: LUÔN bắt gõ lại mã, kể cả đề trống — cùng luật với bên người chấm. */
/**
 * Xoá đề: liệt kê đúng những gì sẽ mất, rồi HAI NÚT — Hủy / Xoá.
 *
 * <p>Bỏ ô "gõ lại mã đề" (20/9, theo yêu cầu): hộp này đã là một bước riêng có kể rõ hậu quả,
 * bắt gõ thêm một lần nữa là hai lớp xác nhận cho cùng một thao tác. Bên màn người chấm vẫn
 * giữ ô gõ vì ở đó xoá bộ là mất luôn BẢNG ĐIỂM đã chấm, không dựng lại được.
 */
function HopXoaDe({ de, onDong, onXong }: { de: DongDe; onDong: () => void; onXong: (id: string) => void }) {
  const [busy, setBusy] = useState(false);
  const [loi, setLoi] = useState<string | null>(null);

  const chay = async () => {
    setBusy(true); setLoi(null);
    try {
      await doc(`/exam-setup/${encodeURIComponent(de.examId)}`, { method: "DELETE" });
      onXong(de.examId);
    } catch (e) {
      setLoi((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Hop tieuDe={`Xoá đề ${de.examId}?`} onDong={onDong}>
      {loi && <Banner tone="error" onClose={() => setLoi(null)}>{loi}</Banner>}
      <ul className="space-y-1.5 rounded-xl border border-slate-100 bg-slate-50 p-4 text-sm text-slate-600">
        <li>• Đề bài, hình minh hoạ và file gốc</li>
        {de.hasTestcase && <li>• <b>Bộ chấm</b> của đề này, kèm mọi bài đã chấm và phiên chấm</li>}
      </ul>
      <div className="mt-5 flex justify-end gap-2">
        <button onClick={onDong} disabled={busy} className={ghostBtn}>Hủy</button>
        <button onClick={chay} disabled={busy} autoFocus
          className="flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700 disabled:opacity-40">
          {busy ? <Loader2 size={15} className="animate-spin" /> : <Trash2 size={15} />} Xoá vĩnh viễn
        </button>
      </div>
    </Hop>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  CHI TIẾT MỘT ĐỀ
// ══════════════════════════════════════════════════════════════════════════════

function ChiTietDe({ maDe, moiTao, tenMoi }: { maDe: string; moiTao: string; tenMoi: string }) {
  const router = useRouter();
  const [dangTai, setDangTai] = useState(true);
  const [loi, setLoi] = useState<string | null>(null);
  const [bao, setBao] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [loaiDe, setLoaiDe] = useState<LoaiDe>("TRONG");
  const [tenDe, setTenDe] = useState(tenMoi);
  const [deBai, setDeBai] = useState("");
  const [daLuu, setDaLuu] = useState("");
  const [mockups, setMockups] = useState<Mockup[]>([]);
  const [hinhDaLuu, setHinhDaLuu] = useState<Mockup[]>([]);
  const [fileGoc, setFileGoc] = useState<{ exists: boolean; file_name?: string; size_bytes?: number }>({ exists: false });
  const [sua, setSua] = useState(false);
  const [html, setHtml] = useState("");
  const [htmlDaLuu, setHtmlDaLuu] = useState("");
  const [moCaiDat, setMoCaiDat] = useState(false);
  const [yeuCauAi, setYeuCauAi] = useState("");
  const [banAi, setBanAi] = useState<string | null>(null);
  const [formAi, setFormAi] = useState(moiTao === "ai");
  // Bản nháp AI CHƯA từng lưu: bấm Hủy ở đây không phải "quay về bản cũ" như mọi lần sửa khác —
  // không có bản cũ nào cả, huỷ là vứt sạch công AI vừa làm. Nên phải hỏi lại, và hỏi xong thì
  // đưa người ta về danh sách chứ đừng bỏ lại giữa một màn trắng chỉ có mỗi mã đề.
  const [nhapAi, setNhapAi] = useState(false);
  const [hoiHuyAi, setHoiHuyAi] = useState(false);
  /** Ảnh vừa chọn, đang chờ giáo viên khai nó là màn nào — xem HopDatTenAnh. */
  const [anhCho, setAnhCho] = useState<File | null>(null);
  const oFile = useRef<HTMLInputElement>(null);
  const oAnh = useRef<HTMLInputElement>(null);

  const chuaLuu = deBai !== daLuu || JSON.stringify(mockups) !== JSON.stringify(hinhDaLuu);
  /** Đang ở form khai yêu cầu cho AI (đề mới tinh, chưa có nội dung nào). */
  const dangKhaiAi = formAi && !daLuu.trim();

  const nap = useCallback(async () => {
    setDangTai(true);
    try {
      const [xem, ds, goc] = await Promise.all([
        doc(`/exam-setup/${encodeURIComponent(maDe)}/de-bai/view`).catch(() => ({})),
        doc("/exam-setup/list").catch(() => []),
        doc(`/exam-setup/${encodeURIComponent(maDe)}/handout/original/info`).catch(() => ({ exists: false })),
      ]);
      const v = xem as { de_bai?: string; html?: string; mockups?: Mockup[] };
      setDeBai(String(v.de_bai || ""));
      setDaLuu(String(v.de_bai || ""));
      setHtml(String(v.html || ""));
      setHtmlDaLuu(String(v.html || ""));
      setMockups(Array.isArray(v.mockups) ? v.mockups : []);
      setHinhDaLuu(Array.isArray(v.mockups) ? v.mockups : []);
      setFileGoc(goc as { exists: boolean });
      const dong = (Array.isArray(ds) ? (ds as DongDe[]) : []).find((e) => e.examId === maDe);
      setLoaiDe((dong?.loaiDe as LoaiDe) || "TRONG");
      // Đề đã có hàng trong DB thì lấy tên ở đó; đề vừa tạo qua đường AI thì chưa có hàng nào,
      // tên còn nằm trên URL — rơi về đó chứ không rơi về rỗng.
      setTenDe(dong?.examName && dong.examName !== maDe ? dong.examName : tenMoi);
      setLoi(null);
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setDangTai(false);
    }
  }, [maDe, tenMoi]);

  useEffect(() => { void nap(); }, [nap]);

  // XEM TRƯỚC SỐNG: đang sửa thì dựng lại HTML theo chữ VỪA GÕ, chờ 400ms cho hết nhịp gõ.
  //
  // Dựng ở máy chủ bằng đúng `HandoutDocument.toHtml` — cùng bộ sinh ra de_bai.html và bản
  // .docx — nên cái nhìn thấy lúc soạn là cái sinh viên nhận. Trình duyệt không có thư viện
  // markdown nào (repo build offline) mà tự viết bộ thứ hai thì sớm muộn hai bên lệch nhau,
  // đúng loại lệch không ai phát hiện cho tới lúc in đề ra giấy.
  //
  // Thiếu hẳn phần này thì bấm "Xem" chỉ thấy bản ĐÃ LƯU: gõ xong tưởng mất chữ.
  useEffect(() => {
    if (!sua) return;
    if (!deBai.trim()) { setHtml(""); return; }
    const timer = setTimeout(async () => {
      try {
        const d = await gui(`/exam-setup/${encodeURIComponent(maDe)}/de-bai/xem-truoc`, {
          de_bai: deBai,
          mockups: mockups.map((m) => ({ id: m.id, title: m.title, svg: m.svg })),
        });
        setHtml(String((d as { html?: string }).html || ""));
      } catch { /* mất một nhịp xem trước không đáng làm hỏng phiên soạn */ }
    }, 400);
    return () => clearTimeout(timer);
  }, [deBai, mockups, sua, maDe]);

  /** Vào sửa: nạp bản đang xem vào ô soạn, bỏ thẻ chỉ dẫn căn lề trước khi cho người ta thấy. */
  const vaoSua = () => {
    const sach = boTheLayout(deBai);
    if (sach !== deBai) setDeBai(sach);
    setSua(true);
  };

  // Hủy phải trả cả chữ lẫn hình về bản đã lưu, kể cả khi bản nháp đến từ AI.
  const huySua = () => {
    setDeBai(daLuu);
    setMockups(hinhDaLuu);
    setHtml(htmlDaLuu);
    setBanAi(null);
    setBao(null);
    setLoi(null);
    setSua(false);
  };

  /** Bản nháp AI chưa lưu thì hỏi lại trước khi vứt; còn lại là sửa thường, huỷ về bản đã lưu. */
  const bamHuy = () => {
    if (nhapAi) setHoiHuyAi(true);
    else huySua();
  };

  const luu = async () => {
    if (!deBai.trim()) { setLoi("Chưa có nội dung đề bài để lưu."); return; }
    setBusy("luu"); setLoi(null);
    try {
      await gui(`/exam-setup/${encodeURIComponent(maDe)}/handout`,
        // Gửi kèm tên đề: với đường "Nhờ AI soạn" thì đây chính là lần đầu đề có hàng trong
        // DB, nên cũng là lần DUY NHẤT đặt được tên cho nó mà không phải qua màn đổi tên.
        { de_bai: deBai, exam_name: tenDe.trim() || undefined,
          mockups: mockups.map((m) => ({ id: m.id, svg: m.svg })) });
      setDaLuu(deBai);
      setBao("Đã lưu đề bài.");
      await nap();
      setSua(false);
      setNhapAi(false);   // đã có bản trên đĩa rồi, từ giờ Hủy là quay về bản đó
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const anhChoTaiLieu = async () => {
    const out: { id: string; png_base64: string; width: number; height: number }[] = [];
    for (const m of mockups) {
      try {
        const { png, width, height } = await svgToPng(m.svg);
        out.push({ id: m.id, png_base64: png, width, height });
      } catch { /* một hình lỗi không chặn cả file */ }
    }
    return out;
  };

  const taiTaiLieu = async (dang: "docx" | "pdf") => {
    setBusy(dang); setLoi(null);
    try {
      const images = await anhChoTaiLieu();
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(maDe)}/de-bai/${dang}`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ images }),
      });
      if (!res.ok) {
        const d = await res.json().catch(() => ({}));
        throw new Error(d?.error || `Không tải được bản .${dang} — hãy lưu đề trước.`);
      }
      downloadBlob(await res.blob(), `${maDe}_de_bai.${dang}`);
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const nhoAiSua = async () => {
    if (!yeuCauAi.trim()) { setLoi("Hãy mô tả bạn muốn AI sửa gì."); return; }
    setBusy("ai"); setLoi(null);
    try {
      const d = await gui("/ai/exam/revise", { de_bai: deBai, instruction: yeuCauAi });
      setBanAi(String((d as { de_bai?: string }).de_bai || ""));
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  /**
   * Nhờ AI vẽ lại khung dây cho các màn ở mục 3.
   *
   * <p>Màn nào giáo viên đã tải ảnh THẬT lên thì AI khỏi vẽ: báo tên màn đó vào lời nhờ để AI bỏ
   * qua ngay từ đầu (đỡ tiền, đỡ thời gian), và nếu AI vẫn cố vẽ thì ở đây loại nốt. Thiếu bước
   * này thì đề in ra có HAI hình cho cùng một màn — một khung dây, một ảnh thật — và người đọc
   * không biết phải tin cái nào.
   */
  const veHinh = async (yeuCau?: string) => {
    setBusy("hinh"); setLoi(null);
    try {
      const anh = mockups.filter(laAnhTaiLen);
      const loiNhac = anh.length === 0 ? "" :
        `Các màn sau đã có ảnh thật rồi, KHÔNG cần vẽ: ${anh.map((m) => m.title).join("; ")}.`;
      const d = await gui("/ai/exam/mockup", {
        de_bai: deBai,
        instruction: [yeuCau?.trim(), loiNhac].filter(Boolean).join("\n") || undefined,
      });
      const ds = (d as { mockups?: Mockup[] }).mockups;
      // THAY TẠI CHỖ chứ không lọc bỏ rồi nối ảnh vào cuối: thứ tự hình trong đề phải theo thứ tự
      // màn ở mục 3. Đẩy ảnh xuống cuối là đề in ra thành màn 2, màn 3, rồi mới màn 1.
      const daDung = new Set<string>();
      const hop = (Array.isArray(ds) ? ds : []).map((m) => {
        const thay = anh.find((a) => chuanHoaTen(a.title) === chuanHoaTen(m.title));
        if (!thay) return m;
        daDung.add(thay.id);
        return thay;
      });
      // Ảnh không khớp màn nào (giáo viên tự đặt tên khác) vẫn phải còn, nối vào cuối.
      setMockups([...hop, ...anh.filter((a) => !daDung.has(a.id))]);
      setSua(true);
      setBao(`AI đã vẽ lại ${hop.length - daDung.size} hình`
        + (anh.length ? `, giữ nguyên ${anh.length} ảnh bạn tải lên` : "") + " — bấm Lưu để giữ.");
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  /**
   * Tải ẢNH THẬT lên làm hình minh hoạ — ảnh chụp app mẫu hay bản thiết kế của chính giáo viên.
   *
   * <p>Đây là đường DUY NHẤT để hình giống hệt thiết kế: AI chỉ mô tả màn hình bằng JSON rồi máy
   * chủ dựng khung dây từ vốn hình khối có sẵn, nên hình AI mãi mãi là khung dây. Ảnh tải lên đi
   * thẳng, không qua chỗ thắt đó.
   *
   * <p>{@code ten} là tên MÀN HÌNH giáo viên khai, không phải tên file. Hai lý do bắt khai: tên
   * này in thẳng vào đề bài làm chú thích trên mỗi hình (tên file thật ngoài đời là
   * "Screenshot_20260920_143210"), và nó là thứ duy nhất cho biết ảnh thay cho màn nào — ảnh
   * trùng tên với một khung dây thì THAY ĐÚNG CHỖ nó, không nằm thêm một hình nữa.
   */
  const themAnh = async (f: File, ten: string) => {
    setBusy("anh"); setLoi(null);
    try {
      const { svg } = await imageFileToSvg(f, ten);
      const moi: Mockup = { id: `anh-${Date.now().toString(36)}`, title: ten, svg };
      const chO = mockups.findIndex((m) => chuanHoaTen(m.title) === chuanHoaTen(ten));
      const ds = [...mockups];
      if (chO >= 0) ds.splice(chO, 1, moi); else ds.push(moi);
      setMockups(ds);
      setSua(true);
      setBao(chO >= 0
        ? `Ảnh đã thay hình “${ten}” — bấm Lưu để giữ.`
        : `Đã thêm ảnh “${ten}” — bấm Lưu để giữ.`);
    } catch (e) {
      setLoi((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const xoaHinh = (id: string) => {
    setMockups(mockups.filter((m) => m.id !== id));
    setSua(true);
  };

  if (dangTai) {
    return <KhungCho />;
  }

  return (
    <>
      {/* Tách tiêu đề và cụm nút để tên đề dài không ép các thao tác trên khung hẹp. */}
      <div className="mb-5">
        <button onClick={() => router.push("/teacher/exam-authoring")}
          className="mb-2 inline-flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm font-semibold text-slate-500 hover:bg-slate-100">
          <ArrowLeft size={16} /> Danh sách
        </button>
        <div>
          <div className="min-w-0">
            <h2 className="truncate text-lg font-bold text-slate-800">{tenDe || maDe}</h2>
            <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-400">
              <span className="font-mono">{maDe}</span>
              <ChipLoai dong={{ examId: maDe, loaiDe, hasDeBai: !!daLuu.trim() }} />
              {chuaLuu && <span className="font-semibold text-amber-600">• chưa lưu</span>}
            </div>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button onClick={() => setMoCaiDat(true)} title="Cấu hình AI"
              className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-slate-200 text-slate-400 hover:text-indigo-600">
              <Settings2 size={16} />
            </button>
            {/* Mọi đề đều xem và sửa được, kể cả đề vừa tải file lên (20/9): nội dung hiển thị
                bóc thẳng từ file Word, và LƯU lần đầu chính là lúc nó thành "soạn trong hệ
                thống". Không còn bước "Đưa vào hệ thống" riêng. */}
            {(
              <>
                <button onClick={() => taiTaiLieu("docx")} disabled={!!busy || !daLuu.trim()} className={ghostBtn}>
                  {busy === "docx" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />} Tải Word
                </button>
                {sua ? (
                  <>
                    <button onClick={bamHuy} disabled={!!busy} className={ghostBtn}>Hủy</button>
                    <button onClick={luu} disabled={!!busy || !chuaLuu} className={primaryBtn}>
                      {busy === "luu" ? <Loader2 size={15} className="animate-spin" /> : <Check size={15} />} Lưu
                    </button>
                  </>
                ) : (
                  <button onClick={vaoSua} disabled={!!busy} className={ghostBtn}>
                    <Pencil size={15} /> Sửa đề bài
                  </button>
                )}
              </>
            )}
          </div>
        </div>
      </div>

      {loi && <Banner tone="error" onClose={() => setLoi(null)}>{loi}</Banner>}
      {bao && <Banner tone="ok" onClose={() => setBao(null)}>{bao}</Banner>}

      {dangKhaiAi ? (
        <FormAiSoanDe maDe={maDe} onXong={(md, hinh) => {
          setDeBai(md); setMockups(hinh); setFormAi(false); setSua(true); setNhapAi(true);
          setBao("AI đã soạn xong bản nháp — xem lại rồi bấm Lưu.");
        }} onLoi={setLoi} />
      ) : (
        <>
          {sua ? (
              <textarea
                aria-label="Nội dung đề bài"
                value={deBai}
                onChange={(e) => setDeBai(e.target.value)}
                disabled={!!busy}
                spellCheck={false}
                placeholder="# Đề bài&#10;&#10;Soạn bằng Markdown…"
                className="h-[70vh] w-full resize-none rounded-xl border border-slate-200 bg-white p-4 font-mono text-[13px] leading-relaxed outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
          ) : (
            <div className="h-[70vh] min-w-0 overflow-hidden rounded-xl border border-slate-200 bg-white">
              {html ? (
                // HTML là tài liệu đầy đủ có CSS body/table; phải cô lập để không bó cả ứng dụng.
                <iframe
                  title={`Xem trước đề bài ${maDe}`}
                  srcDoc={html}
                  sandbox=""
                  className="block h-full w-full border-0"
                />
              ) : (
                <p className="p-10 text-center text-sm text-slate-400">
                  Chưa có nội dung. Bấm “Sửa đề bài” để soạn hoặc nhờ AI chỉnh sửa bên dưới.
                </p>
              )}
            </div>
          )}

        <section className="card mt-4 p-5" aria-label="AI hỗ trợ chỉnh sửa">
          <h3 className="mb-3 text-sm font-bold text-slate-700">Nhờ AI chỉnh sửa đề bài</h3>
          {banAi === null ? (
            <>
              <Field label="Bạn muốn sửa gì?">
                <textarea value={yeuCauAi} onChange={(e) => setYeuCauAi(e.target.value)} rows={4}
                  placeholder="vd: thêm một chức năng lọc theo tháng, và ghi rõ định dạng ngày là yyyy-MM-dd"
                  className={inputClass} />
              </Field>
              <div className="mt-4 flex justify-end gap-2">
                <button onClick={nhoAiSua} disabled={!!busy} className={primaryBtn}>
                  {busy === "ai" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Nhờ AI sửa
                </button>
              </div>
            </>
          ) : (
            <>
              {/* AI KHÔNG ghi thẳng vào đề: đưa bản sửa ra xem trước rồi mới quyết. Ghi thẳng
                  thì một câu mô tả mơ hồ có thể cuốn mất cả phần giảng viên đã gọt kỹ. */}
              <p className="mb-2 text-xs font-semibold text-slate-500">Bản AI đề xuất — xem rồi quyết:</p>
              <textarea value={banAi} onChange={(e) => setBanAi(e.target.value)} rows={18}
                className="w-full rounded-lg border border-slate-200 p-3 font-mono text-[12px] leading-relaxed outline-none focus:border-indigo-400" />
              <div className="mt-4 flex justify-end gap-2">
                <button onClick={() => setBanAi(null)} className={ghostBtn}>Bỏ qua</button>
                <button onClick={() => { setDeBai(banAi); setBanAi(null); setYeuCauAi(""); setSua(true); }}
                  className={primaryBtn}><Check size={15} /> Áp dụng</button>
              </div>
            </>
          )}
        </section>

          <KhoiHinh mockups={mockups} busy={busy} maDe={maDe} onVe={veHinh}
            onTaiAnh={() => oAnh.current?.click()} onXoaHinh={xoaHinh} onLoi={setLoi} />
        </>
      )}

      {/* Khối file gốc: với đề NGOAI đây CHÍNH LÀ đề bài, với đề TRONG nó là tài liệu đính
          kèm. Cùng một khối, đổi lời theo loại — xem KhoiDinhKem.

          KHÔNG hiện suốt quãng đề sinh bằng AI còn là bản nháp (20/9, theo yêu cầu): từ lúc khai
          yêu cầu cho tới khi bấm Lưu, đề CHƯA TỒN TẠI trên đĩa — mời người ta đính kèm tài liệu
          vào một cái chưa có là vô nghĩa, mà bấm vào thì lại tạo thư mục đề nửa vời trong khi bản
          nháp vẫn chưa lưu. Lưu xong là khối này hiện lại bình thường. */}
      {!dangKhaiAi && !nhapAi && (
        <KhoiDinhKem maDe={maDe} fileGoc={fileGoc} laBanChinh={loaiDe === "NGOAI"}
          onTaiLen={() => oFile.current?.click()}
          onXoa={async () => {
            try {
              await doc(`/exam-setup/${encodeURIComponent(maDe)}/handout/original`, { method: "DELETE" });
              await nap();
            } catch (e) { setLoi((e as Error).message); }
          }} />
      )}

      <input ref={oAnh} type="file" accept="image/*" className="hidden"
        onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) setAnhCho(f); }} />

      {anhCho && (
        <HopDatTenAnh
          file={anhCho}
          manCoSan={[...tenManTuDeBai(deBai), ...mockups.map((m) => m.title)]}
          onDong={() => setAnhCho(null)}
          onXong={(ten) => { setAnhCho(null); void themAnh(anhCho, ten); }}
        />
      )}

      <input ref={oFile} type="file" accept=".docx,.pdf" className="hidden"
        onChange={async (e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (!f) return;
          setBusy("tailen"); setLoi(null);
          try {
            const form = new FormData();
            form.append("file", f);
            const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(maDe)}/handout/original`,
              { method: "POST", body: form });
            const d = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(d?.error || "Không tải lên được file.");
            await nap();
            setBao(`Đã tải lên ${f.name}.`);
          } catch (err) {
            setLoi((err as Error).message);
          } finally {
            setBusy(null);
          }
        }} />

      {moCaiDat && (
        <Hop tieuDe="Cấu hình AI" onDong={() => setMoCaiDat(false)} rong="max-w-2xl">
          <AiSettingsPanel />
        </Hop>
      )}

      {hoiHuyAi && (
        <Hop tieuDe="Hủy bỏ đề vừa sinh?" onDong={() => setHoiHuyAi(false)}>
          <p className="text-sm leading-relaxed text-slate-600">
            Bạn chắc chắn muốn hủy bỏ đề sinh bằng AI này chứ? Bản nháp <b>chưa được lưu</b> nên
            cả phần đề bài lẫn hình minh hoạ sẽ mất hẳn, không lấy lại được.
          </p>
          <div className="mt-5 flex justify-end gap-2">
            <button onClick={() => setHoiHuyAi(false)} className={ghostBtn}>Hủy</button>
            <button onClick={() => router.push("/teacher/exam-authoring")} autoFocus
              className="flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700">
              <Trash2 size={15} /> Tiếp tục
            </button>
          </div>
        </Hop>
      )}
    </>
  );
}

/**
 * Hỏi ảnh vừa chọn minh hoạ MÀN NÀO, trước khi nhận nó vào danh sách hình.
 *
 * <p>Bắt khai chứ không lẳng lặng lấy tên file, vì tên này đi xa hơn màn hình soạn đề: nó in
 * thẳng vào đề bài làm chú thích phía trên mỗi hình. Tên file thật ngoài đời là
 * "Screenshot_20260920_143210" hay "z5123456789_abc" — in cái đó lên đề phát cho sinh viên thì
 * chẳng ai hiểu hình đang nói về màn nào.
 *
 * <p>Và nó còn là thứ DUY NHẤT nối ảnh với một màn ở mục 3: trùng tên thì ảnh thay đúng chỗ khung
 * dây của màn đó, đồng thời lần "Vẽ lại" sau AI được báo để khỏi vẽ lại màn ấy nữa.
 *
 * <p>Gợi ý sẵn các màn đọc từ mục 3 của đề — bấm một cái là xong, khỏi gõ và khỏi gõ sai chính tả
 * so với đề. Không có màn nào hợp thì gõ tay, ô chữ điền sẵn tên file cho khỏi trắng trơn.
 */
function HopDatTenAnh({ file, manCoSan, onDong, onXong }: {
  file: File; manCoSan: string[]; onDong: () => void; onXong: (ten: string) => void;
}) {
  const [ten, setTen] = useState(file.name.replace(/\.[^.]+$/, "").replace(/[_-]+/g, " ").trim());
  const man = useMemo(() => {
    const ra: string[] = [];
    for (const m of manCoSan) {
      const t = m.trim();
      if (t && !ra.some((x) => chuanHoaTen(x) === chuanHoaTen(t))) ra.push(t);
    }
    return ra;
  }, [manCoSan]);
  const trung = man.find((m) => chuanHoaTen(m) === chuanHoaTen(ten));

  return (
    <Hop tieuDe="Ảnh này minh hoạ màn nào?" onDong={onDong}>
      <p className="mb-4 text-xs leading-relaxed text-slate-500">
        Tên bạn khai ở đây <b>in vào đề bài</b>, ngay phía trên hình. Chọn đúng tên màn ở mục 3 thì
        ảnh sẽ <b>thay</b> khung dây AI vẽ cho màn đó, và lần “Vẽ lại” sau AI sẽ bỏ qua màn này.
      </p>
      {man.length > 0 && (
        <div className="mb-4 flex flex-wrap gap-2">
          {man.map((m) => (
            <button key={m} onClick={() => setTen(m)}
              className={`rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors ${
                chuanHoaTen(m) === chuanHoaTen(ten)
                  ? "border-indigo-300 bg-indigo-50 text-indigo-700"
                  : "border-slate-200 text-slate-600 hover:border-indigo-200 hover:bg-indigo-50/40"}`}>
              {m}
            </button>
          ))}
        </div>
      )}
      <Field label="Tên màn hình *">
        <input value={ten} onChange={(e) => setTen(e.target.value)} autoFocus
          placeholder="vd: Màn hình danh sách sách" className={inputClass}
          onKeyDown={(e) => { if (e.key === "Enter" && ten.trim()) onXong(ten.trim()); }} />
      </Field>
      <p className="mt-2 text-xs text-slate-400">
        Tệp: <span className="font-mono">{file.name}</span> · {(file.size / 1024).toFixed(0)} KB
        {trung && <span className="ml-2 font-semibold text-amber-600">• sẽ thay hình “{trung}” đang có</span>}
      </p>
      <div className="mt-5 flex justify-end gap-2">
        <button onClick={onDong} className={ghostBtn}>Hủy</button>
        <button onClick={() => onXong(ten.trim())} disabled={!ten.trim()} className={primaryBtn}>
          <Check size={15} /> Thêm ảnh
        </button>
      </div>
    </Hop>
  );
}

/**
 * Khối hình minh hoạ: AI vẽ khung dây, HOẶC giáo viên tải thẳng ảnh thiết kế của mình lên.
 *
 * <p>Hai đường tồn tại song song có lý do: AI chỉ mô tả màn hình bằng JSON rồi máy chủ dựng hình
 * từ vốn hình khối có sẵn, nên hình AI bao giờ cũng là khung dây — muốn hình giống hệt bản thiết
 * kế thì không có cách nào khác ngoài đưa chính ảnh đó vào.
 */
function KhoiHinh({ mockups, busy, maDe, onVe, onTaiAnh, onXoaHinh, onLoi }: {
  mockups: Mockup[]; busy: string | null; maDe: string;
  onVe: (yeuCau?: string) => void; onTaiAnh: () => void; onXoaHinh: (id: string) => void;
  onLoi: (s: string) => void;
}) {
  const [yeuCau, setYeuCau] = useState("");
  return (
    <div className="card mt-4 p-5">
      <div className="flex flex-wrap items-center gap-3">
        <p className="flex items-center gap-2 text-sm font-bold text-slate-700">
          <ImageIcon size={16} className="text-indigo-500" /> Hình minh hoạ giao diện ({mockups.length})
        </p>
        <div className="flex-1" />
        <input value={yeuCau} onChange={(e) => setYeuCau(e.target.value)}
          placeholder="Muốn AI vẽ lại thế nào? (để trống = vẽ theo đề)"
          className="min-w-[240px] flex-1 rounded-lg border border-slate-200 px-3 py-1.5 text-sm outline-none focus:border-indigo-400" />
        <button onClick={onTaiAnh} disabled={!!busy} className={ghostBtn} title="Dùng ảnh chụp hoặc bản thiết kế của bạn">
          {busy === "anh" ? <Loader2 size={15} className="animate-spin" /> : <FileUp size={15} />} Tải ảnh lên
        </button>
        <button onClick={() => { onVe(yeuCau); setYeuCau(""); }} disabled={!!busy} className={ghostBtn}>
          {busy === "hinh" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Vẽ lại
        </button>
      </div>
      {mockups.length > 0 && (
        // Thẻ CỐ ĐỊNH bề ngang, không phải lưới co giãn. Lưới cũ cho SVG rộng bằng cột, mà hình
        // mang dáng máy (cao gấp 2,2 lần bề ngang) nên cột rộng bao nhiêu hình cao gấp đôi bấy
        // nhiêu — khung hẹp thì một màn cao hơn 2000px, vuốt mãi không hết. Nay mỗi hình đúng
        // 309px bề ngang = cỡ khung xem thử ở màn Bộ chấm Golden (412 dp × 3/4, xem MUC_THU_NHO
        // bên behavior-authoring), để hai màn nhìn cùng một cỡ và mắt quen được với nó.
        <div className="mt-4 flex flex-wrap gap-4">
          {mockups.map((m) => (
            <div key={m.id} className="w-[325px] max-w-full rounded-xl border border-slate-200 p-2">
              <div className="overflow-hidden rounded-lg bg-slate-50 [&_svg]:h-auto [&_svg]:w-full"
                dangerouslySetInnerHTML={{ __html: m.svg }} />
              <div className="mt-2 flex items-center justify-between gap-1">
                <span className="truncate text-xs font-medium text-slate-500" title={m.title}>
                  {laAnhTaiLen(m) && <span className="mr-1 text-indigo-400">ảnh</span>}{m.title}
                </span>
                <div className="flex shrink-0 items-center">
                  <button
                    onClick={async () => {
                      try {
                        const { png } = await svgToPng(m.svg);
                        downloadBlob(await (await fetch(png)).blob(), `${maDe}_${m.id}.png`);
                      } catch (e) { onLoi((e as Error).message); }
                    }}
                    className="rounded p-1 text-slate-400 hover:text-indigo-600" title="Tải PNG">
                    <Download size={13} />
                  </button>
                  <button onClick={() => onXoaHinh(m.id)}
                    className="rounded p-1 text-slate-400 hover:text-rose-600" title="Gỡ hình này">
                    <X size={13} />
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Khối file Word/PDF gốc. MỘT khối cho cả hai loại đề, chỉ đổi lời:
 *   - đề chưa ai sửa → file này CHÍNH LÀ đề bài
 *   - đề đã sửa trong hệ thống → nó lùi về tài liệu đính kèm
 * Tách làm hai khối thì sớm muộn hai bên lệch nhau về nút bấm và cách gọi API.
 */
function KhoiDinhKem({ maDe, fileGoc, laBanChinh, onTaiLen, onXoa }: {
  maDe: string; fileGoc: { exists: boolean; file_name?: string; size_bytes?: number };
  laBanChinh: boolean; onTaiLen: () => void; onXoa: () => void;
}) {
  const [mo, setMo] = useState(laBanChinh);
  return (
    <div className="card mt-4 p-4">
      <button onClick={() => setMo(!mo)} className="flex w-full items-center gap-2 text-left text-sm font-bold text-slate-600">
        <FileUp size={15} className="text-slate-400" /> {laBanChinh ? "File đề bài gốc" : "Tài liệu đính kèm"}
        <span className="font-normal text-slate-400">{fileGoc.exists ? "(1)" : "(chưa có)"}</span>
        <div className="flex-1" />
        <span className="text-xs font-normal text-slate-400">{mo ? "Thu gọn" : "Mở"}</span>
      </button>
      {mo && (
        <div className="mt-3 border-t border-slate-100 pt-3">
          <p className="mb-3 text-xs leading-relaxed text-slate-500">
            {laBanChinh ? (
              <>File này <b>đang là đề bài</b> của đề. Nội dung bên trên đọc thẳng từ nó, giữ
              nguyên bảng, đánh số và căn lề. Sửa trong Word rồi tải đè thì bản mới thay ngay.
              Còn bấm “Sửa đề bài” và lưu là đề chuyển sang <b>soạn trong hệ thống</b>, file này
              lùi về tài liệu đính kèm.</>
            ) : (
              <>File tham khảo, bản nguồn Word/PDF. <b>Không phải đề bài</b> — đề bài của đề này
              nằm trong hệ thống. Sinh viên không nhận file ở đây.</>
            )}
          </p>
          {fileGoc.exists ? (
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm text-slate-600">{fileGoc.file_name}</span>
              <div className="flex-1" />
              <a href={`${API_BASE}/exam-setup/${encodeURIComponent(maDe)}/handout/original`} className={ghostBtn}>
                <Download size={14} /> Tải về
              </a>
              <button onClick={onTaiLen} className={ghostBtn}><RefreshCw size={14} /> Thay</button>
              <button onClick={onXoa} className="rounded-lg px-3 py-2 text-sm font-semibold text-rose-600 hover:bg-rose-50">
                <Trash2 size={14} />
              </button>
            </div>
          ) : (
            <button onClick={onTaiLen} className={ghostBtn}><FileUp size={14} /> Đính kèm file</button>
          )}
        </div>
      )}
    </div>
  );
}

/** Form soạn đề bằng AI — chỉ hiện với đề mới, chưa có nội dung nào. */
function FormAiSoanDe({ maDe, onXong, onLoi }: {
  maDe: string; onXong: (md: string, hinh: Mockup[]) => void; onLoi: (s: string) => void;
}) {
  const [req, setReq] = useState({
    topic: "", knowledge: "", screens: "", features: "", entity: "",
    storage: "SQLite", difficulty: "Trung bình", duration: "90 phút", note: "",
  });
  const [busy, setBusy] = useState(false);
  const dat = (k: keyof typeof req) => (e: { target: { value: string } }) => setReq({ ...req, [k]: e.target.value });

  const soan = async () => {
    if (!req.topic.trim()) { onLoi("Hãy nhập chủ đề / bài toán của đề."); return; }
    setBusy(true);
    try {
      const d = await gui("/ai/exam/draft", req) as { de_bai?: string };
      const md = String(d.de_bai || "");
      // Vẽ hình ngay bằng văn bản VỪA nhận, không đọc state (chưa kịp cập nhật trong lượt này).
      let hinh: Mockup[] = [];
      try {
        const h = await gui("/ai/exam/mockup", { de_bai: md }) as { mockups?: Mockup[] };
        hinh = Array.isArray(h.mockups) ? h.mockups : [];
      } catch { /* không vẽ được hình thì vẫn có đề, vẽ lại sau được */ }
      onXong(md, hinh);
    } catch (e) {
      onLoi((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card p-6">
      <p className="mb-4 flex items-center gap-2 text-sm font-bold text-slate-700">
        <Sparkles size={16} className="text-indigo-500" /> Nhờ AI soạn đề {maDe}
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <Field label="Chủ đề / bài toán *">
            <input value={req.topic} onChange={dat("topic")} placeholder="Quản lý chi tiêu cá nhân" className={inputClass} />
          </Field>
        </div>
        <Field label="Kiến thức cần kiểm tra">
          <input value={req.knowledge} onChange={dat("knowledge")} placeholder="ListView, Form, SQLite" className={inputClass} />
        </Field>
        <Field label="Các màn hình">
          <input value={req.screens} onChange={dat("screens")} placeholder="Danh sách, Thêm/Sửa" className={inputClass} />
        </Field>
        <Field label="Chức năng bắt buộc">
          <input value={req.features} onChange={dat("features")} placeholder="Thêm, Sửa, Xoá, Lọc" className={inputClass} />
        </Field>
        <Field label="Thực thể / dữ liệu">
          <input value={req.entity} onChange={dat("entity")} placeholder="Khoản chi: tiêu đề, số tiền, ngày" className={inputClass} />
        </Field>
        <Field label="Lưu trữ dữ liệu">
          <input value={req.storage} onChange={dat("storage")} className={inputClass} />
        </Field>
        <Field label="Độ khó">
          <input value={req.difficulty} onChange={dat("difficulty")} className={inputClass} />
        </Field>
        <Field label="Thời lượng">
          <input value={req.duration} onChange={dat("duration")} className={inputClass} />
        </Field>
        <Field label="Yêu cầu thêm">
          <input value={req.note} onChange={dat("note")} className={inputClass} />
        </Field>
      </div>
      <button onClick={soan} disabled={busy} className={`${primaryBtn} mt-5`}>
        {busy ? <Loader2 size={16} className="animate-spin" /> : <Sparkles size={16} />}
        {busy ? "Đang soạn đề và vẽ hình…" : "Sinh đề bài"}
      </button>
    </div>
  );
}
