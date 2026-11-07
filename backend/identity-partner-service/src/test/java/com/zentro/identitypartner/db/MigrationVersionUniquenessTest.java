package com.zentro.identitypartner.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MigrationVersionUniquenessTest {
  private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

  @Test
  void migrationVersionsAreUniqueOnTheApplicationClasspath() throws IOException {
    Resource[] migrations = new PathMatchingResourcePatternResolver()
        .getResources("classpath*:db/migration/V*.sql");
    Map<String, List<String>> filesByVersion = new HashMap<>();

    for (Resource migration : migrations) {
      String filename = migration.getFilename();
      Matcher matcher = VERSION.matcher(filename == null ? "" : filename);
      assertThat(matcher.matches()).as("migration filename %s has a Flyway version", filename).isTrue();
      filesByVersion.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>()).add(filename);
    }

    Map<String, List<String>> duplicates = filesByVersion.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);

    assertThat(duplicates).as("each S1 Flyway version maps to one migration file").isEmpty();
  }
}
