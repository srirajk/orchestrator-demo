package ai.conduit.gateway.infrastructure.audit;

/**
 * Where an {@link AuditRecord} is written. The name says what it does, not where it writes — the
 * vendor lives only in the adapter, chosen by config. Today that is {@link ObjectStoreAuditSink}
 * (S3-family: MinIO, AWS S3, R2, GCS-interop); an ADLS Gen2 adapter is a second shape when an Azure
 * target is real.
 *
 * <p>Called only from the audit drain thread, never the request path.
 */
public interface AuditRecordSink {

    /** Persist one immutable record. Implementations may throw; the async writer meters and moves on. */
    void write(AuditRecord record) throws Exception;
}
