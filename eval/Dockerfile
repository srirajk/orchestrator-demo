# Continuous-eval sidecar — runs eval/langfuse_continuous.py on a loop.
# Built from the eval/ directory so the eval scripts + requirements travel together.
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
CMD ["python", "-u", "continuous_loop.py"]
