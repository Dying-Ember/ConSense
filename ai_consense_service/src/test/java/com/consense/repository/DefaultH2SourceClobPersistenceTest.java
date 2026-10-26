package com.consense.repository;

import com.consense.config.H2SourceClobUpgradeConfiguration;
import com.consense.config.H2LocalFileAccess;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.SourceContentDigestMigration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the default h2 profile without starting application runners or touching a shared database. */
class DefaultH2SourceClobPersistenceTest {
    private static final String[] COLUMNS = {
            "structured_content_json", "normal_candidate_json", "native_pdf_evidence_json",
            "text_content", "parse_coverage_json", "oer_identity_json"
    };

    @TempDir Path directory;

    @BeforeAll static void h2LocalFileAccess() { H2LocalFileAccess.register(); }

    @Test void freshDefaultH2FileUsesClobsAndKeepsContentAfterRestart() throws Exception {
        String database = "jdbc:h2:file:" + directory.resolve("fresh").toString().replace('\\', '/');
        String options = ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        String url = database + ";AUTO_SERVER=TRUE" + options;
        String payload = "Original 中文 clause 😀\n".repeat(8192);
        long id;
        try (ConfigurableApplicationContext app = start(url); Connection connection = app.getBean(DataSource.class).getConnection()) {
            assertEquals("update", app.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("false", app.getEnvironment().getProperty("spring.flyway.enabled"));
            assertClobs(connection);
            try (PreparedStatement insert = connection.prepareStatement("insert into source_document "
                    + "(project_id, category, file_name, parse_status, ocr_used, created_at, "
                    + String.join(", ", COLUMNS) + ") values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, "isolated-project");
                insert.setString(2, SourceDocument.CATEGORY_VETTING_PACKAGE);
                insert.setString(3, "original.docx");
                insert.setString(4, "PARSED");
                insert.setBoolean(5, false);
                for (int i = 6; i <= 11; i++) insert.setString(i, payload);
                assertEquals(1, insert.executeUpdate());
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    id = keys.getLong(1);
                }
            }
        }
        try (Connection connection = DriverManager.getConnection(database + options + ";IFEXISTS=TRUE", "sa", "")) {
            assertClobs(connection);
            try (PreparedStatement select = connection.prepareStatement("select " + String.join(", ", COLUMNS)
                    + " from source_document where id=?")) {
                select.setLong(1, id);
                try (ResultSet row = select.executeQuery()) {
                    assertTrue(row.next());
                    for (int i = 1; i <= COLUMNS.length; i++) assertEquals(payload, row.getString(i), COLUMNS[i - 1]);
                    assertFalse(row.next());
                }
            }
        }
    }

    @Test void optInStartupUpgradesOnlyAClosedLegacyClone() throws Exception {
        String options = ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        String legacy = "jdbc:h2:rwfile:" + directory.resolve("legacy").toString().replace('\\', '/');
        String payload = "Authored 中文 clause 😀\n".repeat(8192);
        String digest;
        try (Connection connection = DriverManager.getConnection(legacy + options, "sa", "");
                Statement sql = connection.createStatement()) {
            sql.execute("create table source_document (id bigint auto_increment primary key, project_id varchar(64) not null, "
                    + "category varchar(32) not null, file_name varchar(512) not null, parse_status varchar(32) not null, "
                    + "ocr_used boolean not null, created_at timestamp not null, structured_content_json LONGTEXT, "
                    + "normal_candidate_json LONGTEXT, native_pdf_evidence_json LONGTEXT, text_content LONGTEXT, "
                    + "parse_coverage_json LONGTEXT, oer_identity_json LONGTEXT)");
            SourceContentDigestMigration.install(connection);
            try (PreparedStatement insert = connection.prepareStatement("insert into source_document "
                    + "(project_id, category, file_name, parse_status, ocr_used, created_at, "
                    + String.join(", ", COLUMNS) + ") values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, "isolated-project");
                insert.setString(2, SourceDocument.CATEGORY_VETTING_PACKAGE);
                insert.setString(3, "legacy.docx");
                insert.setString(4, "PARSED");
                insert.setBoolean(5, false);
                for (int i = 6; i <= 11; i++) insert.setString(i, payload);
                assertEquals(1, insert.executeUpdate());
            }
            try (ResultSet row = sql.executeQuery("select business_content_sha256 from source_document")) {
                assertTrue(row.next());
                digest = row.getString(1);
                assertEquals(64, digest.length());
            }
            for (String column : COLUMNS) {
                try (ResultSet metadata = connection.getMetaData().getColumns(null, connection.getSchema(),
                        "source_document", column)) {
                    assertTrue(metadata.next(), column);
                    assertNotEquals(Types.CLOB, metadata.getInt("DATA_TYPE"), column);
                }
            }
            sql.execute("CHECKPOINT SYNC");
        }
        Path original = directory.resolve("legacy.mv.db");
        byte[] originalHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original));
        Files.copy(original, directory.resolve("without-opt-in.mv.db"));
        String ordinaryUrl = "jdbc:h2:rwfile:" + directory.resolve("without-opt-in").toString().replace('\\', '/')
                + options + ";IFEXISTS=TRUE";
        try (ConfigurableApplicationContext app = start(ordinaryUrl);
                Connection connection = app.getBean(DataSource.class).getConnection()) {
            for (String column : COLUMNS) {
                try (ResultSet metadata = connection.getMetaData().getColumns(null, connection.getSchema(),
                        "source_document", column)) {
                    assertTrue(metadata.next(), column);
                    assertNotEquals(Types.CLOB, metadata.getInt("DATA_TYPE"), column);
                }
            }
        }
        Files.copy(original, directory.resolve("clone.mv.db"));
        String cloneUrl = "jdbc:h2:rwfile:" + directory.resolve("clone").toString().replace('\\', '/')
                + options + ";IFEXISTS=TRUE";
        try (ConfigurableApplicationContext app = start(cloneUrl, "--consense.h2.source-clob-upgrade=true");
                Connection connection = app.getBean(DataSource.class).getConnection()) {
            assertClobs(connection);
            try (Statement sql = connection.createStatement();
                    ResultSet row = sql.executeQuery("select " + String.join(", ", COLUMNS)
                            + ", business_content_sha256 from source_document where file_name='legacy.docx'")) {
                assertTrue(row.next());
                for (int i = 1; i <= COLUMNS.length; i++) assertEquals(payload, row.getString(i), COLUMNS[i - 1]);
                assertEquals(digest, row.getString(COLUMNS.length + 1));
                assertFalse(row.next());
            }
            try (Statement sql = connection.createStatement()) {
                assertEquals(1, sql.executeUpdate("update source_document set review_role='tender', oer_identity_json='confirmed' "
                        + "where file_name='legacy.docx'"));
                try (ResultSet row = sql.executeQuery("select review_role, oer_identity_json, business_content_sha256 "
                        + "from source_document where file_name='legacy.docx'")) {
                    assertTrue(row.next());
                    assertEquals("tender", row.getString(1));
                    assertEquals("confirmed", row.getString(2));
                    assertEquals(digest, row.getString(3));
                }
            }
        }
        assertArrayEquals(originalHash, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
    }

    private ConfigurableApplicationContext start(String url, String... extra) {
        String[] arguments = {"--spring.profiles.active=h2", "--spring.datasource.url=" + url,
                "--consense.storage-root=" + directory.resolve("uploads"),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"};
        String[] complete = java.util.Arrays.copyOf(arguments, arguments.length + extra.length);
        System.arraycopy(extra, 0, complete, arguments.length, extra.length);
        return new SpringApplicationBuilder(H2SchemaApplication.class).web(WebApplicationType.NONE).run(complete);
    }

    private void assertClobs(Connection connection) throws Exception {
        for (String column : COLUMNS) {
            try (ResultSet metadata = connection.getMetaData().getColumns(null, connection.getSchema(),
                    "source_document", column)) {
                assertTrue(metadata.next(), column);
                assertEquals(Types.CLOB, metadata.getInt("DATA_TYPE"), column);
            }
        }
    }

    @SpringBootConfiguration @EnableAutoConfiguration @EntityScan(basePackageClasses = SourceDocument.class)
    @Import(H2SourceClobUpgradeConfiguration.class)
    static class H2SchemaApplication { }
}
