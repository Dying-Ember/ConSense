package com.consense.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = StaticAssetMimeHttpTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class StaticAssetMimeHttpTest {
    private static final Path WEB = createWeb();

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @EnableConfigurationProperties(ConsenseProperties.class)
    @Import(WebConfig.class)
    static class TestApp { }

    @DynamicPropertySource
    static void staticLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.web.resources.static-locations", () -> WEB.toUri().toString());
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void servesJavascriptAndModuleWorkerWithJavascriptMime() {
        ResponseEntity<String> script = http.getForEntity("/assets/app.js", String.class);
        assertEquals(HttpStatus.OK, script.getStatusCode());
        assertEquals("application/javascript", script.getHeaders().getContentType().getType() + "/" + script.getHeaders().getContentType().getSubtype());
        assertEquals("export const script = 1;", script.getBody());

        ResponseEntity<String> worker = http.getForEntity("/assets/pdf.worker.min-test.mjs", String.class);
        assertEquals(HttpStatus.OK, worker.getStatusCode());
        String workerMime = worker.getHeaders().getContentType().getType() + "/" + worker.getHeaders().getContentType().getSubtype();
        assertTrue(workerMime.equals("application/javascript") || workerMime.equals("text/javascript"), workerMime);
        assertEquals("export const worker = 1;", worker.getBody());
    }

    private static Path createWeb() {
        try {
            Path root = Files.createTempDirectory("consense-static-mime-");
            Path assets = Files.createDirectory(root.resolve("assets"));
            Files.write(assets.resolve("app.js"), "export const script = 1;".getBytes(StandardCharsets.UTF_8));
            Files.write(assets.resolve("pdf.worker.min-test.mjs"), "export const worker = 1;".getBytes(StandardCharsets.UTF_8));
            return root;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @AfterAll
    static void removeWeb() throws IOException {
        try (Stream<Path> files = Files.walk(WEB)) {
            for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
