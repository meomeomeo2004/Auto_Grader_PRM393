// frontend/lib/vai.ts
// Hệ thống được giao cho hai người khác nhau, mỗi người một bản: giảng viên ra đề và
// người chấm chạy máy chấm. KHÔNG chia bằng đăng nhập — bản nào giao cho ai thì bản đó
// chỉ chứa màn hình của vai đó, nên một biến môi trường là đủ để quyết định.
//
// Giá trị đặt trong .env.local (dong-goi.ps1 và start-all.ps1 đều ghi sẵn):
//   NEXT_PUBLIC_ROLE=gv    → giảng viên: Tạo đề, Tạo Golden, Bộ chấm Golden, Thư viện chấm
//   NEXT_PUBLIC_ROLE=nc    → người chấm: Chấm tự động, Lịch sử chấm, Thư viện chấm
//
// KHÔNG còn bản "thấy cả hai vai". Bản đó là cấu hình duy nhất mà bộ chấm vừa xuất bản hiện
// thẳng ở phần chấm bài, không đi qua gói bàn giao — tức là cấu hình duy nhất che được một
// khâu bàn giao đang hỏng, trong khi đó mới là đường cả hai người nhận đều phải đi.

export type Vai = "gv" | "nc";

/** Next thay process.env.NEXT_PUBLIC_* bằng chuỗi hằng lúc build nên đọc ở đâu cũng được. */
function docVai(): Vai | null {
  const v = (process.env.NEXT_PUBLIC_ROLE || "").trim().toLowerCase();
  return v === "gv" || v === "nc" ? v : null;
}

/** null = chưa khai vai. Giao diện phải báo ra chứ không được đoán bừa. */
export const VAI: Vai | null = docVai();

export const laGiangVien = VAI === "gv";
export const laNguoiCham = VAI === "nc";

/**
 * Trang mở đầu phải theo vai: bản giảng viên không có màn Chấm tự động, bản người chấm
 * không có màn soạn đề. Trỏ sai là mở app lên gặp trang trắng.
 */
export const TRANG_CHU = laNguoiCham ? "/teacher/grading" : "/teacher/archive";
