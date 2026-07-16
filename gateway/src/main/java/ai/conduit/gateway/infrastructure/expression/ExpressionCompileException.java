package ai.conduit.gateway.infrastructure.expression;

/**
 * Thrown when a manifest expression fails to parse or references an undeclared identifier under its
 * declared {@link RootVar}. This is a <b>loud, ingest-time</b> failure: a legacy JMESPath string
 * (e.g. a bare {@code failed} identifier) or a wrong-root reference cannot be compiled by the checked
 * environment, so a manifest carrying one refuses to load and the container refuses to start — there
 * is no dual-language mode.
 */
public class ExpressionCompileException extends RuntimeException {

    public ExpressionCompileException(String source, RootVar var, String issues) {
        super("expression compile failed (root=" + (var == null ? "?" : var.varName())
                + "): '" + source + "' — " + issues);
    }
}
