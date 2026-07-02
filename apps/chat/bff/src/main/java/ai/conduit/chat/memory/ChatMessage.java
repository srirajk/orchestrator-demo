package ai.conduit.chat.memory;

/**
 * A single {role, content} pair as sent to the (stateless) gateway in
 * {@code messages[]}. Role is one of {@code system}, {@code user}, {@code assistant}.
 */
public record ChatMessage(String role, String content) {
}
