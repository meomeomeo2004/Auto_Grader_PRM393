# build-base.ps1 - Build anh nen dung chung (chay 1 lan, hoac khi doi pubspec.base.yaml)
# Dung: .\build-base.ps1                 build + gan nhan ghim neu may chua co
#       .\build-base.ps1 -TagOnly        chi bao dam nhan ghim (khong build lai)
#       .\build-base.ps1 -RetagPinned    de len nhan ghim dang tro vao anh khac
#
# Ghi chu:
# - Loi Docker "failed to compute cache key: commit failed: input/output error"
#   thuong do BuildKit cache/WSL disk/Docker data-root bi loi hoac het dung luong.
# - Script nay tu retry bang cache sach va fallback legacy builder de giam loi tren may moi cai.

[CmdletBinding()]
param(
  [switch]$NoCache,
  [switch]$TagOnly,
  [switch]$RetagPinned
)

$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dockerfile = Join-Path $here "Dockerfile.base"
$image = "grading-base:latest"

function Show-FreeSpace($path) {
  try {
    $resolved = Resolve-Path $path
    $root = [System.IO.Path]::GetPathRoot($resolved.Path)
    if ($root -and $root.Length -ge 2) {
      $driveName = $root.Substring(0, 1)
      $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
      if ($drive) {
        $freeGb = [math]::Round($drive.Free / 1GB, 1)
        Write-Host "  Free space on ${driveName}: $freeGb GB" -ForegroundColor DarkGray
      }
    }
  } catch {}
}

function Ensure-DockerReady {
  & docker version *> $null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "  [LOI] Docker chua san sang. Hay mo Docker Desktop, doi engine Ready, roi chay lai." -ForegroundColor Red
    exit 1
  }
}

function Restart-DockerDesktop {
  Write-Host ""
  Write-Host "Restart Docker Desktop + WSL de lam sach loi I/O tam thoi..." -ForegroundColor Yellow
  try { Stop-Process -Name "Docker Desktop" -Force -ErrorAction SilentlyContinue } catch {}
  try { & wsl --shutdown 2>$null | Out-Null } catch {}

  $dd = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
  if (-not (Test-Path $dd)) { $dd = Join-Path $env:LOCALAPPDATA "Docker\Docker Desktop.exe" }
  if (Test-Path $dd) {
    Start-Process $dd | Out-Null
  } else {
    Write-Host "  [CANH BAO] Khong tim thay Docker Desktop.exe. Hay mo Docker Desktop thu cong." -ForegroundColor Yellow
  }

  $ready = $false
  for ($i = 0; $i -lt 80 -and -not $ready; $i++) {
    Start-Sleep -Seconds 3
    try { & docker version *> $null; if ($LASTEXITCODE -eq 0) { $ready = $true } } catch {}
  }
  if (-not $ready) {
    Write-Host "  [CANH BAO] Docker chua san sang sau khi restart." -ForegroundColor Yellow
  }
  return $ready
}

function Invoke-Build($title, [string]$buildKit, [bool]$useNoCache, [bool]$plainProgress) {
  Write-Host ""
  Write-Host "== $title ==" -ForegroundColor Cyan
  $env:DOCKER_BUILDKIT = $buildKit

  $args = @("build")
  if ($plainProgress) { $args += @("--progress=plain") }
  if ($useNoCache) { $args += @("--no-cache") }
  $args += @("-f", $dockerfile, "-t", $image, $here)

  & docker @args
  $code = $LASTEXITCODE
  if ($code -eq 0) {
    Write-Host "  [OK] $title" -ForegroundColor Green
    return $true
  }

  Write-Host "  [LOI] $title that bai (exit=$code)" -ForegroundColor Yellow
  return $false
}

# Backend KHONG chay bang `latest` ma ghim nhan phien ban (xem ghi chu o application.yml):
# diem cua sinh vien phu thuoc anh nay nen doi anh phai thay duoc trong git. Doc nhan
# ghim tu chinh application.yml de chi co MOT nguon su that - bump nhan o do la script
# tu theo, khong phai sua hai cho.
function Get-PinnedImage {
  $yml = Join-Path $here "..\grader\src\main\resources\application.yml"
  if (-not (Test-Path $yml)) { return "" }
  foreach ($line in (Get-Content $yml)) {
    # Dang co bien moi truong: base-image: ${GRADER_BASE_IMAGE:grading-base:<nhan>}
    if ($line -match '^\s*base-image:\s*\$\{GRADER_BASE_IMAGE:([^}]+)\}') { return $Matches[1].Trim() }
    # Dang khai thang: base-image: grading-base:<nhan>
    if ($line -match '^\s*base-image:\s*([^\s#]+)\s*$') { return $Matches[1].Trim() }
  }
  return ""
}

