// Tiện ích cho HÌNH MINH HỌA giao diện (mockup) — dùng chung cho trợ lý AI và trang Xem đề.
//
// Máy chủ không có thư viện rasterize SVG (repo build offline, không thêm dependency được), nên
// mọi việc đổi SVG → PNG đều làm trong trình duyệt: nó vốn đang vẽ đúng hình đó.

/** Đổi một chuỗi SVG ra PNG bằng canvas. Trả về data URI + kích thước thật (để đặt vào .docx). */
export function svgToPng(svg: string, scale = 2): Promise<{ png: string; width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      const width = image.width || 900;
      const height = image.height || 600;
      const canvas = document.createElement("canvas");
      canvas.width = width * scale;
      canvas.height = height * scale;
      const ctx = canvas.getContext("2d");
      if (!ctx) { reject(new Error("Trình duyệt không hỗ trợ canvas.")); return; }
      ctx.fillStyle = "#ffffff";                    // PNG nền trong suốt dán vào Word thành ô đen
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
      resolve({ png: canvas.toDataURL("image/png"), width, height });
    };
    image.onerror = () => reject(new Error("Không dựng được ảnh từ hình minh họa."));
    // Ảnh trong SVG luôn nhúng dạng data: nên không có tài nguyên ngoài → canvas không bị "nhiễm bẩn".
    image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  });
}

/** Bung một Blob ra file tải về. */
export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export function downloadText(text: string, filename: string, type: string) {
  downloadBlob(new Blob([text], { type }), filename);
}

/** Trần dung lượng ảnh tải lên: hình được nhúng thẳng vào de_bai.html nên ảnh nặng làm phình cả đề. */
export const MAX_MOCKUP_BYTES = 4 * 1024 * 1024;

/**
 * Bọc ảnh giáo viên tải lên thành SVG để đi tiếp bằng đúng đường ống của hình do AI vẽ
 * (lưu handout/mockup/&lt;id&gt;.svg → nhúng vào de_bai.html → đổi PNG khi tải .docx).
 *
 * <p>Ảnh nhúng dạng data URI nên file SVG vẫn TỰ CHỨA — copy đi đâu cũng còn hình.
 *
 * <p>Kể cả khi giáo viên tải lên chính một file .svg, ở đây vẫn bọc qua thẻ &lt;image&gt; chứ không
 * dán nội dung vào: trình duyệt vẽ SVG bên trong &lt;image&gt; ở chế độ tĩnh (không chạy script,
 * không tải tài nguyên ngoài), nên file lạ không thể nhét mã vào bản đề phát cho sinh viên.
 */
export function imageFileToSvg(file: File, nhanHinh?: string): Promise<{ svg: string; width: number; height: number }> {
  return new Promise((resolve, reject) => {
    if (file.size > MAX_MOCKUP_BYTES) {
      reject(new Error(`Ảnh nặng ${(file.size / 1024 / 1024).toFixed(1)} MB, vượt mức 4 MB. `
        + "Hãy nén bớt trước khi tải lên."));
      return;
    }
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("Không đọc được file ảnh."));
    reader.onload = () => {
      const dataUri = String(reader.result || "");
      const probe = new Image();
      probe.onload = () => {
        const width = Math.round(probe.naturalWidth || probe.width || 900);
        const height = Math.round(probe.naturalHeight || probe.height || 600);
        // Tên màn hình thành aria-label: đó là cách tên hình đi THEO file SVG. Lúc lưu chỉ gửi
        // {id, svg} nên tên không còn đường nào khác để về, mà tên này in ra đề bài (thẻ h3 trên
        // mỗi hình) — thiếu nó thì chú thích trên ảnh thành "anh mu9j97jy".
        const nhan = String(nhanHinh || file.name.replace(/\.[^.]+$/, ""))
          .replace(/[&<>"']/g, (c) =>
            ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] as string);
        resolve({
          svg: `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"`
            + ` viewBox="0 0 ${width} ${height}" width="${width}" height="${height}"`
            + ` role="img" aria-label="${nhan}">`
            + `<rect width="100%" height="100%" fill="#ffffff"/>`
            + `<image href="${dataUri}" xlink:href="${dataUri}" x="0" y="0"`
            + ` width="${width}" height="${height}"/></svg>`,
          width,
          height,
        });
      };
      probe.onerror = () => reject(new Error("File này không phải ảnh hợp lệ."));
      probe.src = dataUri;
    };
    reader.readAsDataURL(file);
  });
}
