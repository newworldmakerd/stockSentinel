from datetime import date
from unittest.mock import Mock
from fastapi.testclient import TestClient
from app.main import app
from app.api.market import get_provider


def test_provider_disabled(monkeypatch):
    monkeypatch.delenv("MARKET_PROVIDER_ENABLED",raising=False)
    with TestClient(app) as client:
        assert client.get("/internal/stocks").status_code == 503


def test_daily_contract_and_invalid_range():
    mock = Mock()
    mock.daily_bars.return_value = []
    app.dependency_overrides[get_provider] = lambda: mock
    try:
        with TestClient(app) as client:
            result=client.get("/internal/market/daily?symbol=600487&start=2026-01-01&end=2026-10-20&adjustment=QFQ")
            assert result.status_code == 200
            assert result.json()["unit"] == "SHARE"
            assert result.json()["amount_unit"] == "CNY"
            assert result.json()["adjustment"] == "QFQ"
            mock.daily_bars.assert_called_once()
            assert client.get("/internal/market/daily?symbol=600487&start=2026-10-20&end=2026-01-01").status_code == 422
            assert client.get("/internal/market/daily?symbol=oops&start=2026-01-01&end=2026-10-20").status_code == 422
    finally:
        app.dependency_overrides.clear()


def test_upstream_details_are_not_exposed():
    mock=Mock()
    mock.stocks.side_effect=RuntimeError("private upstream response")
    app.dependency_overrides[get_provider]=lambda:mock
    try:
        with TestClient(app) as client:
            response=client.get("/internal/stocks")
            assert response.status_code == 502
            assert "private" not in response.text
    finally:
        app.dependency_overrides.clear()