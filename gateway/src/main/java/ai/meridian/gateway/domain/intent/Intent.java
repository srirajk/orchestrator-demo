package ai.meridian.gateway.domain.intent;

/**
 * Routing label produced by {@link IntentClassifier}.
 *
 * <ul>
 *   <li>{@code FETCH_DATA}  — user wants fresh data from one or more agents</li>
 *   <li>{@code FOLLOW_UP}   — user is asking a clarifying/reformulation question
 *       about data already returned in this conversation</li>
 *   <li>{@code CLARIFY}     — intent is clear but the entity is ambiguous; ask
 *       a scoped question before hitting agents</li>
 *   <li>{@code CHITCHAT}    — general conversation; no banking data needed</li>
 * </ul>
 */
public enum Intent {
    FETCH_DATA,
    FOLLOW_UP,
    CLARIFY,
    CHITCHAT
}
