<#
  start-all.ps1 - Chay CA he thong chi bang 1 lenh (dat o GOC repo Grader_App).
  Dieu phoi: MySQL (docker) -> Backend -> Frontend.
  Moi service mo trong 1 cua so PowerShell rieng (dong cua so = tat service do).

  Cach dung (mo terminal tai thu muc Grader_App):
      powershell -ExecutionPolicy Bypass -File .\start-all.ps1
#>

param(
  [switch]$SkipMysql,             # bo qua docker compose (neu MySQL da chay)
  [ValidateSet('gv','nc')]
  [string]$Vai                    # vai khi chay THANG trong repo; ban giao thi doc tu vai.ps1
)

# KHONG dung "Stop": 1 loi khong nghiem trong (vd Docker chua bat)
# se lam DUNG ca script -> app khong len. Dung "Continue"; cac loi NGHIEM TRONG van chan bang `throw`.
$ErrorActionPreference = "Continue"
$root       = $PSScriptRoot          # = thu muc Grader_App (moi thu nam trong day)
$composeDir = $root                  # docker-compose.yml o goc Grader_App
$beDir      = Join-Path $root "grader"
$feDir      = Join-Path $root "frontend"

# -- Vai cua ban dang chay -----------------------------------------------------
# He thong KHONG con "ban day du". Ly do khong phai gon gang: ban day du la cau hinh duy nhat
# khong di qua khau ban giao (giang vien xuat ban xong la hien ngay ben cham, vi chung mot co
# so du lieu), nen no la cau hinh duy nhat co the CHE mot khau ban giao dang hong - ma do lai
# la duong ma ca hai nguoi nhan deu phai di. Vi vay moi lan chay deu phai chon vai.
#
# Hai duong khai vai:
#   - Ban giao (dist\gv, dist\nc): doc tu vai.ps1 do dong-goi.ps1 sinh ra.
#   - Chay thang trong repo de phat trien: truyen -Vai gv  hoac  -Vai nc
$BePort0 = $null
$FePort  = $null
$Schema  = $null

$vaiFile = Join-Path $root "vai.ps1"
if (Test-Path $vaiFile) {
  . $vaiFile                       # ghi de $Vai, $BePort0, $FePort, $Schema
} elseif ($Vai) {
  $cauHinhFile = Join-Path $root "vai-cau-hinh.ps1"
  if (-not (Test-Path $cauHinhFile)) { throw "Khong tim thay vai-cau-hinh.ps1 ben canh start-all.ps1." }
  . $cauHinhFile
  $c = $CauHinhVai[$Vai]
  $BePort0 = $c.BePort; $FePort = $c.FePort; $Schema = $c.Schema
} else {
  Write-Host ""
  Write-Host "[LOI] Chua chon vai. He thong da tach lam hai ban, khong con ban thay ca hai." -ForegroundColor Red
  Write-Host "  Chay thang trong repo :  .\run -Vai gv      (hoac -Vai nc)" -ForegroundColor Yellow
  Write-Host "  Chay ban da cat       :  vao dist\gv hoac dist\nc roi chay .\run" -ForegroundColor Yellow
  Write-Host "  Cat ban giao          :  .\dong-goi.ps1" -ForegroundColor Yellow
  Write-Host ""
  exit 1
}

