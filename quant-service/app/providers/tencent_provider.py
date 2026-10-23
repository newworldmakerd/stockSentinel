"""Tencent complete one-minute OHLCV fallback; no quotes synthesized into bars."""
from datetime import datetime
from functools import lru_cache
from threading import Lock
import time
import re
import requests
import pandas as pd
from app.providers.minute_failure import retryable, MinuteDataError


class TencentMinuteProvider:
    def __init__(self):
        self._lock = Lock()
        self._last_call = 0.0

    def minutes(self, symbol, day, *, retry_deadline=None):
        if not re.fullmatch(r"[036][0-9]{5}", symbol):
            raise ValueError("Unsupported Tencent symbol")
        code = ("sh" if symbol.startswith("6") else "sz") + symbol
        with self._lock:
            for attempt in range(2):
                wait = 1 - (time.monotonic() - self._last_call)
                if wait > 0:
                    time.sleep(wait)
                try:
                    response = requests.get("https://ifzq.gtimg.cn/appstock/app/kline/mkline",
                        params={"param": f"{code},m1,,320"}, headers={"Referer": "https://gu.qq.com/"}, timeout=(3, 6))
                    response.raise_for_status()
                    payload = response.json()
                    break
                except requests.RequestException as error:
                    # Leave time for the (3s connect + 6s read) retry and rate-limit wait.
                    if attempt or not retryable(error) or (retry_deadline is not None and time.monotonic()+10 > retry_deadline):
                        raise
                finally:
                    self._last_call = time.monotonic()
        if not isinstance(payload, dict) or type(payload.get("code")) is not int or payload["code"] != 0:
            raise MinuteDataError("DATA_SHAPE", field="identity")
        if not isinstance(payload.get("data"), dict):
            raise MinuteDataError("DATA_SHAPE", field="identity")
        data = payload["data"].get(code, {})
        if not isinstance(data, dict) or not isinstance(data.get("qt"), dict):
            raise MinuteDataError("DATA_SHAPE", field="identity")
        identity = data.get("qt", {}).get(code, [])
        if not isinstance(identity, list) or len(identity) < 3 or identity[2] != symbol:
            raise MinuteDataError("DATA_IDENTITY", field="identity")
        raw = data.get("m1")
        if not isinstance(raw, list) or not raw or len(raw) > 320:
            raise MinuteDataError("DATA_ROWS", field="rows")
        rows = []
        previous = None
        for index, row in enumerate(raw):
            if not isinstance(row, list) or len(row) < 6 or not isinstance(row[0], str) or not re.fullmatch(r"[0-9]{12}", row[0]):
                raise MinuteDataError("DATA_SHAPE", row=index, field="rows")
            try:
                stamp = datetime.strptime(row[0], "%Y%m%d%H%M")
            except ValueError:
                raise MinuteDataError("DATA_TIME_PARSE", row=index, field="time") from None
            if previous is not None and stamp <= previous:
                raise MinuteDataError("DATA_TIME_ORDER", row=index, field="time")
            if stamp.date() > day:
                raise MinuteDataError("DATA_TIME_DATE", row=index, field="time")
            previous = stamp
            if stamp.date() != day:
                continue
            rows.append({"时间": stamp, "开盘": row[1], "收盘": row[2], "最高": row[3],
                         "最低": row[4], "成交量": row[5], "成交额": None})
        if not rows:
            raise MinuteDataError("DATA_EMPTY", field="rows")
        return pd.DataFrame(rows)


@lru_cache(maxsize=1)
def get_tencent_provider():
    return TencentMinuteProvider()
