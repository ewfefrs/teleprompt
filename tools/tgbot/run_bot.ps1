# Run the Telegram key-selling bot. Secrets are read from gitignored files next to this
# script: bot_token.txt, license_private_b64.txt (and xrocket_token.txt / cryptobot_token.txt).
# ASCII-only on purpose so Windows PowerShell 5.1 parses it regardless of code page.
#
#   powershell -ExecutionPolicy Bypass -File tools\tgbot\run_bot.ps1
#
$ErrorActionPreference = "Stop"
$dir = $PSScriptRoot

$tokenFile = Join-Path $dir "bot_token.txt"
$privFile  = Join-Path $dir "license_private_b64.txt"
if (-not (Test-Path $tokenFile)) { throw "Missing $tokenFile (put your BotFather token there)." }
if (-not (Test-Path $privFile))  { throw "Missing $privFile (private key from license.py genkeys)." }

$env:BOT_TOKEN        = (Get-Content $tokenFile -Raw).Trim()
$env:LICENSE_PRIV_B64 = (Get-Content $privFile  -Raw).Trim()
if (-not $env:PRICE_STARS) { $env:PRICE_STARS = "150" }

# Crypto via xRocket (@xrocket) - primary provider.
$xrFile = Join-Path $dir "xrocket_token.txt"
if (Test-Path $xrFile) {
    $env:XROCKET_TOKEN = (Get-Content $xrFile -Raw).Trim()
    if (-not $env:XROCKET_CURRENCY) { $env:XROCKET_CURRENCY = "USDT" }
    if (-not $env:XROCKET_AMOUNT)   { $env:XROCKET_AMOUNT   = "3.2" }
}

# Or via CryptoBot (@send) if you drop its token.
$cbFile = Join-Path $dir "cryptobot_token.txt"
if (Test-Path $cbFile) {
    $env:CRYPTOBOT_TOKEN = (Get-Content $cbFile -Raw).Trim()
    if (-not $env:CRYPTO_FIAT)   { $env:CRYPTO_FIAT   = "EUR" }
    if (-not $env:CRYPTO_AMOUNT) { $env:CRYPTO_AMOUNT = "3" }
}

# Find Python: py launcher, else winget/python.org install path, else 'python'.
$py = "py"
if (-not (Get-Command py -ErrorAction SilentlyContinue)) {
    $cand = Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"
    if (Test-Path $cand) { $py = $cand } else { $py = "python" }
}

Set-Location (Resolve-Path (Join-Path $dir "..\..")).Path
Write-Host "Starting bot with $py ..."
& $py tools\tgbot\bot.py
