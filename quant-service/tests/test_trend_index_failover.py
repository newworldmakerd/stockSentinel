"""Exercise actual requests HTTP transport against two local provider simulators."""
import json
import threading
import time
from datetime import date, datetime, timedelta
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace
from urllib.parse import urlparse, parse_qs
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.api.market import get_provider
from app.providers import trend_index as provider

END = date(2025, 10, 20)


def tx_payload():
    return {"code": 0, "data": {"sh000300": {"qt": {"sh000300": ["1", "沪深300", "000300"]},
            "day": [[str(END-timedelta(days=200-i)), "1", str(100+i), "1", "1", "1"] for i in range(201)]}}}


def em_payload():
    return {"data": {"code": "000300", "klines": [f"{END-timedelta(days=200-i)},1,{1000+i},1,1,1,1,1" for i in range(201)]}}


@pytest.fixture
def transport(monkeypatch):
    state = SimpleNamespace(east_status=500, east_body="<html>upstream unavailable</html>",
                            tx_status=200, tx_body=tx_payload(), delay=0, calls=[])
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            parsed = urlparse(self.path)
            state.calls.append((parsed.path, parse_qs(parsed.query)))
            if parsed.path == "/east":
                time.sleep(state.delay)
                status, body = state.east_status, state.east_body
            elif parsed.path == "/tx":
                status, body = state.tx_status, state.tx_body
            else:
                status, body = 501, {"error": "UNMODELED_ENDPOINT"}
            raw = (json.dumps(body) if not isinstance(body, str) else body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    monkeypatch.setattr(provider, "PRIMARY_URL", base+"/east")
    monkeypatch.setattr(provider, "BACKUP_URL", base+"/tx")
    monkeypatch.setattr(provider, "TIMEOUT", (0.3, 0.1))
    app.dependency_overrides[get_provider] = lambda: object()
    try:
        with TestClient(app) as client:
            yield state, client
    finally:
        app.dependency_overrides.pop(get_provider, None)
        server.shutdown()
        server.server_close()
        thread.join(timeout=1)


def get(client, **params):
    return client.get("/internal/trend/index", params={"as_of": str(END), **params})


def test_http_500_html_fails_over_to_complete_tencent_window(transport):
    state, client = transport
    r = get(client)
    assert r.status_code == 200
    body = r.json()
    assert body["source"] == provider.TENCENT and body["fallback_used"] is True
    assert Decimal(body["ma200"]) == Decimal("200.5")
    assert Decimal(body["previous_ma200"]) == Decimal("199.5")
    assert body["source_failures"] == [{"source": provider.EASTMONEY, "code": "SOURCE_UNAVAILABLE"}]
    assert len(body["window_hash"]) == 64
    assert state.calls[0][1]["secid"] == ["1.000300"]
    assert state.calls[1][1]["param"][0].startswith("sh000300,day,")
    assert state.calls[1][1]["param"][0].endswith(",640,")


def test_healthy_primary_never_calls_backup(transport):
    state, client = transport
    state.east_status, state.east_body = 200, em_payload()
    body = get(client).json()
    assert body["source"] == provider.EASTMONEY and not body["fallback_used"]
    assert Decimal(body["ma200"]) == Decimal("1100.5")
    assert [p for p, _ in state.calls] == ["/east"]


def test_primary_socket_timeout_uses_backup(transport):
    state, client = transport
    state.delay = 0.25
    assert get(client).json()["source"] == provider.TENCENT
    assert [p for p, _ in state.calls] == ["/east", "/tx"]


@pytest.mark.parametrize("failure", ["identity", "short", "duplicate", "nan", "stale"])
def test_invalid_primary_fails_over_without_mixing(failure, transport):
    state, client = transport
    state.east_status, state.east_body = 200, em_payload()
    d = state.east_body["data"]
    if failure == "identity":
        d["code"] = "399300"
    if failure == "short":
        d["klines"] = d["klines"][-200:]
    if failure == "duplicate":
        d["klines"][-1] = d["klines"][-2]
    if failure == "nan":
        d["klines"][-1] = f"{END},1,NaN,1,1"
    if failure == "stale":
        d["klines"] = [line.replace(str(END), str(END-timedelta(days=1))) for line in d["klines"][:-1]]
    body = get(client).json()
    assert body["source"] == provider.TENCENT
    assert Decimal(body["ma200"]) == Decimal("200.5")
    assert body["source_failures"][0]["code"] == "SOURCE_INVALID"


@pytest.mark.parametrize("failure", ["identity", "short", "duplicate", "nan", "stale", "adjusted", "rejected"])
def test_invalid_backup_also_fails_closed(failure, transport):
    state, client = transport
    d = state.tx_body["data"]["sh000300"]
    if failure == "identity":
        d["qt"]["sh000300"][2] = "399300"
    if failure == "short":
        d["day"] = d["day"][-200:]
    if failure == "duplicate":
        d["day"][-1] = d["day"][-2]
    if failure == "nan":
        d["day"][-1][2] = "NaN"
    if failure == "stale":
        d["day"] = d["day"][:-1]
    if failure == "adjusted":
        d["qfqday"] = d.pop("day")
    if failure == "rejected":
        state.tx_body["code"] = -1
    r = get(client)
    assert r.status_code == 502
    assert r.json()["detail"]["code"] == "TREND_INDEX_UNAVAILABLE"
    assert [f["source"] for f in r.json()["detail"]["failures"]] == [provider.EASTMONEY, provider.TENCENT]


def test_two_html_failures_do_not_expose_provider_body(transport):
    state, client = transport
    state.tx_status, state.tx_body = 503, "<html>private-provider-error</html>"
    r = get(client)
    assert r.status_code == 502 and "private-provider-error" not in r.text


def test_primary_200_html_is_invalid_data_and_can_fail_over(transport):
    state, client = transport
    state.east_status = 200
    body = get(client).json()
    assert body["source"] == provider.TENCENT
    assert body["source_failures"][0]["code"] == "SOURCE_INVALID"


def test_explicit_source_isolates_confirmation_scope(transport):
    state, client = transport
    body = get(client, source="TENCENT").json()
    assert body["source"] == provider.TENCENT and not body["fallback_used"]
    assert [p for p, _ in state.calls] == ["/tx"]
    state.calls.clear()
    assert get(client, source="EASTMONEY").status_code == 502
    assert [p for p, _ in state.calls] == ["/east"]


def test_unconfirmed_today_or_unknown_source_never_calls_upstream(transport):
    state, client = transport
    today = datetime.now(provider.ZONE).date()
    assert client.get("/internal/trend/index", params={"as_of": str(today)}).status_code == 422
    assert get(client, source="ARBITRARY").status_code == 422
    assert state.calls == []


def test_backup_overfetch_is_filtered_without_future_prices(transport):
    state, client = transport
    rows = state.tx_body["data"]["sh000300"]["day"]
    rows.insert(0, [str(END-timedelta(days=731)), "1", "99999"])
    rows.append([str(END+timedelta(days=1)), "1", "99999"])
    body = get(client).json()
    assert body["history_size"] == 201
    assert body["close"] == "300" and Decimal(body["ma200"]) == Decimal("200.5")
