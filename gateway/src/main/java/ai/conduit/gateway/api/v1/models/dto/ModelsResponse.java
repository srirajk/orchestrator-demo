package ai.conduit.gateway.api.v1.models.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ModelsResponse(
        String object,
        List<Model> data
) {

    public record Model(
            String id,
            String object,
            long created,
            @JsonProperty("owned_by") String ownedBy
    ) {}
}
