FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py .

EXPOSE 8090
VOLUME ["/cassettes"]

# Single worker on purpose: the cassette is an in-process dict, and the stub must never be the
# bottleneck under load. Its work is a dict lookup plus an asyncio.sleep — the event loop handles
# thousands of concurrent requests. (Contrast: the servicing agent serialized because a *sync*
# handler blocked this same loop. See mock-agents/servicing/*/tool.py.)
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8090", "--log-level", "warning"]
