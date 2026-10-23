"""CSI300 daily MA200: whole-window failover, never stock 000300 or mixed sources."""
from datetime import date, datetime, timedelta
from decimal import Decimal, localcontext
import hashlib
from zoneinfo import ZoneInfo
import requests

EASTMONEY = "TREND_CSI300_EASTMONEY"
TENCENT = "TREND_CSI300_TENCENT"
PRIMARY_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
BACKUP_URL = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get"
TIMEOUT = (3, 8)
ZONE = ZoneInfo("Asia/Shanghai")


class IndexUnavailable(Exception):
    def __init__(self, failures):
        self.failures = failures


def _primary(as_of):
    response = requests.get(PRIMARY_URL, params={
        "secid": "1.000300", "fields1": "f1,f2,f3,f4,f5", "fields2": "f51,f52,f53,f54,f55,f56,f57,f58",
        "klt": "101", "fqt": "0", "beg": (as_of-timedelta(days=730)).strftime("%Y%m%d"),
        "end": as_of.strftime("%Y%m%d")}, timeout=TIMEOUT)
    response.raise_for_status()
    data = response.json()["data"]
    if data.get("code") != "000300":
        raise ValueError("IDENTITY")
    rows = [(date.fromisoformat(line.split(",")[0]), Decimal(line.split(",")[2])) for line in data["klines"]]
    if any(day > as_of for day, _ in rows):
        raise ValueError("FUTURE")
    return rows


def _backup(as_of):
    response = requests.get(BACKUP_URL, params={"param": f"sh000300,day,{as_of-timedelta(days=730)},{as_of},640,"},
                            headers={"Referer": "https://gu.qq.com/"}, timeout=TIMEOUT)
    response.raise_for_status()
    payload = response.json()
    if type(payload.get("code")) is not int or payload["code"] != 0:
        raise ValueError("REJECTED")
    data = payload["data"]["sh000300"]
    identity = data["qt"]["sh000300"]
    if len(identity) < 3 or identity[2] != "000300":
        raise ValueError("IDENTITY")
    # Only raw day is accepted. qfqday/hfqday are never substituted.
    values = data["day"]
    if not isinstance(values, list) or not 1 <= len(values) <= 640:
        raise ValueError("ROWS")
    rows = [(date.fromisoformat(row[0]), Decimal(str(row[2]))) for row in values]
    _validate_order(rows)
    # Tencent overfetches earlier rows; consume only the requested historical window.
    return [(day, close) for day, close in rows if as_of-timedelta(days=730) <= day <= as_of]


def _validate_order(rows):
    for i, (day, close) in enumerate(rows):
        if not close.is_finite() or close <= 0 or (i and day <= rows[i-1][0]):
            raise ValueError("ROWS")


def fetch_index(as_of: date, source="AUTO"):
    providers = {"EASTMONEY": [(EASTMONEY, _primary)], "TENCENT": [(TENCENT, _backup)],
                 "AUTO": [(EASTMONEY, _primary), (TENCENT, _backup)]}[source]
    failures = []
    for name, fetch in providers:
        try:
            rows = fetch(as_of)
            _validate_order(rows)
            if len(rows) < 201 or rows[-1][0] != as_of:
                raise ValueError("HISTORY")
            with localcontext() as context:
                context.prec = 40
                window = "|".join(f"{day}:{close}" for day, close in rows[-201:])
                return {"key": "sh000300", "source": name, "as_of": as_of, "close": rows[-1][1],
                        "ma200": sum(c for _, c in rows[-200:])/200,
                        "previous_ma200": sum(c for _, c in rows[-201:-1])/200,
                        "observed_at": datetime.now(ZONE), "history_size": len(rows),
                        "window_start": rows[-201][0], "window_hash": hashlib.sha256(window.encode()).hexdigest(),
                        "fallback_used": bool(failures), "source_failures": failures}
        except requests.exceptions.JSONDecodeError:
            failures.append({"source": name, "code": "SOURCE_INVALID"})
        except requests.RequestException:
            failures.append({"source": name, "code": "SOURCE_UNAVAILABLE"})
        except (ValueError, TypeError, KeyError, IndexError, ArithmeticError, AttributeError):
            failures.append({"source": name, "code": "SOURCE_INVALID"})
    raise IndexUnavailable(failures)
