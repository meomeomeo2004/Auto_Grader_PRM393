"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import SidebarLayout, { MO_LICH_SU, MoLichSuDetail } from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tooltip } from "@/components/ui/Tooltip";
import { gradingStatusLabel, gradingStatusTone, type GradingOutcome } from "@/lib/gradingStatus";
import { findRunningSession, upsertStoredSession } from "@/lib/gradingSessions";
import SelectMenu from "@/components/ui/SelectMenu";
import {
  DownloadCloud, Search,
  AlertCircle, Clock, Users, FileText,
  BarChart3, X, Loader2, FileCode2, RotateCcw, AlertTriangle, FolderDown,
  MessageSquareText, Printer,
} from "lucide-react";

interface ExamOption { examId: string; examName: string; }
interface TestCaseItem {
  test_id?: string; name?: string; status?: string; executed?: boolean; weight?: number;
  difficulty?: string; skill?: string;
  actual?: string; error_log?: string;
  // `actual_source === "observation"` = `actual` đã là câu tiếng Việt sạch, hiển thị thẳng.
  // Vắng cờ = dữ liệu chấm trước P5, `actual` có thể còn là log thô → phải parse.
  actual_source?: string;
  error_code?: string;
}

const DIFF_BADGE: Record<string, string> = {
  basic: "bg-slate-100 text-slate-500",
  intermediate: "bg-amber-100 text-amber-700",
  advanced: "bg-rose-100 text-rose-700",
};
const DIFF_VI: Record<string, string> = { basic: "Cơ bản", intermediate: "Trung bình", advanced: "Nâng cao" };
interface DetailData {
  student?: { id?: string; name?: string };
  grading_result?: {
    score?: number;
    passed_tests?: number;
    failed_tests?: number;
    total_tests?: number;
    total_scenarios?: number;
    total_criteria?: number;
  };
  test_cases?: TestCaseItem[];
}
interface ResultRow {
  id: number;
  studentId: string;
  studentName: string | null;
  score: number | null;
  /** Điểm chấm tay (nếu giảng viên đã chốt) — endpoint vẫn trả, chỉ là type cũ chưa khai. */
  manualScore: number | null;
  /** Số tiêu chí đạt / tổng SAU chấm tay — backend đếm từ manual_json; null khi chưa chấm tay. */
  manualPass?: number | null;
  manualTotal?: number | null;
  /** Điểm lần chấm TRƯỚC — chỉ có sau khi bấm "Chấm lại"; lệch với `score` = cảnh báo. */
  previousScore?: number | null;
  status: "DONE" | "ERROR" | "MANUAL_REVIEW" | "GRADING" | "QUEUED" | "CANCELLED";
  /** Kết luận backend phát hành; vắng mặt ở dữ liệu cũ nên nhãn vẫn suy được từ status. */
  outcome?: GradingOutcome | null;
  batchId: string | null;
  submittedAt: string | null;
  updatedAt: string | null;
  details: string | null;
  errorLog: string | null;
  diagnosticCode: string | null;
  diagnosticOrigin: "STUDENT" | "TESTCASE" | "ENVIRONMENT" | "UNDETERMINED" | null;
  diagnosticStage: string | null;
  requiresManualReview: boolean;
  hasJson: boolean;
  /** Đã có nhận xét AI cache trong DB → bulk "Sinh feedback" bỏ qua bài này. */
  hasFeedback?: boolean;
  /** Máy bắt đầu chấm bài này lúc nào (Start Time). */
  gradingStartedAt?: string | null;
  /** Máy chấm xong lúc nào (End Time); null = lỗi giữa chừng, rơi về updatedAt. */
  gradingFinishedAt?: string | null;
}

/** Bài máy chấm KHÔNG cho ra điểm (outcome do backend phát hành; dữ liệu cũ suy từ status). */
function isSystemBlocked(row: Pick<ResultRow, "status" | "outcome">): boolean {
  if (row.outcome) return row.outcome === "SYSTEM_BLOCKED";
  return row.status === "ERROR" || row.status === "MANUAL_REVIEW";
}
interface CodeFile { name: string; content: string; }

/** Một bản nhận xét AI (khớp FeedbackRow của backend). */
interface FeedbackData {
  studentId: string;
  studentName?: string | null;
  score?: number | null;
  scoreSummary?: string | null;
  feedbackText?: string | null;
  teacherReviewRequired?: boolean;
  reviewReasons?: string[];
  sources?: string[];
  error?: string | null;
}

// Màu badge theo nhóm: sai giá trị (rose) · widget/UI (amber/orange) · exception runtime (red) · khác (slate).
const ERR_BADGE: Record<string, string> = {
  VALUE_MISMATCH: "bg-rose-100 text-rose-700",
  ASSERTION_FAILED: "bg-slate-100 text-slate-600",
  WIDGET_NOT_FOUND: "bg-amber-100 text-amber-700",
  WIDGET_UNEXPECTED: "bg-amber-100 text-amber-700",
  WIDGET_COUNT: "bg-amber-100 text-amber-700",
  // Năm mã của đường quan sát (A1 + A2b) — tách riêng vì cách sửa khác nhau, không dồn chung.
  SIZE_MISMATCH: "bg-rose-100 text-rose-700",
  TEXT_STYLE_MISMATCH: "bg-rose-100 text-rose-700",
  SEMANTICS_MISMATCH: "bg-amber-100 text-amber-700",
  ENABLED_MISMATCH: "bg-amber-100 text-amber-700",
  // Cùng màu hổ phách với nhóm "thành phần chưa đúng", KHÔNG cùng màu WIDGET_NOT_FOUND để
  // giáo viên nhìn badge là phân biệt được "thiếu hẳn" với "có nhưng sai loại".
  WIDGET_TYPE_MISMATCH: "bg-yellow-100 text-yellow-800",
  LAYOUT_OVERFLOW: "bg-orange-100 text-orange-700",
  TIMEOUT: "bg-orange-100 text-orange-700",
  NULL_ERROR: "bg-red-100 text-red-700",
  TYPE_ERROR: "bg-red-100 text-red-700",
  RANGE_ERROR: "bg-red-100 text-red-700",
  NO_SUCH_METHOD: "bg-red-100 text-red-700",
  FORMAT_ERROR: "bg-red-100 text-red-700",
  STATE_ERROR: "bg-red-100 text-red-700",
  BUILD_ERROR: "bg-red-100 text-red-700",
  COMPILE_ERROR: "bg-red-100 text-red-700",
  EXCEPTION_THROWN: "bg-red-100 text-red-700",
};

/**
 * Khối ĐỎ chi tiết lỗi 1 testcase fail.
 *
 * Chọn nhánh theo **CỜ ĐỜI DỮ LIỆU** `actual_source`, không theo sự có mặt của field nào:
 *  - `"observation"` → `actual` đã là câu tiếng Việt do engine quan sát được, in thẳng.
 *  - vắng cờ → dữ liệu chấm trước P5, `actual` có thể còn là log thô của flutter test → parse.
 *
 * Nhánh parse log CỐ Ý GIỮ LẠI: kết quả cũ trong DB không được migrate nên còn đó vĩnh viễn.
 * Gỡ nó "vì format mới đã sạch" là làm trang Lịch sử của bài cũ hiện log thô ra cho giáo viên.
 *
 * P2b đã bỏ `error.message` và `student_safe_summary` — hai câu tra bảng theo mã lỗi, không phải
 * điều quan sát được. Chỉ còn `error_code` làm nhãn phân loại.
 *
 * 2026-08-22 bỏ tiếp `expected`: tên tiêu chí đã tự mô tả đủ, in thêm một câu đặc tả nữa chỉ
 * làm khối lỗi dài ra mà không nói thêm điều gì về BÀI NÀY.
 */
