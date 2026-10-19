package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.service.vetting.NormalSourceCandidateGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Independent unreviewed authority: complete parser observations, never reviewed adoption. */
public final class NormalObservedContract {
    public static final String SCHEMA="consense.normal-observed.v1",PRODUCER="normal_observed",VERSION="complete-bound-normal-observations-v1";
    public static final int MAX_BYTES=1024*1024*1024;
    private NormalObservedContract(){}
    public static int maximumBytes(int configured){
        if(configured<=0||configured>MAX_BYTES)throw new CanonicalEvidenceException(HttpStatus.SERVICE_UNAVAILABLE,"OER_EVIDENCE_CONFIG_INVALID");
        return configured;
    }
    public static boolean isNormal(JsonNode node){return SCHEMA.equals(node.path("schema").asText())||PRODUCER.equals(node.path("producerKind").asText());}
    public static void validateCapability(JsonNode oer){
        JsonNode n=oer.path("normalObserved");
        require(SCHEMA.equals(n.path("schema").asText())&&VERSION.equals(n.path("version").asText())&&PRODUCER.equals(n.path("producerKind").asText())
            &&"/normal-observed".equals(n.path("projectionEndpoint").asText())&&NormalSourceCandidateGraph.SCHEMA.equals(n.path("candidateGraphSchema").asText())
            &&NormalSourceCandidateGraph.VERSION.equals(n.path("candidateGraphVersion").asText())
            &&"complete-bound-generation-all-units-in-source-order".equals(n.path("completeUnitPolicy").asText())
            &&"retained-in-complete-graph-not-submitted-to-model".equals(n.path("nonTextUnitPolicy").asText())
            &&"complete-manifest-actual-cas-reconstruction-every-request".equals(n.path("artifactReadValidation").asText())
            &&"java_current_source_reader".equals(n.path("originalByteVerifier").asText())&&"application".equals(n.path("observerActor").asText())
            &&n.path("maximumMessageBytes").isIntegralNumber()&&n.path("maximumMessageBytes").canConvertToInt()
            &&n.path("maximumMessageBytes").asInt()>0&&n.path("maximumMessageBytes").asInt()<=MAX_BYTES,"NORMAL_OBSERVED_CAPABILITY_MISMATCH");
        for(String key:Arrays.asList("humanConfirmed","sourceQualityAccepted","semanticScopeVerified"))require(n.path(key).isBoolean()&&!n.path(key).asBoolean(),"NORMAL_OBSERVED_AUTHORITY_MISMATCH");
        require("candidate_unverified".equals(n.path("adoptionStatus").asText()),"NORMAL_OBSERVED_AUTHORITY_MISMATCH");
    }
    public static JsonNode validate(String raw,String address){return validate(raw,address,MAX_BYTES);}
    public static JsonNode validate(String raw,String address,int maximum){
        maximumBytes(maximum);
        require(raw!=null&&Utf8.byteCount(raw)<=maximum,"NORMAL_OBSERVED_TOO_LARGE");
        JsonNode p=JsonUtils.parse(raw),m=p.path("manifest"),g=m.path("candidateGraph"),b=m.path("sourceBinding"),i=p.path("projectionIdentity"),s=g.path("source");
        require(isNormal(p)&&SCHEMA.equals(p.path("schema").asText())&&PRODUCER.equals(p.path("producerKind").asText())
            &&address!=null&&address.equals(p.path("projectionSha256").asText())&&address.equals(CanonicalJson.hash(raw,"projectionSha256")),"NORMAL_OBSERVED_SEAL_MISMATCH");
        keys(p,"schema","producerKind","projectionIdentity","sourceHash","projectionRevisionSha256","source","manifest","observationManifestRef","canonical","chunks","nonRetrievableUnits","assetRefs","unknowns","adoptionStatus","humanConfirmed","sourceQualityAccepted","semanticScopeVerified","legalClosure","projectionSha256");
        keys(m,"candidateGraph","sourceBinding","observer");keys(b,"module","eligibleModules","sourcePurpose","sourceClass","routingSha256","scopePolicySha256");
        require(JsonUtils.parse("{\"actor\":\"application\",\"method\":\"normal_parser_observation\",\"originalByteVerifier\":\"java_current_source_reader\"}").equals(m.path("observer")),"NORMAL_OBSERVED_AUTHORITY_MISMATCH");
        declarations(p);declarations(g);
        require(NormalSourceCandidateGraph.SCHEMA.equals(g.path("schema").asText())&&NormalSourceCandidateGraph.VERSION.equals(g.path("producerVersion").asText())
            &&"actual_source_candidate_generation".equals(g.path("status").asText())&&g.path("units").isArray()&&!g.path("units").isEmpty()
            &&g.path("relations").isArray()&&g.path("unknowns").isArray(),"NORMAL_OBSERVED_GRAPH_INVALID");
        String mr=CanonicalJson.field(raw,"manifest"),gr=CanonicalJson.field(mr,"candidateGraph"),ir=CanonicalJson.field(raw,"projectionIdentity");
        String graphSha=CanonicalJson.hash(gr,null),manifestSha=CanonicalJson.hash(mr,null);
        ObjectNode expectedIdentity=object();expectedIdentity.put("version",VERSION);expectedIdentity.put("producerKind",PRODUCER);
        sha(i.path("projectionCodeSha256").asText());expectedIdentity.set("projectionCodeSha256",i.path("projectionCodeSha256"));
        for(String key:Arrays.asList("documentId","role"))expectedIdentity.set(key,s.path(key));
        b.fields().forEachRemaining(e->expectedIdentity.set(e.getKey(),e.getValue()));
        expectedIdentity.set("originalSourceSha256",s.path("originalSourceSha256"));expectedIdentity.set("javaParsedSourceHash",s.path("parsedSourceHash"));
        expectedIdentity.set("generationSha256",g.path("generationSha256"));expectedIdentity.put("candidateGraphSha256",graphSha);expectedIdentity.put("observationManifestSha256",manifestSha);
        String revision=hashFields("originalSourceSha256",scalar(s,"originalSourceSha256"),"javaParsedSourceHash",scalar(s,"parsedSourceHash"),"routingSha256",scalar(b,"routingSha256"),"generationSha256",scalar(g,"generationSha256"),"candidateGraphSha256",CanonicalJson.scalar(graphSha));
        expectedIdentity.put("sourceRevisionSha256",revision);require(expectedIdentity.equals(i),"NORMAL_OBSERVED_IDENTITY_MISMATCH");
        for(String key:Arrays.asList("originalSourceSha256","javaParsedSourceHash","routingSha256","scopePolicySha256","generationSha256"))sha(i.path(key).asText());
        ObjectNode canonical=object();canonical.set("units",g.path("units"));canonical.set("relations",g.path("relations"));canonical.putArray("assetRefs");
        require(canonical.equals(p.path("canonical"))&&g.path("unknowns").equals(p.path("unknowns"))&&p.path("assetRefs").isArray()&&p.path("assetRefs").isEmpty(),"NORMAL_OBSERVED_COMPLETE_GRAPH_MISMATCH");
        String sourceHash=hashFields("projectionIdentity",ir,"canonical",CanonicalJson.field(raw,"canonical"));
        String projectionRevision=hashFields("sourceHash",CanonicalJson.scalar(sourceHash),"projectionIdentity",ir);
        require(sourceHash.equals(p.path("sourceHash").asText())&&projectionRevision.equals(p.path("projectionRevisionSha256").asText()),"NORMAL_OBSERVED_REVISION_MISMATCH");
        ObjectNode source=object();source.set("sourceSha256",s.path("originalSourceSha256"));source.put("sourceRevisionSha256",revision);
        source.set("filename",s.path("filename"));source.set("sourceFilename",s.path("filename"));source.set("parsedSourceHash",s.path("parsedSourceHash"));source.set("projectId",s.path("projectId"));source.set("documentId",s.path("documentId"));
        require(source.equals(p.path("source")),"NORMAL_OBSERVED_SOURCE_MISMATCH");
        JsonNode descriptor=p.path("observationManifestRef");require(manifestSha.equals(descriptor.path("sha256").asText())&&descriptor.path("byteCount").asLong()==Utf8.byteCount(mr),"NORMAL_OBSERVED_MANIFEST_REF_MISMATCH");
        ArrayNode expectedChunks=JsonUtils.mapper().createArrayNode(),nonText=JsonUtils.mapper().createArrayNode();Set<String> units=new HashSet<>(),blocks=new HashSet<>();int previous=-1;
        for(JsonNode unit:g.path("units")){
            require(unit.path("unitId").asText().matches("candidate-unit:[a-f0-9]{64}")&&units.add(unit.path("unitId").asText())&&unit.path("sourceOrder").isIntegralNumber()&&unit.path("sourceOrder").asInt()>previous,"NORMAL_OBSERVED_UNIT_INVALID");previous=unit.path("sourceOrder").asInt();
            require("candidate_unverified".equals(unit.path("adoptionStatus").asText())&&unit.path("semanticScopeVerified").isBoolean()&&!unit.path("semanticScopeVerified").asBoolean()&&"unknown".equals(unit.path("qualifiersComplete").asText()),"NORMAL_OBSERVED_UNIT_AUTHORITY_INVALID");
            require(unit.path("parts").isArray()&&!unit.path("parts").isEmpty()&&unit.path("parts").size()==unit.path("requiredMemberBlockIds").size()&&unit.path("parts").size()==unit.path("sourceSpans").size(),"NORMAL_OBSERVED_PARTS_INVALID");
            List<String> texts=new ArrayList<>();SortedSet<Integer> pages=new TreeSet<>();int n=0;
            for(JsonNode part:unit.path("parts")){
                JsonNode span=unit.path("sourceSpans").get(n),member=unit.path("requiredMemberBlockIds").get(n++);String text=part.path("text").asText();
                require(part.path("text").isTextual()&&member.equals(part.path("blockId"))&&member.equals(span.path("blockId"))&&blocks.add(member.asText())
                    &&part.path("startOffset").isIntegralNumber()&&part.path("startOffset").asInt()==0&&part.path("endOffset").isIntegralNumber()&&part.path("endOffset").asInt()==text.length()
                    &&part.path("startOffset").equals(span.path("startOffset"))&&part.path("endOffset").equals(span.path("endOffset"))&&"utf16".equals(part.path("offsetUnit").asText())&&part.path("offsetUnit").equals(span.path("offsetUnit"))
                    &&part.path("sourceSha256").equals(s.path("originalSourceSha256"))&&part.path("sourceSha256").equals(span.path("sourceSha256"))&&part.path("sourcePosition").isObject()&&part.path("sourcePosition").equals(span.path("sourcePosition")),"NORMAL_OBSERVED_PART_MISMATCH");
                if(!part.path("physicalPageNo").isNull()&&!part.path("physicalPageNo").isMissingNode()){require(part.path("physicalPageNo").isIntegralNumber()&&part.path("physicalPageNo").asInt()>0,"NORMAL_OBSERVED_PAGE_INVALID");pages.add(part.path("physicalPageNo").asInt());}texts.add(text);
            }
            require(unit.path("text").isTextual()&&String.join("\n",texts).equals(unit.path("text").asText())&&JsonUtils.mapper().valueToTree(pages).equals(unit.path("physicalPages")),"NORMAL_OBSERVED_UNIT_MISMATCH");
            if(noObservedText(unit.path("text").asText())){ObjectNode skipped=nonText.addObject();skipped.set("unitId",unit.path("unitId"));skipped.set("sourceOrder",unit.path("sourceOrder"));skipped.put("reason","no_observed_text");continue;}
            expectedChunks.add(chunk(unit,g,i,sourceHash,revision,projectionRevision,manifestSha));
        }
        for(JsonNode relation:g.path("relations"))require(relation.path("humanConfirmed").isBoolean()&&!relation.path("humanConfirmed").asBoolean()&&units.contains(relation.path("fromUnitId").asText())&&(relation.path("toUnitId").isNull()||!relation.has("toUnitId")||units.contains(relation.path("toUnitId").asText())),"NORMAL_OBSERVED_RELATION_INVALID");
        require(expectedChunks.equals(p.path("chunks"))&&nonText.equals(p.path("nonRetrievableUnits")),"NORMAL_OBSERVED_COMPLETE_CHUNKS_MISMATCH");return p;
    }
    private static ObjectNode chunk(JsonNode u,JsonNode graph,JsonNode identity,String sourceHash,String revision,String projectionRevision,String manifestSha){
        ObjectNode c=object();c.put("id","canonical:"+hashFields("revision",CanonicalJson.scalar(projectionRevision),"unitId",scalar(u,"unitId")));
        c.put("producerKind",PRODUCER);c.put("projectionVersion",VERSION);c.put("metadataVersion",VERSION);c.put("segmentationVersion","complete-normal-source-unit-boundaries-v1");c.put("nativeTableMetadataVersion","normal-parser-preserved-table-candidates-v1");c.put("sourceQualityMetadataVersion","unreviewed-normal-observation-quality-v1");c.put("addressKind","normal_observed_unit");c.set("content",u.path("text"));
        if(u.path("parts").get(0).path("anchor").isTextual())c.set("anchor",u.path("parts").get(0).path("anchor"));
        c.put("sourceHash",sourceHash);c.set("sourceSha256",identity.path("originalSourceSha256"));c.set("originalSourceSha256",identity.path("originalSourceSha256"));c.put("sourceRevisionSha256",revision);c.put("projectionRevisionSha256",projectionRevision);c.put("observationManifestSha256",manifestSha);
        ObjectNode quality=declarations();quality.put("status","PARSED");quality.put("semanticQuality","unknown");c.set("sourceQuality",quality);c.put("sourceQualityHash",CanonicalJson.hash(JsonUtils.write(quality),null));c.putArray("assetRefs");declarations().fields().forEachRemaining(e->c.set(e.getKey(),e.getValue()));
        for(String key:Arrays.asList("unitId","kind","parts","sourceSpans","physicalPages","sourceOrder","tableGroup","unknowns","requiredMemberBlockIds","qualifiersComplete"))if(!u.path(key).isNull()&&!u.path(key).isMissingNode())c.set(key,u.path(key));
        for(String key:Arrays.asList("documentId","role","module","eligibleModules","sourcePurpose","sourceClass"))c.set(key,identity.path(key));
        ArrayNode relations=c.putArray("relations");for(JsonNode r:graph.path("relations"))if(u.path("unitId").equals(r.path("fromUnitId"))||u.path("unitId").equals(r.path("toUnitId")))relations.add(r);return c;
    }
    public static void validateChunk(JsonNode chunk){require(PRODUCER.equals(chunk.path("producerKind").asText())&&VERSION.equals(chunk.path("metadataVersion").asText())&&"normal_observed_unit".equals(chunk.path("addressKind").asText())&&chunk.path("parts").isArray()&&!chunk.path("parts").isEmpty(),"NORMAL_OBSERVED_CHUNK_INVALID");declarations(chunk);declarations(chunk.path("sourceQuality"));require("unknown".equals(chunk.path("sourceQuality").path("semanticQuality").asText()),"NORMAL_OBSERVED_AUTHORITY_MISMATCH");}
    static boolean noObservedText(String text){return text.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85);}
    private static ObjectNode declarations(){ObjectNode d=object();d.put("adoptionStatus","candidate_unverified");d.put("humanConfirmed",false);d.put("sourceQualityAccepted",false);d.put("semanticScopeVerified",false);d.put("legalClosure","unknown");return d;}
    private static void declarations(JsonNode node){declarations().fields().forEachRemaining(e->require(e.getValue().equals(node.path(e.getKey())),"NORMAL_OBSERVED_AUTHORITY_MISMATCH"));require(!node.has("adoption")&&!node.has("reference"),"NORMAL_OBSERVED_AUTHORITY_MISMATCH");}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static String scalar(JsonNode node,String name){require(node.path(name).isTextual(),"NORMAL_OBSERVED_IDENTITY_MISMATCH");return CanonicalJson.scalar(node.path(name).asText());}
    private static String hashFields(String... pairs){Map<String,String> fields=new HashMap<>();for(int n=0;n<pairs.length;n+=2)fields.put(pairs[n],pairs[n+1]);return CanonicalJson.hash(CanonicalJson.rawObject(fields),null);}
    private static void keys(JsonNode node,String... names){Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);require(actual.equals(new HashSet<>(Arrays.asList(names))),"NORMAL_OBSERVED_FIELDS_INVALID");}
    private static void sha(String value){require(value.matches("[a-f0-9]{64}"),"NORMAL_OBSERVED_SHA_INVALID");}
    private static void require(boolean ok,String reason){if(!ok)throw new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,reason);}
}
