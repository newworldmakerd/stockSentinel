"""Replay observed public market rows; never contact an upstream service."""
import json
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from unittest.mock import Mock
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.api.market import get_provider
from app.api.intraday import normalize_minutes, minute_clock
from app.providers.minute_failure import MinuteDataError, failure_code, retryable
from test_intraday import NOW, frame


SAMPLES = json.loads((Path(__file__).parent/"fixtures/minute_forming_tail.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("sample", SAMPLES, ids=lambda s:s["source"])
def test_captured_vendor_forming_tail_is_excluded_without_timestamp_shifting(sample):
    now = datetime.fromisoformat(sample["observed"])
    data = pd.DataFrame(sample["rows"])
    source = "TENCENT_1M" if sample["source"] == "tencent" else "AKSHARE_EASTMONEY_1M"
    result = normalize_minutes(sample["symbol"], data, now, source=source)
    assert len(result.bars) == 21
    assert result.bars[-1].bar_at == now.replace(minute=5, second=0, microsecond=0)
    assert all(bar.bar_at < now.replace(second=0, microsecond=0) for bar in result.bars)
    raw = {datetime.fromisoformat(r["时间"]): r for r in sample["rows"]}
    for bar in result.bars:
        original = raw[bar.bar_at.replace(tzinfo=None)]
        assert bar.close_price == Decimal(original["收盘"])
        assert bar.volume == Decimal(original["成交量"])*100


@pytest.mark.parametrize("kind,code,field", [
    ("future", "DATA_TIME_FUTURE", "time"), ("duplicate", "DATA_TIME_ORDER", "time"),
    ("date", "DATA_TIME_DATE", "time"), ("precision", "DATA_TIME_PRECISION", "time"),
    ("session", "DATA_TIME_SESSION", "time"), ("parse", "DATA_TIME_PARSE", "time"),
    ("price", "DATA_PRICE", "open"), ("ohlc", "DATA_OHLC", ""),
    ("volume", "DATA_VOLUME", "volume"), ("amount", "DATA_AMOUNT", "amount"),
    ("number", "DATA_NUMBER", "close"), ("missing", "DATA_FIELD_MISSING", "amount"),
])
def test_exact_validation_code_and_location_never_retain_input(kind, code, field):
    data = frame()
    if kind == "future": data.loc[20,"时间"] = NOW.replace(tzinfo=None, second=0)+timedelta(minutes=2)
    if kind == "duplicate": data.loc[20,"时间"] = data.loc[19,"时间"]
    if kind == "date": data.loc[20,"时间"] += timedelta(days=1)
    if kind == "precision": data.loc[20,"时间"] += timedelta(seconds=1)
    if kind == "session": data.loc[20,"时间"] = datetime(2026,10,20,12,0)
    if kind == "parse": data["时间"] = data["时间"].astype(object); data.loc[20,"时间"] = "private-invalid-time"
    if kind == "price": data.loc[20,"开盘"] = 0
    if kind == "ohlc": data.loc[20,"最高"] = 1
    if kind == "volume": data.loc[20,"成交量"] = -1
    if kind == "amount": data.loc[20,"成交额"] = -1
    if kind == "number": data.loc[20,"收盘"] = "private-invalid-number"
    if kind == "missing": data = data.drop(columns=["成交额"])
    with pytest.raises(MinuteDataError) as caught:
        normalize_minutes("600487",data,NOW)
    error = caught.value
    assert failure_code(error) == code
    assert error.row == (0 if kind == "missing" else 20)
    assert error.field == field
    assert "private" not in str(error)
    assert not retryable(error)


def test_future_rows_inside_window_and_unknown_sources_remain_rejected():
    data = frame()
    data.loc[19,"时间"] = NOW.replace(tzinfo=None,second=0)+timedelta(minutes=1)
    data.loc[20,"时间"] = NOW.replace(tzinfo=None,second=0)+timedelta(minutes=2)
    with pytest.raises(MinuteDataError,match="DATA_TIME_FUTURE"):
        normalize_minutes("600487",data,NOW)
    data = frame()
    data.loc[20,"时间"] = NOW.replace(tzinfo=None,second=0)+timedelta(minutes=1)
    with pytest.raises(MinuteDataError,match="DATA_TIME_FUTURE"):
        normalize_minutes("600487",data,NOW,source="UNKNOWN")


def test_real_replay_succeeds_through_api_and_bad_data_has_safe_diagnostics(monkeypatch,caplog):
    sample=SAMPLES[0]; provider=Mock(); provider._call.return_value=pd.DataFrame(sample["rows"])
    monkeypatch.setenv("MARKET_TENCENT_BACKUP_ENABLED","false")
    app.dependency_overrides[get_provider]=lambda:provider
    app.dependency_overrides[minute_clock]=lambda:datetime.fromisoformat(sample["observed"])
    try:
        client=TestClient(app)
        result=client.get("/internal/market/minutes?symbol=601869")
        assert result.status_code==200
        data=pd.DataFrame(sample["rows"])
        data.loc[0,"收盘"]="private-upstream-input"
        provider._call.return_value=data
        result=client.get("/internal/market/minutes?symbol=601869")
        assert result.status_code==502
        assert result.headers["X-Minute-Primary-Error"]=="DATA_NUMBER"
        assert "primary_row=0 primary_field=close" in caplog.text
        assert "private-upstream-input" not in caplog.text+result.text
    finally:
        app.dependency_overrides.pop(get_provider,None)
        app.dependency_overrides.pop(minute_clock,None)
