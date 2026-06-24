FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the servicing package; /app on sys.path for tool + data imports
COPY . .

EXPOSE 8082

CMD ["python", "server.py"]
