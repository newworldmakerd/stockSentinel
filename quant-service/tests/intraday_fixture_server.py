"""Loopback-only minute fixture; uses actual normalization and metric functions."""
import sys
import os
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from datetime import datetime, timedelta
from decimal import Decimal
import pandas as pd
from app.main import app
from app.api.market import get_provider
from app.api.intraday import minute_clock
from app.providers.minute_failure import MinuteDataError

NOW = datetime.fromisoformat(os.environ["INTRADAY_FIXTURE_NOW"])


class FixedMinutes:
    def _call(self, method, **kwargs):
        assert method == "stock_zh_a_hist_min_em"
        symbol = kwargs["symbol"]
        if symbol == "999625":
            raise MinuteDataError("DATA_TIME_ORDER", row=20, field="time")
        if symbol == "999623":
            raise RuntimeError("fixed provider failure")
        rows = []
        for i in range(21):
            price = Decimal(98 if symbol == "999621" else 100 if symbol == "999624" else 102) if i == 20 else Decimal(100)
            stamp = NOW.replace(second=0)-timedelta(minutes=21-i)
            if symbol == "999622":
                stamp -= timedelta(minutes=10)
            rows.append({"时间": stamp.replace(tzinfo=None), "开盘": price, "收盘": price,
                "最高": price+Decimal("0.1"), "最低": price-Decimal("0.1"),
                "成交量": 15 if i == 20 else 10, "成交额": 100000})
        return pd.DataFrame(rows)


app.dependency_overrides[get_provider] = lambda: FixedMinutes()
app.dependency_overrides[minute_clock] = lambda: NOW
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=int(sys.argv[1]))