function Have($cmd) { return [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Section($t) { Write-Host "`n==== $t ====" -ForegroundColor Cyan }
function Wait-MySqlReady([int]$timeoutSec = 120) {
  Write-Host "  Doi MySQL san sang..." -ForegroundColor DarkGray
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $healthy = $false
    try {
      $cid = (& docker compose ps -q mysql 2>$null | Select-Object -First 1)
      if ($cid) {
        $status = (& docker inspect --format "{{.State.Health.Status}}" $cid 2>$null | Select-Object -First 1)
        if ($status -eq "healthy") { $healthy = $true }
      }
    } catch {}
    if (-not $healthy) {
      try {
        $tcp = Test-NetConnection -ComputerName "127.0.0.1" -Port 3306 -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($tcp) { $healthy = $true }
      } catch {}
    }
    if ($healthy) {
      Write-Host "  [OK] MySQL da san sang" -ForegroundColor Green
      return $true
    }
    Start-Sleep -Seconds 2
  }
  Write-Host "  [CANH BAO] MySQL chua bao san sang sau $timeoutSec giay; backend se tu doi them khi khoi dong." -ForegroundColor Yellow
  return $false
}
function Get-JdkHome {
  $candidates = @()
  foreach ($scope in @("Process","User","Machine")) {
    $jh = [Environment]::GetEnvironmentVariable("JAVA_HOME", $scope)
    if ($jh) { $candidates += $jh }
  }

  $javac = Get-Command "javac" -ErrorAction SilentlyContinue
  if ($javac -and $javac.Source) {
    $candidates += (Split-Path (Split-Path $javac.Source -Parent) -Parent)
  }

  $roots = @(
    (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
    (Join-Path $env:ProgramFiles "Java"),
    (Join-Path $env:ProgramFiles "Microsoft")
  )
  $pf86 = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")
  if ($pf86) { $roots += (Join-Path $pf86 "Eclipse Adoptium") }

  foreach ($root in $roots) {
    if (Test-Path $root) {
      Get-ChildItem -Path $root -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { $candidates += $_.FullName }
    }
  }

  foreach ($candidateHome in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
    try {
      $resolved = (Resolve-Path $candidateHome -ErrorAction Stop).Path
      $javacExe = Join-Path $resolved "bin\javac.exe"
      if (Test-Path $javacExe) {
        $ver = (& $javacExe -version 2>&1 | Out-String).Trim()
        if ($ver -match '^javac\s+(\d+)(?:\.|$)' -and [int]$Matches[1] -ge 17) { return $resolved }
      }
    } catch {}
  }
  return $null
}
function Use-Jdk([string]$jdkHome) {
  if (-not $jdkHome) { return }
  $env:JAVA_HOME = $jdkHome
  $jdkBin = Join-Path $jdkHome "bin"
  if (($env:Path -split ';' | ForEach-Object { $_.Trim().TrimEnd('\') }) -notcontains $jdkBin.TrimEnd('\')) {
    $env:Path = "$jdkBin;$env:Path"
  }
}

# -- 0) Kiem tra cong cu can thiet --------------------------------------------
Section "Kiem tra cong cu"
foreach ($t in @("node","npm","docker")) {
  if (Have $t) { Write-Host "  [OK] $t" -ForegroundColor Green }
  else { Write-Host "  [THIEU] $t - cai dat truoc khi chay" -ForegroundColor Yellow }
}
$backendJdkHome = Get-JdkHome
if ($backendJdkHome) {
  Use-Jdk $backendJdkHome
  Write-Host "  [OK] JDK 17+: $backendJdkHome" -ForegroundColor Green
} else {
  Write-Host "  [THIEU] JDK 17+/javac - chay grader-setup.cmd de backend bien dich duoc." -ForegroundColor Yellow
}
if (-not (Test-Path $beDir))  { throw "Khong thay backend tai: $beDir" }

# -- 1) Docker: bao dam engine chay -> MySQL -> kiem tra anh nen cham bai ------
if (-not $SkipMysql) {
  Section "Docker (MySQL + anh nen cham bai)"
  if (-not (Have "docker")) {
    Write-Host "  [THIEU] docker - cai Docker Desktop truoc (bo qua MySQL)." -ForegroundColor Yellow
  } else {
    # Bao dam Docker engine dang chay (may moi cai Docker can mo 1 lan, doi khi phai reboot)
    $dockerOk = $false
    try { & docker info *> $null; if ($LASTEXITCODE -eq 0) { $dockerOk = $true } } catch {}
    if (-not $dockerOk) {
      $dd = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
      if (Test-Path $dd) {
        Write-Host "  Khoi dong Docker Desktop, cho engine san sang..." -ForegroundColor Yellow
        Start-Process $dd | Out-Null
        for ($i = 0; $i -lt 60 -and -not $dockerOk; $i++) {
          Start-Sleep -Seconds 3
          try { & docker info *> $null; if ($LASTEXITCODE -eq 0) { $dockerOk = $true } } catch {}
        }
      }
    }
    if ($dockerOk) {
      Push-Location $composeDir
      try {
        & docker compose up -d
        if ($LASTEXITCODE -eq 0) {
          Write-Host "  MySQL dang chay (cong 3306)" -ForegroundColor Green
          Wait-MySqlReady 150 | Out-Null
        } elseif (Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue) {
          # Cong 3306 da co MySQL khac chiem; backend van dung duoc - chuoi ket noi mang
          # createDatabaseIfNotExist=true nen no tu tao schema cua vai minh o do.
          Write-Host "  [CANH BAO] docker compose khong bind duoc 3306, dang dung MySQL san co tren cong 3306." -ForegroundColor Yellow
          Wait-MySqlReady 60 | Out-Null
        } else {
          Write-Host "  [LOI] docker compose that bai - MySQL chua san sang." -ForegroundColor Yellow
        }
      }
      catch { Write-Host "  [LOI] docker compose that bai: $($_.Exception.Message)" -ForegroundColor Yellow }
      Pop-Location
      # Anh nen 'grading-base' (Flutter SDK) la BAT BUOC de CHAM BAI. Build 1 lan bang setup-prereqs.ps1.
      $hasBase = $false
      try { & docker image inspect grading-base:latest *> $null; if ($LASTEXITCODE -eq 0) { $hasBase = $true } } catch {}
      if ($hasBase) { Write-Host "  [OK] anh nen grading-base da co" -ForegroundColor Green }
      else { Write-Host "  [CANH BAO] Chua co anh nen grading-base -> CHUA cham bai duoc. Chay: grader-base\build-base.ps1" -ForegroundColor Yellow }
    } else {
      Write-Host "  [CANH BAO] Docker chua san sang. Mo Docker Desktop roi chay lai (cham bai can Docker)." -ForegroundColor Yellow
    }
  }
}

# -- 2) Mo 2 cua so: backend, frontend ----------------------------------------
Section "Khoi dong cac service (moi service 1 cua so)"

# Tranh loi "Port already in use": CHI don instance CU CUA CHINH APP (java/node) tren cong,
# KHONG dung tien trinh la (vd Tomcat cua ban tren 8080).
function Free-Port($port, $name, [string[]]$onlyProc) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
      $procId = $_
      if (-not $procId -or $procId -eq 0) { return }
      $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
      if (-not $p) { return }
      if ($onlyProc -and ($onlyProc -notcontains $p.ProcessName)) {
        Write-Host "  Cong $port dang bi '$($p.ProcessName)' (PID $procId) chiem - GIU NGUYEN (khong phai service cua app)." -ForegroundColor DarkYellow
        return
      }
      try {
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Write-Host "  Giai phong cong $port ($name): da dung '$($p.ProcessName)' (PID $procId)" -ForegroundColor Yellow
      } catch {}
    }
}
Free-Port $BePort0 "backend"  @('java','javaw')
Free-Port $FePort  "frontend" @('node')

# Backend: neu cong uu tien van bi chiem (vd Tomcat) -> tu chon cong trong ke tiep; frontend tro theo.
function Test-PortFree($port) { -not (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }
$bePort = $BePort0
if (-not (Test-PortFree $BePort0)) {
  $cand = $BePort0 + 1; $tran = $BePort0 + 19
  while ($cand -le $tran -and -not (Test-PortFree $cand)) { $cand++ }
  $bePort = $cand
  Write-Host "  Cong $BePort0 dang ban -> backend dung cong $bePort (khong dung tien trinh khac)." -ForegroundColor Yellow
}
# Ghi NEXT_PUBLIC_API_BASE vao frontend/.env.local (giu cac dong khac) de FE goi dung cong backend.
$apiBase  = "http://localhost:$bePort/api"
$envLocal = Join-Path $feDir ".env.local"
$keep = @(); $oldApiBase = ""; $oldVai = ""
if (Test-Path $envLocal) {
  $all  = Get-Content $envLocal
  $keep = $all | Where-Object { $_ -notmatch '^\s*NEXT_PUBLIC_(API_BASE|ROLE)\s*=' }
  $hit  = $all | Where-Object { $_ -match '^\s*NEXT_PUBLIC_API_BASE\s*=' } | Select-Object -First 1
  if ($hit) { $oldApiBase = ($hit -replace '^\s*NEXT_PUBLIC_API_BASE\s*=\s*', '').Trim() }
  $hitVai = $all | Where-Object { $_ -match '^\s*NEXT_PUBLIC_ROLE\s*=' } | Select-Object -First 1
  if ($hitVai) { $oldVai = ($hitVai -replace '^\s*NEXT_PUBLIC_ROLE\s*=\s*', '').Trim() }
}
# Next.js NHUNG CUNG NEXT_PUBLIC_* vao bundle luc bien dich, khong doc lai luc chay. Neu cong
# backend doi ma cache .next van con chunk cu thi trinh duyet goi sang cong CU -> loi
# "Failed to fetch" du backend van song. Doi cong = phai xoa cache, khong co cach nao khac.
if ($oldApiBase -ne $apiBase -or $oldVai -ne $Vai) {
  $nextDir = Join-Path $feDir ".next"
  if (Test-Path $nextDir) {
    if ($oldVai -ne $Vai) {
      # VAI cung la NEXT_PUBLIC_*, cung bi nhung vao bundle luc bien dich. Doi vai ma giu cache
      # cu la menu van hien theo vai truoc do - nhin thi tuong phep tach khong an.
      Write-Host "  Doi vai ($oldVai -> $Vai) -> xoa cache frontend." -ForegroundColor Yellow
    } elseif ($oldApiBase -eq "") {
      Write-Host "  Chua ro cong backend lan truoc -> xoa cache frontend cho chac." -ForegroundColor Yellow
    } else {
      Write-Host "  Backend doi cong ($oldApiBase -> $apiBase) -> xoa cache frontend." -ForegroundColor Yellow
    }
    try {
      Remove-Item -LiteralPath $nextDir -Recurse -Force -ErrorAction Stop
      Write-Host "  [OK] Da xoa frontend\.next (lan khoi dong dau se lau hon vi phai bien dich lai)." -ForegroundColor Green
    } catch {
      Write-Host "  [LOI] Khong xoa duoc frontend\.next : $($_.Exception.Message)" -ForegroundColor Yellow
      Write-Host "        Hay dong het cua so dev roi xoa tay thu muc do." -ForegroundColor Yellow
    }
  }
}
# Ghi KHONG BOM: 'Set-Content -Encoding utf8' tren PS 5.1 chen BOM -> Next.js doc sai bien
# dau dong (NEXT_PUBLIC_API_BASE) -> FE goi sai cong backend. Dung UTF8 khong BOM.
$lines = @($keep) + "NEXT_PUBLIC_ROLE=$Vai" + "NEXT_PUBLIC_API_BASE=$apiBase"
[System.IO.File]::WriteAllText($envLocal, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))

# powershell.exe day du duong dan (tranh 'khong thay file')
$psExe = (Get-Command powershell -ErrorAction SilentlyContinue).Source
if (-not $psExe) { $psExe = "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" }

# Mo 1 cua so service; try/catch de 1 cai loi KHONG chan cac cai con lai.
function Launch($title, $cmd) {
  try {
    Start-Process $psExe -ArgumentList "-NoExit","-NoProfile","-ExecutionPolicy","Bypass","-Command",$cmd | Out-Null
    Write-Host "  [OK] Da mo cua so: $title" -ForegroundColor Green
  } catch {
    Write-Host "  [LOI] Khong mo duoc cua so $title : $($_.Exception.Message)" -ForegroundColor Yellow
  }
}

# Backend Spring Boot (chay tren host). Windows DUNG mvnw.cmd. Cong = $bePort (8080 hoac cong trong).
$beCmd = @"
Set-Location '$beDir'
if ('$backendJdkHome') {
  `$env:JAVA_HOME = '$backendJdkHome'
  `$env:Path = (Join-Path `$env:JAVA_HOME 'bin') + ';' + `$env:Path
  Write-Host "JDK: `$env:JAVA_HOME" -ForegroundColor DarkGray
} else {
  Write-Host '[LOI] Khong thay JDK 17+ (javac). Hay chay grader-setup.cmd roi mo lai GraderLauncher.exe.' -ForegroundColor Red
  return
}
`$env:SERVER_PORT = '$bePort'
# Vai quyet dinh controller nao duoc nap; schema rieng de hai ban chay song song tren cung
# mot MySQL ma khong nhin thay du lieu cua nhau.
`$env:GRADER_ROLE = '$Vai'
`$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/$Schema' + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&createDatabaseIfNotExist=true'
Write-Host "Vai: $Vai | Schema: $Schema" -ForegroundColor DarkGray
Write-Host 'Doi MySQL/JDBC san sang...' -ForegroundColor DarkGray
for (`$i = 0; `$i -lt 60; `$i++) {
  try {
    if (Test-NetConnection -ComputerName '127.0.0.1' -Port 3306 -InformationLevel Quiet -WarningAction SilentlyContinue) { break }
  } catch {}
  Start-Sleep -Seconds 2
}
Write-Host 'Backend: http://localhost:$bePort' -ForegroundColor Green
.\mvnw.cmd spring-boot:run
"@
Launch "Backend (:$bePort)" $beCmd

# Frontend Next.js. Cong truyen thang cho next chu KHONG qua .env: bien PORT trong .env.local
# khong chac duoc doc truoc luc server bind, ma sai cong thi hai ban dam nhau.
$feCmd = @"
Set-Location '$feDir'
if (-not (Test-Path 'node_modules')) { Write-Host 'npm install (lan dau)...' -ForegroundColor Yellow; npm install }
Write-Host 'Frontend: http://localhost:$FePort' -ForegroundColor Green
npm run dev -- -p $FePort
"@
Launch "Frontend (:$FePort)" $feCmd

Section "Xong"
Write-Host "  Frontend : http://localhost:$FePort"
Write-Host "  Backend  : http://localhost:$bePort"
Write-Host "  Tat: dong tung cua so service (hoac Ctrl+C trong cua so do)." -ForegroundColor DarkGray
