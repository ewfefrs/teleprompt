"""
Telegram-бот автономной продажи ключей премиума Whisprompt (ключ привязан к устройству).

Поток:
  1. Пользователь копирует свой Device ID в приложении (Настройки → Премиум → «Ввести ключ»).
  2. Присылает Device ID боту.
  3. Оплачивает (/buy — звёзды Telegram, или /crypto — крипта через @CryptoBot).
  4. Бот САМ подписывает ключ ИМЕННО для этого устройства и присылает его.
  5. Пользователь вставляет ключ в приложении. На другом устройстве ключ не сработает.

Оплата:
  • Звёзды Telegram (XTR) — встроенный способ для цифровых товаров, работает в РФ, провайдер
    не нужен. Вывод: Stars → TON → рубли.
  • Крипта (USDT/TON…) через @CryptoBot (Crypto Pay API) — если задан CRYPTOBOT_TOKEN.

Переменные окружения:
  BOT_TOKEN         — токен бота от @BotFather (обязательно)
  LICENSE_PRIV_B64  — приватный ключ (base64 PKCS8) для подписи ключей (или license_private.pem)
  PRICE_STARS       — цена в звёздах (по умолчанию 150)
  CRYPTOBOT_TOKEN   — токен Crypto Pay (необязательно)
  CRYPTO_FIAT       — фиат счёта (по умолчанию EUR)
  CRYPTO_AMOUNT     — сумма в фиате (по умолчанию 3 → 3 €, крипта по курсу)
  CRYPTO_ASSETS     — какие монеты принимать, напр. "USDT,TON" (пусто = все)
"""
import asyncio
import logging
import os
import re

import license as lic
from telegram import LabeledPrice, Update
from telegram.ext import (
    Application,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    PreCheckoutQueryHandler,
    filters,
)

BOT_TOKEN = os.environ.get("BOT_TOKEN", "")
PRICE_STARS = int(os.environ.get("PRICE_STARS", "150"))   # ≈ 3 € (звёзды не привязаны к евро)
CRYPTOBOT_TOKEN = os.environ.get("CRYPTOBOT_TOKEN", "")
CRYPTO_FIAT = os.environ.get("CRYPTO_FIAT", "EUR")        # счёт в фиате (CryptoBot)
CRYPTO_AMOUNT = os.environ.get("CRYPTO_AMOUNT", "3")      # сумма в фиате (3 €), крипта — по курсу
CRYPTO_ASSETS = os.environ.get("CRYPTO_ASSETS", "")       # напр. "USDT,TON" (пусто = все монеты)

# xRocket Pay (@xrocket). Считает в криптовалюте (фиата нет) → ставим ~3 USDT ≈ 3 €.
XROCKET_TOKEN = os.environ.get("XROCKET_TOKEN", "")       # ключ Rocket-Pay-Key
XROCKET_CURRENCY = os.environ.get("XROCKET_CURRENCY", "USDT")
XROCKET_AMOUNT = os.environ.get("XROCKET_AMOUNT", "3.2")  # ≈ 3 € в USDT

# Какой крипто-провайдер использовать (xRocket в приоритете).
CRYPTO_PROVIDER = "xrocket" if XROCKET_TOKEN else ("cryptobot" if CRYPTOBOT_TOKEN else "")

TITLE = "Whisprompt Premium"
DESC = "Premium activation key (for one device): unlimited texts and words."
ISSUED_LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "issued_keys.log")

# Android ID — 16 hex; принимаем и близкие форматы.
DEVICE_RE = re.compile(r"^[0-9a-fA-F]{8,32}$")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

user_device: dict[int, str] = {}          # user_id -> device_id
crypto_pending: dict = {}                  # invoice_id -> (chat_id, user_id, device_id)


