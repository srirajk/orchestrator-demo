package ai.conduit.gateway.infrastructure.expression;

/**
 * Thrown by {@link EvalEngine#eval} when an expression evaluation fails in a way the mode does not
 * absorb: any error in {@link EvalEngine.Mode#STRICT}, or a <i>non-recoverable</i> error (a
 * type/overload mismatch, divide-by-zero — never a mere missing field) in
 * {@link EvalEngine.Mode#LENIENT}. Carries the underlying CEL error message so call sites can log a
 * precise reason.
 */
public class ExpressionEvalException extends RuntimeException {

    public ExpressionEvalException(String source, String message, Throwable cause) {
        super("expression eval failed: '" + source + "' — " + message, cause);
    }

    public ExpressionEvalException(String source, String message) {
        this(source, message, null);
    }
}
