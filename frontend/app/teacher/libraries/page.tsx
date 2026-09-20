"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import Banner from "@/components/ui/Banner";
import { API_BASE } from "@/lib/config";
import { laNguoiCham } from "@/lib/vai";
import {
  Package, Plus, Trash2, Loader2, Hammer, AlertTriangle, CheckCircle2,
  Info, RotateCcw, Lock,
} from "lucide-react";

interface Pkg { name: string; version: string; protected: boolean; }
interface SuggestedPackage { name: string; version: string }
interface BuildState {
  status: "IDLE" | "RESOLVING" | "BUILDING" | "READY" | "FAILED";
  message: string; log: string; at: number; building: boolean;
}
/** Một package mà bộ chấm đã nhận đòi hỏi nhưng ảnh chấm trên máy này chưa có. */
interface GoiThieu { ten: string; cac_de: string[]; version?: string }

async function apiJson(path: string, method: string, body?: unknown) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Có lỗi xảy ra");
  return data;
}

const nameOk = (s: string) => /^[a-z][a-z0-9_]*$/.test(s);
const versionOk = (s: string) => /^[0-9A-Za-z.^<>=+* _-]*$/.test(s);

/**
 * Bóc cặp nháy bao ngoài. Backend LUÔN quote ràng buộc khi ghi YAML — bắt đầu bằng {@code >}
 * là cú pháp khối của YAML nên không quote là file hỏng — rồi trả về nguyên cả nháy. Ô nhập thì
 * chỉ nhận ký tự của constraint, nên không bóc là `versionOk` đánh trượt chính giá trị đang dùng.
 */
const boNhay = (s: string) => {
  const t = (s || "").trim();
  return t.length >= 2 && ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith('"') && t.endsWith('"')))
    ? t.slice(1, -1).trim()
    : t;
};

