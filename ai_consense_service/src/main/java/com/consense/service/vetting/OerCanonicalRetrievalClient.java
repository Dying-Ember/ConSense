package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.config.ConsenseProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalEvidenceBackend;
import com.consense.service.sources.NativeDocumentCollectionContract;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.service.sources.NativePdfCanonicalContract;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.consense.service.sources.NormalObservedContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import okhttp3.HttpUrl;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Lossless canonical wire. Callers supply current bindings from CanonicalEvidenceService. */
@Component
public class OerCanonicalRetrievalClient {
    private static final String INPUT = "canonical-source-chunks-upstream-scope-and-parser-projection-v1";
    private static final String ASSETS = "source-and-projection-bound-content-addressed-assets-v1";
    private static final String RECIPE = "scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1";
    private static final String INDEX_STATUS = "read-only-existing-canonical-index-v1";
    private static final String SOURCE_PAGE_INDEX = "explicit-composite-index-pins-v1";
    public static final String SOURCE_READ = "source-bound-demand-read-v1";
    public static final String SEMANTIC_READ = "source-semantic-units-v1";
    private static final List<String> SCOPE = Arrays.asList("documentId", "sourceHash", "role", "metadataVersion",
            "segmentationVersion", "nativeTableMetadataVersion", "sourceQualityMetadataVersion", "sourceQualityHash");
    private final HttpSupport http;
    private final ConsenseProperties properties;
    private final CanonicalEvidenceProperties evidence;
    private final CanonicalEvidenceBackend backend;
    private final Map<String, ReceiptState> receiptStates = new ConcurrentHashMap<>();

    /** A key has one invalidation generation, including work already awaiting HTTP. */
    private static final class ReceiptState {
        long generation;
        boolean invalidated;
        Receipt receipt;
        String context;
    }
    private static final class Operation {
        final ReceiptState state;
        final long generation;
        final Receipt receipt;
        final boolean indexing,observing;
        Operation(ReceiptState state,boolean indexing,boolean observing){
            this.state=state;this.generation=state.generation;this.receipt=state.receipt;this.indexing=indexing;this.observing=observing;
        }
    }
    private Operation beginOperation(String key,boolean indexing){
        return beginOperation(key,indexing,false);
    }
    private Operation beginOperation(String key,boolean indexing,boolean observing){
        ReceiptState state=receiptStates.computeIfAbsent(key,ignored->new ReceiptState());
        synchronized(state){
            if(indexing){state.generation++;state.invalidated=true;state.receipt=null;state.context=null;}
            else require(observing||!state.invalidated,"canonical_index_receipt_invalidated");
            return new Operation(state,indexing,observing);
        }
    }
    private void invalidate(Operation operation){
        if(operation==null)return;
        synchronized(operation.state){
            // An expired response must not erase a later successful explicit index.
            if(operation.state.generation!=operation.generation)return;
            operation.state.generation++;operation.state.invalidated=true;
            operation.state.receipt=null;operation.state.context=null;
        }
    }
    private static void currentOperation(Operation operation){
        require(operation.state.generation==operation.generation,"canonical_index_operation_invalidated");
        require(operation.indexing||!operation.state.invalidated,"canonical_index_receipt_invalidated");
    }
    private void bindContext(Operation operation,String module,Corpus corpus,Capability health){
        String context=JsonUtils.write(Arrays.asList(module,corpus.fingerprint,corpus.localBindingFingerprint,
                health.fingerprint,health.sourceUnitPolicy,corpus.localRuntimeFingerprint));
        synchronized(operation.state){
            require(operation.state.generation==operation.generation,"canonical_index_operation_invalidated");
            require(operation.indexing||operation.observing||!operation.state.invalidated,"canonical_index_receipt_invalidated");
            if(!corpus.localRuntimeFingerprint.equals(localRuntimeFingerprint(module))){
                invalidate(operation);throw new IllegalStateException("current canonical runtime configuration changed");
            }
            if(operation.state.context!=null&&!operation.state.context.equals(context)){
                invalidate(operation);throw new IllegalStateException("current canonical corpus or recipe changed");
            }
            if(operation.indexing||!operation.state.invalidated)operation.state.context=context;
        }
    }
    private static boolean sameReceipt(Receipt first,Receipt second){
        return first!=null&&second!=null&&first.signature.equals(second.signature)&&first.fingerprint.equals(second.fingerprint)
                &&first.health.equals(second.health)&&first.module.equals(second.module)&&first.sourceUnitPolicy.equals(second.sourceUnitPolicy);
    }
    private IndexState publishReceipt(Operation operation,Receipt receipt,Corpus corpus,List<CanonicalSourceBinding> current,String module){
        synchronized(operation.state){
            localAdmission(operation,corpus,current,module);
            if(!operation.indexing&&operation.state.receipt!=null&&!sameReceipt(operation.state.receipt,receipt)){
                invalidate(operation);throw new IllegalStateException("canonical index signature changed");
            }
            operation.state.receipt=receipt;
            if(operation.indexing)operation.state.invalidated=false;
            return new IndexState(true,receipt.signature,corpus.chunks.size(),null);
        }
    }
    private JsonNode publishQuery(Operation operation,JsonNode result,Corpus corpus,List<CanonicalSourceBinding> current,String module){
        JsonNode output=result.deepCopy();
        synchronized(operation.state){
            try {
                localAdmission(operation,corpus,current,module);
                require(sameReceipt(operation.receipt,operation.state.receipt),"no current canonical index receipt");
                return output;
            }catch(RuntimeException invalid){invalidate(operation);throw invalid;}
        }
    }

    /** Pure local admission under the publication lock; this never starts another HTTP wait. */
    private void localAdmission(Operation operation,Corpus corpus,List<CanonicalSourceBinding> current,String module){
        currentOperation(operation);validateLocalSources(corpus,current);
        require(corpus.localRuntimeFingerprint.equals(localRuntimeFingerprint(module)),"current canonical runtime configuration changed");
    }

    /** Snapshot strings are immutable. Retain them exactly, without escaping them into another large JSON. */
    private static final class LocalBindingState {
        final List<Object> fields;
        final String snapshot;
        LocalBindingState(CanonicalSourceBinding binding){fields=localBindingFields(binding);snapshot=binding.getSnapshotJson();}
        boolean matches(CanonicalSourceBinding binding){return binding!=null&&fields.equals(localBindingFields(binding))
                &&Objects.equals(snapshot,binding.getSnapshotJson());}
    }
    private static List<Object> localBindingFields(CanonicalSourceBinding binding){
        return Arrays.asList(binding.getId(),binding.getProjectId(),binding.getDocumentId(),binding.getProjectionSha256(),
                binding.getOriginalSourceSha256(),binding.getJavaParsedSourceHash(),binding.getRoutingSha256(),
                binding.getSourceRevisionSha256(),binding.getProjectionRevisionSha256(),binding.getCanonicalSourceHash(),
                binding.getScopePolicySha256(),binding.getAdoptionManifestSha256(),binding.getReviewReferenceSha256(),
                binding.getFamilyCompositionSha256(),binding.getFamilyComponentSetSha256(),
                binding.getSnapshotBytesSha256(),binding.getDeclaredModule(),binding.getEligibleModulesJson(),
                binding.getCreatedAt()==null?null:binding.getCreatedAt().toString());
    }
    private void validateLocalSources(Corpus corpus,List<CanonicalSourceBinding> current){
        require(current!=null&&corpus.localBindings.size()==current.size(),"current canonical selected sources changed");
        for(int ordinal=0;ordinal<current.size();ordinal++)
            require(corpus.localBindings.get(ordinal).matches(current.get(ordinal)),"current canonical stored binding changed");
        require(corpus.sourcePageEvidence.equals(sourcePageEvidence(current)),"current source page evidence changed");
    }
    private String localRuntimeFingerprint(String module){
        ConsenseProperties.Vetting expected=properties.getVetting();
        return hash(JsonUtils.write(Arrays.asList(url(module,"/health"),expected.getExpectedRetrievalContract(),
                expected.getExpectedRetrievalSignatureVersion(),expected.getExpectedRetrievalGranularity(),
                expected.getExpectedEmbeddingWindowMaxTokens(),expected.getExpectedRerankWindowMaxTokens(),expected.getExpectedWindowOverlapTokens(),
                expected.getExpectedStructuralEmbeddingMaxTokens(),expected.getExpectedStructuralRerankMaxTokens(),expected.isExpectedStructuralRerankStrict(),
                expected.getExpectedRetrievalRecipeFingerprint(),expected.getExpectedRetrievalSignature())));
    }

