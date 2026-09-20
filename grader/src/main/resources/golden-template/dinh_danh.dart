/// ĐỊNH DANH (Semantics identifier) — HỢP ĐỒNG giữa bài làm và máy chấm.
///
/// ====================== ĐÂY LÀ FILE MẪU ======================
/// Xoá hết các thành viên ví dụ ở cuối file, thay bằng định danh của đề BẠN.
/// Rồi chép file vào thư mục lib/ của Golden Solution.
///
/// Lúc xuất khung phát, hệ thống chép NGUYÊN BYTE file này từ Golden sang khung
/// cho sinh viên — không sinh lại, không sửa gì. Golden thiếu file này thì không
/// xuất được khung.
/// =============================================================
///
///
/// ---------- 1. GẮN NHƯ THẾ NÀO ----------
///
/// Bọc widget trong Semantics(identifier: ...):
///
///   Semantics(
///     identifier: DinhDanh.them,
///     child: FloatingActionButton(onPressed: _them, child: const Icon(Icons.add)),
///   )
///
/// Widget ĐÃ CÓ Semantics(label:) theo yêu cầu của đề thì chỉ thêm tham số identifier
/// vào chính Semantics đó, KHÔNG bọc thêm một lớp nữa:
///
///   Semantics(
///     identifier: DinhDanh.xoaDong(e.id!),
///     label: 'Xóa khoản chi',          // nhãn này do đề bài quy định
///     button: true,
///     child: IconButton(icon: const Icon(Icons.delete_outline), onPressed: ...),
///   )
///
/// Nhớ import ở đầu mỗi file có gắn định danh:
///
///   import '../dinh_danh.dart';        // đường dẫn tuỳ theo file bạn đang sửa
///
///
/// ---------- 2. CHỌN DẠNG KHAI: HẰNG SỐ HAY HÀM ----------
///
/// Chỉ có hai dạng, và chọn dạng nào KHÔNG phụ thuộc thành phần đó có mang dữ liệu hay
/// không. Chỉ hỏi đúng một câu: trên màn hình có MỘT cái đó, hay có NHIỀU cái mà mỗi
/// cái cần một định danh riêng?
///
///   MỘT cái duy nhất   ->  hằng số:  static const String them = 'chi_tieu.them';
///   NHIỀU cái lặp lại  ->  hàm:      static String dong(int id) => 'chi_tieu.dong.$id';
///
/// Ví dụ: ô nhập Tiêu đề CÓ mang dữ liệu người dùng gõ vào, nhưng trên màn chỉ có một ô
/// đó nên vẫn là hằng số. Chip lọc KHÔNG mang dữ liệu gì, nhưng có sáu cái nên là hàm.
///
/// Lý do phải là hàm nằm ở chính Dart: `const` đòi giá trị biết được ngay lúc biên dịch,
/// mà 'chi_tieu.dong.$id' phụ thuộc id lấy từ database lúc chạy — không thể là const.
///
/// (Nhóm có danh sách mã biết trước, như sáu chip lọc, khai sáu hằng số riêng cũng chạy
///  y hệt. Hàm chỉ là cách viết gọn hơn.)
///
///
/// ---------- 3. BỐN LUẬT PHẢI THEO ----------
///
/// [1] LUÔN GẮN QUA HẰNG SỐ, KHÔNG GÕ THẲNG CHUỖI.
///
///       ĐÚNG:  identifier: DinhDanh.them
///       SAI :  identifier: 'chi_tieu.them'
///
///     Hai dòng này chạy giống hệt nhau — máy chấm không phân biệt được. Nhưng gõ thẳng
///     chuỗi thì: (a) trình biên dịch không còn kiểm giúp bạn, gõ sai một chữ vẫn dịch
///     trót lọt rồi trượt tiêu chí lúc chấm; (b) sau này đổi giá trị hằng số, chỗ gõ
///     thẳng im lặng lệch đi; (c) sinh viên nhận file này mà không thấy cái tên đó thì
///     không có đường nào biết là phải gắn.
///     Hệ thống TỪ CHỐI nhận Golden vi phạm luật này — tải lên sẽ báo lỗi ngay.
///
/// [2] BỌC SÁT CONTROL. Không bọc Form, Card, Column, Padding đang ôm nhiều thứ.
///     Vì sao: khi control không tự mang định danh, bộ ghi hình leo lên cha để tìm.
///     Gặp một tổ tiên KHÔNG CÓ NHÃN mà lại mang định danh thì nó nhận định danh đó.
///     Đặt một định danh trên cả cụm Form -> ba ô nhập ghi thành CÙNG MỘT đích, và
///     không có cảnh báo nào cả; tới lúc chấm mới vỡ.
///
/// [3] DÒNG DANH SÁCH ĐÁNH THEO ID TRONG DATABASE, không theo vị trí hiển thị.
///     Lọc, sắp xếp hay xoá một dòng là đổi hết chỉ số — định danh theo vị trí sẽ trỏ
///     nhầm dòng. Dùng hàm có tham số: dong(int id), xoaDong(int id).
///
/// [4] NÚT CHỈ CÓ ICON VẪN PHẢI CÓ ĐỊNH DANH RIÊNG.
///     Nút không chữ thì không còn chuỗi nào để máy bám. Nút Xoá lặp ở mọi dòng lại
///     càng phải có, vì sáu nút Xoá đều cùng một hình.
///
///
/// ---------- 4. TÊN ĐẶT THẾ NÀO ----------
///
/// Tên phải KHỚP TỪNG CHỮ ở cả ba nơi:
///
///   cột "Định danh" trong đề bài  ==  file này  ==  chỗ gắn trong Golden
///
/// Lệch một chữ ở bất kỳ nơi nào thì sinh viên gắn theo đề, máy tìm theo Golden, hai bên
/// không gặp nhau.
///
/// Đặt theo VAI TRÒ NGHIỆP VỤ, không theo vị trí hay hình dáng:
///   tốt:  chi_tieu.them      chi_tieu.form.so_tien     chi_tieu.dong.3.xoa
///   xấu:  button1            fieldLeft                 nutDo
///
/// Mỗi định danh duy nhất trên một màn hình.
///
/// SAU KHI ĐÃ PUBLISH thì KHÔNG đổi tên hằng số hay đổi chuỗi trong file này nữa: bộ chấm
/// đã tra đúng những chuỗi cũ. Đổi thì phải cập nhật đề bài và sinh lại testcase.
library;

