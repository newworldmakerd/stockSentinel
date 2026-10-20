from fastapi import APIRouter, HTTPException
from app.indicators.calculate import CalculationRequest, CalculationResponse, CalculationError, calculate

router = APIRouter(prefix="/internal/indicators")


@router.post("/calculate", response_model=CalculationResponse)
def calculate_indicators(request: CalculationRequest):
    try:
        return calculate(request)
    except CalculationError as error:
        raise HTTPException(status_code=422, detail={"code": error.code, **error.details}) from None