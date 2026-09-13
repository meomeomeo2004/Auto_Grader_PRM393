"use client";

import React, { useEffect, useState, useRef, useCallback } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  FileText, FileCode2, CheckSquare, BarChart2, Bell, Search,
  GraduationCap, Loader2, History, PanelLeftClose,
  Clock, CheckCircle2, AlertCircle, BookOpen, Package, Pause,
  Bot, ChevronDown, Sparkles, FlaskConical,
} from 'lucide-react';
import { clsx } from 'clsx';
import { API_BASE } from '@/lib/config';
import ThemeToggle from '@/components/layout/ThemeToggle';

interface SidebarLayoutProps {
  children: React.ReactNode;
  activePath?: string;
  title: string;
  subtitle?: string;
  contentClassName?: string;
}

interface BatchNotif {
  batchId: string;
  examId: string;
  status: string;
  totalFiles: number;
  doneCount: number;
  errorCount: number;
  createdAt: string;
}
interface SearchRow {
  examId: string;
  studentId: string;
  studentName: string | null;
  score: number | null;
  status: string;
}

interface NavLeaf { name: string; path: string; icon: React.ElementType }
interface NavGroup { name: string; icon: React.ElementType; children: NavLeaf[] }
type NavEntry = NavLeaf | NavGroup;
const isGroup = (e: NavEntry): e is NavGroup => 'children' in e;

const PRIMARY_NAV: NavEntry[] = [
  { name: 'Thống kê', path: '/statistics', icon: BarChart2 },
  {
    name: 'Chấm bài', icon: CheckSquare, children: [
      { name: 'Chấm tự động', path: '/teacher/grading', icon: Bot },
      { name: 'Chấm thủ công', path: '/teacher/workspace', icon: FileText },
      { name: 'Lịch sử chấm', path: '/history', icon: History },
    ],
  },
  { name: 'Thư viện chấm', path: '/teacher/libraries', icon: Package },
  { name: 'Tạo đề', path: '/teacher/exam-authoring', icon: Sparkles },
  { name: 'Tạo Golden', path: '/teacher/golden-authoring', icon: FlaskConical },
  // Vào thẳng trang Kho — mọi thao tác (tạo/sửa/xóa/chấm lại) đều là nút trong trang đó.
  { name: 'Bộ chấm Golden', path: '/teacher/archive', icon: FileCode2 },
  { name: 'Khung năng lực', path: '/syllabus', icon: BookOpen },
  // "Nhận xét AI" không còn là trang riêng: sinh/xem feedback nằm ngay trang Lịch sử chấm.
];

/** Diễn giải trạng thái 1 phiên chấm cho thông báo. */
function notifStatus(n: BatchNotif) {
  const done = n.doneCount || 0, err = n.errorCount || 0, total = n.totalFiles || 0;
  if (n.status === 'IN_PROGRESS') return { Icon: Clock, tone: 'text-blue-500', text: `Đang chấm ${done + err}/${total}` };
  if (n.status === 'PAUSED') return { Icon: Pause, tone: 'text-amber-500', text: `Tạm dừng ${done + err}/${total}` };
  if (n.status === 'COMPLETED') return { Icon: CheckCircle2, tone: 'text-emerald-500', text: `Hoàn tất ${done}/${total} bài` };
  if (n.status === 'PARTIAL') return { Icon: AlertCircle, tone: 'text-amber-500', text: `Xong ${done}/${total}, ${err} lỗi` };
  if (n.status === 'CANCELLED') return { Icon: AlertCircle, tone: 'text-slate-400', text: `Đã dừng — xong ${done}/${total}` };
  return { Icon: AlertCircle, tone: 'text-slate-400', text: `${done + err}/${total}` };
}

