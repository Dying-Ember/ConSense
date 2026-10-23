package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Explicit cold preparation of selected current rows only; it never changes content or frozen runs. */
@Service @RequiredArgsConstructor
public class SourceContentDigestPreparation {
    private final CanonicalCurrentSourceReader sources;
    private final JdbcTemplate jdbc;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public SourceDocument prepareSourceStamp(SourceDocument captured,String module){
        require(captured!=null&&captured.getId()!=null,"SOURCE_DOCUMENT_UNAVAILABLE");
        SourceDocument before=sources.validatedScope(captured.getProjectId(),captured.getId(),module);
        require(ProjectDocumentScope.sameScopeRevision(captured,before),"CANONICAL_SOURCE_STALE");
        if(SourceContentDigests.stamped(before)){sources.requireCurrentStamp(captured,before);return before;}
        require(before.getBusinessContentSha256()==null,"SOURCE_CONTENT_REVISION_INVALID");
        LinkedHashMap<String,Object> pins=sourcePins(captured);
        require(prepare("source_document","business_content_sha256",pins)==1,"CANONICAL_SOURCE_STALE");
        // Same transaction: REQUIRES_NEW here would not see the trigger result until this write commits.
        SourceDocument after=sources.validatedScope(captured.getProjectId(),captured.getId(),module);
        require(SourceContentDigests.stamped(after),"SOURCE_CONTENT_REVISION_UNAVAILABLE");
        sources.requireCurrentStamp(captured,after);return after;
    }

