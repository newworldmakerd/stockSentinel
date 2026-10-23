"""Bounded Eastmoney daily input plus the installed AKShare CYQ algorithm.

CYQ is a turnover-based estimate, not disclosed investor positions. No business DB.
"""
import ast
from datetime import date, datetime
from decimal import Decimal
from functools import lru_cache
import hashlib
import importlib
import inspect
import math
from threading import Lock
import time
from zoneinfo import ZoneInfo
import requests

URL = 'https://push2his.eastmoney.com/api/qt/stock/kline/get'
SOURCE = 'CHIP_EASTMONEY'
VERSION = 'akshare-cyq-210-v1'
ZONE = ZoneInfo('Asia/Shanghai')
_gate = Lock()
_last = 0.0


class ChipError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


@lru_cache(maxsize=1)
def algorithm():
    # Reuse SDK calculation without executing its unbounded requests.get().
    module = importlib.import_module('akshare.stock_feature.stock_cyq_em')
    tree = ast.parse(inspect.getsource(module.stock_cyq_em))
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'html_str' for t in node.targets):
            if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                script = node.value.value
                return script, hashlib.sha256(script.encode()).hexdigest()
    raise ChipError('CHIP_ALGORITHM_UNAVAILABLE')


def records_for(symbol, as_of, data):
    market = 1 if symbol.startswith('6') else 0
    if not isinstance(data, dict) or data.get('code') != symbol or data.get('market') != market:
        raise ChipError('CHIP_IDENTITY_INVALID')
    lines = data.get('klines')
    if not isinstance(lines, list) or len(lines) > 210:
        raise ChipError('CHIP_SERIES_INVALID')
    if len(lines) < 120:
        raise ChipError('CHIP_HISTORY_UNDER_120')
    rows = []
    for line in lines:
        fields = line.split(',')
        if len(fields) != 11:
            raise ChipError('CHIP_SERIES_INVALID')
        day = date.fromisoformat(fields[0])
        values = [float(x) for x in fields[1:]]
        if not all(math.isfinite(x) for x in values):
            raise ChipError('CHIP_SERIES_INVALID')
        op, close, high, low, volume, amount = values[:6]
        if (day > as_of or (rows and fields[0] <= rows[-1]['date']) or min(op, close, high, low) <= 0
                or not low <= min(op, close) <= max(op, close) <= high
                or volume < 0 or amount < 0 or values[-1] < 0):
            raise ChipError('CHIP_SERIES_INVALID')
        rows.append(dict(date=fields[0], open=op, close=close, high=high, low=low,
                         volume=volume, volume_money=amount, hsl=values[-1]))
    if rows[-1]['date'] != as_of.isoformat():
        raise ChipError('CHIP_BASIS_MISSING')
    return rows


def calculate(symbol, as_of, data):
    rows = records_for(symbol, as_of, data)
    script, digest = algorithm()
    from py_mini_racer import MiniRacer
    with MiniRacer() as js:
        js.eval(script, timeout=2000)
        value = js.call('CYQCalculator', len(rows)-1, rows, timeout=3000)
    d = lambda x: Decimal(str(x))
    c90, c70 = value['percentChips']['90'], value['percentChips']['70']
    if not isinstance(data.get('name'), str) or not data['name'].strip():
        raise ChipError('CHIP_IDENTITY_INVALID')
    result = dict(symbol=symbol, name=data['name'], as_of=as_of, source=SOURCE, version=VERSION,
                  adjustment='NONE', algorithm_hash=digest, history_size=len(rows),
                  average_cost=d(value['avgCost']), profit_ratio=d(value['benefitPart']),
                  low90=d(c90['priceRange'][0]), high90=d(c90['priceRange'][1]), concentration90=d(c90['concentration']),
                  low70=d(c70['priceRange'][0]), high70=d(c70['priceRange'][1]), concentration70=d(c70['concentration']),
                  average20_amount=sum(d(r['volume_money']) for r in rows[-20:])/20,
                  volume=d(rows[-1]['volume'])*100, close=d(rows[-1]['close']),
                  window_hash=hashlib.sha256(repr(rows).encode()).hexdigest(), observed_at=datetime.now(ZONE))
    nums = [x for x in result.values() if isinstance(x, Decimal)]
    if (not all(x.is_finite() for x in nums) or not 0 <= result['profit_ratio'] <= 1
            or not 0 <= result['concentration70'] <= result['concentration90'] <= 1
            or not 0 < result['low90'] <= result['low70'] <= result['high70'] <= result['high90']
            or not result['low90'] <= result['average_cost'] <= result['high90']):
        raise ChipError('CHIP_METRICS_INVALID')
    return result


def fetch(symbol, as_of):
    global _last
    with _gate:
        delay = 1.0 - (time.monotonic() - _last)
        if delay > 0:
            time.sleep(delay)
        try:
            response = requests.get(URL, params=dict(secid=f'{1 if symbol.startswith("6") else 0}.{symbol}',
                fields1='f1,f2,f3,f4,f5,f6', fields2='f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61',
                klt='101', fqt='0', end=as_of.strftime('%Y%m%d'), lmt='210'), timeout=(3, 8))
            response.raise_for_status()
            data = response.json().get('data')
        except Exception:
            raise ChipError('CHIP_SOURCE_UNAVAILABLE') from None
        finally:
            _last = time.monotonic()
        try:
            return calculate(symbol, as_of, data)
        except ChipError:
            raise
        except Exception:
            raise ChipError('CHIP_METRICS_INVALID') from None
