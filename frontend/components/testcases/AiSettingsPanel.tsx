"use client";

// Cấu hình model AI + API key — dùng ở trang "Tạo đề" (tách ra từ AiAuthorPanel.tsx cũ, ban đầu
// dùng chung với "Tạo Golden" — trang đó đã xoá 17/9/2026). Tự chứa hoàn toàn: không cần props,
// không cần cha biết đang dùng model gì — mọi endpoint AI phía sau tự đọc cấu hình đã lưu.

import { useCallback, useEffect, useMemo, useState } from "react";
import { API_BASE } from "@/lib/config";
import {
  Settings2, KeyRound, Save, Info, ChevronDown, Loader2, AlertTriangle,
} from "lucide-react";

interface AiModel { id: string; label: string; provider: string; vendor: string }
export interface AiSettings {
  model: string; provider: string; vendor: string; keyUrl: string | null;
  hasApiKey: boolean; apiKeyMasked: string | null; keyWarning: string | null;
  apiKeyLength: number;
  baseUrl: string; customBaseUrl: boolean;
  timeoutSeconds: number; models: AiModel[]; ready: boolean;
}

export default function AiSettingsPanel() {
  const [open, setOpen] = useState(false);
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [modelDraft, setModelDraft] = useState("");
  const [baseUrlDraft, setBaseUrlDraft] = useState("");
  const [customModel, setCustomModel] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const loadSettings = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/ai/settings`);
      const data = (await res.json()) as AiSettings;
      if (!res.ok) throw new Error("Không đọc được cấu hình AI");
      setSettings(data);
      setModelDraft(data.model || "");
      setBaseUrlDraft(data.customBaseUrl ? data.baseUrl || "" : "");
      setCustomModel(!(data.models || []).some((m) => m.id === data.model));
    } catch {
      setSettings(null);
    }
  }, []);

  useEffect(() => { if (open && !settings) loadSettings(); }, [open, settings, loadSettings]);

  const persistSettings = async () => {
    const body: Record<string, unknown> = { model: modelDraft.trim(), baseUrl: baseUrlDraft.trim() };
    if (apiKeyDraft.trim()) body.apiKey = apiKeyDraft.trim();
    const res = await fetch(`${API_BASE}/ai/settings`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    const data = (await res.json()) as AiSettings;
    if (!res.ok) throw new Error("Không lưu được cấu hình.");
    setSettings(data);
    setApiKeyDraft("");
    setModelDraft(data.model);
    setBaseUrlDraft(data.customBaseUrl ? data.baseUrl || "" : "");
    return data;
  };

  const testAndSaveSettings = async () => {
    if (!apiKeyDraft.trim() && !settings?.hasApiKey) {
      setError(`Chưa có API key cho ${draftVendor}. Hãy dán key rồi thử lại.`);
      return;
    }
    setBusy(true); setError(null); setInfo(null);
    try {
      const probeRes = await fetch(`${API_BASE}/ai/settings/test`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ model: modelDraft.trim(), apiKey: apiKeyDraft.trim(), baseUrl: baseUrlDraft.trim() }),
      });
      const probe = await probeRes.json();
      if (!probe.ok) { setError(probe.message || "Không kết nối được — chưa lưu gì cả."); return; }
      const data = await persistSettings();
      setInfo(`Kết nối thành công (${probe.elapsedMs} ms) và đã lưu. Đang dùng ${data.model} (${data.vendor}).`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không lưu được cấu hình.");
    } finally {
      setBusy(false);
    }
  };

  const modelsByVendor = useMemo(() => {
    const out: Record<string, AiModel[]> = {};
    (settings?.models || []).forEach((m) => { (out[m.vendor] ||= []).push(m); });
    return out;
  }, [settings]);

  const draftVendor = useMemo(() => {
    if (baseUrlDraft.trim()) return "Dịch vụ trung gian (giao thức OpenAI)";
    const id = modelDraft.trim().toLowerCase();
    if (!id) return "";
    if (id.startsWith("claude")) return "Claude (Anthropic)";
    if (id.startsWith("gemini")) return "Gemini (Google)";
    return "GPT (OpenAI)";
  }, [modelDraft, baseUrlDraft]);

  const vendorChanged = !!settings && !!draftVendor && draftVendor !== settings.vendor;

  return (
    <div className="rounded-2xl border border-slate-200">
      <button onClick={() => setOpen((v) => !v)} className="flex w-full items-center gap-2 px-4 py-3 text-left">
        <Settings2 size={15} className="text-slate-500" />
        <span className="text-sm font-bold text-slate-700">Chọn model &amp; API key</span>
        {settings && (
          <span className={`ml-2 rounded-full px-2.5 py-1 text-[11px] font-bold ${
            settings.ready ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}`}>
            {settings.ready ? settings.model : "Chưa có API key"}
          </span>
        )}
        {settings?.hasApiKey && (
          <span className="ml-2 font-mono text-[11px] text-slate-400">{settings.apiKeyMasked}</span>
        )}
        <ChevronDown size={16} className={`ml-auto text-slate-400 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <div className="space-y-3 border-t border-slate-100 p-4">
          {error && (
            <p className="flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 p-2.5 text-[11px] leading-relaxed text-rose-700">
              <AlertTriangle size={13} className="mt-0.5 shrink-0" /> {error}
            </p>
          )}
          {info && (
            <p className="flex items-start gap-2 rounded-xl border border-emerald-200 bg-emerald-50 p-2.5 text-[11px] leading-relaxed text-emerald-700">
              <Info size={13} className="mt-0.5 shrink-0" /> {info}
            </p>
          )}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Model AI">
              <select
                value={customModel ? "__custom__" : modelDraft}
                onChange={(e) => {
                  if (e.target.value === "__custom__") { setCustomModel(true); return; }
                  setCustomModel(false);
                  setModelDraft(e.target.value);
                }}
                className={inputClass}
              >
                {Object.entries(modelsByVendor).map(([vendor, list]) => (
                  <optgroup key={vendor} label={vendor}>
                    {list.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
                  </optgroup>
                ))}
                <option value="__custom__">Tự nhập mã model khác…</option>
              </select>
            </Field>
            <Field label="API key" hint={vendorChanged ? `Đổi sang ${draftVendor} thì phải nhập key mới của hãng đó` : undefined}>
              <div className="flex items-center gap-2">
                <KeyRound size={14} className="shrink-0 text-slate-400" />
                <input
                  type="text"
                  name="grader-ai-key"
                  value={apiKeyDraft}
                  onChange={(e) => setApiKeyDraft(e.target.value.replace(/[^\x21-\x7E]/g, ""))}
                  placeholder={settings?.hasApiKey && !vendorChanged ? settings.apiKeyMasked || "••••" : "Dán API key vào đây"}
                  className={inputClass}
                  style={{ WebkitTextSecurity: "disc" } as React.CSSProperties}
                  autoComplete="off"
                  spellCheck={false}
                  data-lpignore="true"
                  data-1p-ignore
                  data-form-type="other"
                />
              </div>
            </Field>
            {customModel && (
              <div className="sm:col-span-2">
                <Field label="Mã model" hint="Bắt đầu bằng claude… / gpt… / gemini… để gọi đúng hãng">
                  <input value={modelDraft} onChange={(e) => setModelDraft(e.target.value)}
                    placeholder="VD: claude-sonnet-5" className={`${inputClass} font-mono`} />
                </Field>
              </div>
            )}
            <div className="sm:col-span-2">
              <Field label="Endpoint riêng (tùy chọn)">
                <input value={baseUrlDraft} onChange={(e) => setBaseUrlDraft(e.target.value)}
                  placeholder="https://api.dich-vu-cua-ban.com/v1" className={`${inputClass} font-mono`} />
              </Field>
            </div>
          </div>
          {settings?.keyWarning && (
            <p className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-2.5 text-[11px] leading-relaxed text-amber-800">
              <AlertTriangle size={13} className="mt-0.5 shrink-0" /> {settings.keyWarning}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <button onClick={testAndSaveSettings} disabled={busy || (!settings?.hasApiKey && !apiKeyDraft.trim())}
              title="Gọi thử một lượt; gọi được mới lưu — thử hỏng thì cấu hình cũ giữ nguyên"
              className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-3.5 py-2 text-xs font-semibold text-white shadow-sm transition-all hover:from-violet-700 hover:to-indigo-700 active:scale-[0.98] disabled:cursor-not-allowed disabled:from-slate-300 disabled:to-slate-300">
              {busy ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />}
              Kiểm tra &amp; lưu cấu hình
            </button>
            {settings?.apiKeyLength ? (
              <span className="text-[11px] text-slate-400">Key đang lưu: {settings.apiKeyMasked} · {settings.apiKeyLength} ký tự</span>
            ) : null}
            {settings?.keyUrl && (
              <a href={settings.keyUrl} target="_blank" rel="noreferrer"
                className="text-[11px] font-semibold text-indigo-600 underline-offset-2 hover:underline">
                Lấy API key của {settings.vendor}
              </a>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

const inputClass =
  "w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100";

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] font-bold uppercase tracking-wider text-slate-500">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[10px] text-slate-400">{hint}</span>}
    </label>
  );
}
