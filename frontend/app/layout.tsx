import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Grader — Hệ thống chấm thi Flutter tự động",
  description:
    "Nền tảng chấm bài thi thực hành Flutter tự động: cấu hình bộ testcase, chấm hàng loạt trong môi trường Docker cô lập, thống kê và xuất kết quả.",
  icons: { icon: "/favicon.ico" },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html:
              "(function(){try{var t=localStorage.getItem('theme');var d=t?t==='dark':window.matchMedia('(prefers-color-scheme: dark)').matches;if(d)document.documentElement.classList.add('dark');}catch(e){}})();",
          }}
        />
      </head>
      {/* suppressHydrationWarning: vai tro nao cung bao "1 Issue" hydration vi tien ich
          mo rong cua trinh duyet gan them thuoc tinh __processed_<uuid>__ vao <body>
          TRUOC khi React hydrate. Do 22/9/2026: HTML may chu tra ve chi co
          <body class="min-h-full flex flex-col">, mo cung trang bang trinh duyet sach
          thi <body> cung chi co mot thuoc tinh class — nen lech nay khong den tu ma nguon
          va khong the sua o phia may chu. Co suppress thi <html> da co san (cho script
          theme), nhung co do khong lan xuong con nen <body> phai tu khai. */}
      <body className="min-h-full flex flex-col" suppressHydrationWarning>
        {children}
      </body>
    </html>
  );
}
