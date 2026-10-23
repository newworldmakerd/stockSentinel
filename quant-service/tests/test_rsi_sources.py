import json
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock
from decimal import Decimal
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.providers.rsi_history import RsiHistoryProvider, RsiHistoryError, get_rsi_provider
from app.api.intraday import minute_clock
from app.api import t_observation as module
from test_t_observation import NOW, history


@pytest.fixture
def source_server():
    responses = {}; seen = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            path = self.path.split("?", 1)[0]; seen.append(self.path)
            status, body = responses.get(path, (501, "undefined simulated endpoint"))
            raw = body.encode(); self.send_response(status); self.end_headers(); self.wfile.write(raw)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    provider = RsiHistoryProvider(urls={"primary":base+"/primary", "backup":base+"/backup", "factor":base+"/{code}/factor"})
    yield provider, responses, seen
    server.shutdown(); server.server_close(); thread.join()


def test_real_local_http_primary_and_factor_adjusted_backup_agree(source_server):
    provider, responses, seen = source_server
    frame = history(); rows = frame.to_dict("records")
    klines = [f"{r['时间']},1,{r['收盘']},1,1,1,1,1,1,1,1" for r in rows]
    responses["/primary"] = (200, json.dumps({"data":{"code":"000731", "klines":klines}}))
    raw = [{"day":str(r["时间"]), "close":str(r["收盘"])} for r in rows]
    responses["/backup"] = (200, "=("+json.dumps(raw)+");")
    responses["/sz000731/factor"] = (200, 'var sz000731qfq={"data":[{"d":"1900-01-01","f":"1"}]}\n/* inert trailing data */')
    primary = provider.primary("000731","2026-09-21","2026-10-21",time.monotonic()+10)
    backup = provider.backup("000731","2026-09-21","2026-10-21",time.monotonic()+10)
    for key in ("rsi5", "rsi15"):
        assert abs(getattr(module.calculate_rsi("000731",primary,NOW),key)-getattr(module.calculate_rsi("000731",backup,NOW),key)) < Decimal("1e-8")
    assert "fqt=1" in seen[0] and "klt=5" in seen[0]
    responses["/sz000731/factor"] = (200, 'var sz000731qfq={"data":[{"d":"2026-10-21","f":"1"},{"d":"1900-01-01","f":"2"}]}')
    adjusted = provider.backup("000731","2026-09-21","2026-10-21",time.monotonic()+10)
    assert adjusted.iloc[0]["收盘"] == Decimal(str(rows[0]["收盘"]))/2
    assert adjusted.iloc[-1]["收盘"] == Decimal(str(rows[-1]["收盘"]))
    responses["/sz000731/factor"] = (200, 'var sh600487qfq={"data":[]}')
    with pytest.raises(RsiHistoryError, match="IDENTITY"):
        provider.backup("000731","2026-09-21","2026-10-21",time.monotonic()+10)
    with pytest.raises(RsiHistoryError, match="BUDGET"):
        provider.primary("000731","2026-09-21","2026-10-21",time.monotonic()-1)


@pytest.fixture
def endpoint():
    module._cache.clear(); module._failed.clear(); module._circuits.clear()
    provider = Mock(); provider.primary.return_value = history(); provider.backup.return_value = history()
    app.dependency_overrides[get_rsi_provider] = lambda: provider
    app.dependency_overrides[minute_clock] = lambda: NOW
    with TestClient(app) as client:
        yield client, provider
    app.dependency_overrides.clear(); module._cache.clear(); module._failed.clear(); module._circuits.clear()


def test_endpoint_fallback_uses_whole_valid_window(endpoint, monkeypatch):
    client, provider = endpoint; monkeypatch.setenv("MARKET_RSI_SINA_BACKUP_ENABLED", "true")
    provider.primary.side_effect = TimeoutError("private failure")
    response = client.get("/internal/indicators/t-rsi?symbol=000731")
    assert response.status_code == 200 and response.json()["source"] == "SINA_QFQ_5M"
    assert provider.backup.call_count == 1
    assert client.get("/internal/indicators/t-rsi?symbol=000731").status_code == 200
    assert provider.primary.call_count == 1


def test_backoff_circuit_and_recovery_with_no_error_text(endpoint, monkeypatch):
    client, provider = endpoint; monkeypatch.setenv("MARKET_RSI_SINA_BACKUP_ENABLED", "false")
    tick = [1000.0]; monkeypatch.setattr(module, "_elapsed", lambda:tick[0])
    provider.primary.side_effect = RuntimeError("private provider text")
    for i in range(3):
        response = client.get("/internal/indicators/t-rsi?symbol=000731")
        assert response.status_code == 502 and "private" not in response.text
        assert client.get("/internal/indicators/t-rsi?symbol=000731").status_code == 502
        assert provider.primary.call_count == i+1
        tick[0] += 31
    response = client.get("/internal/indicators/t-rsi?symbol=000731")
    assert response.headers["X-RSI-Primary-Error"] == "CIRCUIT_OPEN" and provider.primary.call_count == 3
    tick[0] += 121; provider.primary.side_effect = None
    assert client.get("/internal/indicators/t-rsi?symbol=000731").status_code == 200


def test_bad_primary_window_never_gets_partially_mixed_with_backup(endpoint, monkeypatch):
    client, provider = endpoint; monkeypatch.setenv("MARKET_RSI_SINA_BACKUP_ENABLED", "true")
    bad = history(); bad.loc[5,"收盘"] = -1; provider.primary.return_value = bad
    result = client.get("/internal/indicators/t-rsi?symbol=000731")
    assert result.status_code == 200 and result.json()["source"] == "SINA_QFQ_5M"
    module._gate.acquire()
    try: assert client.get("/internal/indicators/t-rsi?symbol=600487").status_code == 503
    finally: module._gate.release()
