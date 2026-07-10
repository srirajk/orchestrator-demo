# Audit Record — capture spec (WORM, adapter-based)

> **Status:** design of record. No code yet.
> **Scope:** define the immutable audit record and the write path. The analytical layer
> (Iceberg / DuckDB / Spark / ClickHouse / any dashboard) is explicitly **out of scope** and comes
> later, reading these same objects. Getting the *envelope* right is the whole job here, because the
> objects are immutable — a bad schema is permanent.

> ## ⛔ Invariant 0 — NEVER on the request path
>
> The audit write is **never synchronous to the request** and **never blocks, delays, or fails a
> user's turn.** This is the same non-negotiable rule as trace persistence: buffer the events per
> request, hand off at the terminal event to a **bounded async queue drained by a dedicated pool**,
> and return to the user immediately. The object write (network I/O to S3/ADLS) happens entirely off
> the request thread. If the sink is slow, down, or erroring, the user's request is unaffected — the
> failure is logged, metered, and alerted, never propagated. An audit trail that adds latency to the
> thing it audits is a broken design. See `[[feedback-no-sync-telemetry]]`.

## 1. What this is, and what it is not

Audit/replay is an **analytical, write-now / query-later** capability, not a real-time one. The
decision trail is read *rarely* — during an investigation or a regulator's request — never on the
request hot path. So the design is: **on every request, write one immutable record to object
storage; build the query/analytics layer later, as a pure add-on over those objects.**

- **Real-time?** No. The only hot-path cost is one asynchronous object write per request.
- **Emergency?** No — *until* real regulated production traffic flows. At that point the sink must
  already be live, because anything not written durably is gone after the live trace's 24h Redis TTL.
  Demo/dev has no exposure. Build the capability before prod; leave it; add analytics when needed.
- **"Replay" means** reconstruct the decision (what happened and why), read-only. Re-executing a past
  request against the current gateway is a separate, larger effort and is not in this spec.

## 2. Non-goals (deferred, and why each is safe to defer)

| Deferred | Why it can wait |
|---|---|
| Postgres / any query index | Querying isn't needed now. A query index would be an operational store to build, dual-write, and keep in sync for a capability nobody is using yet. The objects are the record; index them later. |
| Iceberg table + DuckDB/Spark/ClickHouse | Pure schema-on-read over the objects. No migration — a later ETL/compaction job rolls the per-request JSON into partitioned Parquet/Iceberg. |
| Analytical dashboard | Reads the analytics layer, which reads the objects. Two layers removed from this spec. |
| Replay-by-re-execution | Needs manifest/version snapshots and deterministic LLM replay. Different feature. |

## 3. The record envelope (the expensive-to-change decision)

One object per request. Top-level fields are **promoted dimensions** — the things a query engine
filters on without unnesting — and `payload` carries the full end-to-end trace verbatim.

```jsonc
{
  "schema_version": "1",                     // envelope version; bump only on breaking change
  "transaction_id": "req_01J...",            // = request_id / correlation_id. Lookup + partition key.
  "conversation_id": "conv_6a48...",         // groups a multi-turn session
  "occurred_at": "2026-07-10T13:45:19.317Z", // ISO-8601, UTC, request completion time
  "principal": {
    "user_id": "rm_jane",
    "jwt_sub": "rm_jane",
    "tenant_id": "meridian",
    "segments": ["wealth-private-banking"]
  },
  "outcome": "ANSWERED",                      // ANSWERED | FAILED | CLARIFY  (promoted for filtering)
  "counts": {                                 // promoted so "all requests with a denial" is a scan, not an unnest
    "agents_ok": 5,
    "agents_failed": 0,
    "entitlement_denials": 1
  },
  "gateway_version": "0.1.0-SNAPSHOT+git.96c7c98", // which code produced this — required for any future replay
  "payload": {
    "events": [ /* the full ordered TraceEvent list for this request, verbatim */ ]
  },
  "content_sha256": "…"                       // sha256 of the canonical-JSON `payload`, for tamper-evidence
}
```

Notes:
- `payload.events` is exactly what already flows to the live glass box (`TraceEvent` +
  `EntitlementCheckData`, `AgentsResolvedData`, `AgentCompleteData`, `RequestComplete`, …). We are
  **persisting a copy of an existing structure**, not inventing a new one.
- The promoted dimensions (`principal`, `outcome`, `counts`) are derived from those events at write
  time. Promoting them is what lets the later Iceberg table be queried by user / outcome / denial
  without parsing every payload.
- `content_sha256` covers `payload`. Optionally chain records (`prev_sha256`) per `conversation_id`
  or per partition for a tamper-evident sequence; deferred, but the field is reserved.
- Format is **canonical JSON** (sorted keys, no insignificant whitespace) so the hash is stable and
  reproducible. One object per request. The analytics layer compacts many objects → Parquet later;
  the immutable record stays JSON.

## 4. Partition layout

Hive/Iceberg-friendly from day one, so the analytics layer needs no reshuffle:

```
audit/dt=2026-07-10/tenant=meridian/{transaction_id}.json
```

- `dt` = UTC date of `occurred_at` — the natural time-partition for retention and analytics.
- `tenant` — one deployment may host one org today, but partitioning by tenant now costs nothing and
  isolates later.
- Object key is deterministic from the record, so a re-write of the same request is idempotent
  (and, under Object Lock, refused — which is correct).

## 5. The write port and its adapters (storage-agnostic — no vendor in the domain)

