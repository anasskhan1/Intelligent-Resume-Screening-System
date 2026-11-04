# =============================================================================
# Intelligent Resume Screening System - Python FastAPI AI Service
# Production Dockerfile
# =============================================================================

FROM python:3.11-slim
WORKDIR /app

# Prevent Python from writing .pyc files and enable unbuffered output
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=10000

# Install curl for container health check
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create unprivileged system group and user
RUN groupadd -r appgroup && useradd -r -g appgroup -s /sbin/nologin -d /app appuser

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy service application code
COPY models/ ./models/
COPY services/ ./services/
COPY main.py .

# Create temporary upload working directory with appuser ownership
RUN mkdir -p /app/temp && chown -R appuser:appgroup /app

USER appuser

EXPOSE 10000

# Health check using the /health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:${PORT:-10000}/health || exit 1

CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-10000}"]