async def issue_key(bot, chat_id: int, user_id: int, device_id: str, source: str) -> None:
    key = lic.make_key(device_id, note=str(user_id))
    with open(ISSUED_LOG, "a", encoding="utf-8") as f:
        f.write(f"{user_id}\t{device_id}\t{source}\t{key}\n")
    await bot.send_message(chat_id, "Payment received ✅ Your key (tap to copy):")
    await bot.send_message(chat_id, f"`{key}`", parse_mode="MarkdownV2")
    await bot.send_message(chat_id, "Enter it in the app: Settings → Premium → \"Enter activation key\".")
    logging.info("Issued key user=%s device=%s via %s", user_id, device_id, source)


async def cmd_start(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    text = (
        "Premium for Whisprompt (key for one device).\n\n"
        "1) In the app: Settings → Premium → \"Enter activation key\" — copy your Device ID.\n"
        "2) Send that Device ID here in one message.\n"
        "3) Pay: /buy (Telegram Stars, ~€3)"
        + (" or /crypto (crypto, ~€3)" if CRYPTO_PROVIDER else "")
        + ".\n4) The bot will send your key — paste it in the app."
    )
    await update.message.reply_text(text)


async def on_text(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    """Ловим Device ID из обычного сообщения."""
    msg = (update.message.text or "").strip()
    if DEVICE_RE.match(msg):
        user_device[update.effective_user.id] = msg
        await update.message.reply_text(f"Device ID saved: {msg}\nNow pay: /buy"
                                        + (" or /crypto" if CRYPTO_PROVIDER else ""))
    else:
        await update.message.reply_text("Send your Device ID from the app (Settings → Premium). /start for instructions.")


def _device_or_none(user_id: int):
    return user_device.get(user_id)


# ---------- Звёзды Telegram (XTR) ----------

async def cmd_buy(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    if not _device_or_none(update.effective_user.id):
        await update.message.reply_text("Send your Device ID from the app first. /start for how.")
        return
    await ctx.bot.send_invoice(
        chat_id=update.effective_chat.id,
        title=TITLE,
        description=DESC,
        payload="premium-stars",
        provider_token="",
        currency="XTR",
        prices=[LabeledPrice(label="Premium", amount=PRICE_STARS)],
    )


async def on_precheckout(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    device = _device_or_none(update.pre_checkout_query.from_user.id)
    await update.pre_checkout_query.answer(
        ok=bool(device),
        error_message=None if device else "Send your Device ID from the app first.",
    )


async def on_paid(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    device = _device_or_none(update.effective_user.id)
    if not device:
        await update.message.reply_text("Device ID not found. Send it, then type /resend.")
        return
    sp = update.message.successful_payment
    await issue_key(ctx.bot, update.effective_chat.id, update.effective_user.id, device, f"stars:{sp.telegram_payment_charge_id}")


async def cmd_resend(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    """Повторно выдать ключ, если Device ID прислали после оплаты."""
    device = _device_or_none(update.effective_user.id)
    if not device:
        await update.message.reply_text("Send your Device ID first.")
        return
    await issue_key(ctx.bot, update.effective_chat.id, update.effective_user.id, device, "resend")


# ---------- Крипта: xRocket (приоритет) или CryptoBot ----------

CB_API = "https://pay.crypt.bot/api"
XR_API = "https://pay.xrocket.tg"


def _cb_create_invoice(payload: str) -> dict:
    import requests
    # Счёт в ФИАТЕ (EUR): пользователь платит крипто-эквивалент по курсу CryptoBot.
    body = {"currency_type": "fiat", "fiat": CRYPTO_FIAT, "amount": str(CRYPTO_AMOUNT),
            "description": DESC, "payload": payload}
    if CRYPTO_ASSETS:
        body["accepted_assets"] = CRYPTO_ASSETS
    r = requests.post(f"{CB_API}/createInvoice", headers={"Crypto-Pay-API-Token": CRYPTOBOT_TOKEN}, json=body, timeout=20)
    res = r.json()["result"]
    return {"id": str(res["invoice_id"]), "url": res.get("bot_invoice_url") or res.get("pay_url"),
            "label": f"{CRYPTO_AMOUNT} {CRYPTO_FIAT}"}


def _cb_get_status(invoice_id: str):
    import requests
    r = requests.get(f"{CB_API}/getInvoices", headers={"Crypto-Pay-API-Token": CRYPTOBOT_TOKEN},
                     params={"invoice_ids": str(invoice_id)}, timeout=20)
    items = r.json().get("result", {}).get("items", [])
    return items[0].get("status") if items else None


def _xr_create_invoice(payload: str) -> dict:
    import requests
    body = {"amount": float(XROCKET_AMOUNT), "currency": XROCKET_CURRENCY, "numPayments": 1,
            "description": DESC, "payload": payload}
    r = requests.post(f"{XR_API}/tg-invoices", headers={"Rocket-Pay-Key": XROCKET_TOKEN}, json=body, timeout=20)
    data = r.json().get("data", {})
    return {"id": str(data.get("id", "")), "url": data.get("link") or data.get("paymentUrl") or data.get("payLink"),
            "label": f"{XROCKET_AMOUNT} {XROCKET_CURRENCY} (~€3)"}


def _xr_get_status(invoice_id: str):
    import requests
    r = requests.get(f"{XR_API}/tg-invoices/{invoice_id}", headers={"Rocket-Pay-Key": XROCKET_TOKEN}, timeout=20)
    return r.json().get("data", {}).get("status")


def _create_invoice(payload: str) -> dict:
    return _xr_create_invoice(payload) if CRYPTO_PROVIDER == "xrocket" else _cb_create_invoice(payload)


def _get_status(invoice_id: str):
    return _xr_get_status(invoice_id) if CRYPTO_PROVIDER == "xrocket" else _cb_get_status(invoice_id)


async def cmd_crypto(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    device = _device_or_none(update.effective_user.id)
    if not device:
        await update.message.reply_text("Send your Device ID from the app first.")
        return
    try:
        inv = _create_invoice(payload=f"user:{update.effective_user.id}")
    except Exception as e:  # noqa: BLE001
        logging.warning("createInvoice error: %s", e)
        await update.message.reply_text("Couldn't create an invoice. Try again later or /buy.")
        return
    if not inv.get("id") or not inv.get("url"):
        await update.message.reply_text("Couldn't create an invoice. Try /buy.")
        return
    crypto_pending[inv["id"]] = (update.effective_chat.id, update.effective_user.id, device)
    await update.message.reply_text(f"Pay {inv['label']} in crypto:\n{inv['url']}\n\nThe key will arrive automatically after payment.")


async def poll_crypto(ctx: ContextTypes.DEFAULT_TYPE) -> None:
    for inv_id in list(crypto_pending.keys()):
        try:
            if _get_status(inv_id) == "paid":
                chat_id, user_id, device = crypto_pending.pop(inv_id)
                await issue_key(ctx.bot, chat_id, user_id, device, f"crypto:{inv_id}")
        except Exception as e:  # noqa: BLE001
            logging.warning("crypto poll error: %s", e)


def main() -> None:
    if not BOT_TOKEN:
        raise SystemExit("Set BOT_TOKEN (from @BotFather).")
    # Python 3.14+ (напр. в Termux) больше не создаёт event loop автоматически в
    # asyncio.get_event_loop(); PTB 21.x (run_polling) на это рассчитывает — создаём явно.
    try:
        asyncio.get_event_loop()
    except RuntimeError:
        asyncio.set_event_loop(asyncio.new_event_loop())
    app = Application.builder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("buy", cmd_buy))
    app.add_handler(CommandHandler("resend", cmd_resend))
    app.add_handler(PreCheckoutQueryHandler(on_precheckout))
    app.add_handler(MessageHandler(filters.SUCCESSFUL_PAYMENT, on_paid))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_text))
    if CRYPTO_PROVIDER:
        app.add_handler(CommandHandler("crypto", cmd_crypto))
        app.job_queue.run_repeating(poll_crypto, interval=15, first=15)
        logging.info("Crypto enabled: %s", CRYPTO_PROVIDER)
    logging.info("Bot started. Price: %s Stars (~EUR 3), crypto ~EUR 3 (%s).", PRICE_STARS, CRYPTO_PROVIDER or "off")
    app.run_polling()


if __name__ == "__main__":
    main()
