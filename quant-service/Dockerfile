FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml requirements.lock ./
COPY app app
RUN pip install --no-cache-dir -r requirements.lock && pip install --no-cache-dir --no-deps .
USER 10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]