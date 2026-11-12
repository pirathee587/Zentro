package com.zentro.parcelpricing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Checks real MockMvc response JSON against the schema referenced by its OpenAPI operation/status.
 * Supports parcel.openapi.json by default, and any service spec via service qualifier.
 */
public final class OpenApiResponseAssertions {
  private static final Path SPECS_DIR = Path.of("..", "..", "docs", "api", "openapi")
      .toAbsolutePath().normalize();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
  private static final ConcurrentHashMap<String, JsonNode> DOCUMENTS = new ConcurrentHashMap<>();

  private OpenApiResponseAssertions() {}

  public static void assertMatches(String path, String method, MvcResult result) throws Exception {
    assertMatches("parcel", path, method, result);
  }

  public static void assertMatches(String service, String path, String method, MvcResult result) throws Exception {
    JsonNode doc = DOCUMENTS.computeIfAbsent(service, s -> {
      try {
        Path specFile = SPECS_DIR.resolve(s + ".openapi.json");
        return MAPPER.readTree(specFile.toFile());
      } catch (Exception e) {
        throw new RuntimeException("Could not load OpenAPI spec for service " + s, e);
      }
    });
    int status = result.getResponse().getStatus();
    JsonNode response = doc.path("paths").path(path).path(method.toLowerCase()).path("responses").path(Integer.toString(status));
    String mediaType = status >= 400 ? "application/problem+json" : "application/json";
    JsonNode reference = response.path("content").path(mediaType).path("schema").path("$ref");
    assertThat(reference.isTextual())
        .as("OpenAPI schema for %s %s %s status %s (%s)", service, method, path, status, mediaType).isTrue();
    String ref = reference.asText();
    Path specFile = SPECS_DIR.resolve(service + ".openapi.json");
    JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(specFile.toUri() + ref), CONFIG);
    JsonNode actual = MAPPER.readTree(result.getResponse().getContentAsByteArray());
    assertThat(schema.validate(actual))
        .as("real response for %s %s %s status %s conforms to %s", service, method, path, status, ref).isEmpty();
  }
}
