"""Minute normalization and metrics only. Java owns calendar, rules and all writes."""
from datetime import datetime, timedelta, time
from decimal import Decimal
import os
import time as elapsed_time
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field, model_validator
from app.api.market import get_provider
from app.providers.tencent_provider import get_tencent_provider

ZONE = ZoneInfo("Asia/Shanghai")
router = APIRouter(prefix="/internal/market")


def minute_clock():
    return datetime.now(ZONE)


class MinuteBar(BaseModel):
    bar_at: datetime
    open_price: Decimal = Field(gt=0)
    high_price: Decimal = Field(gt=0)
    low_price: Decimal = Field(gt=0)
    close_price: Decimal = Field(gt=0)
    volume: Decimal = Field(ge=0)
    amount: Decimal | None = Field(ge=0)

    @model_validator(mode="after")
    def prices(self):
        if self.bar_at.tzinfo is None or self.low_price > min(self.open_price, self.close_price) or self.high_price < max(self.open_price, self.close_price) or self.low_price > self.high_price:
            raise ValueError("Invalid minute bar")
        return self


class MinuteMetrics(BaseModel):
    five_minute_return_pct: Decimal | None = None
    previous_twenty_high: Decimal | None = None
    previous_twenty_volume_mean: Decimal | None = None


class MinuteSnapshot(BaseModel):
    symbol: str
    source: str = "AKSHARE_EASTMONEY_1M"
    adjustment: str = "NONE"
    unit: str = "SHARE"
    amount_unit: str = "CNY"
    observed_at: datetime
    bars: list[MinuteBar]
    metrics: MinuteMetrics

    @model_validator(mode="after")
    def amount_source(self):
        if self.source != "TENCENT_1M" and any(bar.amount is None for bar in self.bars):
            raise ValueError("Missing minute amount")
        return self


def session(value):
    clock = value.timetz().replace(tzinfo=None)
    if time(9, 30) <= clock <= time(11, 30):
        return "AM"
    if time(13) <= clock <= time(15):
        return "PM"
    return None


def contiguous(bars):
    return bool(bars) and session(bars[0].bar_at) is not None and all(
        session(b.bar_at) == session(bars[0].bar_at) and (i == 0 or b.bar_at-bars[i-1].bar_at == timedelta(minutes=1))
        for i, b in enumerate(bars))


def normalize_minutes(symbol, frame, now, *, source="AKSHARE_EASTMONEY_1M"):
    now = now.astimezone(ZONE)
    current_minute = now.replace(second=0, microsecond=0)
    bars = []
    previous = None
    for row in frame.to_dict("records"):
        stamp = datetime.fromisoformat(str(row["时间"]))
        stamp = stamp.replace(tzinfo=ZONE) if stamp.tzinfo is None else stamp.astimezone(ZONE)
        if stamp.date() != now.date() or stamp.second or stamp.microsecond or session(stamp) is None or stamp > current_minute or (previous is not None and stamp <= previous):
            raise ValueError("Invalid minute sequence")
        previous = stamp
        if stamp == current_minute:
            continue  # Conservative: never use a possibly still-updating minute.
        bars.append(MinuteBar(bar_at=stamp, open_price=Decimal(str(row["开盘"])), high_price=Decimal(str(row["最高"])),
            low_price=Decimal(str(row["最低"])), close_price=Decimal(str(row["收盘"])),
            volume=Decimal(str(row["成交量"]))*100,
            amount=None if source == "TENCENT_1M" and row["成交额"] is None else Decimal(str(row["成交额"]))))
    if len(bars) > 242:
        raise ValueError("Too many minute rows")
    metrics = MinuteMetrics()
    if len(bars) >= 6 and contiguous(bars[-6:]):
        metrics.five_minute_return_pct = (bars[-1].close_price/bars[-6].close_price-1)*100
    if len(bars) >= 21 and contiguous(bars[-21:]):
        metrics.previous_twenty_high = max(b.high_price for b in bars[-21:-1])
        metrics.previous_twenty_volume_mean = sum(b.volume for b in bars[-21:-1])/20
    return MinuteSnapshot(symbol=symbol, source=source, observed_at=now, bars=bars[-21:], metrics=metrics)


@router.get("/minutes", response_model=MinuteSnapshot)
def minutes(symbol: str = Query(pattern=r"^[0-9]{6}$"), provider=Depends(get_provider), now: datetime=Depends(minute_clock),
            backup=Depends(get_tencent_provider)):
    started = elapsed_time.monotonic()
    fallback_enabled = os.getenv("MARKET_TENCENT_BACKUP_ENABLED", "false").lower() == "true"
    try:
        day = now.astimezone(ZONE).date().isoformat()
        frame = provider._call("stock_zh_a_hist_min_em", symbol=symbol, period="1", adjust="",
            start_date=f"{day} 09:30:00", end_date=f"{day} 15:00:00")
        result = normalize_minutes(symbol, frame, now)
        if fallback_enabled and (not result.bars or result.bars[-1].bar_at < now-timedelta(seconds=120)):
            raise ValueError("Primary minutes stale or empty")
        return result
    except Exception:
        if fallback_enabled:
            try:
                frame = backup.minutes(symbol, now.astimezone(ZONE).date())
                observed = now + timedelta(seconds=elapsed_time.monotonic()-started)
                return normalize_minutes(symbol, frame, observed, source="TENCENT_1M")
            except Exception:
                pass
        raise HTTPException(status_code=502, detail="Minute provider unavailable or invalid") from None
