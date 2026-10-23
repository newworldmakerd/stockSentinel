"""Minute normalization and metrics only. Java owns calendar, rules and all writes."""
from datetime import datetime, timedelta, time
from decimal import Decimal, InvalidOperation
import os
import logging
import time as elapsed_time
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field, model_validator, ValidationError
from app.api.market import get_provider
from app.providers.tencent_provider import get_tencent_provider
from app.providers.minute_failure import failure_code, error_location, MinuteDataError

ZONE = ZoneInfo("Asia/Shanghai")
router = APIRouter(prefix="/internal/market")
logger = logging.getLogger(__name__)


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
    rows = frame.to_dict("records")
    fields = {"开盘": "open", "最高": "high", "最低": "low", "收盘": "close", "成交量": "volume", "成交额": "amount"}
    def number(row, key, index):
        field = fields[key]
        if key not in row:
            raise MinuteDataError("DATA_FIELD_MISSING", row=index, field=field)
        try:
            value = Decimal(str(row[key]))
            if not value.is_finite():
                raise InvalidOperation
            return value
        except (InvalidOperation, ValueError, TypeError):
            raise MinuteDataError("DATA_NUMBER", row=index, field=field) from None
    for index, row in enumerate(rows):
        if "时间" not in row:
            raise MinuteDataError("DATA_FIELD_MISSING", row=index, field="time")
        try:
            stamp = datetime.fromisoformat(str(row["时间"]))
        except (ValueError, TypeError):
            raise MinuteDataError("DATA_TIME_PARSE", row=index, field="time") from None
        stamp = stamp.replace(tzinfo=ZONE) if stamp.tzinfo is None else stamp.astimezone(ZONE)
        if stamp.date() != now.date():
            raise MinuteDataError("DATA_TIME_DATE", row=index, field="time")
        if stamp.second or stamp.microsecond:
            raise MinuteDataError("DATA_TIME_PRECISION", row=index, field="time")
        if session(stamp) is None:
            raise MinuteDataError("DATA_TIME_SESSION", row=index, field="time")
        if previous is not None and stamp <= previous:
            raise MinuteDataError("DATA_TIME_ORDER", row=index, field="time")
        if stamp > current_minute:
            # Both observed vendor feeds can label a forming terminal row with the
            # next minute. Exclude this row; never shift timestamps or use its OHLCV.
            if (source in {"AKSHARE_EASTMONEY_1M", "TENCENT_1M"}
                    and index == len(rows)-1 and stamp == current_minute+timedelta(minutes=1)):
                logger.info("minute_incomplete_tail symbol=%s source=%s row=%s", symbol, source, index)
                continue
            raise MinuteDataError("DATA_TIME_FUTURE", row=index, field="time")
        previous = stamp
        if stamp == current_minute:
            continue  # Conservative: never use a possibly still-updating minute.
        if "成交额" not in row:
            raise MinuteDataError("DATA_FIELD_MISSING", row=index, field="amount")
        try:
            bar = MinuteBar(bar_at=stamp, open_price=number(row,"开盘",index), high_price=number(row,"最高",index),
                low_price=number(row,"最低",index), close_price=number(row,"收盘",index),
                volume=number(row,"成交量",index)*100,
                amount=None if source == "TENCENT_1M" and row["成交额"] is None else number(row,"成交额",index))
        except ValidationError as invalid:
            location = invalid.errors(include_input=False, include_context=False, include_url=False)[0]["loc"]
            field = {"open_price":"open","high_price":"high","low_price":"low","close_price":"close","volume":"volume","amount":"amount"}.get(location[0] if location else "", "")
            code = "DATA_PRICE" if field in {"open","high","low","close"} else "DATA_VOLUME" if field=="volume" else "DATA_AMOUNT" if field=="amount" else "DATA_OHLC"
            raise MinuteDataError(code, row=index, field=field) from None
        bars.append(bar)
    if len(bars) > 242:
        raise MinuteDataError("DATA_ROWS", field="rows")
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
        observed = now + timedelta(seconds=elapsed_time.monotonic()-started)
        result = normalize_minutes(symbol, frame, observed)
        if fallback_enabled and (not result.bars or result.bars[-1].bar_at < observed-timedelta(seconds=120)):
            raise MinuteDataError("DATA_EMPTY" if not result.bars else "DATA_STALE", field="time")
        return result
    except Exception as primary_error:
        primary_code = failure_code(primary_error)
        primary_row, primary_field = error_location(primary_error)
        backup_code = "DISABLED"
        backup_row, backup_field = -1, ""
        if fallback_enabled:
            try:
                frame = backup.minutes(symbol, now.astimezone(ZONE).date(), retry_deadline=started+40)
                observed = now + timedelta(seconds=elapsed_time.monotonic()-started)
                result = normalize_minutes(symbol, frame, observed, source="TENCENT_1M")
                logger.warning("minute_fallback symbol=%s primary=%s primary_row=%s primary_field=%s backup=SUCCESS", symbol, primary_code, primary_row, primary_field)
                return result
            except Exception as backup_error:
                backup_code = failure_code(backup_error)
                backup_row, backup_field = error_location(backup_error)
        logger.warning("minute_unavailable symbol=%s primary=%s primary_row=%s primary_field=%s backup=%s backup_row=%s backup_field=%s", symbol, primary_code, primary_row, primary_field, backup_code, backup_row, backup_field)
        raise HTTPException(status_code=502, detail="Minute provider unavailable or invalid",
            headers={"X-Minute-Primary-Error": primary_code, "X-Minute-Backup-Error": backup_code}) from None
