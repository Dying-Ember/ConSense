package com.consense.service.sources;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Direct SQL bypasses JPA callbacks; the database must invalidate exactly the changed source. */
class SourceContentDigestTriggerTest {
    @Test void directSqlContentChangesRefreshDigestsWhileProgressAndNullEmptyRemainDistinct() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:source_digest_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")) {
            try(Statement s=c.createStatement()) {
                s.execute("create table source_document(id bigint primary key,text_content clob,structured_content_json clob,parse_coverage_json clob,normal_candidate_json clob,parse_message varchar(1024))");
                s.execute("create table canonical_source_binding(id bigint primary key,snapshot_json clob)");
            }
            String legacy=String.join("",Collections.nCopies(262144,"x"));
            try(PreparedStatement s=c.prepareStatement("insert into source_document(id,text_content) values(2,?)")){s.setCharacterStream(1,new java.io.StringReader(legacy),legacy.length());s.executeUpdate();}
            SourceContentDigestMigration.install(c);
            try(Statement s=c.createStatement()){s.executeUpdate("insert into source_document(id,text_content,structured_content_json,parse_coverage_json,normal_candidate_json) values(1,'AUTHORED 😀','[]',null,'')");}
            Map<String,String> before=read(c);assertNotNull(before.get("business_content_sha256"));
            assertEquals(SourceContentDigests.field("AUTHORED 😀"),before.get("text_content_sha256"));
            assertNotEquals(before.get("parse_coverage_sha256"),before.get("normal_candidate_sha256"));
            long reads=hashedCharacters(c);
            try(Statement s=c.createStatement()){s.executeUpdate("update source_document set parse_message='progress only' where id in (1,2)");}
            assertEquals(before,read(c),"Progress must not create another content revision");
            assertEquals(reads,hashedCharacters(c),"Actual SQL metadata updates must read zero existing LOB characters, including legacy null stamps");
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select business_content_sha256 from source_document where id=2")){assertTrue(r.next());assertNull(r.getString(1));}
            try(Statement s=c.createStatement()){s.executeUpdate("update source_document set business_content_sha256='source-content-prepare-v1' where id=2");}
            assertTrue(hashedCharacters(c)>reads,"Explicit cold preparation streams the selected old LOB exactly when requested");
            for(String field:Arrays.asList("text_content","structured_content_json","parse_coverage_json","normal_candidate_json")) {
                Map<String,String> previous=read(c);
                try(PreparedStatement s=c.prepareStatement("update source_document set "+field+"=? where id=1")){s.setString(1,"AUTHORED changed "+field);s.executeUpdate();}
                assertNotEquals(previous.get("business_content_sha256"),read(c).get("business_content_sha256"),field);
            }
            Map<String,String> current=read(c);
            try(Statement s=c.createStatement()){s.executeUpdate("update source_document set business_content_sha256='forged' where id=1");}
            assertEquals(current,read(c),"Digest-only SQL cannot forge the database's content revision");
            try(Statement s=c.createStatement()){s.executeUpdate("insert into canonical_source_binding values(1,'{\"schema\":\"authored\"}',null)");}
            String binding=readBinding(c);
            assertEquals(CanonicalJson.sha("{\"schema\":\"authored\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),binding);
            try(Statement s=c.createStatement()){s.executeUpdate("update canonical_source_binding set snapshot_json='altered outside JPA' where id=1");}
            assertNotEquals(binding,readBinding(c),"Direct SQL snapshot mutation must invalidate warm binding metadata");
        }
    }
    private long hashedCharacters(Connection c)throws Exception {
        org.h2.engine.SessionLocal session=(org.h2.engine.SessionLocal)c.unwrap(org.h2.jdbc.JdbcConnection.class).getSession();
        org.h2.schema.TriggerObject trigger=session.getDatabase().getSchema("PUBLIC").findTrigger("SOURCE_DOCUMENT_CONTENT_DIGESTS");
        java.lang.reflect.Field callback=org.h2.schema.TriggerObject.class.getDeclaredField("triggerCallback");callback.setAccessible(true);Object instance=callback.get(trigger);
        java.lang.reflect.Field count=instance.getClass().getDeclaredField("hashedCharacters");count.setAccessible(true);return count.getLong(instance);
    }
    private String readBinding(Connection c)throws Exception {try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select snapshot_content_sha256 from canonical_source_binding where id=1")){assertTrue(r.next());return r.getString(1);}}
    private Map<String,String> read(Connection c)throws Exception {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select text_content_sha256,structured_content_sha256,parse_coverage_sha256,normal_candidate_sha256,business_content_sha256 from source_document where id=1")) {
            assertTrue(r.next());Map<String,String> values=new LinkedHashMap<>();for(int i=1;i<=5;i++)values.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),r.getString(i));return values;
        }
    }
}
