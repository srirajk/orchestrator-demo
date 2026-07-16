package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.infrastructure.expression.CompiledExpr;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The claim-check port (F4). Content-addressed spill of agent payloads to an object store, plus the
 * (deferred-use) materialise seam that reads one back with an integrity check.
 *
 * <p><b>The one hash rule.</b> {@link #put} stores the SAME canonical bytes the audit path hashes, under
 * key = {@code sha256} of those bytes, so an inline sha and a Ref sha of the same stamped response are
 * equal by construction ({@code CanonicalSha}).
 *
 * <p>Present only when spill is enabled ({@code conduit.payload.store.enabled=true}); adapters inject it
 * optionally and simply keep the payload {@link PayloadHandle.Inline} when it is absent.
 */
public interface PayloadStore {

    /**
     * Store {@code canonicalBytes} under key {@code sha256} and return the content-addressed
     * {@link PayloadHandle.Ref}. Synchronous and bounded (finite timeouts) — the caller runs this
     * inside the node's submitted callable so a slow store is cut by the node SLA.
     *
     * @param canonicalBytes the canonical sorted-keys UTF-8 bytes (from {@code CanonicalSha.canonicalBytes})
     * @param sha256         SHA-256 hex of those bytes — the object key AND the Ref sha
     * @param mediaType      stored media type (e.g. {@code application/json})
     * @param provenance     provenance to stamp on the returned Ref
     * @param tree           the in-memory stamped tree to retain on the Ref (v1 no-re-fetch guarantee)
     * @throws Exception on any store failure — the adapter falls back to Inline + WARN + counter
     */
    PayloadHandle.Ref put(byte[] canonicalBytes, String sha256, String mediaType,
                          PayloadHandle.Provenance provenance, JsonNode tree) throws Exception;

    /**
     * Materialise a {@link PayloadHandle.Ref} back into a {@link JsonNode}, verifying integrity.
     *
     * <p>This is the SEAM for the deferred lazy-Ref capability — no v1 demo request path calls it (the
     * DAG uses {@code PayloadHandle.tree()} directly). It:
     * <ul>
     *   <li>verifies the downloaded object re-digests to {@code ref.sha256()} → {@link PayloadIntegrityException} on mismatch;</li>
     *   <li>refuses {@code ref.sizeBytes() > conduit.payload.max-materialize-bytes} when {@code select == null}
     *       → {@link PayloadTooLargeException};</li>
     *   <li>uses finite connect/read timeouts and a bounded pool with a bounded acquire wait, honouring
     *       {@code ctx}'s deadline — a slow/lost store fails, never hangs.</li>
     * </ul>
     *
     * @param ref    the content-addressed handle to hydrate
     * @param select an optional compiled projection (F2 {@link CompiledExpr}, NOT a raw expression
     *               string) applied to the materialised tree; {@code null} = whole object
     * @param ctx    identity + deadline envelope (credential slot unused for the internal fetch)
     * @return the materialised (optionally projected) tree
     * @throws PayloadIntegrityException on a sha256 mismatch
     * @throws PayloadTooLargeException  when the object exceeds the cap and no {@code select} bounds it
     * @throws Exception                 on store I/O failure or deadline expiry
     */
    JsonNode materialize(PayloadHandle.Ref ref, CompiledExpr select, MaterializeContext ctx) throws Exception;
}