/// Đổi tên lớp này cũng được, miễn là Golden và khung phát dùng chung một tên.
abstract final class DinhDanh {
  // =====================================================================
  // XOÁ TỪ ĐÂY TRỞ XUỐNG. Bốn ví dụ dưới đây chỉ để chỉ ra BỐN KIỂU khai;
  // chúng là của một đề quản lý chi tiêu, không phải của đề bạn.
  // =====================================================================

  /// KIỂU 1 — HẰNG SỐ: màn hình chỉ có MỘT thành phần như thế.
  ///
  ///   Semantics(identifier: DinhDanh.them, child: FloatingActionButton(...))
  static const String them = 'chi_tieu.them';

  /// KIỂU 2 — HÀM THEO ID DỮ LIỆU: thành phần lặp theo từng dòng danh sách.
  /// Tham số là khoá trong database, KHÔNG phải chỉ số của dòng trên màn hình.
  ///
  ///   Semantics(identifier: DinhDanh.dong(e.id!), child: ListTile(...))
  static String dong(int id) => 'chi_tieu.dong.$id';

  /// KIỂU 3 — HÀM LỒNG TRONG DÒNG: nút nằm bên trong một dòng lặp.
  /// Đặt tiền tố bằng định danh của dòng để đọc ra là biết nó thuộc dòng nào.
  ///
  ///   Semantics(identifier: DinhDanh.xoaDong(e.id!), label: 'Xóa khoản chi',
  ///             button: true, child: IconButton(...))
  static String xoaDong(int id) => 'chi_tieu.dong.$id.xoa';

  /// KIỂU 4 — HÀM THEO MÃ CỐ ĐỊNH: một nhóm nút cùng loại, khác nhau ở mã.
  /// Dùng cho chip lọc, tab, nhóm lựa chọn — những thứ có danh sách mã biết trước.
  ///
  ///   Semantics(identifier: DinhDanh.loc('ANUONG'), child: ChoiceChip(...))
  static String loc(String ma) => 'chi_tieu.loc.$ma';

  /// Ô nhập của biểu mẫu: chỉ có một ô Tiêu đề trên màn nên là hằng số, dù nó mang dữ
  /// liệu. Bọc quanh chính TextFormField, không bọc quanh cả Form.
  ///
  ///   Semantics(identifier: DinhDanh.formTieuDe, child: TextFormField(...))
  static const String formTieuDe = 'chi_tieu.form.tieu_de';

  // =====================================================================
  // XOÁ TỚI ĐÂY.
  // =====================================================================
}
