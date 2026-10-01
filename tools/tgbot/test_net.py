"""Быстрая диагностика доступа к Telegram Bot API с устройства.
Печатает: DNS-резолв api.telegram.org, время TLS+HTTP до getMe, HTTP-код/ответ.
Запуск: python test_net.py
"""
import os
import socket
import time
import urllib.request

HOST = "api.telegram.org"


def dns():
    try:
        t = time.time()
        ip = socket.gethostbyname(HOST)
        print(f"DNS ok: {HOST} -> {ip} ({(time.time()-t)*1000:.0f} ms)")
        return True
    except Exception as e:  # noqa: BLE001
        print(f"DNS FAIL: {e}")
        return False


def http():
    tok = os.environ.get("BOT_TOKEN", "").strip()
    url = f"https://{HOST}/bot{tok}/getMe" if tok else f"https://{HOST}/"
    try:
        t = time.time()
        with urllib.request.urlopen(url, timeout=25) as r:
            body = r.read(300).decode("utf-8", "replace")
            print(f"HTTP ok: {r.status} in {(time.time()-t)*1000:.0f} ms")
            print("body:", body[:200])
    except Exception as e:  # noqa: BLE001
        print(f"HTTP FAIL after {(time.time()-t)*1000:.0f} ms: {type(e).__name__}: {e}")


if __name__ == "__main__":
    dns()
    http()
