#!/data/data/com.termux/files/usr/bin/bash
# Остановить фоновый Whisprompt-бот и снять wake-lock.
# Использование:  bash stop_bot.sh
pkill -f "python bot.py" && echo "bot stopped" || echo "bot was not running"
termux-wake-unlock 2>/dev/null || true
