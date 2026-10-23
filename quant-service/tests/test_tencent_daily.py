from datetime import date
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from unittest.mock import Mock
import json
import pytest
import requests
from app.providers.base import Adjustment
from app.providers.tencent_daily import TencentDailyProvider
from app.providers.akshare_provider import AkshareProvider


@pytest.fixture
def transport(monkeypatch):
    state = {"symbol": "000731", "exchange": "sz", "calls": [], "kind": "ok"}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            from urllib.parse import urlparse, parse_qs
            param = parse_qs(urlparse(self.path).query)["param"][0].split(",")
            state["calls"].append(param)
            key = state["exchange"]+state["symbol"]
            rows = [["2026-10-19", "7.4", "7.35", "7.5", "7.3", "128045.00", {}, "2.33", "9570.33"],
                    ["2026-10-20", "7.35", "7.31", "7.4", "7.2", "66311.00", {}, "1.21", "4821.00"]]
            if state["kind"] == "missing_amount": rows[-1] = rows[-1][:8]
            if state["kind"] == "duplicate": rows[-1][0] = rows[0][0]
            if state["kind"] == "negative": rows[-1][8] = "-1"
            if state["kind"] == "bad_price": rows[-1][3] = "1"
            data = {param[-1]+"day": rows, "qt": {key: ["1", "test", "600487" if state["kind"] == "identity" else state["symbol"]]}}
            if state["kind"] == "wrong_adjustment": data["day"] = data.pop("qfqday")
            raw = json.dumps({"code": 0, "data": {key: data}}).encode()
            self.send_response(200); self.end_headers(); self.wfile.write(raw)
        def log_message(self, *_): pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True); thread.start()
    original = requests.get
    def request(url, **kwargs):
        assert url == "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get"
        return original(f"http://127.0.0.1:{server.server_port}", **kwargs)
    monkeypatch.setattr(requests, "get", request)
    monkeypatch.setattr("app.providers.tencent_daily.time.sleep", lambda _: None)
    yield state
    server.shutdown(); server.server_close(); thread.join(timeout=2)


def fetch(symbol="000731", adjustment=Adjustment.QFQ, today=date(2026,10,21)):
    return TencentDailyProvider().daily_bars(symbol, date(2026,10,19), date(2026,10,20), adjustment, today)


@pytest.mark.parametrize("symbol,exchange,factor", [("000731","sz",100),("000001","sz",100),("300001","sz",100),("600487","sh",100),("688001","sh",1)])
def test_equity_board_units_amount_and_source(transport,symbol,exchange,factor):
    transport.update(symbol=symbol,exchange=exchange)
    bars = fetch(symbol)
    assert bars[-1].volume == Decimal("66311")*factor
    assert bars[-1].amount == Decimal("48210000")
    assert bars[-1].turnover_rate == Decimal("0.0121")
    assert bars[-1].source == "AKSHARE_TENCENT_DAILY" and bars[-1].is_final
    assert all(b.adjustment == Adjustment.QFQ for b in bars)


@pytest.mark.parametrize("kind", ["missing_amount","duplicate","negative","bad_price","identity","wrong_adjustment"])
def test_bad_daily_backup_rejected(transport,kind):
    transport["kind"] = kind
    with pytest.raises(Exception): fetch()


def test_current_day_not_automatically_final(transport):
    assert not fetch(today=date(2026,10,20))[-1].is_final


def test_primary_circuit_and_recovery_keep_default_behavior(transport,monkeypatch):
    from app.providers.tencent_daily import get_tencent_daily
    get_tencent_daily.cache_clear()
    p = AkshareProvider(today=lambda: date(2026,10,21))
    p._eastmoney_daily = Mock(side_effect=requests.ConnectionError("local primary failure"))
    monkeypatch.delenv("MARKET_TENCENT_DAILY_BACKUP_ENABLED", raising=False)
    with pytest.raises(requests.ConnectionError): p.daily_bars("000731", date(2026,10,19), date(2026,10,20), Adjustment.QFQ)
    assert not transport["calls"]
    p._eastmoney_daily.reset_mock()
    monkeypatch.setenv("MARKET_TENCENT_DAILY_BACKUP_ENABLED", "true")
    for _ in range(4): assert len(p.daily_bars("000731", date(2026,10,19), date(2026,10,20), Adjustment.QFQ)) == 2
    assert p._eastmoney_daily.call_count == 3
    assert len(transport["calls"]) == 4
    p._daily_retry_at = 0
    p._eastmoney_daily.side_effect = None
    p._eastmoney_daily.return_value = ["healthy primary"]
    assert p.daily_bars("000731", date(2026,10,19), date(2026,10,20), Adjustment.QFQ) == ["healthy primary"]
    assert len(transport["calls"]) == 4
    get_tencent_daily.cache_clear()
