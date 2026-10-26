package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.config.ConsenseProperties;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.NormalSourceCandidateGraph;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Source-bound registry adoption and asset return; does not alter either retrieval pipeline. */
@Service
public class CanonicalEvidenceService {
    private final CanonicalSourceBindingRepository bindings;
    private final CanonicalEvidenceBackend backend;
    private final CanonicalCurrentSourceReader sources;
    @Autowired private org.springframework.beans.factory.ObjectProvider<CanonicalEvidenceService> boundaryProxy;
    @Autowired private NormalObservedPreparationService normalPreparation;
    @Autowired private SourceContentDigestPreparation digestPreparation;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;

    @Autowired public CanonicalEvidenceService(CanonicalSourceBindingRepository bindings,CanonicalEvidenceBackend backend,CanonicalCurrentSourceReader sources){
        this.bindings=bindings;this.backend=backend;this.sources=sources;
    }

    /** Direct fixtures/probes do not apply Spring transaction advice; production uses the injected reader proxy. */
    public CanonicalEvidenceService(SourceDocumentRepository documents,CanonicalSourceBindingRepository bindings,CanonicalEvidenceBackend backend,ConsenseProperties properties){
        this(bindings,backend,new CanonicalCurrentSourceReader(documents,bindings,properties));
    }
    private static final List<String> MODULES=Arrays.asList("drafting","vetting","advisory");
    private static final String INPUT_CONTRACT="canonical-source-chunks-upstream-scope-and-parser-projection-v1";
    private static final String ASSET_CONTRACT="source-and-projection-bound-content-addressed-assets-v1";

