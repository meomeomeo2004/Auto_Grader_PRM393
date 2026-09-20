/// DINH DANH (Semantics identifier) - HOP DONG giua bai lam va may cham.
///
/// ====================== DAY LA FILE MAU ======================
/// Xoa het cac thanh vien vi du o cuoi file, thay bang dinh danh cua de BAN.
/// Roi chep file vao thu muc lib/ cua Golden Solution.
///
/// Luc xuat khung phat, he thong chep NGUYEN BYTE file nay tu Golden sang khung
/// cho sinh vien - khong sinh lai, khong sua gi. Golden thieu file nay thi khong
/// xuat duoc khung.
/// =============================================================
///
///
/// ---------- 1. GAN NHU THE NAO ----------
///
/// Boc widget trong Semantics(identifier: ...):
///
///   Semantics(
///     identifier: DinhDanh.them,
///     child: FloatingActionButton(onPressed: _them, child: const Icon(Icons.add)),
///   )
///
/// Widget DA CO Semantics(label:) theo yeu cau cua de thi chi them tham so identifier
/// vao chinh Semantics do, KHONG boc them mot lop nua:
///
///   Semantics(
///     identifier: DinhDanh.xoaDong(e.id!),
///     label: 'Xoa khoan chi',          // nhan nay do de bai quy dinh
///     button: true,
///     child: IconButton(icon: const Icon(Icons.delete_outline), onPressed: ...),
///   )
///
///
/// ---------- 2. BON LUAT PHAI THEO ----------
///
/// [1] BOC SAT CONTROL. Khong boc Form, Card, Column, Padding dang om nhieu thu.
///     Vi sao: khi control khong tu mang dinh danh, bo ghi hinh leo len cha de tim.
///     Gap mot to tien KHONG CO NHAN ma lai mang dinh danh thi no nhan dinh danh do.
///     Dat mot dinh danh tren ca cum Form -> ba o nhap ghi thanh CUNG MOT dich, va
///     khong co canh bao nao ca; toi luc cham moi vo.
///
/// [2] DONG DANH SACH DANH THEO ID TRONG DATABASE, khong theo vi tri hien thi.
///     Loc, sap xep hay xoa mot dong la doi het chi so - dinh danh theo vi tri se tro
///     nham dong. Dung ham co tham so: dong(int id), xoaDong(int id).
///
/// [3] NUT CHI CO ICON VAN PHAI CO DINH DANH RIENG.
///     Nut khong chu thi khong con chuoi nao de may bam vao. Nut Xoa lap o moi dong
///     lai cang phai co, vi sau nut Xoa deu cung mot hinh.
///
/// [4] KHONG DUNG ValueKey THAY CHO Semantics(identifier:).
///     May cham tim bang find.bySemanticsIdentifier. ValueKey khong ra toi DOM nen bo
///     ghi hinh khong thay no - gan ValueKey la nhu khong gan gi.
///
///
/// ---------- 3. TEN DAT THE NAO ----------
///
/// Ten phai KHOP TUNG CHU o ca ba noi:
///
///   cot "Dinh danh" trong de bai  ==  file nay  ==  cho gan trong Golden
///
/// Lech mot chu o bat ky noi nao thi sinh vien gan theo de, may tim theo Golden, hai ben
/// khong gap nhau - ma khong co phep kiem nao bat duoc.
///
/// Dat theo VAI TRO NGHIEP VU, khong theo vi tri hay hinh dang:
///   tot:   chi_tieu.them      chi_tieu.form.so_tien     chi_tieu.dong.3.xoa
///   xau:   button1            fieldLeft                 nutDo
///
/// Moi dinh danh duy nhat tren mot man hinh.
///
/// SAU KHI DA PUBLISH thi KHONG doi ten hang so hay doi chuoi trong file nay nua: bo cham
/// da tra dung nhung chuoi cu. Doi thi phai cap nhat de bai va sinh lai testcase.
library;

/// Doi ten lop nay cung duoc, mien la Golden va khung phat dung chung mot ten.
abstract final class DinhDanh {
  // =====================================================================
  // XOA TU DAY TRO XUONG. Bon vi du duoi day chi de chi ra BON KIEU khai;
  // chung la cua mot de quan ly chi tieu, khong phai cua de ban.
  // =====================================================================

  /// KIEU 1 - HANG SO THUONG: mot thanh phan duy nhat tren man hinh.
  ///
  ///   Semantics(identifier: DinhDanh.them, child: FloatingActionButton(...))
  static const String them = 'chi_tieu.them';

  /// KIEU 2 - HAM THEO ID DU LIEU: thanh phan lap theo tung dong danh sach.
  /// Tham so la khoa trong database, KHONG phai chi so cua dong tren man hinh.
  ///
  ///   Semantics(identifier: DinhDanh.dong(e.id!), child: ListTile(...))
  static String dong(int id) => 'chi_tieu.dong.$id';

  /// KIEU 3 - HAM LONG TRONG DONG: nut nam ben trong mot dong lap.
  /// Dat tien to bang dinh danh cua dong de doc ra la biet no thuoc dong nao.
  ///
  ///   Semantics(identifier: DinhDanh.xoaDong(e.id!), label: 'Xoa khoan chi',
  ///             button: true, child: IconButton(...))
  static String xoaDong(int id) => 'chi_tieu.dong.$id.xoa';

  /// KIEU 4 - HAM THEO MA CO DINH: mot nhom nut cung loai, khac nhau o ma.
  /// Dung cho chip loc, tab, nhom lua chon - nhung thu co danh sach ma biet truoc.
  ///
  ///   Semantics(identifier: DinhDanh.loc('ANUONG'), child: ChoiceChip(...))
  static String loc(String ma) => 'chi_tieu.loc.$ma';

  /// O nhap cua bieu mau. Boc quanh chinh TextFormField, khong boc quanh ca Form.
  ///
  ///   Semantics(identifier: DinhDanh.formTieuDe, child: TextFormField(...))
  static const String formTieuDe = 'chi_tieu.form.tieu_de';

  // =====================================================================
  // XOA TOI DAY.
  // =====================================================================
}
