package ai.conduit.chat.memory;

import ai.conduit.chat.conversation.Conversation;

/**
 * Maintains a conversation's <b>facts-free</b> rolling topical summary.
 *
 * <p>Hard invariant (see {@code ADR-STATELESS-GATEWAY.md} / {@code CONDUIT-CHAT-FEATURES.md}):
 * the summary captures <em>topics and intent only</em> — never values, never
 * entities-as-truth, never entitlement conclusions. It is continuity scaffolding for
 * classification, not a data store. The gateway remains the sole ground truth and
 * re-fetches + re-authorizes every turn.
 */
public interface SummaryService {

    /**
     * Requests (re)generation of the conversation's rolling summary. Implementations
     * MUST be fire-and-forget: non-blocking, and never throwing into the caller — a
     * summary failure must never affect the user's streamed response.
     *
     * @param conversation the conversation whose transcript should be summarized.
     * @param userId       owner id (for isolation when reading the transcript).
     */
    void requestRegeneration(Conversation conversation, String userId);
}
