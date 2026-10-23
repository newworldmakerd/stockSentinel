import json
from datetime import datetime, timedelta
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from unittest.mock import Mock
from zoneinfo import ZoneInfo
import pandas as pd
import pytest
import requests
from fastapi.testclient import TestClient
from app.main import app
from app.api.market import get_provider
from app.api.intraday import minute_clock
from app.providers.tencent_provider import TencentMinuteProvider, get_tencent_provider

NOW = datetime(2026, 10, 20, 10, 21, 5, tzinfo=ZoneInfo("Asia/Shanghai"))


def payload():
    rows = []
    for i in range(21):
        p = "102" if i == 20 else "100"
        rows.append([(NOW-timedelta(minutes=21-i)).strftime("%Y%m%d%H%M"), p, p,
                     str(Decimal(p)+Decimal("0.1")), str(Decimal(p)-Decimal("0.1")), "15" if i == 20 else "10", {}, "not-an-amount"])
    return {"code": 0, "data": {"sh600487": {"qt": {"sh600487": ["1", "test stock", "600487"]}, "m1": rows}}}


@pytest.fixture
def environment(monkeypatch):
    state = {"payload": payload(), "status": 200, "calls": 0, "statuses": []}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            state["calls"] += 1
            raw = json.dumps(state["payload"]).encode()
            self.send_response(state["statuses"].pop(0) if state["statuses"] else state["status"])
            self.end_headers()
            self.wfile.write(raw)
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    original = requests.get
    def local_get(url, **kwargs):
        assert url == "https://ifzq.gtimg.cn/appstock/app/kline/mkline"
        assert kwargs["params"]["param"] == "sh600487,m1,,320"
        return original(f"http://127.0.0.1:{server.server_port}", **kwargs)
    monkeypatch.setattr(requests, "get", local_get)
    monkeypatch.setenv("MARKET_TENCENT_BACKUP_ENABLED", "true")
    primary = Mock()
    primary._call.side_effect = requests.ConnectionError("simulated primary outage")
    app.dependency_overrides[get_provider] = lambda: primary
    app.dependency_overrides[minute_clock] = lambda: NOW
    app.dependency_overrides[get_tencent_provider] = lambda: TencentMinuteProvider()
    yield state, primary, TestClient(app)
    for dependency in (get_provider, minute_clock, get_tencent_provider):
        app.dependency_overrides.pop(dependency, None)
    server.shutdown()
    server.server_close()
    thread.join(timeout=2)


def test_primary_failure_switches_to_complete_tencent_window(environment):
    state, primary, client = environment
    result = client.get("/internal/market/minutes?symbol=600487")
    assert result.status_code == 200
    data = result.json()
    assert data["source"] == "TENCENT_1M"
    assert len(data["bars"]) == 21
    assert Decimal(data["bars"][-1]["volume"]) == 1500
    assert all(b["amount"] is None for b in data["bars"])
    assert Decimal(data["metrics"]["five_minute_return_pct"]) == 2
    assert Decimal(data["metrics"]["previous_twenty_high"]) == Decimal("100.1")
    assert state["calls"] == 1
    primary._call.assert_called_once()


def test_primary_success_has_priority_and_backup_disabled_preserves_behavior(environment, monkeypatch):
    state, primary, client = environment
    rows = state["payload"]["data"]["sh600487"]["m1"]
    primary._call.side_effect = None
    primary._call.return_value = pd.DataFrame([{"时间": datetime.strptime(r[0], "%Y%m%d%H%M"), "开盘": r[1], "收盘": r[2],
        "最高": r[3], "最低": r[4], "成交量": r[5], "成交额": "100000"} for r in rows])
    assert client.get("/internal/market/minutes?symbol=600487").json()["source"] == "AKSHARE_EASTMONEY_1M"
    assert state["calls"] == 0
    monkeypatch.setenv("MARKET_TENCENT_BACKUP_ENABLED", "false")
    primary._call.side_effect = RuntimeError("private failure detail")
    result = client.get("/internal/market/minutes?symbol=600487")
    assert result.status_code == 502 and "private" not in result.text
    assert state["calls"] == 0


