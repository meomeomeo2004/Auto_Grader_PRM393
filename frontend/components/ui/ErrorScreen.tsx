"use client";

import React from "react";
import Link from "next/link";
import {
  WifiOff, SearchX, ServerCrash, Container, ShieldAlert, AlertTriangle,
  RotateCcw, Home,
} from "lucide-react";
import { clsx } from "clsx";
import { type ErrorKind, errorCopy } from "@/lib/errors";
import { TRANG_CHU } from "@/lib/vai";

/**
 * Màn báo lỗi dùng chung. Nội dung chữ lấy từ {@link errorCopy} nên mỗi loại lỗi
 * chỉ có MỘT cách diễn đạt trong toàn app; trang gọi chỉ cần truyền `kind`.
 */

const ICON: Record<ErrorKind, React.ElementType> = {
  offline: WifiOff,
  notFound: SearchX,
  invalid: ShieldAlert,
  conflict: AlertTriangle,
  docker: Container,
  server: ServerCrash,
  unknown: AlertTriangle,
};

// Tông màu theo mức nghiêm trọng: hỏng hẳn = đỏ, chờ là được = hổ phách,
// sai đầu vào = tím thương hiệu, không tìm thấy = xám (không phải sự cố).
const TONE: Record<ErrorKind, string> = {
  offline: "bg-rose-50 text-rose-500",
  notFound: "bg-slate-100 text-slate-500",
  invalid: "bg-indigo-50 text-indigo-500",
  conflict: "bg-amber-50 text-amber-600",
  docker: "bg-amber-50 text-amber-600",
  server: "bg-rose-50 text-rose-500",
  unknown: "bg-rose-50 text-rose-500",
};

export interface ErrorScreenProps {
  kind?: ErrorKind;
  /** Đè tiêu đề mặc định của loại lỗi. */
  title?: string;
  /** Đè mô tả mặc định của loại lỗi. */
  message?: string;
  /** Thông điệp thô từ backend / stack — giấu trong phần gấp, không đập vào mặt người dùng. */
  detail?: string | null;
  onRetry?: () => void;
  retryLabel?: string;
  /** Link lối thoát; truyền null để ẩn. Mặc định hiện ở biến thể page. */
  homeHref?: string | null;
  /** page = chiếm cả màn hình (dùng cho error.tsx/not-found.tsx) · inline = nằm trong SidebarLayout. */
  variant?: "page" | "inline";
}

export default function ErrorScreen({
  kind = "unknown",
  title,
  message,
  detail,
  onRetry,
  retryLabel = "Thử lại",
  homeHref,
  variant = "inline",
}: ErrorScreenProps) {
  const copy = errorCopy(kind);
  const Icon = ICON[kind];
  const isPage = variant === "page";
  // Trang lỗi độc lập thì luôn cần lối thoát; màn lỗi nhúng đã có sidebar nên không cần.
  // Trỏ THẲNG trang đầu của bản đang chạy, không dùng "/" — "/" chỉ là một redirect, đi vòng
  // thêm một nhịp mà kết quả vẫn là chỗ đó. Hai vai có trang đầu khác nhau nên phải hỏi TRANG_CHU.
  const home = homeHref === undefined ? (isPage ? TRANG_CHU : null) : homeHref;

  const card = (
    <div className="card mx-auto w-full max-w-md p-8 text-center">
      <div className={clsx("mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl", TONE[kind])}>
        <Icon size={28} />
      </div>

      <h2 className="mb-1.5 text-lg font-bold text-slate-800">{title || copy.title}</h2>
      <p className="text-sm leading-relaxed text-slate-500">{message || copy.message}</p>

      {copy.hint && (
        <p className="mt-3 rounded-lg bg-slate-50 px-3 py-2 text-xs leading-relaxed text-slate-500">
          {copy.hint}
        </p>
      )}

      {(onRetry || home) && (
        <div className="mt-6 flex items-center justify-center gap-2">
          {onRetry && (
            <button
              onClick={onRetry}
              className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:bg-indigo-700 active:scale-95"
            >
              <RotateCcw size={16} /> {retryLabel}
            </button>
          )}
          {home && (
            <Link
              href={home}
              className="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-600 transition-colors hover:text-indigo-600"
            >
              <Home size={16} /> Về trang thống kê
            </Link>
          )}
        </div>
      )}

      {detail && (
        <details className="mt-5 text-left">
          <summary className="cursor-pointer text-xs font-semibold text-slate-400 transition-colors hover:text-slate-600">
            Chi tiết kỹ thuật
          </summary>
          <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-slate-50 p-3 text-left font-mono text-[11px] leading-relaxed text-slate-500">
            {detail}
          </pre>
        </details>
      )}
    </div>
  );

  if (!isPage) return card;

  return (
    <div className="app-canvas flex min-h-screen items-center justify-center p-6">{card}</div>
  );
}
