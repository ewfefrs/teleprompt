#!/data/data/com.termux/files/usr/bin/bash
# Автозапуск бота при загрузке телефона. Скопируй этот файл в ~/.termux/boot/
# (создай папку, если нет) и поставь приложение Termux:Boot из F-Droid.
#   mkdir -p ~/.termux/boot && cp ~/tgbot/termux_boot.sh ~/.termux/boot/ && chmod +x ~/.termux/boot/termux_boot.sh
termux-wake-lock 2>/dev/null || true
cd ~/tgbot 2>/dev/null || exit 0
bash run_termux.sh
