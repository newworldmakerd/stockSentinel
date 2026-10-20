from fastapi import FastAPI
from app.api.market import router
from app.api.indicator import router as indicator_router
from app.api.intraday import router as intraday_router
from app.api.overview import router as overview_router

app = FastAPI(title="StockSentinel Quant", version="0.1.0")
app.include_router(router)
app.include_router(indicator_router)
app.include_router(intraday_router)
app.include_router(overview_router)


@app.get("/health")
def health():
    return {"status": "UP", "service": "stocksentinel-quant"}
