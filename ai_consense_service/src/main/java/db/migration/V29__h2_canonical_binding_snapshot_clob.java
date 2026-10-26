package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** H2 maps the original LONGTEXT snapshot column to inline VARCHAR storage. */
public class V29__h2_canonical_binding_snapshot_clob extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        if (!"H2".equals(metadata.getDatabaseProductName())) return;
        try (ResultSet column = metadata.getColumns(null, connection.getSchema(),
                "canonical_source_binding", "snapshot_json")) {
            if (!column.next()) throw new IllegalStateException("Missing canonical_source_binding.snapshot_json");
            int type = column.getInt("DATA_TYPE");
            if (type == Types.CLOB) return;
            if (type != Types.VARCHAR && type != Types.LONGVARCHAR)
                throw new IllegalStateException("Unexpected canonical_source_binding.snapshot_json JDBC type: " + type);
        }
        try (Statement sql = connection.createStatement()) {
            sql.execute("alter table canonical_source_binding alter column snapshot_json CLOB");
        }
    }
}
