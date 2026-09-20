"use client";

import React, { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ChevronDown, Check } from "lucide-react";

export interface SelectMenuOption {
  value: string;
  label: string;
  sublabel?: string;
  /** Chip vuông bên trái mỗi dòng (vd 2 ký tự đầu của mã đề) — bỏ trống thì không vẽ. */
  badge?: string;
  /**
   * Dòng vẫn HIỆN nhưng không chọn được. Dùng cho thứ người dùng cần THẤY là nó tồn tại và
   * vì sao chưa dùng được — lọc khỏi danh sách thì họ đi tìm "cái của tôi đâu rồi".
   */
  disabled?: boolean;
}

interface Props {
  options: SelectMenuOption[];
  value: string;
  onChange: (value: string) => void;
  /** Icon nhỏ nằm trong ô, phía trái — cùng chỗ với kính lúp của ExamCombobox. */
  icon?: React.ComponentType<{ size?: number; className?: string }>;
  placeholder?: string;
  emptyText?: string;
  ariaLabel?: string;
  disabled?: boolean;
}

/**
 * Dropdown CHỈ CHỌN — hình dáng bám đúng {@link ExamCombobox} (cùng chiều cao, bo góc, viền,
 * icon trái, mũi tên phải, danh sách nổi qua portal) nhưng KHÔNG có ô nhập.
 *
 * <p>Vì sao không dùng {@code <select>} thường: nó chỉ tuân theo CSS ở phần ô đóng, còn danh sách
 * xổ ra do hệ điều hành vẽ — nên đặt cạnh combobox là thấy lệch hẳn về phông, bo góc và màu chọn.
 * Ở đây danh sách là DOM của mình nên hai ô trông như một cặp.
 *
 * <p>Portal ra body + {@code position: fixed}: thẻ bọc ngoài thường là {@code card overflow-hidden},
 * danh sách absolute sẽ bị cắt ngay mép card.
 */
export default function SelectMenu({
  options, value, onChange, icon: Icon, placeholder = "— Chọn —",
  emptyText = "Không có lựa chọn nào", ariaLabel, disabled,
}: Props) {
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const [rect, setRect] = useState<{ top: number; left: number; width: number } | null>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const listId = useId();   // id ổn định qua SSR/CSR, không đụng ref lúc render

  const selectedIndex = options.findIndex((o) => o.value === value);
  const selected = selectedIndex >= 0 ? options[selectedIndex] : null;
  const hi = options.length ? Math.max(0, Math.min(highlight, options.length - 1)) : 0;

  // Đóng khi bấm ra ngoài — phải kiểm CẢ danh sách vì nó nằm ngoài wrapRef (portal).
  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node;
      if (wrapRef.current?.contains(t) || listRef.current?.contains(t)) return;
      setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, []);

  // Bám theo ô khi cuộn/đổi cỡ (scroll bắt ở pha capture vì vùng cuộn thật là <main>).
  useEffect(() => {
    if (!open) return;
    const update = () => {
      const r = wrapRef.current?.getBoundingClientRect();
      if (r) setRect({ top: r.bottom, left: r.left, width: r.width });
    };
    update();
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
    };
  }, [open]);

  // Mở ra thì sáng sẵn dòng đang chọn.
  useEffect(() => { if (open) setHighlight(selectedIndex >= 0 ? selectedIndex : 0); }, [open, selectedIndex]);

  // Cuộn TAY trong đúng khung danh sách — scrollIntoView sẽ kéo cả tổ tiên (card overflow-hidden).
  useEffect(() => {
    const list = listRef.current;
    if (!open || !list) return;
    const el = list.querySelector<HTMLElement>(`[data-idx="${hi}"]`);
    if (!el) return;
    const top = el.offsetTop;
    const bottom = top + el.offsetHeight;
    if (top < list.scrollTop) list.scrollTop = top;
    else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight;
  }, [hi, open]);

  const choose = (o: SelectMenuOption) => { onChange(o.value); setOpen(false); };

  /** Mũi tên NHẢY QUA dòng bị tắt; hết dòng chọn được thì đứng yên chứ không kẹt vào nó. */
  const timChonDuoc = (tu: number, buoc: 1 | -1) => {
    for (let i = tu; i >= 0 && i < options.length; i += buoc) {
      if (!options[i].disabled) return i;
    }
    return hi;
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setOpen(true); setHighlight(timChonDuoc(hi + 1, 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setOpen(true); setHighlight(timChonDuoc(hi - 1, -1)); }
    else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      // Bàn phím phải chặn y hệt chuột: tắt bằng CSS mà Enter vẫn chọn được là cửa hậu.
      if (open && options[hi] && !options[hi].disabled) choose(options[hi]); else setOpen(true);
    } else if (e.key === "Escape") { setOpen(false); }
  };

  return (
    <div ref={wrapRef} className="relative">
      {Icon && (
        <Icon size={15} className="pointer-events-none absolute left-3 top-1/2 z-10 -translate-y-1/2 text-slate-400" />
      )}
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        onKeyDown={onKeyDown}
        disabled={disabled}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        aria-label={ariaLabel}
        className={`w-full rounded-lg border border-slate-200 bg-white py-2 pr-9 text-left text-sm font-medium outline-none transition-colors focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 disabled:bg-slate-50 ${
          Icon ? "pl-9" : "pl-3"
        } ${selected ? "text-slate-800" : "text-slate-400"}`}
      >
        <span className="block truncate">{selected ? selected.label : placeholder}</span>
      </button>
      <ChevronDown
        size={16}
        className={`pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 transition-transform ${open ? "rotate-180" : ""}`}
      />

      {open && !disabled && rect && createPortal(
        <div
          id={listId} ref={listRef} role="listbox"
          style={{ top: rect.top + 6, left: rect.left, width: rect.width }}
          className="custom-scrollbar fixed z-50 max-h-72 overflow-auto rounded-xl border border-slate-200 bg-white py-1 shadow-xl"
        >
          {options.length === 0 ? (
            <div className="px-3 py-6 text-center text-xs text-slate-400">{emptyText}</div>
          ) : (
            options.map((o, i) => {
              const isHi = i === hi;
              const isSel = o.value === value;
              return (
                <button
                  type="button" key={o.value} data-idx={i} role="option" aria-selected={isSel}
                  disabled={o.disabled} aria-disabled={o.disabled}
                  onMouseEnter={() => { if (!o.disabled) setHighlight(i); }}
                  onClick={() => { if (!o.disabled) choose(o); }}
                  className={`flex w-full items-center gap-3 px-3 py-2 text-left transition-colors ${
                    o.disabled ? "cursor-not-allowed opacity-50" : isHi ? "bg-slate-100" : "hover:bg-slate-50"
                  }`}
                >
                  {o.badge && (
                    <span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-md font-mono text-[11px] font-bold ${
                      o.disabled ? "bg-slate-100 text-slate-400" : "bg-indigo-50 text-indigo-600"
                    }`}>
                      {o.badge}
                    </span>
                  )}
                  <span className="min-w-0 flex-1">
                    <span className={`block truncate text-sm font-semibold ${o.disabled ? "text-slate-400" : "text-slate-700"}`}>{o.label}</span>
                    {o.sublabel && <span className={`block truncate text-xs ${o.disabled ? "text-rose-500" : "text-slate-400"}`}>{o.sublabel}</span>}
                  </span>
                  {isSel && !o.disabled && <Check size={15} className="shrink-0 text-indigo-500" />}
                </button>
              );
            })
          )}
        </div>,
        document.body
      )}
    </div>
  );
}
