package ai.conduit.gateway.infrastructure.payload;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * THE ONE HASH RULE (F4 §3a). {@code sha256 = SHA-256(canonical sorted-keys UTF-8 bytes of the FINAL
 * stamped JsonNode)} — computed AFTER {@code withVerifiedSub} and any adapter mutation. The spill path
 * stores these SAME canonical bytes under key = the hash, so an inline sha and a Ref sha of the same
 * response are equal by construction (proved by {@code HashUniformityTest}).
 *
 * <p>Canonicalisation is deterministic and dependency-light: object keys are sorted lexicographically
 * at every level; arrays keep their order; scalars serialise as compact JSON. This is stable across
 * JVMs and Jackson field-insertion orders, which a naive {@code node.toString()} is not.
 *
 * <p><b>Never on the request path at default config.</b> Hashing runs only at audit flush or at spill
 * time (threshold crossed) — never in the {@code NodeResult} compat wrap.
 */
public final class CanonicalSha {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private CanonicalSha() {
    }

    /** The canonical sorted-keys UTF-8 bytes of {@code node} (a {@code null} node canonicalises to {@code null}). */
    public static byte[] canonicalBytes(JsonNode node) {
        JsonNode source = node == null ? MAPPER.nullNode() : node;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = MAPPER.getFactory().createGenerator(out)) {
            writeCanonical(source, gen);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalise JSON for hashing", e);
        }
        return out.toByteArray();
    }

    /** SHA-256 hex of arbitrary bytes. */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** SHA-256 hex of the canonical bytes of {@code node} — the value used as both audit sha and object key. */
    public static String hashHex(JsonNode node) {
        return sha256Hex(canonicalBytes(node));
    }

    private static void writeCanonical(JsonNode node, JsonGenerator gen) throws Exception {
        switch (node.getNodeType()) {
            case OBJECT -> {
                ObjectNode obj = (ObjectNode) node;
                List<String> names = new ArrayList<>();
                obj.fieldNames().forEachRemaining(names::add);
                Collections.sort(names);
                gen.writeStartObject();
                for (String name : names) {
                    gen.writeFieldName(name);
                    writeCanonical(obj.get(name), gen);
                }
                gen.writeEndObject();
            }
            case ARRAY -> {
                ArrayNode arr = (ArrayNode) node;
                gen.writeStartArray();
                for (JsonNode el : arr) {
                    writeCanonical(el, gen);
                }
                gen.writeEndArray();
            }
            // Scalars: delegate to Jackson's compact rendering (stable numeric/string/bool/null forms).
            default -> gen.writeTree(node);
        }
    }
}
