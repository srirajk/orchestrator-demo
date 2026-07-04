package ai.conduit.gateway.infrastructure.telemetry.event;

/**
 * Trace event payload for a single authorization <em>gate</em> decision, emitted so the
 * glass-box renders the authorization decision as a legible, step-by-step trace where every
 * gate shows its pass/deny plus a plain-english reason.
 *
 * <p>Published under trace event {@code type="gate"}. Each frame is one gate's verdict for one
 * agent, in evaluation order:
 * <ul>
 *   <li>{@code audience}       — enterprise agents are open to all; segment agents proceed to the segment gate</li>
 *   <li>{@code segment}        — the agent's business segment must be one the principal holds</li>
 *   <li>{@code classification} — the principal's tier in that segment must meet the agent's data classification</li>
 *   <li>{@code coverage}       — the resolved entity must be in the principal's book of business</li>
 * </ul>
 *
 * <p>All copy is derived from data the principal and the manifest declare (segment names, tiers,
 * entity ids) — never a hardcoded domain literal, so the gateway stays World-B clean.
 *
 * @param gate   which gate produced this verdict: "audience" | "segment" | "classification" | "coverage"
 * @param effect the verdict: "allow" | "deny"
 * @param reason plain-english explanation (e.g. "wealth tier confidential &lt; confidential-pii")
 * @param agent  the agent id this gate was evaluated for
 */
public record GateData(
        String gate,
        String effect,
        String reason,
        String agent
) {
    public static final String EFFECT_ALLOW = "allow";
    public static final String EFFECT_DENY  = "deny";

    public static final String GATE_AUDIENCE       = "audience";
    public static final String GATE_SEGMENT        = "segment";
    public static final String GATE_CLASSIFICATION = "classification";
    public static final String GATE_COVERAGE       = "coverage";

    public static GateData allow(String gate, String reason, String agent) {
        return new GateData(gate, EFFECT_ALLOW, reason, agent);
    }

    public static GateData deny(String gate, String reason, String agent) {
        return new GateData(gate, EFFECT_DENY, reason, agent);
    }
}
