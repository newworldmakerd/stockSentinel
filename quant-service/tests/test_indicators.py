from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP, localcontext
import json
from pathlib import Path

import pandas as pd
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.indicators.calculate import CalculationRequest, CalculationError, calculate, VERSION


def payload(count=130, close=None, volume=None):
    bars=[]
    for i in range(count):
        price=Decimal(100+i) if close is None else Decimal(close)
        bars.append(dict(symbol="999998",trade_date=(date(2025,1,1)+timedelta(days=i)).isoformat(),
            open_price=str(price),high_price=str(price+1),low_price=str(price-1),close_price=str(price),
            volume=str(1000+i*10 if volume is None else volume),amount="100000",source="fixture",
            adjustment="QFQ",is_final=True))
    return dict(symbol="999998",adjustment="QFQ",bars=bars)


def test_constant_series_has_zero_macd():
    result=calculate(CalculationRequest(**payload(close="100",volume=1000)))
    assert len(result.indicators)==11
    for row in result.indicators:
        assert (row.ma5,row.ma10,row.ma20,row.ma60)==(Decimal(100),)*4
        assert (row.dif,row.dea,row.macd_hist)==(Decimal(0),)*3
        assert row.volume_ratio==1


def test_current_day_requires_explicit_java_confirmation_and_final_bar():
    from datetime import datetime
    from zoneinfo import ZoneInfo
    data = payload(count=121, close="100")
    today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
    for index, bar in enumerate(data["bars"]):
        bar["trade_date"] = (today-timedelta(days=120-index)).isoformat()
    with pytest.raises(CalculationError, match="UNFINISHED_BAR"):
        calculate(CalculationRequest(**data))
    data["current_day_confirmed"] = True
    assert calculate(CalculationRequest(**data)).indicators[-1].trade_date == today
    data["bars"][-1]["is_final"] = False
    with pytest.raises(CalculationError, match="UNFINISHED_BAR"):
        calculate(CalculationRequest(**data))
    data["bars"][-1]["is_final"] = True
    data["bars"][-1]["trade_date"] = (today+timedelta(days=1)).isoformat()
    with pytest.raises(CalculationError, match="UNFINISHED_BAR"):
        calculate(CalculationRequest(**data))


def test_linear_series_matches_independent_closed_form():
    result=calculate(CalculationRequest(**payload()))
    with localcontext() as context:
        context.prec=50
        q12,q26,q9=Decimal(11)/13,Decimal(25)/27,Decimal(4)/5
        def dif(t):
            return Decimal(7)-Decimal('12.5')*q26**t+Decimal('5.5')*q12**t
        for t,row in enumerate(result.indicators,119):
            expected_dea=Decimal('0.2')*sum(q9**(t-k)*dif(k) for k in range(1,t+1))
            assert row.dif==dif(t).quantize(Decimal('.00000001'),rounding=ROUND_HALF_UP)
            assert row.dea==expected_dea.quantize(Decimal('.00000001'),rounding=ROUND_HALF_UP)
            assert row.macd_hist==(2*(dif(t)-expected_dea)).quantize(Decimal('.00000001'),rounding=ROUND_HALF_UP)
        last=result.indicators[-1]
        assert last.ma5==Decimal('227')
        assert last.ma10==Decimal('224.5')
        assert last.ma20==Decimal('219.5')
        assert last.ma60==Decimal('199.5')
        assert last.volume_ratio==Decimal('1.013274')  # 2290 / mean(2240..2280), excludes current.


