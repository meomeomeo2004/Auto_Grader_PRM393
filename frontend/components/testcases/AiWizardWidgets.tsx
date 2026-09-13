"use client";

// Mảnh UI dùng chung giữa trang "Tạo đề" và "Tạo Golden" (tách ra từ AiAuthorPanel.tsx cũ).

import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, AlertTriangle, FileCode2 } from "lucide-react";

export const inputClass =
  "w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100";
export const primaryBtn =
  "flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-3.5 py-2 text-xs font-semibold text-white shadow-sm transition-all hover:from-violet-700 hover:to-indigo-700 active:scale-[0.98] disabled:cursor-not-allowed disabled:from-slate-300 disabled:to-slate-300";
export const ghostBtn =
  "flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50";

export function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] font-bold uppercase tracking-wider text-slate-500">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[10px] text-slate-400">{hint}</span>}
    </label>
  );
}

/** Một bước của trợ lý — THU GỌN ĐƯỢC. Bước nào đã chốt (done) thì tự gập lại, bấm tiêu đề mở lại. */
export function Step({ index, icon: Icon, title, done, children }: {
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

export function SyntaxBanner({ syntax }: { syntax: { ok: boolean | null; message: string } }) {
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
export function CodeEditor({ files, openPath, onOpen, onEdit }: {
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