The gateway domain writes to a **role-named port**, never to a vendor SDK. The vendor lives only in
the adapter, chosen by config. This is the same ports-and-adapters discipline already used for
`ProtocolAdapter`, `EmbeddingClient`, and `TraceStorageAdapter`.

```
registry/… no — this lives under the telemetry/audit feature:

infrastructure/audit/
  AuditRecord.java              // the envelope (record type)
  AuditRecordSink.java          // PORT:  void write(AuditRecord) — the only thing the domain sees
  ObjectStoreAuditSink.java     // ADAPTER: S3-family (AWS SDK v2). Covers MinIO, AWS S3, R2,
                                //          GCS S3-interop — MinIO is just an endpoint + path-style.
  AdlsGen2AuditSink.java        // ADAPTER: Azure Data Lake Storage Gen2 (later; seam defined now)
  AuditRecordAssembler.java     // builds AuditRecord from the buffered TraceEvents on RequestComplete
```

- **`AuditRecordSink` is the port.** Its name says *what it does*, not *where it writes*. No type in
  the codebase is called `Minio…` — MinIO is a configured endpoint of the S3 adapter, nothing more.
- **One S3-family adapter covers most of the world.** MinIO, AWS S3, Cloudflare R2, and GCS (S3
  interop) all speak the S3 API; they differ only by endpoint, region, path-style, and credentials —
  all config. That is the "we use MinIO but don't call it loud" requirement, satisfied structurally.
- **ADLS Gen2 is a genuinely different API** (not S3-compatible), so it is a second adapter shape.
  Define the port now so adding it later is additive; do not build it until an Azure target is real.
- The adapter is selected by `conduit.audit.store.type`. Unknown/misconfigured type → fail fast at
  startup, never silently drop audit writes.

## 6. WORM / immutability

- **S3 family:** enable **S3 Object Lock in compliance mode** with a per-object retention period.
  Compliance mode means the object cannot be deleted or overwritten before retention expires — not
  even by the root account. This is what SEC 17a-4(f) / MiFID II "the firm cannot alter the record"
  requires. MinIO supports Object Lock, so the demo uses *real* WORM, not a mock.
- **ADLS Gen2:** immutable blob storage with a time-based retention policy — the Azure equivalent.
- Retention period is config (`conduit.audit.retention-days`, default e.g. 2555 ≈ 7y). The adapter
  sets it per object at write time.
- Idempotency: the deterministic key + Object Lock means a duplicate write of the same request is
  refused, which is the correct behaviour for an immutable record.

## 7. The write hook

`TraceEventPublisher` already buffers a request's events and flushes on the terminal
`request_complete` event (the same point `AsyncTraceWriter` persists to Redis). That flush is the
single hook: on terminal event, the events are **enqueued** for audit — the request thread does no
more than a non-blocking offer to a bounded queue and returns.

- **Off the request path — see Invariant 0.** A dedicated drain (own thread/VT pool, bounded
  `ArrayBlockingQueue`, mirror of `AsyncTraceWriter`) pulls batches, runs `AuditRecordAssembler`, and
  calls `AuditRecordSink.write(...)`. The assembly (hashing, canonical JSON) *and* the object I/O both
  happen on the drain, never on the request thread. The request thread's only audit cost is the
  enqueue.
- **Backpressure, not blocking.** If the queue is full (sink slow/down), drop-oldest or spill to a
  local spool — never block the producer. Emit `conduit.audit.queue.depth` / `.dropped` so a backed-up
  sink is visible. A dropped audit record is a monitored incident; a blocked user request is not
  acceptable.
- **Failure posture:** an audit-write failure is logged, metered (`conduit.audit.write.failed`), and
  — because a bank cannot silently lose audit records — alerted. It never fails the user's request.
  If *guaranteed* capture is later required, back the queue with a durable WAL spool before the sink;
  noted, deferred.

## 8. Config (generic keys, no vendor names)

```yaml
conduit:
  audit:
    enabled: ${CONDUIT_AUDIT_ENABLED:false}          # off until wired + verified
    store:
      type: ${CONDUIT_AUDIT_STORE_TYPE:s3}           # s3 | adls-gen2
      bucket: ${CONDUIT_AUDIT_BUCKET:conduit-audit}
      prefix: ${CONDUIT_AUDIT_PREFIX:audit}
      # S3 family (MinIO / AWS S3 / R2 / GCS-interop) — endpoint blank = real AWS
      endpoint: ${CONDUIT_AUDIT_S3_ENDPOINT:}
      region: ${CONDUIT_AUDIT_S3_REGION:us-east-1}
      path-style: ${CONDUIT_AUDIT_S3_PATH_STYLE:true} # true for MinIO
    retention-days: ${CONDUIT_AUDIT_RETENTION_DAYS:2555}
    object-lock-mode: ${CONDUIT_AUDIT_OBJECT_LOCK_MODE:COMPLIANCE} # COMPLIANCE | GOVERNANCE | none
```

Credentials come from the environment / instance role, never committed.

## 9. Build order when this is greenlit

1. `AuditRecord` + `AuditRecordAssembler` — pure, unit-testable against a fixed event list.
2. `AuditRecordSink` port + `ObjectStoreAuditSink` (S3 family) — integration-test against the MinIO
   we already run, with Object Lock enabled; assert the object exists, matches the hash, and a second
   write of the same key is refused.
3. Wire the terminal-event hook + async write; assert one object per request end-to-end on the live
   stack.
4. Leave it. Iceberg/ClickHouse/dashboard is a separate effort over these objects.

Everything above item 4 is a few days of well-bounded work with no dependency on the analytical
layer — which is the point of getting the envelope right now.
