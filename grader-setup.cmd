@echo off
REM ============================================================================
REM  grader-setup.cmd - CHI cai thanh phan nen hien tai:
REM  Docker Desktop / Node.js LTS / Java JDK 17+ / anh nen cham bai grading-base.
REM  KHONG cai thanh phan nao khac. KHONG tao ban sao repo.
REM  Cai xong thi chay GraderLauncher.exe de mo backend + frontend.
REM
REM  Cach dung:
REM    - Double-click file nay, HOAC go  grader-setup  trong terminal tai repo.
REM    - Tu xin quyen admin (winget can quyen admin de cai Docker/Node/JDK/...).
REM    - Chay lai duoc nhieu lan (idempotent): thu gi da co thi bo qua.
REM ============================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

REM 1) Bao dam quyen admin; neu chua thi mo lai chinh file nay voi UAC roi thoat.
net session >nul 2>&1
if errorlevel 1 (
  echo === Xin quyen admin de cai thanh phan nen... ===
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
  exit /b
)

REM -- CHON O CAI DAT DOCKER DATA (images/containers/volumes) -----------------
echo.
echo ============================================================
echo   CHON O DIA LUU DATA DOCKER
echo   (App Docker Desktop van cai vao C:\Program Files - bat buoc)
echo   Data nang (images, containers, volumes) co the dat o dia khac.
echo ============================================================
echo.

REM Liet ke cac o dia hien co
echo   Cac o dia hien co tren may:
powershell -NoProfile -Command "Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Used -ne $null } | ForEach-Object { Write-Host ('    ' + $_.Name + ':  Free=' + [math]::Round($_.Free/1GB,1) + ' GB  /  Total=' + [math]::Round(($_.Used+$_.Free)/1GB,1) + ' GB') }"
echo.
set "DOCKER_DRIVE=C"
set /p DOCKER_DRIVE="  Nhap ten o (chi can 1 chu cai, vi du D hoac E) [mac dinh: C]: "

REM Chuan hoa: viet hoa, bo dau :
for %%A in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
  if /i "!DOCKER_DRIVE!"=="%%A" set "DOCKER_DRIVE=%%A"
  if /i "!DOCKER_DRIVE!"=="%%A:" set "DOCKER_DRIVE=%%A"
)

REM Kiem tra o co ton tai khong
if not exist "!DOCKER_DRIVE!:\" (
  echo   [CANH BAO] O !DOCKER_DRIVE!: khong tim thay, se dung C: mac dinh.
  set "DOCKER_DRIVE=C"
)

set "DOCKER_DATA_ROOT=!DOCKER_DRIVE!:\DockerData"
echo.
echo   => Docker data se luu tai: !DOCKER_DATA_ROOT!
echo.

REM 2) Cai prereqs NGAY TAI repo nay (-AppDir = thu muc chua file nay).
echo === Cai thanh phan nen (lan dau co the RAT lau: tai Docker/JDK/Node + build grading-base) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\setup-prereqs.ps1" -AppDir "%~dp0." -DockerDataRoot "!DOCKER_DATA_ROOT!"
if errorlevel 1 (
  echo.
  echo [LOI] Setup chua hoan tat. Hay xem loi/canh bao phia tren, sua theo huong dan roi chay lai grader-setup.
  echo      Neu vua cai Docker lan dau: khoi dong lai may, mo Docker Desktop 1 lan, roi chay lai file nay.
  echo.
  pause
  exit /b 1
)

echo.
echo [OK] Xong buoc cai thanh phan nen.
echo   - Docker data root: !DOCKER_DATA_ROOT!
echo   - Neu VUA cai Docker lan dau: khoi dong lai may, mo Docker Desktop 1 lan,
echo     roi chay lai  grader-setup  (de build anh grading-base).
echo   - Chay app: double-click  GraderLauncher.exe  hoac go  run
echo     trong terminal. Launcher chi mo backend va frontend.
echo   - Sau do mo dia chi ma trinh khoi dong in ra o dong cuoi
echo.
pause
endlocal
