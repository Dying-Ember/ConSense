package com.consense.repository;

import com.consense.service.sources.SourceContentDigestMigration;
import db.migration.V28__h2_source_document_clobs;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Upgrades an existing H2 MySQL-mode source row without losing its stored content or digests. */
class SourceDocumentH2ClobUpgradeTest {
    @Test void startupSkipsAbsentColumnsButRejectsAnUnexpectedTypeBeforeChangingAnyColumn() throws Exception {
        String url = "jdbc:h2:mem:source_clob_preflight_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            V28__h2_source_document_clobs.upgradeExisting(connection);
            sql.execute("create table source_document (structured_content_json LONGTEXT, normal_candidate_json INTEGER)");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> V28__h2_source_document_clobs.upgradeExisting(connection));
            assertTrue(error.getMessage().contains("normal_candidate_json"));
            try (ResultSet column = connection.getMetaData().getColumns(null, connection.getSchema(),
                    "source_document", "structured_content_json")) {
                assertTrue(column.next());
                assertNotEquals(Types.CLOB, column.getInt("DATA_TYPE"));
            }
            sql.execute("alter table source_document alter column normal_candidate_json LONGTEXT");
            V28__h2_source_document_clobs.upgradeExisting(connection);
            for (String name : new String[]{"structured_content_json", "normal_candidate_json"}) {
                try (ResultSet column = connection.getMetaData().getColumns(null, connection.getSchema(),
                        "source_document", name)) {
                    assertTrue(column.next());
                    assertEquals(Types.CLOB, column.getInt("DATA_TYPE"));
                }
            }
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            assertThrows(IllegalStateException.class, () -> new V28__h2_source_document_clobs().migrate(context));
        }
    }

    @Test void populatedLongtextRowBecomesExternalClobsAndSurvivesBothPurposeWrites() throws Exception {
        String url = "jdbc:h2:mem:source_clob_upgrade_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
                + ";OLD_INFORMATION_SCHEMA=TRUE;NON_KEYWORDS=VALUE";
        String payload = "Original clause and table evidence. ".repeat(32768);
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            sql.execute("create table source_document (id bigint primary key, project_id varchar(64), source_sha256 varchar(64), "
                    + "review_role varchar(32), oer_identity_json LONGTEXT, native_pdf_evidence_json LONGTEXT, "
                    + "text_content LONGTEXT, structured_content_json LONGTEXT, parse_coverage_json LONGTEXT, normal_candidate_json LONGTEXT)");
            SourceContentDigestMigration.install(connection);
            try (PreparedStatement insert = connection.prepareStatement("insert into source_document "
                    + "(id, project_id, source_sha256, oer_identity_json, native_pdf_evidence_json, text_content, "
                    + "structured_content_json, parse_coverage_json, normal_candidate_json) values (1, 'project', ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, "a".repeat(64));
                for (int i = 2; i <= 7; i++) insert.setString(i, payload);
                assertEquals(1, insert.executeUpdate());
            }
            String digest;
            try (ResultSet row = sql.executeQuery("select business_content_sha256 from source_document where id=1")) {
                assertTrue(row.next()); digest = row.getString(1); assertEquals(64, digest.length());
            }
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            new V28__h2_source_document_clobs().migrate(context);
            new V28__h2_source_document_clobs().migrate(context);
            for (String column : new String[]{"oer_identity_json", "native_pdf_evidence_json", "text_content",
                    "structured_content_json", "parse_coverage_json", "normal_candidate_json"}) {
                try (ResultSet columns = connection.getMetaData().getColumns(null, connection.getSchema(), "source_document", column)) {
                    assertTrue(columns.next(), column);
                    assertEquals(Types.CLOB, columns.getInt("DATA_TYPE"), column);
                }
            }
            assertEquals(1, sql.executeUpdate("update source_document set review_role='tender' where id=1 and source_sha256='"
                    + "a".repeat(64) + "'"));
            assertEquals(1, sql.executeUpdate("update source_document set oer_identity_json='confirmed' where id=1"));
            try (ResultSet row = sql.executeQuery("select review_role, oer_identity_json, native_pdf_evidence_json, "
                    + "text_content, structured_content_json, parse_coverage_json, normal_candidate_json, "
                    + "business_content_sha256 from source_document where id=1")) {
                assertTrue(row.next()); assertEquals("tender", row.getString(1)); assertEquals("confirmed", row.getString(2));
                for (int i = 3; i <= 7; i++) assertEquals(payload, row.getString(i));
                assertEquals(digest, row.getString(8)); assertFalse(row.next());
            }
            assertEquals(1, sql.executeUpdate("update source_document set text_content='Amended source clause' where id=1"));
            try (ResultSet row = sql.executeQuery("select business_content_sha256 from source_document where id=1")) {
                assertTrue(row.next()); assertNotEquals(digest, row.getString(1));
            }
        }
    }
}
