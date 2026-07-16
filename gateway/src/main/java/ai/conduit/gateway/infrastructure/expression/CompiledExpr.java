package ai.conduit.gateway.infrastructure.expression;

import org.projectnessie.cel.Program;

/**
 * A compiled, declaration-checked manifest expression, ready for repeated evaluation. Immutable and
 * thread-safe: the underlying CEL {@link Program} is stateless and its {@code eval} is a pure function
 * of the activation, so a single {@link CompiledExpr} is safely shared across virtual threads.
 *
 * @param source the original expression text (kept for diagnostics)
 * @param var    the root variable this expression was checked against
 * @param program the compiled CEL program
 */
public record CompiledExpr(String source, RootVar var, Program program) {
}
