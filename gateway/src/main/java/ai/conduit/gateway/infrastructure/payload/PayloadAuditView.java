package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.adapter.PayloadHandle;

/**
 * The explicit, flat audit projection of a {@link PayloadHandle} (F4 §6). The sealed handle is never
 * Jackson-serialized; audit records this flat shape instead, so no object-store URI or handle type ever
 * leaks through the trace/insight serializers.
 *
 * <p><b>Hashing is here, on the flush path — never the request path.</b> {@link #of(PayloadHandle)}
 * computes the sha of an {@link PayloadHandle.Inline} lazily (via {@link CanonicalSha}); a
 * {@link PayloadHandle.Ref} already carries its sha from spill time. Because both hash the SAME canonical
 * stamped bytes, {@code payload_sha256 == } the spilled object key by construction ({@code HashUniformityTest}).
 *
 * @param payloadKind  {@code "inline"} or {@code "ref"}
 * @param payloadSha256 SHA-256 hex of the canonical stamped bytes
 * @param payloadSize   canonical byte length
 * @param payloadUri    object-store URI (Ref only; {@code null} for Inline)
 * @param provenance    {@code adapter} | {@code fetched} | {@code derived}
 * @param parentSha256  lineage to the pre-derivation payload (derived only; else {@code null})
 */
public record PayloadAuditView(
        String payloadKind,
        String payloadSha256,
        long payloadSize,
        String payloadUri,
        String provenance,
        String parentSha256) {

    /** Build the flat view from a handle. Computes the Inline sha on this (flush) thread — never the request path. */
    public static PayloadAuditView of(PayloadHandle handle) {
        if (handle == null) {
            return null;
        }
        String provenance = handle.provenance().name().toLowerCase();
        if (handle instanceof PayloadHandle.Ref ref) {
            return new PayloadAuditView("ref", ref.sha256(), ref.sizeBytes(),
                    ref.uri() == null ? null : ref.uri().toString(), provenance, ref.parentSha256());
        }
        PayloadHandle.Inline inline = (PayloadHandle.Inline) handle;
        byte[] canonical = CanonicalSha.canonicalBytes(inline.json());
        String sha = CanonicalSha.sha256Hex(canonical);
        return new PayloadAuditView("inline", sha, canonical.length, null, provenance, inline.parentSha256());
    }
}
