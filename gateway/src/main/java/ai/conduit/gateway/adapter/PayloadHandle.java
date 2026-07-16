package ai.conduit.gateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;

/**
 * The adapter-boundary provenance seam (F4). Every agent output crosses the harness boundary as a
 * {@code PayloadHandle}: either the JSON tree carried {@link Inline}, or a content-addressed
 * {@link Ref} to a claim-checked copy in the object store. The two are equal by construction — a
 * {@code Ref}'s {@code sha256} is the SHA-256 of the canonical bytes of the very same stamped tree an
 * {@code Inline} would have carried (see {@code CanonicalSha}).
 *
 * <p><b>v1 is provenance + seam, not lazy dataflow.</b> The DAG still operates on in-memory
 * {@link JsonNode} trees exactly as before: {@link #tree()} always returns the in-memory stamped tree
 * (even for a {@code Ref}, which keeps it so the request path re-fetches NOTHING). Lazy {@code Ref}
 * hydration through the Blackboard is deferred; {@code PayloadStore.materialize} is the tested seam for
 * it, but no demo request path depends on it.
 *
 * <p><b>Never Jackson-serialized.</b> The sealed type is exposed on {@code NodeResult} via a
 * {@code @JsonIgnore} component; audit gets an explicit flat view ({@code PayloadAuditView}). There is
 * deliberately no {@code @JsonTypeInfo} — no object-store URI or handle shape ever leaks into a trace
 * or insight document.
 *
 * <p><b>A2A contract (adapter does not exist yet).</b> A future A2A adapter maps an artifact
 * {@code FilePart.file.uri} → {@link Ref} and a {@code DataPart} → {@link Inline}, uniformly with
 * HTTP/MCP — proven by {@code ProtocolAdapterPayloadContractTest}, not by code here.
 */
public sealed interface PayloadHandle permits PayloadHandle.Inline, PayloadHandle.Ref {

    /** Where this payload came from — honestly labelled for the audit record. */
    enum Provenance {
        /** The agent's own HTTP/MCP-text response (or an A2A {@code DataPart}). */
        ADAPTER,
        /** Content the gateway EAGERLY fetched from an agent-supplied link (MCP {@code resource_link});
         *  TOFU — proves what the gateway fetched, not what the agent "sent". */
        FETCHED,
        /** A tree re-derived inside the gateway (coverage-filtered produced entities). Its
         *  {@link #parentSha256()} points at the pre-derivation payload for lineage. */
        DERIVED
    }

    /** The in-memory stamped tree. Always present in v1 — the request path never re-fetches a Ref. */
    JsonNode tree();

    /** Provenance of this payload, for the audit flat view. */
    Provenance provenance();

    /** For a {@link Provenance#DERIVED} payload, the sha256 of the payload it was derived from; else null. */
    default String parentSha256() {
        return null;
    }

    /**
     * The JSON tree carried inline. {@link #json()} returns the SAME reference it was constructed with —
     * never cloned, never re-parsed — and no sha is computed here (hashing runs only at audit flush or
     * spill time, never on the request path at default config).
     */
    record Inline(JsonNode json, Provenance provenance, String parentSha256) implements PayloadHandle {

        /** Adapter-provenance inline wrap — the default the compat path uses. Zero hashing, zero copy. */
        public Inline(JsonNode json) {
            this(json, Provenance.ADAPTER, null);
        }

        public Inline(JsonNode json, Provenance provenance) {
            this(json, provenance, null);
        }

        @Override
        public JsonNode tree() {
            return json;
        }
    }

    /**
     * A content-addressed claim-check: the payload lives in the object store under key {@code sha256}.
     * v1 keeps the already-parsed {@link #tree()} in-memory so no request-path re-fetch happens; the
     * URI/hash exist for audit provenance and the deferred lazy-fetch capability.
     *
     * @param uri        object-store location (e.g. {@code s3://conduit-payload/<sha256>})
     * @param sha256     SHA-256 hex of the canonical stamped bytes; also the object key
     * @param sizeBytes  canonical byte length; must be &ge; 0
     * @param mediaType  stored media type (e.g. {@code application/json})
     * @param provenance where the payload came from
     * @param tree       the in-memory stamped tree (v1 no-re-fetch guarantee); may be null off the request path
     */
    record Ref(URI uri, String sha256, long sizeBytes, String mediaType,
               Provenance provenance, JsonNode tree) implements PayloadHandle {

        public Ref {
            if (sha256 == null || sha256.isBlank()) {
                throw new IllegalArgumentException("PayloadHandle.Ref requires a non-blank sha256");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("PayloadHandle.Ref sizeBytes must be >= 0, was " + sizeBytes);
            }
        }

        /** Provenance-adapter ref with no retained in-memory tree (e.g. reconstructed from an audit view). */
        public Ref(URI uri, String sha256, long sizeBytes, String mediaType) {
            this(uri, sha256, sizeBytes, mediaType, Provenance.ADAPTER, null);
        }

        public Ref(URI uri, String sha256, long sizeBytes, String mediaType, Provenance provenance) {
            this(uri, sha256, sizeBytes, mediaType, provenance, null);
        }
    }
}
