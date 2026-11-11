# Antigravity Chat Sync Script (Home <-> Work)
param (
    [Parameter(Mandatory=$false)]
    [ValidateSet("Export", "Import", "SetupJunction", "Status")]
    [string]$Action = "Status"
)

$LocalAgyDir = "$env:USERPROFILE\.gemini\antigravity"
$candidateSyncDirs = @(
    "D:\Dropbox\VS\antigr\.antigravity_chat_sync",
    (Join-Path (Split-Path $PSScriptRoot -Parent) ".antigravity_chat_sync"),
    (Join-Path $PSScriptRoot ".antigravity_chat_sync")
)
$DropboxSyncDir = $candidateSyncDirs[0]
foreach ($dir in $candidateSyncDirs) {
    if (Test-Path $dir) {
        $DropboxSyncDir = $dir
        break
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  ANTIGRAVITY CHAT SYNC (NHA <-> CONG TY)                 " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [Local Dir]   : $LocalAgyDir" -ForegroundColor Gray
Write-Host " [Dropbox Dir] : $DropboxSyncDir" -ForegroundColor Gray
Write-Host ""

if ($Action -eq "Status") {
    Write-Host "Tinh trang du lieu hien tai:" -ForegroundColor Yellow
    if (Test-Path "$LocalAgyDir\conversations") {
        $convs = Get-ChildItem "$LocalAgyDir\conversations" -Filter "*.db" -ErrorAction SilentlyContinue
        Write-Host " -> May nay co $($convs.Count) phien chat trong $LocalAgyDir\conversations" -ForegroundColor Green
    }
    if (Test-Path $DropboxSyncDir) {
        $syncConvs = Get-ChildItem "$DropboxSyncDir\conversations" -Filter "*.db" -ErrorAction SilentlyContinue
        Write-Host " -> Dropbox co $($syncConvs.Count) phien chat da sao luu" -ForegroundColor Green
    } else {
        Write-Host " -> Chua co ban sao luu nao tren Dropbox." -ForegroundColor Gray
    }
    Write-Host ""
    Write-Host "Huong dan su dung:" -ForegroundColor Yellow
    Write-Host " 1. Khi lam xong tren may nay: chay '.\sync_chat.ps1 -Action Export'" -ForegroundColor White
    Write-Host " 2. Khi sang may khac:       chay '.\sync_chat.ps1 -Action Import'" -ForegroundColor White
    Write-Host " 3. Hoac lien ket tu dong:   chay '.\sync_chat.ps1 -Action SetupJunction'" -ForegroundColor White
    exit 0
}

if ($Action -eq "Export") {
    Write-Host "[1/3] Dang sao luu phien chat vao Dropbox..." -ForegroundColor Yellow
    if (-not (Test-Path $DropboxSyncDir)) {
        New-Item -ItemType Directory -Path $DropboxSyncDir -Force | Out-Null
    }

    if (Test-Path "$LocalAgyDir\conversations") {
        Write-Host " -> Dang copy conversations..." -ForegroundColor Gray
        $destConv = Join-Path $DropboxSyncDir "conversations"
        if (-not (Test-Path $destConv)) { New-Item -ItemType Directory -Path $destConv -Force | Out-Null }
        Copy-Item -Path "$LocalAgyDir\conversations\*" -Destination $destConv -Recurse -Force
    }

    if (Test-Path "$LocalAgyDir\brain") {
        Write-Host " -> Dang copy brain..." -ForegroundColor Gray
        $destBrain = Join-Path $DropboxSyncDir "brain"
        if (-not (Test-Path $destBrain)) { New-Item -ItemType Directory -Path $destBrain -Force | Out-Null }
        Copy-Item -Path "$LocalAgyDir\brain\*" -Destination $destBrain -Recurse -Force
    }

    if (Test-Path "$LocalAgyDir\conversation_summaries.db") {
        Copy-Item -Path "$LocalAgyDir\conversation_summaries.db*" -Destination $DropboxSyncDir -Force
    }

    Write-Host "[OK] Da sao luu thanh cong toan bo phien chat len Dropbox!" -ForegroundColor Green
    Write-Host "Dropbox se tu dong dong bo sang may cong ty trong giay lat." -ForegroundColor Cyan
}

if ($Action -eq "Import") {
    Write-Host "[1/3] Dang nap phien chat tu Dropbox vao may nay..." -ForegroundColor Yellow
    if (-not (Test-Path $DropboxSyncDir)) {
        Write-Host "[!] Loi: Khong tim thay thu muc sao luu $DropboxSyncDir tren Dropbox!" -ForegroundColor Red
        exit 1
    }

    if (-not (Test-Path $LocalAgyDir)) {
        New-Item -ItemType Directory -Path $LocalAgyDir -Force | Out-Null
    }

    if (Test-Path "$DropboxSyncDir\conversations") {
        Write-Host " -> Dang khoi phuc conversations..." -ForegroundColor Gray
        $targetConv = Join-Path $LocalAgyDir "conversations"
        if (-not (Test-Path $targetConv)) { New-Item -ItemType Directory -Path $targetConv -Force | Out-Null }
        Copy-Item -Path "$DropboxSyncDir\conversations\*" -Destination $targetConv -Recurse -Force
    }

    if (Test-Path "$DropboxSyncDir\brain") {
        Write-Host " -> Dang khoi phuc brain..." -ForegroundColor Gray
        $targetBrain = Join-Path $LocalAgyDir "brain"
        if (-not (Test-Path $targetBrain)) { New-Item -ItemType Directory -Path $targetBrain -Force | Out-Null }
        Copy-Item -Path "$DropboxSyncDir\brain\*" -Destination $targetBrain -Recurse -Force
    }

    if (Test-Path "$DropboxSyncDir\conversation_summaries.db") {
        Copy-Item -Path "$DropboxSyncDir\conversation_summaries.db*" -Destination $LocalAgyDir -Force
    }

    Write-Host "[OK] Da nap thanh cong toan bo phien chat!" -ForegroundColor Green
    Write-Host "Hay khoi dong lai Antigravity de xem cac cuoc tro chuyen da dong bo." -ForegroundColor Cyan
}

if ($Action -eq "SetupJunction") {
    Write-Host "Thiet lap lien ket tu dong (Directory Junction) qua Dropbox..." -ForegroundColor Yellow
    Write-Host "LUU Y: Vui long tat hoan toan ung dung Antigravity truoc khi thuc hien!" -ForegroundColor Red
    
    $confirm = Read-Host "Ban da tat Antigravity chua? (y/n)"
    if ($confirm -ne "y") {
        Write-Host "Huy thao tac." -ForegroundColor Gray
        exit 0
    }

    if (-not (Test-Path $DropboxSyncDir)) {
        New-Item -ItemType Directory -Path $DropboxSyncDir -Force | Out-Null
    }

    $targetConv = "$DropboxSyncDir\conversations"
    $targetBrain = "$DropboxSyncDir\brain"
    if (-not (Test-Path $targetConv) -and (Test-Path "$LocalAgyDir\conversations")) {
        Move-Item "$LocalAgyDir\conversations" $targetConv
    }
    if (-not (Test-Path $targetBrain) -and (Test-Path "$LocalAgyDir\brain")) {
        Move-Item "$LocalAgyDir\brain" $targetBrain
    }

    if (-not (Test-Path "$LocalAgyDir\conversations")) {
        cmd /c "mklink /J `"$LocalAgyDir\conversations`" `"$targetConv`""
    }
    if (-not (Test-Path "$LocalAgyDir\brain")) {
        cmd /c "mklink /J `"$LocalAgyDir\brain`" `"$targetBrain`""
    }

    Write-Host "[OK] Da thiet lap lien ket tu dong thanh cong!" -ForegroundColor Green
    Write-Host "Tu bay gio, moi doan chat se duoc Dropbox luu thang vao cloud." -ForegroundColor Cyan
}
