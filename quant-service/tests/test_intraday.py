from datetime import datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo
from unittest.mock import Mock
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.api.market import get_provider
from app.api.intraday import normalize_minutes, minute_clock

NOW = datetime(2026, 10, 20, 10, 21, 5, tzinfo=ZoneInfo("Asia/Shanghai"))


def frame(count=21):
    rows = []
    for i in range(count):
        price = Decimal(102 if i == 20 else 100)
        rows.append({"时间": (NOW.replace(second=0)-timedelta(minutes=count-i)).replace(tzinfo=None),
            "开盘": price, "收盘": price, "最高": price+Decimal("0.1"), "最低": price-Decimal("0.1"),
            "成交量": 15 if i == 20 else 10, "成交额": 100000})
    return pd.DataFrame(rows)


def test_units_and_exact_independent_windows():
    data = normalize_minutes("600487", frame(), NOW)
    assert data.bars[-1].volume == 1500
    assert data.bars[-1].amount == 100000
    assert data.metrics.five_minute_return_pct == 2
    assert data.metrics.previous_twenty_high == Decimal("100.1")
    assert data.metrics.previous_twenty_volume_mean == 1000
    assert data.adjustment == "NONE"


def test_in_progress_minute_is_excluded_and_warmup_is_explicit():
    data = frame(5)
    data.loc[4, "时间"] = NOW.replace(second=0, tzinfo=None)
    result = normalize_minutes("600487", data, NOW)
    assert len(result.bars) == 4
    assert result.metrics.five_minute_return_pct is None
    assert result.metrics.previous_twenty_high is None


def test_lunch_gap_never_becomes_five_or_twenty_minute_window():
    data = frame(6)
    for i in range(5):
        data.loc[i, "时间"] = datetime(2026, 10, 20, 11, 26)+timedelta(minutes=i)
    data.loc[5, "时间"] = datetime(2026, 10, 20, 13, 0)
    result = normalize_minutes("600487", data, NOW.replace(hour=13, minute=1))
    assert result.metrics.five_minute_return_pct is None


@pytest.mark.parametrize("kind", ["duplicate", "future", "yesterday", "bad_ohlc", "negative_volume", "zero_price"])
def test_invalid_data_is_rejected(kind):
    data = frame()
    if kind == "duplicate": data.loc[20, "时间"] = data.loc[19, "时间"]
    if kind == "future": data.loc[20, "时间"] = datetime(2026, 10, 20, 10, 22)
    if kind == "yesterday": data.loc[0, "时间"] -= timedelta(days=1)
    if kind == "bad_ohlc": data.loc[20, "最高"] = 1
    if kind == "negative_volume": data.loc[20, "成交量"] = -1
    if kind == "zero_price": data.loc[20, "开盘"] = 0
    with pytest.raises(ValueError): normalize_minutes("600487", data, NOW)


def test_api_calls_only_one_minute_unadjusted_provider_and_sanitizes_failure():
    provider = Mock()
    provider._call.return_value = frame()
    app.dependency_overrides[get_provider] = lambda: provider
    app.dependency_overrides[minute_clock] = lambda: NOW
    try:
        client = TestClient(app)
        result = client.get("/internal/market/minutes?symbol=600487")
        assert result.status_code == 200
        assert provider._call.call_args.args == ("stock_zh_a_hist_min_em",)
        assert provider._call.call_args.kwargs["period"] == "1"
        assert provider._call.call_args.kwargs["adjust"] == ""
        provider._call.side_effect = RuntimeError("private-provider-error")
        result = client.get("/internal/market/minutes?symbol=600487")
        assert result.status_code == 502
        assert "private-provider-error" not in result.text
    finally:
        app.dependency_overrides.pop(get_provider, None)
        app.dependency_overrides.pop(minute_clock, None)
