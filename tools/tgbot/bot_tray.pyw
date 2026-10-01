# -*- coding: utf-8 -*-
"""
Трей-лаунчер Telegram-бота Whisprompt.

Запускает bot.py БЕЗ окна консоли (не мозолит глаза в панели задач) и показывает
иконку в системном трее (в «скрытых значках»). Правый клик по иконке → Quit — корректно
останавливает бота. Секреты (токен, приватный ключ, xRocket/CryptoBot) грузятся из тех же
gitignored txt-файлов рядом, что и в run_bot.ps1.

Запуск (без консоли):
    pythonw.exe bot_tray.pyw
Автозапуск с Windows настраивается ярлыком в папке автозагрузки (см. install_autostart.ps1).
"""
import os
import sys
import subprocess

import pystray
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
CREATE_NO_WINDOW = 0x08000000


def _read(name):
    p = os.path.join(HERE, name)
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            return f.read().strip()
    return None


def build_env():
    """Тот же набор переменных окружения, что готовит run_bot.ps1."""
    env = dict(os.environ)
    tok = _read("bot_token.txt")
    priv = _read("license_private_b64.txt")
    if not tok or not priv:
        return None  # нет секретов — бот не запускаем
    env["BOT_TOKEN"] = tok
    env["LICENSE_PRIV_B64"] = priv
    env.setdefault("PRICE_STARS", "150")
    xr = _read("xrocket_token.txt")
    if xr:
        env["XROCKET_TOKEN"] = xr
        env.setdefault("XROCKET_CURRENCY", "USDT")
        env.setdefault("XROCKET_AMOUNT", "3.2")
    cb = _read("cryptobot_token.txt")
    if cb:
        env["CRYPTOBOT_TOKEN"] = cb
        env.setdefault("CRYPTO_FIAT", "EUR")
        env.setdefault("CRYPTO_AMOUNT", "3")
    return env


def pythonw_exe():
    """Windowless-интерпретатор для дочернего процесса бота."""
    cand = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "Python", "Python312", "pythonw.exe")
    if os.path.exists(cand):
        return cand
    base = os.path.dirname(sys.executable)
    pw = os.path.join(base, "pythonw.exe")
    return pw if os.path.exists(pw) else sys.executable


def make_icon():
    """Иконка трея: тёмный фон + три зелёные строки суфлёра (в стиле приложения)."""
    img = Image.new("RGBA", (64, 64), (11, 20, 16, 255))
    d = ImageDraw.Draw(img)
    g = (84, 230, 122, 255)
    d.rounded_rectangle([14, 18, 50, 24], radius=3, fill=g)
    d.rounded_rectangle([14, 30, 44, 36], radius=3, fill=g)
    d.rounded_rectangle([14, 42, 50, 48], radius=3, fill=g)
    return img


def main():
    env = build_env()
    proc = None
    if env is not None:
        proc = subprocess.Popen(
            [pythonw_exe(), os.path.join(HERE, "bot.py")],
            cwd=HERE, env=env, creationflags=CREATE_NO_WINDOW,
        )

    def on_quit(icon, _item):
        if proc is not None:
            try:
                proc.terminate()
            except Exception:
                pass
        icon.stop()

    def on_restart(icon, _item):
        nonlocal proc
        if proc is not None:
            try:
                proc.terminate()
            except Exception:
                pass
        e = build_env()
        if e is not None:
            proc = subprocess.Popen(
                [pythonw_exe(), os.path.join(HERE, "bot.py")],
                cwd=HERE, env=e, creationflags=CREATE_NO_WINDOW,
            )

    tooltip = "Whisprompt Bot" + ("" if env is not None else " (нет токена/ключа)")
    icon = pystray.Icon(
        "whisprompt_bot",
        make_icon(),
        tooltip,
        menu=pystray.Menu(
            pystray.MenuItem("Restart bot", on_restart),
            pystray.MenuItem("Quit Whisprompt Bot", on_quit),
        ),
    )
    icon.run()


if __name__ == "__main__":
    main()
