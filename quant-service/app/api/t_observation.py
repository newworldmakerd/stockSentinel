"""Completed five-minute data -> Wilder RSI(14), no business database or orders."""
from datetime import datetime, timedelta, time
from decimal import Decimal
from threading import Lock
from collections import OrderedDict
import os
import logging
import time as elapsed_time
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from app.providers.rsi_history import get_rsi_provider, RsiHistoryError
from app.providers.minute_failure import failure_code
from app.api.intraday import minute_clock, ZONE

router = APIRouter(prefix="/internal/indicators")
_cache = OrderedDict()
_gate = Lock()
_failed = OrderedDict()
_circuits = OrderedDict()
_elapsed = elapsed_time.monotonic
logger = logging.getLogger(__name__)


class RsiSnapshot(BaseModel):
    symbol: str
    source: str = "AKSHARE_EASTMONEY_5M"
    adjustment: str = "QFQ"
    period: int = 14
    observed_at: datetime
    five_at: datetime
    fifteen_at: datetime
    rsi5: Decimal
    rsi15: Decimal


def wilder(closes, period=14):
    if len(closes) < period + 1:
        raise ValueError("RSI_HISTORY_INSUFFICIENT")
    changes = [b-a for a, b in zip(closes, closes[1:])]
    gain = sum(max(d, Decimal(0)) for d in changes[:period])/period
    loss = sum(max(-d, Decimal(0)) for d in changes[:period])/period
    for d in changes[period:]:
        gain = (gain*(period-1)+max(d, Decimal(0)))/period
        loss = (loss*(period-1)+max(-d, Decimal(0)))/period
    if gain == loss == 0:
        return Decimal(50)
    return Decimal(100) if loss == 0 else Decimal(100)-Decimal(100)/(1+gain/loss)


def session_index(stamp):
    t = stamp.time().replace(tzinfo=None)
    if time(9, 35) <= t <= time(11, 30):
        return 0, (t.hour*60+t.minute-570)//5
    if time(13, 5) <= t <= time(15):
        return 1, (t.hour*60+t.minute-780)//5
    raise ValueError("RSI_INVALID_SESSION")


def calculate_rsi(symbol, frame, now, *, source="AKSHARE_EASTMONEY_5M"):
    now = now.astimezone(ZONE)
    if frame is None or frame.empty or len(frame) > 6000:
        raise ValueError("RSI_HISTORY_INSUFFICIENT")
    rows = []
    previous = None
    for row in frame.to_dict("records"):
        raw = row["时间"]
        stamp = (raw.to_pydatetime() if hasattr(raw, "to_pydatetime") else datetime.fromisoformat(str(raw)))
        stamp = stamp.replace(tzinfo=ZONE) if stamp.tzinfo is None else stamp.astimezone(ZONE)
        if stamp.second or stamp.microsecond or stamp.minute % 5 or (previous is not None and stamp <= previous):
            raise ValueError("RSI_INVALID_TIME")
        segment, index = session_index(stamp)
        previous = stamp
        if stamp >= now:  # endpoint labels bars by END time, never use unfinished bars
            if stamp-now > timedelta(minutes=5):
                raise ValueError("RSI_FUTURE_HISTORY")
            continue
        close = Decimal(str(row["收盘"]))
        if not close.is_finite() or close <= 0:
            raise ValueError("RSI_INVALID_PRICE")
        if rows:
            old, _, old_segment, old_index = rows[-1]
            adjacent = (stamp.date() == old.date() and
                        ((segment == old_segment and index == old_index+1) or
                         (old_segment == 0 and old_index == 24 and segment == 1 and index == 1)))
            overnight = stamp.date() > old.date() and old_segment == 1 and old_index == 24 and segment == 0 and index == 1
            if not adjacent and not overnight:
                rows = []  # restart warmup after a missing scheduled bar; never fill a gap
        rows.append((stamp, close, segment, index))
    five = [r[1] for r in rows]
    fifteen = []
    fifteen_at = None
    for i in range(2, len(rows)):
        a, b, c = rows[i-2:i+1]
        if (c[3] % 3 == 0 and a[0].date() == c[0].date() and a[2] == b[2] == c[2]
                and b[3] == a[3]+1 and c[3] == b[3]+1):
            fifteen.append(c[1]); fifteen_at = c[0]
    if not rows or rows[-1][0].date() != now.date() or now-rows[-1][0] > timedelta(minutes=6):
        raise ValueError("RSI_STALE_HISTORY")
    if fifteen_at is None or fifteen_at.date() != now.date() or now-fifteen_at > timedelta(minutes=16):
        raise ValueError("RSI_STALE_HISTORY")
    return RsiSnapshot(symbol=symbol, source=source, observed_at=now, five_at=rows[-1][0], fifteen_at=fifteen_at,
                       rsi5=wilder(five), rsi15=wilder(fifteen))


