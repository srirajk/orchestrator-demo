package ai.conduit.gateway.infrastructure.telemetry.event;

public record IntentClassifiedData(String intent, double confidence, String reasoning) {}
