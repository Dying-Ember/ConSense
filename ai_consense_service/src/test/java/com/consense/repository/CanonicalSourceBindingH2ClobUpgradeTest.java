package com.consense.repository;

import com.consense.config.H2LocalFileAccess;
import com.consense.service.sources.SourceContentDigestMigration;
import db.migration.V29__h2_canonical_binding_snapshot_clob;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Storage-only regression coverage; authored fixtures do not establish browser acceptance. */
class CanonicalSourceBindingH2ClobUpgradeTest {
    private static final String OPTIONS = ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
            + ";OLD_INFORMATION_SCHEMA=TRUE;NON_KEYWORDS=VALUE";

    @TempDir Path temporaryDirectory;

    @BeforeAll static void localH2FileAccess() { H2LocalFileAccess.register(); }

    @Test void actualFlywayUpgradePreservesTheCompleteBindingAndDigestsAfterReopen() throws Exception {
        String url = "jdbc:h2:rwfile:" + temporaryDirectory.resolve("binding").toAbsolutePath().toString().replace('\\', '/') + OPTIONS;
        String snapshot = "{\r\n\"schema\":\"authored-binding-v1\",\r\n\"text\":\""
                + "Clause and table evidence \u4e2d\u6587 \uD83D\uDE00. ".repeat(32768)
                + "\",\r\n\"line\":\"first\\r\\nsecond\",\"unknown\":null,\"number\":1e-07\r\n}\r\n";
        Map<String, String> before;
        Map<String, String> sourceBefore;
        int eligibilityType;
        int sourceType;
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            createPopulatedLegacySchema(connection, url, snapshot);
            before = onlyRow(connection, "canonical_source_binding");
            sourceBefore = onlyRow(connection, "source_document");
            assertEquals(sha256(snapshot), before.get("snapshot_content_sha256"));
            assertEquals(sha256(snapshot), before.get("snapshot_bytes_sha256"));
            assertEquals(Types.VARCHAR, type(connection, "canonical_source_binding", "snapshot_json"));
            eligibilityType = type(connection, "canonical_source_binding", "eligible_modules_json");
            sourceType = type(connection, "source_document", "normal_candidate_json");
        }
        // The persisted fixture has history28 and triggers, matching an existing installation before29.
        assertEquals(1, flyway(url).migrate().migrationsExecuted);
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertEquals(Types.CLOB, type(connection, "canonical_source_binding", "snapshot_json"));
            assertEquals(eligibilityType, type(connection, "canonical_source_binding", "eligible_modules_json"));
            assertEquals(sourceType, type(connection, "source_document", "normal_candidate_json"));
            assertEquals(before, onlyRow(connection, "canonical_source_binding"));
            assertEquals(sourceBefore, onlyRow(connection, "source_document"));
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V29__h2_canonical_binding_snapshot_clob().migrate(context);
            assertEquals(before, onlyRow(connection, "canonical_source_binding"));
        }
        assertEquals(0, flyway(url).migrate().migrationsExecuted);
        // All connections have closed; this read and write use the persisted file after a real reopen.
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertEquals(before, onlyRow(connection, "canonical_source_binding"));
            String amended = snapshot + " ";
            try (PreparedStatement update = connection.prepareStatement(
                    "update canonical_source_binding set snapshot_json=? where id=41")) {
                update.setString(1, amended);
                assertEquals(1, update.executeUpdate());
            }
            Map<String, String> after = onlyRow(connection, "canonical_source_binding");
            assertEquals(amended, after.get("snapshot_json"));
            assertEquals(sha256(amended), after.get("snapshot_content_sha256"), "The content trigger must survive conversion");
            after.put("snapshot_json", before.get("snapshot_json"));
            after.put("snapshot_content_sha256", before.get("snapshot_content_sha256"));
            assertEquals(before, after, "Updating content must preserve every identity, review and family pin");
            assertEquals(sourceBefore, onlyRow(connection, "source_document"));
        }
    }

    @Test void actualFlywayPathRejectsMissingTableAndMissingColumn() throws Exception {
        for (boolean tablePresent : new boolean[]{false, true}) {
            String url = memoryUrl();
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                    Statement sql = connection.createStatement()) {
                sql.execute("create table sentinel (id integer primary key, retained varchar(64))");
                sql.execute("insert into sentinel values (1, 'preserved')");
                if (tablePresent) sql.execute("create table canonical_source_binding (id bigint primary key)");
                FlywayException error = assertThrows(FlywayException.class, () -> flyway(url).migrate());
                Throwable failure = rootCause(error);
                assertEquals(IllegalStateException.class, failure.getClass());
                assertEquals("Missing canonical_source_binding.snapshot_json", failure.getMessage());
                assertEquals("preserved", onlyRow(connection, "sentinel").get("retained"));
            }
        }
    }

    @Test void actualFlywayPathRejectsAnUnexpectedTypeWithoutChangingTheRowOrOtherColumns() throws Exception {
        String url = memoryUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement sql = connection.createStatement()) {
            sql.execute("create table canonical_source_binding (id bigint primary key, snapshot_json integer, eligible_modules_json LONGTEXT)");
            sql.execute("insert into canonical_source_binding values (1, 7, '[\"vetting\"]')");
            Map<String, String> before = onlyRow(connection, "canonical_source_binding");
            int eligibilityType = type(connection, "canonical_source_binding", "eligible_modules_json");
            FlywayException error = assertThrows(FlywayException.class, () -> flyway(url).migrate());
            Throwable failure = rootCause(error);
            assertEquals(IllegalStateException.class, failure.getClass());
            assertEquals("Unexpected canonical_source_binding.snapshot_json JDBC type: " + Types.INTEGER, failure.getMessage());
            assertEquals(Types.INTEGER, type(connection, "canonical_source_binding", "snapshot_json"));
            assertEquals(eligibilityType, type(connection, "canonical_source_binding", "eligible_modules_json"));
            assertEquals(before, onlyRow(connection, "canonical_source_binding"));
        }
    }

    @Test void nativeMysqlReturnsBeforeAnySchemaInspectionOrDdl() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        new V29__h2_canonical_binding_snapshot_clob().migrate(context);
        verify(context).getConnection();
        verify(connection).getMetaData();
        verify(metadata).getDatabaseProductName();
        verifyNoMoreInteractions(context, connection, metadata);
    }

    @Test void longvarcharMetadataUsesTheSameFixedClobConversion() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet column = mock(ResultSet.class);
        Statement sql = mock(Statement.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getSchema()).thenReturn("public");
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getColumns(null, "public", "canonical_source_binding", "snapshot_json")).thenReturn(column);
        when(column.next()).thenReturn(true);
        when(column.getInt("DATA_TYPE")).thenReturn(Types.LONGVARCHAR);
        when(connection.createStatement()).thenReturn(sql);
        new V29__h2_canonical_binding_snapshot_clob().migrate(context);
        verify(sql).execute("alter table canonical_source_binding alter column snapshot_json CLOB");
        verify(column).close();
        verify(sql).close();
    }

    private static Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("28").target("29").load();
    }

    private static String memoryUrl() {
        return "jdbc:h2:mem:binding_clob_" + UUID.randomUUID().toString().replace("-", "") + OPTIONS + ";DB_CLOSE_DELAY=-1";
    }

    private static void createPopulatedLegacySchema(Connection connection, String url, String snapshot) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute("create table source_document (id bigint primary key, project_id varchar(64), file_name varchar(512), "
                    + "text_content LONGTEXT, structured_content_json LONGTEXT, parse_coverage_json LONGTEXT, normal_candidate_json LONGTEXT)");
        }
        ResourceDatabasePopulator migrations = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V14__canonical_source_bindings.sql"),
                new ClassPathResource("db/migration/V15__canonical_source_family_pins.sql"));
        migrations.setSqlScriptEncoding("UTF-8");
        migrations.populate(connection);
        // Real existing databases already have Flyway history before digest triggers are installed.
        flyway(url).baseline();
        SourceContentDigestMigration.install(connection);
        try (Statement sql = connection.createStatement()) {
            sql.execute("insert into source_document (id, project_id, file_name, text_content, structured_content_json, "
                    + "parse_coverage_json, normal_candidate_json, source_sha256, oer_identity_json, native_pdf_evidence_json) "
                    + "values (11, 'authored-project', 'authored-original.pdf', 'Original text', '[{\"text\":\"Original text\"}]', "
                    + "'{\"complete\":true}', '{\"retained\":true}', '" + "a".repeat(64) + "', '{\"identity\":true}', '{\"native\":true}')");
        }
        String[] pins = {"projection_sha256", "original_source_sha256", "java_parsed_source_hash", "routing_sha256",
                "source_revision_sha256", "projection_revision_sha256", "canonical_source_hash", "scope_policy_sha256",
                "adoption_manifest_sha256", "review_reference_sha256", "snapshot_bytes_sha256",
                "family_composition_sha256", "family_component_set_sha256"};
        String columns = String.join(",", pins);
        try (PreparedStatement insert = connection.prepareStatement("insert into canonical_source_binding "
                + "(id,project_id,document_id," + columns + ",declared_module,eligible_modules_json,snapshot_json,created_at) "
                + "values (41,'authored-project',11," + String.join(",", java.util.Collections.nCopies(pins.length, "?"))
                + ",'vetting','[\"vetting\",\"advisory\"]',?,'2026-10-26 07:30:00')")) {
            for (int i = 0; i < pins.length; i++) insert.setString(i + 1, "snapshot_bytes_sha256".equals(pins[i])
                    ? sha256(snapshot) : String.format(java.util.Locale.ROOT, "%064x", i + 1));
            insert.setString(pins.length + 1, snapshot);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static Map<String, String> onlyRow(Connection connection, String table) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery("select * from " + table)) {
            assertTrue(row.next(), table);
            ResultSetMetaData metadata = row.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++)
                values.put(metadata.getColumnLabel(column), row.getString(column));
            assertFalse(row.next(), "The complete row count must be preserved for " + table);
        }
        return values;
    }

    private static int type(Connection connection, String table, String name) throws Exception {
        try (ResultSet column = connection.getMetaData().getColumns(null, connection.getSchema(), table, name)) {
            assertTrue(column.next(), table + "." + name);
            return column.getInt("DATA_TYPE");
        }
    }

    private static String sha256(String raw) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)))
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }
}