@router.get("/t-rsi", response_model=RsiSnapshot)
def t_rsi(symbol: str = Query(pattern=r"^[0-9]{6}$"), provider=Depends(get_rsi_provider), now: datetime=Depends(minute_clock)):
    # One historical request per symbol per completed-five-minute bucket, bounded cache.
    now = now.astimezone(ZONE)
    completed = now-timedelta(microseconds=1)
    bucket = completed.replace(minute=completed.minute//5*5, second=0, microsecond=0)
    key = (symbol, bucket)
    if not _gate.acquire(timeout=0.1):
        raise HTTPException(503, detail="RSI_COLLECTOR_BUSY", headers={"Retry-After": "1"})
    try:
        if key in _cache:
            return _cache[key]
        tick = _elapsed()
        if symbol in _failed and _failed[symbol][0] > tick:
            _, status, primary, backup = _failed[symbol]
            raise HTTPException(status, detail="RSI_SOURCE_BACKOFF", headers={"X-RSI-Primary-Error": primary, "X-RSI-Backup-Error": backup, "Retry-After": "30"})
        started = tick
        budget = max(5, min(35, int(os.getenv("MARKET_RSI_BUDGET_SECONDS", "30"))))
        deadline = tick+budget
        errors = {"primary": "NOT_ATTEMPTED", "backup": "DISABLED"}
        enabled = os.getenv("MARKET_RSI_SINA_BACKUP_ENABLED", "false").lower() == "true"
        sources = [("primary", "EASTMONEY_5M")]+([("backup", "SINA_QFQ_5M")] if enabled else [])
        for name, source in sources:
            circuit_key = (symbol, name)
            streak, retry_at = _circuits.get(circuit_key, (0, 0))
            if retry_at > _elapsed():
                errors[name] = "CIRCUIT_OPEN"; continue
            source_started = _elapsed()
            try:
                if _elapsed() >= deadline:
                    raise RsiHistoryError("BUDGET_EXHAUSTED")
                frame = getattr(provider, name)(symbol, (now-timedelta(days=30)).date().isoformat(), now.date().isoformat(), deadline)
                observed = now+timedelta(seconds=_elapsed()-started)
                result = calculate_rsi(symbol, frame, observed, source=source)
                if _elapsed() > deadline:
                    raise RsiHistoryError("BUDGET_EXHAUSTED")
                _circuits[circuit_key] = (0, 0)
                logger.info("t_rsi_source_result symbol=%s source=%s outcome=SUCCESS elapsed_ms=%d", symbol, source, max(0, int((_elapsed()-source_started)*1000)))
                _cache[key] = result; _failed.pop(symbol, None)
                while len(_cache) > 256:
                    _cache.popitem(last=False)
                while len(_circuits) > 512:
                    _circuits.popitem(last=False)
                return result
            except Exception as error:
                code = _code(error); errors[name] = code
                logger.warning("t_rsi_source_result symbol=%s source=%s outcome=FAILED code=%s elapsed_ms=%d", symbol, source, code, max(0, int((_elapsed()-source_started)*1000)))
                streak += 1; _circuits[circuit_key] = (streak, _elapsed()+120 if streak >= 3 else 0)
        status = 422 if errors["primary"] in {"RSI_HISTORY_INSUFFICIENT", "RSI_INVALID_PRICE", "RSI_INVALID_TIME", "RSI_INVALID_SESSION", "RSI_STALE_HISTORY", "RSI_FUTURE_HISTORY"} else 502
        _failed[symbol] = (_elapsed()+30, status, errors["primary"], errors["backup"])
        while len(_failed) > 256:
            _failed.popitem(last=False)
        while len(_circuits) > 512:
            _circuits.popitem(last=False)
        raise HTTPException(status, detail="RSI_SOURCE_UNAVAILABLE", headers={"X-RSI-Primary-Error": errors["primary"], "X-RSI-Backup-Error": errors["backup"]}) from None
    finally:
        _gate.release()


def _code(error):
    if isinstance(error, RsiHistoryError):
        return error.code
    if isinstance(error, ValueError) and str(error) in {"RSI_HISTORY_INSUFFICIENT", "RSI_INVALID_PRICE", "RSI_INVALID_TIME", "RSI_INVALID_SESSION", "RSI_STALE_HISTORY", "RSI_FUTURE_HISTORY"}:
        return str(error)
    return failure_code(error)
