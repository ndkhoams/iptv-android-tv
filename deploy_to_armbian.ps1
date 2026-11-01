# Script tự động đẩy sports_updater.py lên máy chủ Armbian và kích hoạt dịch vụ
$PSCP = "C:\Program Files\PuTTY\pscp.exe"
$PLINK = "C:\Program Files\PuTTY\plink.exe"
$HOSTKEY = "ssh-ed25519 255 SHA256:vHTgGqm8MDox9U0qgdvuk/tzd3DgFg48tNczaEK6Vsc"
$IP = "10.0.0.222"
$USER = "root"
$PW = "armbian"
$REMOTE_PATH = "/opt/iptv-sports/sports_updater.py"

Write-Host "[+] Đang tải sports_updater.py lên Armbian ($IP)..." -ForegroundColor Cyan
& $PSCP -batch -pw $PW -hostkey $HOSTKEY sports_updater.py "${USER}@${IP}:${REMOTE_PATH}"

if ($LASTEXITCODE -eq 0) {
    Write-Host "[+] Tải lên thành công! Đang cấp quyền và kích hoạt chạy thử sports-updater.service..." -ForegroundColor Green
    & $PLINK -ssh -l $USER -pw $PW -batch -hostkey $HOSTKEY $IP "chmod +x $REMOTE_PATH; systemctl start sports-updater.service; sleep 2; systemctl status sports-updater.service --no-pager"
} else {
    Write-Host "[-] Lỗi khi tải file qua pscp!" -ForegroundColor Red
}

