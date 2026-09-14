import { redirect } from "next/navigation";
import { TRANG_CHU } from "@/lib/vai";

/** Đường /teacher cũ còn trong bookmark — đẩy về trang đầu của bản đang chạy. */
export default function TeacherHomeRedirect() {
  redirect(TRANG_CHU);
}
