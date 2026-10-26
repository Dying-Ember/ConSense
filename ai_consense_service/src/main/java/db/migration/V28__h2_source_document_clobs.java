package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** MySQL LONGTEXT is inline VARCHAR in H2 MySQL mode; large source rows must use external CLOB storage. */
public class V28__h2_source_document_clobs extends BaseJavaMigration {
    private static final String[] COLUMNS = {
            "structured_content_json", "normal_candidate_json", "native_pdf_evidence_json",
            "text_content", "parse_coverage_json", "oer_identity_json"
    };

    @Override public void migrate(Context context) throws Exception {
        upgrade(context.getConnection(), false);
    }

    /** The opt-in H2 startup path runs before Hibernate creates any missing columns. */
    public static void upgradeExisting(Connection connection) throws Exception {
        upgrade(connection, true);
    }

    private static void upgrade(Connection connection, boolean allowMissing) throws Exception {
        if (!"H2".equals(connection.getMetaData().getDatabaseProductName())) return;
        boolean[] convert = new boolean[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            String column = COLUMNS[i];
            try (ResultSet result = connection.getMetaData().getColumns(null, connection.getSchema(), "source_document", column)) {
                if (!result.next()) {
                    if (!allowMissing) throw new IllegalStateException("Missing source_document." + column);
                    continue;
                }
                int type = result.getInt("DATA_TYPE");
                if (type == Types.CLOB) continue;
                if (type != Types.VARCHAR && type != Types.LONGVARCHAR)
                    throw new IllegalStateException("Unexpected source_document." + column + " JDBC type: " + type);
                convert[i] = true;
            }
        }
        for (int i = 0; i < COLUMNS.length; i++) {
            if (!convert[i]) continue;
            try (Statement sql = connection.createStatement()) {
                sql.execute("alter table source_document alter column " + COLUMNS[i] + " CLOB");
            }
        }
    }
}
