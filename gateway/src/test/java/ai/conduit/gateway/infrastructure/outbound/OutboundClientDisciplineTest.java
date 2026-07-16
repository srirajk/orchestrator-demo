package ai.conduit.gateway.infrastructure.outbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-scanning static guard for VT-pinning discipline (F3 / AC1) — same pattern as
 * {@code ManifestSchemaCopiesInSyncTest}: read the real production sources, strip comments, and fail
 * the build on any untimed / unbounded outbound-client construction. Seven detectors, each proven
 * able to fail by a parameterized red-proof (a bad snippet it flags AND a good snippet it passes),
 * so a green run means "no detector is asleep", not "no detector exists".
 *
 * <p>Comment-stripping matters: several sources mention {@code new RestTemplate()},
 * {@code RestClient.Builder} etc. in Javadoc explaining WHY they avoid them — those must not trip the
 * grep, exactly as {@code scripts/world-b-check.sh} strips comments before scanning.
 */
class OutboundClientDisciplineTest {

    // ── The seven detectors ────────────────────────────────────────────────────
    // Each returns true when the (comment-stripped) source contains a violation of its rule.

    /** 1. Bare {@code new RestTemplate()} — no-arg ctor uses HttpURLConnection with an infinite read timeout. */
    static final Detector NO_BARE_RESTTEMPLATE = new Detector("bare new RestTemplate()",
            s -> Pattern.compile("new\\s+RestTemplate\\s*\\(\\s*\\)").matcher(s).find());

    /** 2. Any use of {@code HttpURLConnection} — synchronized internals pin a VT carrier pre-JEP-491. */
    static final Detector NO_HTTP_URL_CONNECTION = new Detector("HttpURLConnection",
            s -> Pattern.compile("\\bHttpURLConnection\\b").matcher(s).find());

    /** 3. Any use of {@code WebClient} — dropped from the gateway in F3 (reactor removed from the path). */
    static final Detector NO_WEBCLIENT = new Detector("WebClient",
            s -> Pattern.compile("\\bWebClient\\b").matcher(s).find());

    /** 4. {@code HttpClient.newBuilder()} whose statement omits {@code .connectTimeout(}. */
    static final Detector HTTPCLIENT_NEEDS_CONNECT_TIMEOUT = new Detector(
            "HttpClient.newBuilder() without connectTimeout",
            s -> statementsAfter(s, "HttpClient.newBuilder(").stream()
                    .anyMatch(stmt -> !stmt.contains(".connectTimeout(")));

    /** 5. An injected {@code RestClient.Builder} built without an explicit {@code .requestFactory(}. */
    static final Detector RESTCLIENT_BUILDER_NEEDS_FACTORY = new Detector(
            "injected RestClient.Builder without explicit requestFactory",
            s -> s.contains("RestClient.Builder") && !s.contains(".requestFactory("));

    /**
     * 6. {@code HttpRequest.newBuilder()} whose statement omits {@code .timeout(} AND is not inside an
     * allowlisted deadline-wrapper method. HttpRequest.timeout() bounds only time-to-headers, but its
     * absence is the tell-tale of an unbounded request; an allowlisted wrapper (e.g. one that joins
     * the send future with its own deadline) is the sanctioned exception.
     */
    static final Detector HTTPREQUEST_NEEDS_TIMEOUT = new Detector(
            "HttpRequest.newBuilder() without .timeout(",
            OutboundClientDisciplineTest::httpRequestWithoutTimeout);

    /** 7. {@code S3Client.builder()}/{@code S3AsyncClient.builder()} without {@code .overrideConfiguration(}. */
    static final Detector S3_NEEDS_OVERRIDE_CONFIG = new Detector(
            "S3Client.builder()/S3AsyncClient.builder() without overrideConfiguration",
            s -> Stream.of("S3Client.builder(", "S3AsyncClient.builder(")
                    .flatMap(marker -> statementsAfter(s, marker).stream())
                    .anyMatch(stmt -> !stmt.contains(".overrideConfiguration(")));

    static final List<Detector> DETECTORS = List.of(
            NO_BARE_RESTTEMPLATE, NO_HTTP_URL_CONNECTION, NO_WEBCLIENT,
            HTTPCLIENT_NEEDS_CONNECT_TIMEOUT, RESTCLIENT_BUILDER_NEEDS_FACTORY,
            HTTPREQUEST_NEEDS_TIMEOUT, S3_NEEDS_OVERRIDE_CONFIG);

    /** Method names sanctioned to send a builder-without-.timeout because they bound the send themselves. */
    static final List<String> ALLOWLISTED_DEADLINE_WRAPPERS = List.of("sendWithTimeout");

    // ── The real guard (AC1): every production source is clean ──────────────────

