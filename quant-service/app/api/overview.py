"""Batch quote/index snapshots only. Java owns breadth, screening decisions and storage."""
from datetime import datetime
from decimal import Decimal
import re
import time
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from app.api.market import get_provider
import requests

router = APIRouter(prefix="/internal/market")


class OverviewRequest(BaseModel):
    symbols: list[str] = Field(min_length=1, max_length=20000)


def parse_quotes(text, expected):
    result = []
    for key, raw in re.findall(r'v_([a-z]{2}[0-9]{6})="([^"\r\n]*)";', text):
        if key not in expected:
            raise ValueError("Unexpected quote identity")
        f = raw.split("~")
        if len(f) < 35 or f[2] != key[2:]:
            raise ValueError("Invalid quote identity")
        stamp = datetime.strptime(f[30], "%Y%m%d%H%M%S").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        price, previous, volume = Decimal(f[3]), Decimal(f[4]), Decimal(f[6])*100
        if not all(v.is_finite() for v in (price, previous, volume)) or price <= 0 or previous <= 0 or volume < 0:
            raise ValueError("Invalid quote numbers")
        result.append({"key": key, "symbol": f[2], "name": f[1], "price": str(price),
                       "previous_close": str(previous), "volume": str(volume), "quoted_at": stamp.isoformat()})
    if len({r["key"] for r in result}) != len(result):
        raise ValueError("Duplicate quote identity")
    return result


@router.post("/overview")
def overview(body: OverviewRequest, _=Depends(get_provider)):
    if len(set(body.symbols)) != len(body.symbols) or any(not re.fullmatch(r"(?:sh|sz)[0-9]{6}", s) for s in body.symbols):
        raise HTTPException(status_code=422, detail="Invalid overview symbols")
    keys = list(dict.fromkeys(["sh000001", "sz399001", "sz399006"] + body.symbols))
    result, failed = [], []
    for start in range(0, len(keys), 100):
        batch = keys[start:start+100]
        if start:
            time.sleep(1)
        try:
            response = requests.get("https://qt.gtimg.cn/q="+",".join(batch), timeout=(3, 6))
            response.raise_for_status()
            parsed = parse_quotes(response.content.decode("gbk"), set(batch))
            now = datetime.now(ZoneInfo("Asia/Shanghai"))
            parsed = [q for q in parsed if datetime.fromisoformat(q["quoted_at"]) <= now.replace(microsecond=0)]
            result.extend(parsed)
            found = {q["key"] for q in parsed}
            failed.extend(k for k in batch if k not in found)
        except Exception:
            failed.extend(batch)
    return {"source": "TENCENT_QUOTE", "observed_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
            "quotes": result, "failed_symbols": failed}
