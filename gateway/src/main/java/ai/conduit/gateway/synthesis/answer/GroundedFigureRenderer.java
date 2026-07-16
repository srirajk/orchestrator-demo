package ai.conduit.gateway.synthesis.answer;

import ai.conduit.gateway.infrastructure.expression.CompiledExpr;
import ai.conduit.gateway.infrastructure.expression.EvalEngine;
import ai.conduit.gateway.infrastructure.expression.RootVar;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Component
public class GroundedFigureRenderer {

    private final ObjectMapper mapper;
    private final EvalEngine evalEngine;

    public GroundedFigureRenderer(ObjectMapper mapper) {
        this(mapper, new ai.conduit.gateway.infrastructure.expression.CelEvalEngine(mapper));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GroundedFigureRenderer(ObjectMapper mapper, EvalEngine evalEngine) {
        this.mapper = mapper;
        this.evalEngine = evalEngine;
    }

    public List<GroundedFigure> render(List<NodeResult> results,
                                       Function<String, Optional<AgentManifest>> manifestLookup) {
        if (results == null || manifestLookup == null) return List.of();
        List<GroundedFigure> figures = new ArrayList<>();
        for (NodeResult result : results) {
            if (result == null || !result.isOk() || result.data() == null) continue;
            AgentManifest manifest = manifestLookup.apply(result.agentId()).orElse(null);
            AgentManifest.Io io = manifest == null ? null : manifest.io();
            List<AgentManifest.Produce> produces =
                    (io == null || io.produces() == null) ? List.of() : io.produces();
            for (AgentManifest.Produce produce : produces) {
                List<AgentManifest.ProducedFigure> declared =
                        (produce == null || produce.figures() == null) ? List.of() : produce.figures();
                for (AgentManifest.ProducedFigure figure : declared) {
                    GroundedFigure rendered = renderOne(result.agentId(), figure, result.data(), figures.size());
                    if (rendered != null) figures.add(rendered);
                }
            }
        }
        return figures;
    }

    public GroundedFigure renderOne(String sourceAgent,
                                    AgentManifest.ProducedFigure figure,
                                    JsonNode data,
                                    int index) {
        if (figure == null || data == null || figure.path() == null || figure.path().isBlank()) return null;
        JsonNode selected;
        try {
            CompiledExpr path = evalEngine.compile(figure.path(), RootVar.OUTPUT);
            selected = evalEngine.eval(path, data, EvalEngine.Mode.LENIENT);
        } catch (RuntimeException e) {
            return null;   // a failed/invalid figure path → figure skipped (fail-safe, unchanged behavior)
        }
        if (selected == null || selected.isMissingNode() || selected.isNull()) return null;
        String rendered = format(selected, figure.format());
        String placeholder = "{{figure_" + index + "_" + slug(figure.label()) + "}}";
        return new GroundedFigure(
                figure.label(),
                placeholder,
                rendered,
                selected.deepCopy(),
                figure.format(),
                sourceAgent,
                numericValues(selected, figure.format(), rendered));
    }

    public String format(JsonNode value, String format) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        String fmt = format == null ? "plain" : format;
        return switch (fmt) {
            case "percent" -> trim(scalePercent(value), 2) + "%";
            case "percent1" -> trim(scalePercent(value), 1) + "%";
            case "percent2" -> trim(scalePercent(value), 2) + "%";
            case "currency_usd" -> "$" + grouped(decimal(value), 2);
            case "count" -> String.valueOf(Math.round(decimal(value)));
            case "date", "plain" -> scalarText(value);
            default -> scalarText(value);
        };
    }

    private Set<Double> numericValues(JsonNode value, String format, String rendered) {
        if (!value.isNumber() && !value.isTextual()) return Set.of();
        Double raw = numeric(value);
        if (raw == null) return Set.of();
        Set<Double> values = new LinkedHashSet<>();
        values.add(raw);
        String fmt = format == null ? "plain" : format;
        if (fmt.startsWith("percent")) {
            if (Math.abs(raw) <= 1.0) {
                values.add(raw * 100.0);
            } else {
                values.add(raw / 100.0);
            }
        }
        Double renderedNumeric = numeric(rendered);
        if (renderedNumeric != null) values.add(renderedNumeric);
        return values;
    }

    private double scalePercent(JsonNode value) {
        double raw = decimal(value);
        return Math.abs(raw) <= 1.0 ? raw * 100.0 : raw;
    }

    private double decimal(JsonNode value) {
        Double numeric = numeric(value);
        return numeric == null ? 0.0 : numeric;
    }

    private Double numeric(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.asDouble();
        if (value.isTextual()) {
            return numeric(value.asText());
        }
        return null;
    }

    private Double numeric(String value) {
        if (value == null) return null;
        try {
            return Double.parseDouble(value.replace(",", "").replace("$", "").replace("%", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String scalarText(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isNumber()) return trim(value.asDouble(), 6);
        if (value.isBoolean()) return Boolean.toString(value.asBoolean());
        return value.toString();
    }

    private String grouped(double value, int scale) {
        DecimalFormat format = new DecimalFormat("#,##0." + "0".repeat(scale),
                DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private String trim(double value, int scale) {
        BigDecimal bd = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
        return bd.toPlainString();
    }

    private String slug(String label) {
        if (label == null || label.isBlank()) return "value";
        String slug = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        slug = slug.replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "value" : slug;
    }
}