@pytest.mark.parametrize("kind", ["http_error", "wrong_symbol", "future", "duplicate", "bad_price", "negative_volume", "yesterday_only"])
def test_invalid_backup_fails_closed(environment, kind):
    state, _, client = environment
    data = state["payload"]["data"]["sh600487"]
    if kind == "http_error": state["status"] = 503
    if kind == "wrong_symbol": data["qt"]["sh600487"][2] = "000731"
    if kind == "future": data["m1"][-1][0] = "202610201023"
    if kind == "duplicate": data["m1"][-1][0] = data["m1"][-2][0]
    if kind == "bad_price": data["m1"][-1][3] = "1"
    if kind == "negative_volume": data["m1"][-1][5] = "-1"
    if kind == "yesterday_only":
        for row in data["m1"]: row[0] = row[0].replace("20261020", "20261019")
    result = client.get("/internal/market/minutes?symbol=600487")
    assert result.status_code == 502
    assert "simulated" not in result.text


def test_gap_disables_twenty_minute_rule_without_synthesizing_bars(environment):
    state, _, client = environment
    del state["payload"]["data"]["sh600487"]["m1"][5]
    data = client.get("/internal/market/minutes?symbol=600487").json()
    assert len(data["bars"]) == 20
    assert data["metrics"]["previous_twenty_high"] is None
    assert Decimal(data["metrics"]["five_minute_return_pct"]) == 2


def test_transient_backup_failure_recovers_with_exactly_one_retry(environment):
    state, _, client = environment
    state["statuses"] = [503, 200]
    result = client.get("/internal/market/minutes?symbol=600487")
    assert result.status_code == 200
    assert result.json()["source"] == "TENCENT_1M"
    assert state["calls"] == 2


@pytest.mark.parametrize("status,calls", [(503, 2), (429, 1), (403, 1)])
def test_failure_retry_is_bounded_and_codes_are_sanitized(environment, status, calls, caplog):
    state, _, client = environment
    state["status"] = status
    result = client.get("/internal/market/minutes?symbol=600487")
    assert result.status_code == 502
    assert state["calls"] == calls
    assert result.headers["X-Minute-Primary-Error"] == "CONNECTION"
    assert result.headers["X-Minute-Backup-Error"] == f"HTTP_{status}"
    assert "simulated primary outage" not in caplog.text
    assert "simulated primary outage" not in result.text


def test_invalid_backup_is_not_retried(environment):
    state, _, client = environment
    state["payload"]["data"]["sh600487"]["m1"][-1][3] = "1"
    result = client.get("/internal/market/minutes?symbol=600487")
    assert result.status_code == 502
    assert state["calls"] == 1
    assert result.headers["X-Minute-Backup-Error"] == "DATA_OHLC"


def test_retry_is_skipped_when_request_budget_is_exhausted(environment, monkeypatch):
    state, _, client = environment
    state["status"] = 503
    original = TencentMinuteProvider.minutes
    def exhausted(self, symbol, day, **kwargs):
        return original(self, symbol, day, retry_deadline=0)
    monkeypatch.setattr(TencentMinuteProvider, "minutes", exhausted)
    assert client.get("/internal/market/minutes?symbol=600487").status_code == 502
    assert state["calls"] == 1


@pytest.mark.parametrize("failure", [requests.ConnectionError, requests.Timeout])
def test_transient_transport_error_is_retried_once(environment, monkeypatch, failure):
    state, _, client = environment
    original = requests.get
    attempts = []
    def flaky(url, **kwargs):
        attempts.append(url)
        if len(attempts) == 1:
            raise failure("private connection detail")
        return original(url, **kwargs)
    monkeypatch.setattr(requests, "get", flaky)
    assert client.get("/internal/market/minutes?symbol=600487").status_code == 200
    assert len(attempts) == 2
    assert state["calls"] == 1


@pytest.mark.parametrize("kind", ["stale", "invalid"])
def test_stale_or_invalid_primary_uses_entire_backup_window(environment, kind):
    state, primary, client = environment
    rows = state["payload"]["data"]["sh600487"]["m1"]
    frame = pd.DataFrame([{"时间": datetime.strptime(r[0], "%Y%m%d%H%M"), "开盘": r[1], "收盘": r[2],
        "最高": r[3], "最低": r[4], "成交量": r[5], "成交额": "100000"} for r in rows])
    if kind == "stale": frame["时间"] -= timedelta(minutes=3)
    else: frame.loc[20, "最高"] = "1"
    primary._call.side_effect = None
    primary._call.return_value = frame
    result = client.get("/internal/market/minutes?symbol=600487")
    assert result.status_code == 200
    assert result.json()["source"] == "TENCENT_1M"
    assert all(bar["amount"] is None for bar in result.json()["bars"])
