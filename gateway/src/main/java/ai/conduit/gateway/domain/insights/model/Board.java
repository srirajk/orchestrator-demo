package ai.conduit.gateway.domain.insights.model;

import java.util.List;

/** One board's rendered panels. Wire shape: {@code {panels:[...]}}. */
public record Board(List<Panel> panels) {}
