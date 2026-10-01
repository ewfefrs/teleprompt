# Ставит АВТОЗАПУСК трей-бота Whisprompt с Windows: кладёт ярлык в папку автозагрузки,
# который запускает bot_tray.pyw через pythonw.exe (без окна, иконка в трее).
#   powershell -ExecutionPolicy Bypass -File install_autostart.ps1
$ErrorActionPreference = "Stop"
$dir = $PSScriptRoot
$script = Join-Path $dir "bot_tray.pyw"
$py = Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\pythonw.exe"
if (-not (Test-Path $py)) { $py = "pythonw.exe" }

$startup = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup"
$lnk = Join-Path $startup "Whisprompt Bot.lnk"

$ws = New-Object -ComObject WScript.Shell
$sc = $ws.CreateShortcut($lnk)
$sc.TargetPath = $py
$sc.Arguments = '"' + $script + '"'
$sc.WorkingDirectory = $dir
$sc.WindowStyle = 7
$sc.Description = "Whisprompt Telegram bot (tray)"
$sc.Save()
Write-Host "Autostart installed: $lnk"
