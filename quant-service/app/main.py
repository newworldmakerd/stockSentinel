from fastapi import FastAPI
from app.api.market import router
from app.api.indicator import router as indicator_router
from app.api.intraday import router as intraday_router
from app.api.overview import router as overview_router
from app.api.t_observation import router as t_observation_router
from app.api.trend import router as trend_router
from app.api.chips import router as chip_router

app = FastAPI(title="StockSentinel Quant", version="0.1.0")
app.include_router(router)
app.include_router(indicator_router)
app.include_router(intraday_router)
app.include_router(overview_router)
app.include_router(t_observation_router)
app.include_router(trend_router)
app.include_router(chip_router)


@app.get("/health")
def health():
    return {"status": "UP", "service": "stocksentinel-quant"}
