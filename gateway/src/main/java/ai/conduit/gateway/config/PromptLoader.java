package ai.conduit.gateway.config;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the gateway's LLM prompt skeletons from {@code classpath:prompts/**&#47;*.md} once, at bean
 * construction, and hands them out as raw text or strictly-rendered templates.
 *
 * <p>Design: small, eager, cached, fail-fast. The entire prompt corpus is read exactly once in the
 * constructor — never on the request path. A blank or unreadable resource fails Spring startup, in
 * keeping with the gateway's refuse-to-start posture (missing secrets, embedding-model mismatch).
 *
 * <p>World-B: these resources hold only domain-agnostic skeleton text. Every domain-bearing token
 * (entity fields, display nouns, id-pattern hints, domain context) arrives at runtime through a
 * {@code {{placeholder}}} filled by Java from the effective manifest/config — the externalization
 * moves wording, not knowledge. {@code scripts/world-b-check.sh} scans this directory too.
 */
@Component
public class PromptLoader {

    /** Any leftover {@code {{token}}} after substitution is a bug — a typo or an unfilled variable. */
    private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[a-z0-9_]+}}");

    /** name (path under prompts/, no {@code .md}) → raw text, e.g. "answer-synthesizer.system". */
    private final Map<String, String> prompts;

    public PromptLoader(ResourcePatternResolver resolver) throws IOException {
        Map<String, String> loaded = new HashMap<>();
        // classpath*: so the glob resolves in both an exploded filesystem build and inside a jar.
        for (Resource r : resolver.getResources("classpath*:prompts/**/*.md")) {
            String name = nameFor(r);
            String text = r.getContentAsString(StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new IllegalStateException("Prompt resource is blank: " + name);
            }
            loaded.put(name, text);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "No prompt resources found under classpath*:prompts/**/*.md");
        }
        this.prompts = Map.copyOf(loaded);
    }

    /** Raw prompt text; throws if unknown (fail fast at first use = bean construction of the caller). */
    public String prompt(String name) {
        String p = prompts.get(name);
        if (p == null) {
            throw new IllegalStateException("Unknown prompt resource: " + name);
        }
        return p;
    }

    /**
     * Strict render: substitutes every provided var by exact {@code {{key}}} token, then rejects any
     * leftover {@code {{token}}} — catching both a typo in the resource and a missing Java-side var.
     */
    public String render(String name, Map<String, String> vars) {
        String out = prompt(name);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        Matcher m = LEFTOVER.matcher(out);
        if (m.find()) {
            throw new IllegalStateException(
                    "Unresolved placeholder " + m.group() + " in prompt '" + name + "'");
        }
        return out;
    }

    /** Derives the map key from a resource URL: the path under {@code prompts/} with {@code .md} dropped. */
    private static String nameFor(Resource r) throws IOException {
        String url = r.getURL().toString();
        int idx = url.lastIndexOf("/prompts/");
        if (idx < 0) {
            throw new IllegalStateException("Prompt resource is not under a prompts/ directory: " + url);
        }
        String rel = url.substring(idx + "/prompts/".length());
        if (rel.endsWith(".md")) {
            rel = rel.substring(0, rel.length() - ".md".length());
        }
        return rel;
    }

    /** Convenience factory for plain-JUnit tests (no Spring context): resolves the test classpath. */
    public static PromptLoader forClasspath() throws IOException {
        return new PromptLoader(new PathMatchingResourcePatternResolver());
    }
}