    @Test
    void allGatewaySourcesRespectOutboundClientDiscipline() throws IOException {
        Path mainJava = repoRoot().resolve("gateway/src/main/java");
        assertThat(Files.isDirectory(mainJava)).as("gateway main sources dir must exist").isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String stripped;
                try {
                    stripped = stripComments(Files.readString(p));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                for (Detector d : DETECTORS) {
                    if (d.test.test(stripped)) {
                        violations.add(d.name + "  →  " + repoRoot().relativize(p));
                    }
                }
            });
        }

        assertThat(violations)
                .as("VT-pinning discipline violations (untimed/unbounded outbound clients)")
                .isEmpty();
    }

    // ── Red-proofs (one per detector): flags the bad snippet, passes the good one ─

    @ParameterizedTest(name = "detector[{index}] {0} flags a real violation and passes a compliant case")
    @MethodSource("redProofs")
    void eachDetectorCanFailAndCanPass(String detectorName, Detector detector,
                                       String badSnippet, String goodSnippet) {
        assertThat(detector.test.test(stripComments(badSnippet)))
                .as("%s must FLAG the deliberately-bad snippet", detectorName).isTrue();
        assertThat(detector.test.test(stripComments(goodSnippet)))
                .as("%s must PASS the compliant snippet", detectorName).isFalse();
    }

    static Stream<Arguments> redProofs() {
        return Stream.of(
                Arguments.of("bare RestTemplate", NO_BARE_RESTTEMPLATE,
                        "RestTemplate rt = new RestTemplate();",
                        "RestTemplate rt = new RestTemplate(timedFactory(2000, 5000));"),
                Arguments.of("HttpURLConnection", NO_HTTP_URL_CONNECTION,
                        "HttpURLConnection c = (HttpURLConnection) url.openConnection();",
                        "HttpClient c = HttpClient.newBuilder().connectTimeout(D).build();"),
                Arguments.of("WebClient", NO_WEBCLIENT,
                        "WebClient wc = builder.build();",
                        "RestClient rc = injected;"),
                Arguments.of("HttpClient no connectTimeout", HTTPCLIENT_NEEDS_CONNECT_TIMEOUT,
                        "HttpClient c = HttpClient.newBuilder().version(HTTP_1_1).build();",
                        "HttpClient c = HttpClient.newBuilder().connectTimeout(D).build();"),
                Arguments.of("RestClient.Builder no factory", RESTCLIENT_BUILDER_NEEDS_FACTORY,
                        "void c(RestClient.Builder b){ this.rc = b.baseUrl(u).build(); }",
                        "void c(RestClient.Builder b){ this.rc = b.requestFactory(f).baseUrl(u).build(); }"),
                Arguments.of("HttpRequest no timeout", HTTPREQUEST_NEEDS_TIMEOUT,
                        "HttpRequest r = HttpRequest.newBuilder().uri(u).GET().build();",
                        "HttpRequest r = HttpRequest.newBuilder().uri(u).timeout(D).GET().build();"),
                Arguments.of("S3 no overrideConfiguration", S3_NEEDS_OVERRIDE_CONFIG,
                        "var b = S3Client.builder().region(R).build();",
                        "var b = S3Client.builder().overrideConfiguration(o).region(R).build();")
        );
    }

    // ── Detector plumbing ───────────────────────────────────────────────────────

    record Detector(String name, Predicate<String> test) {}

    private static boolean httpRequestWithoutTimeout(String s) {
        Matcher m = Pattern.compile("HttpRequest\\.newBuilder\\(").matcher(s);
        while (m.find()) {
            String stmt = statementFrom(s, m.start());
            if (stmt.contains(".timeout(")) continue;                 // bounded in-chain
            if (inAllowlistedWrapper(s, m.start())) continue;          // sanctioned wrapper
            return true;
        }
        return false;
    }

    /** True if the method enclosing {@code idx} is one of {@link #ALLOWLISTED_DEADLINE_WRAPPERS}. */
    private static boolean inAllowlistedWrapper(String s, int idx) {
        String before = s.substring(0, idx);
        Matcher decl = Pattern.compile("\\b(\\w+)\\s*\\([^;{}]*\\)\\s*(throws [\\w ,.]+)?\\{").matcher(before);
        String enclosing = null;
        while (decl.find()) enclosing = decl.group(1);   // last method declaration before idx
        return enclosing != null && ALLOWLISTED_DEADLINE_WRAPPERS.contains(enclosing);
    }

    /** All statements (text up to the next {@code ;}) that begin at each occurrence of {@code marker}. */
    private static List<String> statementsAfter(String s, String marker) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = s.indexOf(marker, i)) >= 0) {
            out.add(statementFrom(s, i));
            i += marker.length();
        }
        return out;
    }

    private static String statementFrom(String s, int start) {
        int semi = s.indexOf(';', start);
        return semi < 0 ? s.substring(start) : s.substring(start, semi);
    }

    /** Strips block comments (incl. Javadoc) and line comments so doc text can't trip a detector. */
    static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)//.*$", " ");
    }

    /** gateway/src/test/java/... → repo root is one parent up from the module (user.dir = gateway). */
    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).getParent();
    }
}
