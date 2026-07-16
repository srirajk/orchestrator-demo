package ai.conduit.gateway.infrastructure.expression;

/**
 * The single bound root variable a manifest expression may reference. Each {@link RootVar} maps to
 * exactly one declared identifier in the {@link EvalEngine}'s checked environment — and to nothing
 * else. Because every {@code Env} declares ONLY its own variable, an expression that references any
 * other root (a bare legacy-JMESPath identifier like {@code failed}, or {@code output.x} compiled as
 * {@link #INPUT}) is an undeclared reference at check time and fails the compile loudly. This is the
 * World-B guardrail: the gateway never guesses which root an expression means; the call site declares
 * it and the compiler enforces it.
 */
public enum RootVar {

    /** The consumer's bound wire input (edge {@code select}, {@code map.over}, node {@code condition}). */
    INPUT("input"),

    /** A single element of a {@code map.over} collection (a {@code map.item_select}, or a per-item id selector). */
    ITEM("item"),

    /** A producer's raw output (a figure {@code path} or a produced-entity {@code select}). */
    OUTPUT("output");

    private final String varName;

    RootVar(String varName) {
        this.varName = varName;
    }

    /** The CEL identifier this root is bound to (e.g. {@code "input"}). */
    public String varName() {
        return varName;
    }
}
