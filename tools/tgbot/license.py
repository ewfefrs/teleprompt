"""
Генерация и подпись лицензионных ключей премиума С ПРИВЯЗКОЙ К УСТРОЙСТВУ
(EC P-256 / ECDSA-SHA256). Ключ действует только на устройстве с заданным device_id
(Android ID, который приложение показывает в окне активации).

Приложение (LicenseManager.kt) хранит ТОЛЬКО публичный ключ. Приватный ключ берётся из
переменной окружения LICENSE_PRIV_B64 (base64 PKCS8) — так его удобно держать секретом на
сервере, — либо из файла license_private.pem. Никогда не коммитьте приватный ключ.

Формат ключа:  base64url(payload) + "." + base64url(signatureDER)
payload = "PREMIUM:<device_id>:<time36>[:note]"

CLI:
  python license.py genkeys                 # создать пару (печатает PUBLIC_KEY_B64)
  python license.py make <device_id> [note] # выдать ключ для устройства
  python license.py selftest <device_id>    # проверка (sign → verify)
"""
import base64
import os
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = os.path.dirname(os.path.abspath(__file__))
PRIV_FILE = os.path.join(HERE, "license_private.pem")


def genkeys() -> None:
    if os.path.exists(PRIV_FILE):
        print("Приватный ключ уже есть:", PRIV_FILE, "— удалите вручную, чтобы перегенерировать.")
        return
    priv = ec.generate_private_key(ec.SECP256R1())
    with open(PRIV_FILE, "wb") as f:
        f.write(priv.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ))
    os.chmod(PRIV_FILE, 0o600)
    pub_der = priv.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    print("Приватный ключ сохранён:", PRIV_FILE, "(секрет!)")
    print("PUBLIC_KEY_B64=" + base64.b64encode(pub_der).decode())


def _load_priv():
    # 1) из переменной окружения LICENSE_PRIV_B64 (base64 PKCS8 DER) — удобно на сервере;
    b64 = os.environ.get("LICENSE_PRIV_B64", "").strip()
    if b64:
        return serialization.load_der_private_key(base64.b64decode(b64), password=None)
    # 2) иначе из PEM-файла.
    with open(PRIV_FILE, "rb") as f:
        return serialization.load_pem_private_key(f.read(), password=None)


def _b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def make_key(device_id: str, note: str = "") -> str:
    if not device_id or not device_id.strip():
        raise ValueError("device_id обязателен")
    priv = _load_priv()
    payload = ("PREMIUM:" + device_id.strip() + ":" + format(int(time.time()), "x")
               + ((":" + note) if note else "")).encode("utf-8")
    sig = priv.sign(payload, ec.ECDSA(hashes.SHA256()))
    return _b64u(payload) + "." + _b64u(sig)


def _selftest(device_id: str) -> None:
    priv = _load_priv()
    pub = priv.public_key()
    key = make_key(device_id, "selftest")
    p_b64, s_b64 = key.split(".")
    payload = base64.urlsafe_b64decode(p_b64 + "=" * (-len(p_b64) % 4))
    sig = base64.urlsafe_b64decode(s_b64 + "=" * (-len(s_b64) % 4))
    try:
        pub.verify(sig, payload, ec.ECDSA(hashes.SHA256()))
        ok = payload.decode().startswith("PREMIUM:" + device_id + ":")
    except Exception:
        ok = False
    print("KEY=" + key)
    print("VALID=" + str(ok) + " payload=" + payload.decode())


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "genkeys":
        genkeys()
    elif cmd == "make":
        print(make_key(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else ""))
    elif cmd == "selftest":
        _selftest(sys.argv[2] if len(sys.argv) > 2 else "testdevice")
    else:
        print("usage: genkeys | make <device_id> [note] | selftest <device_id>")
