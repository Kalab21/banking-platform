package com.bankingplatform.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for GHSA-rhhx-6j8h-8cvw, across every service.
 *
 * <p>Each business service carries springdoc 2.6, whose {@code /v3/api-docs}
 * grows a per-locale cache without bound. The docs are off unless
 * {@code SPRINGDOC_ENABLED=true}, which only {@code docker-compose.dev-ports.yml}
 * sets. Read from the sibling modules' sources, so a service added later, or
 * one whose default is flipped, fails the build here.
 */
@DisplayName("Service API docs are off by default")
class ServiceApiDocsDefaultTest {

    private static final List<String> OFF = List.of("false", "${SPRINGDOC_ENABLED:false}");

    @Test
    @DisplayName("every service that configures springdoc defaults it to off")
    @SuppressWarnings("unchecked")
    void everyServiceDefaultsToOff() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> configs;
        try (Stream<Path> modules = Files.list(root)) {
            configs = modules
                    .map(module -> module.resolve("src/main/resources/application.yml"))
                    .filter(Files::exists)
                    .toList();
        }
        assertThat(configs).as("service application.yml files under " + root).hasSizeGreaterThan(10);

        int withDocs = 0;
        for (Path config : configs) {
            Map<String, Object> yaml;
            try (InputStream in = Files.newInputStream(config)) {
                yaml = new Yaml().load(in);
            }
            Map<String, Object> springdoc = (Map<String, Object>) yaml.get("springdoc");
            if (springdoc == null) {
                continue;
            }
            withDocs++;
            for (String part : List.of("api-docs", "swagger-ui")) {
                Map<String, Object> section = (Map<String, Object>) springdoc.get(part);
                assertThat(section).as(config + " springdoc." + part).isNotNull();
                assertThat(String.valueOf(section.get("enabled")))
                        .as(config + " springdoc." + part + ".enabled")
                        .isIn(OFF);
            }
        }
        assertThat(withDocs).as("services configuring springdoc").isGreaterThanOrEqualTo(12);
    }
}
