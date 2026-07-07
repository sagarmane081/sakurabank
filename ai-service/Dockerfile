# ---- build stage ----
FROM python:3.12-slim AS build
WORKDIR /build

COPY pyproject.toml .
COPY app ./app
RUN pip install --no-cache-dir --prefix=/install .

# ---- runtime stage ----
FROM python:3.12-slim
RUN addgroup --system sakura && adduser --system --ingroup sakura sakura

COPY --from=build /install /usr/local
COPY app /app/app

USER sakura
WORKDIR /app

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