    /** Caller has verified the cold binding schema/assets; this adds a DB-owned raw-byte identity only. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String prepareBindingStamp(CanonicalSourceBinding binding,SourceDocument captured,String module){
        return prepareBindingStamp(binding,captured,module,true);
    }
    /** A caller-verified skipped foreign history receives a raw-byte stamp, never current business authority. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String prepareHistoricalBindingStamp(CanonicalSourceBinding binding,SourceDocument captured,String currentModule){
        return prepareBindingStamp(binding,captured,currentModule,false);
    }
    private String prepareBindingStamp(CanonicalSourceBinding binding,SourceDocument captured,String module,boolean selected){
        require(binding!=null&&binding.getId()!=null&&binding.getSnapshotJson()!=null&&captured!=null,"CANONICAL_BINDING_UNAVAILABLE");
        require(Objects.equals(binding.getProjectId(),captured.getProjectId())&&Objects.equals(binding.getDocumentId(),captured.getId()),"CANONICAL_SOURCE_STALE");
        SourceDocument current=prepareSourceStamp(captured,module);
        if(selected){require(Objects.equals(binding.getOriginalSourceSha256(),current.getSourceSha256())
                &&Objects.equals(binding.getJavaParsedSourceHash(),VettingCorpus.sourceHash(captured))
                &&Objects.equals(binding.getRoutingSha256(),CanonicalEvidenceService.routingHash(current)),"CANONICAL_SOURCE_STALE");
        Set<String> eligible=new HashSet<>();
        try{com.fasterxml.jackson.databind.JsonNode declared=JsonUtils.parse(binding.getEligibleModulesJson());require(declared.isArray()&&declared.size()>0,"CANONICAL_ELIGIBILITY_INVALID");
            for(com.fasterxml.jackson.databind.JsonNode item:declared)require(item.isTextual()&&Arrays.asList("drafting","vetting","advisory").contains(item.asText())&&eligible.add(item.asText()),"CANONICAL_ELIGIBILITY_INVALID");}
        catch(CanonicalEvidenceException rejected){throw rejected;}catch(RuntimeException invalid){throw failure("CANONICAL_ELIGIBILITY_INVALID");}
        require(eligible.contains(module),"CANONICAL_MODULE_NOT_ELIGIBLE");}
        String actual=Utf8.sha256(binding.getSnapshotJson());require(actual.equals(binding.getSnapshotBytesSha256()),"CANONICAL_STORED_BYTES_CHANGED");
        LinkedHashMap<String,Object> pins=bindingPins(binding);
        String existing=readBindingStamp(pins);
        if(existing==null){
            require(prepare("canonical_source_binding","snapshot_content_sha256",pins)==1,"CANONICAL_COMPACT_BINDING_CHANGED");
            existing=readBindingStamp(pins);
        }
        require(existing!=null,"SOURCE_CONTENT_REVISION_UNAVAILABLE");
        require(actual.equals(existing),"CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE");
        sources.requireCurrentStamp(captured,sources.validatedScope(captured.getProjectId(),captured.getId(),module));return existing;
    }

    private int prepare(String table,String digest,LinkedHashMap<String,Object> pins){
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)connection->{
            boolean mysql=mysql(connection);String marker="source_document".equals(table)?"source-content-prepare-v1":"source-binding-prepare-v1";
            StringBuilder sql=new StringBuilder("update ").append(table).append(" set ").append(digest).append("='").append(marker).append("' where ").append(digest).append(" is null");
            for(String name:pins.keySet())sql.append(" and ").append(equal(name,mysql));
            try(PreparedStatement statement=connection.prepareStatement(sql.toString())){
                int index=1;for(Object value:pins.values())statement.setObject(index++,value);
                // The row lock plus the trigger's freshly read digest proves content equality before commit, without uploading its LOBs again.
                return statement.executeUpdate();
            }
        });
    }
    private String readBindingStamp(LinkedHashMap<String,Object> pins){
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)connection->{
            StringBuilder sql=new StringBuilder("select snapshot_content_sha256 from canonical_source_binding where 1=1");
            for(String name:pins.keySet())sql.append(" and ").append(equal(name,mysql(connection)));
            try(PreparedStatement statement=connection.prepareStatement(sql.toString())){
                int index=1;for(Object value:pins.values())statement.setObject(index++,value);
                try(ResultSet result=statement.executeQuery()){require(result.next(),"CANONICAL_COMPACT_BINDING_CHANGED");String stamp=result.getString(1);require(!result.next(),"CANONICAL_COMPACT_BINDING_CHANGED");return stamp;}
            }
        });
    }
    private static boolean mysql(Connection connection)throws SQLException {String product=connection.getMetaData().getDatabaseProductName();require(Arrays.asList("H2","MySQL","MariaDB").contains(product),"SOURCE_CONTENT_DATABASE_UNSUPPORTED");return !"H2".equals(product);}
    private static String equal(String name,boolean mysql){return mysql?(Arrays.asList("id","document_id","created_at").contains(name)?"("+name+" <=> ?)":"(BINARY "+name+" <=> BINARY ?)"):name+" is not distinct from ?";}
    private static LinkedHashMap<String,Object> sourcePins(SourceDocument d){
        LinkedHashMap<String,Object> p=new LinkedHashMap<>();p.put("id",d.getId());p.put("project_id",d.getProjectId());p.put("category",d.getCategory());
        p.put("file_name",d.getFileName());p.put("file_key",d.getFileKey());p.put("review_role",d.getReviewRole());p.put("document_scope",d.getDocumentScope());
        p.put("parse_status",d.getParseStatus());p.put("oer_identity_json",d.getOerIdentityJson());p.put("source_sha256",d.getSourceSha256());p.put("storage_path",d.getStoragePath());return p;
    }
    private static LinkedHashMap<String,Object> bindingPins(CanonicalSourceBinding b){
        LinkedHashMap<String,Object> p=new LinkedHashMap<>();p.put("id",b.getId());p.put("project_id",b.getProjectId());p.put("document_id",b.getDocumentId());
        p.put("projection_sha256",b.getProjectionSha256());p.put("original_source_sha256",b.getOriginalSourceSha256());p.put("java_parsed_source_hash",b.getJavaParsedSourceHash());
        p.put("routing_sha256",b.getRoutingSha256());p.put("source_revision_sha256",b.getSourceRevisionSha256());p.put("projection_revision_sha256",b.getProjectionRevisionSha256());
        p.put("canonical_source_hash",b.getCanonicalSourceHash());p.put("scope_policy_sha256",b.getScopePolicySha256());p.put("adoption_manifest_sha256",b.getAdoptionManifestSha256());
        p.put("review_reference_sha256",b.getReviewReferenceSha256());p.put("family_composition_sha256",b.getFamilyCompositionSha256());p.put("family_component_set_sha256",b.getFamilyComponentSetSha256());
        p.put("snapshot_bytes_sha256",b.getSnapshotBytesSha256());p.put("declared_module",b.getDeclaredModule());p.put("eligible_modules_json",b.getEligibleModulesJson());
        p.put("created_at",Timestamp.from(b.getCreatedAt()));return p;
    }
    private static void require(boolean condition,String reason){if(!condition)throw failure(reason);}
    private static CanonicalEvidenceException failure(String reason){return new CanonicalEvidenceException(HttpStatus.CONFLICT,reason);}
}
