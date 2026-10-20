"""Tencent complete one-minute OHLCV fallback; no quotes synthesized into bars."""
from datetime import datetime
from functools import lru_cache
from threading import Lock
import time
import re
import requests
import pandas as pd


class TencentMinuteProvider:
    def __init__(self):
        self._lock = Lock()
        self._last_call = 0.0

    def minutes(self, symbol, day):
        if not re.fullmatch(r"[036][0-9]{5}", symbol):
            raise ValueError("Unsupported Tencent symbol")
        code = ("sh" if symbol.startswith("6") else "sz") + symbol
        with self._lock:
            wait = 1 - (time.monotonic() - self._last_call)
            if wait > 0:
                time.sleep(wait)
            try:
                response = requests.get("https://ifzq.gtimg.cn/appstock/app/kline/mkline",
                    params={"param": f"{code},m1,,320"}, headers={"Referer": "https://gu.qq.com/"}, timeout=(3, 6))
                response.raise_for_status()
                payload = response.json()
            finally:
                self._last_call = time.monotonic()
        if type(payload.get("code")) is not int or payload["code"] != 0:
            raise ValueError("Tencent minute request rejected")
        data = payload.get("data", {}).get(code, {})
        identity = data.get("qt", {}).get(code, [])
        if len(identity) < 3 or identity[2] != symbol:
            raise ValueError("Invalid Tencent minute identity")
        raw = data.get("m1")
        if not isinstance(raw, list) or not raw or len(raw) > 320:
            raise ValueError("Invalid Tencent minute rows")
        rows = []
        previous = None
        for row in raw:
            if not isinstance(row, list) or len(row) < 6 or not isinstance(row[0], str) or not re.fullmatch(r"[0-9]{12}", row[0]):
                raise ValueError("Invalid Tencent minute row")
            stamp = datetime.strptime(row[0], "%Y%m%d%H%M")
            if (previous is not None and stamp <= previous) or stamp.date() > day:
                raise ValueError("Invalid Tencent minute ordering")
            previous = stamp
            if stamp.date() != day:
                continue
            rows.append({"时间": stamp, "开盘": row[1], "收盘": row[2], "最高": row[3],
                         "最低": row[4], "成交量": row[5], "成交额": None})
        if not rows:
            raise ValueError("No current-day Tencent minutes")
        return pd.DataFrame(rows)


@lru_cache(maxsize=1)
def get_tencent_provider():
    return TencentMinuteProvider()
