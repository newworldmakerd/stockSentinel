"""Bounded, credential-free diagnostics for minute market requests."""
import requests


DATA_CODES = frozenset({"DATA_TIME_PARSE", "DATA_TIME_DATE", "DATA_TIME_PRECISION", "DATA_TIME_SESSION",
    "DATA_TIME_FUTURE", "DATA_TIME_ORDER", "DATA_PRICE", "DATA_OHLC", "DATA_VOLUME", "DATA_AMOUNT",
    "DATA_NUMBER", "DATA_FIELD_MISSING", "DATA_ROWS", "DATA_EMPTY", "DATA_STALE", "DATA_IDENTITY", "DATA_SHAPE", "DATA_JSON"})


class MinuteDataError(ValueError):
    """Fixed diagnosis only; never retain an upstream value or exception message."""
    def __init__(self, code, *, row=None, field=""):
        if code not in DATA_CODES:
            raise ValueError("Unknown minute diagnosis")
        super().__init__(code)
        self.code = code
        self.row = row
        self.field = field if field in {"time", "open", "high", "low", "close", "volume", "amount", "identity", "rows"} else ""


def error_location(error):
    return (error.row if error.row is not None else -1, error.field) if isinstance(error, MinuteDataError) else (-1, "")


def failure_code(error):
    # Never use exception text: HTTP clients may include URLs or proxy credentials.
    if isinstance(error, MinuteDataError):
        return error.code
    if isinstance(error, requests.Timeout):
        return "TIMEOUT"
    if isinstance(error, requests.ConnectionError):
        return "CONNECTION"
    if isinstance(error, requests.HTTPError):
        status = error.response.status_code if error.response is not None else 0
        return f"HTTP_{status}" if status in (400, 401, 403, 404, 408, 429, 500, 502, 503, 504) else "HTTP_ERROR"
    if isinstance(error, requests.exceptions.JSONDecodeError):
        return "DATA_JSON"
    if isinstance(error, KeyError):
        return "DATA_FIELD_MISSING"
    if isinstance(error, (ValueError, KeyError, TypeError, ArithmeticError)):
        return "INVALID_DATA"
    if isinstance(error, requests.RequestException):
        return "TRANSPORT"
    return "INTERNAL"


def retryable(error):
    return failure_code(error) in {"TIMEOUT", "CONNECTION", "HTTP_502", "HTTP_503", "HTTP_504"}
