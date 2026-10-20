from datetime import date
import pytest
from pydantic import ValidationError
from app.providers.base import DailyBar, Adjustment


def bar(**changes):
    return DailyBar(**(dict(symbol="600487", trade_date=date(2026,10,20),
        open_price=10, high_price=12, low_price=9, close_price=11,
        volume=100, adjustment=Adjustment.NONE, source="fixture", is_final=True) | changes))


def test_daily_bar_preserves_units_and_adjustment():
    result = bar()
    assert result.volume == 100
    assert result.adjustment == Adjustment.NONE


def test_inconsistent_prices_rejected():
    with pytest.raises(ValidationError):
        bar(high_price=8)


def test_negative_volume_rejected():
    with pytest.raises(ValidationError):
        bar(volume=-1)