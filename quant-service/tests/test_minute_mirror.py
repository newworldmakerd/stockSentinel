import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from types import SimpleNamespace
import pytest
import requests
from app.providers.akshare_provider import AkshareProvider, ProviderDataError


@pytest.fixture
def mirror(monkeypatch):
    payload = {"data": {"code": "000731", "market": 0,
                        "trends": ["2026-10-20 10:00,7.3,7.4,7.4,7.3,200,148000,7.4"]}}
    calls = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            calls.append(self.path)
            raw = json.dumps(payload).encode()
            self.send_response(200)
            self.end_headers()
            self.wfile.write(raw)
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    original_get = requests.get
    def local_get(url, **kwargs):
        assert url == "https://push2.eastmoney.com/api/qt/stock/trends2/get"
        assert kwargs["params"]["secid"] == "0.000731"
        return original_get(f"http://127.0.0.1:{server.server_port}", **kwargs)
    monkeypatch.setattr(requests, "get", local_get)
    monkeypatch.setattr("app.providers.akshare_provider.time.sleep", lambda _: None)
    yield payload, calls
    server.shutdown()
    server.server_close()
    thread.join(timeout=2)


def failed(**_):
    raise requests.ConnectionError("Local simulated primary outage")


def fetch(provider):
    return provider._call("stock_zh_a_hist_min_em", symbol="000731", period="1", adjust="",
                          start_date="2026-10-20 09:30:00", end_date="2026-10-20 15:00:00")


def test_opt_in_primary_outage_uses_same_vendor_schema(mirror, monkeypatch):
    monkeypatch.setenv("MARKET_MINUTE_MIRROR_ENABLED", "true")
    frame = fetch(AkshareProvider(sdk=SimpleNamespace(stock_zh_a_hist_min_em=failed)))
    assert len(frame) == 1
    assert frame.iloc[0]["成交量"] == 200  # remains hands until existing normalizer converts
    assert len(mirror[1]) == 1


def test_default_disabled_and_successful_primary_do_not_call_mirror(mirror, monkeypatch):
    monkeypatch.delenv("MARKET_MINUTE_MIRROR_ENABLED", raising=False)
    with pytest.raises(requests.ConnectionError):
        fetch(AkshareProvider(sdk=SimpleNamespace(stock_zh_a_hist_min_em=failed)))
    monkeypatch.setenv("MARKET_MINUTE_MIRROR_ENABLED", "true")
    assert fetch(AkshareProvider(sdk=SimpleNamespace(stock_zh_a_hist_min_em=lambda **_: "primary"))) == "primary"
    assert mirror[1] == []


def test_wrong_symbol_metadata_is_rejected(mirror, monkeypatch):
    monkeypatch.setenv("MARKET_MINUTE_MIRROR_ENABLED", "true")
    mirror[0]["data"]["code"] = "600487"
    with pytest.raises(ProviderDataError):
        fetch(AkshareProvider(sdk=SimpleNamespace(stock_zh_a_hist_min_em=failed)))
