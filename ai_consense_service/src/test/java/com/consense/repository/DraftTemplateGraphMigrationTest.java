package com.consense.repository;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** Executes V23 exactly in H2 MySQL mode; does not claim production MySQL/Flyway acceptance. */
class DraftTemplateGraphMigrationTest {
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
