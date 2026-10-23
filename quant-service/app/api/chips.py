from datetime import date, datetime, time
from decimal import Decimal
import os
from typing import Annotated
from fastapi import APIRouter, HTTPException, Query
from fastapi.encoders import jsonable_encoder
from app.providers import chips

router = APIRouter(prefix='/internal/chips')


@router.get('/snapshot')
def snapshot(symbol: Annotated[str, Query(pattern=r'^[0-9]{6}$')], as_of: date,
             current_day_confirmed: bool = False):
    if os.getenv('MARKET_CHIP_ENABLED', 'false').lower() != 'true':
        raise HTTPException(503, detail='CHIP_DISABLED')
    now = datetime.now(chips.ZONE)
    if as_of > now.date() or (as_of == now.date() and (not current_day_confirmed or now.time() < time(15))):
        raise HTTPException(422, detail='CHIP_CLOSE_CONFIRMATION_REQUIRED')
    try:
        return jsonable_encoder(chips.fetch(symbol, as_of), custom_encoder={Decimal: str})
    except chips.ChipError as e:
        raise HTTPException(502, detail=e.code) from None
