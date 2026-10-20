from datetime import datetime
from unittest.mock import Mock
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.api.market import get_provider
from app.api.overview import parse_quotes


def raw(key="sz000731", symbol="000731"):
    f = [""]*35
    f[1], f[2], f[3], f[4], f[6], f[30] = "四川美丰", symbol, "7.31", "7.35", "100", "20261020150000"
    return 'v_'+key+'="'+'~'.join(f)+'";'


def test_quote_identity_units_and_timestamp():
    q = parse_quotes(raw(), {"sz000731"})[0]
    assert q["name"] == "四川美丰" and q["volume"] == "10000"
    assert q["quoted_at"] == "2026-10-20T15:00:00+08:00"
    with pytest.raises(ValueError): parse_quotes(raw(symbol="600487"), {"sz000731"})
    with pytest.raises(ValueError): parse_quotes(raw(), {"sh600487"})
    with pytest.raises(ValueError): parse_quotes(raw()+raw(), {"sz000731"})


def test_batch_partial_response_and_failure_are_explicit(monkeypatch):
    app.dependency_overrides[get_provider] = lambda: Mock()
    try:
        response = Mock(content=raw().encode("gbk"))
        get = Mock(return_value=response)
        monkeypatch.setattr("app.api.overview.requests.get", get)
        client = TestClient(app)
        result = client.post("/internal/market/overview", json={"symbols": ["sz000731", "sh600487"]})
        assert result.status_code == 200
        assert len(result.json()["quotes"]) == 1 and "sh600487" in result.json()["failed_symbols"]
        get.side_effect = RuntimeError("private details")
        result = client.post("/internal/market/overview", json={"symbols": ["sz000731"]})
        assert len(result.json()["failed_symbols"]) == 4 and "private" not in result.text
        assert client.post("/internal/market/overview", json={"symbols": ["http://bad"]}).status_code == 422
    finally:
        app.dependency_overrides.pop(get_provider, None)