function FailureDetail({ actual, errorCode, actualSource }:
  { actual?: string; errorCode?: string; actualSource?: string }) {
  // CÓ cờ nguồn = câu đã được tầng chấm viết sẵn, in thẳng. Vắng cờ = dữ liệu đời cũ,
  // `actual` có thể còn là log thô của flutter test nên mới phải bóc.
  if (actualSource) {
    return (
      <div className="mt-1 space-y-0.5 pl-3.5 text-[11px] text-rose-500">
        {actual && <p><span className="font-semibold">Quan sát được:</span> {actual}</p>}
        {errorCode && (
          <p className="flex flex-wrap items-baseline gap-1.5">
            <span className="font-semibold">Loại lỗi:</span>
            <span className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold ${ERR_BADGE[errorCode] || "bg-slate-100 text-slate-600"}`}>
              {errorCode}
            </span>
          </p>
        )}
      </div>
    );
  }

  const t = (actual || "").trim();
  const isWidget = /Test failed\.?\s*See exception logs above/i.test(t);
  const tcId = t.match(/TC_[A-Z0-9_]+/)?.[0];
  const exp = t.match(/Expected:\s*([^\n]+)/i)?.[1]?.trim();
  const act = t.match(/Actual:\s*([^\n]+)/i)?.[1]?.trim();
  const reason = t.split("\n").map((s) => s.trim())
    .filter((s) => s && !/^(Expected:|Actual:|Which:)/i.test(s)).pop();

  // Lý do: ưu tiên diễn giải exception widget; rồi tới reason; rồi tới actual thô (khi không parse được).
  const lyDo = isWidget
    ? `Test ném exception khi chạy (thiếu/sai widget, nhãn UI hoặc luồng mà test cần)${tcId ? ` [${tcId}]` : ""}.`
    : (reason && reason !== exp && reason !== act ? reason
      : (!exp && !act && t ? (t.length > 300 ? t.slice(0, 300) + "…" : t) : ""));

  const Row = ({ label, value }: { label: string; value?: string }) =>
    value ? <p><span className="font-semibold">{label}:</span> <span className="font-mono">{value}</span></p> : null;

  return (
    <div className="mt-1 space-y-0.5 pl-3.5 text-[11px] text-rose-500">
      <Row label="Expected" value={exp} />
      <Row label="Actual (thực tế)" value={act} />
      {lyDo && <p><span className="font-semibold">Lý do:</span> {lyDo}</p>}
    </div>
  );
}

/** Lấy pass/total từ field details (JSON gọn của grader). */
function passInfo(details: string | null): { pass: number; total: number } {
  try {
    const d = JSON.parse(details || "{}");
    return { pass: d.soTestPass ?? 0, total: d.tongSoTest ?? 0 };
  } catch {
    return { pass: 0, total: 0 };
  }
}

/** Chấm lại ra điểm KHÁC lần trước — dấu hiệu bộ chấm/bài nộp không ổn định, cần xem lại. */
function hasScoreDrift(row: Pick<ResultRow, "score" | "previousScore">): boolean {
  return row.previousScore != null && row.score != null
    && Math.abs(row.previousScore - row.score) > 0.001;
}

// Nhãn dùng chung với trang Chấm tự động — xem lib/gradingStatus. Trạng thái riêng của trang này:
// "Lệch điểm" (chấm lại ra khác) ưu tiên trước vì là CẢNH BÁO cần xử lý. (Trạng thái "Edited" đã
// bỏ theo yêu cầu — bài sửa điểm tay vẫn hiện đủ số liệu mới/cũ, chỉ không còn là một trạng thái.)
function statusVi(row: Pick<ResultRow, "status" | "outcome" | "score" | "previousScore">): string {
  if (hasScoreDrift(row)) return "Lệch điểm";
  return gradingStatusLabel(row.status, row.outcome);
}

function formatHistoryTime(value: string | null): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${d.getDate()}/${d.getMonth() + 1}/${d.getFullYear()}`;
}

export default function HistoryPage() {
  const [exams, setExams] = useState<ExamOption[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [rows, setRows] = useState<ResultRow[]>([]);
  const [loadingExams, setLoadingExams] = useState(true);
  const [loadingRows, setLoadingRows] = useState(false);
  const [q, setQ] = useState("");

  // Modal chi tiết năng lực 1 bài
  const [detailRow, setDetailRow] = useState<ResultRow | null>(null);
  const [detail, setDetail] = useState<DetailData | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // Modal xem bài làm + testcase (đối chiếu khi 0/0) & trạng thái chấm lại
  const [viewRow, setViewRow] = useState<ResultRow | null>(null);
  const [subFiles, setSubFiles] = useState<CodeFile[]>([]);
  const [tcFiles, setTcFiles] = useState<CodeFile[]>([]);
  /** Lý do không có testcase để xem (bài còn trong hàng đợi / đang chấm) — hiện thay khung code. */
  const [tcNotice, setTcNotice] = useState<string | null>(null);
  const [viewLoading, setViewLoading] = useState(false);
  const [activeSub, setActiveSub] = useState(0);
  const [activeTc, setActiveTc] = useState(0);
  const [regradingId, setRegradingId] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  // Khóa cuộn trang nền khi mở modal
  useEffect(() => {
    if (!detailRow && !viewRow) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = prev; };
  }, [detailRow, viewRow]);

  // Đọc query param từ thanh tiêu đề (?exam=...&q=...) — ưu tiên trước khi chọn mặc định
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const ex = params.get("exam");
    const query = params.get("q");
    if (query) setQ(query);
    if (ex) setSelected(ex);
  }, []);

  // Khi ĐANG đứng sẵn ở trang này mà bấm một thông báo trên chuông: Next chỉ đổi query chứ
  // không remount, nên effect trên không chạy lại. Thanh tiêu đề bắn kèm sự kiện cho ca đó.
  useEffect(() => {
    const nghe = (e: Event) => {
      const d = (e as CustomEvent<MoLichSuDetail>).detail;
      if (!d) return;
      setSelected(d.examId);
      // Chuông chỉ chỉ ra BỘ, không chỉ ra bài nào — phải dọn ô lọc cũ, nếu không bảng vẫn
      // bị lọc theo mã sinh viên của lần trước và trông như bộ này không có bài nào.
      setQ(d.studentId ?? "");
    };
    window.addEventListener(MO_LICH_SU, nghe);
    return () => window.removeEventListener(MO_LICH_SU, nghe);
  }, []);

  // Nạp danh sách bộ testcase đã chấm
  useEffect(() => {
    fetch(`${API_BASE}/statistics/exams`)
      .then((r) => r.json())
      .then((data: ExamOption[]) => {
        setExams(Array.isArray(data) ? data : []);
        // Chỉ tự chọn đề đầu khi CHƯA có bộ testcase nào được chọn từ URL
        if (Array.isArray(data) && data.length) {
          setSelected((prev) => prev || data[0].examId);
        }
      })
      .catch(() => setExams([]))
      .finally(() => setLoadingExams(false));
  }, []);

  // Bài của đề này còn đang chờ/đang chấm (vd vừa bấm Chấm lại). Bảng KHÔNG hiện chúng, nhưng
  // phải đếm được để biết còn thứ đáng poll — bài chấm lại xong phải tự quay về bảng, không bắt F5.
  const [pendingCount, setPendingCount] = useState(0);

  // Nạp danh sách bài đã chấm của đề được chọn (tách hàm để chấm lại xong refetch không chớp spinner)
  const loadRows = useCallback(async (showSpinner = true) => {
    if (!selected) return;
    if (showSpinner) { setLoadingRows(true); setRows([]); }
    try {
      const data = await fetch(`${API_BASE}/results/exam/${encodeURIComponent(selected)}`).then((r) => r.json());
      // Lịch sử nhận MỌI bài đã qua máy chấm: có điểm (kể cả lệch điểm do chấm lại) lẫn lỗi hệ
      // thống — đây là sổ ghi đầy đủ của kỳ chấm. Chỉ bài CHƯA xong (đang chờ/đang chấm) và bài
      // bị dừng chủ động là ở lại màn hình Chấm tự động.
      const rowsOfExam = Array.isArray(data) ? data : [];
      setPendingCount(rowsOfExam.filter(
        (r: ResultRow) => r.status === "GRADING" || r.status === "QUEUED").length);
      setRows(rowsOfExam.filter((r: ResultRow) =>
        (r.outcome ? r.outcome === "SCORED" : r.status === "DONE") || isSystemBlocked(r)));
    } catch {
      setRows([]);
      setPendingCount(0);
    } finally {
      if (showSpinner) setLoadingRows(false);
    }
  }, [selected]);

  useEffect(() => { loadRows(); }, [loadRows]);

  // Còn bài đang chờ/đang chấm → tự poll tới khi xong.
  const hasPending = pendingCount > 0;
  useEffect(() => {
    if (!hasPending) return;
    const id = setInterval(() => loadRows(false), 4000);
    return () => clearInterval(id);
  }, [hasPending, loadRows]);

  const selectedName = exams.find((e) => e.examId === selected)?.examName || selected || "";

  // ── Lọc theo THỜI GIAN CHẤM ──
  // "Thời gian chấm" = updatedAt (lần chấm gần nhất — chấm lại cập nhật mốc này); dữ liệu cũ
  // thiếu updatedAt thì rơi về submittedAt.
  const [timeFilter, setTimeFilter] = useState<"all" | "today" | "yesterday" | "daybefore" | "custom">("all");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  // Hai bộ lọc trạng thái, bật/tắt độc lập: lỗi hệ thống và chấm lại lệch điểm.
  const [blockedFilter, setBlockedFilter] = useState<"all" | "blocked">("all");
  const [driftFilter, setDriftFilter] = useState<"all" | "drift">("all");

  // Đổi đề → về "Tất cả": giữ bộ lọc của đề trước dễ ra màn hình trống khó hiểu ở đề mới.
  useEffect(() => {
    setTimeFilter("all"); setCustomFrom(""); setCustomTo("");
    setBlockedFilter("all"); setDriftFilter("all");
  }, [selected]);

  const gradedAtMs = (r: ResultRow): number | null => {
    const t = Date.parse(r.updatedAt || r.submittedAt || "");
    return Number.isNaN(t) ? null : t;
  };

  // Mốc so sánh chốt theo ĐỢT NẠP DỮ LIỆU (không cần đồng hồ chạy realtime — bảng cũng chỉ đổi
  // khi nạp lại).
  const timeBounds = useMemo(() => {
    const now = new Date();
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    return {
      today: startOfToday,
      // "Hôm qua"/"Hôm kia" là Ô CỬA SỔ đúng MỘT ngày, không phải "từ đó tới nay".
      yesterdayStart: startOfToday - 86_400_000,
      dayBeforeStart: startOfToday - 2 * 86_400_000,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows]);

  const matchTime = useMemo(() => {
    return (r: ResultRow): boolean => {
      if (timeFilter === "all") return true;
      const t = gradedAtMs(r);
      if (t == null) return false;
      if (timeFilter === "custom") {
        if (customFrom && t < new Date(`${customFrom}T00:00:00`).getTime()) return false;
        if (customTo && t > new Date(`${customTo}T23:59:59.999`).getTime()) return false;
        return true;
      }
      if (timeFilter === "today") return t >= timeBounds.today;
      if (timeFilter === "yesterday") return t >= timeBounds.yesterdayStart && t < timeBounds.today;
      return t >= timeBounds.dayBeforeStart && t < timeBounds.yesterdayStart;
    };
  }, [timeFilter, customFrom, customTo, timeBounds]);

  // Đếm sẵn cho từng mốc — con số trên nút là câu trả lời trước cả khi bấm.
  const timeCounts = useMemo(() => {
    const count = (match: (t: number) => boolean) =>
      rows.reduce((n, r) => { const t = gradedAtMs(r); return t != null && match(t) ? n + 1 : n; }, 0);
    return {
      all: rows.length,
      today: count((t) => t >= timeBounds.today),
      yesterday: count((t) => t >= timeBounds.yesterdayStart && t < timeBounds.today),
      daybefore: count((t) => t >= timeBounds.dayBeforeStart && t < timeBounds.yesterdayStart),
    };
  }, [rows, timeBounds]);

  const blockedCount = useMemo(() => rows.filter(isSystemBlocked).length, [rows]);
  const driftCount = useMemo(() => rows.filter(hasScoreDrift).length, [rows]);

  // Khoảng thời gian THẬT của dữ liệu — prefill cho "Tùy chỉnh" và gợi ý nên lọc từ đâu.
  const dataRange = useMemo(() => {
    const ts = rows.map(gradedAtMs).filter((t): t is number => t != null);
    if (!ts.length) return null;
    return { min: new Date(Math.min(...ts)), max: new Date(Math.max(...ts)) };
  }, [rows]);

  const toDateInput = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

  // "Tùy chỉnh" mở ra với khoảng ĐÚNG BẰNG dữ liệu đang có — chỉnh hẹp lại thay vì gõ từ con số 0.
  const openCustomRange = () => {
    setTimeFilter("custom");
    if (!customFrom && dataRange) setCustomFrom(toDateInput(dataRange.min));
    if (!customTo && dataRange) setCustomTo(toDateInput(dataRange.max));
  };

  const filtered = useMemo(() => {
    const k = q.trim().toLowerCase();
    return rows.filter(
      (r) =>
        matchTime(r) &&
        (blockedFilter === "all" || isSystemBlocked(r)) &&
        (driftFilter === "all" || hasScoreDrift(r)) &&
        (!k || r.studentId.toLowerCase().includes(k) || (r.studentName || "").toLowerCase().includes(k))
    );
  }, [rows, q, matchTime, blockedFilter, driftFilter]);

  /** Bấm ô "Tổng bài đã chấm" → về màn hình TỔNG: xoá mọi bộ lọc đang áp. */
  const showAllRows = () => {
    setTimeFilter("all");
    setCustomFrom("");
    setCustomTo("");
    setBlockedFilter("all");
    setDriftFilter("all");
    setQ("");
  };

  // Mở modal chi tiết: tải result_json đầy đủ (test_cases)
  const openDetail = async (r: ResultRow) => {
    if (!selected || !r.hasJson) return;
    setDetailRow(r);
    setDetail(null);
    setDetailLoading(true);
    try {
      const res = await fetch(
        `${API_BASE}/results/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}`
      );
      if (res.ok) {
        const text = await res.text();
        setDetail(JSON.parse(text) as DetailData);
      }
    } catch {
      /* bỏ qua */
    } finally {
      setDetailLoading(false);
    }
  };
  const closeDetail = () => { setDetailRow(null); setDetail(null); };

  /** Bài chưa xong một lượt chấm nào: chưa có testcase đã dùng, cũng chưa chấm lại được. */
  const inFlight = (r: ResultRow) => r.status === "QUEUED" || r.status === "GRADING";

  // Mở modal đối chiếu: tải song song mã nguồn SV + testcase đã dùng để chấm
  const openView = async (r: ResultRow) => {
    if (!selected) return;
    setViewRow(r); setSubFiles([]); setTcFiles([]); setActiveSub(0); setActiveTc(0);
    setTcNotice(null); setViewLoading(true);
    try {
      const [sf, tf] = await Promise.all([
        fetch(`${API_BASE}/batch/submission/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}/files`).then((x) => (x.ok ? x.json() : [])),
        // Bài chưa chấm xong → backend trả 409 kèm lý do; hiện thẳng lý do đó thay vì khung trống.
        fetch(`${API_BASE}/batch/testcase/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}/files`)
          .then(async (x) => (x.ok ? x.json() : { error: (await x.json().catch(() => ({}))).error })),
      ]);
      setSubFiles(Array.isArray(sf) ? sf : []);
      if (Array.isArray(tf)) setTcFiles(tf);
      else setTcNotice(tf?.error || "Không đọc được testcase đã lưu.");
    } catch {
      /* bỏ qua */
    } finally {
      setViewLoading(false);
    }
  };
  const closeView = () => { setViewRow(null); setSubFiles([]); setTcFiles([]); setTcNotice(null); };

  // Chấm lại 1 bài từ zip đã lưu, rồi poll tiến độ đến khi xong → refetch danh sách
  const regrade = async (r: ResultRow) => {
    if (!selected || regradingId) return;
    // Luật của màn hình Chấm tự động: mỗi lúc chỉ MỘT phiên chạy. Kiểm trước khi tạo phiên mới,
    // nếu không màn hình đó sẽ có hai phiên cùng "đang chấm" mà chỉ một cái được cập nhật.
    const running = await findRunningSession(API_BASE);
    if (running) {
      alert(`Bộ ${running.examId} đang được chấm ở màn hình "Chấm bài tự động". Đợi phiên đó xong rồi chấm lại bài này.`);
      return;
    }
    setRegradingId(r.studentId);
    setRows((list) => list.map((x) => (x.studentId === r.studentId ? { ...x, status: "GRADING", score: null, errorLog: null } : x)));
    try {
      const res = await fetch(`${API_BASE}/batch/regrade/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}`, { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Chấm lại thất bại");
      const batchId = data.batchId as string;
      // Đăng ký phiên vào kho dùng chung → mở "Chấm bài tự động" là thấy bài đang chấm.
      upsertStoredSession(selected, batchId);
      for (let i = 0; i < 90; i++) {
        await new Promise((rs) => setTimeout(rs, 2000));
        const pr = await fetch(`${API_BASE}/batch/progress/${encodeURIComponent(batchId)}`).then((x) => (x.ok ? x.json() : null)).catch(() => null);
        if (!pr) continue;
        const me = (pr.results || []).find((x: { studentId: string; status: string }) => x.studentId === r.studentId);
        if ((me && me.status !== "GRADING" && me.status !== "QUEUED") || pr.done + pr.error >= pr.total) break;
      }
      await loadRows(false);
    } catch (e) {
      await loadRows(false);
      alert((e as Error).message);
    } finally {
      setRegradingId(null);
    }
  };

  // Bỏ chọn khi đổi đề
  useEffect(() => { setSelectedIds(new Set()); }, [selected]);

  const toggleSel = (id: string) =>
    setSelectedIds((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  // Chấm lại NHIỀU bài đã chọn (gộp 1 batch); auto-poll tự cập nhật tới khi xong.
  const regradeSelected = async () => {
    if (!selected || selectedIds.size === 0) return;
    const running = await findRunningSession(API_BASE);
    if (running) {
      alert(`Bộ ${running.examId} đang được chấm ở màn hình "Chấm bài tự động". Đợi phiên đó xong rồi chấm lại.`);
      return;
    }
    const ids = [...selectedIds];
    setRows((list) => list.map((r) => (ids.includes(r.studentId) ? { ...r, status: "GRADING", score: null, errorLog: null } : r)));
    setSelectedIds(new Set());
    try {
      const res = await fetch(`${API_BASE}/batch/regrade-batch/${encodeURIComponent(selected)}`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ studentIds: ids }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Chấm lại thất bại");
      // Cùng lý do với chấm lại một bài: phiên phải nhìn thấy được ở màn hình theo dõi.
      if (data.batchId) upsertStoredSession(selected, data.batchId as string);
      if (Array.isArray(data.skipped) && data.skipped.length)
        alert(`Đã bỏ qua ${data.skipped.length} bài (mất file/bộ testcase): ${data.skipped.join(", ")}`);
    } catch (e) {
      await loadRows(false);
      alert((e as Error).message);
    }
  };

  // ── Nhận xét AI (feedback bot NLP) ──────────────────────────────────────
  // Chỉ còn SINH HÀNG LOẠT (nút trên đầu bảng); nút sinh/xem từng bài đã bỏ theo yêu cầu.
  // Bài vừa sinh xong trong phiên này — cộng với cờ hasFeedback từ server để bỏ qua bài đã có.
  const [fbDone, setFbDone] = useState<Set<string>>(new Set());
  const [bulkFb, setBulkFb] = useState<{ done: number; total: number } | null>(null);

  useEffect(() => { setFbDone(new Set()); }, [selected]);

  const hasFb = (r: ResultRow) => fbDone.has(r.studentId) || !!r.hasFeedback;

  /**
   * SINH TOÀN BỘ feedback của đề rồi tải về ZIP mỗi SV một file `<MSSV>.txt` (file trống nếu bài
   * đó sinh lỗi). Gọi TUẦN TỰ từng bài: bot chạy model local, dồn song song chỉ làm nghẽn.
   */
  const generateAllFeedback = async () => {
    if (!selected || bulkFb) return;
    // Bot phải đang chạy — báo một lần ngay đầu thay vì để N bài cùng lỗi một kiểu.
    try {
      const health = await fetch(`${API_BASE}/feedback/health`).then((x) => x.json());
      if (!health?.up) {
        alert(`Feedback bot (NLP) chưa chạy tại ${health?.base || "http://localhost:8000"}.\nHãy bật bot rồi bấm lại.`);
        return;
      }
    } catch {
      alert("Không kết nối được server.");
      return;
    }

    const targets = rows.filter((r) => r.hasJson && !hasFb(r));
    setBulkFb({ done: 0, total: targets.length });
    let failed = 0;
    for (const r of targets) {
      try {
        const res = await fetch(
          `${API_BASE}/feedback/exam/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}`,
          { method: "POST" });
        const data: FeedbackData = await res.json().catch(() => ({ studentId: r.studentId, error: "?" }));
        if (!res.ok || data.error) failed++;
        else setFbDone((s) => new Set(s).add(r.studentId));
      } catch { failed++; }
      setBulkFb((p) => (p ? { ...p, done: p.done + 1 } : p));
    }
    setBulkFb(null);
    await loadRows(false);   // đồng bộ cờ hasFeedback từ DB (kể cả bản sinh từ máy khác)
    if (failed) alert(`${failed} bài không sinh được feedback — bấm nút feedback của từng bài để xem lỗi.`);

    // Tải ZIP kể cả khi có bài lỗi: danh sách file khớp danh sách bài, bài lỗi là file trống.
    try {
      const res = await fetch(`${API_BASE}/feedback/exam/${encodeURIComponent(selected)}/export`);
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || "Không tạo được gói feedback.");
      }
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `Feedback_${selected}.zip`;
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (error: any) {
      alert(error?.message || "Không tải được gói feedback.");
    }
  };

  /**
   * HỒ SƠ PHÁT CHO SINH VIÊN: Result_of_<đề>/<MSSV>/{json, xls, feedback.txt, logs/} — backend
   * dựng cả gói, ZIP chỉ là lớp vận chuyển thư mục.
   */
  const downloadReportPackage = async () => {
    if (!selected) return;
    try {
      const res = await fetch(`${API_BASE}/results/exam/${encodeURIComponent(selected)}/report-package`);
      if (res.status === 404) throw new Error("Chưa có bài nào chấm xong để xuất hồ sơ.");
      if (!res.ok) throw new Error("Không tạo được gói hồ sơ.");
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `Result_of_${selected}.zip`;
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (error: any) {
      alert(error?.message || "Không xuất được gói hồ sơ.");
    }
  };

  /**
   * Xuất bảng điểm — theo ĐÚNG danh sách đang hiển thị (đã qua lọc), "cái bạn thấy là cái bạn
   * tải về".
   *
   * <p>Xuất dạng bảng HTML lưu đuôi .xls thay vì CSV thuần: yêu cầu là dòng đã sửa điểm phải TÔ
   * VÀNG, mà CSV là văn bản trần không mang được màu. Excel/LibreOffice/Google Sheets đều mở
   * bảng HTML này và giữ nguyên màu nền; Excel có thể hỏi xác nhận đuôi file — bấm Yes là mở.
   */
  const exportExcel = () => {
    if (!filtered.length) return;
    const esc = (v: string | number) =>
      String(v).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    // Cỡ chữ phải khai bằng ĐƠN VỊ pt: Excel đọc px sai (12px ra cỡ 6), còn bỏ trống thì nó rơi
    // về mặc định 10 của bộ nhập HTML.
    const BASE = "border:1px solid #CBD5E1;font-size:12.0pt;";
    // `mso-number-format:'\@'` = ép ô dạng CHỮ. CHỈ dùng cho ô thật sự là chuỗi ("13/30", "1.6/1.4",
    // mốc thời gian) — thiếu nó thì Excel đổi "12/30" thành ngày 30 tháng 12. Áp nhầm vào một con
    // số đơn thuần thì ngược lại: Excel gắn cờ "số lưu dạng chữ" (tam giác xanh góc ô).
    const td = (v: string | number, opts: { yellow?: boolean; text?: boolean } = {}) =>
      `<td style="${BASE}${opts.yellow ? "background:#FEF08A;" : ""}${
        opts.text ? "mso-number-format:'\\@';" : ""}">${esc(v)}</td>`;
    const headerCells = ["STT", "Mã SV", "Điểm", "Trạng thái", "TC RATE", "Start Time", "End Time"]
      .map((h) => `<th style="${BASE}background:#EEF2FF">${h}</th>`)
      .join("");
    const bodyRows = filtered
      .map((r, idx) => {
        const { pass, total } = passInfo(r.details);
        const edited = r.manualScore != null;
        // Điểm: bài đã sửa ghi "mới/cũ" để nhìn phát biết cả hai; bài thường giữ một số.
        const score = edited
          ? `${r.manualScore!.toFixed(1)}/${r.score != null ? r.score.toFixed(1) : "—"}`
          : r.score != null ? r.score.toFixed(1) : "";
        const tcPass = edited && r.manualTotal ? (r.manualPass ?? 0) : pass;
        const tcTotal = edited && r.manualTotal ? r.manualTotal : total;
        // Vàng tô TỪNG Ô, không tô <tr>: nền đặt ở hàng bị Excel kéo dài hết chiều ngang sheet.
        const y = { yellow: edited };
        const cells = [
          td(idx + 1, y),
          td(r.studentId, y),
          // Bài đã sửa: "1.6/1.4" là chuỗi thật → ép chữ. Bài thường: để nguyên SỐ, có vậy Excel
          // mới canh phải và không gắn cờ "số lưu dạng chữ".
          td(score, edited ? { ...y, text: true } : y),
          td(statusVi(r), y),
          td(`${tcPass}/${tcTotal}`, { ...y, text: true }),
          // Cùng nguồn + cùng fallback với hai cột Start/End Time trên bảng.
          td(formatHistoryTime(r.gradingStartedAt || r.submittedAt), { ...y, text: true }),
          td(formatHistoryTime(r.gradingFinishedAt || r.updatedAt), { ...y, text: true }),
        ].join("");
        return `<tr>${cells}</tr>`;
      })
      .join("");
    const html = `﻿<html><head><meta charset="utf-8"></head><body>` +
      `<table style="border-collapse:collapse">` +
      `<thead><tr>${headerCells}</tr></thead><tbody>${bodyRows}</tbody></table></body></html>`;
    const blob = new Blob([html], { type: "application/vnd.ms-excel;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    const dateStr = new Date().toISOString().split("T")[0];
    a.download = `${selected}_lichsu_${dateStr}.xls`;
    a.click();
  };

  return (
    <SidebarLayout
      title="Lịch sử chấm"
      activePath="/history"
      /* Cùng khuôn với trang Chấm tự động: nới trần bề ngang và chốt cột trái 320px. Danh sách
         bộ testcase không dài ra thì cũng không dễ đọc hơn — chỗ dôi ra dồn cho bảng kết quả. */
      contentClassName="max-w-[1600px]"
    >
      {/* Hai TẦNG NGANG thay cho hai cột dọc: tầng trên = chọn bộ testcase + khu lọc, tầng dưới
          là bảng chi tiết chiếm trọn bề ngang — bảng mới là nhân vật chính của trang. */}
      <div className="min-w-0 space-y-6">
        {/* Tầng trên: chọn bộ (dropdown như trang Chấm thủ công — chỉ chọn, không gõ) + bộ lọc. */}
        <div className="grid grid-cols-1 gap-3 md:grid-cols-5 xl:grid-cols-[320px_repeat(5,minmax(0,1fr))]">
          <div className="card p-4 md:col-span-5 xl:col-span-1">
            <label className="mb-2 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-400">
              <FileText size={12} /> Bộ testcase đã chấm ({exams.length})
            </label>
            {loadingExams ? (
              <Skeleton className="h-11 w-full rounded-xl" />
            ) : (
              <SelectMenu
                icon={FileText}
                ariaLabel="Bộ testcase đã chấm"
                value={selected || ""}
                onChange={(v) => setSelected(v)}
                placeholder="— Chọn bộ testcase —"
                emptyText="Chưa có bộ testcase nào được chấm."
                options={exams.map((e) => ({
                  value: e.examId,
                  label: e.examId,
                  sublabel: e.examName,
                  badge: e.examId.slice(0, 2).toUpperCase(),
                }))}
              />
            )}
          </div>
          <MiniStat
            label="Tổng bài đã chấm"
            value={rows.length}
            icon={Users}
            tone="slate"
            onClick={showAllRows}
            title="Xem toàn bộ bài (xoá mọi bộ lọc)"
          />
          <MiniStat
            label="Lỗi hệ thống"
            value={blockedCount}
            icon={AlertCircle}
            tone="amber"
            active={blockedFilter === "blocked"}
            onClick={() => setBlockedFilter((v) => (v === "blocked" ? "all" : "blocked"))}
            title="Chỉ hiện bài máy chấm không cho ra điểm — bấm lần nữa để bỏ lọc"
          />
            <MiniStat
              label="Lệch điểm"
              value={driftCount}
              icon={AlertTriangle}
              tone="orange"
              active={driftFilter === "drift"}
              onClick={() => setDriftFilter((v) => (v === "drift" ? "all" : "drift"))}
              title="Chỉ hiện bài chấm lại ra điểm khác lần trước — bấm lần nữa để bỏ lọc"
            />
            <div className="card p-4 md:col-span-2">
              <div className="mb-2.5 flex flex-wrap items-center justify-between gap-2">
                <p className="flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-400">
                  <Clock size={12} /> Lọc theo thời gian chấm
                </p>
                {dataRange && (
                  <p className="text-[11px] text-slate-400">
                    Dữ liệu từ <span className="font-semibold text-slate-500">{formatHistoryTime(dataRange.min.toISOString())}</span>
                    {" "}đến <span className="font-semibold text-slate-500">{formatHistoryTime(dataRange.max.toISOString())}</span>
                  </p>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                {([
                  { key: "all", label: "Tất cả", count: timeCounts.all },
                  { key: "today", label: "Hôm nay", count: timeCounts.today },
                  { key: "yesterday", label: "Hôm qua", count: timeCounts.yesterday },
                  { key: "daybefore", label: "Hôm kia", count: timeCounts.daybefore },
                ] as const).map((c) => (
                  <button
                    key={c.key}
                    onClick={() => setTimeFilter(c.key)}
                    className={`rounded-lg border px-3 py-1.5 text-xs font-semibold transition-colors ${
                      timeFilter === c.key
                        ? "border-indigo-300 bg-indigo-50 text-indigo-700"
                        : "border-slate-200 bg-white text-slate-500 hover:bg-slate-50"
                    }`}
                  >
                    {c.label} ({c.count})
                  </button>
                ))}
                <button
                  onClick={openCustomRange}
                  className={`rounded-lg border px-3 py-1.5 text-xs font-semibold transition-colors ${
                    timeFilter === "custom"
                      ? "border-indigo-300 bg-indigo-50 text-indigo-700"
                      : "border-slate-200 bg-white text-slate-500 hover:bg-slate-50"
                  }`}
                >
                  Tùy chỉnh…
                </button>
                {timeFilter === "custom" && (
                  <div className="flex flex-wrap items-center gap-1.5">
                    <input
                      type="date"
                      value={customFrom}
                      max={customTo || undefined}
                      onChange={(e) => setCustomFrom(e.target.value)}
                      className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 outline-none focus:border-indigo-400"
                    />
                    <span className="text-xs text-slate-400">→</span>
                    <input
                      type="date"
                      value={customTo}
                      min={customFrom || undefined}
                      onChange={(e) => setCustomTo(e.target.value)}
                      className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 outline-none focus:border-indigo-400"
                    />
                  </div>
                )}
              </div>
            </div>
          </div>

          <div className="card overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/50 px-6 py-4">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-slate-800">{selectedName || "—"}</h3>
                <p className="text-xs text-slate-500">{filtered.length} bài hiển thị</p>
              </div>
              <div className="flex items-center gap-2">
                <div className="relative">
                  <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
                  <input
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    placeholder="Tìm mã SV / tên..."
                    className="w-44 rounded-lg border border-slate-200 bg-white py-1.5 pl-8 pr-3 text-xs outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                </div>
                <button
                  onClick={generateAllFeedback}
                  disabled={!rows.some((r) => r.hasJson) || !!bulkFb}
                  title="Sinh nhận xét AI (bot NLP) cho mọi bài đã chấm, rồi tải về mỗi SV một file <MSSV>.txt"
                  className="flex items-center gap-2 rounded-lg border border-violet-200 bg-violet-50 px-3 py-1.5 text-xs font-semibold text-violet-700 shadow-sm transition-all hover:bg-violet-100 active:scale-95 disabled:opacity-50"
                >
                  {bulkFb ? <Loader2 size={15} className="animate-spin" /> : <MessageSquareText size={15} />}
                  {bulkFb ? `Sinh feedback (${bulkFb.done}/${bulkFb.total})` : "Sinh feedback"}
                </button>
                <button
                  onClick={downloadReportPackage}
                  disabled={!rows.some((r) => r.hasJson && r.outcome === "SCORED")}
                  title="Mỗi SV một thư mục: JSON + Excel (điểm theo nhóm, ảnh đối chứng) + logs"
                  className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:opacity-50"
                >
                  <FolderDown size={15} /> Hồ sơ SV
                </button>
                <button
                  onClick={() => selected && window.open(`${API_BASE}/results/exam/${encodeURIComponent(selected)}/grading-sheet`, "_blank")}
                  disabled={!selected}
                  title="Phiếu chấm tay in được, sinh từ đúng ma trận tiêu chí máy đang chấm — mở tab mới rồi Ctrl+P"
                  className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:opacity-50"
                >
                  <Printer size={15} /> Phiếu chấm tay
                </button>
                <button
                  onClick={exportExcel}
                  disabled={!rows.length}
                  title="Xuất bảng điểm Excel — dòng đã sửa điểm được tô vàng"
                  className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:opacity-50"
                >
                  <DownloadCloud size={15} /> Xuất Excel
                </button>
                {selectedIds.size > 0 && (
                  <button
                    onClick={regradeSelected}
                    title="Chấm lại các bài đã chọn (gộp 1 đợt)"
                    className="flex items-center gap-2 rounded-lg bg-amber-500 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-all hover:bg-amber-600 active:scale-95"
                  >
                    <RotateCcw size={15} /> Chấm lại ({selectedIds.size})
                  </button>
                )}
              </div>
            </div>

            {/* table-fixed + colgroup như trang Chấm tự động: cột không tự co giãn theo nội dung
                nên bảng vừa một màn, không phải kéo ngang. */}
            <div className="overflow-x-auto">
              <table className="w-full min-w-[820px] table-fixed border-collapse text-left">
                <colgroup>
                  <col className="w-[19%]" />
                  <col className="w-[12%]" />
                  <col className="w-[17%]" />
                  <col className="w-[8%]" />
                  {/* Start/End Time: hai mốc cùng khổ "hh:mm:ss d/m/yyyy". */}
                  <col className="w-[15%]" />
                  <col className="w-[15%]" />
                  {/* Cột cuối chứa 3 nút 28px + padding: hẹp hơn nữa là nút tràn ra ngoài bảng
                      (table-fixed không nới cột theo nội dung, nó chỉ cho tràn). */}
                  <col className="w-[14%]" />
                </colgroup>
                <thead>
                  <tr className="border-b border-slate-100 bg-white text-xs font-bold uppercase tracking-wider text-slate-500">
                    <th className="px-6 py-3.5 text-center">
                      <div className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={filtered.length > 0 && filtered.every((r) => selectedIds.has(r.studentId))}
                          onChange={() =>
                            setSelectedIds((s) => {
                              const n = new Set(s);
                              const all = filtered.every((r) => n.has(r.studentId));
                              filtered.forEach((r) => (all ? n.delete(r.studentId) : n.add(r.studentId)));
                              return n;
                            })
                          }
                          className="h-3.5 w-3.5 rounded border-slate-300 accent-indigo-600"
                        />
                        Sinh viên
                      </div>
                    </th>
                    <th className="px-6 py-3.5 text-center">Trạng thái</th>
                    <th className="px-6 py-3.5 text-center">Pass</th>
                    <th className="px-6 py-3.5 text-center">Điểm</th>
                    <th className="px-4 py-3.5 text-center">Start Time</th>
                    <th className="px-4 py-3.5 text-center">End Time</th>
                    <th className="sticky right-0 z-20 border-l border-slate-100 bg-white px-4 py-3.5 text-center">Chi tiết</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {loadingRows ? (
                    Array.from({ length: 6 }).map((_, i) => (
                      <tr key={i}>
                        <td className="px-6 py-3.5"><div className="flex items-center gap-3"><Skeleton className="h-9 w-9 rounded-full" /><div className="flex-1 space-y-2"><Skeleton className="h-3.5 w-28" /><Skeleton className="h-3 w-16" /></div></div></td>
                        <td className="px-6 py-3.5"><Skeleton className="mx-auto h-5 w-16 rounded-full" /></td>
                        <td className="px-6 py-3.5"><Skeleton className="h-3 w-24" /></td>
                        <td className="px-6 py-3.5"><Skeleton className="ml-auto h-6 w-10 rounded-lg" /></td>
                        <td className="px-4 py-3.5"><Skeleton className="h-3 w-24" /></td>
                        <td className="px-4 py-3.5"><Skeleton className="h-3 w-24" /></td>
                        <td className="sticky right-0 z-10 border-l border-slate-100 bg-white px-4 py-3.5"><Skeleton className="mx-auto h-7 w-7 rounded-lg" /></td>
                      </tr>
                    ))
                  ) : filtered.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="px-6 py-10 text-center text-sm text-slate-400">
                        Không có bài nào.
                      </td>
                    </tr>
                  ) : (
                    filtered.map((r) => {
                      const { pass, total } = passInfo(r.details);
                      const ratio = total > 0 ? Math.round((pass / total) * 100) : 0;
                      const isDone = r.status === "DONE";
                      const isError = r.status === "ERROR";
                      const isManual = r.status === "MANUAL_REVIEW";
                      // Đã sửa điểm ở trang Chấm thủ công → trạng thái "Edited", số liệu mới đè số cũ.
                      const isEdited = r.manualScore != null;
                      const isDrift = hasScoreDrift(r);
                      const manualRatio = r.manualTotal
                        ? Math.round(((r.manualPass || 0) / r.manualTotal) * 100) : 0;
                      const statusTone = gradingStatusTone(r.status, r.outcome);
                      const initials = (r.studentName || r.studentId || "?").trim().charAt(0).toUpperCase();
                      return (
                        <tr key={r.id} className="group transition-colors hover:bg-slate-50/70">
                          <td className="px-6 py-3.5 text-center">
                            <div className="flex items-center gap-3">
                              <input
                                type="checkbox"
                                checked={selectedIds.has(r.studentId)}
                                onChange={() => toggleSel(r.studentId)}
                                className="h-3.5 w-3.5 shrink-0 rounded border-slate-300 accent-indigo-600"
                              />
                              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-slate-200 text-xs font-bold text-slate-500">
                                {initials}
                              </div>
                              <div className="min-w-0">
                                <p className="truncate text-sm font-semibold text-slate-800">
                                  {r.studentName || "—"}
                                </p>
                                <p className="font-mono text-xs text-slate-400">{r.studentId}</p>
                              </div>
                            </div>
                          </td>
                          <td className="px-6 py-3.5 text-center">
                            {/* "Lệch điểm" là CẢNH BÁO nên đè lên nhãn thường; tooltip nói rõ
                                lệch từ đâu tới đâu để bấm vào xem là biết soi cái gì. */}
                            <span
                              title={isDrift
                                ? `Chấm lại ra điểm khác: ${r.previousScore!.toFixed(1)} → ${r.score!.toFixed(1)}`
                                : undefined}
                              className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
                                isDrift ? "bg-orange-100 text-orange-700" : statusTone.pill
                              }`}
                            >
                              {isDrift
                                ? <AlertTriangle size={12} className="shrink-0" />
                                : <span className={`h-1.5 w-1.5 rounded-full ${statusTone.dot}`}></span>}
                              {isDrift ? "Lệch điểm" : gradingStatusLabel(r.status, r.outcome)}
                            </span>
                          </td>
                          <td className="px-6 py-3.5 text-center">
                            {isEdited && r.manualTotal ? (
                              /* Thanh MỚI (có màu) nằm trên, thanh CŨ (xám) nằm dưới. */
                              <div className="inline-block space-y-1 text-left">
                                <div className="flex items-center gap-2">
                                  <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-100">
                                    <div
                                      className={`h-full rounded-full ${manualRatio >= 50 ? "bg-emerald-500" : "bg-amber-500"}`}
                                      style={{ width: `${manualRatio}%` }}
                                    ></div>
                                  </div>
                                  <span className="text-xs font-semibold text-slate-700">
                                    {r.manualPass}/{r.manualTotal}
                                  </span>
                                </div>
                                <div className="flex items-center gap-2" title="Kết quả chấm tự động trước khi sửa">
                                  <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-100">
                                    <div className="h-full rounded-full bg-slate-300" style={{ width: `${ratio}%` }}></div>
                                  </div>
                                  <span className="text-[11px] font-medium text-slate-400">
                                    {pass}/{total}
                                  </span>
                                </div>
                              </div>
                            ) : isDone ? (
                              <div className="flex items-center gap-2">
                                <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-100">
                                  <div
                                    className={`h-full rounded-full ${ratio >= 50 ? "bg-emerald-500" : "bg-amber-500"}`}
                                    style={{ width: `${ratio}%` }}
                                  ></div>
                                </div>
                                <span className="text-xs font-medium text-slate-500">
                                  {pass}/{total}
                                </span>
                              </div>
                            ) : (isError || isManual) && r.errorLog ? (
                              <span className={`line-clamp-3 max-w-xs text-xs ${isManual ? "text-amber-700" : "text-rose-500"}`} title={r.errorLog}>
                                {r.diagnosticCode && <strong>[{r.diagnosticCode}] </strong>}{r.errorLog}
                              </span>
                            ) : (
                              <span className="text-slate-300">—</span>
                            )}
                          </td>
                          <td className="px-6 py-3.5 text-center">
                            {isEdited ? (
                              /* [Điểm mới]/[Điểm cũ] — mới giữ màu theo ngưỡng, cũ chuyển xám. */
                              <span
                                className="inline-flex items-baseline gap-0.5"
                                title={`Điểm chấm tay ${r.manualScore!.toFixed(1)} · điểm tự động cũ ${r.score != null ? r.score.toFixed(1) : "—"}`}
                              >
                                <span
                                  className={`inline-block rounded-lg px-2.5 py-1 text-sm font-bold ${
                                    r.manualScore! >= PASS_THRESHOLD
                                      ? "bg-emerald-50 text-emerald-600"
                                      : "bg-rose-50 text-rose-600"
                                  }`}
                                >
                                  {r.manualScore!.toFixed(1)}
                                </span>
                                <span className="text-sm font-semibold text-slate-400">
                                  /{r.score != null ? r.score.toFixed(1) : "—"}
                                </span>
                              </span>
                            ) : r.score != null ? (
                              <span
                                className={`inline-block rounded-lg px-2.5 py-1 text-sm font-bold ${
                                  r.score >= PASS_THRESHOLD
                                    ? "bg-emerald-50 text-emerald-600"
                                    : "bg-rose-50 text-rose-600"
                                }`}
                              >
                                {r.score.toFixed(1)}
                              </span>
                            ) : (
                              <span className="font-medium text-slate-300">—</span>
                            )}
                          </td>
                          {/* Start = máy bắt đầu chấm; End = chấm xong. Bản ghi đời cũ chưa có
                              hai mốc này → rơi về mốc nộp/cập nhật, còn hơn một ô gạch ngang. */}
                          <td className="px-4 py-3.5 text-center text-xs text-slate-500">
                            {formatHistoryTime(r.gradingStartedAt || r.submittedAt) || "—"}
                          </td>
                          <td className="px-4 py-3.5 text-center text-xs text-slate-500">
                            {formatHistoryTime(r.gradingFinishedAt || r.updatedAt) || "—"}
                          </td>
                          <td className="sticky right-0 z-10 border-l border-slate-100 bg-white px-4 py-3.5 transition-colors group-hover:bg-slate-50/70">
                            <div className="flex items-center justify-center gap-1">
                              <Tooltip label={inFlight(r)
                                ? "Xem bài làm (bài chưa chấm xong nên chưa có testcase đã dùng)"
                                : "Xem bài làm & testcase"} side="left">
                                <button
                                  onClick={() => openView(r)}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600"
                                >
                                  <FileCode2 size={15} />
                                </button>
                              </Tooltip>
                              {/* Bài còn trong hàng đợi mà bấm chấm lại là xếp thêm một lượt nữa
                                  cho chính nó — chặn cho tới khi lượt đang chạy kết thúc. */}
                              <Tooltip label={inFlight(r) ? "Bài đang chờ/đang chấm — chưa chấm lại được" : "Chấm lại bài này"} side="left">
                                <button
                                  onClick={() => regrade(r)}
                                  disabled={regradingId === r.studentId || inFlight(r)}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-amber-50 hover:text-amber-600 disabled:opacity-50"
                                >
                                  {regradingId === r.studentId ? <Loader2 size={15} className="animate-spin" /> : <RotateCcw size={15} />}
                                </button>
                              </Tooltip>
                              {r.hasJson && (
                                <Tooltip label={`Xem năng lực ${r.studentId}`} side="left">
                                  <button
                                    onClick={() => openDetail(r)}
                                    className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600"
                                  >
                                    <BarChart3 size={15} />
                                  </button>
                                </Tooltip>
                              )}
                            </div>
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>
      </div>
      {/* Modal chi tiết năng lực 1 bài — render qua Portal ra body để không bị
          giam trong khung max-w-6xl (animate-fade-in-up tạo containing block). */}
      {detailRow && typeof document !== "undefined" && createPortal(
        <div
          className="animate-modal-overlay fixed inset-0 z-[100] flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm"
          onClick={closeDetail}
        >
          <div
            className="animate-modal-pop flex max-h-[88vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex shrink-0 items-center justify-between border-b border-slate-100 px-6 py-4">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-slate-800">
                  {detailRow.studentName || detailRow.studentId}
                </h3>
                <p className="font-mono text-xs text-slate-400">
                  {detailRow.studentId} · {selected}
                </p>
              </div>
              <button
                onClick={closeDetail}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
              >
                <X size={18} />
              </button>
            </div>

            <div className="custom-scrollbar flex-1 space-y-5 overflow-y-auto p-6">
              {detailLoading ? (
                <div className="flex items-center justify-center py-12 text-slate-400">
                  <Loader2 size={22} className="animate-spin" />
                </div>
              ) : detail ? (
                <>
                  {/* Tóm tắt điểm */}
                  <div className="flex items-center justify-between rounded-xl bg-slate-50 px-5 py-4">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Điểm tự động</p>
                      <p className="text-2xl font-bold text-slate-800">
                        {detail.grading_result?.score != null ? detail.grading_result.score.toFixed(1) : "—"}
                        <span className="text-sm font-medium text-slate-400">/10</span>
                      </p>
                    </div>
                    <div className="text-right text-xs text-slate-500">
                      <p>
                        Kịch bản pass:{" "}
                        <span className="font-bold text-emerald-600">
                          {detail.grading_result?.passed_tests ?? 0}
                        </span>{" "}
                        / {detail.grading_result?.total_scenarios ?? detail.grading_result?.total_tests ?? 0}
                      </p>
                      {detail.grading_result?.total_criteria != null && (
                        <p>{detail.grading_result.total_criteria} tiêu chí</p>
                      )}
                    </div>
                  </div>

                  {/* Danh sách testcase */}
                  {detail.test_cases && detail.test_cases.length > 0 && (
                    <div className="space-y-2">
                      <p className="text-sm font-bold text-slate-700">Chi tiết testcase</p>
                      <div className="space-y-1.5">
                        {detail.test_cases.map((tc, i) => {
                          const status = (tc.status || "").toLowerCase();
                          const passed = status === "passed";
                          // not_run = CHƯA CÓ CƠ HỘI CHẠY, không phải làm sai. Tô cùng màu đỏ
                          // với testcase hỏng thật sẽ khiến GV đọc thành "sai 12 chỗ khác nhau"
                          // trong khi chỉ có MỘT nguyên nhân gốc.
                          const notRun = status === "not_run";
                          return (
                            <div
                              key={tc.test_id || i}
                              className="rounded-lg border border-slate-100 bg-white px-3 py-2"
                            >
                              <div className="flex items-center gap-2">
                                <span
                                  className={`h-1.5 w-1.5 shrink-0 rounded-full ${passed ? "bg-emerald-500" : notRun ? "bg-slate-300" : "bg-rose-500"}`}
                                />
                                <span className="min-w-0 flex-1 truncate text-xs font-medium text-slate-700">
                                  {tc.name || tc.test_id}
                                </span>
                                {notRun && (
                                  <span className="shrink-0 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500">
                                    Chưa chạy
                                  </span>
                                )}
                                {tc.difficulty && (
                                  <span className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${DIFF_BADGE[tc.difficulty] || DIFF_BADGE.basic}`}>
                                    {DIFF_VI[tc.difficulty] || tc.difficulty}
                                  </span>
                                )}
                              </div>
                              {!passed && (tc.actual || tc.error_log || tc.error_code) && (
                                <FailureDetail
                                  actual={tc.actual || tc.error_log}
                                  errorCode={tc.error_code}
                                  actualSource={tc.actual_source}
                                />
                              )}
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </>
              ) : (
                <div className="py-12 text-center text-sm text-slate-400">
                  Không tải được dữ liệu bài này.
                </div>
              )}
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Modal ĐỐI CHIẾU: bài làm SV (trái) ↔ testcase đã dùng (phải) + lý do lỗi + Chấm lại */}
      {viewRow && typeof document !== "undefined" && createPortal(
        <div
          className="animate-modal-overlay fixed inset-0 z-[100] flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm"
          onClick={closeView}
        >
          <div
            className="animate-modal-pop flex max-h-[90vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex shrink-0 items-center justify-between border-b border-slate-100 px-6 py-4">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-slate-800">{viewRow.studentName || viewRow.studentId}</h3>
                <p className="font-mono text-xs text-slate-400">{viewRow.studentId} · {selected}</p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => regrade(viewRow)}
                  disabled={regradingId === viewRow.studentId || inFlight(viewRow)}
                  title={inFlight(viewRow) ? "Bài đang chờ/đang chấm — chưa chấm lại được" : undefined}
                  className="flex items-center gap-1.5 rounded-lg bg-amber-500 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-amber-600 disabled:opacity-50"
                >
                  {regradingId === viewRow.studentId ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />} Chấm lại
                </button>
                <button
                  onClick={closeView}
                  className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
                >
                  <X size={18} />
                </button>
              </div>
            </div>

            {(viewRow.status === "ERROR" || viewRow.status === "MANUAL_REVIEW") && viewRow.errorLog && (
              <div className={`shrink-0 border-b px-6 py-3 ${viewRow.status === "MANUAL_REVIEW" ? "border-amber-100 bg-amber-50" : "border-rose-100 bg-rose-50"}`}>
                <p className={`mb-1 flex items-center gap-1.5 text-xs font-bold ${viewRow.status === "MANUAL_REVIEW" ? "text-amber-800" : "text-rose-700"}`}>
                  <AlertCircle size={13} /> {viewRow.status === "MANUAL_REVIEW" ? "Lý do cần chấm tay" : "Lý do lỗi"}
                </p>
                <p className={`custom-scrollbar max-h-28 overflow-y-auto whitespace-pre-wrap text-[11px] leading-relaxed ${viewRow.status === "MANUAL_REVIEW" ? "text-amber-700" : "text-rose-600"}`}>
                  {viewRow.diagnosticOrigin && <strong>{viewRow.diagnosticOrigin === "STUDENT" ? "Bài sinh viên" : viewRow.diagnosticOrigin === "TESTCASE" ? "Bộ testcase" : viewRow.diagnosticOrigin === "ENVIRONMENT" ? "Môi trường chấm" : "Chưa xác định"} · </strong>}
                  {viewRow.diagnosticStage && <span>{viewRow.diagnosticStage} · </span>}
                  {viewRow.diagnosticCode && <strong>[{viewRow.diagnosticCode}] </strong>}
                  {viewRow.errorLog}
                </p>
              </div>
            )}

            <div className="grid min-h-0 flex-1 grid-cols-1 md:grid-cols-2">
              <div className="flex min-h-0 flex-col border-b border-slate-100 md:border-b-0 md:border-r">
                <div className="flex shrink-0 items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-4 py-2">
                  <FileCode2 size={14} className="text-indigo-500" />
                  <span className="text-xs font-bold text-slate-700">Bài làm của SV</span>
                </div>
                {viewLoading ? (
                  <div className="flex h-full items-center justify-center py-10 text-slate-400"><Loader2 size={18} className="animate-spin" /></div>
                ) : (
                  <FileViewer files={subFiles} active={activeSub} setActive={setActiveSub}
                    empty="Không đọc được bài nộp (có thể đã bị dọn hoặc không lưu)." />
                )}
              </div>
              <div className="flex min-h-0 flex-col">
                <div className="flex shrink-0 items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-4 py-2">
                  <FileText size={14} className="text-violet-500" />
                  <span className="text-xs font-bold text-slate-700">Testcase đã dùng để chấm</span>
                </div>
                {viewLoading ? (
                  <div className="flex h-full items-center justify-center py-10 text-slate-400"><Loader2 size={18} className="animate-spin" /></div>
                ) : tcNotice ? (
                  <div className="flex h-full items-center justify-center px-6 py-10 text-center">
                    <p className="flex items-start gap-2 text-xs leading-relaxed text-amber-700">
                      <AlertCircle size={14} className="mt-0.5 shrink-0" /> {tcNotice}
                    </p>
                  </div>
                ) : (
                  <FileViewer files={tcFiles} active={activeTc} setActive={setActiveTc}
                    empty="Không đọc được testcase đã lưu." />
                )}
              </div>
            </div>
          </div>
        </div>,
        document.body
      )}
    </SidebarLayout>
  );
}

// Khung xem mã nguồn: tab file + vùng code (dùng cho cả bài làm SV lẫn testcase)
function FileViewer({ files, active, setActive, empty }: {
  files: CodeFile[]; active: number; setActive: (i: number) => void; empty: string;
}) {
  if (files.length === 0)
    return <div className="flex h-full items-center justify-center p-6 text-center text-xs text-slate-400">{empty}</div>;
  const safe = Math.min(active, files.length - 1);
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="custom-scrollbar flex shrink-0 gap-1 overflow-x-auto border-b border-slate-100 px-2 py-2">
        {files.map((f, i) => (
          <button
            key={f.name}
            onClick={() => setActive(i)}
            className={`shrink-0 rounded-md px-2.5 py-1 font-mono text-[11px] transition-colors ${
              i === safe ? "bg-indigo-100 text-indigo-700" : "text-slate-500 hover:bg-slate-100"
            }`}
          >
            {f.name.split("/").pop()}
          </button>
        ))}
      </div>
      <pre className="custom-scrollbar min-h-0 flex-1 overflow-auto bg-slate-900 p-3 text-[11px] leading-relaxed text-slate-100">
        <code>{files[safe]?.content}</code>
      </pre>
    </div>
  );
}

function MiniStat({
  label, value, icon: Icon, tone, onClick, title, active,
}: {
  label: string; value: number | string; icon: React.ElementType; tone: string;
  onClick?: () => void; title?: string; active?: boolean;
}) {
  const tones: Record<string, string> = {
    slate: "bg-slate-100 text-slate-500",
    emerald: "bg-emerald-100 text-emerald-600",
    amber: "bg-amber-100 text-amber-700",
    rose: "bg-rose-100 text-rose-600",
    indigo: "bg-indigo-100 text-indigo-600",
    violet: "bg-violet-100 text-violet-600",
    orange: "bg-orange-100 text-orange-600",
  };
  const body = (
    <>
      <div className="mb-3 flex items-center justify-between">
        <p className="text-xs font-bold uppercase tracking-wider text-slate-500">{label}</p>
        <span className={`flex h-8 w-8 items-center justify-center rounded-lg ${tones[tone] || tones.slate}`}>
          <Icon size={16} />
        </span>
      </div>
      <p className="text-3xl font-bold tracking-tight text-slate-800">{value}</p>
    </>
  );
  if (!onClick) return <div className="card p-5">{body}</div>;
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      aria-pressed={active}
      // Viền tím khi filter đang bật — không có dấu hiệu thì bấm xong bảng đổi mà không rõ vì sao.
      className={`card p-5 text-left transition-all active:scale-[0.99] ${
        active
          ? `ring-2 ring-offset-1 ${tone === "orange" ? "ring-orange-400" : "ring-violet-400"}`
          : "hover:-translate-y-0.5 hover:shadow-md"
      }`}
    >
      {body}
    </button>
  );
}