def test_nonlinear_series_matches_pandas_reference():
    data=payload(180)
    prices=[]
    for i,bar in enumerate(data['bars']):
        price=Decimal(100)+Decimal((i*17)%41)/10+Decimal(i)/100
        prices.append(float(price))
        bar.update(open_price=str(price),high_price=str(price+1),low_price=str(price-1),close_price=str(price))
    result=calculate(CalculationRequest(**data))
    series=pd.Series(prices)
    dif=series.ewm(span=12,adjust=False).mean()-series.ewm(span=26,adjust=False).mean()
    dea=dif.ewm(span=9,adjust=False).mean()
    for i,row in enumerate(result.indicators,119):
        for value,reference in [(row.dif,dif.iloc[i]),(row.dea,dea.iloc[i]),(row.macd_hist,2*(dif.iloc[i]-dea.iloc[i]))]:
            assert abs(value-Decimal(str(reference)))<=Decimal('.00000001')
        for window in (5,10,20,60):
            assert abs(getattr(row,f'ma{window}')-Decimal(str(series.rolling(window).mean().iloc[i])))<=Decimal('.000001')


def test_120_bars_emit_only_last_bar_and_appending_preserves_prefix():
    first=calculate(CalculationRequest(**payload(120)))
    full=calculate(CalculationRequest(**payload(130)))
    assert len(first.indicators)==1
    assert first.indicators[0]==full.indicators[0]
    assert full.calculation_version==VERSION


def test_zero_previous_volume_is_explicit_null():
    data=payload(volume=0)
    data['bars'][-1]['volume']='100'
    result=calculate(CalculationRequest(**data))
    assert result.indicators[-1].volume_ratio is None
    assert result.indicators[-1].ma5==227


def test_zero_current_volume_has_zero_ratio():
    data=payload()
    data['bars'][-1]['volume']='0'
    assert calculate(CalculationRequest(**data)).indicators[-1].volume_ratio==0


@pytest.mark.parametrize('mutation,code',[
    (lambda d:d['bars'].__setitem__(120,dict(d['bars'][119])), 'UNORDERED_OR_DUPLICATE_DATE'),
    (lambda d:d['bars'][0].update(symbol='000001'), 'MIXED_SERIES'),
    (lambda d:d['bars'][0].update(adjustment='NONE'), 'MIXED_SERIES'),
    (lambda d:d['bars'][0].update(source='other'), 'MIXED_SERIES'),
    (lambda d:d['bars'][-1].update(is_final=False), 'UNFINISHED_BAR'),
    (lambda d:d['bars'][-1].update(trade_date=date.today().isoformat()), 'UNFINISHED_BAR'),
    (lambda d:d['bars'].reverse(), 'UNORDERED_OR_DUPLICATE_DATE'),
])
def test_invalid_series_rejected(mutation,code):
    data=payload()
    mutation(data)
    with pytest.raises(CalculationError) as error:
        calculate(CalculationRequest(**data))
    assert error.value.code==code


def test_api_insufficient_history_has_machine_readable_error():
    with TestClient(app) as client:
        response=client.post('/internal/indicators/calculate',json=payload(119))
    assert response.status_code==422
    assert response.json()['detail']=={'code':'INSUFFICIENT_HISTORY','required':120,'received':119}


@pytest.mark.parametrize('change',[{'close_price':'NaN'},{'volume':'-1'},{'close_price':None}])
def test_api_invalid_numeric_data_rejected(change):
    data=payload()
    data['bars'][-1].update(change)
    with TestClient(app) as client:
        assert client.post('/internal/indicators/calculate',json=data).status_code==422


def test_unknown_calculation_version_rejected():
    data=payload()
    data['calculation_version']='unapproved-v2'
    with TestClient(app) as client:
        assert client.post('/internal/indicators/calculate',json=data).status_code==422


def test_compute_endpoint_works_with_market_provider_disabled(monkeypatch):
    monkeypatch.setenv('MARKET_PROVIDER_ENABLED','false')
    with TestClient(app) as client:
        response=client.post('/internal/indicators/calculate',json=payload())
    assert response.status_code==200
    assert response.json()['indicators'][-1]['ma5']=='227.000000'

def test_indicator_storage_overflow_has_explicit_error():
    with TestClient(app) as client:
        response=client.post('/internal/indicators/calculate',json=payload(close='1000000000000'))
    assert response.status_code==422
    assert response.json()['detail']['code']=='INDICATOR_NUMERIC_OVERFLOW'