    /** Normal observations have independent module registries; reviewed adoptions retain their shared authority. */
    static List<CanonicalSourceBinding> latestForModule(List<CanonicalSourceBinding> revisions,String module){
        for(CanonicalSourceBinding binding:revisions){
            if(!Objects.equals(module,binding.getDeclaredModule())){
                JsonNode snapshot=parse(binding.getSnapshotJson());
                if(NormalObservedContract.isNormal(snapshot)){
                    require(binding.getSnapshotJson()!=null&&Utf8.sha256(binding.getSnapshotJson()).equals(binding.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
                    validateBindingPins(binding,snapshot);continue;
                }
            }
            return Collections.singletonList(binding);
        }
        return Collections.emptyList();
    }
    static List<CanonicalSourceBinding> latestForModule(CanonicalSourceBindingRepository repository,javax.persistence.EntityManager entityManager,String projectId,Long documentId,String module){
        org.springframework.data.domain.Pageable one=org.springframework.data.domain.PageRequest.of(0,1);Long before=null;
        while(true){
            List<CanonicalSourceBinding> page=before==null?repository.findByProjectIdAndDocumentIdOrderByIdDesc(projectId,documentId,one)
                    :repository.findByProjectIdAndDocumentIdAndIdLessThanOrderByIdDesc(projectId,documentId,before,one);
            if(page.isEmpty())return Collections.emptyList();
            require(page.size()==1,HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID");
            List<CanonicalSourceBinding> selected=latestForModule(page,module);if(!selected.isEmpty())return selected;
            CanonicalSourceBinding skipped=page.get(0);Long next=skipped.getId();
            require(next!=null&&(before==null||next<before),HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID");
            // Only validated foreign normal state is skipped; selected authority stays managed.
            if(entityManager!=null&&entityManager.contains(skipped))entityManager.detach(skipped);
            before=next;
        }
    }
    /** Warm guards read scalar revisions; only a foreign normal revision needs its schema inspected. */
    private CanonicalSourceBinding latestCompactForModule(String projectId,Long documentId,String module){
        return latestCompactForModule(projectId,documentId,module,false);
    }
    private CanonicalSourceBinding latestCompactForModule(String projectId,Long documentId,String module,boolean displayOnly){
        org.springframework.data.domain.Pageable one=org.springframework.data.domain.PageRequest.of(0,1);Long before=null;
        while(true){
            List<CanonicalSourceBindingRepository.CompactMetadata> page=before==null?bindings.findCompactByDocument(projectId,documentId,one)
                    :bindings.findCompactByDocumentBefore(projectId,documentId,before,one);
            if(page.isEmpty())return null;
            require(page.size()==1,HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID");
            CanonicalSourceBinding compact=compactBinding(page.get(0));
            if(Objects.equals(module,compact.getDeclaredModule()))return compact;
            if(displayOnly){
                if(!displayNormalSnapshot(compact))return compact;
                Long next=compact.getId();require(next!=null&&(before==null||next<before),HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID");
                before=next;continue;
            }
            CanonicalSourceBinding foreign=bindings.findById(compact.getId())
                    .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID"));
            CanonicalSourceBinding foreignScalars=copyBinding(foreign);foreignScalars.setSnapshotJson(null);
            require(bindingState(compact).equals(bindingState(foreignScalars)),HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID");
            if(!latestForModule(Collections.singletonList(foreign),module).isEmpty())return compact;
            Long next=compact.getId();require(next!=null&&(before==null||next<before),HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID");
            if(entityManager!=null&&entityManager.contains(foreign))entityManager.detach(foreign);
            before=next;
        }
    }
    /** Display classification only; explicit preparation and evidence reads retain full validation. */
    private boolean displayNormalSnapshot(CanonicalSourceBinding captured){
        final String unavailable="CANONICAL_AUTHORITY_INSPECTION_REQUIRED";
        CanonicalSourceBinding before=compactBinding(bindings.findCompactById(captured.getId())
            .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.CONFLICT,unavailable)));
        require(bindingState(captured).equals(bindingState(before))&&before.getSnapshotBytesSha256()!=null
            &&before.getSnapshotBytesSha256().equals(before.getSnapshotContentSha256()),HttpStatus.CONFLICT,unavailable);
        boolean adoption=validDigest(before.getAdoptionManifestSha256())&&validDigest(before.getReviewReferenceSha256())
            &&before.getFamilyCompositionSha256()==null&&before.getFamilyComponentSetSha256()==null;
        boolean family=validDigest(before.getFamilyCompositionSha256())&&validDigest(before.getFamilyComponentSetSha256())
            &&before.getAdoptionManifestSha256()==null&&before.getReviewReferenceSha256()==null;
        boolean unreviewed=before.getAdoptionManifestSha256()==null&&before.getReviewReferenceSha256()==null
            &&before.getFamilyCompositionSha256()==null&&before.getFamilyComponentSetSha256()==null;
        require(adoption||family||unreviewed,HttpStatus.CONFLICT,unavailable);
        // Registration validated these positive review pins against these exact bytes; display need not find a late header again.
        boolean normal=false;
        if(unreviewed){
            require(entityManager!=null,HttpStatus.CONFLICT,unavailable);
            normal=entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection->{
                try(java.sql.PreparedStatement statement=connection.prepareStatement("select snapshot_json,snapshot_bytes_sha256,snapshot_content_sha256 from canonical_source_binding where id=?")){
                    statement.setLong(1,before.getId());
                    try(java.sql.ResultSet rows=statement.executeQuery()){
                        require(rows.next()&&before.getSnapshotBytesSha256().equals(rows.getString(2))
                            &&before.getSnapshotBytesSha256().equals(rows.getString(3)),HttpStatus.CONFLICT,unavailable);
                        try(Reader source=rows.getCharacterStream(1)){require(source!=null,HttpStatus.CONFLICT,unavailable);return normalDisplayHeader(source);}
                    }
                }catch(IOException invalid){throw new CanonicalEvidenceException(HttpStatus.CONFLICT,unavailable);}
            });
            require(normal,HttpStatus.CONFLICT,unavailable);
        }
        CanonicalSourceBinding after=compactBinding(bindings.findCompactById(captured.getId())
            .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.CONFLICT,unavailable)));
        require(bindingState(before).equals(bindingState(after)),HttpStatus.CONFLICT,unavailable);
        return normal;
    }
    private static boolean validDigest(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    static boolean normalDisplayHeader(Reader source)throws IOException{
        final String unavailable="CANONICAL_AUTHORITY_INSPECTION_REQUIRED";
        Reader bounded=new FilterReader(source){int remaining=16384;
            @Override public int read(char[] value,int offset,int length)throws IOException{if(remaining==0)throw new IOException("Authority header exceeds the display budget");int count=super.read(value,offset,Math.min(length,remaining));if(count>0)remaining-=count;return count;}
            @Override public int read()throws IOException{if(remaining==0)throw new IOException("Authority header exceeds the display budget");int value=super.read();if(value>=0)remaining--;return value;}
        };
        try(com.fasterxml.jackson.core.JsonParser parser=JsonUtils.mapper().getFactory().createParser(bounded)){
            require(parser.nextToken()==com.fasterxml.jackson.core.JsonToken.START_OBJECT,HttpStatus.CONFLICT,unavailable);
            String schema=null,producer=null;Set<String> fields=new HashSet<>();
            while(parser.nextToken()==com.fasterxml.jackson.core.JsonToken.FIELD_NAME){
                String field=parser.currentName();require(fields.add(field),HttpStatus.CONFLICT,unavailable);com.fasterxml.jackson.core.JsonToken token=parser.nextToken();
                if("schema".equals(field)||"producerKind".equals(field)){
                    require(token==com.fasterxml.jackson.core.JsonToken.VALUE_STRING,HttpStatus.CONFLICT,unavailable);
                    if("schema".equals(field))schema=parser.getText();else producer=parser.getText();
                }else parser.skipChildren();
                if("consense.canonical-projection.v1".equals(schema)||NativeDocumentCollectionContract.SCHEMA.equals(schema))return false;
                if(schema!=null&&producer!=null){
                    if(NormalObservedContract.SCHEMA.equals(schema)&&NormalObservedContract.PRODUCER.equals(producer))return true;
                    require(NativeCanonicalContract.SCHEMA.equals(schema)&&NativeCanonicalContract.PRODUCER.equals(producer)
                        ||NativePdfCanonicalContract.SCHEMA.equals(schema)&&NativePdfCanonicalContract.PRODUCER.equals(producer)
                        ||NativePdfSourceFamilyContract.SCHEMA.equals(schema)&&NativePdfSourceFamilyContract.PRODUCER.equals(producer),HttpStatus.CONFLICT,unavailable);
                    return false;
                }
            }
        }
        throw new CanonicalEvidenceException(HttpStatus.CONFLICT,unavailable);
    }

    /** Prepare only unregistered sources before callers freeze a run; existing reviewed/normal bindings keep their authority. */
    public JsonNode prepareNormalCandidate(String projectId,Long documentId,String module){
        validModule(module);
        if(latestCompactForModule(projectId,documentId,module,true)!=null)return null;
        return normalPreparation.prepare(projectId,documentId,module);
    }

    /** Explicit normal-parser preparation, independent of the reviewed registration authority. */
    public boolean prepareNormalObserved(String projectId,Long documentId,String module){
        validModule(module);
        List<CanonicalSourceBinding> revisions=latestForModule(bindings,entityManager,projectId,documentId,module);
        if(!revisions.isEmpty()){boundary().snapshot(projectId,documentId,revisions.get(0).getProjectionSha256(),module);return false;}
        CanonicalSourceBinding binding=prepareNormalBinding(projectId,documentId,module);
        boundary().saveNormalBinding(binding,module);return true;
    }
    /** Registration only; Vetting immediately opens its owned semantic frame before consuming evidence. */
    public boolean prepareNormalObservedForSemanticRead(String projectId,Long documentId,String module){
        require("vetting".equals(module),HttpStatus.BAD_REQUEST,"CANONICAL_SEMANTIC_PREPARATION_MODULE");
        if(latestCompactForModule(projectId,documentId,module)!=null)return false;
        CanonicalSourceBinding binding=prepareNormalBinding(projectId,documentId,module);
        boundary().saveNormalBinding(binding,module);return true;
    }
    /** One worker-owned source read covers preparation and the immediately following semantic frame. */
    public SourceDocument prepareVettingSource(SourceDocument expected){
        require(expected!=null&&SourceContentDigests.stamped(expected),HttpStatus.CONFLICT,"SOURCE_CONTENT_REVISION_UNAVAILABLE");
        String project=expected.getProjectId();Long id=expected.getId();
        CanonicalSourceBinding existing=latestCompactForModule(project,id,"vetting",true);
        SourceDocument source=existing==null?normalPreparation.prepareSource(project,id,"vetting",expected):sources.normalDocument(project,id,"vetting");
        if(existing!=null)require(ProjectDocumentScope.sameScopeRevision(expected,source)
                &&SourceContentDigests.stamped(source)&&Objects.equals(expected.getBusinessContentSha256(),source.getBusinessContentSha256()),
                HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        if(existing==null){
            CanonicalSourceBinding binding=normalBinding(project,id,"vetting",source,NormalObservedPreparationService.currentGraph(source));
            boundary().savePreparedVettingBinding(binding,source);
        }
        return source;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void savePreparedVettingBinding(CanonicalSourceBinding binding,SourceDocument source){
        require("vetting".equals(binding.getDeclaredModule()),HttpStatus.CONFLICT,"CANONICAL_SEMANTIC_PREPARATION_MODULE");
        sources.assertSourceStampCurrent(binding,"vetting",source);
        require(latestCompactForModule(binding.getProjectId(),binding.getDocumentId(),"vetting",true)==null,
                HttpStatus.CONFLICT,"CANONICAL_BINDING_CHANGED_DURING_PREPARATION");
        bindings.saveAndFlush(binding);sources.assertSourceStampCurrent(binding,"vetting",source);
    }
    /** Finish the temporary complete graph/view scope before independent persistence validation opens another tree. */
    private CanonicalSourceBinding prepareNormalBinding(String projectId,Long documentId,String module){
        JsonNode graph=normalPreparation.prepare(projectId,documentId,module);
        SourceDocument source=sources.normalDocument(projectId,documentId,module);
        require(graph.equals(NormalObservedPreparationService.currentGraph(source)),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        return normalBinding(projectId,documentId,module,source,graph);
    }
    private CanonicalSourceBinding normalBinding(String projectId,Long documentId,String module,SourceDocument source,JsonNode graph){
        JsonNode health=parse(backend.normalHealth(module)).path("oer");NormalObservedContract.validateGraphCapability(health,graph);
        require(INPUT_CONTRACT.equals(health.path("inputContract").asText())&&ASSET_CONTRACT.equals(health.path("assetReturnContract").asText()),HttpStatus.BAD_GATEWAY,"OER_CANONICAL_CAPABILITY_MISMATCH");
        com.fasterxml.jackson.databind.node.ObjectNode sourceBinding=JsonUtils.mapper().createObjectNode();OerDocumentIdentity identity=OerSourceMetadata.identity(source);
        sourceBinding.put("module",module);sourceBinding.set("eligibleModules",JsonUtils.mapper().valueToTree(identity.getEligibleModules()));sourceBinding.put("sourcePurpose",identity.getSourcePurpose());
        String sourceClass=VettingCorpus.sourceClass(source);sourceBinding.put("sourceClass",sourceClass==null?identity.getKind().toLowerCase(Locale.ROOT):sourceClass);
        sourceBinding.put("routingSha256",routingHash(source));sourceBinding.set("scopePolicySha256",health.path("scopePolicy").path("manifestSha256"));validSha(sourceBinding.path("scopePolicySha256").asText());
        com.fasterxml.jackson.databind.node.ObjectNode request=JsonUtils.mapper().createObjectNode();request.set("candidateGraph",graph);request.set("sourceBinding",sourceBinding);
        boolean requestBound=NormalObservedContract.requestBoundPublication(health);
        if(requestBound)request.put("responseFormat",NormalObservedContract.REQUEST_BOUND_FORMAT);
        if(SourceContentDigests.stamped(source))sources.requireCurrentStamp(source,sources.currentStamp(projectId,documentId,module));
        else require(NormalObservedPreparationService.sameGeneration(source,sources.normalDocument(projectId,documentId,module)),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        NormalObservedContract.Publication publication=NormalObservedContract.publication(
            CanonicalJson.normalObservedReceipt(backend.normalObservedReply(JsonUtils.write(request),module).bytes),graph,sourceBinding,requestBound);
        String raw=publication.raw;JsonNode receiptNode=publication.header,projection=publication.projection;
        projection=NormalObservedContract.validate(raw,projection.path("projectionSha256").asText(),backend.normalMaximumBytes(),projection);
        require(graph.equals(projection.path("manifest").path("candidateGraph"))&&sourceBinding.equals(projection.path("manifest").path("sourceBinding")),HttpStatus.CONFLICT,"NORMAL_OBSERVED_SOURCE_BINDING_MISMATCH");
        require(receiptNode.path("projectionSha256").equals(projection.path("projectionSha256"))&&receiptNode.path("chunks").asInt(-1)==projection.path("chunks").size()
                &&receiptNode.path("immutable").asBoolean()&&!receiptNode.path("qualityVerifiedByRegistry").asBoolean(true)&&!receiptNode.path("sourceQualityChangedByRegistry").asBoolean(true),HttpStatus.BAD_GATEWAY,"NORMAL_OBSERVED_RECEIPT_MISMATCH");
        CanonicalSourceBinding binding=new CanonicalSourceBinding();binding.setProjectId(projectId);binding.setDocumentId(documentId);binding.setProjectionSha256(projection.path("projectionSha256").asText());
        binding.setOriginalSourceSha256(source.getSourceSha256());binding.setJavaParsedSourceHash(VettingCorpus.sourceHash(source));binding.setRoutingSha256(routingHash(source));
        binding.setSourceRevisionSha256(projection.path("source").path("sourceRevisionSha256").asText());binding.setProjectionRevisionSha256(projection.path("projectionRevisionSha256").asText());binding.setCanonicalSourceHash(projection.path("sourceHash").asText());
        binding.setScopePolicySha256(sourceBinding.path("scopePolicySha256").asText());binding.setDeclaredModule(module);binding.setEligibleModulesJson(sourceBinding.path("eligibleModules").toString());binding.setSnapshotJson(raw);binding.setSnapshotBytesSha256(Utf8.sha256(raw));
        assertPreparedNormalSourceCurrent(binding,module,source);
        return binding;
    }
    /** The initial complete source/graph proof is already checked; only absent legacy stamps use that full check again. */
    private void assertPreparedNormalSourceCurrent(CanonicalSourceBinding binding,String module,SourceDocument source){
        try{sources.assertSourceStampCurrent(binding,module,source);}
        catch(CanonicalEvidenceException missing){
            if(missing.getStatus()!=HttpStatus.CONFLICT||!"SOURCE_CONTENT_REVISION_UNAVAILABLE".equals(missing.getMessage()))throw missing;
            sources.assertSourceCurrent(binding,module);
        }
    }
    private CanonicalEvidenceService boundary(){return boundaryProxy==null?this:boundaryProxy.getObject();}
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void saveNormalBinding(CanonicalSourceBinding binding,String module){
        JsonNode snapshot=validatedSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());require(NormalObservedContract.isNormal(snapshot),HttpStatus.BAD_REQUEST,"NORMAL_OBSERVED_AUTHORITY_MISMATCH");validateBindingPins(binding,snapshot);sources.assertSourceCurrent(binding,module);
        List<CanonicalSourceBinding> latest=latestForModule(bindings,entityManager,binding.getProjectId(),binding.getDocumentId(),module);
        require(latest.isEmpty(),HttpStatus.CONFLICT,"CANONICAL_BINDING_CHANGED_DURING_PREPARATION");bindings.saveAndFlush(binding);
        sources.assertSourceCurrent(binding,module);
    }
    public boolean normalPreparable(String projectId,List<Long> ids,String module){
        NormalObservedContract.validateCapability(parse(backend.normalHealth(module)).path("oer"));
        boolean missing=false;
        for(Long id:ids){List<CanonicalSourceBinding> existing=latestForModule(bindings,entityManager,projectId,id,module);
            if(!existing.isEmpty()){boundary().snapshot(projectId,id,existing.get(0).getProjectionSha256(),module);continue;}
            SourceDocument source=sources.normalDocument(projectId,id,module);NormalObservedPreparationService.requireEvidence(source);missing=true;
        }return missing;
    }

    /** Internal implementation API only. All expectations must come from the explicit source-review handoff. */
    public static class ReviewedRegistration {
        public final String projectionSha256,originalSourceSha256,javaParsedSourceHash;
        public final String sourceRevisionSha256,scopePolicySha256,adoptionManifestSha256,reviewReferenceSha256,module;
        public final String familyCompositionSha256,familyComponentSetSha256;
        public ReviewedRegistration(String projection,String original,String parsed,String revision,String policy,String adoption,String reference,String module){
            this(projection,original,parsed,revision,policy,adoption,reference,null,null,module);
        }
        public ReviewedRegistration(String projection,String original,String parsed,String revision,String policy,String adoption,String reference,String composition,String componentSet,String module){
            this.projectionSha256=projection;this.originalSourceSha256=original;this.javaParsedSourceHash=parsed;this.sourceRevisionSha256=revision;
            this.scopePolicySha256=policy;this.adoptionManifestSha256=adoption;this.reviewReferenceSha256=reference;
            this.familyCompositionSha256=composition;this.familyComponentSetSha256=componentSet;this.module=module;
        }
    }
    public CanonicalSourceBinding registerReviewed(String projectId,Long documentId,ReviewedRegistration review) {
        require(review!=null,HttpStatus.BAD_REQUEST,"EXPLICIT_SOURCE_REVIEW_REQUIRED");
        for(String sha:Arrays.asList(review.projectionSha256,review.originalSourceSha256,review.javaParsedSourceHash,
                review.sourceRevisionSha256,review.scopePolicySha256))validSha(sha);
        validModule(review.module);
        SourceDocument document=currentDocument(projectId,documentId,review.module);
        require(review.originalSourceSha256.equals(document.getSourceSha256())&&review.javaParsedSourceHash.equals(VettingCorpus.sourceHash(document)),
                HttpStatus.CONFLICT,"SOURCE_REVIEW_REVISION_STALE");
        JsonNode healthRoot=parse(backend.health()),health=healthRoot.path("oer");
        require(INPUT_CONTRACT.equals(health.path("inputContract").asText())&&ASSET_CONTRACT.equals(health.path("assetReturnContract").asText())
                &&"/project".equals(health.path("canonicalProjectionEndpoint").asText())
                &&review.scopePolicySha256.equals(health.path("scopePolicy").path("manifestSha256").asText()),HttpStatus.BAD_GATEWAY,"OER_CANONICAL_CAPABILITY_MISMATCH");
        String raw=backend.snapshot(review.projectionSha256,review.module);JsonNode snapshot=validatedSnapshot(raw,review.projectionSha256);
        if(NativePdfSourceFamilyContract.isFamily(snapshot)){
            NativePdfSourceFamilyContract.validateCapability(health);
            NativePdfSourceFamilyContract.validateAlgorithms(healthRoot.path("retrieval").path("sourceWindows").path("algorithms"));
        }else if(NativePdfCanonicalContract.isNativePdf(snapshot)){
            NativePdfCanonicalContract.validateCapability(health);
            NativePdfCanonicalContract.validateAlgorithms(healthRoot.path("retrieval").path("sourceWindows").path("algorithms"),snapshot.path("projectionIdentity"));
        }
        if(NativeCanonicalContract.isNative(snapshot)||NativeDocumentCollectionContract.isCollection(snapshot)){
            NativeCanonicalContract.validateCapability(health);
            NativeCanonicalContract.validateAlgorithms(healthRoot.path("retrieval").path("sourceWindows").path("algorithms"));
            if(NativeDocumentCollectionContract.isCollection(snapshot)){
                NativeDocumentCollectionContract.validateCapability(health,healthRoot.path("retrieval").path("sourceWindows").path("algorithms"));
                NativeDocumentCollectionContract.readDocument(raw,review.projectionSha256,
                    ordinal->backend.batch(review.projectionSha256,ordinal,review.module).bytes,
                    ordinal->backend.envelope(review.projectionSha256,ordinal,review.module).bytes);
            }
        }
        JsonNode identity=snapshot.path("projectionIdentity"),manifest=manifest(snapshot);
        // The producer must use the actual database ID. Matching source bytes alone never aliases two uploaded documents.
        require(String.valueOf(documentId).equals(identity.path("documentId").asText())&&String.valueOf(documentId).equals((NativePdfSourceFamilyContract.isFamily(snapshot)?manifest.path("spec"):manifest).path("documentId").asText()),
                HttpStatus.CONFLICT,"CANONICAL_DOCUMENT_BINDING_MISMATCH");
        require(review.originalSourceSha256.equals(snapshot.path("source").path("sourceSha256").asText())
                &&review.sourceRevisionSha256.equals(snapshot.path("source").path("sourceRevisionSha256").asText())
                &&review.scopePolicySha256.equals(manifest.path("scope").path("scopePolicySha256").asText())
                &&"codex".equals(manifest.path("reference").path("actor").asText()),HttpStatus.CONFLICT,"CANONICAL_SOURCE_REVIEW_MISMATCH");
        if(NativePdfSourceFamilyContract.isFamily(snapshot)){
            validSha(review.familyCompositionSha256);validSha(review.familyComponentSetSha256);
            require(review.adoptionManifestSha256==null&&review.reviewReferenceSha256==null
                    &&review.familyCompositionSha256.equals(identity.path("compositionSha256").asText())
                    &&review.familyComponentSetSha256.equals(identity.path("componentSetSha256").asText())
                    &&"component_identity_composition_review".equals(manifest.path("reference").path("method").asText()),HttpStatus.CONFLICT,"CANONICAL_FAMILY_REVIEW_MISMATCH");
        }else{
            validSha(review.adoptionManifestSha256);validSha(review.reviewReferenceSha256);
            require(review.familyCompositionSha256==null&&review.familyComponentSetSha256==null
                    &&review.adoptionManifestSha256.equals(adoptionHash(snapshot,raw))
                    &&review.adoptionManifestSha256.equals(identity.path(NativePdfCanonicalContract.isNativePdf(snapshot)?"adoptionManifestSha256":"manifestSha256").asText())
                    &&review.reviewReferenceSha256.equals(manifest.path("reference").path("evidenceSha256").asText())
                    &&"original_source_review".equals(manifest.path("reference").path("method").asText()),HttpStatus.CONFLICT,"CANONICAL_SOURCE_REVIEW_MISMATCH");
        }
        OerDocumentIdentity routed=OerSourceMetadata.identity(document);
        require(Objects.equals(routed.getSourcePurpose(),identity.path("sourcePurpose").asText())
                &&Objects.equals(VettingCorpus.sourceRole(document),identity.path("role").asText()),HttpStatus.CONFLICT,"CANONICAL_ROUTING_MISMATCH");
        Set<String> declared=modules(identity.path("eligibleModules"));
        require(declared.contains(review.module)&&routed.getEligibleModules().containsAll(declared)
                &&MODULES.contains(identity.path("module").asText()),HttpStatus.FORBIDDEN,"CANONICAL_MODULE_NOT_ELIGIBLE");
        CanonicalSourceBinding binding=new CanonicalSourceBinding();
        binding.setProjectId(projectId);binding.setDocumentId(documentId);binding.setProjectionSha256(review.projectionSha256);
        binding.setOriginalSourceSha256(review.originalSourceSha256);binding.setJavaParsedSourceHash(review.javaParsedSourceHash);
        binding.setRoutingSha256(routingHash(document));binding.setSourceRevisionSha256(review.sourceRevisionSha256);
        binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());
        binding.setScopePolicySha256(review.scopePolicySha256);binding.setAdoptionManifestSha256(review.adoptionManifestSha256);
        binding.setReviewReferenceSha256(review.reviewReferenceSha256);binding.setFamilyCompositionSha256(review.familyCompositionSha256);binding.setFamilyComponentSetSha256(review.familyComponentSetSha256);binding.setDeclaredModule(identity.path("module").asText());
        binding.setEligibleModulesJson(identity.path("eligibleModules").toString());binding.setSnapshotJson(raw);
        binding.setSnapshotBytesSha256(Utf8.sha256(raw));
        // Recheck bytes and current catalog after the remote call; a failed adoption never creates a binding.
        sources.assertSourceCurrent(binding,review.module);
        Optional<CanonicalSourceBinding> existing=bindings.findByProjectIdAndDocumentIdAndProjectionSha256(projectId,documentId,review.projectionSha256);
        if(existing.isPresent()){assertCurrent(existing.get(),review.module);return sameBinding(existing.get(),binding);}
        try{return bindings.saveAndFlush(binding);}catch(DataIntegrityViolationException race){
            Optional<CanonicalSourceBinding> concurrent=bindings.findByProjectIdAndDocumentIdAndProjectionSha256(projectId,documentId,review.projectionSha256);
            if(!concurrent.isPresent())throw race;return sameBinding(concurrent.get(),binding);
        }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public String snapshot(String projectId,Long documentId,String projection,String module){return readBinding(projectId,documentId,projection,module).getSnapshotJson();}

    /** Complete selected-document corpus, in caller order; never fall back from a newer stale adoption. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public List<CanonicalSourceBinding> currentBindings(String projectId,String module,List<Long> selectedDocumentIds){
        validModule(module);require(selectedDocumentIds!=null&&!selectedDocumentIds.isEmpty()
                &&!selectedDocumentIds.contains(null)&&new HashSet<>(selectedDocumentIds).size()==selectedDocumentIds.size(),HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
        List<CanonicalSourceBinding> result=new ArrayList<>();
        for(Long documentId:selectedDocumentIds){
            List<CanonicalSourceBinding> revisions=latestForModule(bindings,entityManager,projectId,documentId,module);
            require(!revisions.isEmpty(),HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENT_UNREGISTERED");
            result.add(readBinding(projectId,documentId,revisions.get(0).getProjectionSha256(),module));
        }
        return result;
    }

    /** Saved-run display checks committed scalar pins without loading selected snapshot CLOBs. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public List<CanonicalSourceBinding> currentDisplayBindings(String projectId,String module,List<SourceDocument> selected){
        return currentDisplayBindings(projectId,module,selected,null);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public List<CanonicalSourceBinding> currentDisplayBindings(String projectId,String module,List<SourceDocument> selected,Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originalPins){
        validModule(module);require(selected!=null&&!selected.isEmpty(),HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
        Map<Long,SourceDocument> metadata=originalPins==null?null:sources.currentModuleMetadata(projectId,module);
        Set<Long> ids=new HashSet<>();List<CanonicalSourceBinding> result=new ArrayList<>();
        for(SourceDocument source:selected){
            require(source!=null&&source.getId()!=null&&Objects.equals(projectId,source.getProjectId())&&ids.add(source.getId()),
                    HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
            CanonicalSourceBinding binding=latestCompactForModule(projectId,source.getId(),module,true);
            require(binding!=null,HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENT_UNREGISTERED");
            require(binding.getSnapshotBytesSha256()!=null&&binding.getSnapshotBytesSha256().equals(binding.getSnapshotContentSha256()),
                    HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE");
            SourceDocument current=metadata==null?sources.currentStamp(projectId,source.getId(),module):metadata.get(source.getId());
            require(current!=null,HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
            if(originalPins!=null)sources.requireOriginalFreshness(current,originalPins.get(source.getId()));
            require(SourceContentDigests.stamped(source)&&SourceContentDigests.stamped(current)&&ProjectDocumentScope.sameScopeRevision(source,current)
                    &&Objects.equals(source.getBusinessContentSha256(),current.getBusinessContentSha256())
                    &&Objects.equals(binding.getOriginalSourceSha256(),current.getSourceSha256())
                    &&Objects.equals(binding.getRoutingSha256(),routingHash(current)),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
            require(modules(JsonUtils.parse(binding.getEligibleModulesJson())).contains(module),HttpStatus.FORBIDDEN,"CANONICAL_MODULE_NOT_ELIGIBLE");
            result.add(binding);
        }
        return Collections.unmodifiableList(result);
    }

    /** Pins and validated pure content identities for one run; validity is checked anew at every boundary. */
    public static final class CompactBindingScope {
        private final CanonicalEvidenceService owner;
        private final String project,module;
        private final List<CanonicalSourceBinding> selected;
        private final Map<Long,List<List<Object>>> histories;
        private final Map<Long,SourceDocument> sources;
        private final Map<Long,CompactRegistryPin> registryPins;
        private CompactBindingScope(CanonicalEvidenceService owner,String project,String module,List<CanonicalSourceBinding> selected,Map<Long,List<List<Object>>> histories,Map<Long,SourceDocument> sources,Map<Long,CompactRegistryPin> registryPins){
            this.owner=owner;this.project=project;this.module=module;this.selected=selected;this.histories=histories;this.sources=sources;this.registryPins=registryPins;
        }
        public List<CanonicalSourceBinding> bindings(){List<CanonicalSourceBinding> result=new ArrayList<>();for(CanonicalSourceBinding binding:selected)result.add(copyBinding(binding));return Collections.unmodifiableList(result);}
        public String identitySha256(){List<Object> pins=new ArrayList<>();for(CanonicalSourceBinding binding:selected)pins.add(bindingState(binding));return VettingCorpus.hash(JsonUtils.write(pins));}
    }
    private static final class CompactRegistryPin {
        final String wireSha256;final boolean normal;
        final CanonicalCurrentSourceReader.OriginalFileState original;
        CompactRegistryPin(String wireSha256,boolean normal,CanonicalCurrentSourceReader.OriginalFileState original){this.wireSha256=wireSha256;this.normal=normal;this.original=original;}
    }
    /** Joined scopes retain scalar pins only; each cold frame has already been closed. */
    public CompactBindingScope joinCompactScopes(List<CompactBindingScope> scopes){
        require(scopes!=null&&!scopes.isEmpty(),HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
        CompactBindingScope first=scopes.get(0);List<CanonicalSourceBinding> selected=new ArrayList<>();
        Map<Long,List<List<Object>>> histories=new LinkedHashMap<>();Map<Long,SourceDocument> stamps=new LinkedHashMap<>();Map<Long,CompactRegistryPin> registryPins=new LinkedHashMap<>();
        for(CompactBindingScope scope:scopes){
            require(scope.owner==this&&scope.project.equals(first.project)&&scope.module.equals(first.module),HttpStatus.CONFLICT,"CANONICAL_COMPACT_SCOPE_OWNER_MISMATCH");
            for(CanonicalSourceBinding binding:scope.selected){Long id=binding.getDocumentId();
                require(stamps.putIfAbsent(id,scope.sources.get(id))==null,HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
                histories.put(id,scope.histories.get(id));registryPins.put(id,scope.registryPins.get(id));selected.add(binding);}
        }
        CompactBindingScope result=new CompactBindingScope(this,first.project,first.module,selected,histories,stamps,registryPins);assertCompactCurrent(result);return result;
    }
    /** Explicit cold preparation changes only previously absent, database-maintained fingerprints. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void prepareCompactIdentities(VerifiedBindingFrame frame,List<SourceDocument> captured){
        frame.requireOwner(this,frame.project,frame.module);Map<Long,SourceDocument> sourcesById=new HashMap<>();for(SourceDocument source:captured)sourcesById.put(source.getId(),source);
        for(BindingView retained:frame.views.values()){
            SourceDocument source=sourcesById.get(retained.binding.getDocumentId());require(source!=null,HttpStatus.CONFLICT,"CANONICAL_SOURCE_SNAPSHOT_MISSING");
            boolean warm=SourceContentDigests.stamped(source)&&SourceContentDigests.valid(retained.binding.getSnapshotContentSha256())
                    &&Objects.equals(retained.binding.getSnapshotContentSha256(),retained.binding.getSnapshotBytesSha256());
            if(!warm)digestPreparation.prepareSourceStamp(source,frame.module);
            Long before=null;org.springframework.data.domain.Pageable one=org.springframework.data.domain.PageRequest.of(0,1);
            while(true){List<CanonicalSourceBindingRepository.CompactMetadata> rows=before==null?bindings.findCompactByDocument(frame.project,source.getId(),one):bindings.findCompactByDocumentBefore(frame.project,source.getId(),before,one);
                require(rows.size()==1,HttpStatus.CONFLICT,"CANONICAL_BINDING_REVISION_ORDER_INVALID");CanonicalSourceBinding row=compactBinding(rows.get(0));if(Objects.equals(row.getId(),retained.binding.getId()))break;
                require(row.getId()!=null&&(before==null||row.getId()<before)&&row.getId()>retained.binding.getId()
                        &&!Objects.equals(row.getDeclaredModule(),frame.module),HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED");
                if(row.getSnapshotContentSha256()==null){
                    CanonicalSourceBinding legacy=bindings.findById(row.getId())
                        .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED"));
                    CanonicalSourceBinding scalars=copyBinding(legacy);scalars.setSnapshotJson(null);
                    require(bindingState(row).equals(bindingState(scalars))&&latestForModule(Collections.singletonList(legacy),frame.module).isEmpty(),HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED");
                    digestPreparation.prepareHistoricalBindingStamp(legacy,source,frame.module);
                    if(entityManager!=null&&entityManager.contains(legacy))entityManager.detach(legacy);
                }else require(row.getSnapshotContentSha256().equals(row.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE");
                // The final owned-frame guard still validates skipped foreign authority from its current bytes.
                before=row.getId();
            }
            String digest=warm?retained.binding.getSnapshotContentSha256():digestPreparation.prepareBindingStamp(retained.binding,source,frame.module);
            // The CAS proved that only this derived field changed; the existing frame still owns all source bytes.
            retained.binding.setSnapshotContentSha256(digest);
        }
        frame.beforeWait();
    }
    /** A cold owned frame has already checked the actual snapshots and source graphs. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public CompactBindingScope compactBindings(VerifiedBindingFrame frame,List<SourceDocument> captured){
        frame.requireOwner(this,frame.project,frame.module);Map<Long,SourceDocument> byId=new HashMap<>();for(SourceDocument source:captured)byId.put(source.getId(),source);
        List<CanonicalSourceBinding> selected=new ArrayList<>();Map<Long,List<List<Object>>> histories=new LinkedHashMap<>();Map<Long,SourceDocument> stamps=new LinkedHashMap<>();Map<Long,CompactRegistryPin> registryPins=new LinkedHashMap<>();
        for(CanonicalSourceBinding original:frame.bindings()){
            SourceDocument source=byId.get(original.getDocumentId());require(source!=null,HttpStatus.CONFLICT,"CANONICAL_SOURCE_SNAPSHOT_MISSING");
            SourceDocument stamp=frame.originalStates.containsKey(original.getDocumentId())?sources.currentVettingMetadata(frame.project).get(original.getDocumentId()):sources.currentStamp(frame.project,original.getDocumentId(),frame.module);
            require(stamp!=null,HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");sources.validateStampAgainstContent(source,stamp);
            stamp.setVerifiedBusinessContentSha256(SourceContentDigests.actual(source));stamps.put(original.getDocumentId(),stamp);
            CanonicalSourceBinding compact=copyBinding(original);compact.setSnapshotJson(null);
            List<List<Object>> history=compactHistory(frame.project,original.getDocumentId(),original.getId());
            require(!history.isEmpty()&&history.get(history.size()-1).equals(bindingState(compact)),HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED");
            BindingView view=frame.views.get(original.getDocumentId());registryPins.put(original.getDocumentId(),new CompactRegistryPin(view.registryWireSha256,NormalObservedContract.isNormal(view.snapshot),sources.observeOriginal(stamp)));
            histories.put(original.getDocumentId(),history);selected.add(compact);
        }
        return new CompactBindingScope(this,frame.project,frame.module,selected,histories,stamps,registryPins);
    }
    /** Closing each cold frame early must still preserve actual CAS proof after the shared prepare wait. */
    public void revalidateCompactRegistry(CompactBindingScope scope){
        require(scope!=null&&scope.owner==this,HttpStatus.CONFLICT,"CANONICAL_COMPACT_SCOPE_OWNER_MISMATCH");
        assertCompactCurrent(scope);
        for(CanonicalSourceBinding binding:scope.selected){
            CompactRegistryPin pin=scope.registryPins.get(binding.getDocumentId());
            CanonicalEvidenceBackend.Reply reply=pin==null||pin.wireSha256==null?null:backend.snapshotIfChanged(binding.getProjectionSha256(),scope.module,pin.wireSha256,pin.normal);
            if(reply==null||!reply.notModified){
                try(VerifiedBindingFrame checked=boundary().verifiedBindings(scope.project,scope.module,Collections.singletonList(binding.getDocumentId()))){
                    CanonicalSourceBinding current=checked.bindings().get(0);current.setSnapshotJson(null);
                    require(bindingState(binding).equals(bindingState(current)),HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED");
                }
            }
            assertCompactMemberCurrent(scope,binding);
        }
        assertCompactCurrent(scope);
    }
    /** Paid packets recheck their selected actual CAS, while the complete operation retains its final proof. */
    public void revalidateCompactSelection(CompactBindingScope scope,java.util.Set<Long> selectedIds){
        require(scope!=null&&scope.owner==this&&selectedIds!=null,HttpStatus.CONFLICT,"CANONICAL_COMPACT_SCOPE_OWNER_MISMATCH");
        assertCompactCurrent(scope);java.util.Set<Long> remaining=new java.util.HashSet<>(selectedIds);
        for(CanonicalSourceBinding binding:scope.selected)if(remaining.remove(binding.getDocumentId())){
            CompactRegistryPin pin=scope.registryPins.get(binding.getDocumentId());
            CanonicalEvidenceBackend.Reply reply=pin==null||pin.wireSha256==null?null:backend.snapshotIfChanged(binding.getProjectionSha256(),scope.module,pin.wireSha256,pin.normal);
            if(reply==null||!reply.notModified)try(VerifiedBindingFrame checked=boundary().verifiedBindings(scope.project,scope.module,Collections.singletonList(binding.getDocumentId()))){
                CanonicalSourceBinding current=checked.bindings().get(0);current.setSnapshotJson(null);
                require(bindingState(binding).equals(bindingState(current)),HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED");
            }
        }
        require(remaining.isEmpty(),HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENT_UNREGISTERED");assertCompactCurrent(scope);
    }
    private void assertCompactMemberCurrent(CompactBindingScope scope,CanonicalSourceBinding binding){
        sources.requireCurrentStamp(scope.sources.get(binding.getDocumentId()),sources.currentStamp(scope.project,binding.getDocumentId(),scope.module));
        require(scope.histories.get(binding.getDocumentId()).equals(compactHistory(scope.project,binding.getDocumentId(),binding.getId())),HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED");
    }
    /** New or changed revisions require a cold rebuild, including unfamiliar foreign normal revisions. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertCompactCurrent(CompactBindingScope scope){
        require(scope!=null&&scope.owner==this,HttpStatus.CONFLICT,"CANONICAL_COMPACT_SCOPE_OWNER_MISMATCH");
        if(!"vetting".equals(scope.module)){for(CanonicalSourceBinding binding:scope.selected)assertCompactMemberCurrent(scope,binding);return;}
        Map<Long,SourceDocument> current=sources.currentVettingMetadata(scope.project);
        for(CanonicalSourceBinding binding:scope.selected){
            SourceDocument source=current.get(binding.getDocumentId());require(source!=null,HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
            sources.requireCurrentStamp(scope.sources.get(binding.getDocumentId()),source);
            sources.requireOriginalFreshness(source,scope.registryPins.get(binding.getDocumentId()).original);
            require(scope.histories.get(binding.getDocumentId()).equals(compactHistory(scope.project,binding.getDocumentId(),binding.getId())),HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_CHANGED");
        }
    }
    private List<List<Object>> compactHistory(String project,Long document,Long selectedId){
        List<List<Object>> result=new ArrayList<>();Long before=null;org.springframework.data.domain.Pageable one=org.springframework.data.domain.PageRequest.of(0,1);
        while(true){List<CanonicalSourceBindingRepository.CompactMetadata> rows=before==null?bindings.findCompactByDocument(project,document,one):bindings.findCompactByDocumentBefore(project,document,before,one);
            require(rows.size()==1,HttpStatus.CONFLICT,"CANONICAL_COMPACT_BINDING_UNAVAILABLE");CanonicalSourceBinding value=compactBinding(rows.get(0));
            require(value.getId()!=null&&(before==null||value.getId()<before)&&value.getSnapshotContentSha256()!=null
                    &&value.getSnapshotContentSha256().equals(value.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE");
            result.add(bindingState(value));if(value.getId().equals(selectedId))return result;
            require(value.getId()>selectedId,HttpStatus.CONFLICT,"CANONICAL_COMPACT_SELECTED_BINDING_MISSING");before=value.getId();
        }
    }
    private static CanonicalSourceBinding compactBinding(CanonicalSourceBindingRepository.CompactMetadata row){
        CanonicalSourceBinding b=new CanonicalSourceBinding();b.setId(row.getId());b.setProjectId(row.getProjectId());b.setDocumentId(row.getDocumentId());b.setProjectionSha256(row.getProjectionSha256());
        b.setOriginalSourceSha256(row.getOriginalSourceSha256());b.setJavaParsedSourceHash(row.getJavaParsedSourceHash());b.setRoutingSha256(row.getRoutingSha256());b.setSourceRevisionSha256(row.getSourceRevisionSha256());
        b.setProjectionRevisionSha256(row.getProjectionRevisionSha256());b.setCanonicalSourceHash(row.getCanonicalSourceHash());b.setScopePolicySha256(row.getScopePolicySha256());b.setAdoptionManifestSha256(row.getAdoptionManifestSha256());
        b.setReviewReferenceSha256(row.getReviewReferenceSha256());b.setFamilyCompositionSha256(row.getFamilyCompositionSha256());b.setFamilyComponentSetSha256(row.getFamilyComponentSetSha256());b.setSnapshotBytesSha256(row.getSnapshotBytesSha256());
        b.setSnapshotContentSha256(row.getSnapshotContentSha256());b.setDeclaredModule(row.getDeclaredModule());b.setEligibleModulesJson(row.getEligibleModulesJson());b.setCreatedAt(row.getCreatedAt());return b;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public EvidencePointer pointer(String projectId,Long documentId,String projection,String unitId,String module){
        BindingView view=readBindingView(projectId,documentId,projection,module);CanonicalSourceBinding binding=view.binding;
        JsonNode snapshot=view.snapshot,chunk=null;JsonNode candidates=snapshot.path("chunks");
        for(JsonNode candidate:candidates)if(unitId.equals(candidate.path("unitId").asText())){require(chunk==null,HttpStatus.CONFLICT,"CANONICAL_UNIT_AMBIGUOUS");chunk=candidate;}
        require(chunk!=null,HttpStatus.NOT_FOUND,"CANONICAL_UNIT_UNAVAILABLE");
        assertCurrent(binding,module);
        EvidencePointer pointer=pointer(binding,snapshot,chunk,module,view.collection?NativeDocumentCollectionContract.unitUnknowns(snapshot,unitId):unknowns(snapshot));
        pointer.setNativeWordSourceScopesContext(wordSourceScopeContext(binding,snapshot,chunk));
        assertCurrent(binding,module);return pointer;
    }

    /** One actual selected model member; whole-corpus address enumeration never expands Word chapters. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public JsonNode wordSourceScopeContext(String projectId,Long documentId,String projection,String chunkId,String module){
        BindingView view=readBindingView(projectId,documentId,projection,module);
        JsonNode context=wordSourceScopeContext(view.binding,view.snapshot,chunkById(view.snapshot,chunkId));
        assertCurrent(view.binding,module);return context;
    }

    /** One source validation for all addresses, instead of hashing a large original once per chunk. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public List<EvidencePointer> pointers(String projectId,Long documentId,String projection,String module){
        BindingView view=readBindingView(projectId,documentId,projection,module);CanonicalSourceBinding binding=view.binding;
        JsonNode snapshot=view.snapshot;List<EvidencePointer> result=new ArrayList<>();
        if(view.collection){
            for(JsonNode chunk:snapshot.path("chunks"))result.add(pointer(binding,snapshot,chunk,module,NativeDocumentCollectionContract.unitUnknowns(snapshot,chunk.path("unitId").asText())));
            assertCurrent(binding,module);
        }else for(JsonNode chunk:snapshot.path("chunks"))result.add(pointer(binding,snapshot,chunk,module));
        return result;
    }

    /** Ordered complete parents from one immutable root. The stored DB snapshot remains the root only. */
    public JsonNode chunks(CanonicalSourceBinding binding,String module){
        validModule(module);
        require(binding!=null&&binding.getSnapshotJson()!=null,HttpStatus.BAD_REQUEST,"CANONICAL_BINDING_UNAVAILABLE");
        require(Utf8.sha256(binding.getSnapshotJson()).equals(binding.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        JsonNode snapshot=validatedSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());validateBindingPins(binding,snapshot);
        if(!NativeDocumentCollectionContract.isCollection(snapshot)){
            if(NormalObservedContract.isNormal(snapshot)||NativePdfSourceFamilyContract.isFamily(snapshot)||NativePdfCanonicalContract.isNativePdf(snapshot))recheckNativeBindingView(binding,module,NormalObservedContract.isNormal(snapshot),null,snapshot);
            return snapshot.path("chunks").deepCopy();
        }
        assertCurrent(binding,module);
        String current=backend.snapshot(binding.getProjectionSha256(),module);validatedSnapshot(current,binding.getProjectionSha256());
        require(CanonicalJson.hash(current,null).equals(CanonicalJson.hash(binding.getSnapshotJson(),null)),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        JsonNode result=NativeDocumentCollectionContract.readDocument(current,binding.getProjectionSha256(),
            ordinal->backend.batch(binding.getProjectionSha256(),ordinal,module).bytes,
            ordinal->backend.envelope(binding.getProjectionSha256(),ordinal,module).bytes).publicProjection.path("chunks");
        assertCurrent(binding,module);return result;
    }
    private static JsonNode manifest(JsonNode snapshot){return snapshot.path(NativeDocumentCollectionContract.isCollection(snapshot)?"manifestMetadata":"manifest");}
    private static String adoptionHash(JsonNode snapshot,String raw){
        if(NativePdfCanonicalContract.isNativePdf(snapshot))return snapshot.path("adoptionManifestRef").path("sha256").asText();
        return NativeDocumentCollectionContract.isCollection(snapshot)?snapshot.path("manifestSha256").asText():CanonicalJson.fieldHash(raw,"manifest");
    }
    private static JsonNode unknowns(JsonNode snapshot){
        if(NormalObservedContract.isNormal(snapshot))return snapshot.path("unknowns");
        return NativePdfSourceFamilyContract.isFamily(snapshot)?NativePdfSourceFamilyContract.unknowns(snapshot):NativePdfCanonicalContract.isNativePdf(snapshot)?snapshot.path("captureCompleteness").path("reference").path("unknowns"):snapshot.path("unknowns");
    }

    private static EvidencePointer pointer(CanonicalSourceBinding binding,JsonNode snapshot,JsonNode chunk,String module){
        return pointer(binding,snapshot,chunk,module,unknowns(snapshot));
    }
    private static EvidencePointer pointer(CanonicalSourceBinding binding,JsonNode snapshot,JsonNode chunk,String module,JsonNode unknowns){
        EvidencePointer pointer=new EvidencePointer();pointer.setProjectId(binding.getProjectId());pointer.setDocumentId(binding.getDocumentId());
        pointer.setOriginalSourceSha256(binding.getOriginalSourceSha256());pointer.setJavaParsedSourceHash(binding.getJavaParsedSourceHash());pointer.setSourceRevisionSha256(binding.getSourceRevisionSha256());
        pointer.setCanonicalSourceHash(binding.getCanonicalSourceHash());pointer.setProjectionSha256(binding.getProjectionSha256());pointer.setProjectionRevisionSha256(binding.getProjectionRevisionSha256());
        pointer.setModule(module);pointer.setUnitId(chunk.path("unitId").asText());pointer.setCanonicalChunkId(chunk.path("id").asText());
        boolean normal=NormalObservedContract.isNormal(snapshot),family=!normal&&NativePdfSourceFamilyContract.isFamily(snapshot),nativePdf=!normal&&!family&&NativePdfCanonicalContract.isNativePdf(snapshot);
        pointer.setSourceFilename(snapshot.path("source").path(nativePdf||family?"filename":"sourceFilename").deepCopy());pointer.setSourceOrder(chunk.path("sourceOrder").deepCopy());
        boolean collection=NativeDocumentCollectionContract.isCollection(snapshot);
        pointer.setProjectionSchema(collection?NativeCanonicalContract.SCHEMA:snapshot.path("schema").asText());
        if(normal){
            pointer.setAddressKind("normal_observed_unit");pointer.setProducerKind(NormalObservedContract.PRODUCER);pointer.setBboxConvention(null);
            pointer.setOriginalGeometryStatus("unknown");pointer.setVisualReturnStatus("unknown");
            List<Integer> observedPages=new ArrayList<>();for(JsonNode page:chunk.path("physicalPages"))observedPages.add(page.asInt());pointer.setPhysicalPages(observedPages);
            com.fasterxml.jackson.databind.node.ObjectNode context=NormalSourceCandidateGraph.pdfSourceGroupContext(snapshot.path("manifest").path("candidateGraph"),chunk.path("parts"));
            if(context!=null&&(!context.path("groups").isEmpty()||!context.path("issues").isEmpty())){
                JsonNode owner=context.path("source");
                require(Objects.equals(binding.getProjectId(),owner.path("projectId").asText())
                        &&Objects.equals(String.valueOf(binding.getDocumentId()),owner.path("documentId").asText())
                        &&Objects.equals(binding.getOriginalSourceSha256(),owner.path("originalSourceSha256").asText())
                        &&Objects.equals(binding.getOriginalSourceSha256(),context.path("sourceSha256").asText())
                        &&Objects.equals(binding.getJavaParsedSourceHash(),owner.path("parsedSourceHash").asText()),HttpStatus.BAD_GATEWAY,"NORMAL_PDF_SOURCE_GROUP_BINDING_MISMATCH");
                context.put("originalSourceSha256",binding.getOriginalSourceSha256());context.put("sourceRevisionSha256",binding.getSourceRevisionSha256());context.put("projectionSha256",binding.getProjectionSha256());
                pointer.setNativePdfSourceGroupsContext(context);
            }
        }else if(family){
            pointer.setProducerKind(NativePdfSourceFamilyContract.PRODUCER);pointer.setStorageShape(NativePdfSourceFamilyContract.STORAGE);
            pointer.setSourceRevisionKind(NativePdfSourceFamilyContract.REVISION);pointer.setFamilyCompositionSha256(binding.getFamilyCompositionSha256());pointer.setFamilyComponentSetSha256(binding.getFamilyComponentSetSha256());
            pointer.setSourceFamilyOriginRef(chunk.path("sourceFamilyOriginRef").deepCopy());pointer.setNativePdfSourceFamilyEvidence(NativePdfSourceFamilyContract.displayDeclarations(snapshot));
            pointer.setOriginalGeometryStatus(chunk.path("parts").get(0).path("geometryStatus").asText());pointer.setVisualReturnStatus(snapshot.path("visualReturnStatus").asText());
            pointer.setBboxConvention(chunk.path("parts").get(0).path("bboxConvention").asText());pointer.setPhysicalPages(new ArrayList<>(pages(chunk.path("physicalPages"))));
        }else if(nativePdf){
            pointer.setProducerKind(NativePdfCanonicalContract.PRODUCER);pointer.setStorageShape(snapshot.path("storageShape").asText());
            pointer.setOriginalGeometryStatus(chunk.path("parts").get(0).path("geometryStatus").asText());pointer.setVisualReturnStatus(snapshot.path("visualReturnStatus").asText());
            pointer.setBboxConvention(chunk.path("parts").get(0).path("bboxConvention").asText());pointer.setPhysicalPages(new ArrayList<>(pages(chunk.path("physicalPages"))));
            pointer.setNativePdfEvidence(NativePdfCanonicalContract.displayDeclarations(snapshot));
        }else if(NativeCanonicalContract.isNative(snapshot)||collection){
            pointer.setProducerKind(NativeCanonicalContract.PRODUCER);pointer.setStorageShape(collection?NativeCanonicalContract.STORAGE:snapshot.path("storageShape").asText());
            pointer.setOriginalGeometryStatus(chunk.path("originalGeometryStatus").asText());pointer.setVisualReturnStatus(snapshot.path("visualReturnStatus").asText());
            pointer.setBboxConvention(null);pointer.setPhysicalPages(new ArrayList<>());
        }else pointer.setPhysicalPages(new ArrayList<>(pages(chunk.path("physicalPages"))));
        pointer.setEligibleModules(chunk.path("eligibleModules").deepCopy());pointer.setParts(chunk.path("parts").deepCopy());pointer.setSourceSpans(chunk.path("sourceSpans").deepCopy());
        pointer.setTableGroup(chunk.has("tableGroup")?chunk.get("tableGroup").deepCopy():null);pointer.setAssetRefs(chunk.path("assetRefs").deepCopy());pointer.setRelations(chunk.path("relations").deepCopy());
        if(chunk.has("nativeTableGroupsRef"))pointer.setNativeTableGroupsRef(chunk.get("nativeTableGroupsRef").deepCopy());
        if(chunk.has("nativeTableSummary"))pointer.setNativeTableSummary(chunk.get("nativeTableSummary").deepCopy());
        if(chunk.has("sourceEditionId"))pointer.setSourceEditionId(chunk.path("sourceEditionId").asText());
        if(chunk.has("sourceUnitId"))pointer.setSourceUnitId(chunk.path("sourceUnitId").asText());
        if(chunk.has("slUnitScopeManifestSha256"))pointer.setSlUnitScopeManifestSha256(chunk.path("slUnitScopeManifestSha256").asText());
        pointer.setAdoption(chunk.has("adoption")?chunk.get("adoption").deepCopy():JsonUtils.mapper().nullNode());pointer.setSourceQuality(chunk.path("sourceQuality").deepCopy());pointer.setUnknowns(unknowns.deepCopy());
        return pointer;
    }

    private static JsonNode chunkById(JsonNode snapshot,String id){
        JsonNode selected=null;for(JsonNode chunk:snapshot.path("chunks"))if(Objects.equals(id,chunk.path("id").asText())){
            require(selected==null,HttpStatus.CONFLICT,"CANONICAL_CHUNK_AMBIGUOUS");selected=chunk;
        }
        require(selected!=null,HttpStatus.NOT_FOUND,"CANONICAL_CHUNK_UNAVAILABLE");return selected;
    }
    private static JsonNode wordSourceScopeContext(CanonicalSourceBinding binding,JsonNode snapshot,JsonNode chunk){
        return wordSourceScopeContext(binding,snapshot,chunk,null);
    }
    private static JsonNode wordSourceScopeContext(CanonicalSourceBinding binding,JsonNode snapshot,JsonNode chunk,NormalSourceCandidateGraph.SelectionIndex index){
        if(!NormalObservedContract.isNormal(snapshot))return null;
        JsonNode graph=snapshot.path("manifest").path("candidateGraph");
        com.fasterxml.jackson.databind.node.ObjectNode context=index==null?NormalSourceCandidateGraph.wordSourceScopeContext(graph,chunk.path("parts"))
                :NormalSourceCandidateGraph.wordSourceScopeContext(graph,chunk.path("parts"),index);
        if(context==null||context.path("ranges").isEmpty()&&context.path("issues").isEmpty())return null;
        JsonNode owner=context.path("source");
        require(Objects.equals(binding.getProjectId(),owner.path("projectId").asText())
                &&Objects.equals(String.valueOf(binding.getDocumentId()),owner.path("documentId").asText())
                &&Objects.equals(binding.getOriginalSourceSha256(),owner.path("originalSourceSha256").asText())
                &&Objects.equals(binding.getOriginalSourceSha256(),context.path("sourceSha256").asText())
                &&Objects.equals(binding.getJavaParsedSourceHash(),owner.path("parsedSourceHash").asText()),HttpStatus.BAD_GATEWAY,"NORMAL_WORD_SOURCE_SCOPE_BINDING_MISMATCH");
        context.put("originalSourceSha256",binding.getOriginalSourceSha256());context.put("sourceRevisionSha256",binding.getSourceRevisionSha256());context.put("projectionSha256",binding.getProjectionSha256());
        context.put("canonicalChunkId",chunk.path("id").asText());context.put("selectedPartsSha256",VettingCorpus.hash(JsonUtils.write(chunk.path("parts"))));return context;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public CanonicalEvidenceBackend.Reply asset(String projectId,Long documentId,String projection,String assetId,String module){
        CanonicalSourceBinding binding=readBinding(projectId,documentId,projection,module);JsonNode reference=null;
        for(JsonNode candidate:parse(binding.getSnapshotJson()).path("assetRefs"))if(assetId.equals(candidate.path("assetId").asText())){
            require(reference==null,HttpStatus.CONFLICT,"CANONICAL_ASSET_AMBIGUOUS");reference=candidate;
        }
        require(reference!=null,HttpStatus.NOT_FOUND,"CANONICAL_ASSET_NOT_BOUND");
        CanonicalEvidenceBackend.Reply reply=backend.asset(projection,binding.getOriginalSourceSha256(),assetId,module);
        String type=reply.contentType==null?"":reply.contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        require(binding.getOriginalSourceSha256().equals(reply.sourceSha256)&&projection.equals(reply.projectionSha256)
                &&("\""+reference.path("sha256").asText()+"\"").equals(reply.etag)
                &&type.equals(reference.path("mediaType").asText())&&reply.bytes.length==reference.path("byteCount").asLong(-1)
                &&CanonicalJson.sha(reply.bytes).equals(reference.path("sha256").asText())&&magic(type,reply.bytes),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_BYTES_MISMATCH");
        assertCurrent(binding,module);return reply;
    }


    /** Exact source proof is owned by one synchronous stage, never a request cache. */
    public static final class VerifiedBindingFrame implements AutoCloseable {
        private final CanonicalEvidenceService owner,boundary;
        private final Thread thread=Thread.currentThread();
        private final String project,module;
        private final Map<Long,BindingView> views=new LinkedHashMap<>();
        private final Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originalStates=new LinkedHashMap<>();
        private final Map<Long,NormalSourceCandidateGraph.SelectionIndex> wordSelections=new HashMap<>();
        private final Map<Long,Map<String,List<JsonNode>>> chunkLookups=new HashMap<>();
        private boolean closed,deferWaitCas;
        private VerifiedBindingFrame(CanonicalEvidenceService owner,CanonicalEvidenceService boundary,String project,String module,List<BindingView> input){
            this.owner=owner;this.boundary=boundary;this.project=project;this.module=module;
            for(BindingView view:input){CanonicalSourceBinding binding=copyBinding(view.binding);
                require(views.putIfAbsent(binding.getDocumentId(),new BindingView(binding,view.snapshot,view.collection,view.sourceStamp,view.registryWireSha256,
                        view.registryWireSha256==null?null:JsonUtils.serializedSha256(view.snapshot)))==null,
                        HttpStatus.CONFLICT,"CANONICAL_FRAME_DOCUMENT_AMBIGUOUS");}
        }
        private void active(){require(Thread.currentThread()==thread&&!closed,HttpStatus.CONFLICT,"CANONICAL_FRAME_CLOSED_OR_WRONG_THREAD");}
        private BindingView view(Long id,String projection,String module){
            active();BindingView view=views.get(id);
            require(this.module.equals(module)&&view!=null&&Objects.equals(projection,view.binding.getProjectionSha256()),
                    HttpStatus.CONFLICT,"CANONICAL_FRAME_SOURCE_OR_MODULE_MISMATCH");return view;
        }
        public List<CanonicalSourceBinding> bindings(){active();List<CanonicalSourceBinding> out=new ArrayList<>();
            for(BindingView view:views.values())out.add(copyBinding(view.binding));return Collections.unmodifiableList(out);}
        public JsonNode snapshot(Long documentId,String projection,String module){return view(documentId,projection,module).snapshot.deepCopy();}
        /** Borrow only while this owned frame is active; callers must not mutate or retain the tree. */
        public JsonNode snapshotForRead(Long documentId,String projection,String module){return view(documentId,projection,module).snapshot;}
        public JsonNode normalSnapshot(Long documentId,String projection,String module){
            BindingView view=view(documentId,projection,module);
            return NormalObservedContract.isNormal(view.snapshot)?view.snapshot.deepCopy():null;
        }
        public List<EvidencePointer> pointers(Long documentId,String projection,String module){
            BindingView view=view(documentId,projection,module);List<EvidencePointer> out=new ArrayList<>();
            for(JsonNode chunk:view.snapshot.path("chunks"))out.add(pointer(view.binding,view.snapshot,chunk,module,
                    view.collection?NativeDocumentCollectionContract.unitUnknowns(view.snapshot,chunk.path("unitId").asText()):unknowns(view.snapshot)));
            return out;
        }
        public JsonNode selectedNormalChunk(Long documentId,String projection,String chunkId,String module){
            BindingView view=view(documentId,projection,module);
            require(NormalObservedContract.isNormal(view.snapshot),HttpStatus.CONFLICT,"CANONICAL_SELECTED_NORMAL_SOURCE_REQUIRED");
            Map<String,List<JsonNode>> lookup=chunkLookups.computeIfAbsent(documentId,id->{
                Map<String,List<JsonNode>> chunks=new HashMap<>();
                for(JsonNode chunk:view.snapshot.path("chunks"))chunks.computeIfAbsent(chunk.path("id").asText(),key->new ArrayList<>()).add(chunk);
                return chunks;
            });
            List<JsonNode> matches=lookup.get(chunkId);
            require(matches!=null,HttpStatus.NOT_FOUND,"CANONICAL_CHUNK_UNAVAILABLE");
            require(matches.size()==1,HttpStatus.CONFLICT,"CANONICAL_CHUNK_AMBIGUOUS");
            return matches.get(0);
        }
        public EvidencePointer selectedNormalPointer(Long documentId,String projection,String chunkId,String module){
            BindingView view=view(documentId,projection,module);
            return pointer(view.binding,view.snapshot,selectedNormalChunk(documentId,projection,chunkId,module),module,unknowns(view.snapshot));
        }
        public JsonNode wordSourceScopeContext(Long documentId,String projection,String chunkId,String module){
            BindingView view=view(documentId,projection,module);
            if(!NormalObservedContract.isNormal(view.snapshot))return null;
            JsonNode graph=view.snapshot.path("manifest").path("candidateGraph");
            NormalSourceCandidateGraph.SelectionIndex index=wordSelections.computeIfAbsent(documentId,id->new NormalSourceCandidateGraph.SelectionIndex(graph));
            return CanonicalEvidenceService.wordSourceScopeContext(view.binding,view.snapshot,selectedNormalChunk(documentId,projection,chunkId,module),index);
        }
        public void requireOwner(CanonicalEvidenceService service,String project,String module){active();
            require(owner==service&&this.project.equals(project)&&this.module.equals(module),HttpStatus.CONFLICT,"CANONICAL_FRAME_OWNER_MISMATCH");}
        public void beforeWait(){active();boundary.assertFrameSourcesCurrent(this);}
        /** One initial Ask keeps its checked source trees until the final owner close before paid work. */
        public void deferWaitCasUntilClose(){active();deferWaitCas=true;}
        public void revalidate(){active();if(deferWaitCas)beforeWait();else boundary.revalidateFrame(this);}
        @Override public void close(){
            if(closed)return;active();
            try{boundary.revalidateFrame(this);boundary.assertFrameOriginalsCurrent(this);}
            finally{closed=true;views.clear();wordSelections.clear();chunkLookups.clear();originalStates.clear();}
        }
    }
    private static CanonicalSourceBinding copyBinding(CanonicalSourceBinding binding){
        // Every entity field is an immutable scalar/string/Instant; this detached
        // copy never exposes the managed entity or an internally retained node.
        CanonicalSourceBinding result=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(binding,result);return result;
    }
    private static List<Object> bindingState(CanonicalSourceBinding binding){
        // Compare every immutable field directly; serializing snapshotJson duplicates the complete sealed source.
        return Arrays.asList(binding.getId(),binding.getProjectId(),binding.getDocumentId(),binding.getProjectionSha256(),
                binding.getOriginalSourceSha256(),binding.getJavaParsedSourceHash(),binding.getRoutingSha256(),
                binding.getSourceRevisionSha256(),binding.getProjectionRevisionSha256(),binding.getCanonicalSourceHash(),
                binding.getScopePolicySha256(),binding.getAdoptionManifestSha256(),binding.getReviewReferenceSha256(),
                binding.getFamilyCompositionSha256(),binding.getFamilyComponentSetSha256(),binding.getSnapshotBytesSha256(),
                binding.getDeclaredModule(),binding.getEligibleModulesJson(),binding.getSnapshotJson(),binding.getCreatedAt(),binding.getSnapshotContentSha256());
    }
    /** Proxy calls arrive at the owning service target; do not compare the Spring proxy to its target. */
    public void assertFrameOwner(VerifiedBindingFrame frame,String project,String module){
        require(frame!=null,HttpStatus.CONFLICT,"CANONICAL_FRAME_UNAVAILABLE");frame.requireOwner(this,project,module);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public VerifiedBindingFrame verifiedBindings(String projectId,String module,List<Long> ids){
        return verifiedBindings(projectId,module,ids,null);
    }
    /** Vetting already owns complete selected sources; retain only their checked scalar revisions. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public VerifiedBindingFrame verifiedSemanticBindings(String projectId,String module,List<SourceDocument> selected){
        require("vetting".equals(module)&&selected!=null,HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
        List<Long> ids=new ArrayList<>();Map<Long,SourceDocument> captured=new LinkedHashMap<>();
        for(SourceDocument source:selected){require(source!=null&&source.getId()!=null&&captured.putIfAbsent(source.getId(),source)==null,
                HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");ids.add(source.getId());}
        VerifiedBindingFrame frame=verifiedBindings(projectId,module,ids,captured);
        try{for(SourceDocument source:selected)if(SourceContentDigests.stamped(source))frame.originalStates.put(source.getId(),sources.observeOriginal(source));return frame;}
        catch(RuntimeException changed){frame.close();throw changed;}
    }
    private VerifiedBindingFrame verifiedBindings(String projectId,String module,List<Long> ids,Map<Long,SourceDocument> captured){
        validModule(module);require(ids!=null&&!ids.isEmpty()&&!ids.contains(null)&&new HashSet<>(ids).size()==ids.size(),
                HttpStatus.BAD_REQUEST,"CANONICAL_SELECTED_DOCUMENTS_INVALID");
        List<BindingView> input=new ArrayList<>();
        for(Long id:ids){CanonicalSourceBinding revision=latestCompactForModule(projectId,id,module);
            require(revision!=null,HttpStatus.CONFLICT,"CANONICAL_SELECTED_DOCUMENT_UNREGISTERED");
            input.add(readBindingView(projectId,id,revision.getProjectionSha256(),module,captured==null?null:captured.get(id)));}
        VerifiedBindingFrame frame=new VerifiedBindingFrame(this,boundaryProxy==null?this:boundaryProxy.getObject(),projectId,module,input);
        try{frame.beforeWait();return frame;}
        catch(RuntimeException changed){frame.closed=true;frame.views.clear();throw changed;}
    }

    /** Fresh committed original/catalog/binding guard immediately before an external wait. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertFrameSourcesCurrent(VerifiedBindingFrame frame){
        frame.requireOwner(this,frame.project,frame.module);
        for(BindingView retained:frame.views.values()){
            assertFrameCurrent(frame,retained);
            assertFrameBindingCurrent(frame,retained);
        }
    }
    /** Fresh CAS plus committed source guards after waits; a frame never certifies another stage. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void revalidateFrame(VerifiedBindingFrame frame){
        frame.requireOwner(this,frame.project,frame.module);
        for(BindingView retained:frame.views.values()){
            assertFrameBindingCurrent(frame,retained);
            CanonicalSourceBinding binding=retained.binding;
            JsonNode snapshot=retained.snapshot;
            if(retained.registryWireSha256!=null||retained.collection||NormalObservedContract.isNormal(snapshot)||NativePdfSourceFamilyContract.isFamily(snapshot)||NativePdfCanonicalContract.isNativePdf(snapshot)||NativeCanonicalContract.isNative(snapshot)){
                boolean rechecked=false;
                if(retained.registryWireSha256!=null){
                    assertFrameCurrent(frame,retained);
                    CanonicalEvidenceBackend.Reply reply=backend.snapshotIfChanged(binding.getProjectionSha256(),frame.module,retained.registryWireSha256,NormalObservedContract.isNormal(snapshot));
                    if(reply!=null){
                        if(reply.notModified)assertFrameCurrent(frame,retained);
                        else snapshot=validateRecheckedSnapshot(binding,frame.module,retained.sourceStamp,reply.json()).snapshot;
                        rechecked=true;
                    }
                }
                if(!rechecked)snapshot=recheckNativeSnapshot(binding,frame.module,NormalObservedContract.isNormal(snapshot),retained.sourceStamp);
            }
            else assertFrameCurrent(frame,retained);
            require(snapshot.equals(retained.snapshot),HttpStatus.CONFLICT,"CANONICAL_FRAME_SNAPSHOT_CHANGED");
            if(retained.snapshotSha256!=null)require(retained.snapshotSha256.equals(JsonUtils.serializedSha256(retained.snapshot)),HttpStatus.CONFLICT,"CANONICAL_FRAME_SNAPSHOT_CHANGED");
        }
        // The binding loaded before a CAS wait may still be in this transaction's
        // JPA identity map. A separate proxy transaction must re-read every field
        // after that wait; an unchanged projection SHA alone is insufficient.
        frame.beforeWait();
    }
    /** Only the owned Vetting frame uses cheap intermediate original pins; its exit hashes actual originals. */
    private void assertFrameCurrent(VerifiedBindingFrame frame,BindingView retained){
        CanonicalCurrentSourceReader.OriginalFileState original=frame.originalStates.get(retained.binding.getDocumentId());
        if(original==null){assertCurrent(retained.binding,frame.module,retained.sourceStamp);return;}
        SourceDocument current=sources.currentVettingMetadata(frame.project).get(retained.binding.getDocumentId());
        require(current!=null,HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");sources.requireCurrentStamp(retained.sourceStamp,current);sources.requireOriginalFreshness(current,original);
        require(Objects.equals(retained.binding.getOriginalSourceSha256(),current.getSourceSha256())&&Objects.equals(retained.binding.getRoutingSha256(),routingHash(current)),HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true)
    public void assertFrameOriginalsCurrent(VerifiedBindingFrame frame){
        frame.requireOwner(this,frame.project,frame.module);
        for(BindingView retained:frame.views.values())if(frame.originalStates.containsKey(retained.binding.getDocumentId()))sources.requireOriginalBytes(retained.sourceStamp);
    }
    private void assertFrameBindingCurrent(VerifiedBindingFrame frame,BindingView retained){
        CanonicalSourceBinding selected=retained.binding;
        if(entityManager==null){
            List<CanonicalSourceBinding> latest=latestForModule(bindings,entityManager,frame.project,selected.getDocumentId(),frame.module);
            require(!latest.isEmpty()&&bindingState(latest.get(0)).equals(bindingState(selected)),HttpStatus.CONFLICT,"CANONICAL_FRAME_BINDING_CHANGED");
            return;
        }
        CanonicalSourceBinding latest=latestCompactForModule(frame.project,selected.getDocumentId(),frame.module);
        CanonicalSourceBinding expected=copyBinding(selected);expected.setSnapshotJson(null);
        require(latest!=null&&selected.getSnapshotBytesSha256()!=null&&bindingState(latest).equals(bindingState(expected)),HttpStatus.CONFLICT,"CANONICAL_FRAME_BINDING_CHANGED");
        // The write trigger owns this digest; only unstamped legacy bindings need another complete CLOB read.
        String content=latest.getSnapshotContentSha256();
        require(Objects.equals(content==null?streamedSnapshotSha256(selected.getId()):content,selected.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_FRAME_BINDING_CHANGED");
    }
    private String streamedSnapshotSha256(Long id){
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection->{
            try(java.sql.PreparedStatement statement=connection.prepareStatement("select snapshot_json from canonical_source_binding where id=?")){
                statement.setLong(1,id);
                try(java.sql.ResultSet rows=statement.executeQuery()){
                    if(!rows.next())return null;
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    try(Reader source=rows.getCharacterStream(1);
                        Writer sink=new OutputStreamWriter(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest),StandardCharsets.UTF_8)){
                        if(source==null)return null;
                        source.transferTo(sink);
                    }
                    return java.util.HexFormat.of().formatHex(digest.digest());
                }
            }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            catch(IOException unreadable){throw new UncheckedIOException(unreadable);}
        });
    }

    private static final class BindingView {
        final CanonicalSourceBinding binding;final JsonNode snapshot;final boolean collection;final SourceDocument sourceStamp;
        final String registryWireSha256,snapshotSha256;
        BindingView(CanonicalSourceBinding binding,JsonNode snapshot,boolean collection){this(binding,snapshot,collection,null);}
        BindingView(CanonicalSourceBinding binding,JsonNode snapshot,boolean collection,SourceDocument sourceStamp){this(binding,snapshot,collection,sourceStamp,null);}
        BindingView(CanonicalSourceBinding binding,JsonNode snapshot,boolean collection,SourceDocument sourceStamp,String registryWireSha256){
            this(binding,snapshot,collection,sourceStamp,registryWireSha256,null);}
        BindingView(CanonicalSourceBinding binding,JsonNode snapshot,boolean collection,SourceDocument sourceStamp,String registryWireSha256,String snapshotSha256){
            this.binding=binding;this.snapshot=snapshot;this.collection=collection;this.sourceStamp=sourceStamp;this.registryWireSha256=registryWireSha256;
            this.snapshotSha256=snapshotSha256;}
    }
    private CanonicalSourceBinding readBinding(String projectId,Long documentId,String projection,String module){
        return readBindingView(projectId,documentId,projection,module).binding;
    }
    private BindingView readBindingView(String projectId,Long documentId,String projection,String module){
        return readBindingView(projectId,documentId,projection,module,null);
    }
    private BindingView readBindingView(String projectId,Long documentId,String projection,String module,SourceDocument captured){
        validSha(projection);validModule(module);
        CanonicalSourceBinding binding=bindings.findByProjectIdAndDocumentIdAndProjectionSha256(projectId,documentId,projection)
                .orElseThrow(()->new CanonicalEvidenceException(HttpStatus.NOT_FOUND,"CANONICAL_BINDING_UNAVAILABLE"));
        require(binding.getSnapshotJson()!=null&&Utf8.sha256(binding.getSnapshotJson()).equals(binding.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        JsonNode snapshot=validatedSnapshot(binding.getSnapshotJson(),projection);validateBindingPins(binding,snapshot);boolean collection=NativeDocumentCollectionContract.isCollection(snapshot);
        SourceDocument sourceStamp=captured==null?null:sources.captureSourceStamp(binding,module,captured,snapshot);
        if(captured==null&&NormalObservedContract.isNormal(snapshot))sourceStamp=sources.captureNormalSourceStamp(binding,module,snapshot);
        if(NormalObservedContract.isNormal(snapshot)||NativePdfSourceFamilyContract.isFamily(snapshot)||NativePdfCanonicalContract.isNativePdf(snapshot)||NativeCanonicalContract.isNative(snapshot)||NativeDocumentCollectionContract.isCollection(snapshot)){
            // Native compact snapshots refer to private proof. Re-read the registry so every
            // public operation revalidates the actual CAS bytes, even for a persisted binding.
            return recheckNativeBindingView(binding,module,NormalObservedContract.isNormal(snapshot),sourceStamp,snapshot);
        }else assertCurrent(binding,module,sourceStamp); // Native reads already have fresh pre/post gates around the registry call.
        return new BindingView(binding,snapshot,collection,sourceStamp);
    }
    private JsonNode recheckNativeSnapshot(CanonicalSourceBinding binding,String module,boolean normal){
        return recheckNativeSnapshot(binding,module,normal,null);
    }
    private JsonNode recheckNativeSnapshot(CanonicalSourceBinding binding,String module,boolean normal,SourceDocument sourceStamp){
        return recheckNativeBindingView(binding,module,normal,sourceStamp,null).snapshot;
    }
    private BindingView recheckNativeBindingView(CanonicalSourceBinding binding,String module,boolean normal,SourceDocument sourceStamp,JsonNode checkedSnapshot){
        assertCurrent(binding,module,sourceStamp);
        if(normal&&checkedSnapshot!=null){
            // Stored publication order can differ from the registry's sorted wire bytes.
            String wireSha256=CanonicalJson.hash(binding.getSnapshotJson(),null);
            CanonicalEvidenceBackend.Reply reply=backend.snapshotIfChanged(binding.getProjectionSha256(),binding.getDeclaredModule(),wireSha256,true);
            if(reply!=null){
                if(!reply.notModified)return validateRecheckedSnapshot(binding,module,sourceStamp,reply.json());
                assertCurrent(binding,module,sourceStamp);return new BindingView(binding,checkedSnapshot,false,sourceStamp,wireSha256);
            }
        }
        String current=normal?backend.normalSnapshot(binding.getProjectionSha256(),binding.getDeclaredModule()):backend.snapshot(binding.getProjectionSha256(),module);
        return validateRecheckedSnapshot(binding,module,sourceStamp,current);
    }
    private BindingView validateRecheckedSnapshot(CanonicalSourceBinding binding,String module,SourceDocument sourceStamp,String current){
        JsonNode snapshot=validatedSnapshot(current,binding.getProjectionSha256());
        require(CanonicalJson.hash(current,null).equals(CanonicalJson.hash(binding.getSnapshotJson(),null)),HttpStatus.CONFLICT,"CANONICAL_STORED_BYTES_CHANGED");
        boolean collection=NativeDocumentCollectionContract.isCollection(snapshot);
        if(collection)snapshot=NativeDocumentCollectionContract.readDocument(current,binding.getProjectionSha256(),
            ordinal->backend.batch(binding.getProjectionSha256(),ordinal,module).bytes,
            ordinal->backend.envelope(binding.getProjectionSha256(),ordinal,module).bytes).publicProjection;
        assertCurrent(binding,module,sourceStamp);return new BindingView(binding,snapshot,collection,sourceStamp,Utf8.sha256(current));
    }
    private void assertCurrent(CanonicalSourceBinding binding,String module){
        assertCurrent(binding,module,null);
    }
    private void assertCurrent(CanonicalSourceBinding binding,String module,SourceDocument sourceStamp){
        if(entityManager==null){sources.assertCurrent(binding,module);return;}
        if(sourceStamp==null)sources.assertSourceCurrent(binding,module);
        else sources.assertSourceStampCurrent(binding,module,sourceStamp);
        CanonicalSourceBinding latest=latestCompactForModule(binding.getProjectId(),binding.getDocumentId(),module);
        require(latest!=null&&Objects.equals(latest.getProjectionSha256(),binding.getProjectionSha256())
                &&Objects.equals(latest.getSnapshotBytesSha256(),binding.getSnapshotBytesSha256()),HttpStatus.CONFLICT,"CANONICAL_ADOPTION_SUPERSEDED");
    }
    private SourceDocument currentDocument(String projectId,Long id,String module){return sources.currentDocument(projectId,id,module);}
    public static String routingHash(SourceDocument d){return CanonicalJson.sha(JsonUtils.write(Arrays.asList(d.getCategory(),d.getFileKey(),d.getReviewRole(),d.getDocumentScope(),d.getParseStatus(),d.getOerIdentityJson())).getBytes(StandardCharsets.UTF_8));}
    public static void validateBindingPins(CanonicalSourceBinding binding,JsonNode snapshot){
        JsonNode identity=snapshot.path("projectionIdentity");
        if(NormalObservedContract.isNormal(snapshot)){
            require(binding.getAdoptionManifestSha256()==null&&binding.getReviewReferenceSha256()==null&&binding.getFamilyCompositionSha256()==null&&binding.getFamilyComponentSetSha256()==null
                &&Objects.equals(binding.getRoutingSha256(),identity.path("routingSha256").asText())&&Objects.equals(binding.getScopePolicySha256(),identity.path("scopePolicySha256").asText())
                &&Objects.equals(binding.getOriginalSourceSha256(),identity.path("originalSourceSha256").asText())&&Objects.equals(binding.getJavaParsedSourceHash(),identity.path("javaParsedSourceHash").asText())
                &&Objects.equals(binding.getCanonicalSourceHash(),snapshot.path("sourceHash").asText())&&Objects.equals(binding.getSourceRevisionSha256(),identity.path("sourceRevisionSha256").asText())
                &&Objects.equals(binding.getProjectionRevisionSha256(),snapshot.path("projectionRevisionSha256").asText())&&Objects.equals(binding.getDeclaredModule(),identity.path("module").asText())
                &&JsonUtils.parse(binding.getEligibleModulesJson()).equals(identity.path("eligibleModules")),HttpStatus.CONFLICT,"CANONICAL_BINDING_TYPED_PINS_CHANGED");return;
        }
        boolean family=NativePdfSourceFamilyContract.isFamily(snapshot);
        require(family?binding.getAdoptionManifestSha256()==null&&binding.getReviewReferenceSha256()==null
                &&Objects.equals(binding.getFamilyCompositionSha256(),identity.path("compositionSha256").asText())
                &&Objects.equals(binding.getFamilyComponentSetSha256(),identity.path("componentSetSha256").asText())
                :binding.getFamilyCompositionSha256()==null&&binding.getFamilyComponentSetSha256()==null
                &&Objects.equals(binding.getAdoptionManifestSha256(),adoptionHash(snapshot,binding.getSnapshotJson()))
                &&Objects.equals(binding.getReviewReferenceSha256(),manifest(snapshot).path("reference").path("evidenceSha256").asText()),HttpStatus.CONFLICT,"CANONICAL_BINDING_TYPED_PINS_CHANGED");
    }
    private CanonicalSourceBinding sameBinding(CanonicalSourceBinding existing,CanonicalSourceBinding candidate){
        validateBindingPins(existing,validatedSnapshot(existing.getSnapshotJson(),existing.getProjectionSha256()));
        validateBindingPins(candidate,validatedSnapshot(candidate.getSnapshotJson(),candidate.getProjectionSha256()));
        require(Objects.equals(existing.getOriginalSourceSha256(),candidate.getOriginalSourceSha256())&&Objects.equals(existing.getJavaParsedSourceHash(),candidate.getJavaParsedSourceHash())
                &&Objects.equals(existing.getRoutingSha256(),candidate.getRoutingSha256())
                &&CanonicalJson.hash(existing.getSnapshotJson(),null).equals(CanonicalJson.hash(candidate.getSnapshotJson(),null)),HttpStatus.CONFLICT,"CANONICAL_BINDING_IMMUTABLE_CONFLICT");return existing;
    }
    private static JsonNode parse(String value){try{return JsonUtils.parse(value);}catch(Exception invalid){throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}}
    private static void validModule(String module){require(MODULES.contains(module),HttpStatus.BAD_REQUEST,"CANONICAL_MODULE_INVALID");}
    private static void validSha(String sha){require(sha!=null&&sha.matches("[a-f0-9]{64}"),HttpStatus.BAD_REQUEST,"CANONICAL_SHA_INVALID");}
    private static void require(boolean condition,HttpStatus status,String reason){if(!condition)throw new CanonicalEvidenceException(status,reason);}
    private static Set<String> modules(JsonNode value){require(value.isArray()&&value.size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");Set<String> result=new LinkedHashSet<>();for(JsonNode item:value){require(item.isTextual()&&MODULES.contains(item.asText())&&result.add(item.asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_ELIGIBILITY_INVALID");}return result;}
    private static Set<Integer> pages(JsonNode value){require(value.isArray()&&value.size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_PAGES_INVALID");Set<Integer> result=new TreeSet<>();int previous=0;for(JsonNode p:value){require(p.isIntegralNumber()&&p.canConvertToInt()&&p.asInt()>previous,HttpStatus.BAD_GATEWAY,"CANONICAL_PAGES_INVALID");previous=p.asInt();result.add(previous);}return result;}
    private static boolean magic(String media,byte[] bytes){return ("image/png".equals(media)&&starts(bytes,new byte[]{(byte)137,80,78,71,13,10,26,10}))
            ||("image/jpeg".equals(media)&&starts(bytes,new byte[]{(byte)255,(byte)216,(byte)255}))
            ||("application/pdf".equals(media)&&starts(bytes,"%PDF-".getBytes(StandardCharsets.US_ASCII)))
            ||("image/webp".equals(media)&&bytes.length>=12&&starts(bytes,"RIFF".getBytes(StandardCharsets.US_ASCII))&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP"));}
    private static boolean starts(byte[] actual,byte[] prefix){if(actual.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(actual[i]!=prefix[i])return false;return true;}

    /** Shared version gate; source freshness and actual CAS reads belong to the service/registry. */
    private JsonNode validatedSnapshot(String raw,String address){return validateSnapshot(raw,address,backend::normalMaximumBytes);}
    public static JsonNode validateSnapshot(String raw,String address){return validateSnapshot(raw,address,NormalObservedContract.MAX_BYTES);}
    public static JsonNode validateSnapshot(String raw,String address,int normalMaximum){return validateSnapshot(raw,address,()->normalMaximum);}
    private static JsonNode validateSnapshot(String raw,String address,java.util.function.IntSupplier normalMaximum){
        require(raw!=null,HttpStatus.BAD_GATEWAY,"CANONICAL_SNAPSHOT_TOO_LARGE");
        long bytes=Utf8.byteCount(raw);
        require(bytes<=NormalObservedContract.MAX_BYTES,HttpStatus.BAD_GATEWAY,"CANONICAL_SNAPSHOT_TOO_LARGE");
        JsonNode snapshot;
        try{snapshot=parse(raw);}catch(CanonicalEvidenceException invalid){
            require(bytes<=NativePdfCanonicalContract.MAX_BYTES,HttpStatus.BAD_GATEWAY,"CANONICAL_SNAPSHOT_TOO_LARGE");throw invalid;
        }
        if(NormalObservedContract.isNormal(snapshot))return NormalObservedContract.validate(raw,address,normalMaximum.getAsInt(),snapshot);
        require(bytes<=NativePdfCanonicalContract.MAX_BYTES,HttpStatus.BAD_GATEWAY,"CANONICAL_SNAPSHOT_TOO_LARGE");
        if(NativePdfSourceFamilyContract.isFamily(snapshot))return NativePdfSourceFamilyContract.validateEnvelope(raw,address);
        if(NativePdfCanonicalContract.isNativePdf(snapshot)){
            require(address!=null&&address.equals(snapshot.path("projectionSha256").asText())&&address.equals(CanonicalJson.hash(raw,"projectionSha256")),HttpStatus.BAD_GATEWAY,"CANONICAL_SEAL_MISMATCH");
            NativePdfCanonicalContract.validateEnvelope(snapshot);return snapshot;
        }
        if(NativeDocumentCollectionContract.isCollection(snapshot))return NativeDocumentCollectionContract.validateRoot(raw,address);
        boolean nativeDocx=NativeCanonicalContract.isNative(snapshot);
        require(snapshot.isObject()&&(nativeDocx||"consense.canonical-projection.v1".equals(snapshot.path("schema").asText()))
                &&address.equals(snapshot.path("projectionSha256").asText())&&address.equals(CanonicalJson.hash(raw,"projectionSha256")),HttpStatus.BAD_GATEWAY,"CANONICAL_SEAL_MISMATCH");
        validSha(snapshot.path("sourceHash").asText());validSha(snapshot.path("projectionRevisionSha256").asText());
        require(snapshot.path("humanConfirmed").isBoolean()&&!snapshot.path("humanConfirmed").asBoolean()
                &&snapshot.path("chunks").isArray()&&snapshot.path("chunks").size()>0&&snapshot.path("assetRefs").isArray(),HttpStatus.BAD_GATEWAY,"CANONICAL_SNAPSHOT_INVALID");
        String source=snapshot.path("source").path("sourceSha256").asText();validSha(source);
        JsonNode identity=snapshot.path("projectionIdentity");Set<String> eligible=modules(identity.path("eligibleModules"));
        require(source.equals(identity.path("sourceSha256").asText())&&snapshot.path("source").path("sourceRevisionSha256").equals(identity.path("sourceRevisionSha256"))
                &&identity.path("documentId").equals(snapshot.path("manifest").path("documentId"))
                &&identity.path("module").equals(snapshot.path("manifest").path("module"))
                &&identity.path("role").equals(snapshot.path("manifest").path("role"))
                &&identity.path("sourcePurpose").equals(snapshot.path("manifest").path("sourcePurpose"))
                &&identity.path("eligibleModules").equals(snapshot.path("manifest").path("eligibleModules"))
                &&source.equals(snapshot.path("manifest").path("reference").path("sourceSha256").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_IDENTITY_MISMATCH");
        if(nativeDocx)NativeCanonicalContract.validateEnvelope(snapshot);
        Set<String> assets=new HashSet<>(),units=new HashSet<>(),chunkIds=new HashSet<>();
        for(JsonNode asset:snapshot.path("assetRefs")){
            String id=asset.path("assetId").asText();require(id.matches("asset:[a-f0-9]{64}")&&assets.add(id),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_INVALID");
            validSha(asset.path("sha256").asText());
            require(source.equals(asset.path("sourceSha256").asText())&&asset.path("byteCount").isIntegralNumber()&&asset.path("byteCount").asLong()>0
                    &&asset.path("sha256").equals(asset.path("artifactDescriptor").path("sha256"))
                    &&asset.path("byteCount").equals(asset.path("artifactDescriptor").path("byteCount")),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_DESCRIPTOR_MISMATCH");
            require(pages(asset.path("physicalPages")).size()==1&&asset.path("sourceRegions").isArray()&&asset.path("sourceRegions").size()>0,HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_REGIONS_INVALID");
            for(JsonNode region:asset.path("sourceRegions"))validateBox(region);
        }
        for(JsonNode chunk:snapshot.path("chunks")){
            String unit=chunk.path("unitId").asText();require(!unit.trim().isEmpty()&&units.add(unit)&&chunk.path("id").asText().matches("canonical:[a-f0-9]{64}")&&chunkIds.add(chunk.path("id").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_UNIT_INVALID");
            require(source.equals(chunk.path("sourceSha256").asText())&&source.equals(chunk.path("originalSourceSha256").asText())
                    &&chunk.path("documentId").equals(identity.path("documentId"))&&chunk.path("module").equals(identity.path("module"))
                    &&chunk.path("role").equals(identity.path("role"))&&chunk.path("sourcePurpose").equals(identity.path("sourcePurpose"))
                    &&chunk.path("sourceRevisionSha256").equals(snapshot.path("source").path("sourceRevisionSha256"))
                    &&eligible.equals(modules(chunk.path("eligibleModules")))&&chunk.path("sourceHash").equals(snapshot.path("sourceHash"))
                    &&chunk.path("projectionRevisionSha256").equals(snapshot.path("projectionRevisionSha256"))
                    &&chunk.path("sourceQuality").path("semanticQuality").asText().equals("unknown")
                    &&chunk.path("humanConfirmed").isBoolean()&&!chunk.path("humanConfirmed").asBoolean(),HttpStatus.BAD_GATEWAY,"CANONICAL_CHUNK_BINDING_MISMATCH");
            if(nativeDocx){NativeCanonicalContract.validateChunk(chunk,source);continue;}
            Set<Integer> physical=pages(chunk.path("physicalPages"));Set<Integer> partsPages=new TreeSet<>();Set<String> blocks=new HashSet<>();
            require(chunk.path("parts").isArray()&&chunk.path("parts").size()>0&&chunk.path("assetRefs").isArray(),HttpStatus.BAD_GATEWAY,"CANONICAL_PARTS_INVALID");
            for(JsonNode part:chunk.path("parts")){validateBox(part);require(source.equals(part.path("sourceSha256").asText())&&part.path("physicalPageNo").isIntegralNumber()
                    &&blocks.add(part.path("blockId").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_PART_BINDING_MISMATCH");partsPages.add(part.path("physicalPageNo").asInt());}
            require(physical.equals(partsPages),HttpStatus.BAD_GATEWAY,"CANONICAL_PAGE_SPAN_MISMATCH");
            Set<Integer> assetPages=new TreeSet<>();Set<String> assetBlocks=new HashSet<>();List<JsonNode> expectedAssets=new ArrayList<>();
            for(JsonNode asset:snapshot.path("assetRefs"))if(unit.equals(asset.path("unitId").asText())){expectedAssets.add(asset);assetPages.addAll(pages(asset.path("physicalPages")));for(JsonNode region:asset.path("sourceRegions"))assetBlocks.add(region.path("blockId").asText());}
            require(expectedAssets.size()==chunk.path("assetRefs").size(),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_GROUP_MISMATCH");
            int at=0;for(JsonNode asset:chunk.path("assetRefs"))require(asset.equals(expectedAssets.get(at++)),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_GROUP_MISMATCH");
            if("table_group".equals(chunk.path("canonicalScope").asText())){
                require(physical.equals(pages(chunk.path("tableGroup").path("physicalPages"))),HttpStatus.BAD_GATEWAY,"CANONICAL_TABLE_GROUP_INVALID");
                // A table with no assets is a text-only unit. Once assets are declared, the complete group is mandatory.
                require(expectedAssets.isEmpty()||(physical.equals(assetPages)&&blocks.equals(assetBlocks)),HttpStatus.BAD_GATEWAY,"CANONICAL_TABLE_GROUP_ASSETS_INCOMPLETE");
            }
        }
        for(JsonNode asset:snapshot.path("assetRefs"))require(units.contains(asset.path("unitId").asText()),HttpStatus.BAD_GATEWAY,"CANONICAL_ASSET_UNIT_UNBOUND");
        return snapshot;
    }
    private static void validateBox(JsonNode node){JsonNode box=node.path("bbox");require("normalized_xyxy_original_page".equals(node.path("bboxConvention").asText())&&box.isArray()&&box.size()==4,HttpStatus.BAD_GATEWAY,"CANONICAL_COORDINATES_INVALID");double[] b=new double[4];for(int i=0;i<4;i++){b[i]=box.get(i).asDouble(Double.NaN);require(box.get(i).isNumber()&&Double.isFinite(b[i])&&b[i]>=0&&b[i]<=1,HttpStatus.BAD_GATEWAY,"CANONICAL_COORDINATES_INVALID");}require(b[0]<=b[2]&&b[1]<=b[3],HttpStatus.BAD_GATEWAY,"CANONICAL_COORDINATES_INVALID");}
}
