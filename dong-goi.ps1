<#
  dong-goi.ps1 - Cat repo thanh cac ban giao cho tung vai.

  He thong duoc giao cho HAI nguoi khac nhau, moi nguoi mot ban chay tren may rieng:
      gv = giang vien : Bo cham Golden, Khung nang luc, Thu vien cham
      nc = nguoi cham : Cham tu dong, Lich su cham, Thu vien cham
  Ma nguon thi VAN CHI CO MOT trong repo nay. Script chi la khau cat cuoi cung, nen sua engine
  hay sua phan doc ket qua chi phai lam mot lan; chay lai script la ca hai ban cung co.

  Cach dung (mo terminal tai goc repo):
      powershell -ExecutionPolicy Bypass -File .\dong-goi.ps1
      powershell -ExecutionPolicy Bypass -File .\dong-goi.ps1 -Vai gv
      powershell -ExecutionPolicy Bypass -File .\dong-goi.ps1 -Dich D:\giao

  Ket qua: <Dich>\gv va <Dich>\nc, moi thu muc la mot ban chay duoc doc lap.

  BAN LAM VIEC vs BAN GIAO DI - hai thu khac nhau, dung lan
  - dist\gv va dist\nc la BAN LAM VIEC tren may nay. Dong goi lai GIU NGUYEN du lieu da sinh
    ra trong do (xem $GiuLai): bo Golden, thu muc de, bai nop. Nho vay sua ma xong dong goi
    lai khong mat cong da soan.
  - Chinh vi giu du lieu ma KHONG BAO GIO duoc dua thang dist\gv cho nguoi khac:
    grader\behavior-artifacts trong do la loi giai mau va database an cua MOI de.
  - Ban giao chinh thuc phai cat ra mot thu muc MOI, chua tung chay:
        powershell -ExecutionPolicy Bypass -File .\dong-goi.ps1 -Dich D:\giao
    Thu muc moi thi khong co gi de giu, nen no sach theo dinh nghia: khong Golden, khong
    hidden.db, khong bai nop, khong lich su cham. Do moi la thu dem di giao.
  - Con thieu mot manh khong nam trong goi: anh Docker grading-base. May nguoi nhan khong co
    no thi khong cham duoc bai nao. Dua bang "docker save" ra file .tar roi ho "docker load";
    dung bao ho tu dung tu Dockerfile.base vi no khong ghim phien ban, hai may dung hai luc
    ra hai anh khac nhau.

  CAT GI VA KHONG CAT GI
  - Frontend: xoa han thu muc man hinh cua vai kia. Ban giao di khong con ma cua vai do.
  - Backend : GIU NGUYEN ca hai. Khong phai vi luoi: controller cua vai nao khong duoc bat thi
              Spring khong nap, goi vao la 404 that (xem grader/src/main/java/.../config/Vai.java),
              va dieu do duoc chung minh bang VaiDuongDanTest. Cat bot controller la xoa luon
              bai kiem chung minh phep tach hoat dong, doi lay mot thu khong ai nhin thay.
#>

