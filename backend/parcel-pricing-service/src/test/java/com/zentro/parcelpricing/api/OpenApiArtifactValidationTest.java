package com.zentro.parcelpricing.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validates every committed service spec with the external Swagger Parser in test scope. */
class OpenApiArtifactValidationTest {
  private static final Path SPECS = Path.of("..", "..", "docs", "api", "openapi").toAbsolutePath().normalize();

  @Test
  void allFiveServiceSpecsAreValidOpenApi31Documents() throws Exception {
    List<Path> files;
    try (var paths = Files.list(SPECS)) {
      files = paths.filter(path -> path.getFileName().toString().endsWith(".openapi.json")).sorted().toList();
    }
    assertThat(files).hasSize(5);

    OpenAPIV3Parser parser = new OpenAPIV3Parser();
    for (Path file : files) {
      SwaggerParseResult result = parser.readLocation(file.toUri().toString(), null, new ParseOptions());
      assertThat(result.getOpenAPI()).as(file.getFileName().toString()).isNotNull();
      assertThat(result.getOpenAPI().getOpenapi()).as(file.getFileName().toString()).isEqualTo("3.1.0");
      assertThat(result.getMessages()).as(file.getFileName().toString()).isEmpty();
    }
  }
}
