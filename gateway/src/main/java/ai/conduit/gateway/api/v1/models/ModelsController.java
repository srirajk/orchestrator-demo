package ai.conduit.gateway.api.v1.models;

import ai.conduit.gateway.api.v1.models.dto.ModelsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class ModelsController {

    @GetMapping("/models")
    public ModelsResponse listModels() {
        return new ModelsResponse(
                "list",
                List.of(new ModelsResponse.Model(
                        "conduit-assistant",
                        "model",
                        1_700_000_000L,
                        "conduit"
                ))
        );
    }
}