export default function SidebarLayout({ children, activePath = '/', title, subtitle, contentClassName }: SidebarLayoutProps) {
  const router = useRouter();

  // ── UI state ──────────────────────────────────────────────────
  // Lần render đầu trên server và client phải giống nhau để React hydrate ổn định.
  // Khôi phục lựa chọn đã lưu sau khi component được mount trong trình duyệt.
  const [collapsed, setCollapsed] = useState(false);
  // Nhóm menu đang xổ. Mở sẵn nhóm chứa trang hiện tại để chuyển trang không bị "sập" menu.
  const [openGroups, setOpenGroups] = useState<string[]>(() =>
    PRIMARY_NAV.filter((e) => isGroup(e) && e.children.some((c) => c.path === activePath))
      .map((e) => e.name)
  );
  const [notifOpen, setNotifOpen] = useState(false);
  const [notifs, setNotifs] = useState<BatchNotif[]>([]);
  const [lastSeen, setLastSeen] = useState(0);
  const [q, setQ] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [results, setResults] = useState<SearchRow[]>([]);
  const [searching, setSearching] = useState(false);

  const notifRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchSeq = useRef(0);   // chống response cũ ghi đè response mới

  // Khôi phục trạng thái chỉ tồn tại trong localStorage sau hydration.
  useEffect(() => {
    try {
      setCollapsed(localStorage.getItem("sidebar_collapsed") === "1");
      setLastSeen(Number(localStorage.getItem("notif_last_seen") || 0));
    } catch { /* bỏ qua */ }
  }, []);

  // Nạp thông báo (phiên chấm gần đây) + tự làm mới mỗi 30s
  const loadNotifs = useCallback(() => {
    fetch(`${API_BASE}/batch/recent`)
      .then((r) => r.json())
      .then((d) => setNotifs(Array.isArray(d) ? d : []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    loadNotifs();
    const id = setInterval(loadNotifs, 30000);
    return () => clearInterval(id);
  }, [loadNotifs]);

  // Đóng dropdown khi click ra ngoài
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) setNotifOpen(false);
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) setSearchOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => {
      document.removeEventListener("mousedown", handler);
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  const toggleCollapse = () => {
    setCollapsed((c) => {
      const n = !c;
      try { localStorage.setItem("sidebar_collapsed", n ? "1" : "0"); } catch { /* bỏ qua */ }
      return n;
    });
  };

  const unread = notifs.filter(
    (n) => n.createdAt && new Date(n.createdAt).getTime() > lastSeen
  ).length;

  const toggleNotif = () => {
    setNotifOpen((o) => {
      const open = !o;
      if (open && notifs.length) {
        const latest = Math.max(...notifs.map((x) => (x.createdAt ? new Date(x.createdAt).getTime() : 0)));
        setLastSeen(latest);
        try { localStorage.setItem("notif_last_seen", String(latest)); } catch { /* bỏ qua */ }
      }
      return open;
    });
  };

  const onSearchChange = (val: string) => {
    setQ(val);
    setSearchOpen(true);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (val.trim().length < 1) { setResults([]); setSearching(false); return; }
    setSearching(true);
    const seq = ++searchSeq.current;
    debounceRef.current = setTimeout(() => {
      fetch(`${API_BASE}/results/search?q=${encodeURIComponent(val.trim())}`)
        .then((r) => (r.ok ? r.json() : []))
        .then((d) => { if (seq === searchSeq.current) setResults(Array.isArray(d) ? d : []); })
        .catch(() => { if (seq === searchSeq.current) setResults([]); })
        .finally(() => { if (seq === searchSeq.current) setSearching(false); });
    }, 300);
  };

  // Bấm một kết quả = MỞ BÀI LÀM của sinh viên đó ở trang Chấm thủ công (xem code + chấm tay),
  // không phải nhảy về bảng Lịch sử rồi tự tìm lại lần nữa. Lệnh truyền qua localStorage + event
  // (không qua query string) để cả khi ĐANG đứng ở trang đó, bài vẫn được mở — điều hướng cùng
  // route chỉ đổi query sẽ không remount trang.
  const gotoResult = (r: SearchRow) => {
    setSearchOpen(false);
    setQ("");
    setResults([]);
    try {
      localStorage.setItem("grader_open_submission", JSON.stringify({ examId: r.examId, studentId: r.studentId }));
    } catch { /* bỏ qua */ }
    window.dispatchEvent(new Event("grader:open-submission"));
    router.push("/teacher/workspace");
  };

  const onSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (results.length) gotoResult(results[0]);
    else if (q.trim()) { setSearchOpen(false); router.push(`/history?q=${encodeURIComponent(q.trim())}`); }
  };

  // Bấm nhóm khi menu đang thu gọn thì mở rộng luôn — nếu không, danh sách con xổ ra
  // sẽ nằm ngoài bề ngang 80px và người dùng không thấy gì.
  const toggleGroup = (name: string) => {
    if (collapsed) {
      setCollapsed(false);
      try { localStorage.setItem('sidebar_collapsed', '0'); } catch { /* bỏ qua */ }
      setOpenGroups((g) => (g.includes(name) ? g : [...g, name]));
      return;
    }
    setOpenGroups((g) => (g.includes(name) ? g.filter((x) => x !== name) : [...g, name]));
  };

  // Vùng icon CỐ ĐỊNH (w-16) → icon luôn ở 1 vị trí, không "nhảy" khi đóng/mở.
  // Chữ luôn render, chỉ fade opacity + bị panel che dần khi thu gọn → trượt mượt.
  const renderLink = (item: NavLeaf, nested = false) => {
    const isActive = activePath === item.path;
    return (
      <Link
        key={item.name}
        href={item.path}
        title={collapsed ? item.name : undefined}
        className={clsx(
          'group relative flex items-center overflow-hidden rounded-lg font-medium transition-colors',
          nested ? 'h-10 text-[13px]' : 'h-11 text-sm',
          isActive ? 'bg-indigo-500/10 text-white' : 'text-slate-400 hover:bg-slate-800/70 hover:text-white'
        )}
      >
        {isActive && (
          <span className="absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-indigo-400" />
        )}
        <span className={clsx('flex shrink-0 items-center justify-center', nested ? 'w-11' : 'w-16')}>
          <item.icon
            size={nested ? 16 : 18}
            className={clsx('transition-colors', isActive ? 'text-indigo-300' : 'text-slate-500 group-hover:text-slate-300')}
          />
        </span>
        <span className={clsx('truncate whitespace-nowrap pr-3 transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>
          {item.name}
        </span>
      </Link>
    );
  };

  const renderGroup = (group: NavGroup) => {
    const open = openGroups.includes(group.name);
    const hasActive = group.children.some((c) => c.path === activePath);
    return (
      <div key={group.name}>
        <button
          type="button"
          onClick={() => toggleGroup(group.name)}
          title={collapsed ? group.name : undefined}
          aria-expanded={open}
          className={clsx(
            'group relative flex h-11 w-full items-center overflow-hidden rounded-lg text-sm font-medium transition-colors',
            hasActive ? 'text-white' : 'text-slate-400 hover:bg-slate-800/70 hover:text-white',
            hasActive && !open && 'bg-indigo-500/10'
          )}
        >
          <span className="flex w-16 shrink-0 items-center justify-center">
            <group.icon
              size={18}
              className={clsx('transition-colors', hasActive ? 'text-indigo-300' : 'text-slate-500 group-hover:text-slate-300')}
            />
          </span>
          <span className={clsx('truncate whitespace-nowrap transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>
            {group.name}
          </span>
          <ChevronDown
            size={15}
            className={clsx(
              'ml-auto mr-3 shrink-0 transition-all duration-200',
              open && 'rotate-180',
              collapsed ? 'opacity-0' : 'opacity-100'
            )}
          />
        </button>
        {open && !collapsed && (
          <div className="mt-1 ml-7 space-y-0.5 border-l border-white/10 pl-1">
            {group.children.map((child) => renderLink(child, true))}
          </div>
        )}
      </div>
    );
  };

  const renderEntry = (entry: NavEntry) => (isGroup(entry) ? renderGroup(entry) : renderLink(entry));

  return (
    <div className="flex h-screen w-full overflow-hidden bg-slate-50 font-sans text-slate-800">
      {/* Sidebar */}
      <aside
        className={clsx(
          'z-20 flex shrink-0 flex-col overflow-hidden bg-[#0b1120] text-slate-300 shadow-xl transition-[width] duration-300 ease-in-out',
          collapsed ? 'w-20' : 'w-64'
        )}
      >
        {/* Brand + nút thu gọn */}
        <div className="flex h-16 shrink-0 items-center overflow-hidden border-b border-white/5">
          <button
            type="button"
            onClick={toggleCollapse}
            title={collapsed ? 'Mở rộng menu' : 'Thu gọn menu'}
            className="flex h-16 w-20 shrink-0 items-center justify-center"
          >
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 shadow-lg shadow-indigo-600/30 ring-1 ring-white/10 transition-transform hover:scale-105">
              <GraduationCap size={20} className="text-white" />
            </span>
          </button>
          <div className={clsx('min-w-0 flex-1 whitespace-nowrap leading-tight transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>
            <div className="text-[15px] font-bold tracking-wide text-white">Grader</div>
            <div className="text-[10px] font-medium uppercase tracking-[0.18em] text-slate-500">Auto-grading</div>
          </div>
          <button
            type="button"
            onClick={toggleCollapse}
            title="Thu gọn menu"
            className={clsx('mr-3 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-slate-400 transition-all hover:bg-slate-800/70 hover:text-white', collapsed ? 'pointer-events-none opacity-0' : 'opacity-100')}
          >
            <PanelLeftClose size={18} />
          </button>
        </div>

        <div className="custom-scrollbar flex-1 overflow-y-auto px-2 py-6">
          <nav className="space-y-1">{PRIMARY_NAV.map(renderEntry)}</nav>
        </div>
      </aside>

      {/* Main Content */}
      <div className="relative flex flex-1 flex-col overflow-hidden">
        {/* Top Navbar */}
        <header className="z-10 flex h-16 shrink-0 items-center justify-between border-b border-slate-200 bg-white/90 px-8 backdrop-blur-sm">
          <div className="min-w-0">
            <h1 className="truncate text-lg font-bold text-slate-900">{title}</h1>
            {subtitle && <p className="truncate text-xs text-slate-500">{subtitle}</p>}
          </div>
          <div className="flex items-center gap-5">
            {/* Thanh tìm kiếm */}
            <div ref={searchRef} className="relative hidden md:block">
              <form onSubmit={onSearchSubmit}>
                <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="text"
                  value={q}
                  onChange={(e) => onSearchChange(e.target.value)}
                  onFocus={() => q.trim() && setSearchOpen(true)}
                  placeholder="Tìm mã bộ testcase, sinh viên..."
                  className="w-64 rounded-full border border-transparent bg-slate-100 py-2 pl-9 pr-4 text-sm outline-none transition-all focus:border-indigo-400 focus:bg-white focus:ring-2 focus:ring-indigo-100"
                />
              </form>
              {searchOpen && q.trim().length > 0 && (
                <div className="absolute left-0 top-full z-30 mt-2 w-80 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl">
                  {searching ? (
                    <div className="p-4 text-center text-xs text-slate-400">
                      <Loader2 size={14} className="mr-1 inline animate-spin" /> Đang tìm...
                    </div>
                  ) : results.length === 0 ? (
                    <div className="p-4 text-center text-xs text-slate-400">Không tìm thấy kết quả</div>
                  ) : (
                    <ul className="max-h-80 overflow-y-auto py-1">
                      {results.map((r, i) => (
                        <li key={`${r.examId}-${r.studentId}-${i}`}>
                          <button
                            onClick={() => gotoResult(r)}
                            className="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-slate-50"
                          >
                            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-slate-200 text-xs font-bold text-slate-500">
                              {(r.studentName || r.studentId || '?').charAt(0).toUpperCase()}
                            </div>
                            <div className="min-w-0 flex-1">
                              <p className="truncate text-sm font-medium text-slate-700">{r.studentName || r.studentId}</p>
                              <p className="truncate font-mono text-xs text-slate-400">{r.studentId} · {r.examId}</p>
                            </div>
                            {r.score != null && (
                              <span className="shrink-0 text-xs font-bold text-slate-500">{r.score.toFixed(1)}</span>
                            )}
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>

            {/* Nút đổi giao diện sáng/tối — hiển thị trên mọi trang dùng layout này */}
            <ThemeToggle />

            {/* Chuông thông báo */}
            <div ref={notifRef} className="relative">
              <button
                onClick={toggleNotif}
                className="relative flex h-9 w-9 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 transition-all hover:border-indigo-300 hover:text-indigo-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:text-indigo-300"
                title="Thông báo"
              >
                <Bell size={18} />
                {unread > 0 && (
                  <span className="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white ring-2 ring-white dark:ring-slate-800">
                    {unread > 9 ? '9+' : unread}
                  </span>
                )}
              </button>
              {notifOpen && (
                <div className="absolute right-0 top-full z-30 mt-2 w-80 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl">
                  <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
                    <h4 className="text-sm font-bold text-slate-700">Thông báo</h4>
                    <span className="text-[11px] text-slate-400">{notifs.length} phiên gần đây</span>
                  </div>
                  {notifs.length === 0 ? (
                    <div className="p-6 text-center text-xs text-slate-400">Chưa có phiên chấm nào</div>
                  ) : (
                    <ul className="max-h-96 overflow-y-auto py-1">
                      {notifs.map((n) => {
                        const s = notifStatus(n);
                        return (
                          <li key={n.batchId}>
                            <button
                              onClick={() => { setNotifOpen(false); router.push(`/history?exam=${encodeURIComponent(n.examId)}`); }}
                              className="flex w-full items-start gap-3 px-4 py-2.5 text-left transition-colors hover:bg-slate-50"
                            >
                              <s.Icon size={18} className={clsx('mt-0.5 shrink-0', s.tone)} />
                              <div className="min-w-0 flex-1">
                                <p className="truncate text-sm font-semibold text-slate-700">{n.examId}</p>
                                <p className="text-xs text-slate-500">{s.text}</p>
                                <p className="mt-0.5 text-[11px] text-slate-400">
                                  {n.createdAt ? new Date(n.createdAt).toLocaleString('vi-VN') : ''}
                                </p>
                              </div>
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
              )}
            </div>

          </div>
        </header>

        {/* Page Content */}
        <main className="custom-scrollbar flex-1 overflow-y-auto bg-slate-50 p-8">
          <div className={clsx('mx-auto w-full max-w-6xl animate-fade-in-up', contentClassName)}>{children}</div>
        </main>
      </div>
    </div>
  );
}