    @Autowired public OerCanonicalRetrievalClient(HttpSupport http,ConsenseProperties properties,CanonicalEvidenceProperties evidence,CanonicalEvidenceBackend backend){
        this.http=http;this.properties=properties;this.evidence=evidence;this.backend=backend;
    }
    /** Existing direct fixtures keep their constructor; collection HTTP still uses the configured bounded backend. */
    public OerCanonicalRetrievalClient(HttpSupport http,ConsenseProperties properties,CanonicalEvidenceProperties evidence){
        this(http,properties,evidence,new CanonicalEvidenceBackend(evidence));
    }

    private static class Corpus {
        final List<String> projections = new ArrayList<>();
        final Map<String, JsonNode> chunks = new LinkedHashMap<>();
        final Map<String, String> sourcePageEvidence = new TreeMap<>();
        final List<LocalBindingState> localBindings=new ArrayList<>();
        String policy, fingerprint,localBindingFingerprint,localRuntimeFingerprint;
        boolean nativeSources,collectionSources,nativePdfSources,familySources,normalSources;
    }
    public boolean attachmentContextEnabled(){return VettingRetrievalHealth.ATTACHMENT_STRUCTURAL_CONTRACT.equals(properties.getVetting().getExpectedRetrievalContract());}
    /** Fresh source-only capability. An existing index must still match the current source and health. */
    public String sourceUnitPolicy(String indexKey,String module,CanonicalEvidenceService.VerifiedBindingFrame frame){
        return sourceUnitPolicy(indexKey,module,frame.bindings(),frame);
    }
    public String sourceUnitPolicy(String indexKey,String module,List<CanonicalSourceBinding> current){return sourceUnitPolicy(indexKey,module,current,null);}
    private String sourceUnitPolicy(String indexKey,String module,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Operation operation=beginOperation(indexKey,false);Receipt receipt=operation.receipt;
        try{
            Corpus corpus=corpus(current,module,frame);
            Capability live=stageHealth(frame,false,module,corpus);
            bindContext(operation,module,corpus,live);
            if(receipt!=null){
                require(module.equals(receipt.module)&&corpus.fingerprint.equals(receipt.fingerprint),"current canonical corpus changed");
                require(receipt.health.equals(live.fingerprint)&&receipt.sourceUnitPolicy.equals(live.sourceUnitPolicy),"canonical recipe changed after index");
            }
            require(VettingRetrievalSourceUnits.supportsPolicy(live.sourceUnitPolicy),"canonical source-unit capability is unknown");
            revalidateCurrent(operation,module,current,frame,corpus,live);
            synchronized(operation.state){localAdmission(operation,corpus,current,module);return live.sourceUnitPolicy;}
        }catch(RuntimeException invalid){invalidate(operation);throw invalid;}
    }
    private static class Receipt {
        final String signature, fingerprint, health, module, sourceUnitPolicy;
        Receipt(String signature, String fingerprint, String health, String module,String sourceUnitPolicy) {
            this.signature=signature; this.fingerprint=fingerprint; this.health=health; this.module=module;this.sourceUnitPolicy=sourceUnitPolicy;
        }
    }
    private static final class Capability {
        final String fingerprint,sourceUnitPolicy,indexStatusContract,softwareIdentity;
        final boolean sourceRead,semanticRead;
        Capability(String fingerprint,String policy,String indexStatusContract,boolean sourceRead,boolean semanticRead,String softwareIdentity){this.fingerprint=fingerprint;this.sourceUnitPolicy=policy;this.indexStatusContract=indexStatusContract;this.sourceRead=sourceRead;this.semanticRead=semanticRead;this.softwareIdentity=softwareIdentity;}
    }
    /** Immutable semantic addresses, not a source-validity verdict or a retained source graph. */
    public static final class SemanticSources {
        private final String module,policy,software,runtime,token;
        private final JsonNode addresses,manifests,sources;
        private final List<CanonicalSourceBinding> bindings;
        private final int total;
        private String scopeSha;
        private SemanticSources(String module,String policy,Capability health,String runtime,JsonNode result,List<CanonicalSourceBinding> bindings,int total){
            this.module=module;this.policy=policy;software=health.softwareIdentity;this.runtime=runtime;token=result.path("readManifestToken").asText();
            addresses=result.path("projectionSha256").deepCopy();manifests=result.path("readManifestSha256").deepCopy();sources=result.path("sources").deepCopy();this.bindings=new ArrayList<>();
            for(CanonicalSourceBinding original:bindings){CanonicalSourceBinding compact=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(original,compact);compact.setSnapshotJson(null);this.bindings.add(compact);}this.total=total;
        }
        public String identitySha256(){return hash(JsonUtils.write(Arrays.asList(module,policy,software,addresses,manifests,sources)));}
        public int totalOrigins(){return total;}
        public JsonNode sourceCatalog(){return sources.deepCopy();}
    }
    public SemanticSources prepareSemanticSources(String module,List<CanonicalSourceBinding> current){
        require(current!=null&&!current.isEmpty(),"semantic sources missing");String policy=current.get(0).getScopePolicySha256();
        ObjectNode request=JsonUtils.mapper().createObjectNode();request.put("module",module);request.put("operation","prepare");request.put("view","semantic_units");request.put("maxBytes",16777216);
        ArrayNode addresses=request.putArray("projectionSha256");Set<Long> ids=new HashSet<>();for(CanonicalSourceBinding binding:current){
            require(binding!=null&&ids.add(binding.getDocumentId())&&policy.equals(binding.getScopePolicySha256())&&sha(binding.getProjectionSha256()),"semantic source pins differ");addresses.add(binding.getProjectionSha256());}
        Capability before=health(module,policy,false,false,false,false,false,false);require(before.sourceRead&&before.semanticRead,"OER bounded semantic source reads unavailable");
        String runtime=localRuntimeFingerprint(module);JsonNode result=statusReceipt(backend.sourceRead(JsonUtils.write(request),module,16777216));
        Capability after=health(module,policy,false,false,false,false,false,false);require(before.fingerprint.equals(after.fingerprint)&&runtime.equals(localRuntimeFingerprint(module)),"semantic runtime changed during preparation");
        require(SOURCE_READ.equals(result.path("contract").asText())&&SEMANTIC_READ.equals(result.path("semanticProjectionVersion").asText())
                &&"prepare".equals(result.path("operation").asText())&&"semantic_units".equals(result.path("view").asText())&&module.equals(result.path("module").asText())
                &&addresses.equals(result.path("projectionSha256"))&&before.sourceUnitPolicy.equals(result.path("sourceUnitPolicy").asText())&&falseBoolean(result.path("sourceQualityVerified"))
                &&"prepared".equals(result.path("coverage").path("status").asText())&&result.path("origins").isArray()&&result.path("origins").isEmpty()&&result.path("units").isArray()&&result.path("units").isEmpty(),"semantic preparation receipt differs");
        require(result.path("readManifestSha256").isArray()&&result.path("readManifestSha256").size()==current.size()&&sha(result.path("readManifestToken").asText()),"semantic preparation addresses missing");
        for(JsonNode manifest:result.path("readManifestSha256"))require(manifest.isTextual()&&sha(manifest.asText()),"semantic manifest identity invalid");
        int total=validateSemanticCatalog(result.path("sources"),current);require(integer(result.path("coverage").path("totalOrigins"))&&total==result.path("coverage").path("totalOrigins").asInt(),"semantic preparation count differs");
        return new SemanticSources(module,policy,before,runtime,result,current,total);
    }
    /** Every call asks the public source service to rehash its actual dependencies before and after the read. */
    public JsonNode readSemanticSources(SemanticSources selected,JsonNode demand){
        require(selected!=null&&demand!=null&&demand.isObject(),"semantic read scope missing");Set<String> allowed=new HashSet<>(Arrays.asList("operation","originIds","cursor","maxItems","maxBytes"));
        demand.fieldNames().forEachRemaining(field->require(allowed.contains(field),"semantic read field unsupported"));String operation=demand.path("operation").asText();
        int items=demand.path("maxItems").asInt(64),maximum=demand.path("maxBytes").asInt(16777216);require(Arrays.asList("catalog","ids","scan").contains(operation)&&items>0&&items<=256&&maximum>=1024&&maximum<=16777216,"semantic read operation/budget invalid");
        for(String field:Arrays.asList("maxItems","maxBytes"))require(!demand.has(field)||integer(demand.path(field)),"semantic read budget must be integral");
        List<String> requested=demand.has("originIds")?ids(demand.path("originIds")):Collections.emptyList();require("ids".equals(operation)?!requested.isEmpty()&&requested.size()<=items:requested.isEmpty(),"semantic read ids differ");
        require(!demand.hasNonNull("cursor")||"scan".equals(operation)&&demand.path("cursor").isTextual(),"semantic cursor invalid");
        Capability before=health(selected.module,selected.policy,false,false,false,false,false,false);require(before.sourceRead&&before.semanticRead&&selected.software.equals(before.softwareIdentity)&&selected.runtime.equals(localRuntimeFingerprint(selected.module)),"semantic source software changed");
        ObjectNode request=demand.deepCopy();request.put("module",selected.module);request.put("view","semantic_units");request.put("maxItems",items);request.put("maxBytes",maximum);request.set("projectionSha256",selected.addresses);request.set("readManifestSha256",selected.manifests);request.put("readManifestToken",selected.token);
        JsonNode result=statusReceipt(backend.sourceRead(JsonUtils.write(request),selected.module,maximum));Capability after=health(selected.module,selected.policy,false,false,false,false,false,false);
        require(before.fingerprint.equals(after.fingerprint)&&selected.runtime.equals(localRuntimeFingerprint(selected.module)),"semantic runtime changed during read");
        require(SOURCE_READ.equals(result.path("contract").asText())&&SEMANTIC_READ.equals(result.path("semanticProjectionVersion").asText())&&operation.equals(result.path("operation").asText())
                &&selected.module.equals(result.path("module").asText())&&"semantic_units".equals(result.path("view").asText())&&selected.addresses.equals(result.path("projectionSha256"))
                &&selected.manifests.equals(result.path("readManifestSha256"))&&selected.token.equals(result.path("readManifestToken").asText())&&selected.sources.equals(result.path("sources"))
                &&before.sourceUnitPolicy.equals(result.path("sourceUnitPolicy").asText())&&falseBoolean(result.path("sourceQualityVerified")),"semantic read receipt differs");
        ObjectNode identity=result.path("scopeIdentity").deepCopy();require(identity.isObject(),"semantic scope identity missing");JsonNode hashNode=identity.remove("scopeSha256");String scopeSha=hashNode==null?null:hashNode.asText();
        require(sha(scopeSha)&&scopeSha.equals(hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(identity))))&&selected.software.equals(identity.path("softwareIdentitySha256").asText())
                &&selected.sources.equals(identity.path("sources"))&&selected.manifests.equals(identity.path("readManifestSha256"))&&selected.policy.equals(identity.path("policyIdentity").path("manifestSha256").asText())
                &&selected.module.equals(identity.path("module").asText())&&"semantic_units".equals(identity.path("view").asText()),"semantic scope identity differs");
        require(selected.scopeSha==null||selected.scopeSha.equals(scopeSha),"semantic source order changed");selected.scopeSha=scopeSha;
        JsonNode origins=result.path("origins"),units=result.path("units"),coverage=result.path("coverage");require(origins.isArray()&&units.isArray()&&origins.size()==units.size()&&origins.size()<=items,"semantic origins/units differ");
        List<String> returned=new ArrayList<>();for(int i=0;i<origins.size();i++){JsonNode origin=origins.get(i);validateSemanticMember(origin,selected);require(!returned.contains(origin.path("id").asText()),"semantic duplicate origin");returned.add(origin.path("id").asText());validateSemanticUnit(units.get(i),origin,selected);}
        require(returned.equals(ids(coverage.path("returnedOriginIds")))&&integer(coverage.path("returnedOrigins"))&&returned.size()==coverage.path("returnedOrigins").asInt()&&integer(coverage.path("totalOrigins"))&&selected.total==coverage.path("totalOrigins").asInt(),"semantic read coverage differs");
        if("ids".equals(operation))require(requested.equals(returned)&&requested.equals(ids(coverage.path("requestedOriginIds")))&&"complete".equals(coverage.path("status").asText())&&result.path("nextCursor").isNull(),"semantic requested ids omitted or reordered");
        if("catalog".equals(operation))require(origins.isEmpty()&&"catalog".equals(coverage.path("status").asText())&&result.path("nextCursor").isNull(),"semantic catalog returned content");
        if("scan".equals(operation)){
            require(integer(coverage.path("processedOrigins"))&&integer(coverage.path("remainingOrigins")),"semantic scan counts missing");int start=demand.hasNonNull("cursor")?sourceCursorOffset(demand.path("cursor"),scopeSha,selected.total):0,processed=coverage.path("processedOrigins").asInt();
            require(processed==start+returned.size()&&processed<=selected.total&&coverage.path("remainingOrigins").asInt()==selected.total-processed&&returned.equals(ids(coverage.path("requestedOriginIds"))),"semantic scan skipped or repeated origins");
            require(processed==selected.total?result.path("nextCursor").isNull():result.path("nextCursor").isTextual()&&sourceCursorOffset(result.path("nextCursor"),scopeSha,selected.total)==processed,"semantic scan cursor differs");
            require(processed==selected.total?"complete".equals(coverage.path("status").asText()):returned.isEmpty()?"unit_too_large".equals(coverage.path("status").asText()):"partial".equals(coverage.path("status").asText()),"semantic scan status differs");
        }
        return result.deepCopy();
    }
    private static int validateSemanticCatalog(JsonNode sources,List<CanonicalSourceBinding> bindings){
        require(sources.isArray()&&sources.size()==bindings.size(),"semantic source catalog differs");int total=0;for(int i=0;i<bindings.size();i++){CanonicalSourceBinding binding=bindings.get(i);JsonNode source=sources.get(i);
            require(binding.getProjectionSha256().equals(source.path("projectionSha256").asText())&&String.valueOf(binding.getDocumentId()).equals(source.path("documentId").asText())
                    &&binding.getOriginalSourceSha256().equals(source.path("originalSourceSha256").asText())&&binding.getCanonicalSourceHash().equals(source.path("sourceHash").asText())
                    &&binding.getJavaParsedSourceHash().equals(source.path("javaParsedSourceHash").asText())&&integer(source.path("chunkCount")),"semantic source identity differs");
            if(source.hasNonNull("sourceRevisionSha256"))require(binding.getSourceRevisionSha256().equals(source.path("sourceRevisionSha256").asText()),"semantic source revision differs");total=Math.addExact(total,source.path("chunkCount").asInt());}
        return total;
    }
    private static void validateSemanticMember(JsonNode member,SemanticSources selected){
        require(member.isObject()&&SEMANTIC_READ.equals(member.path("semanticProjectionVersion").asText())&&!member.path("id").asText().isEmpty()&&member.path("text").isTextual()&&member.path("parts").isArray(),"semantic member invalid");
        CanonicalSourceBinding binding=null;for(CanonicalSourceBinding candidate:selected.bindings)if(String.valueOf(candidate.getDocumentId()).equals(member.path("documentId").asText()))binding=candidate;require(binding!=null,"semantic member outside scope");
        JsonNode reference=member.path("evidenceRef");require(binding.getProjectionSha256().equals(reference.path("projectionSha256").asText())&&member.path("id").equals(reference.path("canonicalChunkId"))
                &&binding.getOriginalSourceSha256().equals(reference.path("originalSourceSha256").asText())&&binding.getCanonicalSourceHash().equals(member.path("sourceHash").asText())
                &&contains(member.path("eligibleModules"),selected.module),"semantic member address differs");
    }
    private static void validateSemanticUnit(JsonNode unit,JsonNode origin,SemanticSources selected){
        require(unit.isObject()&&sha(unit.path("unitId").asText())&&ids(unit.path("originIds")).equals(Collections.singletonList(origin.path("id").asText()))&&falseBoolean(unit.path("derivedMembersCanBecomeOrigins"))&&falseBoolean(unit.path("semanticScopeVerified")),"semantic unit declarations differ");
        for(JsonNode envelope:Arrays.asList(unit,unit.get("hierarchyContext"),unit.get("attachmentContext")))if(envelope!=null){
            List<String> required=ids(envelope.path("requiredMemberIds"));JsonNode members=envelope.path("members");require(members.isArray()&&members.size()==required.size(),"semantic unit missing complete members");
            for(int i=0;i<members.size();i++){JsonNode member=members.get(i);validateSemanticMember(member,selected);require(required.get(i).equals(member.path("id").asText())&&origin.path("documentId").equals(member.path("documentId")),"semantic unit members reordered or outside source");}
            require(!"unknown".equals(envelope.path("status").asText())||members.isEmpty()&&envelope.path("reason").isTextual(),"semantic unknown unit promoted");
            require(Arrays.asList("unknown","complete_observed_unit","complete_observed_hierarchy_context","complete_observed_attachment_context").contains(envelope.path("status").asText())
                    &&("unknown".equals(envelope.path("status").asText())||required.contains(origin.path("id").asText())),"semantic complete context omitted its origin");
        }
    }
    public static final class IndexState {
        public final boolean ready;
        public final String signature,reason;
        public final int chunkCount;
        public IndexState(boolean ready,String signature,int chunks,String reason){this.ready=ready;this.signature=signature;this.chunkCount=chunks;this.reason=reason;}
    }
    /** Current source and live module/recipe status, independent of legacy vector counts. */
    public IndexState indexStatus(String key,String module,CanonicalEvidenceService.VerifiedBindingFrame frame){
        return indexStatus(key,module,frame.bindings(),frame);
    }
    public IndexState indexStatus(String key,String module,List<CanonicalSourceBinding> current){return indexStatus(key,module,current,null);}
    private IndexState indexStatus(String key,String module,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Corpus corpus=null;Operation operation=null;
        try {
            operation=beginOperation(key,false,true);Receipt receipt=operation.receipt;
            corpus=corpus(current,module,frame);
            Capability currentHealth=stageHealth(frame,false,module,corpus);
            bindContext(operation,module,corpus,currentHealth);
            synchronized(operation.state){
                require(operation.state.generation==operation.generation,"canonical_index_operation_invalidated");
                if(operation.state.invalidated)return new IndexState(false,null,corpus.chunks.size(),"canonical_index_receipt_invalidated");
            }
            if(receipt!=null){
                require(module.equals(receipt.module)&&corpus.fingerprint.equals(receipt.fingerprint),"current canonical corpus changed");
                require(receipt.health.equals(currentHealth.fingerprint)&&receipt.sourceUnitPolicy.equals(currentHealth.sourceUnitPolicy),"canonical recipe changed after index");
            }
            require(INDEX_STATUS.equals(currentHealth.indexStatusContract),"read-only canonical index status capability missing");
            ObjectNode request=JsonUtils.mapper().createObjectNode();request.put("projectId",key);request.put("module",module);
            ArrayNode addresses=request.putArray("projectionSha256");corpus.projections.forEach(addresses::add);
            if(!corpus.sourcePageEvidence.isEmpty())request.set("sourcePageEvidence",JsonUtils.mapper().valueToTree(corpus.sourcePageEvidence));
            if(frame!=null)frame.beforeWait();
            JsonNode result=statusReceipt(http.postJson(url(module,"/index-canonical/status"),JsonUtils.write(request),properties.getVetting().getRetrievalIndexTimeoutMs()));
            require(result.isObject()&&key.equals(result.path("projectId").asText())
                    &&result.path("ready").isBoolean()&&result.path("ready").asBoolean()
                    &&result.path("readOnly").isBoolean()&&result.path("readOnly").asBoolean()
                    &&INDEX_STATUS.equals(result.path("indexStatusContract").asText())
                    &&integer(result.path("indexed"))&&result.path("indexed").asInt()==corpus.chunks.size()
                    &&integer(result.path("vectorPoints"))&&result.path("vectorPoints").asInt()>=0
                    &&sha(result.path("signature").asText())&&module.equals(result.path("targetModule").asText())
                    &&properties.getVetting().getExpectedRetrievalContract().equals(result.path("contract").asText())
                    &&addresses.equals(result.path("canonicalProjections")),"canonical status identity differs");
            validateIndexPins(result,corpus.sourcePageEvidence);scope(result.path("competitionScope"),module,corpus.policy);
            String frozen=properties.getVetting().getExpectedRetrievalSignature();
            require(JsonUtils.isBlankText(frozen)||frozen.equals(result.path("signature").asText()),"frozen index signature differs");
            require(receipt==null||receipt.signature.equals(result.path("signature").asText()),"canonical index signature changed");
            revalidateCurrent(operation,module,current,frame,corpus,currentHealth);
            Receipt recovered=new Receipt(result.path("signature").asText(),corpus.fingerprint,currentHealth.fingerprint,module,currentHealth.sourceUnitPolicy);
            return publishReceipt(operation,recovered,corpus,current,module);
        } catch(RuntimeException unavailable){invalidate(operation);return new IndexState(false,null,corpus==null?0:corpus.chunks.size(),unavailable.getMessage());}
    }

    /** Complete JSON is authoritative; no old Chunk/Part DTO conversion occurs. */
    public void index(String indexKey,String module,CanonicalEvidenceService.VerifiedBindingFrame frame){
        index(indexKey,module,frame.bindings(),frame);
    }
    public void index(String indexKey, String module, List<CanonicalSourceBinding> current){index(indexKey,module,current,null);}
    private void index(String indexKey, String module, List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Operation operation=beginOperation(indexKey,true);
        try {
        Corpus corpus = corpus(current,module,frame);
        Capability health = stageHealth(frame,true,module,corpus);
        bindContext(operation,module,corpus,health);
        ObjectNode request = JsonUtils.mapper().createObjectNode();
        request.put("projectId", indexKey); request.put("module", module);
        ArrayNode addresses = request.putArray("projectionSha256"); corpus.projections.forEach(addresses::add);
        if(!corpus.sourcePageEvidence.isEmpty())request.set("sourcePageEvidence",JsonUtils.mapper().valueToTree(corpus.sourcePageEvidence));
        if(frame!=null)frame.beforeWait();
        JsonNode result = JsonUtils.parse(http.postJson(url(module,"/index-canonical"), JsonUtils.write(request),
                properties.getVetting().getRetrievalIndexTimeoutMs()));
        require(indexKey.equals(result.path("projectId").asText()) && integer(result.path("indexed"))
                && result.path("indexed").asInt()==corpus.chunks.size() && sha(result.path("signature").asText())
                && VettingRetrievalHealth.structuralContract(result.path("contract").asText())
                && properties.getVetting().getExpectedRetrievalContract().equals(result.path("contract").asText())
                && module.equals(result.path("targetModule").asText())
                && addresses.equals(result.path("canonicalProjections")), "canonical index identity differs");
        validateIndexPins(result,corpus.sourcePageEvidence);
        require(corpus.sourcePageEvidence.equals(sourcePageEvidence(current)),"source page evidence changed during index");
        scope(result.path("competitionScope"), module, corpus.policy);
        String frozen=properties.getVetting().getExpectedRetrievalSignature();
        require(JsonUtils.isBlankText(frozen) || frozen.equals(result.path("signature").asText()), "frozen index signature differs");
        revalidateCurrent(operation,module,current,frame,corpus,health);
        publishReceipt(operation,new Receipt(result.path("signature").asText(),corpus.fingerprint,health.fingerprint,module,health.sourceUnitPolicy),corpus,current,module);
        }catch(RuntimeException invalid){invalidate(operation);throw invalid;}
    }

    public JsonNode readSources(String module,JsonNode demand,CanonicalEvidenceService.VerifiedBindingFrame frame){
        return readSources(module,demand,frame.bindings(),frame);
    }
    public JsonNode readSources(String module,JsonNode demand,List<CanonicalSourceBinding> current){
        return readSources(module,demand,current,null);
    }
    /** OER owns closure construction; Java checks exact source identity and returned payload bytes. */
    private JsonNode readSources(String module,JsonNode demand,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame){
        require(demand!=null&&demand.isObject(),"source read demand missing");
        Set<String> allowed=new HashSet<>(Arrays.asList("operation","originIds","cursor","maxItems","maxBytes","view"));
        demand.fieldNames().forEachRemaining(field->require(allowed.contains(field),"source read demand contains an unsupported field"));
        String operation=demand.path("operation").asText(),view=demand.path("view").asText("canonical_units");
        int maximum=demand.path("maxBytes").asInt(4194304),items=demand.path("maxItems").asInt(64);
        for(String field:Arrays.asList("maxBytes","maxItems"))require(!demand.has(field)||integer(demand.path(field)),"source read budget must be an integer");
        require(Arrays.asList("catalog","ids","scan").contains(operation)&&"canonical_units".equals(view)
                &&maximum>=1024&&maximum<=16777216&&items>0&&items<=256,"source read operation/view/budget invalid");
        List<String> origins=demand.has("originIds")?ids(demand.path("originIds")):Collections.emptyList();
        require("ids".equals(operation)?!origins.isEmpty()&&origins.size()<=items:origins.isEmpty(),"source read origins differ from operation");
        require(!demand.hasNonNull("cursor")||"scan".equals(operation)&&demand.path("cursor").isTextual(),"source read cursor invalid");
        Corpus corpus=corpus(current,module,frame);for(String origin:origins)require(corpus.chunks.containsKey(origin),"source read origin outside authorized corpus");
        Capability before=stageHealth(frame,true,module,corpus);
        require(before.sourceRead,"OER source-read capability unavailable");
        ObjectNode request=demand.deepCopy();request.put("module",module);request.put("view",view);request.put("maxItems",items);request.put("maxBytes",maximum);
        ArrayNode addresses=request.putArray("projectionSha256");corpus.projections.forEach(addresses::add);
        if(frame!=null)frame.beforeWait();
        String raw=backend.sourceRead(JsonUtils.write(request),module,maximum);JsonNode result;
        try(com.fasterxml.jackson.core.JsonParser parser=JsonUtils.mapper().getFactory().createParser(raw)){
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);result=JsonUtils.mapper().readTree(parser);
            require(parser.nextToken()==null,"source read has trailing JSON");
        }catch(java.io.IOException invalid){throw new IllegalStateException("Source read rejected: invalid JSON",invalid);}
        if(frame!=null)frame.revalidate();
        Capability after=stageHealth(frame,false,module,corpus);if(frame!=null)frame.revalidate();
        validateLocalSources(corpus,current);
        require(before.fingerprint.equals(after.fingerprint)&&before.sourceUnitPolicy.equals(after.sourceUnitPolicy),"source read runtime changed");
        require(SOURCE_READ.equals(result.path("contract").asText())&&module.equals(result.path("module").asText())
                &&operation.equals(result.path("operation").asText())&&view.equals(result.path("view").asText())&&addresses.equals(result.path("projectionSha256"))
                &&sha(result.path("scopeIdentity").path("scopeSha256").asText())&&module.equals(result.path("scopeIdentity").path("module").asText())
                &&corpus.policy.equals(result.path("scopeIdentity").path("policyIdentity").path("manifestSha256").asText())
                &&before.sourceUnitPolicy.equals(result.path("sourceUnitPolicy").asText())&&falseBoolean(result.path("sourceQualityVerified")),"source read receipt differs");
        require(result.path("sources").equals(result.path("scopeIdentity").path("sources")),"source read scope catalog differs");
        ObjectNode scopeIdentity=result.path("scopeIdentity").deepCopy();String scopeSha=scopeIdentity.remove("scopeSha256").asText();
        require(scopeSha.equals(hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(scopeIdentity))))
                &&before.softwareIdentity.equals(scopeIdentity.path("softwareIdentitySha256").asText())
                &&before.sourceUnitPolicy.equals(scopeIdentity.path("sourceUnitPolicy").asText())
                &&hash(JsonUtils.write(new ArrayList<>(corpus.chunks.keySet()))).equals(scopeIdentity.path("originOrderSha256").asText()),"source read scope identity differs");
        validateReadSources(result,current,corpus);
        JsonNode returned=result.path("origins"),units=result.path("units"),coverage=result.path("coverage");
        require(returned.isArray()&&units.isArray()&&returned.size()==units.size()&&returned.size()<=items,"source read units/origins differ");
        List<String> returnedIds=new ArrayList<>();for(int i=0;i<returned.size();i++){
            JsonNode origin=returned.get(i);String id=origin.path("id").asText();
            require(!returnedIds.contains(id)&&origin.equals(corpus.chunks.get(id)),"source read origin payload differs");returnedIds.add(id);
            validateReadUnit(units.get(i),origin,corpus.chunks);
        }
        if("ids".equals(operation))require(origins.equals(returnedIds)&&origins.equals(ids(coverage.path("requestedOriginIds"))),"source read omitted/reordered requested origin");
        require(returnedIds.equals(ids(coverage.path("returnedOriginIds")))&&integer(coverage.path("returnedOrigins"))
                &&coverage.path("returnedOrigins").asInt()==returned.size(),"source read coverage differs from returned origins");
        if("ids".equals(operation))require("complete".equals(coverage.path("status").asText())&&result.path("nextCursor").isNull(),"source read ids did not complete");
        if("catalog".equals(operation))require(returned.isEmpty()&&units.isEmpty()&&"catalog".equals(coverage.path("status").asText())&&result.path("nextCursor").isNull(),"source catalog contains full source units or invalid coverage");
        if("scan".equals(operation)){
            require(integer(coverage.path("processedOrigins"))&&integer(coverage.path("remainingOrigins"))&&integer(coverage.path("totalOrigins")),"source scan counts invalid");
            int processed=coverage.path("processedOrigins").asInt(),total=coverage.path("totalOrigins").asInt();List<String> ordered=new ArrayList<>(corpus.chunks.keySet());
            require(total==ordered.size()&&processed>=returnedIds.size()&&processed<=total&&coverage.path("remainingOrigins").asInt()==total-processed
                    &&returnedIds.equals(ordered.subList(processed-returnedIds.size(),processed)),"source scan order/coverage differs");
            int start=demand.hasNonNull("cursor")?sourceCursorOffset(demand.path("cursor"),scopeSha,total):0;
            require(processed==start+returnedIds.size(),"source scan skipped origins");
            require(returnedIds.equals(ids(coverage.path("requestedOriginIds"))),"source scan requested origins differ");
            require(processed==total?result.path("nextCursor").isNull():result.path("nextCursor").isTextual(),"source scan cursor/completion differs");
            if(processed<total)require(sourceCursorOffset(result.path("nextCursor"),scopeSha,total)==processed,"source scan continuation offset differs");
            String status=coverage.path("status").asText();
            require(processed==total?"complete".equals(status):returnedIds.isEmpty()?"unit_too_large".equals(status):"partial".equals(status),"source scan status differs");
            if("unit_too_large".equals(status))require(processed<total&&ordered.get(processed).equals(result.path("blockedOrigin").path("id").asText())
                    &&result.path("blockedOrigin").path("requiredBytes").asLong()>maximum,"source scan blocked origin differs");
        }
        return result;
    }
    /** The OER process verifies the HMAC; this checks that pagination advances from the supplied scope/offset. */
    private static int sourceCursorOffset(JsonNode token,String scope,int total){
        require(token.isTextual()&&token.asText().length()<=8192,"source scan cursor invalid");String[] parts=token.asText().split("\\.",-1);
        require(parts.length==2&&sha(parts[1]),"source scan cursor invalid");JsonNode payload;
        try{payload=statusReceipt(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));}
        catch(RuntimeException invalid){throw new IllegalStateException("Source read rejected: invalid source cursor",invalid);}
        require(scope.equals(payload.path("scopeSha256").asText())&&integer(payload.path("offset"))&&payload.path("offset").asInt()>=0
                &&payload.path("offset").asInt()<total,"source scan cursor scope/offset differs");return payload.path("offset").asInt();
    }
    private static void validateReadSources(JsonNode result,List<CanonicalSourceBinding> current,Corpus corpus){
        JsonNode sources=result.path("sources");require(sources.isArray()&&sources.size()==current.size(),"source read catalog differs");
        Map<String,Long> counts=new HashMap<>();for(JsonNode chunk:corpus.chunks.values())counts.merge(chunk.path("documentId").asText(),1L,Long::sum);
        for(int i=0;i<current.size();i++){
            CanonicalSourceBinding binding=current.get(i);JsonNode source=sources.get(i);long count=counts.getOrDefault(String.valueOf(binding.getDocumentId()),0L);
            require(binding.getProjectionSha256().equals(source.path("projectionSha256").asText())&&String.valueOf(binding.getDocumentId()).equals(source.path("documentId").asText())
                    &&binding.getOriginalSourceSha256().equals(source.path("originalSourceSha256").asText())&&binding.getCanonicalSourceHash().equals(source.path("sourceHash").asText())
                    &&source.path("chunkCount").isIntegralNumber()&&source.path("chunkCount").asLong()==count,"source read source identity/count differs");
            if(binding.getSourceRevisionSha256()!=null)require(binding.getSourceRevisionSha256().equals(source.path("sourceRevisionSha256").asText()),"source read source revision differs");
        }
    }
    private static void validateReadUnit(JsonNode unit,JsonNode origin,Map<String,JsonNode> parents){
        require(unit.isObject()&&sha(unit.path("unitId").asText())&&ids(unit.path("originIds")).equals(Collections.singletonList(origin.path("id").asText()))
                &&falseBoolean(unit.path("derivedMembersCanBecomeOrigins"))&&falseBoolean(unit.path("semanticScopeVerified"))
                &&"unknown".equals(unit.path("qualifiersComplete").asText()),"source read unit declarations differ");
        for(String field:SCOPE)require(unit.path("sourceIdentity").path(field).equals(origin.path(field)),"source read unit identity differs");
        validateReadMembers(unit,origin,parents);
        for(String field:Arrays.asList("hierarchyContext","attachmentContext"))if(unit.has(field)){
            JsonNode context=unit.path(field);String status=context.path("status").asText();
            require(context.isObject()&&unit.path("unitId").equals(context.path("seedObservedUnitId"))
                    &&falseBoolean(context.path("derivedMembersCanBecomeOrigins"))&&falseBoolean(context.path("semanticScopeVerified"))
                    &&falseBoolean(context.path("legalClosureVerified"))&&"unknown".equals(context.path("qualifiersComplete").asText())
                    &&Arrays.asList("unknown","hierarchyContext".equals(field)?"complete_observed_hierarchy_context":"complete_observed_attachment_context").contains(status),"source read context declarations differ");
            for(String key:Arrays.asList("documentId","sourceHash"))require(origin.path(key).equals(context.path("sourceIdentity").path(key)),"source read context source differs");
            validateReadMembers(context,origin,parents);
            if("unknown".equals(status))require(context.path("members").isEmpty()&&context.path("reason").isTextual(),"source read unknown context was promoted");
            else require(ids(context.path("requiredMemberIds")).contains(origin.path("id").asText()),"source read context omitted origin");
        }
        require(Arrays.asList("unknown","complete_observed_unit").contains(unit.path("status").asText()),"source read unit status invalid");
        if("unknown".equals(unit.path("status").asText()))require(unit.path("members").isEmpty()&&unit.path("reason").isTextual(),"source read unknown unit was promoted");
        else require(ids(unit.path("requiredMemberIds")).contains(origin.path("id").asText()),"source read unit omitted origin");
    }
    private static void validateReadMembers(JsonNode unit,JsonNode origin,Map<String,JsonNode> parents){
        List<String> required=ids(unit.path("requiredMemberIds"));JsonNode members=unit.path("members");
        require(members.isArray()&&members.size()==required.size(),"source read member count differs");
        for(int i=0;i<members.size();i++)require(required.get(i).equals(members.get(i).path("id").asText())&&members.get(i).equals(parents.get(required.get(i)))
                &&origin.path("documentId").equals(members.get(i).path("documentId"))&&origin.path("sourceHash").equals(members.get(i).path("sourceHash")),"source read member payload/source differs");
    }

    /** Returned seeds stay ranked; closure members retain complete source JSON and unknowns. */
    public JsonNode retrieve(String indexKey,String module,String query,String role,CanonicalEvidenceService.VerifiedBindingFrame frame){
        return retrieve(indexKey,module,query,role,frame.bindings(),frame);
    }
    public JsonNode retrieve(String indexKey,String module,String query,String role,List<CanonicalSourceBinding> current){
        return retrieve(indexKey,module,query,role,current,null);
    }
    private JsonNode retrieve(String indexKey,String module,String query,String role,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        Operation operation=beginOperation(indexKey,false);Receipt receipt=operation.receipt;
        require(receipt!=null, "no current canonical index receipt");
        Corpus corpus;Capability live;
        try {
            corpus=corpus(current,module,frame);
            require(module.equals(receipt.module) && corpus.fingerprint.equals(receipt.fingerprint), "current canonical corpus changed");
            live=stageHealth(frame,true,module,corpus);
            bindContext(operation,module,corpus,live);
            require(receipt.health.equals(live.fingerprint)&&receipt.sourceUnitPolicy.equals(live.sourceUnitPolicy), "canonical recipe changed after index");
        } catch (RuntimeException invalid) { invalidate(operation); throw invalid; }
        ConsenseProperties.Vetting config=properties.getVetting();
        require(config.getTopK()>0 && config.getTopK()<=config.getCandidateLimit(), "query output/candidate limits differ");
        ObjectNode request=JsonUtils.mapper().createObjectNode();
        request.put("projectId",indexKey); request.put("query",query); request.put("candidates",config.getCandidateLimit());
        request.put("limit",config.getTopK()); if(role!=null)request.put("role",role);
        if(frame!=null)frame.beforeWait();
        JsonNode result=JsonUtils.parse(http.postJson(url(module,"/retrieve"),JsonUtils.write(request),config.getRetrievalTimeoutMs()));
        try {require(corpus.sourcePageEvidence.equals(sourcePageEvidence(current)),"source page evidence changed during retrieval");
            revalidateCurrent(operation,module,current,frame,corpus,live);}
        catch(RuntimeException invalid){invalidate(operation);throw invalid;}
        require(indexKey.equals(result.path("projectId").asText()) && receipt.signature.equals(result.path("indexSignature").asText())
                && VettingRetrievalHealth.structuralContract(result.path("mode").asText())
                && properties.getVetting().getExpectedRetrievalContract().equals(result.path("mode").asText())
                && result.path("hits").isArray() && result.path("hits").size()<=config.getTopK(), "canonical response identity differs");
        scope(result.path("competitionScope"),module,corpus.policy);
        List<String> ranked = new ArrayList<>(); double previous=Double.POSITIVE_INFINITY;
        for(JsonNode hit:result.path("hits")) {
            String id=hit.path("id").asText(); JsonNode original=corpus.chunks.get(id);
            require(original!=null && !ranked.contains(id) && original.equals(hit.path("payload")), "canonical ranked payload differs");
            require(role==null || role.equals(original.path("role").asText()), "canonical ranked role differs");
            require(hit.path("score").isNumber() && Double.isFinite(hit.path("score").asDouble())
                    && hit.path("score").asDouble()<=previous, "canonical rerank scores/order invalid");
            previous=hit.path("score").asDouble(); ranked.add(id);
        }
        require(receipt.sourceUnitPolicy.equals(result.path("sourceUnits").path("policy").asText()),"canonical source-unit policy differs from indexed capability");
        validateUnits(result,corpus,ranked);
        return publishQuery(operation,result,corpus,current,module);
    }

    private void revalidateCurrent(Operation operation,String module,List<CanonicalSourceBinding> current,CanonicalEvidenceService.VerifiedBindingFrame frame,Corpus corpus,Capability health){
        if(frame!=null)frame.revalidate();
        Corpus after=corpus(current,module,frame);Capability afterHealth=stageHealth(frame,false,module,after);
        require(corpus.fingerprint.equals(after.fingerprint)&&health.fingerprint.equals(afterHealth.fingerprint)
                &&health.sourceUnitPolicy.equals(afterHealth.sourceUnitPolicy),"canonical sources or runtime changed during operation");
        // This is the final health wait. A frame re-reads committed DB/source and
        // full CAS through its existing post-wait guards; the List seam then uses
        // exact retained local bytes/fields. Do not append another health request.
        if(frame!=null)frame.revalidate();
        validateLocalSources(corpus,current);
        bindContext(operation,module,after,afterHealth);
    }

    private Capability stageHealth(CanonicalEvidenceService.VerifiedBindingFrame frame,boolean anotherWait,String module,Corpus corpus){
        if(frame!=null)frame.beforeWait();
        try{return health(module,corpus.policy,corpus.nativeSources,corpus.collectionSources,corpus.nativePdfSources,corpus.familySources,corpus.normalSources,!corpus.sourcePageEvidence.isEmpty());}
        finally{if(frame!=null&&anotherWait)frame.revalidate();}
    }

    private Corpus corpus(List<CanonicalSourceBinding> bindings,String module,CanonicalEvidenceService.VerifiedBindingFrame frame) {
        require(Arrays.asList("drafting","vetting","advisory").contains(module) && bindings!=null && !bindings.isEmpty(), "canonical module/sources missing");
        Corpus out=new Corpus(); Set<Long> documents=new HashSet<>(); List<String> identities=new ArrayList<>();
        out.localRuntimeFingerprint=localRuntimeFingerprint(module);
        List<List<Object>> localIdentities=new ArrayList<>();
        for(CanonicalSourceBinding binding:bindings){
            require(binding!=null,"canonical stored binding is invalid");LocalBindingState retained=new LocalBindingState(binding);
            out.localBindings.add(retained);localIdentities.add(retained.fields);
        }
        out.localBindingFingerprint=hash(JsonUtils.write(localIdentities));
        out.sourcePageEvidence.putAll(sourcePageEvidence(bindings));
        for(CanonicalSourceBinding binding:bindings) {
            require(binding!=null && binding.getDocumentId()!=null && documents.add(binding.getDocumentId())
                    && sha(binding.getProjectionSha256()) && binding.getSnapshotJson()!=null
                    && hash(binding.getSnapshotJson()).equals(binding.getSnapshotBytesSha256()), "canonical stored binding is invalid");
            JsonNode verifiedNormal=frame==null?null:frame.normalSnapshot(binding.getDocumentId(),binding.getProjectionSha256(),module);
            JsonNode snapshot=verifiedNormal==null?JsonUtils.parse(binding.getSnapshotJson()):verifiedNormal, identity=snapshot.path("projectionIdentity");
            boolean collection=NativeDocumentCollectionContract.isCollection(snapshot);
            boolean nativeDocx=NativeCanonicalContract.isNative(snapshot)||collection;
            boolean family=NativePdfSourceFamilyContract.isFamily(snapshot),nativePdf=!family&&NativePdfCanonicalContract.isNativePdf(snapshot);
            boolean normal=NormalObservedContract.isNormal(snapshot);
            if(normal)require(module.equals(binding.getDeclaredModule())&&module.equals(identity.path("module").asText()),"normal observed projection belongs to another independent module state");
            if(normal){
                if(verifiedNormal==null){
                    CanonicalEvidenceService.validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());
                    JsonNode live=frame==null?CanonicalEvidenceService.validateSnapshot(backend.normalSnapshot(binding.getProjectionSha256(),binding.getDeclaredModule()),binding.getProjectionSha256())
                            :frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module);
                    require(live.equals(snapshot),"canonical stored normal observations changed");
                }
                CanonicalEvidenceService.validateBindingPins(binding,snapshot);out.normalSources=true;
            }else if(family){
                CanonicalEvidenceService.validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());
                CanonicalEvidenceService.validateBindingPins(binding,snapshot);out.familySources=true;
                JsonNode live=frame==null?CanonicalEvidenceService.validateSnapshot(backend.snapshot(binding.getProjectionSha256(),module),binding.getProjectionSha256())
                        :frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module);
                require(live.equals(snapshot),"canonical stored native PDF family changed");
            }else if(nativePdf){
                CanonicalEvidenceService.validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());out.nativePdfSources=true;
                JsonNode live=frame==null?CanonicalEvidenceService.validateSnapshot(backend.snapshot(binding.getProjectionSha256(),module),binding.getProjectionSha256())
                        :frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module);
                require(live.equals(snapshot),"canonical stored native PDF source changed");
            }
            if(nativeDocx){CanonicalEvidenceService.validateSnapshot(binding.getSnapshotJson(),binding.getProjectionSha256());out.nativeSources=true;}
            JsonNode parents=snapshot.path("chunks");
            if(collection){
                out.collectionSources=true;
                if(frame!=null)parents=frame.snapshot(binding.getDocumentId(),binding.getProjectionSha256(),module).path("chunks");
                else {
                    String current=backend.snapshot(binding.getProjectionSha256(),module);
                    JsonNode live=CanonicalEvidenceService.validateSnapshot(current,binding.getProjectionSha256());
                    require(live.equals(snapshot),"canonical stored collection root changed");
                    parents=NativeDocumentCollectionContract.readDocument(current,binding.getProjectionSha256(),
                            ordinal->backend.batch(binding.getProjectionSha256(),ordinal,module).bytes,
                            ordinal->backend.envelope(binding.getProjectionSha256(),ordinal,module).bytes).publicProjection.path("chunks");
                }
            }
            require((normal||family||nativePdf||nativeDocx||"consense.canonical-projection.v1".equals(snapshot.path("schema").asText()))
                    && binding.getProjectionSha256().equals(snapshot.path("projectionSha256").asText())
                    && String.valueOf(binding.getDocumentId()).equals(identity.path("documentId").asText())
                    && binding.getOriginalSourceSha256().equals(snapshot.path("source").path("sourceSha256").asText())
                    && binding.getCanonicalSourceHash().equals(snapshot.path("sourceHash").asText())
                    && contains(identity.path("eligibleModules"),module), "canonical envelope/source/module differs");
            require(parents.isArray() && parents.size()>0, "canonical chunks are missing");
            String policy=normal?identity.path("scopePolicySha256").asText():snapshot.path(collection?"manifestMetadata":"manifest").path("scope").path("scopePolicySha256").asText();
            require(sha(policy) && (out.policy==null || out.policy.equals(policy)), "canonical sources use different scope policies"); out.policy=policy;
            for(JsonNode chunk:parents) {
                String id=chunk.path("id").asText();
                require(!id.isEmpty() && !out.chunks.containsKey(id) && contains(chunk.path("eligibleModules"),module)
                        && identity.path("documentId").equals(chunk.path("documentId"))
                        && snapshot.path("sourceHash").equals(chunk.path("sourceHash"))
                        && binding.getOriginalSourceSha256().equals(chunk.path("originalSourceSha256").asText()), "canonical chunk identity differs");
                mapped(chunk); out.chunks.put(id,chunk);
            }
            out.projections.add(binding.getProjectionSha256());
            identities.add(JsonUtils.write(Arrays.asList(binding.getProjectId(),binding.getDocumentId(),binding.getJavaParsedSourceHash(),
                    binding.getRoutingSha256(),binding.getProjectionSha256(),binding.getSnapshotBytesSha256())));
        }
        validateLocalSources(out,bindings);
        out.fingerprint=hash(JsonUtils.write(out.sourcePageEvidence.isEmpty()?identities:Arrays.asList(identities,out.sourcePageEvidence))); return out;
    }

    /** Only selected, explicitly reviewed composites affect this index; sealed chunks stay unchanged. */
    private Map<String,String> sourcePageEvidence(List<CanonicalSourceBinding> bindings){
        Map<String,String> pins=new TreeMap<>();for(CanonicalSourceBinding binding:bindings){
            String identity=evidence.getSourcePageRenderEvidenceIdentities().get(binding.getProjectionSha256());
            if(identity!=null){require(sha(identity),"reviewed source page evidence identity invalid");pins.put(binding.getProjectionSha256(),identity);}
        }return pins;
    }
    private static void validateIndexPins(JsonNode result,Map<String,String> expected){
        JsonNode echo=result.path("sourcePageEvidence");
        if(expected.isEmpty()&&echo.isMissingNode())return;
        require(echo.isObject()&&JsonUtils.mapper().valueToTree(expected).equals(echo),"source page index evidence echo differs");
    }
    private Capability health(String module,String policy,boolean nativeSources,boolean collectionSources,boolean nativePdfSources,boolean familySources,boolean normalSources,boolean sourcePages) {
        JsonNode root=JsonUtils.parse(http.get(url(module,"/health"),properties.getVetting().getRetrievalTimeoutMs()));
        VettingRetrievalHealth.Identity base=VettingRetrievalHealth.validate(root,properties.getVetting());
        JsonNode oer=root.path("oer"), window=root.path("retrieval").path("sourceWindows");
        if(sourcePages)require(SOURCE_PAGE_INDEX.equals(oer.path("sourcePageIndexContract").asText()),"explicit source page index capability missing");
        require("cuda".equals(root.path("device").asText()), "canonical CUDA runtime required");
        for(String kind:Arrays.asList("embedding","reranker")) {
            JsonNode runtime=root.path("runtime").path(kind);
            require("cuda".equals(runtime.path("device").asText()) && "float32".equals(runtime.path("dtype").asText())
                    && runtime.path("serialOffload").isBoolean() && runtime.path("serialOffload").asBoolean(),
                    "canonical CUDA float32 serial runtime required: "+kind);
        }
        require(VettingRetrievalHealth.structuralContract(base.contract) && base.signatureVersion==4
                && INPUT.equals(oer.path("inputContract").asText()) && ASSETS.equals(oer.path("assetReturnContract").asText())
                && RECIPE.equals(oer.path("recipe").asText()) && module.equals(oer.path("targetModule").asText())
                && falseBoolean(oer.path("sourceQualityVerifiedByHealth")) && falseBoolean(oer.path("parserCandidatesAcceptedByRetrieval"))
                && "/project".equals(oer.path("canonicalProjectionEndpoint").asText())
                && policy.equals(oer.path("scopePolicy").path("manifestSha256").asText())
                && oer.path("scopePolicy").equals(window.path("competitionScope"))
                && INPUT.equals(window.path("oerInputContract").asText()) && module.equals(window.path("oerTargetModule").asText()),
                "canonical capability/scope handshake differs");
        for(String name:Arrays.asList("oer_server.py","competition_scope.py","artifact_store.py","structured_parser_evidence.py",
                "canonical_projection.py","oer_source_registry.py","oer_request_evidence.py"))
            require(sha(window.path("algorithms").path(name).asText()), "canonical algorithm identity missing: "+name);
        if(nativeSources){
            NativeCanonicalContract.validateCapability(oer);
            NativeCanonicalContract.validateAlgorithms(window.path("algorithms"));
        }
        if(collectionSources)NativeDocumentCollectionContract.validateCapability(oer,window.path("algorithms"));
        if(nativePdfSources){NativePdfCanonicalContract.validateCapability(oer);NativePdfCanonicalContract.validateAlgorithms(window.path("algorithms"));}
        if(familySources){NativePdfSourceFamilyContract.validateCapability(oer);NativePdfSourceFamilyContract.validateAlgorithms(window.path("algorithms"));}
        if(normalSources){NormalObservedContract.validateCapability(oer);for(String file:Arrays.asList("normal_observed_projection.py","normal_observed_server.py","unified_source_registry.py"))require(sha(window.path("algorithms").path(file).asText()),"normal observed algorithm identity missing: "+file);}
        JsonNode sourceRead=oer.path("sourceRead");
        return new Capability(hash(base.fingerprint+JsonUtils.write(oer)),base.sourceUnitPolicy,oer.path("indexStatusContract").asText(),
                SOURCE_READ.equals(sourceRead.path("contract").asText())&&"/sources/read".equals(sourceRead.path("endpoint").asText()),
                SEMANTIC_READ.equals(sourceRead.path("semanticProjectionVersion").asText())&&contains(sourceRead.path("views"),"semantic_units")&&contains(sourceRead.path("operations"),"prepare"),
                hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(window.path("algorithms")))));
    }

    /** This endpoint returns only a small receipt; reject ambiguous or over-cap JSON. */
    private static JsonNode statusReceipt(String raw){
        require(raw!=null&&raw.length()<=16777216&&raw.getBytes(StandardCharsets.UTF_8).length<=16777216,"canonical status receipt exceeds bounded wire");
        try{return JsonUtils.mapper().copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);}
        catch(Exception invalid){throw new IllegalStateException("Canonical retrieval rejected: canonical status receipt is not complete strict JSON",invalid);}
    }

    private static void scope(JsonNode receipt,String module,String policy) {
        require(module.equals(receipt.path("targetModule").asText()) && INPUT.equals(receipt.path("inputContract").asText())
                && policy.equals(receipt.path("policyIdentity").path("manifestSha256").asText())
                && falseBoolean(receipt.path("sourceQualityChanged")), "canonical scope receipt differs");
    }
    private static void validateUnits(JsonNode result,Corpus corpus,List<String> ranked) {
        OerCanonicalSourceUnits.validate(result,corpus.chunks,ranked);
    }

    private static void mapped(JsonNode chunk) {
        require(chunk.path("content").isTextual() && chunk.path("parts").isArray() && chunk.path("parts").size()>0, "canonical text/parts missing");
        List<String> text=new ArrayList<>();
        for(JsonNode part:chunk.path("parts")) {
            require(part.path("text").isTextual() && part.path("blockId").isTextual() && !part.path("blockId").asText().isEmpty()
                    && part.path("anchor").isTextual() && !part.path("anchor").asText().isEmpty()
                    && integer(part.path("startOffset")) && integer(part.path("endOffset")) && part.path("startOffset").asInt()>=0
                    && part.path("endOffset").asInt()-part.path("startOffset").asInt()==part.path("text").asText().length(), "canonical UTF16 part mapping differs");
            text.add(part.path("text").asText());
        }
        require(String.join("\n",text).equals(chunk.path("content").asText()), "canonical source text was transformed");
        for(String field:SCOPE)require(chunk.path(field).isTextual() && !chunk.path(field).asText().isEmpty(), "canonical source identity missing: "+field);
    }
    private static ObjectNode identity(JsonNode chunk) { ObjectNode out=JsonUtils.mapper().createObjectNode(); for(String field:SCOPE)out.set(field,chunk.path(field)); return out; }
    private static boolean intersects(JsonNode parts,Set<String> blocks) { for(JsonNode p:parts)if(blocks.contains(p.path("blockId").asText()))return true; return false; }
    private static List<String> ids(JsonNode values) { require(values.isArray(),"canonical ID list missing"); List<String> out=new ArrayList<>(); for(JsonNode v:values){require(v.isTextual()&&!v.asText().isEmpty()&&!out.contains(v.asText()),"canonical IDs missing/duplicate"); out.add(v.asText());} return out; }
    private static boolean contains(JsonNode values,String text) { if(!values.isArray())return false; for(JsonNode v:values)if(v.isTextual()&&text.equals(v.asText()))return true; return false; }
    private String url(String module,String path) {
        String selected=evidence.getRetrievalBaseUrls().getOrDefault(module,evidence.getBaseUrl());
        HttpUrl base=HttpUrl.parse(selected==null?"":selected);
        require(base!=null && base.username().isEmpty() && base.password().isEmpty()
                && base.query()==null && base.fragment()==null && "/".equals(base.encodedPath()), "canonical configured backend URL invalid");
        return base.newBuilder().addPathSegments(path.substring(1)).build().toString();
    }
    private static boolean integer(JsonNode node) { return node.isIntegralNumber()&&node.canConvertToInt(); }
    private static boolean falseBoolean(JsonNode node) { return node.isBoolean()&&!node.asBoolean(); }
    private static boolean sha(String value) { return value!=null&&value.matches("[0-9a-f]{64}"); }
    private static String hash(String value) { return com.consense.common.Utf8.sha256(value); }
    private static void require(boolean valid,String message) { if(!valid)throw new IllegalStateException("Canonical retrieval rejected: "+message); }
}
