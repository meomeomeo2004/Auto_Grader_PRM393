<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

# Frontend Auto-Grader V2

Đọc `../AGENTS.md` trước để biết hệ thống làm gì và hai vai `gv` / `nc` là gì. File này chỉ
nói phần frontend.

Next 16.2 · React 19.2 · Tailwind v4 (qua `@tailwindcss/postcss`, **không có** `tailwind.config`)
· `lucide-react` · `framer-motion` · `recharts` · TypeScript. Không có test frontend —
kiểm bằng `npx tsc --noEmit`.

## 1. Vai quyết định giao diện, và nó được nướng vào lúc build

`lib/vai.ts` là nguồn duy nhất:

```ts
export const VAI: Vai | null          // đọc NEXT_PUBLIC_ROLE, chỉ nhận "gv" | "nc"
export const laGiangVien, laNguoiCham
export const TRANG_CHU                // nc → /teacher/grading, gv → /teacher/archive
```

- `NEXT_PUBLIC_ROLE` nằm trong `.env.local` (cả `dong-goi.ps1` lẫn `start-all.ps1` đều ghi).
- **Next thay `process.env.NEXT_PUBLIC_*` bằng chuỗi hằng lúc build.** Đổi vai mà không xoá
  `.next` thì giao diện vẫn là vai cũ. Đây là lỗi tốn thời gian nhất ở phần FE.
- `VAI === null` nghĩa là chưa khai vai. Giao diện **phải báo ra** (`ChuaKhaiVai` ở cuối
  `SidebarLayout.tsx`), không được đoán bừa một vai.

`components/layout/SidebarLayout.tsx` giữ bảng `TOAN_BO_NAV` rồi lọc theo vai:

| Mục | Đường | Vai |
|---|---|---|
| Chấm bài › Chấm tự động | `/teacher/grading` | nc |
| Chấm bài › Lịch sử chấm | `/history` | nc |
| Quản lý bộ testcase | `/teacher/testcases` | nc |
| Thư viện chấm | `/teacher/libraries` | **cả hai** |
| Tạo đề | `/teacher/exam-authoring` | gv |
| Tạo Golden | `/teacher/golden-authoring` | gv |
| Kho tài liệu đề | `/teacher/exam-documents` | gv |
| Bộ chấm Golden | `/teacher/archive` | gv |

**Mục nào không khai `vai` thì hiện ở CẢ HAI bản.** Thêm mục mới mà quên khai là rò màn hình
sang vai không được phép — đã xảy ra thật với hai mục AI.

## 2. Hai tầng component để không vi phạm Rules of Hooks

```tsx
export default function SidebarLayout(props) {
  if (!VAI) return <ChuaKhaiVai />;   // return sớm, TRƯỚC mọi hook
  return <SidebarTheoVai {...props} />;
}
function SidebarTheoVai(...) { /* toàn bộ hook nằm ở đây */ }
```

Không gộp hai tầng này lại. Return sớm phải đứng trước mọi hook, mà phần thân thì cần rất
nhiều hook — nên phải tách.

Cũng vì lý do vai: các effect chỉ có nghĩa với người chấm (chuông thông báo phiên chấm, ô tìm
kiếm) đều gác `if (!laNguoiCham) return;` chứ không chỉ ẩn phần render.

## 3. Bố cục route

```
app/page.jsx              → redirect về TRANG_CHU
app/teacher/page.jsx      → redirect
app/teacher/archive/      → 8 dòng, re-export thẳng behavior-authoring (giữ bookmark cũ)
app/teacher/behavior-authoring/page.tsx   3200 dòng ← màn soạn bộ chấm Golden, to nhất repo
app/teacher/grading/page.jsx              1400 dòng ← màn chấm tự động (nc)
app/history/page.tsx                      1400 dòng ← lịch sử chấm (nc)
app/teacher/{exam-authoring,golden-authoring,exam-documents,testcases,libraries,exam-view}/
components/{layout,ui,grading,testcases}/
lib/{vai,config,csv,errors,gradingSessions,gradingStatus,exam-pdf,mockup-image,aiAuthorDrafts}
```

`dong-goi.ps1` **xoá hẳn** thư mục route của vai kia khi cắt bản:

- `dist/gv` bỏ `teacher/grading`, `history`, `teacher/testcases`
- `dist/nc` bỏ `teacher/behavior-authoring`, `teacher/archive`, `teacher/exam-view`,
  `teacher/exam-authoring`, `teacher/golden-authoring`, `components/testcases`,
  `lib/aiAuthorDrafts.ts`
- `teacher/libraries` cố ý còn ở **cả hai** bản
- `teacher/exam-documents` vẫn còn trong `dist/nc` nhưng là **rác**: nav ẩn nó (`vai: 'gv'`)
  và mọi lời gọi của nó đi vào `ExamSetupController` — controller gv-only, đã bị chính
  `dong-goi.ps1` xoá khỏi bản nc. Gõ thẳng URL thì trang lên nhưng mọi request 404.

Nên đừng import chéo giữa hai nhóm route, bản cắt ra sẽ vỡ. Script có kiểm import treo sau khi
cắt. `teacher/archive` chỉ là một dòng re-export `behavior-authoring` nên hai thư mục đó phải
đi cùng nhau — đã suýt gãy một lần vì quên.

## 4. Gọi API

`lib/config.js` → `API_BASE = process.env.NEXT_PUBLIC_API_BASE || "http://localhost:8080/api"`.
Cổng theo vai: gv → 8090, nc → 8080. **Đừng hardcode URL trong trang.**

Không có API client dùng chung. Mỗi trang lớn tự khai helper của nó
(`api<T>()` trong `behavior-authoring`, `apiJson()` trong `libraries`). Khi thêm code, theo
file xung quanh chứ đừng dựng một lớp client mới cho riêng một trang.

Backend trả **404 thật** cho endpoint của vai kia (controller gắn `@Profile` nên không được
nạp). 404 ở đây là đúng thiết kế, không phải route sai.

## 5. Gotchas

1. **Modal / lớp phủ phải `createPortal` ra `document.body`.** Khung nội dung của
   `SidebarLayout` mang lớp `animate-fade-in-up`; phần tử có `transform` trở thành khung quy
   chiếu cho mọi con `position: fixed` bên trong. Đo thật 14/9: hộp xác nhận xoá ra
   1120×494 tại (288,96) thay vì 1440×900 tại (0,0) — nền mờ hụt cả thanh bên, và `<main>`
   còn `overflow-y-auto` nên hộp có lúc trôi khỏi tầm nhìn. `SelectMenu.tsx` và
   `ExamCombobox.tsx` đã portal sẵn cho danh sách xổ.
2. **Tailwind v4 không có file config.** Token màu và `@keyframes` tự khai nằm trong
   `app/globals.css`. Đừng tạo `tailwind.config.js`.
3. Chuyển trang giữa hai màn của người chấm dùng sự kiện `MO_LICH_SU`
   (`grader:mo-lich-su`) chứ không chỉ `router.push`, vì trang đích có thể đang mở sẵn.
4. `npm run lint` dùng flat config (`eslint.config.mjs`).

## 6. Quy ước

- Comment tiếng Việt, giải thích **"tại sao"**, theo style dày đặc sẵn có.
- Sửa xong: `npx tsc --noEmit`. Đừng tự khởi động lại dev server người dùng đang chạy.
- **Không tự chạy `git commit` / `git push`**, và không thêm trailer `Co-Authored-By`.
