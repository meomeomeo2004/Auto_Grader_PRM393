"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import ExamCombobox from "@/components/ui/ExamCombobox";
import PerformanceSettings from "@/components/grading/PerformanceSettings";
import { gradingStatusLabel, gradingStatusTone } from "@/lib/gradingStatus";
// Kho phiên chấm dùng chung với trang Lịch sử (nút "Chấm lại" bên đó ghi vào cùng chỗ này).
import { readStoredSessions, writeStoredSessions } from "@/lib/gradingSessions";
import { UploadCloud, Play, Pause, FileArchive, X, CheckCircle, Clock, AlertCircle, Loader2, CheckSquare, BarChart2, Users, TrendingUp, StopCircle, Ban, RotateCcw, ListFilter, ChevronDown, FileJson } from "lucide-react";

const normalizedPath = (value) => String(value || "").replace(/\\/g, "/");

/**
 * Tên thư mục KHÔNG mang thông tin sinh viên: thư mục nén của bài làm (lib), thư mục trung gian
 * do LMS sinh ra… Gặp mấy tên này thì phải trèo lên thư mục cha mới lấy đúng tên sinh viên.
 */
const GENERIC_FOLDERS = new Set([
  "lib", "src", "code", "source", "submission", "submissions", "bai", "bailam", "bai_lam",
  "baithi", "bai_thi", "nop", "nopbai", "assignment", "upload", "uploads", "zip", "files",
]);

const genericFolder = (name) =>
  GENERIC_FOLDERS.has(String(name || "").toLowerCase().replace(/[\s-]+/g, "_"));

/**
 * Suy một bài nộp từ một file trong cây thư mục đã thả/chọn.
 *
 * <p>KHÔNG ràng buộc tên thư mục: tên có dấu, có khoảng trắng, có ngoặc đều nhận. Backend tự rút
 * mã SV (mẫu 2 chữ + ≥6 số ở bất kỳ đâu, không có thì rút gọn cả tên) — xem parseStudentInfo.
 */
const submissionFromFile = (file, suppliedPath = "") => {
  const relativePath = normalizedPath(suppliedPath || file.webkitRelativePath || file.name);
  const segments = relativePath.split("/").filter(Boolean);
  if (!segments.at(-1)?.toLowerCase().endsWith(".zip")) return null;

  // Tên sinh viên = thư mục gần file zip nhất mà KHÔNG phải tên chung chung (lib, src…).
  let username = "";
  for (let i = segments.length - 2; i >= 0; i--) {
    const name = segments[i]?.trim();
    if (!name) continue;
    if (genericFolder(name) && i > 0) continue;
    username = name;
    break;
  }
  // Zip nằm trần (kéo thẳng nhiều file .zip vào): lấy luôn tên file làm tên sinh viên.
  // Trừ khi nó tên "lib.zip" — không có thư mục cha thì chẳng biết đó là bài của ai.
  if (!username) {
    const base = segments.at(-1).replace(/\.zip$/i, "").trim();
    if (!base || genericFolder(base)) return null;
    username = base;
  }

  return { file, username: username.slice(0, 100), relativePath, key: username.toLowerCase() };
};

/**
 * Duyệt một thư mục chọn bằng File System Access API (showDirectoryPicker).
 *
 * <p>Đường này KHÔNG bật hộp thoại "Tải N tệp lên trang này?" của trình duyệt như
 * {@code <input webkitdirectory>}, nên chọn xong là bài hiện thẳng vào khu vực chờ.
 */
const collectFromDirectoryHandle = async (dirHandle, parentPath = "") => {
  const path = parentPath ? `${parentPath}/${dirHandle.name}` : dirHandle.name;
  const out = [];
  for await (const entry of dirHandle.values()) {
    if (entry.kind === "file") {
      if (!entry.name.toLowerCase().endsWith(".zip")) continue;   // bỏ qua rác, đỡ đọc thừa
      out.push({ file: await entry.getFile(), relativePath: `${path}/${entry.name}` });
    } else {
      out.push(...await collectFromDirectoryHandle(entry, path));
    }
  }
  return out;
};

/**
 * Quét thư mục vừa chọn theo TỪNG thư mục con: chọn một lần thư mục lớp là lấy được cả lớp.
 *
 * <p>Trình duyệt không cho chọn nhiều thư mục trong một hộp thoại, nên "nhiều thư mục cùng lúc"
 * đi bằng đường này — mỗi thư mục con là một sinh viên. Thư mục con nào không có .zip thì trả tên
 * ra để báo, chứ bỏ qua lặng lẽ là mất bài mà không ai biết.
 */
const scanPickedDirectory = async (handle) => {
  const nested = [];          // .zip nằm trong thư mục con → thư mục con là tên sinh viên
  const loose = [];           // .zip nằm ngay trong thư mục vừa chọn
  const emptyFolders = [];
  for await (const entry of handle.values()) {
    if (entry.kind === "file") {
      if (entry.name.toLowerCase().endsWith(".zip")) loose.push(entry);
      continue;
    }
    const found = await collectFromDirectoryHandle(entry, handle.name);
    if (found.length) nested.push(...found);
    else emptyFolders.push(entry.name);
  }

  // Thư mục vừa chọn là THƯ MỤC LỚP hay thư mục của MỘT sinh viên? Có bài trong thư mục con, hoặc
  // có từ hai file .zip nằm trần trở lên → là thư mục lớp: tên nó KHÔNG phải tên sinh viên, nên
  // mỗi zip trần phải tự lấy tên file (Lớp cô Huệ/HE180412.zip → HE180412). Nếu cứ ghép tên thư
  // mục lớp vào thì cả lớp mang chung một tên và gộp lại thành đúng một bài.
  const rootIsContainer = nested.length > 0 || loose.length > 1;
  const entries = [...nested];
  for (const entry of loose) {
    entries.push({
      file: await entry.getFile(),
      relativePath: rootIsContainer ? entry.name : `${handle.name}/${entry.name}`,
    });
  }
  return { entries, emptyFolders };
};

const readDirectoryEntries = async (reader) => {
  const entries = [];
  while (true) {
    const page = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
    if (!page.length) return entries;
    entries.push(...page);
  }
};

const collectDroppedEntry = async (entry, parentPath = "") => {
  const path = parentPath ? `${parentPath}/${entry.name}` : entry.name;
  if (entry.isFile) {
    const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
    return [{ file, relativePath: path }];
  }
  if (!entry.isDirectory) return [];
  const children = await readDirectoryEntries(entry.createReader());
  const nested = await Promise.all(children.map((child) => collectDroppedEntry(child, path)));
  return nested.flat();
};

