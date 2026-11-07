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
import org.springframework.test.web.servlet.MvcResult;

/** Checks real MockMvc response JSON against the schema referenced by its OpenAPI operation/status. */
public final class OpenApiResponseAssertions {
  private static final Path SPEC = Path.of("..", "..", "docs", "api", "openapi", "parcel.openapi.json")
      .toAbsolutePath().normalize();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNode DOCUMENT;
  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

  static {
    try {
      DOCUMENT = MAPPER.readTree(SPEC.toFile());
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private OpenApiResponseAssertions() {}

  public static void assertMatches(String path, String method, MvcResult result) throws Exception {
    int status = result.getResponse().getStatus();
    JsonNode response = DOCUMENT.path("paths").path(path).path(method.toLowerCase()).path("responses").path(Integer.toString(status));
    String mediaType = status >= 400 ? "application/problem+json" : "application/json";
    JsonNode reference = response.path("content").path(mediaType).path("schema").path("$ref");
    assertThat(reference.isTextual())
        .as("OpenAPI schema for %s %s status %s (%s)", method, path, status, mediaType).isTrue();
    String ref = reference.asText();
    JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(SPEC.toUri() + ref), CONFIG);
    JsonNode actual = MAPPER.readTree(result.getResponse().getContentAsByteArray());
    assertThat(schema.validate(actual))
        .as("real response for %s %s status %s conforms to %s", method, path, status, ref).isEmpty();
  }
}
