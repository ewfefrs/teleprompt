# Убирает автозапуск трей-бота Whisprompt (удаляет ярлык из папки автозагрузки).
#   powershell -ExecutionPolicy Bypass -File uninstall_autostart.ps1
$lnk = Join-Path (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup") "Whisprompt Bot.lnk"
if (Test-Path $lnk) { Remove-Item $lnk -Force; Write-Host "Autostart removed: $lnk" }
else { Write-Host "No autostart shortcut found." }
