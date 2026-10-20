from datetime import date, datetime
from decimal import Decimal
from zoneinfo import ZoneInfo
from threading import Lock
import os
import time
import requests

from app.providers.base import Adjustment, DailyBar, StockMetadata


class ProviderDataError(ValueError):
    pass


class AkshareProvider:
    """Single-process serialized access. No upstream calls at construction."""
    def __init__(self, sdk=None, today=None):
        self._sdk = sdk
        self._today = today or (lambda: datetime.now(ZoneInfo("Asia/Shanghai")).date())
        self._lock = Lock()
        self._last_call = 0.0

    def _call(self, name, **kwargs):
        with self._lock:
            if self._sdk is None:
                import akshare
                self._sdk = akshare
            wait = 1.0 - (time.monotonic() - self._last_call)
            if wait > 0:
                time.sleep(wait)
            try:
                return getattr(self._sdk, name)(**kwargs)
            except (requests.ConnectionError, requests.Timeout):
                # Explicit opt-in, same vendor/schema/units; no change to daily APIs.
                if (name != "stock_zh_a_hist_min_em" or kwargs.get("period") != "1"
                        or kwargs.get("adjust") != "" or os.getenv("MARKET_MINUTE_MIRROR_ENABLED", "false").lower() != "true"):
                    raise
                time.sleep(1)
                return self._minute_mirror(**kwargs)
            finally:
                self._last_call = time.monotonic()

    @staticmethod
    def _minute_mirror(symbol, period, adjust, start_date, end_date):
        import pandas as pd
        market = 1 if symbol.startswith("6") else 0
        # Public request routing token from AKShare, not an account credential.
        params = {"fields1": "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13",
                  "fields2": "f51,f52,f53,f54,f55,f56,f57,f58", "ut": "7eea3edcaed734bea9cbfc24409ed989",
                  "ndays": "1", "iscr": "0", "secid": f"{market}.{symbol}"}
        response = requests.get("https://push2.eastmoney.com/api/qt/stock/trends2/get", params=params, timeout=10)
        response.raise_for_status()
        data = response.json().get("data")
        if not isinstance(data, dict) or data.get("code") != symbol or data.get("market") != market or not data.get("trends"):
            raise ProviderDataError("Invalid minute mirror metadata")
        rows = [item.split(",") for item in data["trends"]]
        if any(len(row) != 8 for row in rows):
            raise ProviderDataError("Invalid minute mirror rows")
        frame = pd.DataFrame(rows, columns=["时间", "开盘", "收盘", "最高", "最低", "成交量", "成交额", "均价"])
        stamps = pd.to_datetime(frame["时间"], errors="raise")
        frame = frame[(stamps >= pd.Timestamp(start_date)) & (stamps <= pd.Timestamp(end_date))].copy()
        for column in frame.columns[1:]:
            frame[column] = pd.to_numeric(frame[column], errors="raise")
        return frame

    def stocks(self):
        feeds = [
            ("stock_info_sh_name_code", {"symbol": "主板A股"}, "SH", "证券代码", "证券简称"),
            ("stock_info_sh_name_code", {"symbol": "科创板"}, "SH", "证券代码", "证券简称"),
            ("stock_info_sz_name_code", {"symbol": "A股列表"}, "SZ", "A股代码", "A股简称"),
            ("stock_info_bj_name_code", {}, "BJ", "证券代码", "证券简称"),
        ]
        result = {}
        for method, kwargs, exchange, code, name in feeds:
            frame = self._call(method, **kwargs)
            if frame.empty:
                raise ProviderDataError("Empty stock catalog")
            for row in frame.to_dict("records"):
                stock = StockMetadata(symbol=str(row[code]).zfill(6), name=row[name], exchange=exchange)
                previous = result.get(stock.symbol)
                if previous is not None and previous != stock:
                    raise ProviderDataError("Conflicting catalog entries")
                result[stock.symbol] = stock
        return sorted(result.values(), key=lambda item: item.symbol)

    def daily_bars(self, symbol: str, start: date, end: date, adjustment: Adjustment):
        frame = self._call("stock_zh_a_hist", symbol=symbol, period="daily",
                           start_date=start.strftime("%Y%m%d"), end_date=end.strftime("%Y%m%d"),
                           adjust={Adjustment.NONE: "", Adjustment.QFQ: "qfq", Adjustment.HFQ: "hfq"}[adjustment],
                           timeout=15)
        result = []
        dates = set()
        for row in frame.to_dict("records"):
            trade_date = date.fromisoformat(str(row["日期"]))
            if not start <= trade_date <= end or trade_date > self._today() or trade_date in dates:
                raise ProviderDataError("Duplicate or out-of-range trading date")
            if str(row["股票代码"]).zfill(6) != symbol:
                raise ProviderDataError("Symbol mismatch")
            dates.add(trade_date)
            result.append(DailyBar(
                symbol=symbol, trade_date=trade_date,
                open_price=Decimal(str(row["开盘"])), high_price=Decimal(str(row["最高"])),
                low_price=Decimal(str(row["最低"])), close_price=Decimal(str(row["收盘"])),
                volume=Decimal(str(row["成交量"])) * 100,
                amount=Decimal(str(row["成交额"])),
                turnover_rate=Decimal(str(row["换手率"])) / 100,
                adjustment=adjustment, source="AKSHARE_EASTMONEY",
                is_final=trade_date < self._today()))
        return sorted(result, key=lambda item: item.trade_date)
