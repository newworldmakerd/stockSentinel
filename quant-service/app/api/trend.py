"""Independent trend indicators and bounded market adapters; no trading decisions or database."""
from datetime import date, datetime, timedelta
from decimal import Decimal, localcontext
import hashlib
import re
import time
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from fastapi.encoders import jsonable_encoder
import requests
from app.api.market import get_provider
from app.providers.base import DailyBar, Adjustment
from app.providers.trend_index import fetch_index, IndexUnavailable
from typing import Literal

router = APIRouter(prefix="/internal/trend")
ZONE = ZoneInfo("Asia/Shanghai")
VERSION = "trend-ma-atr-sma-v1"


class TrendInput(BaseModel):
    symbol: str = Field(pattern=r"^[0-9]{6}$")
    adjusted: list[DailyBar] = Field(min_length=120, max_length=1000)
    raw: list[DailyBar] = Field(min_length=120, max_length=1000)
    current_day_confirmed: bool = False


def metrics(body: TrendInput):
    today = datetime.now(ZONE).date()
    for bars, adjustment in [(body.adjusted, Adjustment.QFQ), (body.raw, Adjustment.NONE)]:
        source = bars[0].source
        for i, b in enumerate(bars):
            if (b.symbol != body.symbol or b.adjustment != adjustment or b.source != source
                    or not b.is_final or b.trade_date > today
                    or (b.trade_date == today and not body.current_day_confirmed)
                    or (i and b.trade_date <= bars[i-1].trade_date)):
                raise ValueError("TREND_SERIES_INVALID")
    a, raw = body.adjusted, body.raw
    if [b.trade_date for b in a] != [b.trade_date for b in raw]:
        raise ValueError("TREND_DATES_UNALIGNED")
    with localcontext() as ctx:
        ctx.prec = 40
        closes = [b.close_price for b in a]
        ma = lambda n, end: sum(closes[end-n:end])/Decimal(n)
        end = len(a)
        tr = [max(a[i].high_price-a[i].low_price, abs(a[i].high_price-a[i-1].close_price),
                  abs(a[i].low_price-a[i-1].close_price)) for i in range(end-14, end)]
        factor = raw[-1].close_price/a[-1].close_price
        result = {"symbol": body.symbol, "version": VERSION, "as_of": a[-1].trade_date,
                  "source": a[-1].source, "raw_source": raw[-1].source, "history_size": end,
                  "ma20": ma(20, end), "ma60": ma(60, end), "ma120": ma(120, end),
                  "ma60_change20": ma(60, end)/ma(60, end-20)-1,
                  "atr14": sum(tr)/14, "close": a[-1].close_price,
                  "previous_twenty_high": max(b.high_price for b in a[-21:-1]),
                  "recent_twenty_high": max(b.high_price for b in a[-20:]),
                  "raw_close": raw[-1].close_price, "previous_raw_close": raw[-2].close_price,
                  "raw_factor": factor, "raw_atr14": sum(tr)/14*factor,
                  "amount": raw[-1].amount, "volume": raw[-1].volume,
                  "window_hash": hashlib.sha256((body.model_dump_json()).encode()).hexdigest()}
        return result


@router.post("/calculate")
def calculate(body: TrendInput):
    try:
        return jsonable_encoder(metrics(body), custom_encoder={Decimal: str})
    except (ValueError, ArithmeticError):
        raise HTTPException(422, detail="TREND_SERIES_INVALID") from None


@router.get("/index")
def index(as_of: date, current_day_confirmed: bool = False, _=Depends(get_provider), source: Literal["AUTO", "EASTMONEY", "TENCENT"] = "AUTO"):
    today = datetime.now(ZONE).date()
    if as_of > today or (as_of == today and not current_day_confirmed):
        raise HTTPException(422, detail="TREND_INDEX_NOT_CONFIRMED")
    try:
        return jsonable_encoder(fetch_index(as_of, source), custom_encoder={Decimal: str})
    except IndexUnavailable as unavailable:
        raise HTTPException(502, detail={"code": "TREND_INDEX_UNAVAILABLE", "failures": unavailable.failures}) from None


class QuoteInput(BaseModel):
    symbols: list[str] = Field(min_length=1, max_length=100)


def parse_quotes(text: str, expected: set[str]):
    result = []
    for key, raw in re.findall(r'v_([a-z]{2}[0-9]{6})="([^"\r\n]*)";', text):
        f = raw.split("~")
        if key not in expected or len(f) < 49 or f[2] != key[2:]:
            raise ValueError("TREND_QUOTE_IDENTITY")
        def number(i, optional=False):
            if optional and not f[i]:
                return None
            v = Decimal(f[i])
            if not v.is_finite() or v < 0:
                raise ValueError("TREND_QUOTE_NUMBER")
            return v
        price, previous = number(3), number(4)
        if price <= 0 or previous <= 0:
            continue
        amount = number(37, True)
        # CSI300 has no daily price limits; Tencent uses -1 for these fields.
        def limit(i):
            return None if key == "sh000300" and f[i] == "-1" else number(i, True)
        result.append({"key": key, "symbol": f[2], "name": f[1], "price": price,
                       "previous_close": previous, "volume": number(6)*100,
                       "amount": None if amount is None else amount*10000,
                       "limit_up": limit(47), "limit_down": limit(48),
                       "quoted_at": datetime.strptime(f[30], "%Y%m%d%H%M%S").replace(tzinfo=ZONE)})
    if len({q["key"] for q in result}) != len(result):
        raise ValueError("TREND_QUOTE_DUPLICATE")
    return result


_last_quote = 0.0
from threading import Lock
_quote_lock = Lock()


@router.post("/quotes")
def quotes(body: QuoteInput, _=Depends(get_provider)):
    if len(set(body.symbols)) != len(body.symbols) or any(not re.fullmatch(r"(?:sh|sz)[0-9]{6}", k) for k in body.symbols):
        raise HTTPException(422, detail="TREND_QUOTE_KEYS_INVALID")
    global _last_quote
    if not _quote_lock.acquire(timeout=0.1):
        raise HTTPException(503, detail="TREND_QUOTES_BUSY")
    try:
        wait = 1-(time.monotonic()-_last_quote)
        if wait > 0:
            time.sleep(wait)
        try:
            r = requests.get("https://qt.gtimg.cn/q="+",".join(body.symbols), timeout=(3, 6))
            r.raise_for_status()
            parsed = parse_quotes(r.content.decode("gbk"), set(body.symbols))
            return jsonable_encoder({"source": "TENCENT_TREND_QUOTES", "observed_at": datetime.now(ZONE), "quotes": parsed,
                    "failed_symbols": [k for k in body.symbols if k not in {q["key"] for q in parsed}]}, custom_encoder={Decimal: str})
        except Exception:
            raise HTTPException(502, detail="TREND_QUOTES_UNAVAILABLE") from None
        finally:
            _last_quote = time.monotonic()
    finally:
        _quote_lock.release()
