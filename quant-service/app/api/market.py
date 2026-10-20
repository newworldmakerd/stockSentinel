from datetime import date
from functools import lru_cache
from typing import Annotated
import os

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from app.providers.base import Adjustment, DailyBar, StockMetadata
from app.providers.akshare_provider import AkshareProvider

router = APIRouter(prefix="/internal")


@lru_cache(maxsize=1)
def _provider():
    return AkshareProvider()


def get_provider():
    if os.getenv("MARKET_PROVIDER_ENABLED", "false").lower() != "true":
        raise HTTPException(status_code=503, detail="Market provider disabled")
    return _provider()


class DailyResponse(BaseModel):
    symbol: str
    start: date
    end: date
    adjustment: Adjustment
    unit: str = "SHARE"
    amount_unit: str = "CNY"
    bars: list[DailyBar]


@router.get("/stocks", response_model=list[StockMetadata])
def stocks(provider=Depends(get_provider)):
    try:
        return provider.stocks()
    except Exception:
        raise HTTPException(status_code=502, detail="Stock provider unavailable or invalid") from None


@router.get("/market/daily", response_model=DailyResponse)
def daily(symbol: Annotated[str, Query(pattern=r"^[0-9]{6}$")], start: date, end: date,
          adjustment: Adjustment = Adjustment.NONE, provider=Depends(get_provider)):
    if start > end or (end - start).days > 3660:
        raise HTTPException(status_code=422, detail="Invalid date range (maximum 3660 days)")
    try:
        bars = provider.daily_bars(symbol, start, end, adjustment)
        return DailyResponse(symbol=symbol, start=start, end=end, adjustment=adjustment, bars=bars)
    except Exception:
        raise HTTPException(status_code=502, detail="Daily provider unavailable or invalid") from None