package com.consense.service.sources;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Write-boundary fingerprints, also covering JPQL and direct SQL without reading LOBs in guards. */
public final class SourceContentDigestMigration {
    private SourceContentDigestMigration() {}
    /** Explicit finite installer for installations with Flyway disabled; never runs at application startup. */
    public static void main(String[] arguments)throws Exception {
        if(arguments.length!=2||!"--install".equals(arguments[0]))throw new IllegalArgumentException("Usage: SourceContentDigestMigration --install <jdbc-url>; credentials come from CONSENSE_DIGEST_DB_USER/PASSWORD");
        String user=System.getenv("CONSENSE_DIGEST_DB_USER"),password=System.getenv("CONSENSE_DIGEST_DB_PASSWORD");
        try(Connection connection=DriverManager.getConnection(arguments[1],user==null?"sa":user,password==null?"":password)) {
            install(connection);System.out.println("Source content digest write triggers installed; existing content rows were not backfilled.");
        }
    }
    public static synchronized void install(Connection connection)throws Exception {
        installTable(connection,"source_document",new String[]{"text_content","structured_content_json","parse_coverage_json","normal_candidate_json"},
                    new String[]{"text_content_sha256","structured_content_sha256","parse_coverage_sha256","normal_candidate_sha256"},true);
        if(tableExists(connection,"canonical_source_binding"))installTable(connection,"canonical_source_binding",new String[]{"snapshot_json"},new String[]{"snapshot_content_sha256"},false);
    }
    private static boolean tableExists(Connection c,String table)throws SQLException {
        try(ResultSet r=c.getMetaData().getTables(null,null,null,null)){while(r.next())if(table.equalsIgnoreCase(r.getString("TABLE_NAME")))return true;}return false;
    }
    private static void installTable(Connection c,String table,String[] inputs,String[] outputs,boolean aggregate)throws Exception {
        Set<String> columns=new HashSet<>();
        try(ResultSet r=c.getMetaData().getColumns(null,null,null,null)){while(r.next())if(table.equalsIgnoreCase(r.getString("TABLE_NAME")))columns.add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
        List<String> wanted=new ArrayList<>(Arrays.asList(outputs));if(aggregate)wanted.add("business_content_sha256");
        try(Statement s=c.createStatement()) {
            for(String column:wanted)if(!columns.contains(column))s.execute("alter table "+table+" add column "+column+" varchar(64)");
            String product=c.getMetaData().getDatabaseProductName();
            if("H2".equals(product)) {
                String source;try(InputStream stream=SourceContentDigestMigration.class.getResourceAsStream("/db/source-content-digest-trigger.h2.java")) {
                    if(stream==null)throw new IllegalStateException("Missing source digest trigger resource");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1)bytes.write(buffer,0,n);source=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
                }
                source=source.replace("@INPUTS@",quoted(inputs)).replace("@OUTPUTS@",quoted(outputs)).replace("@PREFIXED@",String.valueOf(aggregate));
                String name=table+"_content_digests";s.execute("drop trigger if exists "+name);
                s.execute("create trigger "+name+" before insert, update on "+table+" for each row as '"+source.replace("'","''")+"'");
            } else if("MySQL".equals(product)||"MariaDB".equals(product)) {
                for(String event:new String[]{"insert","update"}) {
                    StringBuilder body=new StringBuilder("begin ");
                    for(int i=0;i<inputs.length;i++) {
                        String hash=aggregate?"case when NEW."+inputs[i]+" is null then sha2('source-null-v1',256) else sha2(concat('source-string-v1',char(10),convert(NEW."+inputs[i]+" using utf8mb4)),256) end":"sha2(convert(NEW."+inputs[i]+" using utf8mb4),256)";
                        if("update".equals(event))hash="case when (BINARY NEW."+inputs[i]+" <=> BINARY OLD."+inputs[i]+") then case when OLD."+outputs[i]+" regexp '^[0-9a-f]{64}$' then OLD."+outputs[i]+" when "+(aggregate?"NEW.business_content_sha256='source-content-prepare-v1'":"NEW.snapshot_content_sha256='source-binding-prepare-v1'")+" then "+hash+" else null end else "+hash+" end";
                        body.append("set NEW.").append(outputs[i]).append('=').append(hash).append(';');
                    }
                    if(aggregate){body.append("set NEW.business_content_sha256=case when ");for(int i=0;i<outputs.length;i++){if(i>0)body.append(" and ");body.append("NEW.").append(outputs[i]).append(" is not null");}body.append(" then sha2(concat('source-business-content-v1',char(10)");for(String output:outputs)body.append(",NEW.").append(output).append(",char(10)");body.append("),256) else null end;");}
                    body.append("end");String name=table+"_content_digest_"+event;s.execute("drop trigger if exists "+name);
                    s.execute("create trigger "+name+" before "+event+" on "+table+" for each row "+body);
                }
            } else throw new IllegalStateException("Unsupported database for trusted source content fingerprints: "+product);
        }
    }
    private static String quoted(String[] values){List<String> result=new ArrayList<>();for(String value:values)result.add("\""+value+"\"");return String.join(",",result);}
}
