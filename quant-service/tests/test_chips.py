from datetime import date, timedelta
from decimal import Decimal
import copy
import pytest
import requests
from fastapi.testclient import TestClient
from app.main import app
from app.providers import chips

DAY = date(2025, 10, 20)


def fixture(count=210):
    return {'code': '000731', 'market': 0, 'name': '四川美丰', 'klines': [
        f'{DAY-timedelta(days=count-i-1)},10,10,10.5,9.5,100000,200000000,10,0,0,5'
        for i in range(count)]}


def test_actual_sdk_algorithm_has_narrow_chips_and_correct_ratio_units():
    result = chips.calculate('000731', DAY, fixture())
    assert result['concentration90'] < Decimal('0.10')
    assert result['concentration70'] < Decimal('0.06')
    assert result['low90'] <= result['low70'] <= result['high70'] <= result['high90']
    assert Decimal('0') <= result['profit_ratio'] <= 1
    assert result['average20_amount'] == Decimal('200000000')
    assert result['volume'] == Decimal('10000000')
    assert len(result['algorithm_hash']) == 64
    assert result['adjustment'] == 'NONE'


@pytest.mark.parametrize('mutation,code', [
    (lambda d: d.update(code='600487'), 'CHIP_IDENTITY_INVALID'),
    (lambda d: d.update(market=1), 'CHIP_IDENTITY_INVALID'),
    (lambda d: d['klines'].pop(), 'CHIP_BASIS_MISSING'),
    (lambda d: d['klines'].reverse(), 'CHIP_SERIES_INVALID'),
    (lambda d: d['klines'].__setitem__(-1, d['klines'][-1].replace(',10,10,', ',NaN,10,')), 'CHIP_SERIES_INVALID'),
    (lambda d: d.update(klines=d['klines'][-119:]), 'CHIP_HISTORY_UNDER_120'),
    (lambda d: d.update(klines=d['klines']+[d['klines'][-1]]), 'CHIP_SERIES_INVALID'),
])
def test_invalid_input_is_rejected_before_sdk_calculation(mutation, code):
    value = fixture(); mutation(value)
    with pytest.raises(chips.ChipError, match=code):
        chips.records_for('000731', DAY, value)


def test_fetch_uses_fixed_unadjusted_window_and_timeout(monkeypatch):
    class Response:
        def raise_for_status(self): pass
        def json(self): return {'data': fixture()}
    seen = {}
    def get(url, **kwargs):
        seen.update(url=url, **kwargs)
        return Response()
    monkeypatch.setattr(chips.requests, 'get', get)
    monkeypatch.setattr(chips, '_last', 0)
    result = chips.fetch('000731', DAY)
    assert seen['timeout'] == (3, 8)
    assert seen['params']['fqt'] == '0'
    assert seen['params']['lmt'] == '210'
    assert seen['params']['end'] == '20251020'
    assert result['source'] == 'CHIP_EASTMONEY'


def test_source_timeout_has_safe_code(monkeypatch):
    def unavailable(*args, **kwargs): raise requests.Timeout('private upstream text')
    monkeypatch.setattr(chips.requests, 'get', unavailable)
    monkeypatch.setattr(chips, '_last', 0)
    with pytest.raises(chips.ChipError, match='CHIP_SOURCE_UNAVAILABLE') as exc:
        chips.fetch('000731', DAY)
    assert 'private' not in str(exc.value)


def test_route_defaults_off_and_requires_confirmed_today(monkeypatch):
    client = TestClient(app)
    monkeypatch.delenv('MARKET_CHIP_ENABLED', raising=False)
    assert client.get('/internal/chips/snapshot', params={'symbol':'000731', 'as_of':str(DAY)}).status_code == 503
    monkeypatch.setenv('MARKET_CHIP_ENABLED', 'true')
    called = []
    monkeypatch.setattr(chips, 'fetch', lambda *args: called.append(args))
    today = chips.datetime.now(chips.ZONE).date()
    assert client.get('/internal/chips/snapshot', params={'symbol':'000731', 'as_of':str(today)}).status_code == 422
    assert client.get('/internal/chips/snapshot', params={'symbol':'000731', 'as_of':str(today+timedelta(days=1)), 'current_day_confirmed':'true'}).status_code == 422
    assert not called


def test_route_returns_decimal_strings_and_safe_failure(monkeypatch):
    client = TestClient(app); monkeypatch.setenv('MARKET_CHIP_ENABLED', 'true')
    value = chips.calculate('000731', DAY, fixture())
    monkeypatch.setattr(chips, 'fetch', lambda *args: value)
    response = client.get('/internal/chips/snapshot', params={'symbol':'000731', 'as_of':str(DAY)})
    assert response.status_code == 200
    assert isinstance(response.json()['concentration90'], str)
    def unavailable(*args): raise chips.ChipError('CHIP_SOURCE_UNAVAILABLE')
    monkeypatch.setattr(chips, 'fetch', unavailable)
    assert client.get('/internal/chips/snapshot', params={'symbol':'000731', 'as_of':str(DAY)}).json() == {'detail':'CHIP_SOURCE_UNAVAILABLE'}
