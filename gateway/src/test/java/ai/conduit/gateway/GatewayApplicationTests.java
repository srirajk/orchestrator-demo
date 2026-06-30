package ai.conduit.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void contextLoads() {
        // Verifies the Spring context wires up cleanly
    }

    @Test
    void models_returns_conduit_assistant() throws Exception {
        mvc.perform(get("/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value("conduit-assistant"))
                .andExpect(jsonPath("$.data[0].object").value("model"));
    }

    @Test
    void chat_completions_returns_text_event_stream() throws Exception {
        String body = """
                {
                  "model": "conduit-assistant",
                  "messages": [{"role": "user", "content": "hello"}],
                  "stream": true
                }
                """;

        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        containsString(MediaType.TEXT_EVENT_STREAM_VALUE)));
    }
}