export default function AutomaticGradingPage() {
  const [examId, setExamId] = useState("");
  const [examOptions, setExamOptions] = useState([]);
  const [examOptionsLoading, setExamOptionsLoading] = useState(true);
  // MỖI BỘ MỘT PHIÊN: exam → { batchId, phase: "uploading"|"polling"|"done", progress, parseErrors }.
  // Một biến phase/progress toàn cục là lý do chấm bộ 2 xong quay lại bộ 1 thì mọi số liệu biến
  // mất — phiên mới ghi đè phiên cũ. Giữ theo map thì đổi khung nhìn chỉ là đổi khoá đọc; dữ liệu
  // một bộ chỉ mất khi chính bộ ĐÓ bắt đầu lượt chấm mới.
  const [sessions, setSessions] = useState({});
  // Bài đã chọn nhưng CHƯA upload — cũng theo từng bộ, để soạn sẵn bài cho bộ 2 trong lúc bộ 1
  // đang chấm mà hai danh sách không lẫn vào nhau.
  const [filesByExam, setFilesByExam] = useState({});
  const [dragging, setDragging] = useState(false);
  const [uploadErr, setUploadErr] = useState(null);
  // Một trục lọc duy nhất cho bảng kết quả: all | scored | grading | blocked.
  const [rowFilter, setRowFilter] = useState("all");
  const [filterOpen, setFilterOpen] = useState(false);
  const [regrading, setRegrading] = useState(false);
  const [batchAction, setBatchAction] = useState(null);   // "stop" | "cancel" khi đang gọi API
  const [stopNotice, setStopNotice] = useState(null);
  const [addingFiles, setAddingFiles] = useState(false);
  const [addNotice, setAddNotice] = useState(null);   // { type: "ok" | "err", text }
  // Bộ VỪA chấm xong (đặt lúc phiên chuyển polling→done). Banner không được lặng lẽ biến mất
  // khi máy chấm xong — nó đổi sang trạng thái "đã chấm xong" cho tới khi người dùng đóng.
  const [finishedNotice, setFinishedNotice] = useState(null);
  const fileRef = useRef();
  const pollRef = useRef(null);
  const filterRef = useRef(null);
  const addFileRef = useRef(null);
  // Chặn effect ghi localStorage chạy TRƯỚC khi khôi phục xong — không thì lần mount đầu
  // (sessions còn rỗng) sẽ xoá sạch phiên đã lưu trước cả khi kịp đọc lại.
  const restoredRef = useRef(false);

  // ── Helpers cho map phiên/file theo bộ ──
  const patchSession = (exam, patch) => setSessions((all) => ({
    ...all,
    [exam]: typeof patch === "function" ? patch(all[exam]) : { ...all[exam], ...patch },
  }));
  const dropSession = (exam) => setSessions((all) => {
    const next = { ...all };
    delete next[exam];
    return next;
  });
  const setFilesFor = (exam, updater) => setFilesByExam((all) => ({
    ...all,
    [exam]: typeof updater === "function" ? updater(all[exam] || []) : updater,
  }));

  // ── Khung nhìn = bộ đang chọn trong combobox; các biến dưới đây đều là CỦA BỘ ĐÓ ──
  const trimmedExam = examId.trim();
  const viewSession = sessions[trimmedExam] || null;
  const phase = viewSession?.phase || "idle";          // idle | uploading | polling | done
  const batchId = viewSession?.batchId || null;
  const progress = viewSession?.progress || null;
  const parseErrors = viewSession?.parseErrors || [];
  const files = filesByExam[trimmedExam] || [];
  // Bộ (bất kỳ) đang chấm — toàn trang chỉ cho MỘT phiên chạy tại một thời điểm.
  const runningExam = Object.keys(sessions).find(
    (exam) => sessions[exam]?.phase === "uploading" || sessions[exam]?.phase === "polling"
  ) || null;
  const runningSession = runningExam ? sessions[runningExam] : null;
  const blockedByRunningSession = !!runningExam && runningExam !== trimmedExam;

  // Người chấm chỉ cần MỘT câu trả lời: bài nào máy chấm không cho ra điểm. Backend phát hành
  // sẵn kết luận đó ở `outcome` (PENDING | SCORED | SYSTEM_BLOCKED | STOPPED).
  //
  // Trước đây chỗ này tự suy lại từ status × diagnostic_origin × diagnostic_code qua ~50 dòng
  // heuristic, kèm cả việc bới JSON test_cases. Hậu quả đo được: 4 mã timeout của MÔI TRƯỜNG
  // (GRADER_TOTAL_TIMEOUT, CONTAINER_WATCHDOG_TIMEOUT, TEST_PROCESS_TIMEOUT,
  // GRADING_TIMEOUT_UNDETERMINED) bị luật "chỉ nhận timeout khi origin=TESTCASE" nuốt mất —
  // đúng nhóm cần báo thì không hiện. Suy đoán ở FE là nguồn sự thật thứ hai; nay bỏ hẳn.
  const isBlocked = (row) => row?.outcome === "SYSTEM_BLOCKED";

  const formatDiagnosticStage = (stage) => {
    const labels = {
      DEPENDENCY_PREFLIGHT: "Kiểm tra thư viện",
      COMPILE: "Biên dịch",
      SOURCE_CONTRACT: "Contract mã nguồn",
      SOURCE_POLICY: "Chính sách class/mã nguồn",
      APP_BOOT: "Khởi động ứng dụng",
      STUDENT_UI_ACTION: "Thao tác giao diện",
      STUDENT_ASYNC_SETTLE: "Chờ xử lý bất đồng bộ",
      STUDENT_FUNCTION: "Hàm sinh viên",
      TESTCASE_EXECUTION: "Thực thi testcase",
      CONTAINER: "Docker sandbox",
    };
    return labels[stage] || stage || "";
  };

  // Nạp toàn bộ bộ testcase đã tạo để giáo viên chọn nhanh; không giới hạn ở bộ testcase đã chấm.
  useEffect(() => {
    let cancelled = false;
    fetch(`${API_BASE}/exam-setup/list`)
      .then((r) => (r.ok ? r.json() : []))
      .then((data) => {
        if (cancelled) return;
        const options = Array.isArray(data)
          ? data
              .filter((e) => e?.examId)
              .map((e) => ({ examId: String(e.examId), examName: e.examName || String(e.examId) }))
          : [];
        setExamOptions(options);
      })
      .catch(() => { if (!cancelled) setExamOptions([]); })
      .finally(() => { if (!cancelled) setExamOptionsLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // ── Khôi phục phiên chấm khi quay lại trang ──
  // CHỈ khôi phục phiên còn CHẠY DỞ. Phiên đã chấm hết bài là phiên ĐÃ KẾT THÚC — dữ liệu của nó
  // thuộc về trang Lịch sử chấm, màn hình này không lưu lại. Lưu dạng map nhiều phiên (v2); vẫn
  // đọc được bản lưu một-phiên cũ.
  useEffect(() => {
    const saved = readStoredSessions();
    const entries = Object.entries(saved.sessions);
    if (!entries.length) { restoredRef.current = true; return; }

    // Ba kết cục khác nhau, và phải phân biệt: "backend nói phiên này không còn/đã xong" thì XÓA
    // khỏi kho, còn "không hỏi được backend" thì GIỮ NGUYÊN — backend đang khởi động lại mà đi
    // dọn thì mất trắng phiên chấm thật.
    Promise.all(entries.map(async ([exam, info]) => {
      if (!exam || !info?.batchId) return { exam, verdict: "gone" };
      try {
        const data = await fetch(`${API_BASE}/batch/progress/${info.batchId}`).then((r) => r.json());
        if (!data || data.total == null) return { exam, verdict: "unreachable", info };
        // Bộ testcase bị xóa → backend xóa luôn phiên chấm và trả `status: "UNKNOWN"`. Không bỏ
        // phiên ở đây thì batchId cũ nằm lại localStorage và cứ mỗi lần F5 lại dựng lại nguyên
        // màn hình chấm của một bộ không còn tồn tại.
        if (data.status === "UNKNOWN") return { exam, verdict: "gone" };
        const pending = (data.queued || 0) + (data.grading || 0);
        // Chấm xong hết trong lúc vắng mặt → phiên đã kết thúc, kết quả xem ở Lịch sử chấm.
        if (pending === 0) return { exam, verdict: "gone" };
        return { exam, verdict: "live", info, session: {
          batchId: info.batchId,
          phase: "polling",
          progress: data,
          parseErrors: info.parseErrors || [],
        } };
      } catch { return { exam, verdict: "unreachable", info }; }
    })).then((outcomes) => {
      const valid = outcomes.filter((o) => o.verdict === "live").map((o) => [o.exam, o.session]);
      if (valid.length) {
        setSessions(Object.fromEntries(valid));
        const running = valid.find(([, s]) => s.phase === "polling");
        // Ưu tiên mở đúng bộ đang chấm dở; không thì bộ dùng gần nhất.
        const focus = running?.[0] || saved.lastExam || valid[valid.length - 1][0];
        setExamId((prev) => prev || focus);
        if (running) startPolling(running[1].batchId, running[0]);
      }
      restoredRef.current = true;

      // Dọn kho ngay tại đây. Effect ghi kho bên dưới chỉ chạy khi `sessions` đổi, mà phiên chết
      // thì không vào `sessions` — không tự ghi lại thì batchId chết nằm mãi trong localStorage.
      const dropped = outcomes.filter((o) => o.verdict === "gone");
      if (!dropped.length) return;
      const kept = {};
      for (const o of outcomes) {
        if (o.verdict === "live") kept[o.exam] = { batchId: o.session.batchId, parseErrors: o.session.parseErrors };
        else if (o.verdict === "unreachable") kept[o.exam] = o.info;
      }
      writeStoredSessions({ lastExam: kept[saved.lastExam] ? saved.lastExam : undefined, sessions: kept });
    });
  }, []);

  // Ghi lại map phiên mỗi khi nó đổi (batchId là đủ để dựng lại; progress đọc lại từ backend).
  // CHỈ ghi phiên đang chạy dở: phiên chấm xong là kết thúc — không lưu, F5 là dữ liệu về với
  // trang Lịch sử chấm (màn hình này chỉ giữ nó tới khi rời trang, cho người đứng nhìn tiến độ).
  useEffect(() => {
    if (!restoredRef.current) return;
    const out = {};
    for (const [exam, s] of Object.entries(sessions)) {
      if (s?.batchId && (s.phase === "uploading" || s.phase === "polling"))
        out[exam] = { batchId: s.batchId, parseErrors: s.parseErrors || [] };
    }
    writeStoredSessions({ lastExam: examId.trim(), sessions: out });
  }, [sessions, examId]);

  // Đổi bộ đang xem → thông báo của bộ trước không được dính sang bộ sau.
  useEffect(() => { setUploadErr(null); setAddNotice(null); setStopNotice(null); setRowFilter("all"); }, [examId]);

  // Dọn interval khi rời trang (tránh setState trên component đã unmount)
  useEffect(() => () => clearInterval(pollRef.current), []);

  // Đóng menu lọc khi bấm ra ngoài hoặc nhấn Esc.
  useEffect(() => {
    if (!filterOpen) return;
    const onDown = (e) => { if (!filterRef.current?.contains(e.target)) setFilterOpen(false); };
    const onKey = (e) => { if (e.key === "Escape") setFilterOpen(false); };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [filterOpen]);

  // File handling
  const addFiles = useCallback((incoming) => {
    const candidates = Array.from(incoming).map((value) => value?.file
      ? submissionFromFile(value.file, value.relativePath)
      : submissionFromFile(value));
    const submissions = candidates.filter(Boolean);
    if (!submissions.length) {
      setUploadErr("Không tìm thấy bài hợp lệ. Mỗi thư mục sinh viên phải chứa một file .zip của thư mục lib.");
      return;
    }
    // File chọn vào BỘ ĐANG XEM — không có bộ thì chưa biết cất vào đâu.
    const exam = examId.trim();
    if (!exam) { setUploadErr("Chọn mã bộ testcase trước khi thêm bài."); return; }
    setFilesByExam((all) => {
      const prev = all[exam] || [];
      const existing = new Set(prev.map((entry) => entry.key));
      return { ...all, [exam]: [...prev, ...submissions.filter((entry) => !existing.has(entry.key))] };
    });
    setUploadErr(null);
  }, [examId]);

  const onDrop = useCallback(async (e) => {
    e.preventDefault(); setDragging(false);
    try {
      const roots = Array.from(e.dataTransfer.items || [])
        .map((item) => item.webkitGetAsEntry?.())
        .filter(Boolean);
      if (roots.length) {
        const nested = await Promise.all(roots.map((entry) => collectDroppedEntry(entry)));
        addFiles(nested.flat());
      } else {
        addFiles(e.dataTransfer.files);
      }
    } catch (error) {
      setUploadErr("Không đọc được thư mục đã thả: " + (error?.message || "lỗi không xác định"));
    }
  }, [addFiles]);

  /**
   * Chọn thư mục bài nộp — chọn THƯ MỤC LỚP là lấy hết bài của cả lớp trong một lần.
   *
   * <p>Ưu tiên showDirectoryPicker: chọn xong là bài vào thẳng khu vực chờ, KHÔNG qua hộp thoại
   * "Tải N tệp lên trang này?" mà {@code <input webkitdirectory>} luôn bật lên. Hộp thoại xin
   * quyền đọc thư mục là của trình duyệt, trang web không tắt được — nhưng chọn cả thư mục lớp
   * thì chỉ phải qua nó ĐÚNG MỘT LẦN thay vì mỗi sinh viên một lần.
   */
  const pickFolders = useCallback(async () => {
    if (typeof window === "undefined" || !window.showDirectoryPicker) {
      fileRef.current?.click();
      return;
    }
    let handle;
    try {
      handle = await window.showDirectoryPicker({ id: "grader-submissions", mode: "read" });
    } catch (error) {
      if (error?.name !== "AbortError") {                // AbortError = người dùng bấm Huỷ
        setUploadErr("Không mở được thư mục: " + (error?.message || "lỗi không xác định"));
      }
      return;
    }
    try {
      const { entries } = await scanPickedDirectory(handle);
      addFiles(entries);
    } catch (error) {
      setUploadErr("Không đọc được thư mục đã chọn: " + (error?.message || "lỗi không xác định"));
    }
  }, [addFiles]);

  const removeFile = (key) => setFilesFor(examId.trim(), (current) => current.filter((entry) => entry.key !== key));

  // Upload + poll
  const execute = async () => {
    // CHỐT exam ngay đầu hàm: mọi bước sau dùng đúng giá trị lúc bấm nút, kể cả khi combobox đổi
    // giữa chừng.
    const exam = examId.trim();
    if (!exam) { setUploadErr("Vui lòng nhập mã bộ testcase."); return; }
    // MỖI LÚC MỘT PHIÊN trên toàn trang (nút cũng đã khoá — đây là lưới an toàn).
    if (runningExam) return;
    const staged = filesByExam[exam] || [];
    if (!staged.length) { setUploadErr("Chưa có file nào để chấm."); return; }

    setUploadErr(null); setStopNotice(null); setAddNotice(null); setRowFilter("all");
    // Phiên MỚI của chính bộ này thay phiên cũ CỦA NÓ — dữ liệu các bộ khác không bị đụng tới.
    setSessions((all) => ({ ...all, [exam]: { batchId: null, phase: "uploading", progress: null, parseErrors: [] } }));

    const form = new FormData();
    form.append("examId", exam);
    staged.forEach((entry) => {
      form.append("files", entry.file, entry.file.name);
      form.append("usernames", entry.username);
    });

    try {
      const res = await fetch(`${API_BASE}/batch/upload`, { method: "POST", body: form });
      const data = await res.json();

      // Upload hỏng → bỏ phiên nháp nhưng GIỮ danh sách file đã chọn để sửa rồi bấm lại.
      if (!res.ok) { setUploadErr(data.error || "Lỗi server."); dropSession(exam); return; }

      setFilesFor(exam, []);   // đã upload — danh sách chờ của bộ này về rỗng
      patchSession(exam, { batchId: data.batchId, phase: "polling", parseErrors: data.parseErrors || [] });
      startPolling(data.batchId, exam);
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
      dropSession(exam);
    }
  };

  /**
   * Nạp THÊM bài vào phiên đang mở — thu bài làm nhiều đợt mà không phải tạo phiên mới.
   *
   * <p>Gửi thẳng lên `/batch/{id}/add` chứ không gom vào `files` như màn hình đầu: ở đây phiên đã
   * chạy rồi, thêm một bước "chọn xong rồi bấm Bắt đầu" chỉ tạo cơ hội quên bấm.
   */
  const addMoreSubmissions = async (incoming) => {
    const exam = examId.trim();
    const bid = sessions[exam]?.batchId;
    if (!bid || addingFiles) return;
    // Bộ khác đang chấm: nạp thêm vào phiên này sẽ thành hai bộ chạy song song — chặn.
    if (runningExam && runningExam !== exam) {
      setAddNotice({ type: "err", text: `Bộ ${runningExam} đang được chấm — đợi xong mới nạp thêm bài cho bộ này.` });
      return;
    }
    const submissions = Array.from(incoming || [])
      .map((value) => (value?.file ? submissionFromFile(value.file, value.relativePath) : submissionFromFile(value)))
      .filter(Boolean);
    if (!submissions.length) {
      setAddNotice({ type: "err", text: "Không tìm thấy bài hợp lệ. Mỗi thư mục sinh viên phải chứa một file .zip." });
      return;
    }

    setAddingFiles(true);
    setAddNotice(null);
    const form = new FormData();
    submissions.forEach((entry) => {
      form.append("files", entry.file, entry.file.name);
      form.append("usernames", entry.username);
    });
    try {
      const res = await fetch(`${API_BASE}/batch/${encodeURIComponent(bid)}/add`, { method: "POST", body: form });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { setAddNotice({ type: "err", text: data?.error || "Không thêm được bài vào phiên chấm." }); return; }

      if (data.parseErrors?.length) {
        patchSession(exam, (s) => ({ ...s, parseErrors: [...(s?.parseErrors || []), ...data.parseErrors] }));
      }
      // `BatchSubmitResponse.totalQueued` — KHÔNG phải `queued`. Đọc sai khoá thì `undefined > 0`
      // luôn false, nên trước đây thông báo hiện "0 bài" VÀ vòng poll không được bật lại: bài vẫn
      // vào hàng đợi và chấm bình thường dưới Docker, chỉ có màn hình là đứng im.
      const queued = data.totalQueued ?? 0;
      setAddNotice({ type: "ok", text: `Đã đưa ${queued} bài vào hàng đợi chấm.` });
      if (queued > 0) {
        patchSession(exam, (s) => ({ ...s, phase: "polling" }));
        await refreshProgress(bid, exam);   // cập nhật con số NGAY, không chờ hết nhịp 3 giây
        startPolling(bid, exam);
      }
    } catch (e) {
      setAddNotice({ type: "err", text: "Không kết nối được server: " + e.message });
    } finally {
      setAddingFiles(false);
    }
  };

  /** Đọc tiến độ MỘT lần cho phiên của `exam`. Trả về số bài còn chờ/đang chấm (-1 nếu lỗi). */
  const refreshProgress = async (bid, exam) => {
    try {
      const res = await fetch(`${API_BASE}/batch/progress/${bid}`);
      const data = await res.json();
      patchSession(exam, (s) => ({ ...s, progress: data }));
      return (data.queued || 0) + (data.grading || 0);
    } catch (_) {
      return -1;
    }
  };

  const startPolling = (bid, exam) => {
    clearInterval(pollRef.current);   // toàn trang chỉ một phiên chạy → một interval là đủ
    pollRef.current = setInterval(async () => {
      const pending = await refreshProgress(bid, exam);
      if (pending === 0) {
        clearInterval(pollRef.current);
        patchSession(exam, (s) => ({ ...s, phase: "done" }));
        // GIỮ phiên trong map + localStorage: F5 hay đổi bộ xong quay lại vẫn thấy nguyên kết quả.
        setFinishedNotice(exam);   // banner chuyển sang "đã chấm xong", không lặng lẽ biến mất
      }
    }, 3000);
  };

  const toggleBatchPause = async () => {
    if (!batchId) return;
    const action = progress?.status === "PAUSED" ? "resume" : "pause";
    try {
      const res = await fetch(`${API_BASE}/batch/${encodeURIComponent(batchId)}/${action}`, { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data?.error || "Không cập nhật được trạng thái phiên chấm.");
      patchSession(trimmedExam, (s) => ({ ...s, progress: data }));
      setUploadErr(null);
    } catch (error) {
      setUploadErr(error?.message || "Không cập nhật được trạng thái phiên chấm.");
    }
  };

  /**
   * "+ Chấm kỳ thi mới": chỉ RỜI khung nhìn về form trắng (bỏ chọn bộ testcase) — KHÔNG xoá dữ
   * liệu của bất kỳ phiên nào. Chọn lại một bộ đã từng chấm là thấy lại nguyên kết quả của nó;
   * dữ liệu phiên chỉ mất khi chính bộ đó bắt đầu lượt chấm mới.
   */
  const startNewView = () => {
    setExamId("");
    setUploadErr(null); setStopNotice(null); setAddNotice(null);
    setRowFilter("all");
  };

  // ── Dừng phiên chấm đang chạy ──────────────────────────────────
  // Dừng = bỏ các bài chưa chấm + giết container đang chạy, GIỮ kết quả đã có; phiên vẫn nhận
  // thêm bài để chấm tiếp. (Nút "Hủy phiên chấm" — xóa sạch kết quả — đã bỏ theo yêu cầu:
  // quá gần một thao tác không hoàn tác được, ai cần xóa thì xóa cả bộ ở Quản lý bộ testcase.)
  const stopGrading = async () => {
    if (!batchId || batchAction) return;
    setBatchAction("stop");
    try {
      const res = await fetch(`${API_BASE}/batch/${batchId}/stop`, { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { setUploadErr(data.error || "Không dừng được phiên chấm."); return; }
      const skipped = (data.dequeued || 0) + (data.cancelled || 0);
      setStopNotice(`Đã dừng phiên chấm: ${skipped} bài chưa chấm bị bỏ qua`
        + (data.killedContainers ? `, ${data.killedContainers} bài đang chấm bị ngắt` : "")
        + ". Kết quả đã có vẫn giữ nguyên — nạp thêm bài ở ô “Thêm bài làm” để chấm tiếp.");
      // GIỮ phiên lưu: bài đã chấm xong vẫn còn đó, và giáo viên còn nạp thêm bài vào phiên này.
      // KHÔNG tự chuyển sang "done" ở đây: vòng poll sẵn có sẽ tự kết thúc khi container cuối
      // cùng thực sự thoát — nếu giết hụt thì người dùng phải thấy nó vẫn đang chạy.
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
    } finally {
      setBatchAction(null);
    }
  };

  /**
   * Tải về thư mục kết quả: bấm một cái là tải ngay, giải nén ra `Json/<MSSV>.json`.
   *
   * <p>KHÔNG dùng `showDirectoryPicker`: nó bắt người dùng chọn thư mục, và Chrome từ chối phần
   * lớn thư mục quen tay ("thư mục này chứa tệp hệ thống") nên thao tác hay chết giữa chừng.
   * Trình duyệt không tải xuống được một thư mục thật, nên ZIP là lớp vận chuyển duy nhất —
   * bên trong vẫn đúng một thư mục `Json` với các file rời, không phải JSON gộp.
   */
  const downloadResultsFolder = async () => {
    if (!batchId) return;
    try {
      const res = await fetch(`${API_BASE}/results/batch/${encodeURIComponent(batchId)}/archive`);
      if (res.status === 404) throw new Error("Chưa có bài nào chấm xong để xuất.");
      if (!res.ok) throw new Error("Không tạo được thư mục kết quả.");
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "Json.zip";
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (error) {
      setUploadErr(error?.message || "Không xuất được thư mục JSON.");
    }
  };

  // Tải JSON riêng của 1 sinh viên → MaSV.json
  const downloadStudentJson = async (r) => {
    const exId = r.examId || trimmedExam;
    try {
      const res = await fetch(`${API_BASE}/results/${encodeURIComponent(exId)}/${encodeURIComponent(r.studentId)}`);
      if (!res.ok) return;
      const text = await res.text();
      const blob = new Blob([text], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `${r.studentId}.json`;
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (_) {}
  };

  const p = progress;
  const isPaused = p?.status === "PAUSED";

  const totalItems = (p?.total || 0) + parseErrors.length;
  const doneItems = p?.done || 0;
  const cancelledItems = p?.cancelled || 0;   // bài bị bỏ do người dùng dừng phiên
  // Bài máy chấm KHÔNG cho ra điểm. Trước đây tách "lỗi" và "cần chấm tay" thành hai con số,
  // nhưng người chấm phải xử lý y như nhau nên tách chỉ làm khó đọc. Bài sinh viên làm sai
  // KHÔNG nằm ở đây — nó đã là 0 điểm trong "Hoàn thành".
  const blockedItems = p?.blocked ?? ((p?.error || 0) + (p?.manualReview || 0));
  // File bị loại ngay khi upload (không phải .zip, rỗng, quá 50MB) chưa từng vào hàng đợi.
  const rejectedItems = parseErrors.length;

  // Bài bị dừng cũng là "đã xử lý xong": không tính vào đây thì thanh tiến độ đứng mãi dưới 100%.
  const processedItems = doneItems + blockedItems + cancelledItems + rejectedItems;
  const pct = totalItems > 0 ? Math.round((processedItems / totalItems) * 100) : 0;
  const donePct = totalItems > 0 ? Math.round((doneItems / totalItems) * 100) : 0;
  const blockedPct = totalItems > 0 ? Math.round(((blockedItems + rejectedItems) / totalItems) * 100) : 0;
  const cancelPct = totalItems > 0 ? Math.round((cancelledItems / totalItems) * 100) : 0;

  const totalSize = files.reduce((sum, entry) => sum + entry.file.size, 0);

  const allResultRows = p?.results || [];
  // Sự cố đã được backend GOM THEO NGUYÊN NHÂN: Docker chết một cái là 20 bài cùng hỏng, người
  // chấm cần đọc một dòng chứ không phải cuộn 20 dòng giống nhau.
  const incidents = p?.incidents || [];
  const blockedStudentIds = incidents.flatMap((incident) => incident.studentIds || []);
  // Đếm trên chính danh sách đang hiển thị để con số trên nút khớp đúng số dòng lọc ra được
  // (blockedItems của batch còn cộng cả bài không nằm trong bảng).
  const blockedRowCount = allResultRows.filter(isBlocked).length;
  // Nhãn tiếng Việt của từng mã lấy luôn từ nhóm sự cố — không dựng bảng tra thứ hai ở FE.
  const incidentLabelByCode = Object.fromEntries(
    incidents.map((incident) => [incident.code, incident.label])
  );

  // Bộ lọc khai BẰNG DỮ LIỆU, không phải bằng chuỗi if: cả menu lọc lẫn 4 thẻ thống kê phía trên
  // đều đọc từ đây nên số trên thẻ luôn đúng bằng số dòng lọc ra được — không có đường nào để hai
  // chỗ lệch nhau.
  const rowFilters = [
    { key: "all",     label: "Tất cả bài",   count: allResultRows.length,
      match: () => true },
    { key: "scored",  label: "Đã chấm",      count: allResultRows.filter((r) => r.outcome === "SCORED").length,
      match: (r) => r.outcome === "SCORED" },
    { key: "grading", label: "Đang chấm",    count: allResultRows.filter((r) => r.outcome === "PENDING").length,
      match: (r) => r.outcome === "PENDING" },
    { key: "blocked", label: "Lỗi hệ thống", count: blockedRowCount,
      match: isBlocked },
  ];
  const activeFilter = rowFilters.find((f) => f.key === rowFilter) || rowFilters[0];
  const filteredResultRows = allResultRows.filter(activeFilter.match);

  // Chấm lại đúng nhóm bài hỏng sau khi đã sửa máy/testcase — việc gần như luôn phải làm sau một
  // sự cố hệ thống, nên đặt ngay cạnh cảnh báo thay vì bắt vào trang Lịch sử bấm từng bài.
  const regradeBlocked = async () => {
    if (!blockedStudentIds.length || regrading || runningExam) return;
    const targetExam = p?.examId || trimmedExam;
    if (!targetExam) return;
    setRegrading(true);
    try {
      const res = await fetch(`${API_BASE}/batch/regrade-batch/${encodeURIComponent(targetExam)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ studentIds: blockedStudentIds }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { setUploadErr(data?.error || "Không chấm lại được nhóm bài này."); return; }
      setRowFilter("all");
      setStopNotice(`Đang chấm lại ${data.queued || 0} bài bị sự cố hệ thống.`);
      // Batch chấm lại THAY phiên hiện tại của đúng bộ này.
      setSessions((all) => ({ ...all, [targetExam]: {
        batchId: data.batchId, phase: "polling", progress: null, parseErrors: [],
      } }));
      startPolling(data.batchId, targetExam);
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
    } finally {
      setRegrading(false);
    }
  };

  return (
    <SidebarLayout
      title="Chấm bài tự động"
      subtitle="Chấm tự động bài thi Flutter trong môi trường Docker cô lập"
      activePath="/teacher/grading"
      /* Nới trần bề ngang (mặc định max-w-6xl) — bảng kết quả 6 cột không đủ chỗ trong 1152px
         nên phải kéo ngang; cùng mức với trang Quản lý bộ testcase để hai trang nhìn đồng bộ. */
      contentClassName="max-w-[1600px]"
    >
      {/* Cột trái CỐ ĐỊNH 320px: form thiết lập không dài ra thì cũng không đẹp hơn. Toàn bộ
          phần dôi ra dồn cho cột kết quả — đó mới là nơi người chấm nhìn lâu nhất. */}
      <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-[320px_minmax(0,1fr)]">

        {/* Cột trái: Form cấu hình & Upload */}
        <div className="min-w-0 space-y-6">
          <div className="card overflow-hidden">
            {/* Header gradient */}
            <div className="flex items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-blue-50 px-6 py-4">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 text-white shadow-sm">
                <CheckSquare size={18} />
              </div>
              <div>
                <h2 className="text-sm font-bold text-slate-800">Thiết lập phiên chấm bài</h2>
                <p className="text-xs text-slate-500">Chọn bộ testcase và tải bài nộp</p>
              </div>
            </div>

            <div className="space-y-4 p-6">
              <div>
                <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">Mã Bộ Testcase</label>
                {/* Không khoá lúc đang chấm — đổi bộ chỉ đổi KHUNG NHÌN; phiên đang chạy vẫn
                    chạy ngầm và quay lại đúng bộ đó là thấy lại tiến độ. */}
                <ExamCombobox
                  options={examOptions}
                  value={examId}
                  onChange={setExamId}
                  ariaLabel="Mã bộ testcase"
                  placeholder={examOptionsLoading ? "Đang tải danh sách bộ testcase..." : "Nhập hoặc chọn mã bộ testcase..."}
                />
              </div>

              {/* Hiện cả khi phiên trước VỪA XONG: phiên đã kết thúc thì lượt upload kế tiếp là
                  một phiên hoàn toàn mới, không cần bấm reset gì trước. */}
              {(phase === "idle" || phase === "done") && (
                <div>
                  <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">Upload Bài Nộp</label>
                  <div
                    onDrop={onDrop}
                    onDragOver={e => { e.preventDefault(); setDragging(true); }}
                    onDragLeave={() => setDragging(false)}
                    onClick={pickFolders}
                    className={`cursor-pointer rounded-xl border-2 border-dashed p-8 text-center transition-all ${
                      dragging ? "border-indigo-500 bg-indigo-50 scale-[1.01]" : "border-slate-200 bg-slate-50 hover:border-indigo-300 hover:bg-slate-100"
                    }`}
                  >
                    <div className={`mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full border border-slate-100 bg-white shadow-sm transition-transform ${dragging ? "scale-110" : ""}`}>
                      <UploadCloud size={24} className={dragging ? "text-indigo-500" : "text-slate-400"} />
                    </div>
                    <p className="mb-1 text-sm font-semibold text-slate-700">Kéo thả thư mục bài nộp vào đây</p>
                    <p className="text-xs text-slate-500">Mỗi thư mục mang tên username và chứa một file <span className="font-mono text-slate-600">.zip</span> của thư mục lib</p>
                    <input
                      ref={fileRef}
                      type="file"
                      multiple
                      webkitdirectory=""
                      directory=""
                      className="hidden"
                      onChange={(event) => { addFiles(event.target.files); event.target.value = ""; }}
                    />
                  </div>
                </div>
              )}

              {/* Lỗi Upload */}
              {uploadErr && (
                <div className="flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-600">
                  <AlertCircle size={18} className="mt-0.5 shrink-0" />
                  <p className="text-sm font-medium">{uploadErr}</p>
                </div>
              )}

              {/* File bị bỏ qua — parseErrors giờ nằm TRONG phiên của từng bộ */}
              {parseErrors.length > 0 && (
                <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
                  <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-amber-800">
                    <AlertCircle size={16} /> {parseErrors.length} file bị bỏ qua
                  </h3>
                  <ul className="space-y-2">
                    {parseErrors.map((err, i) => {
                      const c = categorizeError(err);
                      const badge = ERROR_TONES[c.tone] || ERROR_TONES.slate;
                      return (
                        <li key={i} className="rounded-lg border border-amber-100 bg-white/70 p-2.5">
                          <div className="mb-1 flex items-center gap-2">
                            <span className={`rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide ${badge}`}>{c.type}</span>
                            {c.file && <span className="truncate font-mono text-xs text-slate-500">{c.file}</span>}
                          </div>
                          <p className="break-words text-xs text-slate-600">{c.detail}</p>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              )}

              {/* Đang xem bộ KHÁC trong khi một phiên còn chấm dở: chọn file trước được, nhưng
                  chưa bắt đầu được — mỗi lúc chỉ một bộ được chấm. */}
              {blockedByRunningSession && phase === "idle" && (
                <div className="flex items-start gap-2.5 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-amber-800">
                  <Clock size={15} className="mt-0.5 shrink-0" />
                  <p className="text-xs font-medium leading-relaxed">
                    Bộ <span className="font-mono font-bold">{runningExam}</span> đang được chấm.
                    Cứ chọn bài sẵn — đợi phiên đó hoàn tất là bấm chấm được ngay.
                  </p>
                </div>
              )}

              {/* Nút Execute — bấm ở phase done là mở PHIÊN MỚI thay phiên vừa kết thúc. */}
              {(phase === "idle" || phase === "done") && (
                <button
                  onClick={execute}
                  disabled={files.length === 0 || blockedByRunningSession}
                  title={blockedByRunningSession ? `Đang chấm bộ ${runningExam} — đợi xong mới chấm bộ khác` : undefined}
                  className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-indigo-600 to-blue-600 px-4 py-3.5 font-semibold text-white shadow-sm shadow-indigo-600/20 transition-all hover:from-indigo-700 hover:to-blue-700 active:scale-[0.98] disabled:cursor-not-allowed disabled:from-slate-300 disabled:to-slate-300 disabled:shadow-none"
                >
                  <Play size={18} />
                  Bắt đầu chấm ({files.length} bài)
                </button>
              )}

              {/* Loading State */}
              {phase === "uploading" && (
                <div className="rounded-xl border border-indigo-100 bg-indigo-50 p-6 text-center">
                  <Loader2 size={28} className="mx-auto mb-3 animate-spin text-indigo-600" />
                  <h3 className="mb-1 text-sm font-bold text-indigo-900">Đang tải dữ liệu lên...</h3>
                  <p className="text-xs text-indigo-700/80">Đang upload {files.length} thư mục bài thi lên server</p>
                </div>
              )}
            </div>
          </div>

          {/* Thêm bài vào phiên ĐANG CHẠY — thu bài nhiều đợt trong cùng một phiên. Chấm xong
              hết là phiên KẾT THÚC: lúc đó dùng lại form upload ở trên để mở phiên mới. */}
          {phase === "polling" && (
            <div className="card overflow-hidden">
              <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-5 py-3.5">
                <UploadCloud size={16} className="text-indigo-500" />
                <h3 className="text-sm font-bold text-slate-700">Thêm bài làm</h3>
              </div>
              <div className="space-y-3 p-4">
                <button
                  onClick={() => addFileRef.current?.click()}
                  disabled={addingFiles || blockedByRunningSession}
                  title={blockedByRunningSession ? `Đang chấm bộ ${runningExam} — đợi xong mới nạp thêm bài cho bộ này` : undefined}
                  className="flex w-full items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-200 bg-slate-50 px-4 py-4 text-sm font-semibold text-slate-600 transition-all hover:border-indigo-300 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {addingFiles
                    ? <><Loader2 size={16} className="animate-spin" /> Đang tải lên…</>
                    : <><UploadCloud size={16} className="text-slate-400" /> Chọn thêm thư mục bài nộp</>}
                </button>
                <p className="text-xs text-slate-500">
                  Bài mới được đưa vào cuối hàng đợi của phiên này. Sinh viên đã có kết quả mà nộp lại
                  thì kết quả cũ bị ghi đè.
                </p>
                {addNotice && (
                  <p className={`flex items-center gap-1.5 text-xs font-semibold ${
                    addNotice.type === "ok" ? "text-emerald-600" : "text-rose-600"
                  }`}>
                    {addNotice.type === "ok" ? <CheckCircle size={13} /> : <AlertCircle size={13} />}
                    {addNotice.text}
                  </p>
                )}
                <input
                  ref={addFileRef}
                  type="file"
                  multiple
                  webkitdirectory=""
                  directory=""
                  className="hidden"
                  onChange={(event) => { addMoreSubmissions(event.target.files); event.target.value = ""; }}
                />
              </div>
            </div>
          )}

          {/* Cấu hình tài nguyên Docker (CPU/RAM mỗi bài + số bài song song) */}
          <PerformanceSettings running={!!runningExam} />

          {/* Danh sách file đang chọn */}
          {files.length > 0 && phase === "idle" && (
            <div className="card flex max-h-[420px] flex-col overflow-hidden">
              <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/50 px-5 py-4">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold uppercase tracking-wider text-slate-500">Bài nộp đã chọn ({files.length})</span>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-500">{(totalSize / 1024 / 1024).toFixed(1)} MB</span>
                </div>
                <button onClick={() => setFilesFor(trimmedExam, [])} className="text-xs font-semibold text-rose-500 transition-colors hover:text-rose-700">Xóa hết</button>
              </div>
              <div className="custom-scrollbar overflow-y-auto p-2">
                {files.map((entry) => (
                  <div key={entry.key} className="group flex items-center gap-3 rounded-lg p-3 transition-colors hover:bg-slate-50">
                    <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-500">
                      <FileArchive size={15} />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium text-slate-700">{entry.username}</p>
                      <p className="truncate text-xs text-slate-400">{entry.file.name} · {(entry.file.size / 1024).toFixed(0)} KB</p>
                    </div>
                    <button onClick={() => removeFile(entry.key)} className="p-1 text-slate-300 opacity-0 transition-opacity hover:text-rose-500 group-hover:opacity-100">
                      <X size={16} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Cột phải: Tiến độ & Kết quả */}
        <div className="min-w-0 space-y-6">
          {/* Đặt NGOÀI thẻ tiến độ: sau khi Hủy, phase quay về "idle" nên thẻ đó không còn render */}
          {stopNotice && (
            <div className="flex items-start gap-2.5 rounded-xl border border-slate-200 bg-slate-50 p-4 text-slate-600">
              <Ban size={16} className="mt-0.5 shrink-0 text-slate-400" />
              <p className="text-xs font-medium leading-relaxed">{stopNotice}</p>
              <button onClick={() => setStopNotice(null)} className="ml-auto shrink-0 text-slate-300 transition-colors hover:text-slate-500">
                <X size={14} />
              </button>
            </div>
          )}
          {/* Banner phiên chạy ngầm — nằm NGOÀI ternary để hiện ở MỌI khung nhìn không phải phiên
              đó, phía trên các thẻ thống kê. Hai trạng thái: ĐANG chấm (indigo đậm) và VỪA chấm
              xong (emerald) — chấm xong không được lặng lẽ biến mất, chỉ đóng khi người dùng bấm X
              hoặc mở xem kết quả. */}
          {blockedByRunningSession ? (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-gradient-to-r from-indigo-600 to-blue-600 p-4 text-white shadow-lg shadow-indigo-600/25">
              <div className="flex items-start gap-3">
                <span className="relative mt-0.5 flex h-4 w-4 shrink-0">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-white/60"></span>
                  <Loader2 size={16} className="relative animate-spin" />
                </span>
                <div>
                  <p className="text-sm font-bold">
                    Bộ <span className="rounded bg-white/20 px-1.5 py-0.5 font-mono">{runningExam}</span> đang được chấm
                  </p>
                  <p className="mt-0.5 text-xs text-indigo-100">
                    {(runningSession?.progress?.done || 0) + (runningSession?.progress?.error || 0) + (runningSession?.progress?.manualReview || 0)}
                    /{runningSession?.progress?.total || 0} bài đã xử lý — phiên vẫn chạy khi bạn xem bộ khác.
                  </p>
                </div>
              </div>
              <button
                onClick={() => setExamId(runningExam)}
                className="rounded-lg bg-white px-3.5 py-2 text-xs font-bold text-indigo-700 shadow-sm transition-all hover:bg-indigo-50 active:scale-95"
              >
                Xem phiên này
              </button>
            </div>
          ) : finishedNotice && finishedNotice !== trimmedExam && sessions[finishedNotice] ? (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-500 p-4 text-white shadow-lg shadow-emerald-500/25">
              <div className="flex items-start gap-3">
                <CheckCircle size={17} className="mt-0.5 shrink-0" />
                <div>
                  <p className="text-sm font-bold">
                    Bộ <span className="rounded bg-white/20 px-1.5 py-0.5 font-mono">{finishedNotice}</span> đã chấm xong
                  </p>
                  <p className="mt-0.5 text-xs text-emerald-50">
                    {sessions[finishedNotice]?.progress?.done || 0}/{sessions[finishedNotice]?.progress?.total || 0} bài
                    có điểm{(sessions[finishedNotice]?.progress?.blocked || 0) > 0
                      ? ` · ${sessions[finishedNotice].progress.blocked} bài lỗi hệ thống`
                      : ""} — mở xem để tải kết quả hoặc chấm tiếp.
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setExamId(finishedNotice)}
                  className="rounded-lg bg-white px-3.5 py-2 text-xs font-bold text-emerald-700 shadow-sm transition-all hover:bg-emerald-50 active:scale-95"
                >
                  Xem kết quả
                </button>
                <button
                  onClick={() => setFinishedNotice(null)}
                  title="Đóng thông báo"
                  className="rounded-lg p-2 text-emerald-100 transition-colors hover:bg-white/15 hover:text-white"
                >
                  <X size={14} />
                </button>
              </div>
            </div>
          ) : null}
          {(phase === "polling" || phase === "done") ? (
            <>
              {/* Thống kê nhanh — cũng LÀ bộ lọc: bấm thẻ nào thì bảng dưới hiện đúng nhóm đó,
                  giống hệt menu lọc. Số lấy từ cùng một khai báo `rowFilters` nên không lệch.
                  File bị loại ngay khi upload không nằm ở đây; chúng có khối riêng "N file bị bỏ qua". */}
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                {rowFilters.map((f) => (
                  <StatCard
                    key={f.key}
                    label={f.key === "all" ? "Tổng số bài" : f.label}
                    value={f.count}
                    icon={STAT_ICON[f.key]}
                    tone={STAT_TONE[f.key]}
                    pulse={f.key === "grading" && f.count > 0}
                    active={rowFilter === f.key}
                    onClick={() => setRowFilter(f.key)}
                  />
                ))}
              </div>

              {/* Thanh tiến độ */}
              <div className="card p-6">
                <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
                  <div>
                    <h3 className="flex items-center gap-2 text-base font-bold text-slate-800">
                      <TrendingUp size={18} className="text-indigo-500" /> Tiến độ thực thi
                    </h3>
                    <p className="mt-1 text-xs font-medium text-slate-500">
                      Batch: <span className="rounded bg-slate-100 px-2 py-0.5 font-mono text-slate-700">{batchId}</span>
                    </p>
                  </div>
                  <div className="flex items-end gap-3 text-right">
                    <div>
                    <span className="text-3xl font-bold text-slate-800">{pct}<span className="text-lg text-slate-400">%</span></span>
                    {phase === "polling" && (
                      <p className={`flex items-center justify-end gap-1.5 text-xs font-semibold ${isPaused ? "text-amber-600" : "text-blue-600"}`}>
                        <span className="relative flex h-2 w-2">
                          {!isPaused && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-blue-400 opacity-75"></span>}
                          <span className={`relative inline-flex h-2 w-2 rounded-full ${isPaused ? "bg-amber-500" : "bg-blue-500"}`}></span>
                        </span>
                        {isPaused ? "Đã tạm dừng" : "Đang chạy"}
                      </p>
                    )}
                    </div>
                  </div>
                </div>

                <div className="flex h-3 w-full overflow-hidden rounded-full bg-slate-100">
                  <div className="h-full bg-gradient-to-r from-emerald-400 to-emerald-500 transition-all duration-500 ease-out" style={{ width: `${donePct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-amber-400 to-amber-500 transition-all duration-500 ease-out" style={{ width: `${blockedPct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-slate-300 to-slate-400 transition-all duration-500 ease-out" style={{ width: `${cancelPct}%` }}></div>
                </div>
                <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
                  <span>
                    <span className="font-semibold text-emerald-600">{doneItems}</span> có điểm
                    {(blockedItems + rejectedItems) > 0 && <> · <span className="font-semibold text-amber-600">{blockedItems + rejectedItems}</span> lỗi hệ thống</>}
                    {cancelledItems > 0 && <> · <span className="font-semibold text-slate-600">{cancelledItems}</span> đã dừng</>}
                  </span>
                  <span>{processedItems}/{totalItems} đã xử lý</span>
                </div>

                {(phase === "polling" || phase === "done") && (
                  <div className="mt-4 flex flex-wrap items-center justify-end gap-2 border-t border-slate-100 pt-4">
                    {phase === "polling" && (
                      <>
                        <button
                          type="button"
                          onClick={toggleBatchPause}
                          title={isPaused
                            ? "Tiếp tục đưa bài đang chờ vào máy chấm"
                            : "Tạm dừng sau khi các bài đang chạy hoàn tất"}
                          className="flex items-center gap-2 rounded-lg border border-indigo-200 bg-indigo-50 px-4 py-2 text-sm font-semibold text-indigo-700 transition-colors hover:bg-indigo-100"
                        >
                          {isPaused ? <Play size={15} /> : <Pause size={15} />}
                          {isPaused ? "Tiếp tục" : "Tạm dừng"}
                        </button>
                        <button
                          onClick={stopGrading}
                          disabled={batchAction !== null}
                          title="Ngừng chấm các bài còn lại, giữ kết quả đã có; vẫn nạp thêm bài chấm tiếp được"
                          className="flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-sm font-semibold text-amber-700 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {batchAction === "stop"
                            ? <Loader2 size={15} className="animate-spin" />
                            : <StopCircle size={15} />}
                          Dừng chấm
                        </button>
                      </>
                    )}
                    {phase === "done" && (
                      <button onClick={startNewView} className="rounded-lg bg-indigo-50 px-4 py-2 text-sm font-semibold text-indigo-600 transition-colors hover:bg-indigo-100">
                        + Chấm kỳ thi mới
                      </button>
                    )}
                  </div>
                )}
              </div>

              {/* Bảng kết quả */}
              <div className="card min-w-0 overflow-hidden">
                <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/50 px-6 py-4">
                  <h3 className="text-sm font-bold uppercase tracking-wider text-slate-700">Chi tiết kết quả</h3>
                  <button
                    onClick={downloadResultsFolder}
                    disabled={!allResultRows.length}
                    title="Xuất thư mục gồm một JSON cho mỗi sinh viên"
                    className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    <FileJson size={16} /> Xuất JSON
                  </button>
                </div>

                {/* Sự cố HỆ THỐNG — thứ duy nhất người chấm phải xử lý. Bài 0 điểm do sinh viên
                    làm sai không xuất hiện ở đây: điểm số đã nói hết. Không có sự cố thì cả khối
                    này biến mất, màn hình sạch. */}
                {incidents.length > 0 && (
                  <div className="border-b border-amber-100 bg-amber-50 px-6 py-4">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="flex items-start gap-2">
                        <AlertCircle size={16} className="mt-0.5 shrink-0 text-amber-600" />
                        <div>
                          <p className="text-sm font-bold text-amber-900">
                            {blockedItems} bài chưa có điểm do sự cố hệ thống
                          </p>
                          <p className="text-xs text-amber-700">
                            Máy chấm không cho ra kết quả tin được — cần xử lý rồi chấm lại.
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <button
                          onClick={regradeBlocked}
                          disabled={regrading || !!runningExam || !blockedStudentIds.length}
                          title="Chấm lại đúng nhóm bài bị sự cố, sau khi đã xử lý nguyên nhân"
                          className="flex items-center gap-2 rounded-lg border border-amber-300 bg-white px-3 py-1.5 text-xs font-semibold text-amber-800 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {regrading ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
                          Chấm lại {blockedStudentIds.length} bài
                        </button>
                      </div>
                    </div>

                    <ul className="mt-3 space-y-1.5">
                      {incidents.map((incident) => (
                        <li
                          key={incident.code}
                          className="flex flex-wrap items-baseline gap-x-2 rounded-lg border border-amber-200 bg-white px-3 py-2 text-xs"
                          title={incident.message || ""}
                        >
                          <span className="font-bold text-amber-900">{incident.count} bài</span>
                          <span className="text-slate-700">· {incident.label}</span>
                          <span className="text-slate-400">· {incident.originLabel}</span>
                          <span className="ml-auto truncate font-mono text-[10px] text-slate-400">{incident.code}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {/* Bộ lọc CỐ ĐỊNH, luôn thấy — kể cả khi lô hiện tại sạch. Gom vào một menu thay
                    vì rải nút: bốn nhóm là bốn lựa chọn loại trừ nhau, menu nói rõ điều đó và
                    không chiếm thêm bề ngang khi số nhóm tăng. */}
                <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 bg-white px-6 py-2.5">
                  <div ref={filterRef} className="relative">
                    <button
                      type="button"
                      onClick={() => setFilterOpen((v) => !v)}
                      aria-haspopup="listbox"
                      aria-expanded={filterOpen}
                      className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
                    >
                      <ListFilter size={14} className="text-slate-400" />
                      {activeFilter.label} ({activeFilter.count})
                      <ChevronDown size={14} className={`text-slate-400 transition-transform ${filterOpen ? "rotate-180" : ""}`} />
                    </button>
                    {filterOpen && (
                      <ul
                        role="listbox"
                        className="absolute left-0 top-full z-30 mt-1 w-56 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-xl"
                      >
                        {rowFilters.map((f) => (
                          <li key={f.key}>
                            <button
                              type="button"
                              role="option"
                              aria-selected={rowFilter === f.key}
                              onClick={() => { setRowFilter(f.key); setFilterOpen(false); }}
                              className={`flex w-full items-center justify-between px-3 py-2 text-left text-xs font-semibold transition-colors ${
                                rowFilter === f.key
                                  ? "bg-indigo-50 text-indigo-700"
                                  : "text-slate-600 hover:bg-slate-50"
                              }`}
                            >
                              <span>{f.label}</span>
                              <span className="font-mono text-[11px] text-slate-400">{f.count}</span>
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                  <span className="ml-auto text-xs font-medium text-slate-400">
                    Hiển thị {filteredResultRows.length}/{allResultRows.length} bài
                  </span>
                </div>

                {/* Vừa trong một màn: bỏ min-w cứng 900px (thứ ép kéo ngang ở cột hẹp) và chia
                    lại tỉ lệ theo lượng chữ thật của từng cột. Vẫn giữ overflow-x-auto làm lưới
                    an toàn cho màn rất hẹp, nhưng ở bố cục mới nó không còn kích hoạt. */}
                <div className="max-w-full overflow-x-auto">
                  <table className="w-full min-w-[680px] table-fixed border-collapse text-left">
                    <colgroup>
                      <col className="w-[22%]" />
                      <col className="w-[18%]" />
                      <col className="w-[13%]" />
                      <col className="w-[24%]" />
                      <col className="w-[13%]" />
                      <col className="w-[10%]" />
                    </colgroup>
                    <thead>
                      <tr className="border-b border-slate-100 bg-white text-xs font-bold uppercase tracking-wider text-slate-500">
                        <th className="px-3 py-3.5 text-center">Sinh viên</th>
                        <th className="px-3 py-3.5 text-center">Trạng thái</th>
                        <th className="px-3 py-3.5 text-center">Tỉ lệ Pass</th>
                        <th className="px-3 py-3.5 text-center">Sự cố hệ thống</th>
                        <th className="px-3 py-3.5 text-center">Điểm số</th>
                        <th className="px-3 py-3.5 text-center">Thao tác</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {filteredResultRows.map((r) => {
                        let passCount = 0, totalCount = 0;
                        try {
                          const d = JSON.parse(r.details || "{}");
                          passCount = d.soTestPass ?? 0;
                          totalCount = d.tongSoTest ?? 0;
                        } catch (_) {}

                        const isDone = r.outcome === "SCORED";
                        const tone = gradingStatusTone(r.status, r.outcome);
                        const ratio = totalCount > 0 ? Math.round((passCount / totalCount) * 100) : 0;
                        const initials = (r.studentName || r.studentId || "?").trim().charAt(0).toUpperCase();
                        const diagnosticScope = r.diagnosticOrigin === "STUDENT" ? "Bài sinh viên" :
                          r.diagnosticOrigin === "TESTCASE" ? "Bộ testcase" :
                          r.diagnosticOrigin === "ENVIRONMENT" ? "Môi trường chấm" :
                          r.diagnosticOrigin === "UNDETERMINED" ? "Chưa xác định" : "";
                        const blocked = isBlocked(r);

                        return (
                          <tr key={r.id} className="transition-colors hover:bg-slate-50/70">
                            <td className="px-3 py-3.5 text-center">
                              <div className="flex items-center gap-3">
                                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-slate-200 text-xs font-bold text-slate-500">
                                  {initials}
                                </div>
                                <div className="min-w-0">
                                  <p className="truncate text-sm font-semibold text-slate-800">{r.studentName || "—"}</p>
                                  <p className="font-mono text-xs text-slate-400">{r.studentId}</p>
                                </div>
                              </div>
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${tone.pill}`}>
                                <span className={`h-1.5 w-1.5 rounded-full ${tone.dot}`}></span>
                                {gradingStatusLabel(r.status, r.outcome)}
                              </span>
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              {(isDone || totalCount > 0) ? (
                                <div className="flex items-center gap-2">
                                  <div className="h-1.5 min-w-8 flex-1 overflow-hidden rounded-full bg-slate-100">
                                    <div className={`h-full rounded-full ${ratio >= 50 ? 'bg-emerald-500' : 'bg-amber-500'}`} style={{ width: `${ratio}%` }}></div>
                                  </div>
                                  <span className="text-xs font-medium text-slate-500">{passCount}/{totalCount}</span>
                                </div>
                              ) : <span className="text-slate-300">—</span>}
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              {blocked ? (
                                <div
                                  className="w-full min-w-0 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-amber-800"
                                  title={[r.diagnosticCode, diagnosticScope, r.diagnosticStage, r.errorLog].filter(Boolean).join(" · ")}
                                >
                                  <div className="flex items-center gap-1.5 text-xs font-bold">
                                    <AlertCircle size={13} className="shrink-0" />
                                    <span className="truncate">
                                      {incidentLabelByCode[r.diagnosticCode] || "Sự cố chưa xác định nguồn"}
                                    </span>
                                  </div>
                                  <p className="mt-1 truncate font-mono text-[10px] opacity-80">
                                    {r.diagnosticCode || "CHƯA PHÂN LOẠI"}
                                  </p>
                                  {(diagnosticScope || r.diagnosticStage) && (
                                    <p className="mt-0.5 truncate text-[10px] font-medium opacity-80">
                                      {[diagnosticScope, formatDiagnosticStage(r.diagnosticStage)].filter(Boolean).join(" · ")}
                                    </p>
                                  )}
                                </div>
                              ) : (
                                <span className="text-xs text-slate-300">—</span>
                              )}
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              {r.score != null ? (
                                <span
                                  className={`inline-block rounded-lg px-2.5 py-1 text-sm font-bold ${r.score >= PASS_THRESHOLD ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'}`}
                                  title={r.score === 0 && r.diagnosticCode
                                    ? `${r.diagnosticCode} · ${r.errorLog || ""}`
                                    : undefined}
                                >
                                  {r.score.toFixed(1)}
                                </span>
                              ) : (
                                <span className="font-medium text-slate-300">—</span>
                              )}
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              <button
                                onClick={() => downloadStudentJson(r)}
                                title="Tải JSON kết quả của sinh viên này"
                                className="inline-flex items-center justify-center rounded-lg border border-slate-200 p-1.5 text-slate-500 transition-colors hover:border-indigo-300 hover:text-indigo-600"
                              >
                                <FileJson size={15} />
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                      {allResultRows.length === 0 && (
                        <tr>
                          <td colSpan="6" className="px-6 py-10 text-center text-sm text-slate-500">
                            <Loader2 size={20} className="mx-auto mb-2 animate-spin text-slate-300" />
                            Đang chờ dữ liệu...
                          </td>
                        </tr>
                      )}
                      {allResultRows.length > 0 && filteredResultRows.length === 0 && (
                        <tr>
                          <td colSpan="6" className="px-6 py-10 text-center text-sm">
                            {/* Lọc "Lỗi hệ thống" mà rỗng là TIN TỐT, không phải kết quả trống —
                                nói thẳng ra thay vì để người chấm tự suy từ một bảng trắng. */}
                            {rowFilter === "blocked" ? (
                              <span className="inline-flex items-center gap-1.5 font-semibold text-emerald-600">
                                <CheckCircle size={15} /> Không có bài nào lỗi do hệ thống.
                              </span>
                            ) : rowFilter === "grading" ? (
                              <span className="text-slate-500">Không còn bài nào đang chờ hoặc đang chấm.</span>
                            ) : rowFilter === "scored" ? (
                              <span className="text-slate-500">Chưa có bài nào chấm xong.</span>
                            ) : (
                              <span className="text-slate-500">Không có bài nào phù hợp với bộ lọc hiện tại.</span>
                            )}
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          ) : (
            <div className="space-y-4">
              <div className="flex h-full min-h-[320px] flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center backdrop-blur-sm">
                <div className="mb-4 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-blue-50 text-indigo-400">
                  <BarChart2 size={36} />
                </div>
                <h3 className="mb-2 text-base font-bold text-slate-700">Chưa có phiên chấm bài nào cho bộ này</h3>
                <p className="max-w-sm text-sm text-slate-500">Chọn mã bộ testcase và upload các thư mục username có chứa một file .zip của thư mục lib để bắt đầu chấm điểm tự động.</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </SidebarLayout>
  );
}

// ── Phân loại lỗi để hiển thị rõ "loại lỗi" ──────────────────────
const ERROR_TONES = {
  amber: "bg-amber-100 text-amber-700",
  rose: "bg-rose-100 text-rose-700",
  blue: "bg-blue-100 text-blue-700",
  slate: "bg-slate-100 text-slate-600",
};

function categorizeError(errStr) {
  const s = String(errStr ?? "");
  const idx = s.indexOf(": ");
  const file = idx > 0 ? s.slice(0, idx) : "";
  const msg = (idx > 0 ? s.slice(idx + 2) : s).trim();

  if (/trùng mã sv|cùng lần upload|cùng lần nộp/i.test(msg))
    return { file, type: "Trùng trong lần nộp", detail: "Mã SV xuất hiện nhiều lần trong cùng một lần upload — chỉ giữ 1 bài.", tone: "amber" };
  if (/sai format|username|\.zip|định dạng/i.test(msg))
    return { file, type: "Sai cấu trúc thư mục", detail: "Mỗi thư mục username phải chứa một file .zip của thư mục lib.", tone: "amber" };
  if (/chỉ nhận|file rỗng|quá 50mb|rỗng/i.test(msg))
    return { file, type: "File không hợp lệ", detail: msg, tone: "rose" };
  if (/duplicate entry|đã có kết quả/i.test(msg))
    return { file, type: "Đã chấm trước đó", detail: "Mã SV này đã có kết quả cho bộ testcase (giờ sẽ tự ghi đè khi chấm lại).", tone: "blue" };
  if (/sql|constraint|could not execute|statement|database/i.test(msg))
    return { file, type: "Lỗi hệ thống (DB)", detail: "Lỗi khi lưu vào cơ sở dữ liệu.", tone: "rose" };
  return { file, type: "Lỗi khác", detail: msg || "Không xác định", tone: "slate" };
}

// Icon/màu của 4 thẻ thống kê, khớp theo khoá bộ lọc (xem `rowFilters`).
const STAT_ICON = { all: Users, scored: CheckCircle, grading: Clock, blocked: AlertCircle };
const STAT_TONE = { all: "slate", scored: "emerald", grading: "blue", blocked: "amber" };

// ── Thẻ thống kê nhỏ, tái sử dụng ─────────────────────────────────
function StatCard({ label, value, icon: Icon, tone, pulse, active, onClick }) {
  const tones = {
    slate:   { text: "text-slate-800",  badge: "bg-slate-100 text-slate-500",     border: "border-slate-200" },
    emerald: { text: "text-emerald-600", badge: "bg-emerald-100 text-emerald-600", border: "border-emerald-100" },
    blue:    { text: "text-blue-600",    badge: "bg-blue-100 text-blue-600",       border: "border-blue-100" },
    amber:   { text: "text-amber-600",   badge: "bg-amber-100 text-amber-700",     border: "border-amber-100" },
    rose:    { text: "text-rose-600",    badge: "bg-rose-100 text-rose-600",       border: "border-rose-100" },
  };
  const t = tones[tone] || tones.slate;
  const body = (
    <>
      <div className="mb-3 flex items-center justify-between">
        <p className="eyebrow">{label}</p>
        <span className={`flex h-8 w-8 items-center justify-center rounded-lg ${t.badge} ${pulse ? "animate-pulse" : ""}`}>
          <Icon size={16} />
        </span>
      </div>
      <p className={`text-3xl font-bold tracking-tight ${t.text}`}>{value}</p>
    </>
  );
  if (!onClick) return <div className="card card-hover p-5">{body}</div>;
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={!!active}
      title={`Chỉ hiện nhóm: ${label}`}
      // Viền đậm + nền nhạt khi đang lọc theo thẻ này — nếu không có dấu hiệu, người dùng bấm
      // xong thấy bảng đổi mà không biết vì sao.
      className={`card card-hover p-5 text-left transition-all ${
        active ? "ring-2 ring-indigo-400 ring-offset-1" : "hover:-translate-y-0.5"
      }`}
    >
      {body}
    </button>
  );
}
