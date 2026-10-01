#!/usr/bin/env bash
# Запуск Telegram-бота Whisprompt на Android через Termux.
# Секреты берутся из gitignored txt-файлов рядом (как в run_bot.ps1).
# Использование:  bash run_termux.sh
set -e
cd "$(dirname "$0")"

[ -f bot_token.txt ] || { echo "Нет bot_token.txt"; exit 1; }
[ -f license_private_b64.txt ] || { echo "Нет license_private_b64.txt"; exit 1; }

export BOT_TOKEN="$(tr -d '\r\n' < bot_token.txt)"
export LICENSE_PRIV_B64="$(tr -d '\r\n' < license_private_b64.txt)"
export PRICE_STARS="${PRICE_STARS:-150}"

if [ -f xrocket_token.txt ]; then
  export XROCKET_TOKEN="$(tr -d '\r\n' < xrocket_token.txt)"
  export XROCKET_CURRENCY="${XROCKET_CURRENCY:-USDT}"
  export XROCKET_AMOUNT="${XROCKET_AMOUNT:-3.2}"
fi
if [ -f cryptobot_token.txt ]; then
  export CRYPTOBOT_TOKEN="$(tr -d '\r\n' < cryptobot_token.txt)"
  export CRYPTO_FIAT="${CRYPTO_FIAT:-EUR}"
  export CRYPTO_AMOUNT="${CRYPTO_AMOUNT:-3}"
fi

# Не давать телефону усыплять процесс (нужен пакет termux-api; без него просто пропустится).
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

echo "Starting Whisprompt bot..."
exec python bot.py
