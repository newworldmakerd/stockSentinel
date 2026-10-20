from datetime import date
from decimal import Decimal
from unittest.mock import Mock
import pandas as pd
import pytest
from pydantic import ValidationError
from app.providers.base import Adjustment
from app.providers.akshare_provider import AkshareProvider, ProviderDataError


def raw(**changes):
    return dict({"日期": date(2026,10,19), "股票代码": "600487", "开盘": 10,
                 "最高": 12, "最低": 9, "收盘": 11, "成交量": 123,
                 "成交额": 135300, "换手率": 1.25}, **changes)


def provider(rows):
    sdk = Mock()
    sdk.stock_zh_a_hist.return_value = pd.DataFrame(rows)
    return AkshareProvider(sdk=sdk, today=lambda: date(2026,10,20)), sdk


def test_units_and_adjustment():
    instance, sdk = provider([raw()])
    result = instance.daily_bars("600487",date(2026,1,1),date(2026,10,20),Adjustment.QFQ)
    assert result[0].volume == Decimal("12300")
    assert result[0].amount == Decimal("135300")
    assert result[0].turnover_rate == Decimal("0.0125")
    assert result[0].is_final
    assert sdk.stock_zh_a_hist.call_args.kwargs["adjust"] == "qfq"
    assert sdk.stock_zh_a_hist.call_args.kwargs["timeout"] == 15


def test_current_day_is_not_final_even_after_close():
    instance, _ = provider([raw(日期=date(2026,10,20))])
    assert not instance.daily_bars("600487",date(2026,10,20),date(2026,10,20),Adjustment.NONE)[0].is_final


@pytest.mark.parametrize("rows", [[raw(),raw()], [raw(股票代码="000001")], [raw(日期=date(2026,10,21))]])
def test_bad_identity_and_dates_rejected(rows):
    instance, _ = provider(rows)
    with pytest.raises(ProviderDataError):
        instance.daily_bars("600487",date(2026,1,1),date(2026,10,20),Adjustment.NONE)


@pytest.mark.parametrize("changes", [{"收盘": float("nan")}, {"成交量": -1}, {"开盘": -1}, {"最高": 8}])
def test_invalid_market_values_rejected(changes):
    instance, _ = provider([raw(**changes)])
    with pytest.raises(ValidationError):
        instance.daily_bars("600487",date(2026,1,1),date(2026,10,20),Adjustment.NONE)


def test_empty_data_is_not_invented():
    instance, _ = provider([])
    assert instance.daily_bars("600487",date(2026,1,1),date(2026,10,20),Adjustment.NONE) == []


def test_exchange_comes_from_catalog_feed(monkeypatch):
    instance = AkshareProvider()
    frames = iter([pd.DataFrame([{"证券代码":"600487","证券简称":"test"}]),
                   pd.DataFrame([{"证券代码":"688001","证券简称":"kcb"}]),
                   pd.DataFrame([{"A股代码":"000001","A股简称":"sz"}]),
                   pd.DataFrame([{"证券代码":"920001","证券简称":"bj"}])])
    monkeypatch.setattr(instance,"_call",lambda *args,**kwargs: next(frames))
    assert {item.symbol:item.exchange for item in instance.stocks()} == {"600487":"SH","688001":"SH","000001":"SZ","920001":"BJ"}