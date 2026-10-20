"""Versioned, deterministic indicators. No network or database access."""
from datetime import date, datetime
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP, localcontext
from typing import Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, Field
from app.providers.base import Adjustment, DailyBar

VERSION = "ma-macd-vol-v1"
WARMUP = 120


class CalculationRequest(BaseModel):
    symbol: str = Field(pattern=r"^[0-9]{6}$")
    adjustment: Adjustment
    calculation_version: Literal["ma-macd-vol-v1"] = VERSION
    bars: list[DailyBar] = Field(min_length=1, max_length=10000)
    # Trusted Java orchestration sets this only after its source/date confirmation checks.
    current_day_confirmed: bool = False


class IndicatorRow(BaseModel):
    trade_date: date
    ma5: Decimal
    ma10: Decimal
    ma20: Decimal
    ma60: Decimal
    dif: Decimal
    dea: Decimal
    macd_hist: Decimal
    volume_ratio: Decimal | None


class CalculationResponse(BaseModel):
    symbol: str
    adjustment: Adjustment
    calculation_version: str = VERSION
    history_size: int
    warmup_period: int = WARMUP
    indicators: list[IndicatorRow]


class CalculationError(ValueError):
    def __init__(self, code, **details):
        self.code = code
        self.details = details


def calculate(request: CalculationRequest) -> CalculationResponse:
    bars = request.bars
    if len(bars) < WARMUP:
        raise CalculationError("INSUFFICIENT_HISTORY", required=WARMUP, received=len(bars))
    today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
    previous = None
    source = bars[0].source
    for bar in bars:
        if bar.symbol != request.symbol or bar.adjustment != request.adjustment or bar.source != source:
            raise CalculationError("MIXED_SERIES")
        if not bar.is_final or bar.trade_date > today or (bar.trade_date == today and not request.current_day_confirmed):
            raise CalculationError("UNFINISHED_BAR")
        if previous is not None and bar.trade_date <= previous:
            raise CalculationError("UNORDERED_OR_DUPLICATE_DATE")
        previous = bar.trade_date
    rows = []
    with localcontext() as context:
        context.prec = 50
        ema12 = ema26 = bars[0].close_price
        dea = Decimal(0)  # First DIF = first close - first close = 0.
        alpha12, alpha26, alpha9 = Decimal(2)/13, Decimal(2)/27, Decimal(2)/10
        closes = [bar.close_price for bar in bars]
        volumes = [bar.volume for bar in bars]
        for index, bar in enumerate(bars):
            if index:
                ema12 += alpha12 * (bar.close_price - ema12)
                ema26 += alpha26 * (bar.close_price - ema26)
            dif = ema12 - ema26
            dea += alpha9 * (dif - dea)
            if index < WARMUP - 1:
                continue
            moving = {window: sum(closes[index-window+1:index+1])/window for window in (5,10,20,60)}
            prior_volume = sum(volumes[index-5:index])/5
            def rounded(value, places):
                try:
                    result = value.quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP)
                except InvalidOperation:
                    raise CalculationError("INDICATOR_NUMERIC_OVERFLOW") from None
                if abs(result) >= Decimal(10) ** (18 - places):
                    raise CalculationError("INDICATOR_NUMERIC_OVERFLOW")
                return result
            rows.append(IndicatorRow(
                trade_date=bar.trade_date,
                ma5=rounded(moving[5],6), ma10=rounded(moving[10],6),
                ma20=rounded(moving[20],6), ma60=rounded(moving[60],6),
                dif=rounded(dif,8), dea=rounded(dea,8), macd_hist=rounded(2*(dif-dea),8),
                volume_ratio=None if prior_volume == 0 else rounded(bar.volume/prior_volume,6)))
    return CalculationResponse(symbol=request.symbol, adjustment=request.adjustment,
                               history_size=len(bars), indicators=rows)