export default function LibrariesPage() {
  const [protectedPkgs, setProtectedPkgs] = useState<Pkg[]>([]);
  const [editable, setEditable] = useState<Pkg[]>([]);   // danh sách sửa được (cục bộ)
  const [original, setOriginal] = useState<string>("");  // snapshot để biết "đã đổi"
  const [build, setBuild] = useState<BuildState | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [newName, setNewName] = useState("");
  const [newVer, setNewVer] = useState("");
  const [suggestedPackages, setSuggestedPackages] = useState<SuggestedPackage[]>([]);
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    let requested: SuggestedPackage[] = [];
    try {
      const decoded = JSON.parse(params.get("package_specs") || "[]") as unknown;
      if (Array.isArray(decoded)) {
        requested = decoded.flatMap((item) => {
          if (!item || typeof item !== "object" || Array.isArray(item)) return [];
          const spec = item as Record<string, unknown>;
          const name = typeof spec.name === "string" ? spec.name.trim().toLowerCase() : "";
          const version = typeof spec.version === "string" ? spec.version.trim() : "";
          return nameOk(name) ? [{ name, version: versionOk(version) ? version : "" }] : [];
        });
      }
    } catch { /* URL cũ hoặc bị sửa tay: dùng danh sách tên phía dưới. */ }
    if (!requested.length) {
      requested = (params.get("packages") || "").split(",")
        .map((name) => name.trim().toLowerCase()).filter(nameOk)
        .map((name) => ({ name, version: "" }));
    }
    const unique = [...new Map(requested.map((item) => [item.name, item])).values()];
    setSuggestedPackages(unique);
    if (unique.length) { setNewName(unique[0].name); setNewVer(unique[0].version); }
  }, []);

  // ── Bên người chấm: thư viện là để XEM ───────────────────────────────────────
  // Danh sách package là quyết định của người ra đề, người chấm không có cơ sở để sửa. Nhưng
  // ảnh chấm thì bắt buộc phải có trên máy họ, nên khi bộ chấm vừa nhận đòi một package chưa
  // có, đúng tên đó — và chỉ tên đó — được mở ra cho thêm.
  const [thieu, setThieu] = useState<GoiThieu[]>([]);
  const [docDuocAnh, setDocDuocAnh] = useState(true);
  const [daMo, setDaMo] = useState<string[]>([]);
  const chiXem = laNguoiCham;   // khong con ban thay ca hai vai nen khong phai tru bi
  const moDuoc = (ten: string) => !chiXem || daMo.includes(ten);

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const stopPoll = () => { if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; } };

  const loadPackages = useCallback(async () => {
    try {
      const d = await fetch(`${API_BASE}/grading-env/packages`).then((r) => r.json());
      const all: Pkg[] = Array.isArray(d.packages) ? d.packages : [];
      setProtectedPkgs(all.filter((p) => p.protected));
      const edit = all.filter((p) => !p.protected);
      setEditable(edit);
      setOriginal(JSON.stringify(edit));
      // Chụp version đang có để còn trả lại nếu người dùng xoá rồi thêm lại. Bóc nháy vì YAML
      // lưu ràng buộc trong nháy ('>=2.4.2+1 <2.4.3') mà ô nhập chỉ nhận ký tự của constraint.
      setPhienBanGoc(Object.fromEntries(edit.map((p) => [p.name, boNhay(p.version)])));
      setBuild(d.build || null);
    } catch {
      setErr("Không tải được danh sách thư viện");
    } finally {
      setLoading(false);
    }
  }, []);

  // Nạp phần thiếu của mọi bộ chấm đang có trên máy. Bản giảng viên cũng gọi, để người ra đề
  // thấy được máy mình có khớp với đề mình vừa làm không.
  const loadThieu = useCallback(async () => {
    try {
      const d = await fetch(`${API_BASE}/exam-setup/goi-con-thieu`).then((r) => r.json());
      setThieu(Array.isArray(d.thieu) ? d.thieu : []);
      setDangDung(d.dang_dung && typeof d.dang_dung === "object" ? d.dang_dung : {});
      setDocDuocAnh(d.doc_duoc_anh !== false);
    } catch {
      setThieu([]);
      setDangDung({});
    }
  }, []);

  const startPoll = useCallback(() => {
    if (pollRef.current) return;
    pollRef.current = setInterval(async () => {
      const s: BuildState | null = await fetch(`${API_BASE}/grading-env/build-status`)
        .then((r) => r.json()).catch(() => null);
      if (!s) return;
      setBuild(s);
      if (!s.building) {
        stopPoll();
        // Dựng lại ảnh xong thì danh sách thiếu phải tính lại — nếu không người dùng vừa thêm
        // đúng thứ đang thiếu mà cảnh báo vẫn còn nguyên, tưởng là không ăn thua.
        if (s.status === "READY") { loadPackages(); loadThieu(); }
      }
    }, 4000);
  }, [loadPackages, loadThieu]);

  useEffect(() => {
    loadPackages();
    loadThieu();
    return stopPoll;
  }, [loadPackages, loadThieu]);

  // Nếu mở trang lúc đang build dở → poll tiếp
  useEffect(() => { if (build?.building) startPoll(); }, [build?.building, startPoll]);

  const dirty = JSON.stringify(editable) !== original;
  const busy = !!build?.building;

  const addPkg = () => {
    const name = newName.trim().toLowerCase();
    if (!name) return;
    if (/[,;\s]/.test(name)) { setErr("Mỗi lần chỉ thêm một package. Nhập một tên, ví dụ: intl."); return; }
    if (!nameOk(name)) { setErr(`Tên package không hợp lệ: "${name}" (chỉ a-z, 0-9, _).`); return; }
    // VERSION BẮT BUỘC: để trống là pub tự lấy bản mới nhất, mà ảnh chấm là nơi bài sinh viên
    // chạy — lệch bản với Golden thì giao diện xê dịch rồi trượt hàng loạt tiêu chí vị trí.
    if (!newVer.trim()) { setErr("Phải ghi ràng buộc phiên bản, ví dụ ^5.7.0. Để trống là pub tự lấy bản mới nhất, lệch với Golden mà không ai biết."); return; }
    if (!versionOk(newVer.trim())) { setErr("Version package không hợp lệ. Ví dụ hợp lệ: ^5.7.0 hoặc >=2.1.0 <3.0.0."); return; }
    if (name === "flutter" || name === "flutter_test") { setErr("Đây là thư viện lõi, đã có sẵn."); return; }
    if (editable.some((p) => p.name === name) || protectedPkgs.some((p) => p.name === name)) { setErr("Package này đã có trong danh sách."); return; }
    setErr(null);
    setEditable((list) => [...list, { name, version: newVer.trim(), protected: false }]);
    // Phím tắt Golden có thể báo nhiều gói; từng lần bấm chỉ thêm đúng gói đang nhập.
    const remaining = suggestedPackages.filter((suggested) => suggested.name !== name);
    setSuggestedPackages(remaining);
    setNewName(remaining[0]?.name || ""); setNewVer(remaining[0]?.version || "");
  };

  /**
   * XÓA PACKAGE — hai cửa, giống cách xóa bộ chấm.
   *
   * <p>Cửa một chỉ hiện khi gói đang có bộ chấm đòi: nói rõ bộ nào rồi mới cho đi tiếp. Cửa hai
   * bắt gõ lại tên gói. Xóa xong chỉ GHI VÀO BẢNG, ảnh chấm chưa đổi — bấm "Áp dụng" mới dựng
   * lại, nên xóa vài gói một lượt vẫn chỉ tốn một lần dựng và còn đường Hoàn tác.
   */
  const [dinhXoaGoi, setDinhXoaGoi] = useState<string | null>(null);
  const [daQuaCanhBao, setDaQuaCanhBao] = useState(false);
  const [goTenGoi, setGoTenGoi] = useState("");

  const moXoaGoi = (name: string) => {
    setErr(null);
    setDinhXoaGoi(name);
    setGoTenGoi("");
    // Không bộ nào dùng thì bỏ qua cửa cảnh báo, vào thẳng bước gõ tên.
    setDaQuaCanhBao((dangDung[name] || []).length === 0);
  };
  const dongXoaGoi = () => { setDinhXoaGoi(null); setGoTenGoi(""); setDaQuaCanhBao(false); };
  const xacNhanXoaGoi = () => {
    if (!dinhXoaGoi || goTenGoi.trim() !== dinhXoaGoi) return;
    setEditable((list) => list.filter((p) => p.name !== dinhXoaGoi));
    dongXoaGoi();
  };

  /** Gói nào đang có bộ chấm đòi — để cảnh báo trước khi xóa. */
  const [dangDung, setDangDung] = useState<Record<string, string[]>>({});

  /** Còn dòng nào bỏ trống version thì chưa cho áp dụng: đó là cửa sinh ra lệch bản. */
  const thieuVersion = editable.filter((p) => !p.version.trim()).map((p) => p.name);

  /**
   * Bấm vào một package đang cảnh báo = mở đúng nó ra để thêm, không mở cả bảng.
   *
   * <p>Mang theo RÀNG BUỘC PHIÊN BẢN của Golden. Thêm mà bỏ trống là để pub tự chọn bản mới
   * nhất — lệch bản Golden đã ghi hình thì giao diện xê dịch một chút cũng đủ trượt hàng loạt
   * tiêu chí vị trí, mà lúc đó không ai ngờ nguyên nhân nằm ở ô version bỏ trống hôm nay.
   */
  const moGoiThieu = (ten: string, version = "") => {
    setErr(null);
    setDaMo((ds) => (ds.includes(ten) ? ds : [...ds, ten]));
    // Ba nguồn ràng buộc, theo thứ tự đáng tin: hợp đồng của đề (đúng bản Golden đã ghi hình)
    // → bản ảnh chấm ĐANG có trước khi bị xoá → rỗng. Nguồn thứ hai cứu đúng ca hay gặp nhất:
    // lỡ tay xoá một gói rồi thêm lại, trước đây là mất trắng ràng buộc dù nó vừa còn đó.
    const rangBuoc = (versionOk(version) && version) || phienBanGoc[ten] || "";
    setEditable((list) =>
      list.some((p) => p.name === ten)
        ? list
        : [...list, { name: ten, version: rangBuoc, protected: false }]);
  };

  /**
   * Version mà ảnh chấm đang dùng, chụp lúc NẠP TRANG — trước mọi thao tác xoá trong phiên.
   * Giữ riêng chứ không đọc lại từ `editable`: xoá xong thì dòng đó không còn để mà hỏi.
   */
  const [phienBanGoc, setPhienBanGoc] = useState<Record<string, string>>({});

  /**
   * Mọi gói ĐANG ĐƯỢC PHÉP thêm, gộp hai nguồn: gói thiếu của các bộ chấm đã nhận
   * (`/goi-con-thieu`), và gói đi kèm đường dẫn khi một lần nạp gói vừa bị từ chối
   * (`?package_specs=`). Phải có nguồn thứ hai: gói bị từ chối thì KHÔNG có bản ghi đề nào,
   * nên nguồn thứ nhất rỗng — không gộp thì người chấm không còn cửa nào thêm gói để sửa.
   */
  const goiThemDuoc: GoiThieu[] = useMemo(() => {
    const gop = new Map<string, GoiThieu>();
    thieu.forEach((g) => gop.set(g.ten, g));
    suggestedPackages.forEach((s) => {
      const co = gop.get(s.name);
      if (co) { if (!co.version && s.version) gop.set(s.name, { ...co, version: s.version }); return; }
      gop.set(s.name, { ten: s.name, cac_de: [], version: s.version });
    });
    return [...gop.values()].sort((a, b) => a.ten.localeCompare(b.ten));
  }, [thieu, suggestedPackages]);

  // Sửa version trực tiếp; để trống = backend tự resolve lại version tương thích khi áp dụng.
  const editVer = (name: string, version: string) =>
    setEditable((list) => list.map((p) => (p.name === name ? { ...p, version } : p)));

  const resetEdits = () => { loadPackages(); setErr(null); };

  const apply = async () => {
    setErr(null);
    if (thieuVersion.length > 0) {
      setErr(`Còn ${thieuVersion.length} package chưa ghi version: ${thieuVersion.join(", ")}.`
        + " Để trống là pub tự lấy bản mới nhất, lệch với Golden mà không ai biết.");
      return;
    }
    try {
      const payload = { packages: editable.map((p) => ({ name: p.name, version: p.version.trim() })) };
      const data: BuildState = await apiJson("/grading-env/apply", "POST", payload);
      setBuild(data);
      startPoll();
    } catch (e) {
      setErr((e as Error).message);
    }
  };

  return (
    <SidebarLayout
      title="Thư viện chấm"
      activePath="/teacher/libraries"
    >
      {/* Trạng thái build */}
      {build && build.status !== "IDLE" && <BuildBanner build={build} />}

      {err && <Banner tone="error" onClose={() => setErr(null)}>{err}</Banner>}

      {/* Bộ chấm đã nhận đòi package mà ảnh chấm chưa có → bài sinh viên sẽ không biên dịch nổi.
          Nói ra ngay lúc này, thay vì để phát hiện giữa lúc đang chấm cả lớp. */}
      {goiThemDuoc.length > 0 && (
        <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-4">
          <p className="flex items-center gap-2 text-sm font-bold text-amber-800">
            <AlertTriangle size={16} /> Ảnh chấm còn thiếu {goiThemDuoc.length} thư viện mà bộ chấm đòi hỏi
          </p>
          <p className="mt-1 text-xs leading-relaxed text-amber-700">
            {chiXem
              ? "Bấm vào tên bên dưới để thêm đúng thư viện đó, rồi dựng lại ảnh chấm. Thiếu thì bài sinh viên không biên dịch được và cả lượt chấm hỏng."
              : "Bấm vào tên bên dưới để thêm vào ảnh chấm."}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            {goiThemDuoc.map((g) => {
              // CHỈ nhìn danh sách hiện tại, không nhìn "đã từng bấm". Trước đây điều kiện có
              // thêm `daMo.includes(g.ten)`, mà daMo thì chỉ thêm chứ không bớt: lỡ tay xoá gói
              // khỏi bảng là ô này tắt vĩnh viễn, không còn đường thêm lại (ca thật 19/9 — bên
              // người chấm không có ô nhập tay nên mất luôn cách cứu).
              const daThem = editable.some((p) => p.name === g.ten)
                || protectedPkgs.some((p) => p.name === g.ten);
              const nhan = g.cac_de.length > 0
                ? `Đề cần: ${g.cac_de.join(", ")}`
                : "Gói bàn giao vừa bị từ chối vì thiếu gói này";
              return (
                <button
                  key={g.ten}
                  onClick={() => moGoiThieu(g.ten, g.version || "")}
                  disabled={busy || daThem}
                  title={g.version ? `${nhan} · ràng buộc ${g.version}` : nhan}
                  className="flex items-center gap-1.5 rounded-lg border border-amber-300 bg-white px-2.5 py-1.5 font-mono text-xs font-semibold text-amber-800 transition-colors hover:border-amber-400 hover:bg-amber-100 disabled:opacity-50"
                >
                  {daThem ? <CheckCircle2 size={13} /> : <Plus size={13} />} {g.ten}
                  {g.version && <span className="font-normal opacity-70">{g.version}</span>}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {!docDuocAnh && (
        <div className="mb-4 rounded-xl border border-slate-200 bg-slate-50 p-3.5 text-xs text-slate-600">
          <p className="flex items-center gap-2 font-semibold text-slate-700">
            <Info size={15} /> Chưa đọc được ảnh chấm
          </p>
          <p className="mt-1 leading-relaxed">
            Docker chưa bật hoặc ảnh chưa dựng, nên chưa đối chiếu được thư viện của máy với thư
            viện mà bộ chấm đòi. Chưa biết thì chưa kết luận là thiếu.
          </p>
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={24} className="animate-spin" /></div>
      ) : (
        <div className="card overflow-hidden">
          <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-5 py-3.5">
            <Package size={16} className="text-indigo-500" />
            <h3 className="text-sm font-bold text-slate-700">Danh sách thư viện</h3>
          </div>

          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-[10px] uppercase tracking-wider text-slate-400">
                <th className="px-5 py-2.5">Package</th>
                <th className="px-5 py-2.5">Version</th>
                <th className="px-5 py-2.5 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {protectedPkgs.map((p) => (
                <tr key={p.name} className="text-slate-500">
                  <td className="px-5 py-2.5 font-mono">{p.name}</td>
                  <td className="px-5 py-2.5 font-mono text-xs">{p.version}</td>
                  <td className="px-5 py-2.5 text-right">
                    <span className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500">
                      <Lock size={10} /> lõi
                    </span>
                  </td>
                </tr>
              ))}
              {editable.map((p) => {
                const isNew = !original.includes(`"name":"${p.name}"`);
                return (
                  <tr key={p.name} className="hover:bg-slate-50/60">
                    <td className="px-5 py-2.5 font-medium text-slate-700">
                      <span className="font-mono">{p.name}</span>
                      {isNew && <span className="ml-2 rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700">mới</span>}
                    </td>
                    <td className="px-5 py-2.5">
                      <input
                        value={p.version}
                        disabled={busy || !moDuoc(p.name)}
                        onChange={(e) => editVer(p.name, e.target.value)}
                        placeholder="bắt buộc"
                        title={moDuoc(p.name)
                          ? "Bắt buộc — ví dụ ^5.7.0. Để trống là pub tự lấy bản mới nhất, lệch với Golden."
                          : "Bản người chấm chỉ sửa version của thư viện đang bị cảnh báo thiếu."}
                        className={`w-32 rounded-md border bg-white px-2 py-1 font-mono text-xs outline-none focus:ring-1 disabled:bg-slate-100 ${
                          p.version.trim()
                            ? "border-slate-200 text-slate-600 focus:border-indigo-400 focus:ring-indigo-100"
                            : "border-rose-300 text-rose-700 focus:border-rose-400 focus:ring-rose-100"
                        }`}
                      />
                    </td>
                    <td className="px-5 py-2.5 text-right">
                      {/* Xóa được ở CẢ HAI vai (19/9). Bên người chấm không có ô thêm tay, nên
                          xóa nhầm một gói không bộ nào đòi là cửa một chiều — bù lại bằng cảnh
                          báo "bộ nào đang dùng" ở bước một và nút Bổ sung package bên màn bộ. */}
                      <button onClick={() => moXoaGoi(p.name)} disabled={busy}
                        className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-rose-50 hover:text-rose-600 disabled:opacity-40">
                        <Trash2 size={14} />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          {/* Thêm package — bản người chấm không có ô này. Họ chỉ thêm được qua chỗ cảnh báo
              thiếu ở trên, tức là chỉ thêm đúng thứ bộ chấm đang đòi. */}
          {!chiXem && (
          <div className="flex flex-wrap items-end gap-2 border-t border-slate-100 bg-slate-50/40 px-5 py-3.5">
            {suggestedPackages.length > 0 && <div className="flex w-full flex-wrap items-center gap-2 text-xs text-slate-500">
              <span>Gói Golden còn cần thêm — chọn từng gói:</span>
              {suggestedPackages.map((item) => <button key={item.name} type="button" disabled={busy} onClick={() => { setNewName(item.name); setNewVer(item.version); setErr(null); }} className="rounded border border-slate-200 bg-white px-2 py-1 font-mono hover:text-indigo-600 disabled:opacity-50">{item.name}</button>)}
            </div>}
            <label className="flex-1 min-w-[160px]">
              <span className="mb-1 block text-[11px] font-semibold text-slate-500">Tên package</span>
              <input value={newName} disabled={busy}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") addPkg(); }}
                placeholder="vd: intl"
                className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 disabled:bg-slate-100" />
            </label>
            <label className="w-36">
              <span className="mb-1 block text-[11px] font-semibold text-slate-500">Version (tùy chọn)</span>
              <input value={newVer} disabled={busy}
                onChange={(e) => setNewVer(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") addPkg(); }}
                placeholder="^1.0.0"
                className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 disabled:bg-slate-100" />
            </label>
            <button onClick={addPkg} disabled={busy || !newName.trim()}
              className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-semibold text-slate-600 transition-colors hover:text-indigo-600 disabled:opacity-50">
              <Plus size={15} /> Thêm
            </button>
          </div>
          )}

          {/* Áp dụng */}
          <div className="flex items-center justify-between border-t border-slate-100 px-5 py-3.5">
            <div className="text-xs text-slate-500">
              {dirty ? "Có thay đổi chưa áp dụng."
                : thieuVersion.length > 0 ? `Thiếu version: ${thieuVersion.join(", ")}.`
                : "Chưa có thay đổi."}
            </div>
            <div className="flex items-center gap-2">
              {dirty && !busy && (
                <button onClick={resetEdits}
                  className="flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">
                  <RotateCcw size={14} /> Hoàn tác
                </button>
              )}
              <button onClick={apply} disabled={!dirty || busy || thieuVersion.length > 0}
                title={thieuVersion.length > 0 ? `Điền version cho: ${thieuVersion.join(", ")}` : undefined}
                className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-all hover:bg-indigo-700 active:scale-95 disabled:opacity-50">
                {busy ? <Loader2 size={15} className="animate-spin" /> : <Hammer size={15} />} Áp dụng &amp; cập nhật
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Portal ra body: SidebarLayout có transform nên nó thành khối chứa của MỌI con
          `position: fixed` bên trong — nền mờ hụt thanh bên và hộp lệch xuống giữa vùng cuộn. */}
      {dinhXoaGoi && createPortal((
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl">
            {!daQuaCanhBao ? (
              <>
                {/* CỬA MỘT: gói đang được bộ chấm đòi. Nói rõ bộ nào trước khi cho đi tiếp. */}
                <p className="flex items-center gap-2 text-sm font-bold text-amber-700">
                  <AlertTriangle size={16} /> Bộ chấm đang sử dụng package này
                </p>
                <p className="mt-2 text-sm text-slate-600">
                  Xóa <span className="font-mono font-bold">{dinhXoaGoi}</span> khỏi ảnh chấm thì
                  các bộ sau không chấm được nữa:
                </p>
                <ul className="mt-2 list-inside list-disc font-mono text-sm text-slate-700">
                  {(dangDung[dinhXoaGoi] || []).map((de) => <li key={de}>{de}</li>)}
                </ul>
                <div className="mt-5 flex justify-end gap-2">
                  <button onClick={dongXoaGoi}
                    className="rounded-lg px-4 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">
                    Hủy
                  </button>
                  <button onClick={() => setDaQuaCanhBao(true)}
                    className="rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700">
                    Tiếp tục xóa
                  </button>
                </div>
              </>
            ) : (
              <>
                {/* CỬA HAI: gõ lại tên. Cùng cách với xóa bộ chấm — một cú bấm nhầm ở đây làm
                    hỏng ảnh dùng chung, và bên người chấm không có ô thêm tay để dựng lại. */}
                <p className="text-sm font-bold text-slate-800">Gõ lại tên package để xác nhận xóa</p>
                <p className="mt-1 text-xs text-slate-500">
                  Gõ đúng <span className="font-mono font-bold text-slate-700">{dinhXoaGoi}</span>.
                  Xóa xong mới chỉ ghi vào bảng — bấm “Áp dụng &amp; cập nhật” mới dựng lại ảnh chấm.
                </p>
                <input
                  autoFocus
                  value={goTenGoi}
                  onChange={(e) => setGoTenGoi(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") xacNhanXoaGoi(); }}
                  placeholder={dinhXoaGoi}
                  className="mt-3 w-full rounded-lg border border-slate-200 px-3 py-2 font-mono text-sm outline-none focus:border-rose-400 focus:ring-2 focus:ring-rose-100"
                />
                <div className="mt-5 flex justify-end gap-2">
                  <button onClick={dongXoaGoi}
                    className="rounded-lg px-4 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">
                    Hủy
                  </button>
                  <button onClick={xacNhanXoaGoi} disabled={goTenGoi.trim() !== dinhXoaGoi}
                    className="rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700 disabled:opacity-40">
                    Xóa khỏi danh sách
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      ), document.body)}
    </SidebarLayout>
  );
}

function BuildBanner({ build }: { build: BuildState }) {
  const map = {
    RESOLVING: { cls: "border-blue-100 bg-blue-50 text-blue-700", icon: <Loader2 size={15} className="animate-spin" /> },
    BUILDING:  { cls: "border-blue-100 bg-blue-50 text-blue-700", icon: <Loader2 size={15} className="animate-spin" /> },
    READY:     { cls: "border-emerald-100 bg-emerald-50 text-emerald-700", icon: <CheckCircle2 size={15} /> },
    FAILED:    { cls: "border-rose-100 bg-rose-50 text-rose-600", icon: <AlertTriangle size={15} /> },
    IDLE:      { cls: "border-slate-100 bg-slate-50 text-slate-500", icon: <Info size={15} /> },
  }[build.status];
  return (
    <div className={`mb-4 rounded-xl border p-3.5 ${map.cls}`}>
      <p className="flex items-center gap-2 text-sm font-semibold">{map.icon} {build.message || build.status}</p>
      {build.log && (build.status === "BUILDING" || build.status === "FAILED") && (
        <pre className="custom-scrollbar mt-2 max-h-40 overflow-auto whitespace-pre-wrap rounded-lg bg-white/60 p-2 text-[10px] leading-relaxed text-slate-600">{build.log}</pre>
      )}
    </div>
  );
}
