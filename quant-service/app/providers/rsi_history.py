"""Bounded historical five-minute adapters; never silently substitute raw for QFQ."""
from datetime import date
from decimal import Decimal
from functools import lru_cache
import json
import os
import re
import time
import requests
import pandas as pd
from fastapi import HTTPException


class RsiHistoryError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


class RsiHistoryProvider:
    def __init__(self, *, clock=time.monotonic, session=None, urls=None):
        self.clock = clock
        self.session = session or requests.Session()
        self.next_call = 0.0
        self.urls = urls or {
            "primary": "https://push2his.eastmoney.com/api/qt/stock/kline/get",
            "backup": "https://quotes.sina.cn/cn/api/jsonp_v2.php/=/CN_MarketDataService.getKLineData",
            "factor": "https://finance.sina.com.cn/realstock/company/{code}/qfq.js"}

    def _text(self, url, params, deadline):
        wait = max(0, self.next_call-self.clock())
        if wait:
            if self.clock()+wait >= deadline:
                raise RsiHistoryError("BUDGET_EXHAUSTED")
            time.sleep(wait)
        self.next_call = self.clock()+1
        remaining = deadline-self.clock()
        if remaining <= 0.1:
            raise RsiHistoryError("BUDGET_EXHAUSTED")
        timeout = (min(3, remaining/2), min(8, remaining/2))
        with self.session.get(url, params=params, timeout=timeout, stream=True) as response:
            response.raise_for_status()
            chunks = []; size = 0
            for chunk in response.iter_content(65536):
                if self.clock() > deadline:
                    raise RsiHistoryError("BUDGET_EXHAUSTED")
                size += len(chunk)
                if size > 4_000_000:
                    raise RsiHistoryError("DATA_ROWS")
                chunks.append(chunk)
            return b"".join(chunks).decode("utf-8-sig")

    def primary(self, symbol, start, end, deadline):
        params = {"fields1": "f1,f2,f3,f4,f5,f6", "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
                  "klt": "5", "fqt": "1", "secid": f"{1 if symbol.startswith('6') else 0}.{symbol}",
                  "beg": "0", "end": "20500000"}
        data = json.loads(self._text(self.urls["primary"], params, deadline)).get("data")
        if not isinstance(data, dict) or data.get("code") != symbol:
            raise RsiHistoryError("DATA_IDENTITY")
        raw = data.get("klines")
        if not isinstance(raw, list) or not 1 <= len(raw) <= 6000:
            raise RsiHistoryError("DATA_ROWS")
        rows = []
        for row in raw:
            values = row.split(",") if isinstance(row, str) else []
            if len(values) != 11:
                raise RsiHistoryError("DATA_SHAPE")
            if start <= values[0][:10] <= end:
                rows.append({"时间": values[0], "收盘": values[2]})
        return pd.DataFrame(rows, columns=["时间", "收盘"])

    def backup(self, symbol, start, end, deadline):
        code = ("sh" if symbol.startswith("6") else "sz")+symbol
        text = self._text(self.urls["backup"], {"symbol": code, "scale": "5", "ma": "no", "datalen": "1970"}, deadline)
        prefix = "=("
        if prefix not in text:
            raise RsiHistoryError("DATA_SHAPE")
        raw, _ = json.JSONDecoder().raw_decode(text.split(prefix, 1)[1].lstrip())
        if not isinstance(raw, list) or not 1 <= len(raw) <= 1970:
            raise RsiHistoryError("DATA_ROWS")
        factor_text = self._text(self.urls["factor"].format(code=code), {}, deadline)
        match = re.match(r"\s*var\s+"+re.escape(code)+r"qfq\s*=\s*", factor_text)
        if match is None:
            raise RsiHistoryError("DATA_IDENTITY")
        # Decode JSON only; opaque trailing JavaScript comments are never evaluated.
        obj, _ = json.JSONDecoder().raw_decode(factor_text[match.end():])
        factors = obj.get("data") if isinstance(obj, dict) else None
        if not isinstance(factors, list) or not 1 <= len(factors) <= 2000:
            raise RsiHistoryError("FACTOR_MISSING")
        parsed = []; previous = None
        for item in factors:
            d = date.fromisoformat(item["d"]); f = Decimal(str(item["f"]))
            if not f.is_finite() or f <= 0 or (previous is not None and d >= previous):
                raise RsiHistoryError("FACTOR_INVALID")
            parsed.append((d, f)); previous = d
        usable = [(d, f) for d, f in parsed if d <= date.fromisoformat(end)]
        if not usable or usable[0][1] != 1:
            raise RsiHistoryError("FACTOR_ANCHOR_INVALID")
        rows = []
        for item in raw:
            stamp = str(item["day"])
            if start <= stamp[:10] <= end:
                day = date.fromisoformat(stamp[:10])
                factor = next((f for d, f in usable if d <= day), None)
                if factor is None:
                    raise RsiHistoryError("FACTOR_MISSING")
                close = Decimal(str(item["close"]))
                if not close.is_finite() or close <= 0:
                    raise RsiHistoryError("DATA_PRICE")
                rows.append({"时间": stamp, "收盘": close/factor})
        return pd.DataFrame(rows, columns=["时间", "收盘"])


@lru_cache(maxsize=1)
def _provider():
    return RsiHistoryProvider()


def get_rsi_provider():
    if os.getenv("MARKET_PROVIDER_ENABLED", "false").lower() != "true":
        raise HTTPException(503, detail="Market provider disabled")
    return _provider()
