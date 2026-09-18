"use client";

import React, { useEffect, useState, useRef, useCallback } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  FileCode2, CheckSquare, Bell, FileArchive,
  GraduationCap, History, PanelLeftClose,
  Clock, CheckCircle2, AlertCircle, Package, Pause,
  Bot, ChevronDown, Sparkles, FileText,
} from 'lucide-react';
import { clsx } from 'clsx';
import { API_BASE } from '@/lib/config';
import { VAI, Vai, laGiangVien, laNguoiCham } from '@/lib/vai';
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
/**
 * Thanh trên báo cho trang Lịch sử chấm biết cần mở bộ nào.
 *
 * <p>Đổi query trên CÙNG một route thì Next không remount trang, nên effect đọc query của trang
 * Lịch sử không chạy lại — đang đứng sẵn ở đó mà bấm một thông báo thì màn hình đứng im. Sự
 * kiện này là đường báo cho đúng ca đó.
 */
export const MO_LICH_SU = 'grader:mo-lich-su';
export interface MoLichSuDetail { examId: string; studentId?: string }

// `vai` để trống nghĩa là cả hai bản đều có mục này.
interface NavLeaf { name: string; path: string; icon: React.ElementType; vai?: Vai }
interface NavGroup { name: string; icon: React.ElementType; vai?: Vai; children: NavLeaf[] }
type NavEntry = NavLeaf | NavGroup;
const isGroup = (e: NavEntry): e is NavGroup => 'children' in e;

const TOAN_BO_NAV: NavEntry[] = [
  {
    name: 'Chấm bài', icon: CheckSquare, vai: 'nc', children: [
      { name: 'Chấm tự động', path: '/teacher/grading', icon: Bot },
      { name: 'Lịch sử chấm', path: '/history', icon: History },
    ],
  },
  // Bản người chấm không soạn được bộ nào: bộ chấm chỉ vào bằng gói .zip giảng viên gửi, nên
  // nơi nhận gói phải là một màn riêng chứ không phải một góc của màn Chấm tự động.
  { name: 'Quản lý bộ testcase', path: '/teacher/testcases', icon: FileArchive, vai: 'nc' },
  { name: 'Thư viện chấm', path: '/teacher/libraries', icon: Package },
  // Soạn đề bằng AI là việc của giảng viên. Hai mục này về từ nhánh AI, viết trước khi tách
  // vai nên không khai `vai` — mà mục không khai vai bị hiểu là "cả hai bản đều có".
  { name: 'Tạo đề', path: '/teacher/exam-authoring', icon: Sparkles, vai: 'gv' },
  { name: 'Kho tài liệu đề', path: '/teacher/exam-documents', icon: FileText, vai: 'gv' },
  // Vào thẳng trang Kho — mọi thao tác (tạo/sửa/xóa/chấm lại) đều là nút trong trang đó.
  { name: 'Bộ chấm Golden', path: '/teacher/archive', icon: FileCode2, vai: 'gv' },
];

const PRIMARY_NAV: NavEntry[] = TOAN_BO_NAV.filter(
  (e) => !e.vai || (e.vai === 'gv' ? laGiangVien : laNguoiCham)
);

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

/**
 * Thiếu vai thì mọi màn hình đều sai: menu trống, mà API bên máy chủ cũng không tồn tại. Nói
 * thẳng ra một lần ở đây, thay vì để người dùng bấm quanh một giao diện nửa vời.
 *
 * Tách làm hai lớp vì phép kiểm phải đứng TRƯỚC mọi hook — trả về sớm ở giữa thân component
 * là vi phạm quy tắc hook của React.
 */
export default function SidebarLayout(props: SidebarLayoutProps) {
  if (!VAI) return <ChuaKhaiVai />;
  return <SidebarTheoVai {...props} />;
}

function SidebarTheoVai({ children, activePath = '/', title, subtitle, contentClassName }: SidebarLayoutProps) {
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

  const notifRef = useRef<HTMLDivElement>(null);

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
    // Bản giảng viên không nạp API phiên chấm — gọi vào chỉ nhận 404 mỗi 30 giây.
    if (!laNguoiCham) return;
    loadNotifs();
    const id = setInterval(loadNotifs, 30000);
    return () => clearInterval(id);
  }, [loadNotifs]);

  // Đóng dropdown khi click ra ngoài
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) setNotifOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
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

  // Mở trang Lịch sử chấm đúng bộ được bấm. Truyền bằng query để lần điều hướng nào cũng đọc
  // được, KÈM sự kiện cho ca đang đứng sẵn ở trang đó (xem chú thích ở MO_LICH_SU).
  const moLichSu = (examId: string) => {
    router.push(`/history?exam=${encodeURIComponent(examId)}`);
    window.dispatchEvent(new CustomEvent(MO_LICH_SU, { detail: { examId } }));
  };

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
            {/* Nút đổi giao diện sáng/tối — hiển thị trên mọi trang dùng layout này */}
            <ThemeToggle />

            {/* Chuông thông báo — phiên chấm chỉ tồn tại ở bản người chấm. */}
            {laNguoiCham && (
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
                              onClick={() => { setNotifOpen(false); moLichSu(n.examId); }}
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
            )}

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

/** Màn hình khi bản đang chạy không khai vai — nói rõ phải làm gì, không bắt đoán. */
function ChuaKhaiVai() {
  return (
    <div className="flex h-screen w-full items-center justify-center bg-slate-50 p-8 font-sans">
      <div className="max-w-lg rounded-2xl border border-rose-200 bg-white p-8 shadow-sm">
        <h1 className="text-lg font-bold text-slate-900">Bản này chưa khai vai</h1>
        <p className="mt-2 text-sm leading-relaxed text-slate-600">
          Hệ thống đã tách làm hai bản và không còn bản thấy cả hai vai. Không khai vai thì menu
          trống và mọi lời gọi API đều không tồn tại — nên màn hình dừng ở đây thay vì để bạn bấm
          quanh một giao diện nửa vời.
        </p>
        <div className="mt-5 space-y-3 text-sm text-slate-700">
          <div>
            <p className="font-semibold">Chạy bản đã cắt</p>
            <code className="mt-1 block rounded-lg bg-slate-100 px-3 py-2 font-mono text-xs">
              vào dist\gv hoặc dist\nc rồi chạy .\run
            </code>
          </div>
          <div>
            <p className="font-semibold">Chạy thẳng trong repo để phát triển</p>
            <code className="mt-1 block rounded-lg bg-slate-100 px-3 py-2 font-mono text-xs">
              .\run -Vai gv
            </code>
          </div>
        </div>
        <p className="mt-5 text-xs leading-relaxed text-slate-500">
          Mở tay thì đặt <code className="font-mono">NEXT_PUBLIC_ROLE=gv</code> hoặc{" "}
          <code className="font-mono">nc</code> trong <code className="font-mono">frontend/.env.local</code>,
          rồi xóa thư mục <code className="font-mono">.next</code> vì biến này được nướng vào bản
          biên dịch.
        </p>
      </div>
    </div>
  );
}
