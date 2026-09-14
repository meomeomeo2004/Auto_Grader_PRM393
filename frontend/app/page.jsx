import { redirect } from "next/navigation";
import { TRANG_CHU } from "@/lib/vai";

/** Trang đầu tùy theo bản đang chạy: người chấm vào Chấm tự động, giảng viên vào Bộ chấm Golden. */
export default function HomeRedirect() {
  redirect(TRANG_CHU);
}
