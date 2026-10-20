"""Loopback-only TASK-06 fixture: fake market, real indicator computation, no vendor SDK calls."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from decimal import Decimal
from app.main import app
from app.api.market import get_provider
from app.providers.base import DailyBar, Adjustment


class FixedMarket:
    def daily_bars(self, symbol, start, end, adjustment):
        if symbol == "999611":
            raise RuntimeError("fixed market unavailable")
        bars = []
        for index in range(121):
            day = end - timedelta(days=120-index)
            if day < start or (symbol == "999612" and index == 120):
                continue
            close = Decimal(110 if index == 120 and symbol != "999613" else 100)
            if adjustment == Adjustment.NONE:
                close *= 2
            bars.append(DailyBar(symbol=symbol, trade_date=day, open_price=close,
                high_price=close+1, low_price=close-1, close_price=close,
                volume=Decimal(120 if index == 120 else 100), amount=Decimal(100000),
                adjustment=adjustment, source="JOB_FIXTURE", is_final=day < datetime.now(ZoneInfo("Asia/Shanghai")).date()))
        return bars


app.dependency_overrides[get_provider] = lambda: FixedMarket()
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=int(sys.argv[1]))
