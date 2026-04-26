package edu.njit.jerse.daikonplusplus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.njit.jerse.daikonplusplus.config.DpConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

public final class LocalLlmClient implements LlmClient {

  private final DpConfig config;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public LocalLlmClient(DpConfig config) {
    this.config = config;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  @Override
  public List<LlmInvariantGenerator.InvariantsOut.Item> complete(String system, String user)
      throws IOException {

    try {
      String prompt = system + "\n\n" + user;

      String requestBody =
          mapper.writeValueAsString(new OllamaRequest(config.llmLocalModel(), prompt));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.llmLocalUrl() + "/api/generate"))
              .timeout(Duration.ofSeconds(config.llmPerReqTimeoutSec()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new IOException("Local LLM HTTP error: " + response.statusCode());
      }

      // STEP 1: extract raw model output
      JsonNode root = mapper.readTree(response.body());
      String raw = root.path("response").asText();

      if (raw == null || raw.isBlank()) {
        return List.of();
      }

      // STEP 2: EXACT SAME parsing path as OpenAI
      LlmInvariantGenerator.InvariantsOut out =
          mapper.readValue(raw, LlmInvariantGenerator.InvariantsOut.class);

      return out.invariants == null ? List.of() : out.invariants;

    } catch (Exception e) {
      throw new IOException("Local LLM call failed", e);
    }
  }

  private static final class OllamaRequest {
    public String model;
    public String prompt;
    public boolean stream = false;

    public OllamaRequest(String model, String prompt) {
      this.model = model;
      this.prompt = prompt;
    }
  }
}
