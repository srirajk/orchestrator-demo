FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the insurance package; the working dir (/app) is on sys.path
# so `from shared.canned_data import ...` and `from policy_details.handler import ...`
# resolve correctly.
COPY . .

EXPOSE 8087

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8087"]
