FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the wealth-market-research package; the working dir (/app) is on sys.path
# so `from shared.canned_data import ...` and `from market_research.handler import ...`
# resolve correctly.
COPY . .

EXPOSE 8089

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8089"]
