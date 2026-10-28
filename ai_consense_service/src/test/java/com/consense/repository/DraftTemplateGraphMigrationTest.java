package com.consense.repository;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.flywaydb.core.Flyway;
import static org.junit.jupiter.api.Assertions.*;

/** Executes V23 exactly in H2 MySQL mode; does not claim production MySQL/Flyway acceptance. */
class DraftTemplateGraphMigrationTest {
    @Test void flywayResolvesExistingMigrationsAlongsideV30(){
        String url="jdbc:h2:mem:baseline_flyway_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;OLD_INFORMATION_SCHEMA=TRUE;NON_KEYWORDS=VALUE";
        org.flywaydb.core.api.MigrationInfo[] migrations=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().info().all();
        assertEquals(1,java.util.Arrays.stream(migrations).filter(item->item.getVersion()!=null&&"28".equals(item.getVersion().getVersion())).count());
        assertEquals(1,java.util.Arrays.stream(migrations).filter(item->item.getVersion()!=null&&"29".equals(item.getVersion().getVersion())).count());
        assertEquals(1,java.util.Arrays.stream(migrations).filter(item->item.getVersion()!=null&&"30".equals(item.getVersion().getVersion())).count());
    }
    @Test void v30CreatesIndependentBaselineStorage()throws Exception{
        try(Connection connection=DriverManager.getConnection("jdbc:h2:mem:baseline_migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");Statement sql=connection.createStatement()){
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__draft_template_baseline.sql")).populate(connection);
            sql.execute("INSERT INTO draft_template_baseline(id,edition_sha256,origin_project_id,origin_revision_id,field_ids_json,graph_json,graph_sha256,created_at) VALUES ('b','edition','old','revision','[]','{}','digest',CURRENT_TIMESTAMP)");
            sql.execute("INSERT INTO draft_template_baseline_source(id,baseline_id,file_key,source_sha256) VALUES ('b:NTT','b','NTT','source-hash')");
            assertThrows(SQLException.class,()->sql.execute("INSERT INTO draft_template_baseline_source(id,baseline_id,file_key,source_sha256) VALUES ('b:NTT2','b','NTT','other')"));
            try(ResultSet row=sql.executeQuery("SELECT graph_json,graph_sha256 FROM draft_template_baseline WHERE id='b'")){assertTrue(row.next());assertEquals("{}",row.getString(1));assertEquals("digest",row.getString(2));}
        }
    }
    @Test void exactSqlPreservesExistingRowsAndEnforcesOneBindingPerProjectInput()throws Exception{
        try(Connection connection=DriverManager.getConnection("jdbc:h2:mem:review_migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");Statement sql=connection.createStatement()){
            sql.execute("CREATE TABLE source_document(id BIGINT PRIMARY KEY,file_name VARCHAR(512))");sql.execute("INSERT INTO source_document VALUES(1,'legacy-preserved.docx')");
            ResourceDatabasePopulator migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V23__draft_template_graph.sql"));migration.setSqlScriptEncoding("UTF-8");migration.populate(connection);
            try(ResultSet row=sql.executeQuery("SELECT file_name FROM source_document WHERE id=1")){assertTrue(row.next());assertEquals("legacy-preserved.docx",row.getString(1));}
            sql.execute("INSERT INTO draft_template_graph_head(project_id) VALUES ('project')");
            try(ResultSet row=sql.executeQuery("SELECT current_revision_id,draft_revision_id FROM draft_template_graph_head")){assertTrue(row.next());assertNull(row.getString(1));assertNull(row.getString(2));}
            sql.execute("INSERT INTO draft_graph_input_binding(id,project_id,var_key,definition_sha256) VALUES ('a','project','duration','hash')");assertThrows(SQLException.class,()->sql.execute("INSERT INTO draft_graph_input_binding(id,project_id,var_key,definition_sha256) VALUES ('b','project','duration','changed')"));
            sql.execute("INSERT INTO draft_template_graph_revision(id,project_id,status,graph_sha256,graph_json,sources_json,created_at) VALUES ('r','project','DRAFT','hash','{}','[]',CURRENT_TIMESTAMP)");
            try(ResultSet row=sql.executeQuery("SELECT graph_json,sources_json FROM draft_template_graph_revision WHERE id='r'")){assertTrue(row.next());assertEquals("{}",row.getString(1));assertEquals("[]",row.getString(2));}
        }
    }
}
