package db.migration;

import com.consense.service.sources.SourceContentDigestMigration;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds write-time digests only; does not scan or backfill historical source/binding LOBs. */
public class V26__source_content_digests extends BaseJavaMigration {
    @Override public void migrate(Context context)throws Exception {SourceContentDigestMigration.install(context.getConnection());}
}