param(
  [ValidateSet('gv','nc','ca-hai')]
  [string]$Vai = 'ca-hai',
  [string]$Dich = 'dist'
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Section($t) { Write-Host "`n==== $t ====" -ForegroundColor Cyan }
function Ok($t)      { Write-Host "  [OK] $t" -ForegroundColor Green }
function Canh($t)    { Write-Host "  [!] $t" -ForegroundColor Yellow }

# Cong va schema lay tu vai-cau-hinh.ps1 - dung chung voi start-all.ps1 de hai noi khong lech.
. (Join-Path $PSScriptRoot "vai-cau-hinh.ps1")

# Rieng danh sach man hinh phai bo thi chi khau dong goi moi can, nen de ngay o day.
#
# teacher\testcases DOI BEN tu 14/9/2026: truoc kia no chi la mot duong dan cu tro ve
# behavior-authoring (bo cung voi ban nguoi cham); nay no la man "Quan ly bo testcase" - noi
# DUY NHAT cua ban nguoi cham de nhan goi .zip va xoa bo. Nen ban giang vien moi la ban bo no.
$BoManTheoVai = @{
  gv = @("frontend\app\teacher\grading", "frontend\app\history",
         "frontend\app\teacher\testcases")
  # archive chi la mot duong dan cu tro ve behavior-authoring, nen phai di cung no: bo moi
  # behavior-authoring thi duong kia thanh lien ket gay.
  #
  # Bon duong CUOI la cua chuc nang AI RA DE - toan bo thuoc ve giang vien. Chung CHUA co trong
  # nhanh nay: chung nam o origin/main (3 commit), se vao khi gop. Khai truoc o day la co y -
  # vong xoa man la "if (Test-Path) thi xoa", duong chua ton tai thi no lang le bo qua, nen dong
  # nay hom nay khong lam gi ca. Nhung den luc gop thi khong ai phai NHO ra viec nay nua.
  #
  # Vi sao phai nho: AI ra de viet TRUOC khi tach vai nen no khong biet vai la gi. Git se gop
  # tron tru, khong mot dong canh bao, va ban nguoi cham tu nhien soan duoc de - dung thu ma ca
  # khau tach vai dung ra de ngan. Kem theo day con hai viec nua phai lam luc gop:
  #   1. Gan @Profile(Vai.GIANG_VIEN) cho AiAuthorController (/api/ai/**, co ca /settings giu
  #      khoa API). Bai kiem VaiDuongDanNguoiChamTest da chan san san cho viec nay.
  #   2. Them vai: 'gv' cho hai muc menu "Tao de" va "Tao Golden" trong SidebarLayout - muc
  #      khong khai vai bi hieu la "ca hai ban deu co".
  nc = @("frontend\app\teacher\behavior-authoring", "frontend\app\teacher\archive",
         "frontend\app\teacher\exam-view", "frontend\app\syllabus",
         "frontend\app\teacher\exam-authoring", "frontend\app\teacher\golden-authoring",
         "frontend\components\testcases", "frontend\lib\aiAuthorDrafts.ts")
}

$CauHinh = @{}
foreach ($k in $CauHinhVai.Keys) {
  $CauHinh[$k] = @{
    Ten    = $CauHinhVai[$k].Ten
    BePort = $CauHinhVai[$k].BePort
    FePort = $CauHinhVai[$k].FePort
    Schema = $CauHinhVai[$k].Schema
    BoMan  = $BoManTheoVai[$k]
  }
}

# Thu muc/tep duoc chep sang ban giao. Khong chep du lieu va khong chep thu do build sinh ra -
# nguoi nhan chay npm install va mvnw lan dau nhu moi cai dat khac.
# installer\ la BAT BUOC di kem grader-setup.cmd: file .cmd do goi thang
# "%~dp0installer\setup-prereqs.ps1", thieu la buoc cai dat dau tien tren may moi hong ngay.
# "mysql" da bo khoi danh sach 14/9/2026: thu muc do chi co mot init.sql dung tay 6 bang theo
# hinh dang cu cua schema chamthi_db. Schema do da xoa, va Hibernate tu dung toan bo bang trong
# schema cua tung vai, nen init.sql khong con viec gi ngoai viec che mat loi DDL.
$ChepThuMuc = @("grader", "frontend", "grader-base", "installer")

# GraderLauncher.exe la duong chay bang double-click ma README (cung duoc chep) chi nguoi nhan
# dung. No chi goi start-all.ps1 nam CANH no, nen bo vao ban giao la tu doc vai.ps1 dung vai.
# launcher.cs va build-exe.cmd thi khong chep: do la do de dung lai cai exe, viec cua nguoi phat
# trien chu khong phai cua nguoi nhan.
$ChepTep    = @("docker-compose.yml", "start-all.ps1", "stop-all.ps1", "run.cmd", "stop.cmd",
                "pause.cmd", "grader-setup.cmd", "GraderLauncher.exe", "README.md")

# behavior-artifacts la KHO GOLDEN VA DATABASE AN cua moi de tren may nguoi ra de. Gia tri
# no gan 10 MB nen de lot ma khong ai de y, nhung giao no di la giao luon loi giai mau va
# du lieu an cua tat ca cac de. Nguoi cham chi duoc nhan hidden.db nam trong tung goi ban
# giao cua de ho thuc su phai cham, khong phai ca cai kho.
# golden-runtimes la ban web da build (84 MB) - thu sinh ra, dung lai la sinh lai duoc.
$LoaiTru    = @("node_modules", ".next", "target", "tsconfig.tsbuildinfo", ".env.local",
                "golden-runtimes", "behavior-artifacts", "submissions", ".git")

# Don thu muc dich truoc khi chep ban moi.
#
# KHONG dung Remove-Item truc tiep: ban dong goi truoc co the chua duong dan dai hon gioi han
# cua Windows, luc do Remove-Item chet giua chung va de lai mot ban nua voi. Robocopy lam viec
# duoc voi duong dai, nen lay no "phu" mot thu muc rong len tren.
#
# GIU LAI node_modules va .next: cai chung mat vai phut va vai tram MB, trong khi dong goi lai
# la viec lam moi lan sua ma. Xoa di la moi lan dong goi lai phai ngoi cho npm install.
#
# Cach giu: DOI RA mot thu muc ben canh roi tra lai sau khi don. KHONG dung /XD cua robocopy -
# da thu va no van xoa mat, doi lay mot lan npm install oan. Doi trong cung o dia nen chi la
# doi ten, khong ton thoi gian du thu muc nang vai tram MB.
# grader\target cung giu: Maven tu bien dich lai phan nguon doi, giu lai chi de khoi build
# tu dau moi lan dong goi.
#
# GIU LAI CA DU LIEU (them 14/9/2026). Bon thu muc duoi day SINH RA TRONG BAN CHAY va khong
# co ban nao trong repo de chep lai - don thu muc dich la mat that. Truoc khi co dong nay, cu
# sua mot dong ma roi dong goi lai la xoa sach bo Golden vua soan, trong khi cac hang trong
# MySQL van con (MySQL nam ngoai dist) va tro vao nhung thu muc khong con ton tai.
#
#   exams                      thu muc testcase da xuat ban / da nhan qua goi ban giao
#   submissions                file .zip bai nop da luu
#   grader\behavior-artifacts  KHO GOLDEN + hidden.db cua moi de - thu quy nhat o ban gv
#   grader\golden-runtimes     ban web da build; dung lai duoc nhung rat lau
#
# HE QUA PHAI BIET: tu day <Dich> la BAN LAM VIEC, khong phai ban giao di. Xem canh bao o dau
# file - ban giao phai cat ra mot thu muc MOI bang -Dich.
$GiuLai = @("frontend\node_modules", "frontend\.next", "grader\target",
            "exams", "submissions",
            "grader\behavior-artifacts", "grader\golden-runtimes")

function Xoa-Sach($duong) {
  if (-not (Test-Path $duong)) { return }

  $kho = $duong + "-dang-giu"
  $daCat = @()
  foreach ($g in $GiuLai) {
    $p = Join-Path $duong $g
    if (-not (Test-Path $p)) { continue }
    $dest = Join-Path $kho ($g -replace '[\\/]', '_')
    New-Item -ItemType Directory -Path $kho -Force | Out-Null
    Move-Item -LiteralPath $p -Destination $dest -Force
    $daCat += ,@($g, $dest)
  }

  # /XJ: robocopy mac dinh DI XUYEN junction. Trong mot cay co junction tro ra ngoai thi /MIR
  # se xoa luon thu muc that o dau ben kia.
  $rong = Join-Path $env:TEMP ("dong-goi-rong-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $rong -Force | Out-Null
  & robocopy $rong $duong /MIR /XJ /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
  $global:LASTEXITCODE = 0
  Remove-Item -LiteralPath $rong -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $duong -Recurse -Force -ErrorAction SilentlyContinue

  foreach ($cap in $daCat) {
    $p = Join-Path $duong $cap[0]
    New-Item -ItemType Directory -Path (Split-Path $p -Parent) -Force | Out-Null
    Move-Item -LiteralPath $cap[1] -Destination $p -Force
  }
  if (Test-Path $kho) { Remove-Item -LiteralPath $kho -Recurse -Force -ErrorAction SilentlyContinue }
}

function Chep-CoLoc($nguon, $dich) {
  $robo = @($nguon, $dich, "/E", "/XJ", "/NFL", "/NDL", "/NJH", "/NJS", "/NP", "/R:1", "/W:1")
  foreach ($x in $LoaiTru) {
    if ($x -like ".*" -or $x -like "*.*") { $robo += @("/XF", $x) }
    $robo += @("/XD", $x)
  }
  & robocopy @robo | Out-Null
  # Robocopy tra ve 0-7 la thanh cong; tu 8 tro len moi la loi that.
  if ($LASTEXITCODE -ge 8) { throw "robocopy loi ($LASTEXITCODE): $nguon -> $dich" }
  $global:LASTEXITCODE = 0
}

function Dong-Goi-Vai($ma) {
  $c = $CauHinh[$ma]
  $dich = Join-Path $Dich $ma
  if (-not [System.IO.Path]::IsPathRooted($dich)) { $dich = Join-Path $root $dich }

  Section "Ban $ma ($($c.Ten))"

  # Dong goi lai = don sach thu muc dich roi chep ban moi. Lam viec do trong khi ban nay DANG
  # CHAY la rut thu muc target ra ngay duoi chan tien trinh Java - backend chet giua chung ma
  # khong bao gi ro rang. Da tu can mot lan, nen chan tu dau.
  foreach ($cong in @($c.BePort, $c.FePort)) {
    if (Get-NetTCPConnection -LocalPort $cong -State Listen -ErrorAction SilentlyContinue) {
      throw "Ban $ma dang chay (cong $cong con nghe). Dong cac cua so backend/frontend cua ban do" +
            " roi dong goi lai - neu khong, don thu muc se giet tien trinh dang chay."
    }
  }

  Xoa-Sach $dich
  New-Item -ItemType Directory -Path $dich -Force | Out-Null

  foreach ($t in $ChepThuMuc) {
    $nguon = Join-Path $root $t
    if (-not (Test-Path $nguon)) { Canh "Khong thay $t - bo qua"; continue }
    Chep-CoLoc $nguon (Join-Path $dich $t)
  }
  foreach ($f in $ChepTep) {
    $nguon = Join-Path $root $f
    if (-not (Test-Path $nguon)) { continue }
    try { Copy-Item $nguon (Join-Path $dich $f) -Force }
    catch {
      # Hay gap nhat: GraderLauncher.exe cua ban giao dang chay nen bi khoa. Bao ro roi dung
      # lai, chu de script chet voi mot dong loi .NET thi khong ai biet phai lam gi.
      throw "Khong ghi de duoc $f (dang bi mot tien trinh giu). Dong cua so GraderLauncher" +
            " / terminal dang chay ban $ma roi dong goi lai. Chi tiet: $($_.Exception.Message)"
    }
  }
  Ok "Da chep ma nguon"

  foreach ($man in $c.BoMan) {
    $p = Join-Path $dich $man
    if (Test-Path $p) {
      Remove-Item -LiteralPath $p -Recurse -Force
      Write-Host "      bo man $man" -ForegroundColor DarkGray
    }
  }
  Ok "Da bo man hinh cua vai kia"

  # .env.local cua frontend. Ghi UTF8 KHONG BOM: PS 5.1 mac dinh chen BOM, Next doc sai bien
  # dau dong va frontend goi sai cong backend.
  $env1 = @(
    "# Sinh boi dong-goi.ps1 - ban $ma ($($c.Ten)).",
    "NEXT_PUBLIC_ROLE=$ma",
    "NEXT_PUBLIC_API_BASE=http://localhost:$($c.BePort)/api"
  )
  $envPath = Join-Path $dich "frontend\.env.local"
  [System.IO.File]::WriteAllText($envPath, (($env1 -join "`r`n") + "`r`n"),
                                 (New-Object System.Text.UTF8Encoding($false)))

  # vai.ps1 - start-all.ps1 doc file nay de biet cong va schema cua ban minh.
  $vai1 = @(
    "# Sinh boi dong-goi.ps1. start-all.ps1 dot-source file nay.",
    "# Doi cong o day thi phai doi NEXT_PUBLIC_API_BASE trong frontend\.env.local cho khop.",
    "`$Vai     = '$ma'",
    "`$BePort0 = $($c.BePort)",
    "`$FePort  = $($c.FePort)",
    "`$Schema  = '$($c.Schema)'"
  )
  $vaiPath = Join-Path $dich "vai.ps1"
  [System.IO.File]::WriteAllText($vaiPath, (($vai1 -join "`r`n") + "`r`n"),
                                 (New-Object System.Text.UTF8Encoding($false)))
  Ok "Da ghi .env.local va vai.ps1 (backend :$($c.BePort), frontend :$($c.FePort), schema $($c.Schema))"

  # Kiem lai ngay: con file nao con IMPORT vao thu muc vua bo khong. Chi bat lenh import/export,
  # KHONG bat chuoi thuong: bang menu van liet ke duong cua ca hai vai roi loc theo vai luc chay,
  # nen bat chuoi la lan nao cung keu, keu mai thi nguoi dung thoi doc.
  # Ca thuc te tung suyt gay: app/teacher/archive chi la mot dong
  #     export { default } from "../behavior-authoring/page"
  # bo behavior-authoring ma quen archive la ban giao di co mot trang chet.
  # So theo duong dan da GIAI, khong so theo chuoi: app/syllabus co import components/grading,
  # ma man vua bo cung ten "grading" - so chuoi thi bao dong gia va phai bo qua, bo qua roi thi
  # lan sau co gay that cung khong ai tin nua.
  $feGoc  = [System.IO.Path]::GetFullPath((Join-Path $dich "frontend"))
  $daBo   = $c.BoMan | ForEach-Object { [System.IO.Path]::GetFullPath((Join-Path $dich $_)) }
  $hong   = @()
  $tepMa  = Get-ChildItem -Path $feGoc -Recurse -File `
              -Include *.ts,*.tsx,*.js,*.jsx -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch "node_modules" }
  foreach ($f in $tepMa) {
    $noiDung = Get-Content -LiteralPath $f.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $noiDung) { continue }
    foreach ($m in [regex]::Matches($noiDung, "from\s+['""]([^'""]+)['""]")) {
      $khai = $m.Groups[1].Value
      if ($khai.StartsWith("@/"))     { $giai = Join-Path $feGoc $khai.Substring(2) }
      elseif ($khai.StartsWith("."))  { $giai = Join-Path $f.DirectoryName $khai }
      else { continue }   # package ngoai, khong lien quan
      $giai = [System.IO.Path]::GetFullPath($giai)
      foreach ($bo in $daBo) {
        if ($giai.StartsWith($bo, [StringComparison]::OrdinalIgnoreCase)) {
          $hong += "$($f.FullName) van import '$khai' -> nam trong man da bo"
        }
      }
    }
  }
  if ($hong.Count -gt 0) {
    foreach ($h in $hong) { Canh $h }
    throw "Ban $ma con import vao man da bo - dung lai de khong giao di mot ban gay."
  }
  Ok "Khong con lenh import nao tro vao man da bo"

  Write-Host "  Ban $ma nam tai: $dich" -ForegroundColor White
}

$danhSach = if ($Vai -eq 'ca-hai') { @('gv','nc') } else { @($Vai) }
foreach ($m in $danhSach) { Dong-Goi-Vai $m }

Section "Xong"
Write-Host "  Giao thu muc tuong ung cho tung nguoi. Ben do chay nhu moi cai dat khac:"
Write-Host "      powershell -ExecutionPolicy Bypass -File .\start-all.ps1"
Write-Host "  Hai ban dung chung MySQL cong 3306 nhung khac schema, chay song song khong dung nhau."
