FROM python:3.9-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

COPY server.py svcmodel.pkl dtreemodel.pkl ./

EXPOSE 8080
CMD ["sh", "-c", "gunicorn --workers 2 --threads 2 --timeout 120 --bind 0.0.0.0:${PORT:-8080} server:app"]