# Gan nhan ghim cho anh vua build. May moi clone chi co `latest`, trong khi backend goi
# `docker run <nhan-ghim>` - recorder/capture/validate KHONG tu build anh nen se chet
# ngay o buoc soan de (da xay ra 31/8 tren may thanh vien).
function Set-PinnedTag {
  $pinned = Get-PinnedImage
  if (-not $pinned -or $pinned -eq $image) { return "" }

  $pinnedId = [string](& docker images -q $pinned)
  $latestId = [string](& docker images -q $image)
  if ($pinnedId -and $pinnedId.Trim() -ne $latestId.Trim() -and -not $RetagPinned) {
    # Nhan ghim la ban dong bang dung de cham diem: khong am tham keo sang anh moi.
    Write-Host ""
    Write-Host "[BO QUA] Nhan ghim $pinned da co san va tro vao anh KHAC." -ForegroundColor Yellow
    Write-Host "  Nhan ghim quyet dinh diem cua sinh vien nen script khong tu de len."
    Write-Host "  Neu that su muon de: .\build-base.ps1 -RetagPinned"
    return $pinned
  }

  & docker tag $image $pinned | Out-Null
  if ($LASTEXITCODE -eq 0) {
    Write-Host "  [OK] Da gan nhan ghim: $pinned" -ForegroundColor Green
  } else {
    Write-Host "  [LOI] Khong gan duoc nhan ghim $pinned (exit=$LASTEXITCODE)" -ForegroundColor Yellow
    Write-Host "  Gan tay: docker tag $image $pinned"
  }
  return $pinned
}

# -TagOnly: may da co anh roi, chi thieu nhan ghim - khong bat nguoi dung cho build lai.
if ($TagOnly) {
  & docker image inspect $image *> $null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[LOI] Chua co $image tren may - chay .\build-base.ps1 (bo -TagOnly) de build truoc." -ForegroundColor Yellow
    exit 1
  }
  $pinnedTag = Set-PinnedTag
  if (-not $pinnedTag) { Write-Host "  [OK] application.yml khong ghim nhan rieng - khong can gan them." -ForegroundColor Green }
  exit 0
}

Ensure-DockerReady

Write-Host "Building grading-base:latest (lan dau co the mat 10-20 phut)..." -ForegroundColor Cyan
Show-FreeSpace $here

$ok = Invoke-Build "BuildKit build" "1" ([bool]$NoCache) $true

if (-not $ok) {
  Write-Host ""
  Write-Host "Docker build loi. Dang don BuildKit cache roi thu lai --no-cache..." -ForegroundColor Yellow
  try { & docker builder prune -af | Out-Host } catch {}
  $ok = Invoke-Build "BuildKit build --no-cache" "1" $true $true
}

if (-not $ok) {
  Write-Host ""
  Write-Host "BuildKit van loi. Thu legacy builder --no-cache (hay sua loi cache key/I/O tren Docker Desktop)..." -ForegroundColor Yellow
  $ok = Invoke-Build "Legacy docker build --no-cache" "0" $true $false
}

if (-not $ok) {
  $restarted = Restart-DockerDesktop
  if ($restarted) {
    $ok = Invoke-Build "BuildKit build --no-cache sau restart Docker" "1" $true $true
  }
}

if (-not $ok) {
  Write-Host ""
  Write-Host "[KHONG BUILD DUOC grading-base]" -ForegroundColor Red
  Write-Host "Cach xu ly tren may bi loi input/output/cache:" -ForegroundColor Yellow
  Write-Host "  1) Mo Docker Desktop -> Troubleshoot -> Restart Docker Desktop, roi chay lai grader-setup.cmd."
  Write-Host "  2) Dam bao o dia Docker data la o cung noi bo, dinh dang NTFS, con toi thieu 30-40GB trong."
  Write-Host "  3) Khong dat Docker data tren USB/network drive/thu muc sync cloud."
  Write-Host "  4) Chay PowerShell Admin: wsl --shutdown, mo Docker Desktop lai, roi chay:"
  Write-Host "       cd grader-base"
  Write-Host "       .\build-base.ps1 -NoCache"
  Write-Host "  5) Neu van loi: Docker Desktop -> Troubleshoot -> Clean / Purge data, sau do chay lai grader-setup.cmd."
  exit 1
}

$pinnedTag = Set-PinnedTag

Write-Host ""
if ($pinnedTag) {
  Write-Host "OK - $image + $pinnedTag da san sang. Cac de thi se build trong vai giay." -ForegroundColor Green
} else {
  Write-Host "OK - $image da san sang. Cac de thi se build trong vai giay." -ForegroundColor Green
}
