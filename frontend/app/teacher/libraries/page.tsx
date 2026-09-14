"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import Banner from "@/components/ui/Banner";
import { API_BASE } from "@/lib/config";
import { laNguoiCham } from "@/lib/vai";
import {
  Package, Plus, Trash2, Loader2, Hammer, AlertTriangle, CheckCircle2,
  Info, RotateCcw, Lock,
} from "lucide-react";

interface Pkg { name: string; version: string; protected: boolean; }
interface BuildState {
  status: "IDLE" | "RESOLVING" | "BUILDING" | "READY" | "FAILED";
  message: string; log: string; at: number; building: boolean;
}
/** Một package mà bộ chấm đã nhận đòi hỏi nhưng ảnh chấm trên máy này chưa có. */
interface GoiThieu { ten: string; cac_de: string[] }

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

export default function LibrariesPage() {
  const [protectedPkgs, setProtectedPkgs] = useState<Pkg[]>([]);
  const [editable, setEditable] = useState<Pkg[]>([]);   // danh sách sửa được (cục bộ)
  const [original, setOriginal] = useState<string>("");  // snapshot để biết "đã đổi"
  const [build, setBuild] = useState<BuildState | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [newName, setNewName] = useState("");
  const [newVer, setNewVer] = useState("");

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
      setDocDuocAnh(d.doc_duoc_anh !== false);
    } catch {
      setThieu([]);
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
    if (!nameOk(name)) { setErr(`Tên package không hợp lệ: "${name}" (chỉ a-z, 0-9, _).`); return; }
    if (name === "flutter" || name === "flutter_test") { setErr("Đây là thư viện lõi, đã có sẵn."); return; }
    if (editable.some((p) => p.name === name) || protectedPkgs.some((p) => p.name === name)) {
      setErr(`"${name}" đã có trong danh sách.`); return;
    }
    setErr(null);
    setEditable((list) => [...list, { name, version: newVer.trim(), protected: false }]);
    setNewName(""); setNewVer("");
  };

  const removePkg = (name: string) => setEditable((list) => list.filter((p) => p.name !== name));

  /** Bấm vào một package đang cảnh báo = mở đúng nó ra để thêm, không mở cả bảng. */
  const moGoiThieu = (ten: string) => {
    setErr(null);
    setDaMo((ds) => (ds.includes(ten) ? ds : [...ds, ten]));
    setEditable((list) =>
      list.some((p) => p.name === ten) ? list : [...list, { name: ten, version: "", protected: false }]);
  };

  // Sửa version trực tiếp; để trống = backend tự resolve lại version tương thích khi áp dụng.
  const editVer = (name: string, version: string) =>
    setEditable((list) => list.map((p) => (p.name === name ? { ...p, version } : p)));

  const resetEdits = () => { loadPackages(); setErr(null); };

  const apply = async () => {
    setErr(null);
    try {
      const payload = { packages: editable.map((p) => ({ name: p.name, version: p.version || "" })) };
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
      {thieu.length > 0 && (
        <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-4">
          <p className="flex items-center gap-2 text-sm font-bold text-amber-800">
            <AlertTriangle size={16} /> Ảnh chấm còn thiếu {thieu.length} thư viện mà bộ chấm đòi hỏi
          </p>
          <p className="mt-1 text-xs leading-relaxed text-amber-700">
            {chiXem
              ? "Bấm vào tên bên dưới để thêm đúng thư viện đó, rồi dựng lại ảnh chấm. Thiếu thì bài sinh viên không biên dịch được và cả lượt chấm hỏng."
              : "Bấm vào tên bên dưới để thêm vào ảnh chấm."}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            {thieu.map((g) => {
              const daThem = daMo.includes(g.ten) || editable.some((p) => p.name === g.ten);
              return (
                <button
                  key={g.ten}
                  onClick={() => moGoiThieu(g.ten)}
                  disabled={busy || daThem}
                  title={`Đề cần: ${g.cac_de.join(", ")}`}
                  className="flex items-center gap-1.5 rounded-lg border border-amber-300 bg-white px-2.5 py-1.5 font-mono text-xs font-semibold text-amber-800 transition-colors hover:border-amber-400 hover:bg-amber-100 disabled:opacity-50"
                >
                  {daThem ? <CheckCircle2 size={13} /> : <Plus size={13} />} {g.ten}
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
                        placeholder="tự chọn"
                        title={moDuoc(p.name)
                          ? "Để trống = tự chọn version tương thích"
                          : "Bản người chấm chỉ xem. Sửa được khi thư viện này đang bị cảnh báo thiếu."}
                        className="w-32 rounded-md border border-slate-200 bg-white px-2 py-1 font-mono text-xs text-slate-600 outline-none focus:border-indigo-400 focus:ring-1 focus:ring-indigo-100 disabled:bg-slate-100"
                      />
                    </td>
                    <td className="px-5 py-2.5 text-right">
                      {moDuoc(p.name) ? (
                        <button onClick={() => removePkg(p.name)} disabled={busy}
                          className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-rose-50 hover:text-rose-600 disabled:opacity-40">
                          <Trash2 size={14} />
                        </button>
                      ) : (
                        <span className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500">
                          <Lock size={10} /> chỉ xem
                        </span>
                      )}
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
            <label className="flex-1 min-w-[160px]">
              <span className="mb-1 block text-[11px] font-semibold text-slate-500">Tên package</span>
              <input value={newName} disabled={busy}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") addPkg(); }}
                placeholder="vd: intl, collection, http"
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
                : chiXem ? "Bản người chấm — danh sách này chỉ để xem."
                : "Chưa có thay đổi."}
            </div>
            <div className="flex items-center gap-2">
              {dirty && !busy && (
                <button onClick={resetEdits}
                  className="flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">
                  <RotateCcw size={14} /> Hoàn tác
                </button>
              )}
              <button onClick={apply} disabled={!dirty || busy}
                className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-all hover:bg-indigo-700 active:scale-95 disabled:opacity-50">
                {busy ? <Loader2 size={15} className="animate-spin" /> : <Hammer size={15} />} Áp dụng &amp; cập nhật
              </button>
            </div>
          </div>
        </div>
      )}
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
