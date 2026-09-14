# vai-cau-hinh.ps1 - BANG CONG VA SCHEMA CUA TUNG VAI. Mot nguon su that duy nhat.
#
# Ca dong-goi.ps1 (cat ban giao) lan start-all.ps1 (chay) deu dot-source file nay. De hai
# noi tu khai rieng thi som muon cung lech, ma lech cong giua frontend va backend thi giao
# dien len duoc nhung goi API nao cung hong - kieu loi mat nhieu thoi gian nhat de tim ra.
#
# Ban giao di KHONG can file nay: dong-goi.ps1 da nuong so cu the vao vai.ps1 trong tung ban.

# Vi sao khong vai nao dung lai chamthi_db: do la co so du lieu cu tu thoi mot he thong lam ca
# hai viec. Trong do co san nhung de CHUA he di qua goi ban giao, va duong dan testcase cua
# chung tro thang vao thu muc repo. Cham thu bang schema do la doc thu muc repo chu khong phai
# goi vua nhap - roi ket luan nham rang khau ban giao da chay duoc.
#
# chamthi_db DA BI XOA HAN ngay 14/9/2026, cung luc xoa kho Golden cu tren dia. Khong con
# duong lui ve no nua; hai schema duoi day la tat ca nhung gi he thong dung. Chung KHONG
# duoc tao san o dau ca - chuoi ket noi mang createDatabaseIfNotExist=true nen backend tu
# tao schema cua minh o lan chay dau.
$CauHinhVai = @{
  gv = @{
    Ten    = "giang vien"
    BePort = 8090
    FePort = 3100
    Schema = "chamthi_gv"
  }
  nc = @{
    Ten    = "nguoi cham"
    BePort = 8080
    FePort = 3000
    Schema = "chamthi_nc"
  }
}
