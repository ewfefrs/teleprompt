#!/data/data/com.termux/files/usr/bin/bash
# Запуск Whisprompt-бота В ФОНЕ (переживает закрытие Termux-сессии), с wake-lock.
# Использование:  bash start_bot.sh
cd "$(dirname "$0")"

# Не дать Android усыплять процесс (termux-wake-lock из termux-tools; если нет — пропустим).
termux-wake-lock 2>/dev/null || true

# Не плодить второй экземпляр (иначе конфликт 409 в Telegram).
pkill -f "python bot.py" 2>/dev/null || true
sleep 1

# Отвязать от сессии + лог в файл.
nohup bash run_termux.sh > bot.log 2>&1 &
sleep 4

echo "=== last log ==="
tail -n 12 bot.log
echo "=== pid ==="
pgrep -f "python bot.py" && echo "RUNNING" || echo "NOT RUNNING"
