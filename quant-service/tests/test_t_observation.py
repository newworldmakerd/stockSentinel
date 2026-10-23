from datetime import datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo
from unittest.mock import Mock
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.providers.rsi_history import get_rsi_provider
from app.api.intraday import minute_clock
from app.api.t_observation import wilder, calculate_rsi, _cache, _failed, _circuits

ZONE = ZoneInfo("Asia/Shanghai")
NOW = datetime(2026, 10, 21, 10, 6, 5, tzinfo=ZONE)


def history():
    rows = []
    for day in (20, 21):
        for hour, minute in ((9, 30), (13, 0)):
            start = datetime(2026, 10, day, hour, minute)
            for i in range(1, 25):
                stamp = start+timedelta(minutes=i*5)
                if stamp.replace(tzinfo=ZONE) >= NOW:
                    continue
                rows.append({"时间": stamp, "收盘": Decimal(100)+Decimal(len(rows))/10})
    return pd.DataFrame(rows)


def test_wilder_edges_and_independent_seed_smoothing():
    assert wilder([Decimal(i) for i in range(1, 17)]) == 100
    assert wilder([Decimal(i) for i in range(17, 1, -1)]) == 0
    assert wilder([Decimal(10)]*16) == 50
    prices = [Decimal(100)]+[Decimal(100+i) for i in range(1, 15)]+[Decimal(111)]
    assert abs(wilder(prices)-Decimal("81.25")) < Decimal("0.00000001")
    with pytest.raises(ValueError, match="INSUFFICIENT"):
        wilder([Decimal(1)]*14)


def test_closed_cross_day_aggregation_and_no_lunch_fill():
    frame = history()
    result = calculate_rsi("000731", frame, NOW)
    assert result.five_at.hour == 10 and result.five_at.minute == 5
    assert result.fifteen_at.hour == 10 and result.fifteen_at.minute == 0
    assert result.rsi5 == result.rsi15 == 100
    # A forming 10:10 candle, even with an extreme close, cannot influence either RSI.
    future = pd.concat([frame, pd.DataFrame([{"时间": NOW.replace(tzinfo=None, minute=10, second=0), "收盘": 1}])], ignore_index=True)
    assert calculate_rsi("000731", future, NOW).rsi15 == result.rsi15
    exact = NOW.replace(minute=5, second=0)
    assert calculate_rsi("000731", frame, exact).five_at.minute == 0


def test_missing_bars_restart_warmup_and_stale_is_not_no_signal():
    frame = history().drop(index=46)
    with pytest.raises(ValueError, match="INSUFFICIENT"):
        calculate_rsi("000731", frame, NOW)
    with pytest.raises(ValueError, match="STALE"):
        calculate_rsi("000731", history(), NOW+timedelta(minutes=10))
    frame = history(); frame.loc[3, "收盘"] = float("nan")
    with pytest.raises(ValueError, match="PRICE"):
        calculate_rsi("000731", frame, NOW)


def test_endpoint_cache_units_and_provider_failures_are_safe():
    provider = Mock(); provider.primary.return_value = history()
    _cache.clear(); _failed.clear(); _circuits.clear()
    app.dependency_overrides[get_rsi_provider] = lambda: provider
    app.dependency_overrides[minute_clock] = lambda: NOW
    try:
        with TestClient(app) as http:
            value = http.get("/internal/indicators/t-rsi?symbol=000731")
            assert value.status_code == 200
            assert value.json()["adjustment"] == "QFQ"
            assert value.json()["period"] == 14
            assert http.get("/internal/indicators/t-rsi?symbol=000731").status_code == 200
            assert provider.primary.call_count == 1
            assert provider.primary.call_args.args[0] == "000731"
            provider.primary.side_effect = RuntimeError("private upstream payload")
            failure = http.get("/internal/indicators/t-rsi?symbol=600487")
            assert failure.status_code == 502
            assert "private" not in failure.text
    finally:
        app.dependency_overrides.clear(); _cache.clear(); _failed.clear(); _circuits.clear()
