FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir fastapi uvicorn[standard]

COPY placeholder.py .

EXPOSE 8090

CMD ["uvicorn", "placeholder:app", "--host", "0.0.0.0", "--port", "8090"]
