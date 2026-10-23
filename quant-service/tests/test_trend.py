from datetime import date, timedelta
from decimal import Decimal
from types import SimpleNamespace
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.api.market import get_provider
from app.api import trend
from app.providers.base import DailyBar, Adjustment


def body(end=date(2025, 10, 20)):
    raw = [DailyBar(symbol="000731", trade_date=end-timedelta(days=119-i),
                    open_price=Decimal(100+i), high_price=Decimal(101+i), low_price=Decimal(99+i),
                    close_price=Decimal(100+i), volume=10000, amount=50000000,
                    adjustment=Adjustment.NONE, source="TEST", is_final=True) for i in range(120)]
    adjusted = [b.model_copy(update={"adjustment": Adjustment.QFQ}) for b in raw]
    return trend.TrendInput(symbol="000731", adjusted=adjusted, raw=raw)


def test_ma_periods_and_twenty_day_change_are_exact():
    m = trend.metrics(body())
    assert m["ma20"] == Decimal("209.5")
    assert m["ma60"] == Decimal("189.5")
    assert m["ma120"] == Decimal("159.5")
    assert abs(m["ma60_change20"]-(Decimal("189.5")/Decimal("169.5")-1)) < Decimal("1e-25")
    assert m["atr14"] == 2


def test_channel_excludes_current_day_and_preview_channel_includes_it():
    x = body()
    x.adjusted[-1] = x.adjusted[-1].model_copy(update={"high_price": Decimal(10000)})
    assert trend.metrics(x)["previous_twenty_high"] == 219
    assert trend.metrics(x)["recent_twenty_high"] == 10000


def test_atr_is_simple_fourteen_tr_mean_not_wilder():
    x = body()
    x.adjusted[-15] = x.adjusted[-15].model_copy(update={"high_price": Decimal(10000)})
    assert trend.metrics(x)["atr14"] == 2


def test_adjustment_factor_converts_atr_to_actual_price_basis():
    x = body()
    x.adjusted = [b.model_copy(update={k: getattr(b, k)/2 for k in ["open_price", "high_price", "low_price", "close_price"]}) for b in x.adjusted]
    result = trend.metrics(x)
    assert result["raw_factor"] == 2
    assert result["atr14"] == 1
    assert result["raw_atr14"] == 2


@pytest.mark.parametrize("change", ["source", "date", "unfinished", "adjustment"])
def test_bad_or_mixed_series_is_rejected(change):
    x = body()
    if change == "source":
        x.raw[0] = x.raw[0].model_copy(update={"source": "OTHER"})
    if change == "date":
        x.raw[-1] = x.raw[-1].model_copy(update={"trade_date": date(2025, 10, 21)})
    if change == "unfinished":
        x.adjusted[-1] = x.adjusted[-1].model_copy(update={"is_final": False})
    if change == "adjustment":
        x.raw[-1] = x.raw[-1].model_copy(update={"adjustment": Adjustment.QFQ})
    with pytest.raises(ValueError):
        trend.metrics(x)


def quote_text():
    f = ["" for _ in range(49)]
    for i, v in {1: "四川美丰", 2: "000731", 3: "8", 4: "7.9", 6: "100", 30: "20251020101000", 37: "5000", 47: "8.69", 48: "7.11"}.items():
        f[i] = v
    return 'v_sz000731="'+'~'.join(f)+'";'


def test_quote_units_and_limit_prices():
    q = trend.parse_quotes(quote_text(), {"sz000731"})[0]
    assert q["amount"] == 50000000
    assert q["volume"] == 10000
    assert q["limit_up"] == Decimal("8.69")
    assert q["quoted_at"].utcoffset().total_seconds() == 28800


def test_index_negative_limit_sentinels_do_not_reject_stock_batch():
    index = quote_text().replace("sz000731", "sh000300").replace("000731", "000300").replace("8.69", "-1").replace("7.11", "-1")
    rows = trend.parse_quotes(index+quote_text(), {"sh000300", "sz000731"})
    assert rows[0]["limit_up"] is None and rows[0]["limit_down"] is None
    assert rows[1]["limit_up"] == Decimal("8.69")
    with pytest.raises(ValueError):
        trend.parse_quotes(quote_text().replace("8.69", "-1"), {"sz000731"})


def test_api_calculation_uses_decimal_strings_and_never_calls_provider():
    with TestClient(app) as client:
        r = client.post("/internal/trend/calculate", json=body().model_dump(mode="json"))
        assert r.status_code == 200
        assert r.json()["ma60"] == "189.5"
        assert len(r.json()["window_hash"]) == 64


def test_index_and_quote_are_disabled_without_provider(monkeypatch):
    monkeypatch.setenv("MARKET_PROVIDER_ENABLED", "false")
    with TestClient(app) as client:
        assert client.get("/internal/trend/index?as_of=2025-10-20").status_code == 503
        assert client.post("/internal/trend/quotes", json={"symbols": ["sz000731"]}).status_code == 503


def test_quote_route_matches_contract_with_mocked_transport(monkeypatch):
    app.dependency_overrides[get_provider] = lambda: object()
    monkeypatch.setattr(trend, "_last_quote", 0)
    monkeypatch.setattr(trend.requests, "get", lambda *a, **k: SimpleNamespace(content=quote_text().encode("gbk"), raise_for_status=lambda: None))
    try:
        with TestClient(app) as client:
            r = client.post("/internal/trend/quotes", json={"symbols": ["sz000731"]})
            assert r.status_code == 200
            assert r.json()["quotes"][0]["amount"] == "50000000"
            assert r.json()["failed_symbols"] == []
    finally:
        app.dependency_overrides.pop(get_provider)


def test_index_uses_fixed_csi300_identity_and_201_closes(monkeypatch):
    app.dependency_overrides[get_provider] = lambda: object()
    end = date(2025, 10, 20)
    rows = [f"{end-timedelta(days=200-i)},1,{100+i},1,1,1,1,1" for i in range(201)]
    seen = []
    def get(*a, **k):
        seen.append(k["params"])
        return SimpleNamespace(raise_for_status=lambda: None, json=lambda: {"data": {"code": "000300", "klines": rows}})
    monkeypatch.setattr(trend.requests, "get", get)
    try:
        with TestClient(app) as client:
            r = client.get("/internal/trend/index?as_of=2025-10-20")
            assert r.status_code == 200
            assert r.json()["key"] == "sh000300"
            assert Decimal(r.json()["ma200"]) == Decimal("200.5")
            assert seen[0]["secid"] == "1.000300"
    finally:
        app.dependency_overrides.pop(get_provider)
