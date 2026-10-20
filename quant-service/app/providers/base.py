from datetime import date
from decimal import Decimal
from enum import Enum
from typing import Protocol
from pydantic import BaseModel, Field, model_validator


class Adjustment(str, Enum):
    NONE = "NONE"
    QFQ = "QFQ"
    HFQ = "HFQ"


class DailyBar(BaseModel):
    symbol: str = Field(pattern=r"^[0-9]{6}$")
    trade_date: date
    open_price: Decimal = Field(gt=0)
    high_price: Decimal = Field(gt=0)
    low_price: Decimal = Field(gt=0)
    close_price: Decimal = Field(gt=0)
    volume: Decimal = Field(ge=0)  # shares
    amount: Decimal | None = Field(default=None, ge=0)  # CNY
    turnover_rate: Decimal | None = Field(default=None, ge=0)  # fraction: 0.01 = 1%
    adjustment: Adjustment
    source: str = Field(min_length=1)
    is_final: bool

    @model_validator(mode="after")
    def check_ohlc(self):
        if self.low_price > min(self.open_price, self.close_price) or self.high_price < max(self.open_price, self.close_price) or self.low_price > self.high_price:
            raise ValueError("Inconsistent OHLC prices")
        return self


class MarketProvider(Protocol):
    def stocks(self) -> list["StockMetadata"]: ...

    def daily_bars(self, symbol: str, start: date, end: date,
                   adjustment: Adjustment) -> list[DailyBar]: ...

class StockMetadata(BaseModel):
    symbol: str = Field(pattern=r"^[0-9]{6}$")
    name: str = Field(min_length=1, max_length=100, pattern=r".*\S.*")
    exchange: str = Field(pattern=r"^(SH|SZ|BJ)$")
