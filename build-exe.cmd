@echo off
REM build-exe.cmd — Bien dich launcher.cs -> GraderLauncher.exe bang csc.exe co san trong Windows.
REM Khong can cai them gi (dung .NET Framework 4 dong goi san trong Windows 10/11).
setlocal

set "CSC="
for %%d in (Framework64 Framework) do (
  if not defined CSC if exist "%WINDIR%\Microsoft.NET\%%d\v4.0.30319\csc.exe" set "CSC=%WINDIR%\Microsoft.NET\%%d\v4.0.30319\csc.exe"
)

if not defined CSC (
  echo [LOI] Khong tim thay csc.exe ^(.NET Framework 4^).
  echo       Cach khac: dung ps2exe ^(xem huong dan trong cau tra loi^).
  pause
  exit /b 1
)

echo Dang bien dich GraderLauncher.exe ...
"%CSC%" /nologo /target:exe /out:"%~dp0GraderLauncher.exe" "%~dp0launcher.cs"
if errorlevel 1 (
  echo [LOI] Bien dich that bai.
  pause
  exit /b 1
)

echo.
echo [OK] Da tao: %~dp0GraderLauncher.exe
echo Giao vien chi viec double-click GraderLauncher.exe de chay toan bo.
pause
