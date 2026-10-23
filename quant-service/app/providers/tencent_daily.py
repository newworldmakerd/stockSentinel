"""Source-consistent daily backup. Tencent newfqkline supplies turnover amount in CNY 10,000.

Use a full requested historical window; never derive adjustment factors from quote snapshots.
"""
from datetime import date, timedelta
from decimal import Decimal
from functools import lru_cache
from threading import Lock
import re
import time
import requests
from app.providers.base import Adjustment, DailyBar


class TencentDailyProvider:
    def __init__(self):
        self._lock = Lock()
        self._last_call = 0.0

    def daily_bars(self, symbol, start, end, adjustment, today):
        if not re.fullmatch(r"[036][0-9]{5}", symbol):
            raise ValueError("Unsupported daily symbol")
        code = ("sh" if symbol.startswith("6") else "sz") + symbol
        suffix = {Adjustment.NONE: "", Adjustment.QFQ: "qfq", Adjustment.HFQ: "hfq"}[adjustment]
        expected_key = suffix+"day"
        result = {}
        # At most639 calendar days can contain at most639 unique daily rows, safely
        # below the640 cap. Filter server overfetch, and never drop older requested blocks.
        block_start = start
        while block_start <= end:
            block_end = min(end, block_start+timedelta(days=638))
            with self._lock:
                wait = 1-(time.monotonic()-self._last_call)
                if wait > 0:
                    time.sleep(wait)
                try:
                    response = requests.get("https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get",
                        params={"param": f"{code},day,{block_start},{block_end},640,{suffix}"},
                        headers={"Referer": "https://gu.qq.com/"}, timeout=(3, 8))
                    response.raise_for_status()
                    payload = response.json()
                finally:
                    self._last_call = time.monotonic()
            if type(payload.get("code")) is not int or payload["code"] != 0:
                raise ValueError("Daily backup rejected")
            data = payload.get("data", {}).get(code, {})
            identity = data.get("qt", {}).get(code, [])
            if len(identity) < 3 or identity[2] != symbol:
                raise ValueError("Daily backup identity mismatch")
            rows = data.get(expected_key)
            if not isinstance(rows, list) or not rows or len(rows) > 640:
                raise ValueError("Daily backup adjustment missing")
            previous = None
            for row in rows:
                if not isinstance(row, list) or len(row) < 9:
                    raise ValueError("Daily backup amount missing")
                stamp = date.fromisoformat(row[0])
                if previous is not None and stamp <= previous:
                    raise ValueError("Daily backup dates unordered")
                previous = stamp
                if not block_start <= stamp <= block_end:
                    continue
                if stamp > today:
                    raise ValueError("Future daily backup")
                volume = Decimal(str(row[5]))
                # Equity SH688 is already shares; SZ000 is an equity prefix, not an index.
                if not symbol.startswith("688"):
                    volume *= 100
                result[stamp] = DailyBar(symbol=symbol, trade_date=stamp,
                    open_price=Decimal(str(row[1])), close_price=Decimal(str(row[2])),
                    high_price=Decimal(str(row[3])), low_price=Decimal(str(row[4])), volume=volume,
                    amount=Decimal(str(row[8]))*10000, turnover_rate=Decimal(str(row[7]))/100,
                    adjustment=adjustment, source="AKSHARE_TENCENT_DAILY", is_final=stamp < today)
            block_start = block_end+timedelta(days=1)
        if not result:
            raise ValueError("No daily backup rows")
        return [result[stamp] for stamp in sorted(result)]


@lru_cache(maxsize=1)
def get_tencent_daily():
    return TencentDailyProvider()
