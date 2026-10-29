package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalSourcePageService;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.service.sources.NativeDocumentCollectionContract;
import com.consense.service.sources.NativePdfCanonicalContract;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.sources.SourceContentDigests;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/** Shared current-source corpus, with full sealed evidence beside a model/citation read-only view. */
@Service @RequiredArgsConstructor
public class OerBusinessEvidence {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OerBusinessEvidence.class);
    private final CanonicalEvidenceService evidence;
    private final OerCanonicalRetrievalClient retrieval;
    private final CanonicalEvidenceProperties properties;
    @Autowired(required=false) private CanonicalSourcePageService sourcePages;
    @Autowired private CanonicalCurrentSourceReader currentSources;

    public boolean enabled(){return !JsonUtils.isBlankText(properties.getBaseUrl());}
    public static final class Session implements AutoCloseable {
        public final String projectId,module,indexKey;
        public final List<Long> documentIds;
        private final String bindingFingerprint;
        private final Map<String,String> sourceRenderPins;
        private final Map<String,Chunk> byId;
        private final Map<String,String> viewPayloads;
        private final Corpus chunks;
        private volatile boolean invalidated;
        private OerBusinessEvidence readOwner;
        private CurrentStage readStage;
        private List<CanonicalSourceBinding> queryScope;
        private Supplier<List<SourceDocument>> querySelection;
        private String querySourceGeneration;
        private Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originalFileStates;
        private String initialSourceGeneration;
        private boolean closedSourceProof;
        private Runnable initialAskExit;
        private Runnable adviceTextByteGuard;
        private String adviceQuestion;
        private int queryChunkCount;
        private CanonicalEvidenceService.CompactBindingScope semanticScope;
        private OerCanonicalRetrievalClient.SemanticSources semanticSources;
        private Map<Long,SourceDocument> semanticDocuments;
        private Map<String,JsonNode> semanticContexts;
        private Map<String,ObservedSourceClauseLocators.Bound> semanticLocators=Collections.emptyMap();
        private Map<String,List<String>> semanticAnchors;
        private Map<String,String> semanticHeadingLocations=Collections.emptyMap();
        private List<VettingRuleEngine.Source> semanticRuleSources;
        private boolean scanned;
        private final List<JsonNode> semanticBlocked=new ArrayList<>();
        private final Set<String> deferredTextOrigins=new LinkedHashSet<>();
        private Session reviewQuery;
        private ReviewTextIndex reviewTextIndex;
        private Session(String project,String module,List<Long> ids,List<CanonicalSourceBinding> bindings,Map<String,Chunk> views,Map<String,String> pins){
            this.projectId=project;this.module=module;this.documentIds=Collections.unmodifiableList(new ArrayList<>(ids));
            this.bindingFingerprint=fingerprint(bindings);this.byId=views;
            this.sourceRenderPins=Collections.unmodifiableMap(new TreeMap<>(pins));
            this.viewPayloads=new LinkedHashMap<>();views.forEach((id,view)->viewPayloads.put(id,JsonUtils.serializedSha256(view)));
            Object projections=bindings.stream().map(CanonicalSourceBinding::getProjectionSha256).collect(Collectors.toList());
            this.indexKey=project+"::canonical::"+module+"::"+VettingCorpus.hash(JsonUtils.write(pins.isEmpty()?projections:Arrays.asList(projections,sourceRenderPins)));
            this.chunks=new Corpus(this,new ArrayList<>(views.values()));
        }
        private Session(String project,String module,CanonicalEvidenceService.CompactBindingScope scope,OerCanonicalRetrievalClient.SemanticSources sources,
                        Map<Long,SourceDocument> documents,Map<String,JsonNode> contexts,Map<String,List<String>> anchors,List<VettingRuleEngine.Source> rules,Map<String,String> pins){
            projectId=project;this.module=module;semanticScope=scope;semanticSources=sources;semanticDocuments=documents;semanticContexts=contexts;semanticAnchors=anchors;
            semanticRuleSources=rules;
            documentIds=Collections.unmodifiableList(new ArrayList<>(documents.keySet()));bindingFingerprint=fingerprint(scope.bindings());sourceRenderPins=Collections.unmodifiableMap(new TreeMap<>(pins));
            byId=new LinkedHashMap<>();viewPayloads=new LinkedHashMap<>();chunks=new Corpus(this,new ArrayList<>());indexKey=project+"::semantic::"+module+"::"+sources.identitySha256();
        }
        /** Only an explicitly opened initial read owns a frame; later operations open fresh stages. */
        @Override public void close(){
            CurrentStage held=readStage;if(held==null)return;OerBusinessEvidence owner=readOwner;
            if(held.frame!=null)owner.evidence.assertFrameOwner(held.frame,projectId,module);
            readStage=null;readOwner=null;
            try{held.close();owner.complete(this,held);closedSourceProof=held.frame!=null&&(initialSourceGeneration!=null||queryScope!=null);}
            catch(RuntimeException|Error changed){invalidated=true;throw changed;}
        }
        public List<Chunk> chunks(){return chunks;}
        public int chunkCount(){return queryScope==null?chunks.size():queryChunkCount;}
        public void completeInitialAsk(){
            close();if(initialAskExit!=null)try{initialAskExit.run();initialAskExit=null;closedSourceProof=true;}
            catch(RuntimeException|Error changed){invalidated=true;throw changed;}
        }
        public boolean hasClosedSourceProof(){return initialAskExit==null&&(closedSourceProof||queryScope!=null&&readStage==null);}
        public String identitySha256(){return renderFingerprint(bindingFingerprint,sourceRenderPins);}
        public JsonNode semanticReadCoverage(){ObjectNode ledger=JsonUtils.mapper().createObjectNode();ledger.put("status",semanticBlocked.isEmpty()?"complete":"partial");ledger.put("totalOrigins",semanticSources.totalOrigins());ledger.put("readableOrigins",chunks.size());ledger.set("blockedOrigins",JsonUtils.mapper().valueToTree(semanticBlocked));return ledger;}
    }
    private static final class Corpus extends AbstractList<Chunk> implements VettingRetrievalSourceUnits.AttachedUnits{
        final Session session;private final List<Chunk> views,owned;
        private final List<VettingRetrievalSourceUnits.Unit> sourceUnits=new ArrayList<>();
        Corpus(Session s,List<Chunk> views){owned=views;this.views=Collections.unmodifiableList(views);session=s;}
        public Chunk get(int i){return views.get(i);}public int size(){return views.size();}
        public List<VettingRetrievalSourceUnits.Unit> sourceUnits(){return Collections.unmodifiableList(sourceUnits);}
    }
    private static final class SharedSemanticSourceUnit {
        final Chunk first;
        final JsonNode template;
        final VettingRetrievalSourceUnits.Unit unit;
        final Set<String> declaredOrigins;
        SharedSemanticSourceUnit(Chunk first,VettingRetrievalSourceUnits.Unit unit){
            this.first=first;this.template=first.getCanonicalPayload().path("semanticUnit");this.unit=unit;
            JsonNode hierarchy=template.get("hierarchyContext"),attachment=template.get("attachmentContext");
            List<JsonNode> contexts=new ArrayList<>();
            if(attachment!=null&&attachment.isObject()){
                if(hierarchy!=null&&hierarchy.isObject())contexts.add(hierarchy);contexts.add(attachment);
            }else contexts.add(hierarchy!=null&&hierarchy.isObject()&&completeTransport(hierarchy)?hierarchy:template);
            Set<String> required=null;
            for(JsonNode context:contexts){
                if(!completeTransport(context))break;
                Set<String> ids=new HashSet<>(VettingRetrievalSourceUnits.textIds(context.path("requiredMemberIds")));
                if(required==null)required=ids;else required.retainAll(ids);
            }
            declaredOrigins=required==null?Collections.emptySet():required;
        }
        VettingRetrievalSourceUnits.Unit forOrigin(Chunk origin){
            require(template==origin.getCanonicalPayload().path("semanticUnit"),"semantic dictionary group changed during scan");
            require(Objects.equals(first.getDocumentId(),origin.getDocumentId())&&Objects.equals(first.getOriginalSourceSha256(),origin.getOriginalSourceSha256())
                    &&Objects.equals(first.getSourceHash(),origin.getSourceHash())&&Objects.equals(first.getRole(),origin.getRole()),"semantic dictionary closure crosses source scope");
            if(!declaredOrigins.isEmpty())require(declaredOrigins.contains(origin.getId()),"semantic dictionary closure omitted its origin");
            return new VettingRetrievalSourceUnits.Unit(origin.getId(),unit);
        }
    }
    public static boolean canonicalCorpus(List<Chunk> corpus){return corpus instanceof Corpus;}
    public static Session session(List<Chunk> corpus){if(!(corpus instanceof Corpus))throw new IllegalArgumentException("Canonical corpus session required");return ((Corpus)corpus).session;}
    public static boolean isSemanticCorpus(List<Chunk> corpus){return canonicalCorpus(corpus)&&session(corpus).semanticScope!=null;}
    /** The source reader grants this operation-local capability; public unknown JSON cannot create it. */
    static Set<String> deferredTextOrigins(List<Chunk> corpus){
        if(!isSemanticCorpus(corpus))return Collections.emptySet();Session selected=session(corpus);
        require(!selected.invalidated,"canonical session invalidated; reload current sources");
        return selected.scanned&&selected.semanticSources.deferredText()?Collections.unmodifiableSet(new LinkedHashSet<>(selected.deferredTextOrigins)):Collections.emptySet();
    }
    public Session loadSemantic(String projectId,String module,List<SourceDocument> selected){
        require("vetting".equals(module),"semantic full-scope consumption is configured for vetting only");Map<Long,SourceDocument> documents=selectedDocuments(projectId,selected);
        String[] admission={"compact_metadata_unavailable"};int[] qualified={0};java.util.function.BiConsumer<String,Integer> record=(reason,count)->{admission[0]=reason;qualified[0]=count;};
        List<CanonicalSourceBinding> normalCandidates=evidence.compactNormalCandidates(projectId,module,selected,record);
        if(normalCandidates!=null&&!normalCandidates.isEmpty()){
            if(!sourceRenderPins(normalCandidates).isEmpty())record.accept("selected_render_pins",normalCandidates.size());
            else if(!retrieval.compactNormalPreparationAvailable(module,normalCandidates))record.accept("native_capability_unavailable",normalCandidates.size());
            else{Session compact=loadCompactNormalSemantic(projectId,module,documents,normalCandidates,record);if(compact!=null)return compact;}
        }
        logCompactAdmission(selected.size(),qualified[0],admission[0]);
        Map<Long,SourceDocument> admitted=new LinkedHashMap<>();List<CanonicalEvidenceService.CompactBindingScope> scopes=new ArrayList<>();
        Map<String,JsonNode> contexts=new LinkedHashMap<>();Map<String,List<String>> anchors=new LinkedHashMap<>();Map<String,String> headingLocations=new LinkedHashMap<>();
        Map<String,ObservedSourceClauseLocators.Bound> locators=new LinkedHashMap<>();List<VettingRuleEngine.Source> rules=new ArrayList<>();int timedSources=0;
        for(SourceDocument captured:documents.values()){
            long started=System.nanoTime(),prepared=-1,framed=-1,semantic=-1,closed=-1;
            boolean compact=payloadFree(captured)&&SourceContentDigests.stamped(captured);
            try{
                SourceDocument source=compact?evidence.prepareVettingSource(captured):captured;
                prepared=System.nanoTime();
                source.setParseMessage(captured.getParseMessage());source.setPageCount(captured.getPageCount());source.setOcrUsed(captured.getOcrUsed());
                CanonicalEvidenceService.CompactBindingScope scope;CurrentStage loaded=stage(projectId,module,Collections.singletonList(source.getId()),Collections.singletonList(source));
                try(CurrentStage held=loaded){
                    require(held.frame!=null,"semantic preparation requires an owned source frame");evidence.prepareCompactIdentities(held.frame,Collections.singletonList(source));held.bindings=held.frame.bindings();
                    require(held.bindings.size()==1&&source.getId().equals(held.bindings.get(0).getDocumentId()),"selected binding order differs");
                    framed=System.nanoTime();
                    prepareSemanticSource(projectId,module,source,held.bindings.get(0),held,contexts,anchors,headingLocations,locators,rules);
                    semantic=System.nanoTime();
                    scope=evidence.compactBindings(held.frame,Collections.singletonList(source));
                }
                closed=System.nanoTime();
                SourceDocument pins=CanonicalCurrentSourceReader.compactVerifiedSource(source);
                if(compact)org.springframework.beans.BeanUtils.copyProperties(pins,captured);
                admitted.put(source.getId(),pins);scopes.add(scope);
            }catch(com.consense.service.sources.CanonicalEvidenceException missing){
                if(!compact||!"NORMAL_OBSERVED_SOURCE_EVIDENCE_MISSING".equals(missing.getMessage()))throw missing;
                LOG.warn("Selected Vetting source {} has no usable observed evidence and remains unreviewed",captured.getId());
            }finally{
                if(timedSources++<33)LOG.info("Vetting source timing documentId={} totalMs={} prepareMs={} bindingMs={} semanticMs={} closeMs={}",captured.getId(),(System.nanoTime()-started)/1_000_000,
                        prepared<0?-1:(prepared-started)/1_000_000,framed<0?-1:(framed-prepared)/1_000_000,semantic<0?-1:(semantic-framed)/1_000_000,closed<0?-1:(closed-semantic)/1_000_000);
            }
        }
        require(!admitted.isEmpty(),"no usable selected source observations");
        CanonicalEvidenceService.CompactBindingScope scope=evidence.joinCompactScopes(scopes);List<CanonicalSourceBinding> bindings=scope.bindings();
        OerCanonicalRetrievalClient.SemanticSources sources=retrieval.prepareSemanticSources(module,bindings);
        // Native prepare already closed the exact actual-byte scope; retain fresh scalar revision checks.
        evidence.assertCompactCurrent(scope);
        Session result=new Session(projectId,module,scope,sources,admitted,contexts,anchors,rules,sourceRenderPins(bindings));
        result.semanticHeadingLocations=Collections.unmodifiableMap(headingLocations);result.semanticLocators=Collections.unmodifiableMap(locators);
        current(result);return result;
    }
    /** The current source graph supplies only rule/locator inputs; native prepare owns the full projection proof. */
    private Session loadCompactNormalSemantic(String projectId,String module,Map<Long,SourceDocument> documents,List<CanonicalSourceBinding> bindings,java.util.function.BiConsumer<String,Integer> record){
        long started=System.nanoTime();
        Map<Long,CanonicalSourceBinding> byDocument=new LinkedHashMap<>();for(CanonicalSourceBinding binding:bindings)require(byDocument.putIfAbsent(binding.getDocumentId(),binding)==null,"duplicate compact normal source");
        Map<String,List<String>> anchors=new LinkedHashMap<>();List<VettingRuleEngine.Source> rules=new ArrayList<>();
        CanonicalEvidenceService.CompactNormalPreparation prepared=evidence.prepareCompactNormalSources(projectId,module,new ArrayList<>(documents.values()),bindings,
                (source,graph)->prepareCompactNormalRuleSource(projectId,source,graph,byDocument.get(source.getId()),anchors,rules),record);
        if(prepared==null)return null;
        logCompactAdmission(bindings.size(),bindings.size(),"eligible");
        LOG.info("OER compact normal Java preparation sources={} elapsedMs={}",bindings.size(),(System.nanoTime()-started)/1_000_000);
        OerCanonicalRetrievalClient.SemanticSources sources=retrieval.prepareCompactNormalSources(module,prepared.bindings());
        evidence.attestCompactNormalSources(prepared,sources.normalProofs());
        CanonicalEvidenceService.CompactBindingScope scope=prepared.scope();evidence.assertCompactCurrent(scope);
        Map<Long,SourceDocument> admitted=prepared.sources();
        admitted.forEach((id,pins)->{SourceDocument captured=documents.get(id);pins.setParseMessage(captured.getParseMessage());pins.setPageCount(captured.getPageCount());pins.setOcrUsed(captured.getOcrUsed());
            if(payloadFree(captured))org.springframework.beans.BeanUtils.copyProperties(pins,captured);});
        Session result=new Session(projectId,module,scope,sources,admitted,new LinkedHashMap<>(),anchors,rules,sourceRenderPins(bindings));
        current(result);return result;
    }
    private static void logCompactAdmission(int selected,int qualified,String reason){LOG.info("Vetting compact normal admission selectedSources={} qualifiedPrefix={} reason={}",selected,qualified,reason);}
    private static void prepareCompactNormalRuleSource(String projectId,SourceDocument source,JsonNode graph,CanonicalSourceBinding binding,Map<String,List<String>> anchors,List<VettingRuleEngine.Source> rules){
        require(binding!=null&&Objects.equals(source.getId(),binding.getDocumentId()),"compact normal source outside selected bindings");
        List<com.consense.document.DocumentBlock> blocks=new ArrayList<>();Set<String> observed=new HashSet<>();RuleSourceRead read=new RuleSourceRead(source);
        for(JsonNode unit:graph.path("units")){
            if(NormalObservedContract.noObservedText(unit.path("text").asText()))continue;
            String id=NormalObservedContract.canonicalChunkId(binding.getProjectionRevisionSha256(),unit.path("unitId").asText());List<String> locations=new ArrayList<>();
            for(JsonNode raw:unit.path("parts"))locations.add(raw.path("anchor").asText());require(anchors.putIfAbsent(id,locations)==null,"duplicate canonical chunk");
            if(!"tender".equals(VettingCorpus.sourceRole(source)))continue;
            for(JsonNode raw:unit.path("parts")){
                Part part=new Part();part.setBlockId(raw.path("blockId").asText());part.setAnchor(raw.path("anchor").asText());part.setText(raw.path("text").asText());part.setStartOffset(raw.path("startOffset").asInt());part.setEndOffset(raw.path("endOffset").asInt());
                if(!observed.add(part.getBlockId()+"|"+part.getStartOffset()+"|"+part.getEndOffset()))continue;
                com.consense.document.DocumentBlock target=new com.consense.document.DocumentBlock();target.setId(part.getBlockId());target.setLocation(part.getAnchor());target.setText(part.getText());target.setOriginalText(part.getText());target.setSource("normal_observed_candidate");
                if(raw.path("physicalPageNo").isIntegralNumber()&&raw.path("physicalPageNo").asInt()>0)target.setPageNo(raw.path("physicalPageNo").asInt());
                bindRuleContext(projectId,read,String.valueOf(source.getId()),binding.getJavaParsedSourceHash(),binding.getOriginalSourceSha256(),unit.path("parts"),part,target);blocks.add(target);
            }
        }
        if(!blocks.isEmpty())rules.add(new VettingRuleEngine.Source(String.valueOf(source.getId()),source.getFileKey()==null?"OTHER":source.getFileKey(),source.getFileName(),binding.getJavaParsedSourceHash(),blocks,blocks.stream().map(com.consense.document.DocumentBlock::getText).collect(Collectors.joining("\n"))));
    }
    private static boolean payloadFree(SourceDocument source){return source.getTextContent()==null&&source.getStructuredContentJson()==null
            &&source.getParseCoverageJson()==null&&source.getNormalCandidateJson()==null;}
    private SourceDocument semanticDocument(Session session,Long id){
        SourceDocument captured=session.semanticDocuments.get(id);require(captured!=null,"selected semantic source missing");
        SourceDocument source=currentSources.normalDocument(session.projectId,id,session.module);
        currentSources.requireCurrentStamp(captured,source);return source;
    }
    private void completeSemanticDocument(Session session,CurrentStage held,Long id){
        List<CanonicalSourceBinding> expected=session.semanticScope.bindings().stream().filter(b->id.equals(b.getDocumentId())).collect(Collectors.toList());
        require(expected.size()==1&&fingerprint(expected).equals(fingerprint(held.bindings)),"selected semantic binding changed");
        require(sourceRenderPins(expected).equals(sourceRenderPins(held.bindings)),"selected semantic render identity changed");
    }
    private void prepareSemanticSource(String projectId,String module,SourceDocument source,CanonicalSourceBinding binding,CurrentStage held,
            Map<String,JsonNode> contexts,Map<String,List<String>> anchors,Map<String,String> headingLocations,
            Map<String,ObservedSourceClauseLocators.Bound> locators,List<VettingRuleEngine.Source> rules){
        require(source!=null,"semantic source binding is outside selected documents");
        Session cold=loadSession(projectId,module,Collections.singletonMap(source.getId(),source),held,Collections.singletonList(binding));
        OerModelMaterial.Request material=new OerModelMaterial.Request(Collections.singletonList(source));
        for(Chunk chunk:cold.chunks){
            require(!anchors.containsKey(chunk.getId()),"duplicate canonical chunk");
            anchors.put(chunk.getId(),chunk.getParts().stream().map(Part::getAnchor).collect(Collectors.toList()));
            headingLocations.put(chunk.getId(),chunk.getClauseHeadingLocation());
            if(NormalObservedContract.isNormal(chunk.getCanonicalPayload()))continue;
            EvidencePointer pointer=JsonUtils.read(JsonUtils.write(chunk.getCanonicalEvidence()),EvidencePointer.class);
            JsonNode word=held.frame.wordSourceScopeContext(pointer.getDocumentId(),pointer.getProjectionSha256(),pointer.getCanonicalChunkId(),module);
            if(word!=null)pointer.setNativeWordSourceScopesContext(word);
            contexts.put(chunk.getId(),VettingSourceMaterial.semantic(material.projectStandalone(pointer,chunk.getCanonicalPayload(),chunk.getRole()),Collections.emptyMap()));
            ObservedSourceClauseLocators.Bound locator=ObservedSourceClauseLocators.capture(pointer,chunk.getCanonicalPayload(),chunk.getRole(),word);
            if(locator!=null)locators.put(chunk.getId(),locator);
        }
        rules.addAll(buildRuleSources(cold,Collections.singletonList(source)));
    }
    private final class SemanticScanAccumulator {
        final Session session;final List<Chunk> collected=new ArrayList<>();final Set<String> ids=new HashSet<>();
        final Map<String,JsonNode> groups=new LinkedHashMap<>();final List<JsonNode> blocked=new ArrayList<>();
        final Set<String> deferred=new LinkedHashSet<>();
        JsonNode cursor;int processed,batch;
        SemanticScanAccumulator(Session session){this.session=session;}
        void accept(JsonNode result,long readElapsedMs){
            evidence.assertCompactCurrent(session.semanticScope);
            require(!"unit_too_large".equals(result.path("coverage").path("status").asText()),"a complete semantic source unit exceeds the bounded read budget");
            boolean dictionary=OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY.equals(result.path("semanticReadVersion").asText());
            if(dictionary){for(JsonNode row:result.path("units")){String group=row.path("groupId").asText();JsonNode prior=groups.putIfAbsent(group,row.path("unit"));require(prior==null||prior.equals(row.path("unit")),"semantic group changed during scan");}
                for(JsonNode row:result.path("blockedOrigins")){require(ids.add(row.path("id").asText()),"semantic scan repeated blocked origin");blocked.add(row.deepCopy());}}
            for(int i=0;i<result.path("origins").size();i++){JsonNode origin=result.path("origins").get(i);String group=dictionary?result.path("originUnits").path(origin.path("id").asText()).asText():null;JsonNode unit=dictionary?groups.get(group):result.path("units").get(i);require(unit!=null,"semantic scan group missing");Chunk chunk=semanticView(session,origin,unit,dictionary);if(dictionary)((ObjectNode)chunk.getCanonicalPayload()).put("semanticUnitRef",group);require(ids.add(chunk.getId()),"semantic scan repeated an origin");if(session.semanticSources.deferredNormalOrigin(origin,unit))deferred.add(chunk.getId());collected.add(chunk);}
            int next=result.path("coverage").path("processedOrigins").asInt();require(next==processed+result.path("origins").size()+(dictionary?result.path("blockedOrigins").size():0),"semantic scan skipped origins");processed=next;cursor=result.path("nextCursor");
            LOG.info("OER semantic source scan batch={} processedOrigins={}/{} readElapsedMs={}",++batch,processed,session.semanticSources.totalOrigins(),readElapsedMs);
            evidence.assertCompactCurrent(session.semanticScope);
        }
    }
    public List<Chunk> scan(Session session){
        return scan(session,(processed,total)->{});
    }
    public List<Chunk> scan(Session session,java.util.function.BiConsumer<Integer,Integer> progress){
        require(session!=null&&session.semanticScope!=null,"semantic scan requires its selected source session");current(session);if(session.scanned)return session.chunks;
        SemanticScanAccumulator read=new SemanticScanAccumulator(session);
        if(session.semanticSources.stream()){
            long[] previous={System.nanoTime()};evidence.assertCompactCurrent(session.semanticScope);
            retrieval.streamSemanticSources(session.semanticSources,page->{long now=System.nanoTime();read.accept(page,(now-previous[0])/1_000_000);progress.accept(read.processed,session.semanticSources.totalOrigins());previous[0]=System.nanoTime();});
            evidence.assertCompactCurrent(session.semanticScope);
        }else do{
            ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","scan");demand.put("maxItems",session.semanticSources.scanMaximumItems());demand.put("maxBytes",16777216);
            if(read.cursor!=null&&!read.cursor.isNull())demand.set("cursor",read.cursor);
            if(session.semanticSources.dictionary())demand.set("knownSemanticGroups",JsonUtils.mapper().valueToTree(read.groups.keySet().stream().skip(Math.max(0,read.groups.size()-512)).collect(Collectors.toList())));
            evidence.assertCompactCurrent(session.semanticScope);long started=System.nanoTime();
            read.accept(retrieval.readSemanticSources(session.semanticSources,demand),(System.nanoTime()-started)/1_000_000);
            progress.accept(read.processed,session.semanticSources.totalOrigins());
        }while(read.cursor!=null&&!read.cursor.isNull());
        require(read.processed==session.semanticSources.totalOrigins(),"semantic scan did not cover its declared source scope");
        List<Chunk> collected=read.collected;List<JsonNode> blocked=read.blocked;
        long finalizeStarted=System.nanoTime();
        Map<String,Chunk> byId=new LinkedHashMap<>();Map<String,Integer> sourceOrder=new HashMap<>();
        for(int i=0;i<collected.size();i++){Chunk chunk=collected.get(i);require(byId.putIfAbsent(chunk.getId(),chunk)==null,"duplicate semantic origin");sourceOrder.put(chunk.getId(),i);}
        List<VettingRetrievalSourceUnits.Unit> sourceUnits=new ArrayList<>();Map<String,SharedSemanticSourceUnit> sharedUnits=new HashMap<>();
        for(Chunk chunk:collected){String group=chunk.getCanonicalPayload().path("semanticUnitRef").asText(null);
            SharedSemanticSourceUnit shared=group==null?null:sharedUnits.get(group);
            if(shared!=null)sourceUnits.add(shared.forOrigin(chunk));
            else{VettingRetrievalSourceUnits.Unit unit=semanticSourceUnit(byId,sourceOrder,chunk);sourceUnits.add(unit);if(group!=null)sharedUnits.put(group,new SharedSemanticSourceUnit(chunk,unit));}}
        long closuresBuilt=System.nanoTime();current(session);long sourceChecked=System.nanoTime();
        Map<String,String> viewPayloads=semanticViewPayloads(collected);
        for(Chunk chunk:collected)require(canonicalViewStatus(chunk)==null,"selected semantic view changed during scan");
        long viewsStamped=System.nanoTime();
        current(session);
        session.semanticBlocked.addAll(blocked);
        for(Chunk chunk:collected){session.byId.put(chunk.getId(),chunk);session.viewPayloads.put(chunk.getId(),viewPayloads.get(chunk.getId()));session.chunks.owned.add(chunk);}
        session.chunks.sourceUnits.addAll(sourceUnits);session.scanned=true;
        session.deferredTextOrigins.addAll(read.deferred);
        LOG.info("OER semantic scan finalized origins={} sharedGroups={} closureMs={} sourceCheckMs={} viewStampMs={} finalCheckMs={}",
                collected.size(),sharedUnits.size(),(closuresBuilt-finalizeStarted)/1_000_000,(sourceChecked-closuresBuilt)/1_000_000,
                (viewsStamped-sourceChecked)/1_000_000,(System.nanoTime()-viewsStamped)/1_000_000);
        return session.chunks;
    }
    public void bindReviewEvidence(Session session,List<com.consense.web.dto.VettingDtos.FindingEvidence> quotes){
        if(session.semanticScope==null){bindRuleEvidence(session,quotes);return;}current(session);for(com.consense.web.dto.VettingDtos.FindingEvidence quote:quotes)if(quote.getFixedBaselineEvidence()==null&&quote.getCanonicalEvidence()==null)bindRuleEvidenceUnchecked(session,Collections.singletonList(quote));
        Set<String> selectedIds=new LinkedHashSet<>();for(com.consense.web.dto.VettingDtos.FindingEvidence quote:quotes)if(quote.getCanonicalEvidence()!=null&&quote.getFixedBaselineEvidence()==null&&session.byId.containsKey(quote.getCanonicalEvidence().getCanonicalChunkId()))selectedIds.add(quote.getCanonicalEvidence().getCanonicalChunkId());
        checkSemanticViews(session,selectedIds.stream().map(session.byId::get).collect(Collectors.toList()));
        Map<String,JsonNode> fullMembers=selectedSemanticMembers(session,selectedIds);
        Set<com.consense.web.dto.VettingDtos.FindingEvidence> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Map<com.consense.web.dto.VettingDtos.FindingEvidence,EvidencePointer> verified=new IdentityHashMap<>();Map<String,List<EvidencePointer>> groups=new LinkedHashMap<>();
        for(com.consense.web.dto.VettingDtos.FindingEvidence quote:quotes){if(!seen.add(quote)||quote.getFixedBaselineEvidence()!=null)continue;EvidencePointer selected=quote.getCanonicalEvidence();if(selected==null)continue;
            Chunk captured=session.byId.get(selected.getCanonicalChunkId());
            // JSON copying can change Java null and missing-node representations without changing the source wire.
            if(captured==null||captured.getCanonicalEvidence()==null||!JsonUtils.serializedSha256(captured.getCanonicalEvidence()).equals(JsonUtils.serializedSha256(selected))||!Objects.equals(quote.getDocumentId(),captured.getDocumentId())
                    ||!Objects.equals(quote.getSourceHash(),selected.getJavaParsedSourceHash())||!VettingCorpus.normalize(captured.getContent()).contains(VettingCorpus.normalize(quote.getQuote()))
                    ||session.semanticSources.textFirst()&&!VettingCorpus.normalize(fullMembers.get(selected.getCanonicalChunkId()).path("text").asText()).contains(VettingCorpus.normalize(quote.getQuote()))){quote.setLocated(false);quote.setCanonicalEvidence(null);continue;}
            verified.put(quote,selected);groups.computeIfAbsent(selected.getDocumentId()+"|"+selected.getProjectionSha256(),ignored->new ArrayList<>()).add(selected);
        }
        Map<String,EvidencePointer> originals=selectedTextQuotePointers(session,groups,fullMembers);
        if(originals==null){originals=new HashMap<>();for(List<EvidencePointer> group:groups.values()){
            EvidencePointer first=group.get(0);List<String> unitIds=group.stream().map(EvidencePointer::getUnitId).distinct().collect(Collectors.toList());
            Map<String,EvidencePointer> selected=evidence.selectedPointers(session.projectId,first.getDocumentId(),first.getProjectionSha256(),unitIds,session.module);
            for(String unitId:unitIds){EvidencePointer original=selected.get(unitId);require(original!=null,"selected original evidence missing");originals.put(first.getDocumentId()+"|"+first.getProjectionSha256()+"|"+unitId,original);}
        }}
        for(com.consense.web.dto.VettingDtos.FindingEvidence quote:quotes){EvidencePointer selected=verified.remove(quote);if(selected==null)continue;
            EvidencePointer original=originals.get(selected.getDocumentId()+"|"+selected.getProjectionSha256()+"|"+selected.getUnitId());
            String text=VettingCorpus.normalize(quote.getQuote());boolean found=!text.isEmpty()&&VettingCorpus.normalize(java.util.stream.StreamSupport.stream(original.getParts().spliterator(),false).map(part->part.path("text").asText()).collect(Collectors.joining("\n"))).contains(text);
            if(found){quote.setCanonicalEvidence(JsonUtils.read(JsonUtils.write(original),EvidencePointer.class));quote.setSourceHash(original.getJavaParsedSourceHash());quote.setOriginalSourceSha256(original.getOriginalSourceSha256());
                List<JsonNode> parts=new ArrayList<>();for(JsonNode part:original.getParts())if(VettingCorpus.normalize(part.path("text").asText()).contains(text))parts.add(part);
                JsonNode position=parts.size()==1?VettingCorpus.nativePosition(parts.get(0).get("sourcePosition"),original.getOriginalSourceSha256()):null;
                quote.setNativePositions(position==null?null:Collections.singletonList(position));}
            else{quote.setLocated(false);quote.setCanonicalEvidence(null);}
        }current(session);
    }

    /** Exact quotation locators retain selected original parts; complete source context is loaded on demand. */
    private Map<String,EvidencePointer> selectedTextQuotePointers(Session session,Map<String,List<EvidencePointer>> groups,Map<String,JsonNode> members){
        if(groups.isEmpty())return Collections.emptyMap();
        if(!session.semanticSources.textFirst()||currentSources==null)return null;
        Set<Long> documentIds=new LinkedHashSet<>();Set<String> selectedIds=new LinkedHashSet<>();
        for(List<EvidencePointer> group:groups.values())for(EvidencePointer pointer:group){
            if(!NormalObservedContract.PRODUCER.equals(pointer.getProducerKind()))return null;
            documentIds.add(pointer.getDocumentId());selectedIds.add(pointer.getCanonicalChunkId());
        }
        List<SourceDocument> selected=new ArrayList<>();for(Long id:documentIds){SourceDocument source=session.semanticDocuments.get(id);require(source!=null,"selected quotation source missing");selected.add(source);}
        List<CanonicalSourceBinding> bindings=evidence.compactNormalCandidates(session.projectId,session.module,selected);if(bindings==null)return null;
        for(SourceDocument source:selected)currentSources.requireOriginalBytes(source);
        CanonicalEvidenceService.SelectedNormalTextSources text=evidence.selectedNormalTextSources(session.projectId,session.module,selected,bindings,selectedIds);if(text==null)return null;
        Map<Long,CanonicalSourceBinding> byDocument=new HashMap<>();for(CanonicalSourceBinding binding:bindings)byDocument.put(binding.getDocumentId(),binding);
        Map<Long,JsonNode> headers=text.headers();Map<String,JsonNode> chunks=text.chunks();Map<String,EvidencePointer> originals=new HashMap<>();
        for(List<EvidencePointer> group:groups.values())for(EvidencePointer captured:group){
            String key=captured.getDocumentId()+"|"+captured.getProjectionSha256()+"|"+captured.getUnitId();if(originals.containsKey(key))continue;
            JsonNode raw=chunks.get(captured.getCanonicalChunkId()),member=members.get(captured.getCanonicalChunkId());
            require(raw!=null&&member!=null&&raw.path("content").equals(member.path("text")),"selected quotation text differs from registered source");
            for(String field:Arrays.asList("id","unitId","documentId","role","sourceHash","eligibleModules","sourceOrder"))require(raw.path(field).equals(member.path(field)),"selected quotation identity differs from registered source");
            require(raw.path("parts").size()==member.path("parts").size(),"selected quotation part count differs");
            for(int i=0;i<raw.path("parts").size();i++)for(String field:Arrays.asList("blockId","text","startOffset","endOffset"))
                require(raw.path("parts").get(i).path(field).equals(member.path("parts").get(i).path(field)),"selected quotation part differs from registered source");
            CanonicalSourceBinding binding=byDocument.get(captured.getDocumentId());
            require(binding!=null&&Objects.equals(captured.getProjectionSha256(),binding.getProjectionSha256())&&Objects.equals(captured.getProjectionRevisionSha256(),binding.getProjectionRevisionSha256())
                    &&Objects.equals(captured.getSourceRevisionSha256(),binding.getSourceRevisionSha256())&&Objects.equals(captured.getOriginalSourceSha256(),binding.getOriginalSourceSha256())
                    &&Objects.equals(captured.getJavaParsedSourceHash(),binding.getJavaParsedSourceHash())&&Objects.equals(captured.getCanonicalSourceHash(),binding.getCanonicalSourceHash()),"selected quotation source revision differs");
            originals.put(key,CanonicalEvidenceService.compactNormalPointer(binding,headers.get(captured.getDocumentId()),raw,session.module));
        }
        evidence.revalidateCompactSelection(session.semanticScope,documentIds);
        for(SourceDocument source:selected)currentSources.requireOriginalBytes(source);
        evidence.assertCompactCurrent(text.scope());current(session);return originals;
    }

    private Map<String,JsonNode> selectedSemanticMembers(Session session,Set<String> ids){
        if(!session.semanticSources.textFirst()||ids.isEmpty())return Collections.emptyMap();
        List<String> ordered=new ArrayList<>(ids);Map<String,JsonNode> full=new LinkedHashMap<>();
        for(int start=0;start<ordered.size();start+=64){List<String> batch=ordered.subList(start,Math.min(ordered.size(),start+64));
            ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","ids");demand.put("maxItems",batch.size());demand.put("maxBytes",16777216);demand.set("originIds",JsonUtils.mapper().valueToTree(batch));
            JsonNode result=retrieval.readSemanticSources(session.semanticSources,demand);
            for(int i=0;i<batch.size();i++){String id=batch.get(i);JsonNode member=result.path("origins").get(i);Chunk scanned=session.byId.get(id);
                require(member!=null&&scanned!=null&&id.equals(member.path("id").asText()),"selected semantic member missing");
                ObjectNode thin=(ObjectNode)scanned.getCanonicalPayload().deepCopy();thin.remove("semanticUnit");thin.remove("semanticUnitRef");
                thin.fields().forEachRemaining(field->require(field.getValue().equals(member.path(field.getKey())),"selected semantic member differs from scanned text"));
                full.put(id,member.deepCopy());
            }
        }
        return full;
    }


    private static final class CurrentStage implements AutoCloseable {
        final CanonicalEvidenceService.VerifiedBindingFrame frame;final boolean borrowed;List<CanonicalSourceBinding> bindings;Session session;
        CurrentStage(CanonicalEvidenceService.VerifiedBindingFrame frame,List<CanonicalSourceBinding> bindings){this(frame,bindings,false);}
        CurrentStage(CanonicalEvidenceService.VerifiedBindingFrame frame,List<CanonicalSourceBinding> bindings,boolean borrowed){this.frame=frame;this.bindings=bindings;this.borrowed=borrowed;}
        @Override public void close(){if(frame!=null)try{if(borrowed)frame.beforeWait();else frame.close();}catch(RuntimeException changed){if(session!=null)session.invalidated=true;throw changed;}}
    }
    private CurrentStage stage(String project,String module,List<Long> ids){
        return stage(project,module,ids,null);
    }
    private CurrentStage stage(String project,String module,List<Long> ids,List<SourceDocument> captured){
        CanonicalEvidenceService.VerifiedBindingFrame frame=captured==null?evidence.verifiedBindings(project,module,ids)
                :evidence.verifiedSemanticBindings(project,module,captured);
        // Direct legacy test adapters expose untrusted lists, never a reusable
        // source verdict. That path retains all original fresh reads.
        if(frame==null)return new CurrentStage(null,evidence.currentBindings(project,module,ids));
        try{evidence.assertFrameOwner(frame,project,module);return new CurrentStage(frame,frame.bindings());}
        catch(RuntimeException failure){frame.close();throw failure;}
    }
    private CurrentStage stage(Session session){
        require(!session.invalidated,"canonical session invalidated; reload current sources");
        CurrentStage stage=null;
        try{
            if(session.readStage!=null&&session.readStage.frame!=null){
                require(session.readOwner==this,"initial read session owner differs");
                evidence.assertFrameOwner(session.readStage.frame,session.projectId,session.module);
                stage=new CurrentStage(session.readStage.frame,session.readStage.bindings,true);
            }else if(session.queryScope!=null){
                Set<String> loaded=session.byId.values().stream().map(Chunk::getDocumentId).collect(Collectors.toSet());
                List<Long> ids=session.documentIds.stream().filter(id->loaded.contains(String.valueOf(id))).collect(Collectors.toList());
                stage=ids.isEmpty()?new CurrentStage(null,Collections.emptyList()):stage(session.projectId,session.module,ids);
                if(stage.frame!=null)stage.frame.deferWaitCasUntilClose();
            }else stage=stage(session.projectId,session.module,session.documentIds,session.semanticScope==null?null:new ArrayList<>(session.semanticDocuments.values()));
            stage.session=session;checkViews(session,stage.bindings);return stage;}
        catch(RuntimeException changed){session.invalidated=true;if(stage!=null)stage.close();throw changed;}
    }
    private void checkViews(Session session,List<CanonicalSourceBinding> bindings){
        if(session.queryScope==null){
            require(session.bindingFingerprint.equals(fingerprint(bindings)),"current bindings changed since corpus load");
            require(session.sourceRenderPins.equals(sourceRenderPins(bindings)),"source render identities changed since corpus load");
        }else{
            currentQueryScope(session);Set<Long> ids=bindings.stream().map(CanonicalSourceBinding::getDocumentId).collect(Collectors.toSet());
            List<CanonicalSourceBinding> expected=session.queryScope.stream().filter(binding->ids.contains(binding.getDocumentId())).collect(Collectors.toList());
            require(expected.size()==bindings.size()&&fingerprint(expected).equals(fingerprint(bindings)),"hydrated source bindings changed");
        }
        Map<String,String> semanticPayloads=session.semanticScope==null?null:semanticViewPayloads(session.chunks);
        if(session.initialSourceGeneration!=null&&session.readStage!=null)return;
        for(Chunk view:session.chunks)require(Objects.equals(session.viewPayloads.get(view.getId()),semanticPayloads==null?JsonUtils.serializedSha256(view):semanticPayloads.get(view.getId()))
                &&canonicalViewStatus(view)==null,"selected canonical view changed");
    }

    public Session load(String projectId,String module,List<SourceDocument> selected) {
        try(Session session=openReadSession(projectId,module,selected)){return session;}
    }
    /** One initial Ask borrows this checked epoch until prompt assembly, then closes before paid work. */
    public Session openReadSession(String projectId,String module,List<SourceDocument> selected) {
        Map<Long,SourceDocument> documents=selectedDocuments(projectId,selected);
        CurrentStage loaded=stage(projectId,module,new ArrayList<>(documents.keySet()));
        try{
            Session session=loadSession(projectId,module,documents,loaded);loaded.session=session;
            session.readOwner=this;session.readStage=loaded;return session;
        }catch(RuntimeException|Error failure){
            try{loaded.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}throw failure;
        }
    }
    /** Whole selected documents remain available for a requested continuation; unrelated document graphs are released. */
    public void retainAdviceSources(Session session,List<VectorStore.SearchHit> selected){
        if(!session.hasClosedSourceProof()||session.queryScope==null)return;
        Set<String> documents=new HashSet<>();for(VectorStore.SearchHit hit:selected)if(!fixedBaselineHit(hit))for(EvidencePointer pointer:canonicalPointers(hit))documents.add(String.valueOf(pointer.getDocumentId()));
        session.byId.entrySet().removeIf(entry->!documents.contains(entry.getValue().getDocumentId()));
        session.viewPayloads.keySet().retainAll(session.byId.keySet());session.chunks.owned.removeIf(view->!session.byId.containsKey(view.getId()));
    }
    /** A complete checked read/build receipt permits initial rank-before-hydration, including after process restart. */
    public Session openQuerySession(String projectId,Supplier<List<SourceDocument>> currentSelected){
        List<SourceDocument> selected=currentSelected.get();Map<Long,SourceDocument> documents=selectedDocuments(projectId,selected);
        Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originals=observeOriginals(selected);
        List<CanonicalSourceBinding> bindings;String generation;
        try{bindings=evidence.currentDisplayBindings(projectId,"advisory",selected,originals);generation=sourceGeneration(selected);}
        catch(com.consense.service.sources.CanonicalEvidenceException legacy){
            if(Arrays.asList("SOURCE_CONTENT_REVISION_UNAVAILABLE","CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE").contains(legacy.getMessage()))return sequentialAdvisoryRead(projectId,currentSelected,true,false).session;
            throw legacy;
        }
        Session session=new Session(projectId,"advisory",new ArrayList<>(documents.keySet()),bindings,new LinkedHashMap<>(),sourceRenderPins(bindings));
        session.originalFileStates=originals;
        if(!retrieval.sourceGenerationLinked(session.module,session.indexKey,generation)){
            AdvisoryRead recovered=recoverAdvisoryQuerySession(session,bindings,generation,currentSelected);
            return recovered==null?sequentialAdvisoryRead(projectId,currentSelected,true,false).session:recovered.session;
        }
        session.queryScope=bindings;session.querySelection=currentSelected;session.querySourceGeneration=generation;
        requireAdviceOriginalBytes(session,selected);
        currentQueryScope(session);OerCanonicalRetrievalClient.IndexState available=retrieval.indexAvailable(session.indexKey,"advisory",bindings);
        require(available.ready,"Current index is unavailable. Refresh the selected source index before asking again.");
        session.queryChunkCount=available.chunkCount;currentQueryScope(session);return session;
    }
    /** A ready native index proves its complete catalog without hydrating every Java source tree. */
    private AdvisoryRead recoverAdvisoryQuerySession(Session session,List<CanonicalSourceBinding> bindings,String generation,Supplier<List<SourceDocument>> currentSelected){
        session.initialSourceGeneration=generation;
        Runnable guard=()->{List<SourceDocument> current=currentSelected.get();assertSelectedScope(session,current);
            List<CanonicalSourceBinding> live=evidence.currentDisplayBindings(session.projectId,"advisory",current,session.originalFileStates);
            require(session.bindingFingerprint.equals(fingerprint(live))&&session.sourceRenderPins.equals(sourceRenderPins(live)),"cold Advisory source identity changed");};
        guard.run();requireAdviceOriginalBytes(session,currentSelected.get());guard.run();
        ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","catalog");demand.put("maxBytes",16777216);
        JsonNode catalog;
        try{catalog=retrieval.readSources("advisory",demand,bindings);}
        catch(IllegalStateException unsupported){
            if(unsupported.getMessage()==null||!unsupported.getMessage().contains("source-read capability unavailable")
                    &&!unsupported.getMessage().contains("source catalog policy unknown"))throw unsupported;
            guard.run();return null;
        }
        guard.run();int count=catalog.path("coverage").path("totalOrigins").asInt();if(count<=0)return null;
        OerCanonicalRetrievalClient.IndexState state=retrieval.advisoryRecoveryAvailability(session.indexKey,bindings,count,generation,guard);
        if("canonical_index_bootstrap_unsupported".equals(state.reason))state=retrieval.advisoryCompactIndex(session.indexKey,bindings,count,Collections.emptySet(),generation,guard,guard,false,false);
        if(!state.ready)return null;
        session.queryScope=bindings;session.querySelection=currentSelected;session.querySourceGeneration=generation;session.queryChunkCount=count;
        session.initialAskExit=()->{guard.run();JsonNode closed=retrieval.readSources("advisory",demand,bindings);
            require(catalog.path("sources").equals(closed.path("sources"))&&count==closed.path("coverage").path("totalOrigins").asInt(),"cold Advisory source catalog changed");guard.run();};
        currentQueryScope(session);return new AdvisoryRead(session,state);
    }
    private static final class AdvisoryRead {
        final Session session;final OerCanonicalRetrievalClient.IndexState state;
        AdvisoryRead(Session session,OerCanonicalRetrievalClient.IndexState state){this.session=session;this.state=state;}
    }
    /** A cold process inspects one source at a time and retains only the complete scope identity/count. */
    private AdvisoryRead sequentialAdvisoryRead(String projectId,Supplier<List<SourceDocument>> currentSelected,boolean buildMissing,boolean rebuild){
        List<SourceDocument> selected=currentSelected.get();selectedDocuments(projectId,selected);List<CanonicalSourceBinding> bindings;
        Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originals=observeOriginals(selected);
        try{bindings=evidence.currentDisplayBindings(projectId,"advisory",selected,originals);}
        catch(com.consense.service.sources.CanonicalEvidenceException legacy){
            if(!Arrays.asList("SOURCE_CONTENT_REVISION_UNAVAILABLE","CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE").contains(legacy.getMessage()))throw legacy;
            require(currentSources!=null,"current source reader required for cold Advisory fingerprints");
            for(SourceDocument document:selected){SourceDocument source=currentSources.currentDocument(projectId,document.getId(),"advisory");
                try(CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(projectId,"advisory",Collections.singletonList(source.getId()))){
                    require(frame!=null,"cold Advisory preparation requires an owned frame");evidence.prepareCompactIdentities(frame,Collections.singletonList(source));}
            }
            selected=currentSelected.get();originals=observeOriginals(selected);bindings=evidence.currentDisplayBindings(projectId,"advisory",selected,originals);
        }
        Session session=new Session(projectId,"advisory",selected.stream().map(SourceDocument::getId).collect(Collectors.toList()),bindings,new LinkedHashMap<>(),sourceRenderPins(bindings));
        session.originalFileStates=originals;
        session.initialSourceGeneration=sourceGeneration(selected);
        final List<CanonicalSourceBinding> captured=bindings;final String generation=session.initialSourceGeneration;
        Runnable guard=()->{List<SourceDocument> current=currentSelected.get();assertSelectedScope(session,current);
            List<CanonicalSourceBinding> live=evidence.currentDisplayBindings(projectId,"advisory",current,session.originalFileStates);
            require(session.bindingFingerprint.equals(fingerprint(live))&&session.sourceRenderPins.equals(sourceRenderPins(live)),"cold Advisory source identity changed");};
        guard.run();AdvisorySourceProof proof=verifySequentialAdvisory(session,captured);guard.run();
        Runnable fullExit=()->{AdvisorySourceProof after=verifySequentialAdvisory(session,captured);require(proof.chunks==after.chunks&&proof.kinds.equals(after.kinds),"cold Advisory source denominator or producer changed");guard.run();};
        boolean initialAsk=buildMissing&&!rebuild;Runnable operationExit=initialAsk?guard:fullExit;
        OerCanonicalRetrievalClient.IndexState state=retrieval.advisoryCompactIndex(session.indexKey,captured,proof.chunks,proof.kinds,generation,guard,operationExit,rebuild,rebuild);
        if(!state.ready&&buildMissing&&!rebuild)state=retrieval.advisoryCompactIndex(session.indexKey,captured,proof.chunks,proof.kinds,generation,guard,fullExit,true,false);
        if(state.ready){session.queryScope=captured;session.querySelection=currentSelected;session.querySourceGeneration=generation;session.queryChunkCount=proof.chunks;session.closedSourceProof=true;currentQueryScope(session);}
        if(initialAsk)session.initialAskExit=fullExit;
        return new AdvisoryRead(session,state);
    }
    private static final class AdvisorySourceProof {int chunks;final Set<String> kinds=new HashSet<>();}
    private AdvisorySourceProof verifySequentialAdvisory(Session session,List<CanonicalSourceBinding> bindings){
        AdvisorySourceProof proof=new AdvisorySourceProof();
        for(CanonicalSourceBinding expected:bindings){
            try(CanonicalEvidenceService.VerifiedBindingFrame frame=evidence.verifiedBindings(session.projectId,"advisory",Collections.singletonList(expected.getDocumentId()))){
                require(frame!=null,"sequential Advisory verification requires an owned frame");evidence.assertFrameOwner(frame,session.projectId,"advisory");
                require(fingerprint(Collections.singletonList(expected)).equals(fingerprint(frame.bindings())),"sequential Advisory binding changed");
                JsonNode snapshot=frame.snapshotForRead(expected.getDocumentId(),expected.getProjectionSha256(),"advisory");
                require(snapshot.path("chunks").isArray(),"sequential Advisory chunks missing");proof.chunks=Math.addExact(proof.chunks,snapshot.path("chunks").size());
                JsonNode root=JsonUtils.parse(frame.bindings().get(0).getSnapshotJson());
                if(NormalObservedContract.isNormal(root))proof.kinds.add("normal");
                else if(NativePdfSourceFamilyContract.isFamily(root))proof.kinds.add("family");
                else if(NativePdfCanonicalContract.isNativePdf(root))proof.kinds.add("pdf");
                if(NativeDocumentCollectionContract.isCollection(root)){proof.kinds.add("collection");proof.kinds.add("native");}
                else if(NativeCanonicalContract.isNative(root))proof.kinds.add("native");
            }
        }
        return proof;
    }
    public CurrentIndexStatus rebuildAdvisoryIndex(String projectId,Supplier<List<SourceDocument>> currentSelected){
        AdvisoryRead read=sequentialAdvisoryRead(projectId,currentSelected,true,true);
        return new CurrentIndexStatus(read.session.indexKey,read.state.chunkCount,read.session.documentIds.size(),read.state);
    }
    private static String sourceGeneration(List<SourceDocument> sources){
        List<List<Object>> generations=new ArrayList<>();for(SourceDocument source:sources){
            require(SourceContentDigests.stamped(source),"verified source content generation unavailable");
            generations.add(Arrays.asList(source.getId(),source.getBusinessContentSha256()));
        }return VettingCorpus.hash(JsonUtils.write(generations));
    }
    private Map<Long,CanonicalCurrentSourceReader.OriginalFileState> observeOriginals(List<SourceDocument> selected){
        require(currentSources!=null,"current source reader required for Advisory original identity");
        Map<Long,CanonicalCurrentSourceReader.OriginalFileState> states=new LinkedHashMap<>();
        for(SourceDocument source:selected)states.put(source.getId(),currentSources.observeOriginal(source));
        return Collections.unmodifiableMap(states);
    }
    private void requireAdviceOriginalBytes(Session session,List<SourceDocument> selected){
        evidence.currentDisplayBindings(session.projectId,session.module,selected,session.originalFileStates);
        for(SourceDocument source:selected){currentSources.requireOriginalFreshness(source,session.originalFileStates.get(source.getId()));
            currentSources.requireOriginalBytes(source);currentSources.requireOriginalFreshness(source,session.originalFileStates.get(source.getId()));}
        evidence.currentDisplayBindings(session.projectId,session.module,selected,session.originalFileStates);
    }
    /** A native byte proof occurs once after each paid preflight, never once per answer section. */
    public void assertAdviceDispatchCurrent(Session session){if(session.adviceTextByteGuard!=null)session.adviceTextByteGuard.run();}
    /** A caller may whitelist an alias only from a locally retained, exact selected-text view. */
    public boolean admitsAdviceText(Session session,EvidencePointer pointer,String content,JsonNode unit){
        if(session==null||session.invalidated||session.adviceTextByteGuard==null||pointer==null||unit==null)return false;
        Chunk view=session.byId.get(pointer.getCanonicalChunkId());return view!=null&&Objects.equals(content,view.getContent())
                &&unit.path("originIds").isArray()&&unit.path("originIds").size()==1&&Objects.equals(pointer.getCanonicalChunkId(),unit.path("originIds").get(0).asText())
                &&"unknown".equals(unit.path("status").asText())&&"additional_source_context_deferred".equals(unit.path("reason").asText())
                &&Objects.equals(session.viewPayloads.get(view.getId()),JsonUtils.serializedSha256(view))
                &&JsonUtils.mapper().valueToTree(pointer).equals(JsonUtils.mapper().valueToTree(view.getCanonicalEvidence()));
    }
    public void assertAdviceOriginalsCurrent(Session session){
        if(session.queryScope==null){current(session);return;}
        if(session.adviceTextByteGuard!=null){session.adviceTextByteGuard.run();return;}
        List<SourceDocument> selected=session.querySelection.get();assertSelectedScope(session,selected);requireAdviceOriginalBytes(session,selected);currentQueryScope(session);
    }
    private String sourceGeneration(Session session){
        List<SourceDocument> current=new ArrayList<>();for(Long id:session.documentIds)current.add(currentSources.currentStamp(session.projectId,id,session.module));
        return sourceGeneration(current);
    }
    private List<CanonicalSourceBinding> currentQueryScope(Session session){
        require(!session.invalidated,"canonical session invalidated; reload current sources");
        try{List<SourceDocument> selected=session.querySelection.get();assertSelectedScope(session,selected);
            List<CanonicalSourceBinding> current=evidence.currentDisplayBindings(session.projectId,session.module,selected,session.originalFileStates);
            require(session.bindingFingerprint.equals(fingerprint(current))&&session.sourceRenderPins.equals(sourceRenderPins(current)),"selected canonical source identity changed");
            require(session.querySourceGeneration.equals(sourceGeneration(selected))&&retrieval.sourceGenerationLinked(session.module,session.indexKey,session.querySourceGeneration),"source content generation changed since index build");
            return current;
        }catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    private CanonicalEvidenceService.VerifiedBindingFrame hydrateQueryDocuments(Session session,List<Long> ids){
        currentQueryScope(session);Set<Long> requested=new HashSet<>(ids);
        for(Chunk view:session.byId.values())requested.add(Long.valueOf(view.getDocumentId()));
        ids=session.documentIds.stream().filter(requested::contains).collect(Collectors.toList());
        if(session.readStage!=null)session.close();
        Map<Long,SourceDocument> selected=selectedDocuments(session.projectId,session.querySelection.get()),documents=new LinkedHashMap<>();
        for(Long id:ids){require(selected.containsKey(id),"ranked source outside selected scope");documents.put(id,selected.get(id));}
        CurrentStage loaded=stage(session.projectId,session.module,ids);
        try{require(loaded.frame!=null,"checked ranked source frame required");loaded.frame.deferWaitCasUntilClose();Session views=loadSession(session.projectId,session.module,documents,loaded);
            session.byId.clear();session.byId.putAll(views.byId);session.viewPayloads.clear();session.viewPayloads.putAll(views.viewPayloads);
            session.chunks.owned.clear();session.chunks.owned.addAll(views.chunks);
            loaded.session=session;session.readOwner=this;session.readStage=loaded;currentQueryScope(session);return loaded.frame;
        }catch(RuntimeException|Error rejected){try{loaded.close();}catch(RuntimeException|Error closing){rejected.addSuppressed(closing);}throw rejected;}
    }
    private Map<Long,SourceDocument> selectedDocuments(String projectId,List<SourceDocument> selected){
        if(!enabled())throw new IllegalStateException("OER canonical business backend is not configured");
        require(selected!=null&&!selected.isEmpty(),"no permitted current documents");
        Map<Long,SourceDocument> documents=new LinkedHashMap<>();for(SourceDocument d:selected)
            require(d!=null&&d.getId()!=null&&Objects.equals(projectId,d.getProjectId())&&documents.putIfAbsent(d.getId(),d)==null,"selected document identity differs");
        return documents;
    }
    private Session loadSession(String projectId,String module,Map<Long,SourceDocument> documents,CurrentStage stage){
        return loadSession(projectId,module,documents,stage,stage.bindings);
    }
    private Session loadSession(String projectId,String module,Map<Long,SourceDocument> documents,CurrentStage stage,List<CanonicalSourceBinding> bindings){
        List<Long> ids=new ArrayList<>(documents.keySet());
        Map<String,String> pins=sourceRenderPins(bindings);
        require(pins.isEmpty()||sourcePages!=null,"source page validator required for reviewed render identities");
        require(ids.equals(bindings.stream().map(CanonicalSourceBinding::getDocumentId).collect(Collectors.toList())),"selected binding order differs");
        Map<String,Chunk> views=new LinkedHashMap<>();for(CanonicalSourceBinding binding:bindings) {
            JsonNode snapshot=stage.frame==null?JsonUtils.parse(binding.getSnapshotJson()):stage.frame.snapshotForRead(binding.getDocumentId(),binding.getProjectionSha256(),module);SourceDocument source=documents.get(binding.getDocumentId());
            boolean normal=NormalObservedContract.isNormal(snapshot),family=!normal&&NativePdfSourceFamilyContract.isFamily(snapshot),nativePdf=!normal&&!family&&NativePdfCanonicalContract.isNativePdf(snapshot);
            JsonNode display=family?NativePdfSourceFamilyContract.displayDeclarations(snapshot):nativePdf?NativePdfCanonicalContract.displayDeclarations(snapshot):null;
            // A large native document has one root binding. Complete batches
            // are verified by the source service; the root is not a chunk.
            JsonNode payloads="consense.native-document.v1".equals(snapshot.path("schema").asText())
                    ?(stage.frame==null?evidence.chunks(binding,module):snapshot.path("chunks")):snapshot.path("chunks");
            Map<String,EvidencePointer> pointers=new LinkedHashMap<>();
            for(EvidencePointer verified:stage.frame==null?evidence.pointers(projectId,binding.getDocumentId(),binding.getProjectionSha256(),module)
                    :stage.frame.pointers(binding.getDocumentId(),binding.getProjectionSha256(),module)){
                EvidencePointer pointer=sourcePages==null?verified:sourcePages.enrich(verified);
                require(Objects.equals(pins.get(binding.getProjectionSha256()),pointer.getSourceRenderEvidenceIdentitySha256()),"source render pointer differs from reviewed identity");
                require(pointers.putIfAbsent(pointer.getCanonicalChunkId(),pointer)==null,"duplicate canonical pointer");
            }
            require(payloads!=null&&payloads.isArray()&&pointers.size()==payloads.size(),"canonical pointer count differs");
            for(JsonNode payload:payloads) {
                EvidencePointer pointer=pointers.get(payload.path("id").asText());require(pointer!=null,"canonical pointer missing");
                if(family)require(display.equals(pointer.getNativePdfSourceFamilyEvidence()),"native PDF family display declarations differ");
                else if(nativePdf)require(display.equals(pointer.getNativePdfEvidence()),"native PDF display declarations differ");
                Chunk view=view(payload,pointer,source);require(views.putIfAbsent(view.getId(),view)==null,"duplicate canonical chunk");
                require(canonicalViewStatus(view)==null,"canonical pointer and selected snapshot differ");
            }
        }
        return new Session(projectId,module,ids,bindings,views,pins);
    }
    /** Scalar result only: the verified frame and its source verdict never leave this request. */
    public static final class CurrentIndexStatus {
        public final String indexKey;public final int chunks,documentCount;
        public final OerCanonicalRetrievalClient.IndexState state;
        private CurrentIndexStatus(Session session,OerCanonicalRetrievalClient.IndexState state){
            this(session.indexKey,session.chunkCount(),session.documentIds.size(),state);
        }
        private CurrentIndexStatus(String key,int count,int documents,OerCanonicalRetrievalClient.IndexState state){indexKey=key;chunks=count;documentCount=documents;this.state=state;}
    }
    /** Routine polling checks live scalar scope; it never constructs a full source Session. */
    public CurrentIndexStatus currentIndexAvailability(String projectId,String module,Supplier<List<SourceDocument>> currentSelected){
        Map<Long,SourceDocument> selected=selectedDocuments(projectId,currentSelected.get());
        Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originals=observeOriginals(new ArrayList<>(selected.values()));
        Map<Long,String> contentRevisions=sourceContentRevisions(selected.values());
        List<CanonicalSourceBinding> before;
        try{before=evidence.currentDisplayBindings(projectId,module,new ArrayList<>(selected.values()),originals);}
        catch(com.consense.service.sources.CanonicalEvidenceException legacy){
            if("advisory".equals(module)&&Arrays.asList("SOURCE_CONTENT_REVISION_UNAVAILABLE","CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE").contains(legacy.getMessage()))return currentIndexStatus(projectId,module,currentSelected);
            throw legacy;
        }
        Map<String,String> pins=sourceRenderPins(before);String identity=renderFingerprint(fingerprint(before),pins);
        Object projections=before.stream().map(CanonicalSourceBinding::getProjectionSha256).collect(Collectors.toList());
        String key=projectId+"::canonical::"+module+"::"+VettingCorpus.hash(JsonUtils.write(pins.isEmpty()?projections:Arrays.asList(projections,pins)));
        if(!retrieval.sourceGenerationLinked(module,key,sourceGeneration(new ArrayList<>(selected.values()))))return currentIndexStatus(projectId,module,currentSelected);
        for(SourceDocument source:selected.values()){currentSources.requireOriginalBytes(source);currentSources.requireOriginalFreshness(source,originals.get(source.getId()));}
        OerCanonicalRetrievalClient.IndexState state=retrieval.indexAvailable(key,module,before);
        Map<Long,SourceDocument> after=selectedDocuments(projectId,currentSelected.get());
        require(new ArrayList<>(selected.keySet()).equals(new ArrayList<>(after.keySet())),"current selected scope changed during availability check");
        require(contentRevisions.equals(sourceContentRevisions(after.values())),"current source content changed during availability check");
        List<CanonicalSourceBinding> current=evidence.currentDisplayBindings(projectId,module,new ArrayList<>(after.values()),originals);
        require(identity.equals(renderFingerprint(fingerprint(current),sourceRenderPins(current))),"current source identity changed during availability check");
        retrieval.sourceGenerationLinked(module,key,sourceGeneration(new ArrayList<>(after.values())));
        if(state.ready)for(SourceDocument source:after.values()){
            currentSources.requireOriginalBytes(source);currentSources.requireOriginalFreshness(source,originals.get(source.getId()));
        }
        return new CurrentIndexStatus(key,state.chunkCount,selected.size(),state);
    }
    /** A status read owns one fresh frame; the selected catalog is fetched before health and after close. */
    public CurrentIndexStatus currentIndexStatus(String projectId,String module,Supplier<List<SourceDocument>> currentSelected){
        require(currentSelected!=null,"current selected scope reader required");
        if("advisory".equals(module)){
            List<SourceDocument> selected=currentSelected.get();Map<Long,SourceDocument> documents=selectedDocuments(projectId,selected);
            Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originals=observeOriginals(selected);List<CanonicalSourceBinding> bindings;
            try{bindings=evidence.currentDisplayBindings(projectId,module,selected,originals);}
            catch(com.consense.service.sources.CanonicalEvidenceException legacy){
                if(!Arrays.asList("SOURCE_CONTENT_REVISION_UNAVAILABLE","CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE").contains(legacy.getMessage()))throw legacy;
                AdvisoryRead fallback=sequentialAdvisoryRead(projectId,currentSelected,false,false);
                return new CurrentIndexStatus(fallback.session.indexKey,fallback.state.chunkCount,fallback.session.documentIds.size(),fallback.state);
            }
            Session candidate=new Session(projectId,module,new ArrayList<>(documents.keySet()),bindings,new LinkedHashMap<>(),sourceRenderPins(bindings));
            candidate.originalFileStates=originals;
            AdvisoryRead read=recoverAdvisoryQuerySession(candidate,bindings,sourceGeneration(selected),currentSelected);
            if(read==null)read=sequentialAdvisoryRead(projectId,currentSelected,false,false);
            else{read.session.completeInitialAsk();currentQueryScope(read.session);}
            return new CurrentIndexStatus(read.session.indexKey,read.state.chunkCount,read.session.documentIds.size(),read.state);
        }
        Map<Long,SourceDocument> documents=selectedDocuments(projectId,currentSelected.get());
        CurrentStage loaded=stage(projectId,module,new ArrayList<>(documents.keySet()));Session session;
        OerCanonicalRetrievalClient.IndexState state;
        try(CurrentStage held=loaded){
            session=loadSession(projectId,module,documents,held);held.session=session;
            assertSelectedScope(session,currentSelected.get());
            state=held.frame==null?retrieval.indexStatus(session.indexKey,module,held.bindings)
                    :retrieval.indexStatus(session.indexKey,module,held.frame);
        }
        complete(session,loaded);
        // A new eligible member can appear during the exit CAS wait as well as health.
        assertSelectedScope(session,currentSelected.get());
        return new CurrentIndexStatus(session,state);
    }
    private void complete(Session session,CurrentStage stage){
        if(stage.frame==null){current(session);return;}
        try{checkViews(session,stage.bindings);}catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    public void index(Session session){
        index(session,false);
    }
    public void rebuildIndex(Session session){
        index(session,true);
    }
    private void index(Session session,boolean rebuild){
        require(session.queryScope==null,"Index builds require the complete eager source session");
        CurrentStage loaded=stage(session);Runnable attest=null;
        try(CurrentStage stage=loaded){
            boolean linked=false;
            if("advisory".equals(session.module)&&stage.frame!=null&&currentSources!=null)try{
                sourceGeneration(session);linked=true;
            }catch(com.consense.service.sources.CanonicalEvidenceException legacy){
                if(!"SOURCE_CONTENT_REVISION_UNAVAILABLE".equals(legacy.getMessage()))throw legacy;
            }
            if(linked)attest=retrieval.indexAdvisory(session.indexKey,stage.frame,rebuild,()->sourceGeneration(session));
            else if(rebuild){
                if(stage.frame==null)retrieval.rebuild(session.indexKey,session.module,stage.bindings);
                else retrieval.rebuild(session.indexKey,session.module,stage.frame);
            }else if(stage.frame==null)retrieval.index(session.indexKey,session.module,stage.bindings);
            else retrieval.index(session.indexKey,session.module,stage.frame);
        }
        complete(session,loaded);if(attest!=null)attest.run();
    }
    public OerCanonicalRetrievalClient.IndexState indexStatus(Session session){
        OerCanonicalRetrievalClient.IndexState state;
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){state=stage.frame==null?retrieval.indexStatus(session.indexKey,session.module,stage.bindings)
                :retrieval.indexStatus(session.indexKey,session.module,stage.frame);}
        complete(session,loaded);return state;
    }
    /** One fresh selected-scope stage directly precedes the external health wait. */
    public OerCanonicalRetrievalClient.IndexState indexStatusSelected(Session session,List<SourceDocument> selected){
        try{assertSelectedScope(session,selected);
            return indexStatus(session);
        }catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    /** Pure selected-scope identity check; callers fetch the current catalog anew at a remote boundary. */
    public void assertSelectedScope(Session session,List<SourceDocument> selected){
        try{require(!session.invalidated,"canonical session invalidated; reload current sources");
            require(selected!=null&&selected.stream().allMatch(d->d!=null&&session.projectId.equals(d.getProjectId())),"selected scope identity differs");
            require(session.documentIds.equals(selected.stream().map(SourceDocument::getId).collect(Collectors.toList())),"current selected scope changed");
            if(session.initialSourceGeneration!=null)require(session.initialSourceGeneration.equals(sourceGeneration(selected)),"source content generation changed during initial Ask");
            if(session.queryScope!=null)require(session.querySourceGeneration.equals(sourceGeneration(selected))
                    &&retrieval.sourceGenerationLinked(session.module,session.indexKey,session.querySourceGeneration),"source content generation changed since index build");
        }catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    public void ensureIndex(Session session){if(!indexStatus(session).ready)index(session);}
    /** Questions use live availability; explicit index/status actions keep complete vector verification. */
    public void ensureAvailable(Session session,Supplier<List<SourceDocument>> currentSelected){
        if(session.queryScope!=null){currentQueryScope(session);return;}
        List<CanonicalSourceBinding> compact;
        List<SourceDocument> selected=currentSelected.get();assertSelectedScope(session,selected);
        Map<Long,String> contentRevisions=sourceContentRevisions(selected);
        try{compact=evidence.currentDisplayBindings(session.projectId,session.module,selected);}
        catch(com.consense.service.sources.CanonicalEvidenceException legacy){
            if(Arrays.asList("SOURCE_CONTENT_REVISION_UNAVAILABLE","CANONICAL_STORED_BYTES_CHANGED_OR_REVISION_UNAVAILABLE").contains(legacy.getMessage())){ensureIndex(session);return;}
            throw legacy;
        }
        require(session.bindingFingerprint.equals(fingerprint(compact)),"selected canonical source identity changed");
        OerCanonicalRetrievalClient.IndexState state=retrieval.indexAvailable(session.indexKey,session.module,compact,session.chunkCount());
        selected=currentSelected.get();assertSelectedScope(session,selected);
        require(contentRevisions.equals(sourceContentRevisions(selected)),"current source content changed during availability check");
        require(session.bindingFingerprint.equals(fingerprint(evidence.currentDisplayBindings(session.projectId,session.module,selected))),"selected canonical source identity changed during availability check");
        if(Arrays.asList("canonical_index_receipt_missing","canonical_index_availability_unsupported").contains(state.reason)){ensureIndex(session);return;}
        require(state.ready,"Current index is unavailable. Refresh the selected source index before asking again.");
    }
    private static Map<Long,String> sourceContentRevisions(Collection<SourceDocument> selected){
        Map<Long,String> revisions=new LinkedHashMap<>();for(SourceDocument source:selected)revisions.put(source.getId(),source.getBusinessContentSha256());return revisions;
    }
    public void assertCurrent(Session session){current(session);}
    public void assertSelectedViews(Session session,List<Chunk> selected){
        current(session);if(session.semanticScope!=null){
            List<Chunk> views=selected.stream().filter(c->!FixedVettingReferences.isFixed(c)).collect(Collectors.toList());checkSemanticViews(session,views);
            evidence.revalidateCompactSelection(session.semanticScope,views.stream().map(c->c.getCanonicalEvidence().getDocumentId()).collect(Collectors.toCollection(LinkedHashSet::new)));
            checkSemanticViews(session,views);
        }
    }
    /** Publication checks the complete in-memory corpus once; intermediate reads check their selected views. */
    public void assertCorpusIntegrity(Session session){current(session);if(session.semanticScope!=null){
        ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","catalog");retrieval.readSemanticSources(session.semanticSources,demand);
        current(session);checkSemanticViews(session,session.chunks);
    }}
    private static void checkSemanticViews(Session session,List<Chunk> views){
        Map<String,String> payloads=semanticViewPayloads(views);
        for(Chunk view:views)require(Objects.equals(session.viewPayloads.get(view.getId()),payloads.get(view.getId()))&&canonicalViewStatus(view)==null,"selected semantic view changed");
    }
    public String currentIdentity(Session session){current(session);return session.identitySha256();}
    public String currentIdentity(String projectId,String module,List<SourceDocument> selected){
        List<CanonicalSourceBinding> bindings=evidence.currentBindings(projectId,module,selected.stream().map(SourceDocument::getId).collect(Collectors.toList()));
        return renderFingerprint(fingerprint(bindings),sourceRenderPins(bindings));
    }
    public String currentDisplayIdentity(String projectId,String module,List<SourceDocument> selected){
        List<CanonicalSourceBinding> bindings=evidence.currentDisplayBindings(projectId,module,selected);
        return renderFingerprint(fingerprint(bindings),sourceRenderPins(bindings));
    }
    public String currentDisplayIdentity(Session session,List<SourceDocument> selected){
        List<CanonicalSourceBinding> bindings=evidence.currentDisplayBindings(session.projectId,session.module,selected,session.originalFileStates);
        return renderFingerprint(fingerprint(bindings),sourceRenderPins(bindings));
    }
    /** Callers obtain the complete selected scope anew; fixed IDs alone cannot detect new package members. */
    public void assertSelected(Session session,List<SourceDocument> selected){
        try {
            require(selected!=null&&selected.stream().allMatch(d->d!=null&&session.projectId.equals(d.getProjectId())),"selected scope identity differs");
            require(session.documentIds.equals(selected.stream().map(SourceDocument::getId).collect(Collectors.toList())),"current selected scope changed");current(session);
        } catch(RuntimeException changed){session.invalidated=true;throw changed;}
    }
    private List<CanonicalSourceBinding> current(Session session){
        require(!session.invalidated,"canonical session invalidated; reload current sources");
        if(session.queryScope!=null){List<CanonicalSourceBinding> bindings=currentQueryScope(session);checkViews(session,bindings);return bindings;}
        if(session.semanticScope!=null)try{
            require(session.sourceRenderPins.equals(sourceRenderPins(session.semanticScope.bindings())),"source render identities changed");
            evidence.assertCompactCurrent(session.semanticScope);
            return session.semanticScope.bindings();
        }catch(RuntimeException invalid){session.invalidated=true;throw invalid;}
        try {
            List<CanonicalSourceBinding> bindings=evidence.currentBindings(session.projectId,session.module,session.documentIds);
            checkViews(session,bindings);
            return bindings;
        } catch(RuntimeException invalid){session.invalidated=true;throw invalid;}
    }
    /** Hash each shared dictionary template once while retaining a full stamp of every origin view. */
    private static Map<String,String> semanticViewPayloads(List<Chunk> views){
        Map<String,String> payloads=new LinkedHashMap<>();IdentityHashMap<JsonNode,String> sharedHashes=new IdentityHashMap<>();
        for(Chunk view:views){JsonNode raw=view.getCanonicalPayload();String digest;
            if(raw!=null&&raw.has("semanticUnitRef")&&raw.path("semanticUnit").isObject()){
                JsonNode template=raw.path("semanticUnit");String shared=sharedHashes.computeIfAbsent(template,JsonUtils::serializedSha256);
                ObjectNode origin=JsonUtils.mapper().createObjectNode();raw.fields().forEachRemaining(field->{if(!"semanticUnit".equals(field.getKey()))origin.set(field.getKey(),field.getValue());});
                Chunk copy=new Chunk();org.springframework.beans.BeanUtils.copyProperties(view,copy);copy.setCanonicalPayload(origin);
                digest=JsonUtils.serializedSha256(Arrays.asList(copy,shared));
            }else digest=JsonUtils.serializedSha256(view);
            payloads.put(view.getId(),digest);
        }
        sharedHashes.forEach((template,digest)->require(Objects.equals(digest,JsonUtils.serializedSha256(template)),"semantic dictionary group changed during validation"));
        return payloads;
    }
    private static String viewPayload(Session session,Chunk view){
        return session.semanticScope==null?JsonUtils.serializedSha256(view):semanticViewPayloads(Collections.singletonList(view)).get(view.getId());
    }
    private static String fingerprint(List<CanonicalSourceBinding> bindings){
        List<List<Object>> identities=new ArrayList<>();for(CanonicalSourceBinding b:bindings)
            identities.add(Arrays.asList(b.getProjectId(),b.getDocumentId(),b.getOriginalSourceSha256(),b.getJavaParsedSourceHash(),
                    b.getRoutingSha256(),b.getSourceRevisionSha256(),b.getProjectionSha256(),b.getProjectionRevisionSha256(),
                    b.getCanonicalSourceHash(),b.getScopePolicySha256(),b.getAdoptionManifestSha256(),b.getReviewReferenceSha256(),b.getFamilyCompositionSha256(),b.getFamilyComponentSetSha256(),
                b.getDeclaredModule(),b.getEligibleModulesJson(),b.getSnapshotBytesSha256(),b.getSnapshotJson()==null?b.getSnapshotBytesSha256():VettingCorpus.hash(b.getSnapshotJson())));
        return VettingCorpus.hash(JsonUtils.write(identities));
    }
    private Map<String,String> sourceRenderPins(List<CanonicalSourceBinding> bindings){
        Map<String,String> pins=new TreeMap<>();for(CanonicalSourceBinding binding:bindings){
            String identity=properties.getSourcePageRenderEvidenceIdentities().get(binding.getProjectionSha256());
            if(identity!=null){require(identity.matches("[a-f0-9]{64}"),"reviewed source render identity invalid");pins.put(binding.getProjectionSha256(),identity);}
        }return pins;
    }
    private static String renderFingerprint(String bindings,Map<String,String> pins){
        return pins.isEmpty()?bindings:VettingCorpus.hash(JsonUtils.write(Arrays.asList(bindings,pins)));
    }
    public List<Chunk> retrieve(Session session,String query,String role){
        JsonNode response=response(session,query,role);List<Chunk> seeds=new ArrayList<>();List<VettingRetrievalSourceUnits.Unit> units=new ArrayList<>();
        for(JsonNode hit:response.path("hits"))seeds.add(copy(session.byId.get(hit.path("id").asText())));
        for(JsonNode raw:response.path("sourceUnits").path("units")) {
            ObjectNode traceNode=raw.deepCopy();traceNode.remove("members");
            if(traceNode.path("hierarchyContext").isObject())((ObjectNode)traceNode.path("hierarchyContext")).remove("members");
            if(traceNode.path("attachmentContext").isObject())((ObjectNode)traceNode.path("attachmentContext")).remove("members");
            Map<String,Object> trace=JsonUtils.readMap(JsonUtils.write(traceNode));
            trace.put("selectionPolicy",response.path("sourceUnits").path("policy").asText());
            JsonNode transport=transportUnit(raw,canonicalParents(session));List<String> ids=new ArrayList<>();transport.path("requiredMemberIds").forEach(id->ids.add(id.asText()));
            units.add(new VettingRetrievalSourceUnits.Unit(raw.path("originIds").get(0).asText(),completeTransport(transport)?"complete_observed_unit":"unknown",
                    transport.path("reason").isNull()?null:transport.path("reason").asText(),ids,trace));
        }
        return new VettingRetrievalSourceUnits.RankedChunks(seeds,units);
    }
    /** Reuse the verified scan denominator and rank before loading only the requested normal text. */
    public List<Chunk> retrieveReviewText(Session session,String query,String role,Runnable sourceGuard){
        return retrieveReviewText(session,query,role,sourceGuard,10000);
    }
    public List<Chunk> retrieveReviewText(Session session,String query,String role,Runnable sourceGuard,int contextChars){
        return retrieveReviewText(session,query,role,sourceGuard,true,contextChars);
    }
    public List<Chunk> retrieveRequiredProjectText(Session session,String clause,Runnable sourceGuard,int contextChars){
        require(session!=null&&session.semanticScope!=null&&session.semanticDocuments!=null,"required project query needs its selected source scope");
        require(clause!=null&&clause.matches("(?i)[A-Z][A-Z0-9_-]*\\.[A-Z0-9]+(?:\\.[A-Z0-9]+)*(?:\\([A-Z0-9]+\\))*"),"required project clause locator invalid");
        String family=clause.substring(0,clause.indexOf('.')).toUpperCase(Locale.ROOT);Set<String> selected=new LinkedHashSet<>();
        for(CanonicalSourceBinding binding:session.semanticScope.bindings()){
            SourceDocument source=session.semanticDocuments.get(binding.getDocumentId());
            if(source!=null&&family.equalsIgnoreCase(source.getFileKey())&&"package_manifest".equals(VettingCorpus.sourceRole(source)))selected.add(binding.getOriginalSourceSha256());
        }
        if(selected.isEmpty())return Collections.emptyList();
        return retrieveReviewText(session,clause,"package_manifest",sourceGuard,true,contextChars,selected);
    }
    public List<Chunk> retrieveRequiredProjectText(Session session,String clause,List<Chunk> nearby,Runnable sourceGuard,int contextChars){
        if(session==null||session.semanticScope==null)return retrieveRequiredProjectText(session,clause,sourceGuard,contextChars);
        require(clause!=null&&clause.matches("(?i)[A-Z][A-Z0-9_-]*\\.[A-Z0-9]+(?:\\.[A-Z0-9]+)*(?:\\([A-Z0-9]+\\))*"),"required project clause locator invalid");
        String family=clause.substring(0,clause.indexOf('.'));List<Chunk> seeds=nearby.stream()
                .filter(c->"tender".equals(c.getRole())&&family.equalsIgnoreCase(c.getFileKey())).collect(Collectors.toList());
        if(seeds.isEmpty())return retrieveRequiredProjectText(session,clause,sourceGuard,contextChars);
        sourceGuard.run();current(session);
        if(session.reviewTextIndex==null)session.reviewTextIndex=new ReviewTextIndex(session.chunks);
        List<Chunk> required=new ArrayList<>(requestedReviewText(session.reviewTextIndex,seeds,clause,contextChars,null).values());
        checkSemanticViews(session,required);sourceGuard.run();
        return new ReviewTextChunks(required.stream().map(OerBusinessEvidence::copy).collect(Collectors.toList()),Collections.emptySet());
    }
    /** A model-free preview verifies only selected ranked text; it does not rescan the full corpus. */
    public List<Chunk> retrieveReviewTextForPreview(Session session,String query,String role,Runnable sourceGuard){
        return retrieveReviewText(session,query,role,sourceGuard,false,0);
    }
    private List<Chunk> retrieveReviewText(Session session,String query,String role,Runnable sourceGuard,boolean requireScan,int contextChars){
        return retrieveReviewText(session,query,role,sourceGuard,requireScan,contextChars,null);
    }
    private List<Chunk> retrieveReviewText(Session session,String query,String role,Runnable sourceGuard,boolean requireScan,int contextChars,Set<String> selectedSources){
        require(session!=null&&session.semanticScope!=null&&(!requireScan||session.scanned)&&"vetting".equals(session.module),"review query requires its selected source scope");
        require(!JsonUtils.isBlankText(query)&&(role==null||Arrays.asList("tender","standard","project_fact","package_manifest").contains(role)),"review query or role unavailable");
        long started=System.nanoTime();ReviewTextStats stats=reviewTextStats(query);
        String phase="source_guard",queryFingerprint=stats==null?"":stats.queryFingerprint;int observedBudget=requireScan?contextChars:-1;
        reviewQuerySummary("started",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);
        try{
        Runnable guard=()->{sourceGuard.run();current(session);};
        guard.run();
        phase="index_readiness";
        reviewQuerySummary("started",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);
        Session queryScope=session.reviewQuery;
        if(queryScope==null){
            List<CanonicalSourceBinding> bindings=new ArrayList<>();for(CanonicalSourceBinding original:session.semanticScope.bindings()){
                CanonicalSourceBinding compact=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(original,compact);compact.setSnapshotJson(null);bindings.add(compact);
            }
            List<SourceDocument> selected=new ArrayList<>(session.semanticDocuments.values());
            require(session.documentIds.equals(bindings.stream().map(CanonicalSourceBinding::getDocumentId).collect(Collectors.toList()))
                    &&session.documentIds.equals(selected.stream().map(SourceDocument::getId).collect(Collectors.toList())),"compact review index scope differs");
            String generation=sourceGeneration(selected);
            queryScope=new Session(session.projectId,"vetting",session.documentIds,bindings,new LinkedHashMap<>(),session.sourceRenderPins);
            queryScope.originalFileStates=observeOriginals(selected);
            OerCanonicalRetrievalClient.IndexState available=retrieval.compactRecoveryAvailability("vetting",queryScope.indexKey,bindings,session.semanticSources.totalOrigins(),generation,guard);
            if(!available.ready){
                Set<String> kinds=new HashSet<>();for(JsonNode source:session.semanticSources.sourceCatalog()){
                    require(NormalObservedContract.PRODUCER.equals(source.path("producerKind").asText()),"compact review index source kind unsupported; refresh the source-bound index");kinds.add("normal");
                }
                Map<Long,CanonicalCurrentSourceReader.OriginalFileState> originals=queryScope.originalFileStates;
                Runnable buildProof=()->{guard.run();for(SourceDocument source:selected){
                    currentSources.requireOriginalFreshness(source,originals.get(source.getId()));currentSources.requireOriginalBytes(source);
                    currentSources.requireOriginalFreshness(source,originals.get(source.getId()));
                }evidence.revalidateCompactRegistry(session.semanticScope);guard.run();};
                available=retrieval.compactIndex("vetting",queryScope.indexKey,bindings,session.semanticSources.totalOrigins(),kinds,generation,guard,buildProof,true,false);
            }
            require(available.ready,"source-bound review index unavailable");
            queryScope.queryScope=bindings;queryScope.querySelection=()->{guard.run();return selected;};queryScope.querySourceGeneration=generation;
            queryScope.queryChunkCount=available.chunkCount;session.reviewQuery=queryScope;
        }
        final Session rankedScope=queryScope;
        // Each call retains only its ranked text; source identities and the native index receipt remain shared.
        rankedScope.byId.clear();rankedScope.viewPayloads.clear();rankedScope.chunks.owned.clear();rankedScope.adviceTextByteGuard=null;rankedScope.initialAskExit=null;
        phase="ranking";
        reviewQuerySummary("started",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);
        java.util.function.Function<List<Long>,CanonicalEvidenceService.VerifiedBindingFrame> noGraphs=ids->{throw new IllegalStateException("Complete review source graphs are not a compact-query fallback");};
        java.util.function.BiFunction<List<Long>,JsonNode,Map<String,JsonNode>> parents=(ids,response)->compactAdviceParents(rankedScope,ids,response);
        Runnable queryGuard=()->{guard.run();currentQueryScope(rankedScope);};
        JsonNode result=selectedSources==null?retrieval.retrieveCompact("vetting",rankedScope.indexKey,query,role,rankedScope.queryScope,rankedScope.querySourceGeneration,
                queryGuard,noGraphs,parents,true):retrieval.retrieveCompact("vetting",rankedScope.indexKey,query,role,rankedScope.queryScope,rankedScope.querySourceGeneration,
                queryGuard,noGraphs,parents,true,selectedSources);
        phase="seed_binding";
        reviewQuerySummary("started",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);
        guard.run();List<Chunk> seeds=new ArrayList<>();List<String> rankedIds=new ArrayList<>();
        for(JsonNode hit:result.path("hits")){
            Chunk proof=rankedScope.byId.get(hit.path("id").asText());
            Chunk selected=session.scanned?session.byId.get(hit.path("id").asText()):proof;
            require(selected!=null&&proof!=null&&(role==null||role.equals(selected.getRole())),"ranked review origin outside the selected source scope");
            require(Objects.equals(proof.getContent(),selected.getContent())&&Objects.equals(proof.getSourceHash(),selected.getSourceHash())
                    &&Objects.equals(proof.getOriginalSourceSha256(),selected.getOriginalSourceSha256()),"ranked review text differs from its selected scan");
            seeds.add(selected);rankedIds.add(selected.getId());
        }
        if(stats!=null)stats.seeds(seeds,!requireScan);
        if(!session.scanned)rankedScope.completeInitialAsk();
        if(requireScan){
            phase="context_selection";
            reviewQuerySummary("started",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);
            if(session.reviewTextIndex==null)session.reviewTextIndex=new ReviewTextIndex(session.chunks);
            seeds=reviewTextContext(session.reviewTextIndex,seeds,result,query,contextChars,stats);
            checkSemanticViews(session,seeds);guard.run();
        }
        phase="selected_copy";
        require(selectedSources==null||seeds.stream().allMatch(chunk->selectedSources.contains(chunk.getOriginalSourceSha256())),"required project context outside selected originals");
        reviewQuerySummary("started",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);
        List<Chunk> copied=seeds.stream().map(OerBusinessEvidence::copy).collect(Collectors.toList());
        if(stats!=null)stats.selected(seeds,rankedIds);
        reviewQuerySummary("completed",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);
        return requireScan?new ReviewTextChunks(copied,rankedIds):copied;
        }catch(RuntimeException failed){reviewQuerySummary("failed",phase,session,queryFingerprint,role,query.length(),observedBudget,started,stats);throw failed;}
    }

    private static ReviewTextStats reviewTextStats(String query){
        try{if(!LOG.isInfoEnabled())return null;ReviewTextStats stats=new ReviewTextStats();
            stats.queryFingerprint=VettingCorpus.hash(query.substring(0,Math.min(1024,query.length()))).substring(0,16);return stats;
        }catch(Throwable ignored){return null;}
    }
    private static final class ReviewTextStats {
        String queryFingerprint;
        final Set<String> offered=new HashSet<>();int seeds,selected,contexts,sources,tender,standard,facts,manifests,omitted;
        long seedChars,selectedChars,contextChars,offeredChars,omittedChars;
        void seeds(List<Chunk> chunks,boolean preview){try{seeds=chunks.size();for(Chunk chunk:chunks){seedChars+=chunk.getContent().length();if(preview)candidate(chunk,false);}}catch(Throwable ignored){}}
        void candidate(Chunk chunk,boolean overBudget){try{if(offered.add(chunk.getId())){offeredChars+=chunk.getContent().length();
            if(overBudget){omitted++;omittedChars+=chunk.getContent().length();}}}catch(Throwable ignored){}}
        void selected(List<Chunk> chunks,List<String> rankedIds){
            try{
            Set<String> ranked=new HashSet<>(rankedIds);Set<List<Object>> identities=new HashSet<>();selected=chunks.size();
            for(Chunk chunk:chunks){int chars=chunk.getContent().length();selectedChars+=chars;
                if(!ranked.contains(chunk.getId())){contexts++;contextChars+=chars;}
                List<Object> key=reviewSourceKey(chunk);if(key!=null)identities.add(key);
                if("tender".equals(chunk.getRole()))tender++;else if("standard".equals(chunk.getRole()))standard++;
                else if("project_fact".equals(chunk.getRole()))facts++;else if("package_manifest".equals(chunk.getRole()))manifests++;}
            sources=identities.size();
            }catch(Throwable ignored){/* Counters cannot interrupt retrieval. */}
        }
    }
    private static void reviewQuerySummary(String status,String phase,Session session,String queryFingerprint,String role,int queryChars,int budget,long started,ReviewTextStats stats){
        if(stats==null)return;
        try{LOG.info("Vetting evidence flow status={} phase={} threadId={} sessionFingerprint={} queryFingerprint={} queryChars={} queryPrefixChars={} role={} budgetChars={} seeds={} seedChars={} candidates={} candidateChars={} selected={} selectedChars={} contextChunks={} contextChars={} selectedSources={} tenderChunks={} standardChunks={} projectFactChunks={} manifestChunks={} budgetOmittedChunks={} budgetOmittedChars={} elapsedMs={}",
                status,phase,Thread.currentThread().getId(),session.bindingFingerprint.substring(0,Math.min(16,session.bindingFingerprint.length())),queryFingerprint,queryChars,Math.min(1024,queryChars),role==null?"all":role,budget,
                stats.seeds,stats.seedChars,stats.offered.size(),stats.offeredChars,stats.selected,stats.selectedChars,stats.contexts,stats.contextChars,stats.sources,stats.tender,stats.standard,stats.facts,stats.manifests,stats.omitted,stats.omittedChars,(System.nanoTime()-started)/1_000_000);
        }catch(Throwable ignored){/* Observation must not change retrieval or source guards. */}
    }

    /** Retain ranking provenance through caller budgeting without adding fields to source or model text. */
    static final class ReviewTextChunks extends ArrayList<Chunk> {
        final Set<String> rankedIds;
        ReviewTextChunks(List<Chunk> chunks,Collection<String> rankedIds){super(chunks);this.rankedIds=new LinkedHashSet<>(rankedIds);}
    }
    static boolean rankedReviewOrigin(List<Chunk> chunks,Chunk chunk){return !(chunks instanceof ReviewTextChunks)||((ReviewTextChunks)chunks).rankedIds.contains(chunk.getId());}
    private static final String REVIEW_TECHNICAL_LABEL="(?:[A-Z]{1,8}\\.[A-Z]?|[A-Z]{2,8})[0-9]+(?:\\.[0-9]+)*(?:\\.APPEND[0-9]+)?(?:\\.P)?";
    private static final java.util.regex.Pattern REVIEW_HEADING=java.util.regex.Pattern.compile(
            "(?i)^\\s*(?:(clause)\\s+)?((?:"+REVIEW_TECHNICAL_LABEL+")|[A-Z]?[0-9]{1,3}(?:\\.[0-9]{1,3})*)(?=\\s|[.)](?:\\s|$)|$)");
    private static final class ReviewTextIndex {
        final Map<String,Chunk> byId=new LinkedHashMap<>();
        final Map<List<Object>,List<Chunk>> sources=new LinkedHashMap<>();
        final Map<List<Object>,Map<String,List<Integer>>> clauses=new HashMap<>();
        final Set<List<Object>> ordered=new HashSet<>();
        ReviewTextIndex(List<Chunk> corpus){
            for(Chunk chunk:corpus){byId.put(chunk.getId(),chunk);List<Object> key=reviewSourceKey(chunk);
                if(key!=null)sources.computeIfAbsent(key,ignored->new ArrayList<>()).add(chunk);}
            sources.forEach((key,rows)->{Set<java.math.BigInteger> positions=new HashSet<>();
                if(rows.stream().allMatch(c->reviewPosition(c)!=null&&positions.add(reviewPosition(c)))){
                    rows.sort(Comparator.comparing(OerBusinessEvidence::reviewPosition));ordered.add(key);
                    Map<String,List<Integer>> located=new LinkedHashMap<>();for(int i=0;i<rows.size();i++){
                        String clause=reviewClause(rows.get(i));if(!clause.isEmpty())located.computeIfAbsent(clause,ignored->new ArrayList<>()).add(i);}
                    clauses.put(key,located);}});
        }
    }
    /** Select exact observed text only; adjacency never certifies ownership or complete legal scope. */
    static List<Chunk> reviewTextContext(List<Chunk> corpus,List<Chunk> seeds,JsonNode result,String query,int maxChars){
        return reviewTextContext(new ReviewTextIndex(corpus),seeds,result,query,maxChars,null);
    }
    private static List<Chunk> reviewTextContext(ReviewTextIndex index,List<Chunk> seeds,JsonNode result,String query,int maxChars,ReviewTextStats stats){
        LinkedHashMap<String,Chunk> selected=requestedReviewText(index,seeds,query,maxChars,stats);
        int[] remaining={Math.max(0,maxChars)-selected.values().stream().mapToInt(c->c.getContent().length()).sum()};
        // Assigned clause text must fit before unrelated ranked seeds consume its allowance.
        for(Chunk seed:seeds)addReviewText(selected,seed,remaining,stats);
        for(Chunk seed:seeds){
            List<Object> key=reviewSourceKey(seed);if(key==null||!selected.containsKey(seed.getId()))continue;
            List<Chunk> rows=index.sources.get(key);if(rows==null)continue;
            int position=rows.indexOf(index.byId.get(seed.getId()));boolean ordered=index.ordered.contains(key);if(position<0)continue;
            int observed=0;
            for(JsonNode unit:result.path("sourceUnits").path("units")){
                if(!completeTransport(unit)||!VettingRetrievalSourceUnits.textIds(unit.path("originIds")).contains(seed.getId()))continue;
                for(String id:VettingRetrievalSourceUnits.textIds(unit.path("requiredMemberIds"))){Chunk context=index.byId.get(id);
                    if(context!=null&&key.equals(reviewSourceKey(context))&&!differentReviewClause(seed,context)&&observed++<64)
                        addReviewText(selected,context,remaining,stats);}
            }
            if(!ordered)continue;
            List<Chunk> clause=reviewClauseText(rows,position);
            if(clause.size()>1)for(Chunk context:clause)addReviewText(selected,context,remaining,stats);
            else if(reviewClause(seed).isEmpty())for(int near:new int[]{position-1,position+1})if(near>=0&&near<rows.size()
                    &&!differentReviewClause(seed,rows.get(near))&&reviewHeading(rows.get(near)).isEmpty())
                addReviewText(selected,rows.get(near),remaining,stats);
        }
        Map<List<Object>,List<Chunk>> groups=new LinkedHashMap<>();
        for(Chunk chunk:selected.values()){List<Object> key=reviewSourceKey(chunk);
            groups.computeIfAbsent(key==null?Arrays.asList("unbound",chunk.getId()):key,ignored->new ArrayList<>()).add(chunk);}
        List<Chunk> out=new ArrayList<>();groups.forEach((key,rows)->{if(index.ordered.contains(key))rows.sort(Comparator.comparing(OerBusinessEvidence::reviewPosition));out.addAll(rows);});return out;
    }
    private static LinkedHashMap<String,Chunk> requestedReviewText(ReviewTextIndex index,List<Chunk> seeds,String query,int maxChars,ReviewTextStats stats){
        LinkedHashMap<String,Chunk> selected=new LinkedHashMap<>();int[] remaining={Math.max(0,maxChars)};
        Map<String,Integer> requestedClauses=new HashMap<>();
        for(Chunk seed:seeds){
            List<Object> key=reviewSourceKey(seed);if(key==null)continue;
            List<Chunk> rows=index.sources.get(key);if(rows==null)continue;
            int position=rows.indexOf(index.byId.get(seed.getId()));boolean ordered=index.ordered.contains(key);if(position<0)continue;
            if(ordered){
                // An explicit request can find a new clause even when ranking repeats the same seed.
                Map<String,Integer> requested=new LinkedHashMap<>();
                for(Map.Entry<String,List<Integer>> clause:index.clauses.get(key).entrySet())if(requestedClauses.computeIfAbsent(clause.getKey(),id->requestedReviewClauseOffset(query,id))>=0)
                    for(int i:clause.getValue()){Integer previous=requested.get(clause.getKey());
                        boolean body=reviewClauseText(rows,i,128).size()>1,previousBody=previous!=null&&reviewClauseText(rows,previous,128).size()>1;
                        if(previous==null||body&&!previousBody||body==previousBody&&Math.abs(i-position)<Math.abs(previous-position))requested.put(clause.getKey(),i);}
                List<Map.Entry<String,Integer>> windows=new ArrayList<>(requested.entrySet());
                windows.removeIf(parent->{String section=parent.getKey().replaceFirst("\\.P$","");
                    return section.matches("[A-Z]{1,8}\\.[A-Z][0-9]+")&&requested.keySet().stream()
                            .anyMatch(child->!child.equals(parent.getKey())&&child.startsWith(section+"."));});
                windows.sort(Comparator.comparingInt(entry->requestedClauses.get(entry.getKey())));
                int requests=0;for(Map.Entry<String,Integer> window:windows){if(requests++>=4)break;
                    for(Chunk context:reviewClauseText(rows,window.getValue(),128))addReviewText(selected,context,remaining,stats);}
            }
        }
        return selected;
    }
    private static void addReviewText(Map<String,Chunk> selected,Chunk chunk,int[] remaining,ReviewTextStats stats){
        if(chunk==null||selected.containsKey(chunk.getId())||JsonUtils.isBlankText(chunk.getContent()))return;
        boolean overBudget=chunk.getContent().length()>remaining[0];if(stats!=null)stats.candidate(chunk,overBudget);
        if(overBudget)return;
        selected.put(chunk.getId(),chunk);remaining[0]-=chunk.getContent().length();
    }
    private static List<Chunk> reviewClauseText(List<Chunk> rows,int seed){
        return reviewClauseText(rows,seed,64);
    }
    private static List<Chunk> reviewClauseText(List<Chunk> rows,int seed,int maximum){
        int start=seed;String clause=reviewClause(rows.get(seed));
        // A title can precede an unlabelled continuation, but only within a short observed window.
        if(clause.isEmpty())for(int i=seed-1;i>=Math.max(0,seed-32);i--){
            if(differentReviewClause(rows.get(seed),rows.get(i)))break;
            if(!reviewClause(rows.get(i)).isEmpty()){start=i;clause=reviewClause(rows.get(i));break;}}
        else while(start>0&&seed-start<32&&reviewHeading(rows.get(start)).isEmpty()&&clause.equals(reviewClause(rows.get(start-1)))
                &&!differentReviewClause(rows.get(seed),rows.get(start-1)))start--;
        if(clause.isEmpty())return Collections.singletonList(rows.get(seed));
        List<Chunk> out=new ArrayList<>();Chunk first=rows.get(start);
        for(int i=start;i<rows.size()&&out.size()<maximum;i++){
            Chunk row=rows.get(i);if(i>start&&!numberedTechnicalItem(first,row)&&(differentReviewClause(first,row)
                    ||!reviewHeading(row).isEmpty()&&(!clause.equals(reviewHeading(row))
                        ||JsonUtils.isBlankText(first.getClauseHeadingLocation())||!Objects.equals(first.getClauseHeadingLocation(),row.getClauseHeadingLocation()))))break;
            out.add(row);
        }
        return out;
    }
    private static boolean numberedTechnicalItem(Chunk heading,Chunk item){
        boolean unowned=JsonUtils.isBlankText(item.getClauseId())&&JsonUtils.isBlankText(item.getClauseHeadingLocation());
        boolean sameOwner=reviewClause(heading).equals(reviewClause(item))&&!JsonUtils.isBlankText(heading.getClauseHeadingLocation())
                &&heading.getClauseHeadingLocation().equals(item.getClauseHeadingLocation());
        return reviewClause(heading).matches(REVIEW_TECHNICAL_LABEL)&&(unowned||sameOwner)
                &&reviewHeading(item).matches("[0-9]{1,3}(?:\\.[0-9]{1,3})*");
    }
    private static boolean differentReviewClause(Chunk left,Chunk right){
        String a=reviewClause(left),b=reviewClause(right);
        return !a.isEmpty()&&!b.isEmpty()&&!a.equals(b)
                ||!JsonUtils.isBlankText(left.getClauseHeadingLocation())&&!JsonUtils.isBlankText(right.getClauseHeadingLocation())
                    &&!left.getClauseHeadingLocation().equals(right.getClauseHeadingLocation());
    }
    private static String reviewClause(Chunk chunk){return JsonUtils.isBlankText(chunk.getClauseId())?reviewHeading(chunk):chunk.getClauseId().trim().toUpperCase(Locale.ROOT);}
    private static String reviewHeading(Chunk chunk){
        String text=chunk.getContent()==null?"":chunk.getContent();java.util.regex.Matcher match=REVIEW_HEADING.matcher(text);if(!match.find())return "";
        String id=match.group(2).toUpperCase(Locale.ROOT);
        // Bare quantities such as "12 months" are not headings; declared clause IDs remain authoritative.
        if(id.matches("[0-9]+")&&match.group(1)==null&&(match.end(2)>=text.length()||".)".indexOf(text.charAt(match.end(2)))<0))return "";
        return id;
    }
    private static boolean requestsReviewClause(String query,String clause){return requestedReviewClauseOffset(query,clause)>=0;}
    private static int requestedReviewClauseOffset(String query,String clause){
        if(clause.isEmpty()||query==null)return -1;
        String prefix=clause.matches("[0-9]+")?"(?<!sub-)(?:clause|GCC|GCT|SCT|SCC|NTT|PRE|SL)\\s+":"";
        boolean projectVariant=clause.endsWith(".P")&&clause.matches(REVIEW_TECHNICAL_LABEL);
        String identifier=projectVariant?clause.substring(0,clause.length()-2):clause;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?i)(?<![A-Z0-9.])"+prefix+java.util.regex.Pattern.quote(identifier)+(projectVariant?"(?:\\.P)?":"")+"(?![A-Z0-9.])").matcher(query);
        return match.find()?match.start():-1;
    }
    private static java.math.BigInteger reviewPosition(Chunk chunk){
        JsonNode order=chunk.getCanonicalEvidence()==null?null:chunk.getCanonicalEvidence().getSourceOrder();
        return order!=null&&order.isIntegralNumber()&&order.bigIntegerValue().signum()>=0?order.bigIntegerValue():null;
    }
    private static List<Object> reviewSourceKey(Chunk chunk){
        EvidencePointer p=chunk.getCanonicalEvidence();if(p==null||p.getDocumentId()==null
                ||!Objects.equals(chunk.getId(),p.getCanonicalChunkId())||!Objects.equals(chunk.getDocumentId(),String.valueOf(p.getDocumentId()))
                ||!Objects.equals(chunk.getOriginalSourceSha256(),p.getOriginalSourceSha256())||!Objects.equals(chunk.getSourceHash(),p.getCanonicalSourceHash()))return null;
        if(Arrays.asList(chunk.getRole(),p.getProjectId(),p.getModule(),p.getOriginalSourceSha256(),p.getJavaParsedSourceHash(),p.getSourceRevisionSha256(),p.getCanonicalSourceHash(),p.getProjectionSha256(),p.getProjectionRevisionSha256()).stream().anyMatch(JsonUtils::isBlankText))return null;
        return Arrays.asList(chunk.getRole(),p.getProjectId(),p.getModule(),p.getDocumentId(),p.getOriginalSourceSha256(),p.getJavaParsedSourceHash(),p.getSourceRevisionSha256(),p.getCanonicalSourceHash(),p.getProjectionSha256(),p.getProjectionRevisionSha256(),p.getProjectionSchema(),p.getProducerKind(),p.getSourceRevisionKind(),p.getFamilyCompositionSha256(),p.getFamilyComponentSetSha256());
    }
    public JsonNode response(Session session,String query,String role){
        return response(session,query,role,false);
    }
    private JsonNode response(Session session,String query,String role,boolean textOnly){
        if(session.queryScope!=null){
            require(role==null,"initial Advisory query role must remain unfiltered");
            int[] admitted={0};
            try{JsonNode result=retrieval.retrieveAdvisory(session.indexKey,query,session.queryScope,session.querySourceGeneration,
                    ()->currentQueryScope(session),ids->hydrateQueryDocuments(session,ids),textOnly?(ids,ranked)->{Map<String,JsonNode> parents=compactAdviceParents(session,ids,ranked);if(parents!=null)admitted[0]=ids.size();return parents;}:null);
                if(textOnly&&admitted[0]>0)logAdviceAdmission(admitted[0],admitted[0],result.path("hits").size(),session.byId.size(),"accepted_after_rank_validation");
                else if(textOnly&&result.path("hits").isEmpty())logAdviceAdmission(0,0,0,0,"no_ranked_sources");return result;}
            catch(RuntimeException|Error rejected){try{session.close();}catch(RuntimeException|Error closing){rejected.addSuppressed(closing);}session.invalidated=true;throw rejected;}
        }
        if(textOnly)logAdviceAdmission(session.documentIds.size(),session.documentIds.size(),0,session.byId.size(),"fallback_query_scope_unavailable");
        JsonNode response;
        CurrentStage loaded=stage(session);
        try(CurrentStage stage=loaded){response=stage.frame==null?retrieval.retrieve(session.indexKey,session.module,query,role,stage.bindings)
                :retrieval.retrieve(session.indexKey,session.module,query,role,stage.frame);}
        complete(session,loaded);return response;
    }

    /** Current selected source syntax only; capabilities are never reconstructed from compact model JSON. */
    Map<String,ObservedSourceClauseLocators.Bound> sourceClauseLocators(Session session,List<Chunk> selected,Map<String,JsonNode> wordContexts){
        current(session);Map<String,ObservedSourceClauseLocators.Bound> result=new LinkedHashMap<>();
        if(session.semanticScope!=null)checkSemanticViews(session,selected.stream().filter(c->!FixedVettingReferences.isFixed(c)).collect(Collectors.toList()));
        List<Chunk> deferred=new ArrayList<>();
        for(Chunk chunk:selected){
            if(FixedVettingReferences.isFixed(chunk))continue;
            require(chunk!=null&&session.byId.containsKey(chunk.getId())&&Objects.equals(session.viewPayloads.get(chunk.getId()),viewPayload(session,chunk)),"selected source locator differs");
            if(session.semanticScope!=null&&NormalObservedContract.PRODUCER.equals(chunk.getCanonicalEvidence().getProducerKind())){deferred.add(chunk);continue;}
            ObservedSourceClauseLocators.Bound locator=session.semanticScope!=null?session.semanticLocators.get(chunk.getId()):ObservedSourceClauseLocators.capture(chunk.getCanonicalEvidence(),chunk.getCanonicalPayload(),chunk.getRole(),wordContexts.get(chunk.getId()));
            if(locator!=null&&!locator.model(chunk).isEmpty())result.put(chunk.getId(),locator);
        }
        if(!deferred.isEmpty()){
            Map<Long,List<Chunk>> groups=deferred.stream().collect(Collectors.groupingBy(c->c.getCanonicalEvidence().getDocumentId(),LinkedHashMap::new,Collectors.toList()));
            for(Map.Entry<Long,List<Chunk>> group:groups.entrySet()){
                SourceDocument source=semanticDocument(session,group.getKey());CurrentStage loaded=stage(session.projectId,session.module,Collections.singletonList(source.getId()),Collections.singletonList(source));
                try(CurrentStage stage=loaded){require(stage.frame!=null,"selected semantic locator requires verified source frame");
                for(Chunk chunk:group.getValue()){EvidencePointer pointer=stage.frame.selectedNormalPointer(chunk.getCanonicalEvidence().getDocumentId(),chunk.getCanonicalEvidence().getProjectionSha256(),chunk.getId(),session.module);
                    JsonNode raw=stage.frame.selectedNormalChunk(pointer.getDocumentId(),pointer.getProjectionSha256(),chunk.getId(),session.module);
                    JsonNode word=stage.frame.wordSourceScopeContext(pointer.getDocumentId(),pointer.getProjectionSha256(),chunk.getId(),session.module);
                    ObservedSourceClauseLocators.Bound locator=ObservedSourceClauseLocators.capture(pointer,raw,chunk.getRole(),word);
                    if(locator!=null&&!locator.model(chunk).isEmpty())result.put(chunk.getId(),locator);
                }
                completeSemanticDocument(session,stage,group.getKey());}
            }
            current(session);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Selected model context only. Neither the corpus nor a source-verification verdict is retained here. */
    public Map<String,JsonNode> wordSourceScopeContexts(Session session,List<Chunk> selected){
        if(session.semanticScope!=null){current(session);Map<String,JsonNode> contexts=new LinkedHashMap<>();List<Chunk> deferred=new ArrayList<>();for(Chunk chunk:selected){
                if(FixedVettingReferences.isFixed(chunk))continue;require(session.byId.containsKey(chunk.getId())&&Objects.equals(session.viewPayloads.get(chunk.getId()),viewPayload(session,chunk)),"selected semantic context differs");
                JsonNode context=session.semanticContexts.get(chunk.getId());if(context!=null)contexts.put(chunk.getId(),context.deepCopy());
                else if(NormalObservedContract.PRODUCER.equals(chunk.getCanonicalEvidence().getProducerKind()))deferred.add(chunk);}
            if(!deferred.isEmpty()){
                require(currentSources!=null,"selected semantic source reader required");
                Map<Long,List<Chunk>> groups=deferred.stream().collect(Collectors.groupingBy(c->c.getCanonicalEvidence().getDocumentId(),LinkedHashMap::new,Collectors.toList()));
                for(Map.Entry<Long,List<Chunk>> group:groups.entrySet()){
                    SourceDocument source=semanticDocument(session,group.getKey());String sourceHash=VettingCorpus.sourceHash(source);
                    OerModelMaterial.Request material=new OerModelMaterial.Request(Collections.singletonList(source));
                    CurrentStage loaded=stage(session.projectId,session.module,Collections.singletonList(source.getId()),Collections.singletonList(source));
                    try(CurrentStage stage=loaded){require(stage.frame!=null,"selected semantic material requires verified source frame");
                    for(Chunk chunk:group.getValue()){EvidencePointer semantic=chunk.getCanonicalEvidence();Long documentId=semantic.getDocumentId();String projection=semantic.getProjectionSha256();
                        EvidencePointer pointer=stage.frame.selectedNormalPointer(documentId,projection,chunk.getId(),session.module);
                        if(sourcePages!=null)pointer=sourcePages.enrich(pointer);
                        require(Objects.equals(session.sourceRenderPins.get(projection),pointer.getSourceRenderEvidenceIdentitySha256()),"selected source render pointer differs");
                        JsonNode raw=stage.frame.selectedNormalChunk(documentId,projection,chunk.getId(),session.module);
                        require(Objects.equals(source.getProjectId(),session.projectId)&&Objects.equals(source.getId(),documentId)
                                &&Objects.equals(source.getSourceSha256(),pointer.getOriginalSourceSha256())&&Objects.equals(sourceHash,pointer.getJavaParsedSourceHash())
                                &&Objects.equals(VettingCorpus.sourceRole(source),chunk.getRole()),"selected semantic current source differs");
                        JsonNode word=stage.frame.wordSourceScopeContext(documentId,projection,chunk.getId(),session.module);if(word!=null)pointer.setNativeWordSourceScopesContext(word);
                        contexts.put(chunk.getId(),VettingSourceMaterial.semantic(material.projectStandalone(pointer,raw,chunk.getRole()),Collections.emptyMap()));
                    }
                    completeSemanticDocument(session,stage,group.getKey());}
                }
                current(session);
            }
            return contexts;}
        Objects.requireNonNull(selected,"Selected model chunks required");List<Chunk> words=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Chunk chunk:selected){
            if(FixedVettingReferences.isFixed(chunk))continue;
            require(chunk!=null&&session.byId.containsKey(chunk.getId())&&Objects.equals(session.viewPayloads.get(chunk.getId()),JsonUtils.serializedSha256(chunk))
                    &&canonicalViewStatus(chunk)==null,"selected model member differs from canonical session");
            if(NormalObservedContract.isNormal(chunk.getCanonicalPayload())&&chunk.getCanonicalEvidence().getParts()!=null)
                for(JsonNode part:chunk.getCanonicalEvidence().getParts())if("docx_native".equals(part.path("sourcePosition").path("kind").asText())){
                    if(ids.add(chunk.getId()))words.add(chunk);break;
                }
        }
        if(words.isEmpty())return Collections.emptyMap();
        CurrentStage loaded=stage(session);Map<String,JsonNode> contexts=new LinkedHashMap<>();
        try(CurrentStage stage=loaded){for(Chunk chunk:words){EvidencePointer pointer=chunk.getCanonicalEvidence();
            JsonNode context=stage.frame==null?evidence.wordSourceScopeContext(session.projectId,pointer.getDocumentId(),pointer.getProjectionSha256(),pointer.getCanonicalChunkId(),session.module)
                    :stage.frame.wordSourceScopeContext(pointer.getDocumentId(),pointer.getProjectionSha256(),pointer.getCanonicalChunkId(),session.module);
            if(context!=null)contexts.put(chunk.getId(),context);
        }}
        complete(session,loaded);return contexts;
    }

    private List<VectorStore.SearchHit> withWordSourceScopes(Session session,List<VectorStore.SearchHit> hits){
        Map<String,Chunk> selected=new LinkedHashMap<>();
        for(VectorStore.SearchHit hit:hits)for(JsonNode member:JsonUtils.mapper().valueToTree(hit.getPayload().get("canonicalMembers"))){
            Chunk chunk=session.byId.get(member.path("id").asText());require(chunk!=null&&member.equals(chunk.getCanonicalPayload()),"advisory model member differs from canonical session");selected.put(chunk.getId(),chunk);
        }
        Map<String,JsonNode> contexts=wordSourceScopeContexts(session,new ArrayList<>(selected.values()));
        for(VectorStore.SearchHit hit:hits){Object value=hit.getPayload().get("canonicalEvidence");require(value instanceof List,"advisory model pointers required");
            for(Object raw:(List<?>)value){require(raw instanceof EvidencePointer,"advisory model pointer type differs");EvidencePointer pointer=(EvidencePointer)raw;
                JsonNode context=contexts.get(pointer.getCanonicalChunkId());if(context!=null)pointer.setNativeWordSourceScopesContext(context.deepCopy());
            }
        }
        return hits;
    }

    /** One ranked hit carries the whole observed unit; its members receive no invented rerank scores. */
    public List<VectorStore.SearchHit> adviceHits(Session session,String question) {
        return withWordSourceScopes(session,adviceHits(session,question,false));
    }
    public List<VectorStore.SearchHit> adviceTextHits(Session session,String question) {
        session.adviceQuestion=question;
        return adviceHits(session,question,true);
    }
    private List<VectorStore.SearchHit> adviceHits(Session session,String question,boolean textOnly){
        JsonNode result=response(session,question,null,textOnly);List<VectorStore.SearchHit> out=new ArrayList<>();
        for(int i=0;i<result.path("hits").size();i++) {
            JsonNode hit=result.path("hits").get(i),unit=result.path("sourceUnits").path("units").get(i);
            out.add(businessHit(session,hit.path("id").asText(),unit,hit.path("score").asDouble(),"reranked_seed"));
        }return out;
    }
    private Map<String,JsonNode> compactAdviceParents(Session session,List<Long> ids,JsonNode result){
        if(session.readStage!=null||!session.byId.isEmpty()){logAdviceAdmission(ids.size(),ids.size(),result.path("hits").size(),session.byId.size(),"fallback_loaded_session");return null;}
        Set<Long> requested=new LinkedHashSet<>(ids);List<SourceDocument> selected=session.querySelection.get().stream().filter(source->requested.contains(source.getId())).collect(Collectors.toList());
        List<CanonicalSourceBinding> bindings=evidence.compactNormalCandidates(session.projectId,session.module,selected);if(bindings==null){logAdviceAdmission(ids.size(),ids.size(),result.path("hits").size(),0,"fallback_non_normal_or_unstamped");return null;}
        currentQueryScope(session);Set<String> retained=new LinkedHashSet<>(),seeds=new LinkedHashSet<>();
        for(JsonNode hit:result.path("hits")){String id=hit.path("id").asText();require(!id.isEmpty()&&seeds.add(id),"duplicate ranked origin");retained.add(id);}
        for(JsonNode unit:result.path("sourceUnits").path("units"))for(JsonNode envelope:Arrays.asList(unit,unit.path("hierarchyContext"),unit.path("attachmentContext"))){
            for(JsonNode id:envelope.path("requiredMemberIds"))retained.add(id.asText());
            for(JsonNode member:envelope.path("members"))retained.add(member.path("id").asText());
        }
        Set<String> clauses=requestedAdviceClauses(session.adviceQuestion),contextSeeds=new LinkedHashSet<>();
        for(JsonNode hit:result.path("hits")){JsonNode raw=hit.path("payload");SourceDocument source=selected.stream().filter(value->String.valueOf(value.getId()).equals(raw.path("documentId").asText())).findFirst().orElse(null);
            if(source!=null&&contextSeeds.size()<10&&adviceClauseSeed(source.getFileKey(),raw.path("content").asText(),clauses))contextSeeds.add(hit.path("id").asText());}
        CanonicalEvidenceService.SelectedNormalTextSources text=contextSeeds.isEmpty()?evidence.selectedNormalTextSources(session.projectId,session.module,selected,bindings,retained)
                :evidence.selectedNormalTextSources(session.projectId,session.module,selected,bindings,retained,contextSeeds,24000);
        if(text==null){logAdviceAdmission(ids.size(),bindings.size(),result.path("hits").size(),0,"fallback_registered_authority_not_normal");return null;}
        CanonicalEvidenceService.CompactBindingScope scope=text.scope();Map<Long,SourceDocument> sources=text.sources();Map<Long,JsonNode> headers=text.headers();
        Map<String,JsonNode> parents=text.chunks();Map<Long,CanonicalSourceBinding> byDocument=new LinkedHashMap<>();bindings.forEach(binding->byDocument.put(binding.getDocumentId(),binding));
        ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","catalog");demand.put("maxBytes",16777216);
        evidence.assertCompactCurrent(scope);JsonNode catalog=retrieval.readSources(session.module,demand,bindings);evidence.assertCompactCurrent(scope);currentQueryScope(session);
        for(String id:seeds)require(parents.containsKey(id),"ranked source origin unavailable");
        for(Map.Entry<String,JsonNode> entry:parents.entrySet()){String id=entry.getKey();JsonNode raw=entry.getValue();Long document=Long.valueOf(raw.path("documentId").asText());CanonicalSourceBinding binding=byDocument.get(document);
            require(binding!=null,"ranked source outside selected documents");EvidencePointer pointer=CanonicalEvidenceService.compactNormalPointer(binding,headers.get(document),raw,session.module);
            if(sourcePages!=null)pointer=sourcePages.enrich(pointer);require(Objects.equals(session.sourceRenderPins.get(binding.getProjectionSha256()),pointer.getSourceRenderEvidenceIdentitySha256()),"source render pointer differs from reviewed identity");
            Chunk view=view(raw,pointer,sources.get(document));require(canonicalViewStatus(view)==null,"selected normal text view differs");session.byId.put(id,view);session.chunks.owned.add(view);session.viewPayloads.put(id,JsonUtils.serializedSha256(view));
        }
        Runnable byteGuard=()->{List<CanonicalSourceBinding> current=currentQueryScope(session);evidence.assertCompactCurrent(scope);
            Set<Long> disclosed=new LinkedHashSet<>(requested);for(Chunk view:session.byId.values())disclosed.add(Long.valueOf(view.getDocumentId()));
            List<SourceDocument> additional=session.querySelection.get().stream().filter(source->disclosed.contains(source.getId())&&!requested.contains(source.getId())).collect(Collectors.toList());
            if(!additional.isEmpty())requireAdviceOriginalBytes(session,additional);
            List<CanonicalSourceBinding> admitted=current.stream().filter(binding->disclosed.contains(binding.getDocumentId())).collect(Collectors.toList());JsonNode closed=retrieval.readSources(session.module,demand,admitted);
            for(JsonNode source:catalog.path("sources")){boolean found=false;for(JsonNode after:closed.path("sources"))if(source.equals(after))found=true;require(found,"selected Advisory source catalog changed");}
            if(!additional.isEmpty())requireAdviceOriginalBytes(session,additional);evidence.assertCompactCurrent(scope);currentQueryScope(session);};
        session.adviceTextByteGuard=byteGuard;Runnable previous=session.initialAskExit;session.initialAskExit=()->{byteGuard.run();if(previous!=null)previous.run();evidence.assertCompactCurrent(scope);currentQueryScope(session);};
        return new OerCanonicalRetrievalClient.RankedTextParents(parents);
    }
    private static void logAdviceAdmission(int sources,int projections,int hits,int views,String reason){LOG.info("Advisory selected-proof admission selectedSources={} selectedProjections={} rankedHits={} retainedViews={} reason={}",sources,projections,hits,views,reason);}
    /** Explicit model evidence requests may now load their complete source documents. */
    public void prepareAdviceSupplement(Session session,List<VectorStore.SearchHit> selected){
        if(session.queryScope==null)return;Set<Long> documents=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:selected)if(!fixedBaselineHit(hit))for(EvidencePointer pointer:canonicalPointers(hit))documents.add(pointer.getDocumentId());
        if(!documents.isEmpty())hydrateQueryDocuments(session,new ArrayList<>(documents));
    }

    /** Advisory declares its context needs; source selection and observed closure stay in shared OER. */
    public List<VectorStore.SearchHit> advisoryTextContext(Session session,List<VectorStore.SearchHit> hits){
        String question=session.adviceQuestion;
        if(session.adviceTextByteGuard==null&&(session.queryScope==null||!session.byId.isEmpty()))return withIncorporationContext(session,hits,true);
        List<VectorStore.SearchHit> libraries=hits.stream().filter(hit->fixedBaselineHit(hit)&&!String.valueOf(hit.getPayload().getOrDefault("fixedReferenceDocumentTitle","")).isEmpty()).collect(Collectors.toList());
        if(libraries.isEmpty())return hits;
        Set<String> composition=new LinkedHashSet<>(),amendments=new LinkedHashSet<>(),amendmentDocuments=new LinkedHashSet<>();
        for(SourceDocument source:session.querySelection.get())if("tender".equals(VettingCorpus.sourceRole(source))){
            String name=text(source.getFileName()).toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
            if(name.matches(".*\\bcoc\\b.*")||name.contains("conditions of contract")&&!name.contains("special"))composition.add(source.getSourceSha256());
            if(name.matches(".*\\bscc\\b.*")||name.contains("special conditions of contract")){amendments.add(source.getSourceSha256());amendmentDocuments.add(String.valueOf(source.getId()));}
        }
        Set<String> titles=libraries.stream().map(hit->String.valueOf(hit.getPayload().get("fixedReferenceDocumentTitle"))).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> clauses=requestedAdviceClauses(question);
        if(clauses.isEmpty())for(VectorStore.SearchHit library:libraries){String label=String.valueOf(library.getPayload().getOrDefault("clauseId",""));
            if(label.isEmpty()||"null".equals(label))label=text(library.text()).split("\\R",2)[0];
            java.util.regex.Matcher primary=java.util.regex.Pattern.compile("(?i)^\\s*(?:GCC\\s*)?(\\d{1,2}\\.\\d{1,3})(?=\\s|[.(]|$)").matcher(label);
            if(primary.find()){clauses.add(primary.group(1));break;}}
        List<VectorStore.SearchHit> contexts=new ArrayList<>();
        boolean adoptionPresent=titles.stream().allMatch(title->hits.stream().anyMatch(hit->"tender".equals(hit.getPayload().get("sourceRole"))&&FixedReferenceEvidence.adoptionMatches(hit.fileLabel(),hit.text(),title)));
        if(!composition.isEmpty()&&!adoptionPresent)for(VectorStore.SearchHit hit:compactAdviceContext(session,String.join(" ",titles)+" documents forming part of the Contract",composition))
            if(titles.stream().anyMatch(title->FixedReferenceEvidence.adoptionMatches(hit.fileLabel(),hit.text(),title)))contexts.add(hit);
        boolean amendmentsPresent=clauses.stream().allMatch(number->session.chunks.stream().anyMatch(chunk->amendmentDocuments.contains(chunk.getDocumentId())
                &&java.util.regex.Pattern.compile("(?i)\\b(?:gcc\\s+)?clauses?\\s+"+java.util.regex.Pattern.quote(number)+"(?=\\b|\\()").matcher(chunk.getContent()).find()
                &&chunk.getContent().toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:amended|substitut\\w*|replac\\w*)\\b.*")));
        if(!amendments.isEmpty()&&!clauses.isEmpty()&&!amendmentsPresent)contexts.addAll(compactAdviceContext(session,clauses.stream().map(value->"Clause "+value).collect(Collectors.joining(" "))+" of the General Conditions of Contract is amended substituted following",amendments));
        List<Chunk> clauseSeeds=session.chunks.stream().filter(chunk->adviceClauseSeed(chunk.getFileKey(),chunk.getContent(),clauses)).collect(Collectors.toList());
        if(!clauseSeeds.isEmpty())for(Chunk chunk:reviewTextContext(session.chunks,clauseSeeds,JsonUtils.mapper().createObjectNode(),String.join(" ",clauses),12000)){
            ObjectNode unit=OerCanonicalSourceUnits.resolve(canonicalParents(session),chunk.getCanonicalPayload());unit.put("kind","unknown");unit.put("status","unknown");unit.put("reason","additional_source_context_deferred");unit.putArray("requiredMemberIds");unit.putArray("members");unit.remove("hierarchyContext");unit.remove("attachmentContext");
            contexts.add(businessHit(session,chunk.getId(),unit,0d,"context_only"));}
        // Necessary scope text precedes ranked detail so a byte bound cannot silently discard adoption.
        List<VectorStore.SearchHit> result=new ArrayList<>();Set<String> present=new LinkedHashSet<>();int chars=0;
        for(VectorStore.SearchHit hit:contexts)if(chars+hit.text().length()<=12000&&result.size()<64&&present.add(hit.getId())){result.add(hit);chars+=hit.text().length();}
        for(VectorStore.SearchHit hit:hits)if(present.add(hit.getId()))result.add(hit);
        return result;
    }
    static Set<String> requestedAdviceClauses(String question){
        return FixedReferenceEvidence.requestedClauseLabels(question);
    }
    private static boolean adviceClauseSeed(String family,String content,Set<String> clauses){
        if(!Arrays.asList("SCC","PRE").contains(text(family).toUpperCase(Locale.ROOT)))return false;
        String value=text(content);java.util.regex.Matcher heading=REVIEW_HEADING.matcher(value);boolean technical=heading.find()&&heading.group(2).toUpperCase(Locale.ROOT).matches(REVIEW_TECHNICAL_LABEL);
        if(clauses.isEmpty())return technical||value.matches("(?is).*\\bclauses?\\s+\\d+\\.\\d+.*\\b(?:amended|substitut\\w*|replac\\w*)\\b.*");
        for(String clause:clauses)if(java.util.regex.Pattern.compile("(?i)(?<![0-9.])"+java.util.regex.Pattern.quote(clause)+"(?![0-9.])").matcher(value).find()
                ||technical&&"SCC".equalsIgnoreCase(family)&&heading.group(2).toUpperCase(Locale.ROOT).startsWith("SCC"+clause.substring(0,clause.indexOf('.'))+"."))return true;
        return false;
    }
    private List<VectorStore.SearchHit> compactAdviceContext(Session session,String query,Set<String> selectedSources){
        currentQueryScope(session);
        Session selected=new Session(session.projectId,session.module,session.documentIds,session.queryScope,new LinkedHashMap<>(),session.sourceRenderPins);
        selected.queryScope=session.queryScope;selected.querySelection=session.querySelection;selected.querySourceGeneration=session.querySourceGeneration;selected.originalFileStates=session.originalFileStates;
        selected.adviceQuestion=query;
        java.util.function.Function<List<Long>,CanonicalEvidenceService.VerifiedBindingFrame> noGraphs=ids->{throw new IllegalStateException("Advice scope text requires compact proof; complete source graphs were not loaded");};
        JsonNode response=retrieval.retrieveCompact(session.module,session.indexKey,query,"tender",session.queryScope,session.querySourceGeneration,()->currentQueryScope(selected),noGraphs,
                (ids,result)->compactAdviceParents(selected,ids,result),true,selectedSources);
        List<VectorStore.SearchHit> hits=new ArrayList<>();for(int i=0;i<response.path("hits").size();i++){
            JsonNode hit=response.path("hits").get(i);hits.add(businessHit(selected,hit.path("id").asText(),response.path("sourceUnits").path("units").get(i),hit.path("score").asDouble(),"requested_scope_context"));
        }
        selected.completeInitialAsk();
        if(selected.adviceTextByteGuard!=null){Runnable previous=session.adviceTextByteGuard,additional=selected.adviceTextByteGuard;session.adviceTextByteGuard=()->{if(previous!=null)previous.run();additional.run();};}
        for(Chunk view:selected.byId.values())if(!session.byId.containsKey(view.getId())){session.byId.put(view.getId(),view);session.viewPayloads.put(view.getId(),selected.viewPayloads.get(view.getId()));session.chunks.owned.add(view);}
        currentQueryScope(session);return hits;
    }
    public List<VectorStore.SearchHit> advisoryContext(Session session,List<VectorStore.SearchHit> hits,boolean includeSmallCorpus,int stuffingLimit){
        List<VectorStore.SearchHit> selected=withIncorporationContext(session,hits);
        selected=advisoryTableContext(session,selected);
        return includeSmallCorpus?withSmallCorpusContext(session,selected,stuffingLimit):selected;
    }
    public OerModelMaterial.Request modelMaterial(Session session,List<VectorStore.SearchHit> hits){
        require(session!=null,"canonical model material requires its selected source session");
        return new OerModelMaterial.Request(materialSources(session,hits,false));
    }
    private List<VectorStore.SearchHit> withSmallCorpusContext(Session session,List<VectorStore.SearchHit> hits,int stuffingLimit){
        long remaining=stuffingLimit;if(remaining<=0)return hits;
        for(Chunk chunk:session.chunks){remaining-=text(chunk.getContent()).length();if(remaining<0)return hits;}
        Set<String> represented=new LinkedHashSet<>(),units=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:hits)if(!fixedBaselineHit(hit)){
            represented.add(hit.getId());JsonNode unit=canonicalUnit(hit);units.add(unit==null?hit.getId():unit.path("unitId").asText(hit.getId()));
            for(EvidencePointer pointer:canonicalPointers(hit))if(!JsonUtils.isBlankText(pointer.getCanonicalChunkId()))represented.add(pointer.getCanonicalChunkId());
        }
        List<String> origins=session.chunks.stream().map(Chunk::getId).filter(id->!represented.contains(id)).collect(Collectors.toList());
        if(origins.isEmpty())return hits;
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);
        for(VectorStore.SearchHit context:sourceHits(session,origins)){JsonNode unit=canonicalUnit(context);if(units.add(unit==null?context.getId():unit.path("unitId").asText(context.getId())))result.add(context);}
        return result;
    }
    public List<VectorStore.SearchHit> advisoryTableContext(Session session,List<VectorStore.SearchHit> hits){
        List<SourceDocument> sources=materialSources(session,hits,true);if(sources.isEmpty())return hits;
        OerModelMaterial.Request material=new OerModelMaterial.Request(sources);
        Map<List<String>,Set<String>> members=new LinkedHashMap<>();Set<String> present=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:hits){
            JsonNode unit=canonicalUnit(hit);if(unit!=null)present.add(unit.path("unitId").asText());
            JsonNode payloads=JsonUtils.mapper().valueToTree(hit.getPayload().get("canonicalMembers"));int ordinal=0;
            for(EvidencePointer pointer:canonicalPointers(hit)){
                JsonNode payload=payloads.isArray()&&ordinal<payloads.size()?payloads.get(ordinal):null;ordinal++;
                if(!NormalObservedContract.PRODUCER.equals(pointer.getProducerKind()))continue;
                JsonNode selected=material.project(pointer,payload,String.valueOf(hit.getPayload().get("sourceRole")));
                for(JsonNode table:selected.path("observedSourceUnits").path("units"))
                    if(Arrays.asList("ordinary_docx_native_table_tree","ordinary_docx_table").contains(table.path("kind").asText()))
                        for(JsonNode id:table.path("requiredMemberBlockIds"))members.computeIfAbsent(tableSource(pointer,String.valueOf(hit.getPayload().get("sourceRole"))),key->new LinkedHashSet<>()).add(id.asText());
            }
        }
        if(members.isEmpty())return hits;
        List<String> origins=new ArrayList<>();
        for(Chunk chunk:session.chunks){EvidencePointer pointer=chunk.getCanonicalEvidence();if(pointer==null)continue;
            Set<String> required=members.get(tableSource(pointer,chunk.getRole()));
            if(required!=null&&chunk.getParts().stream().anyMatch(part->required.contains(part.getBlockId())))origins.add(chunk.getId());
        }
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);
        for(VectorStore.SearchHit context:sourceHits(session,origins))if(present.add(canonicalUnit(context).path("unitId").asText()))result.add(context);
        return result;
    }
    private List<SourceDocument> materialSources(Session session,List<VectorStore.SearchHit> hits,boolean normalOnly){
        Map<Long,SourceDocument> byDocument=new LinkedHashMap<>();
        for(VectorStore.SearchHit hit:hits)for(EvidencePointer pointer:canonicalPointers(hit)){
            boolean normal=NormalObservedContract.PRODUCER.equals(pointer.getProducerKind());if(normalOnly&&!normal)continue;
            require(Objects.equals(session.projectId,pointer.getProjectId())&&session.documentIds.contains(pointer.getDocumentId()),"model material pointer outside selected scope");
            SourceDocument source=byDocument.get(pointer.getDocumentId());
            if(normal&&session.semanticScope!=null){
                require(currentSources!=null,"current source reader required for selected model material");
                if(source==null){source=semanticDocument(session,pointer.getDocumentId());byDocument.put(pointer.getDocumentId(),source);}
            } else if(source==null){require(currentSources!=null,"current source reader required for selected model material");source=normal?currentSources.normalDocument(session.projectId,pointer.getDocumentId(),session.module):currentSources.currentDocument(session.projectId,pointer.getDocumentId(),session.module);byDocument.put(pointer.getDocumentId(),source);}
            require(Objects.equals(source.getProjectId(),pointer.getProjectId())&&Objects.equals(source.getId(),pointer.getDocumentId())
                    &&Objects.equals(source.getSourceSha256(),pointer.getOriginalSourceSha256())&&Objects.equals(VettingCorpus.sourceHash(source),pointer.getJavaParsedSourceHash())
                    &&Objects.equals(VettingCorpus.sourceRole(source),String.valueOf(hit.getPayload().get("sourceRole"))),"model material current source differs from selected pointer");
        }
        return new ArrayList<>(byDocument.values());
    }
    private static List<String> tableSource(EvidencePointer pointer,String role){
        return Arrays.asList(pointer.getProjectId(),String.valueOf(pointer.getDocumentId()),pointer.getOriginalSourceSha256(),pointer.getJavaParsedSourceHash(),pointer.getProjectionSha256(),role);
    }
    private List<VectorStore.SearchHit> withIncorporationContext(Session session,List<VectorStore.SearchHit> hits){
        return withIncorporationContext(session,hits,false);
    }
    private List<VectorStore.SearchHit> withIncorporationContext(Session session,List<VectorStore.SearchHit> hits,boolean textOnly){
        List<VectorStore.SearchHit> libraries=hits.stream().filter(h->"standard".equals(String.valueOf(h.getPayload().get("sourceRole")))).collect(Collectors.toList());if(libraries.isEmpty())return hits;
        if(session.queryScope!=null){
            Set<String> loaded=session.byId.values().stream().map(Chunk::getDocumentId).collect(Collectors.toSet());List<Long> requested=new ArrayList<>();
            for(SourceDocument source:session.querySelection.get()){
                String name=text(source.getFileName()).toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
                if(!loaded.contains(String.valueOf(source.getId()))&&(name.matches(".*\\b(?:coc|scc)\\b.*")||name.contains("conditions of contract")||name.contains("specification composition")))requested.add(source.getId());
            }
            if(!requested.isEmpty())hydrateQueryDocuments(session,requested);
        }
        IncorporationOrigins selected=incorporationOrigins(session.chunks,libraries);
        List<String> origins=selected.ids;
        List<VectorStore.SearchHit> contexts=origins.isEmpty()?Collections.emptyList():textOnly?sourceTextHits(session,origins):sourceHits(session,origins);
        List<String> requiredUnits=contexts.stream().map(context->canonicalUnit(context).path("unitId").asText()).distinct().collect(Collectors.toList());
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);Set<String> units=new LinkedHashSet<>(),representedOrigins=new LinkedHashSet<>();
        for(VectorStore.SearchHit hit:hits){JsonNode unit=canonicalUnit(hit);if(unit!=null){units.add(unit.path("unitId").asText());recordCompleteMembers(unit,representedOrigins);}}
        boolean complete=selected.hasComposition&&(session.queryScope==null||session.byId.values().stream().map(Chunk::getDocumentId).distinct().count()==session.documentIds.size());int contextChars=0;
        for(VectorStore.SearchHit context:contexts){JsonNode unit=canonicalUnit(context);if(unit==null||!"complete_observed_unit".equals(unit.path("status").asText()))complete=false;
            String unitId=unit==null?context.getId():unit.path("unitId").asText();if(units.contains(unitId))continue;
            if(contextChars+context.text().length()>12000){complete=false;continue;}
            units.add(unitId);result.add(context);contextChars+=context.text().length();recordCompleteMembers(unit,representedOrigins);
        }
        complete=complete&&representedOrigins.containsAll(origins);List<VectorStore.SearchHit> annotated=new ArrayList<>();
        for(VectorStore.SearchHit hit:result){Map<String,Object> payload=new LinkedHashMap<>(hit.getPayload());if("standard".equals(payload.get("sourceRole"))&&!fixedBaselineHit(hit)){payload.put("applicabilityContextComplete",complete);payload.put("applicabilityContextRequiredUnits",requiredUnits);}annotated.add(new VectorStore.SearchHit(hit.getId(),hit.getScore(),payload));}
        return annotated;
    }
    static final class IncorporationOrigins {
        final List<String> ids;final boolean hasComposition;
        IncorporationOrigins(List<String> ids,boolean hasComposition){this.ids=ids;this.hasComposition=hasComposition;}
    }
    static IncorporationOrigins incorporationOrigins(List<Chunk> chunks,List<VectorStore.SearchHit> libraries){
        Set<String> clauses=new LinkedHashSet<>();java.util.regex.Pattern clause=java.util.regex.Pattern.compile("(?i)\\b(?:clause\\s+|gcc[\\s-]*)?(\\d{1,2}\\.\\d{1,3})(?=\\b|\\()");
        for(VectorStore.SearchHit library:libraries){String opening=(text(library.anchor())+" "+text(library.text()));for(java.util.regex.Matcher match=clause.matcher(opening.substring(0,Math.min(opening.length(),600)));match.find();)clauses.add(match.group(1));}
        List<java.util.regex.Pattern> linkedClauses=clauses.stream().map(number->java.util.regex.Pattern.compile("(?i)\\b(?:gcc\\s+)?clause\\s+"+java.util.regex.Pattern.quote(number)+"(?=\\b|\\()"))
                .collect(Collectors.toList());
        List<String> composition=new ArrayList<>(),amendments=new ArrayList<>();String previousDocument=null;boolean continuation=false;
        for(Chunk chunk:chunks){
            if(!"tender".equals(chunk.getRole()))continue;
            String name=text(chunk.getFileName()).toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
            String content=text(chunk.getContent());boolean specification=name.contains("the specification")||name.contains("specification composition");
            boolean conditions=name.matches(".*\\bcoc\\b.*")||name.contains("conditions of contract");
            if(specification||conditions&&libraries.stream().anyMatch(h->referenceMention(content,h.fileLabel())))composition.add(chunk.getId());
            boolean scc=name.matches(".*\\bscc\\b.*")||name.contains("special conditions of contract");
            boolean linked=scc&&linkedClauses.stream().anyMatch(pattern->pattern.matcher(content).find());
            if(linked||continuation&&Objects.equals(previousDocument,chunk.getDocumentId()))amendments.add(chunk.getId());
            continuation=linked&&content.toLowerCase(Locale.ROOT).matches("(?s).*\\bfollowing\\s*:-?\\s*$");previousDocument=chunk.getDocumentId();
        }
        List<String> origins=new ArrayList<>(new LinkedHashSet<>(composition));for(String id:amendments)if(!origins.contains(id))origins.add(id);
        return new IncorporationOrigins(origins,!composition.isEmpty());
    }
    private static void recordCompleteMembers(JsonNode unit,Set<String> representedOrigins){
        if(unit!=null&&"complete_observed_unit".equals(unit.path("status").asText()))for(JsonNode member:unit.path("requiredMemberIds"))representedOrigins.add(member.asText());
    }
    public static boolean referenceMention(String passage,String referenceLabel){
        String content=text(passage).toLowerCase(Locale.ROOT),label=text(referenceLabel).toLowerCase(Locale.ROOT);
        if(label.startsWith("sl ")||label.contains("specification library")||label.contains("general specification"))return content.contains("specification library")||content.contains("general specification")||content.matches("(?s).*\\b(?:sl[ -]?20\\d\\d|gscew\\d*|gs[ -]?20\\d\\d)\\b.*");
        if(label.startsWith("gcc ")||label.contains("general conditions of contract"))return content.contains("general conditions of contract")||content.matches("(?s).*\\bgcc\\b.*");
        String key=label.split("[ ·]",2)[0];return !key.isEmpty()&&content.matches("(?s).*\\b"+java.util.regex.Pattern.quote(key)+"\\b.*");
    }
    private static JsonNode canonicalUnit(VectorStore.SearchHit hit){Object unit=hit.getPayload().get("canonicalSourceUnit");return unit instanceof JsonNode?(JsonNode)unit:unit==null?null:JsonUtils.mapper().valueToTree(unit);}
    private static List<EvidencePointer> canonicalPointers(VectorStore.SearchHit hit){Object pointers=hit.getPayload().get("canonicalEvidence");return pointers==null?Collections.emptyList():JsonUtils.readList(JsonUtils.write(pointers),EvidencePointer.class);}
    private static boolean fixedBaselineHit(VectorStore.SearchHit hit){return hit!=null&&hit.getPayload()!=null&&Boolean.TRUE.equals(hit.getPayload().get("fixedDevelopmentBaseline"));}
    private static String text(String value){return value==null?"":value;}
    /** OER returns observed closure for explicitly requested origins in this same immutable corpus. */
    public List<VectorStore.SearchHit> sourceHits(Session session,List<String> originIds){
        return withWordSourceScopes(session,sourceTextHits(session,originIds));
    }
    public List<VectorStore.SearchHit> sourceTextHits(Session session,List<String> originIds){
        require(originIds!=null&&new HashSet<>(originIds).size()==originIds.size(),"context origins invalid");
        for(String id:originIds)require(session.byId.containsKey(id),"context origin outside selected corpus");
        if(originIds.isEmpty()){current(session);return Collections.emptyList();}
        CurrentStage loaded=stage(session);String policy=null;
        List<VectorStore.SearchHit> out=new ArrayList<>();Set<String> used=new HashSet<>();
        try(CurrentStage stage=loaded){
            for(int start=0;start<originIds.size();start+=256){
                List<String> batch=originIds.subList(start,Math.min(originIds.size(),start+256));ObjectNode demand=JsonUtils.mapper().createObjectNode();
                demand.put("operation","ids");demand.put("view","canonical_units");demand.put("maxItems",batch.size());demand.put("maxBytes",16777216);demand.set("originIds",JsonUtils.mapper().valueToTree(batch));
                JsonNode result=stage.frame==null?retrieval.readSources(session.module,demand,stage.bindings):retrieval.readSources(session.module,demand,stage.frame);
                String returnedPolicy=result.path("sourceUnitPolicy").asText();require(VettingRetrievalSourceUnits.supportsPolicy(returnedPolicy)&&(policy==null||policy.equals(returnedPolicy)),"context source-unit capability is unknown or changed");policy=returnedPolicy;
                JsonNode origins=result.path("origins"),units=result.path("units");require(origins.isArray()&&units.isArray()&&origins.size()==batch.size()&&units.size()==batch.size(),"context source read omitted requested origins");
                for(int i=0;i<batch.size();i++){
                    String id=batch.get(i);require(id.equals(origins.get(i).path("id").asText()),"context source read reordered requested origins");JsonNode unit=units.get(i);
                    require(unit.path("unitId").isTextual()&&!unit.path("unitId").asText().isEmpty(),"context source-unit identity missing");
                    if(used.add(unit.path("unitId").asText()))out.add(businessHit(session,id,unit,0d,"context_only"));
                }
            }
        }
        complete(session,loaded);return out;
    }
    /** One synchronous adapter owns these lazy reads; rejected identities never parse the source. */
    private static final class RuleSourceRead {
        final SourceDocument source;
        private String sourceHash;
        private List<com.consense.document.DocumentBlock> blocks;
        private RuntimeException blocksFailure;
        private boolean blocksRead;
        private final Map<String,List<com.consense.document.DocumentBlock>> byId=new HashMap<>();
        private Map<JsonNode,Map<String,List<com.consense.document.DocumentBlock>>> headings;
        private JsonNode coverage;
        RuleSourceRead(SourceDocument source){this.source=source;}
        String sourceHash(){if(sourceHash==null)sourceHash=VettingCorpus.sourceHash(source);return sourceHash;}
        List<com.consense.document.DocumentBlock> blocks(){
            if(!blocksRead){blocksRead=true;try{blocks=VettingCorpus.blocks(source);for(com.consense.document.DocumentBlock block:blocks)byId.computeIfAbsent(block.getId(),key->new ArrayList<>()).add(block);}
                catch(RuntimeException invalid){blocksFailure=invalid;}}
            if(blocksFailure!=null)throw blocksFailure;return blocks;
        }
        List<com.consense.document.DocumentBlock> blockMatches(String id){blocks();return byId.getOrDefault(id,Collections.emptyList());}
        List<com.consense.document.DocumentBlock> headingMatches(JsonNode position,String text){
            if(headings==null){headings=new HashMap<>();for(com.consense.document.DocumentBlock block:blocks())headings.computeIfAbsent(block.getSourcePosition(),key->new HashMap<>()).computeIfAbsent(block.getEffectiveText(),key->new ArrayList<>()).add(block);}
            return headings.getOrDefault(position,Collections.emptyMap()).getOrDefault(text,Collections.emptyList());
        }
        JsonNode coverage(){
            if(coverage==null)try{coverage=JsonUtils.parse(source.getParseCoverageJson());}
                catch(RuntimeException invalid){coverage=JsonUtils.mapper().createObjectNode();}
            return coverage;
        }
    }
    /** Deterministic checks traverse only accepted observations, retaining source-bound original anchors. */
    public List<VettingRuleEngine.Source> ruleSources(Session session){
        return ruleSources(session,Collections.emptyList());
    }
    public List<VettingRuleEngine.Source> ruleSources(Session session,List<SourceDocument> capturedSources){
        current(session);if(session.semanticScope!=null){List<VettingRuleEngine.Source> copied=new ArrayList<>();for(VettingRuleEngine.Source source:session.semanticRuleSources)
            copied.add(new VettingRuleEngine.Source(source.getId(),source.getFileKey(),source.getFileName(),source.getHash(),JsonUtils.readList(JsonUtils.write(source.getBlocks()),com.consense.document.DocumentBlock.class),source.getText()));return copied;}
        return buildRuleSources(session,capturedSources);
    }
    private static List<VettingRuleEngine.Source> buildRuleSources(Session session,List<SourceDocument> capturedSources){
        Map<String,List<Chunk>> byDocument=new LinkedHashMap<>();for(Chunk c:session.chunks)
            if("tender".equals(c.getRole()))byDocument.computeIfAbsent(c.getDocumentId(),key->new ArrayList<>()).add(c);
        List<VettingRuleEngine.Source> out=new ArrayList<>();for(List<Chunk> chunks:byDocument.values()){
            Chunk first=chunks.get(0);List<com.consense.document.DocumentBlock> blocks=new ArrayList<>();Set<String> observed=new HashSet<>();
            List<SourceDocument> matching=capturedSources==null?Collections.emptyList():capturedSources.stream().filter(d->d!=null&&Objects.equals(String.valueOf(d.getId()),first.getDocumentId())).collect(Collectors.toList());
            RuleSourceRead read=new RuleSourceRead(matching.size()==1?matching.get(0):null);
            for(Chunk c:chunks)for(Part p:c.getParts())if(observed.add(p.getBlockId()+"|"+p.getStartOffset()+"|"+p.getEndOffset())){
                com.consense.document.DocumentBlock b=new com.consense.document.DocumentBlock();b.setId(p.getBlockId());b.setLocation(p.getAnchor());
                b.setText(p.getText());b.setOriginalText(p.getText());b.setSource(NormalObservedContract.isNormal(c.getCanonicalPayload())?"normal_observed_candidate":"source_reviewed_canonical_observation");
                if(p.getPageNo()!=null)b.setPageNo(Integer.valueOf(p.getPageNo().substring(1)));
                List<Double> box=p.getBbox();if(box!=null)b.setBbox(box.stream().mapToDouble(Double::doubleValue).toArray());
                bindRuleContext(session.projectId,read,c,p,b);blocks.add(b);
            }
            out.add(new VettingRuleEngine.Source(first.getDocumentId(),first.getFileKey(),first.getFileName(),first.getCanonicalEvidence().getJavaParsedSourceHash(),
                    blocks,blocks.stream().map(com.consense.document.DocumentBlock::getText).collect(Collectors.joining("\n"))));
        }return out;
    }
    /** Copies only structure from the unique current source/range; never unselected field or cell text. */
    static void bindRuleContext(String projectId,SourceDocument source,Chunk chunk,Part part,com.consense.document.DocumentBlock target) {
        bindRuleContext(projectId,new RuleSourceRead(source),chunk,part,target);
    }
    private static void bindRuleContext(String projectId,RuleSourceRead read,Chunk chunk,Part part,com.consense.document.DocumentBlock target) {
        EvidencePointer pointer=chunk.getCanonicalEvidence();
        bindRuleContext(projectId,read,chunk.getDocumentId(),pointer==null?null:pointer.getJavaParsedSourceHash(),pointer==null?null:pointer.getOriginalSourceSha256(),chunk.getCanonicalPayload()==null?null:chunk.getCanonicalPayload().get("parts"),part,target);
    }
    private static void bindRuleContext(String projectId,RuleSourceRead read,String documentId,String parsedSourceHash,String originalSourceSha256,JsonNode acceptedParts,Part part,com.consense.document.DocumentBlock target) {
        SourceDocument source=read.source;
        ObjectNode context=JsonUtils.mapper().createObjectNode(),binding=context.putObject(VettingPackageIdentity.CONTEXT);target.setReadingContext(context);
        binding.put("status","unknown");binding.put("reason","current_source_binding_missing");binding.put("startOffset",part.getStartOffset());binding.put("endOffset",part.getEndOffset());binding.put("offsetUnit","utf16");
        if(source==null||!Objects.equals(projectId,source.getProjectId())||!"PARSED".equals(source.getParseStatus())||acceptedParts==null
                ||!Objects.equals(String.valueOf(source.getId()),documentId)||!Objects.equals(read.sourceHash(),parsedSourceHash)
                ||source.getSourceSha256()==null||!Objects.equals(source.getSourceSha256(),originalSourceSha256))return;
        List<com.consense.document.DocumentBlock> matches;
        try{matches=read.blockMatches(part.getBlockId());}
        catch(RuntimeException invalid){binding.put("reason","source_structure_unreadable");return;}
        binding.put("reason","original_block_not_unique");if(matches.size()!=1)return;com.consense.document.DocumentBlock original=matches.get(0);String text=original.getEffectiveText();int start=part.getStartOffset(),end=part.getEndOffset();
        binding.put("reason","accepted_range_differs");if(text==null||start<0||end<start||end>text.length()||!Objects.equals(text.substring(start,end),part.getText())||!Objects.equals(part.getAnchor(),original.getLocation()))return;
        List<JsonNode> accepted=new ArrayList<>();for(JsonNode raw:acceptedParts)if(Objects.equals(raw.path("blockId").asText(),part.getBlockId())&&raw.path("startOffset").isIntegralNumber()&&raw.path("endOffset").isIntegralNumber()&&raw.path("startOffset").asInt()==start&&raw.path("endOffset").asInt()==end&&Objects.equals(raw.path("text").asText(),part.getText()))accepted.add(raw);
        binding.put("reason","accepted_part_not_unique");if(accepted.size()!=1)return;
        JsonNode position=original.getSourcePosition(),supplied=accepted.get(0).get("sourcePosition");binding.put("reason","source_position_differs");
        if(position!=null&&(!position.equals(supplied)||!source.getSourceSha256().equals(position.path("sourceSha256").asText()))||position==null&&supplied!=null&&!supplied.isNull())return;
        copyRuleContext(read,original,target,context,binding,start,end);
    }
    /** Same captured-document adapter for the existing noncanonical rule path, with no canonical pointer. */
    public static List<VettingRuleEngine.Source> ordinaryRuleSources(String projectId,List<SourceDocument> capturedSources) {
        List<VettingRuleEngine.Source> out=new ArrayList<>();if(capturedSources==null)return out;
        for(SourceDocument source:capturedSources)if(source!=null&&Objects.equals(projectId,source.getProjectId())&&"tender".equals(VettingCorpus.sourceRole(source))){
            RuleSourceRead read=new RuleSourceRead(source);List<com.consense.document.DocumentBlock> originals=read.blocks(),blocks=new ArrayList<>();Map<String,Long> counts=originals.stream().filter(b->b.getId()!=null).collect(Collectors.groupingBy(com.consense.document.DocumentBlock::getId,Collectors.counting()));
            for(com.consense.document.DocumentBlock original:originals){com.consense.document.DocumentBlock block=JsonUtils.read(JsonUtils.write(original),com.consense.document.DocumentBlock.class);ObjectNode context=JsonUtils.mapper().createObjectNode(),binding=context.putObject(VettingPackageIdentity.CONTEXT);block.setReadingContext(context);binding.put("status","unknown");binding.put("reason","ordinary_source_binding_unverified");String text=original.getEffectiveText();
                if("PARSED".equals(source.getParseStatus())&&source.getSourceSha256()!=null&&Objects.equals(text,original.getText())&&counts.getOrDefault(original.getId(),0L)==1L&&(original.getSourcePosition()==null||source.getSourceSha256().equals(original.getSourcePosition().path("sourceSha256").asText())))copyRuleContext(read,original,block,context,binding,0,text==null?0:text.length());
                blocks.add(block);
            }
            out.add(new VettingRuleEngine.Source(String.valueOf(source.getId()),source.getFileKey(),source.getFileName(),read.sourceHash(),blocks,blocks.stream().map(com.consense.document.DocumentBlock::getText).collect(Collectors.joining("\n"))));
        }return out;
    }
    private static void copyRuleContext(RuleSourceRead read,com.consense.document.DocumentBlock original,com.consense.document.DocumentBlock target,ObjectNode context,ObjectNode binding,int start,int end) {
        SourceDocument source=read.source;
        JsonNode position=original.getSourcePosition();String text=original.getEffectiveText();
        JsonNode observed=original.getReadingContext();if(observed!=null){
            copyContextFields(observed,context,"sourceSha256","occurrenceId","story","role","ownerStatus","semanticStatus","adoptionStatus","containerId");
            if(observed.path("nativeDocxSection").isObject())copyContextFields(observed.path("nativeDocxSection"),context.putObject("nativeDocxSection"),"sectionId","ordinalZeroBased","inventoryPointer","membershipStatus","ownerStatus","applicabilityStatus","effectiveSectionState");
            if(observed.path("nativeDocxOutline").isObject())copyContextFields(observed.path("nativeDocxOutline"),context.putObject("nativeDocxOutline"),"ancestorOrdinals","containedHeadingOrdinals");
        }context.set(VettingPackageIdentity.CONTEXT,binding);
        target.setKind(original.getKind());target.setParagraphStyle(original.getParagraphStyle());target.setParagraphStyleName(original.getParagraphStyleName());if(position!=null)target.setSourcePosition(position.deepCopy());
        binding.put("status","source_bound");binding.put("reason","unique_current_document_block_and_accepted_range");binding.put("startOffset",start);binding.put("endOffset",end);binding.put("offsetUnit","utf16");binding.put("sourceHash",read.sourceHash());binding.put("originalSourceSha256",source.getSourceSha256());binding.put("originalBytesStatus","captured_identity_only");binding.put("role",VettingCorpus.sourceRole(source));binding.put("documentScope",source.getDocumentScope());binding.put("completeOriginalBlock",text!=null&&start==0&&end==text.length());
        binding.put("selectedStartsAtSourceLineBoundary",text!=null&&(start==0||text.charAt(start-1)=='\n'||text.charAt(start-1)=='\r'));binding.put("selectedEndsAtSourceLineBoundary",text!=null&&(end==text.length()||text.charAt(end)=='\n'||text.charAt(end)=='\r'));
        bindNativeStory(read,position,binding);
        bindScopeExclusion(read,original,binding);
    }
    private static void copyContextFields(JsonNode source,ObjectNode target,String... keys){for(String key:keys)if(source.has(key)){JsonNode value=source.path(key);boolean allowed=value.isValueNode();if(value.isArray()){allowed=true;for(JsonNode item:value)allowed&=item.isIntegralNumber();}if(allowed)target.set(key,value.deepCopy());}}
    /** Source outline edges may exclude examples/references; they never establish package-own identity. */
    private static void bindScopeExclusion(RuleSourceRead read,com.consense.document.DocumentBlock original,ObjectNode binding) {
        SourceDocument source=read.source;
        ObjectNode scope=binding.putObject("sourceScopeExclusion");scope.put("status","unknown");scope.put("reason","no_explicit_bound_scope_exclusion");
        JsonNode refs=original.getReadingContext()==null?null:original.getReadingContext().path("nativeDocxOutline").path("ancestorOrdinals"),position=original.getSourcePosition();if(refs==null||!refs.isArray()||refs.isEmpty()||position==null||!"docx_native".equals(position.path("kind").asText())||position.path("partName").asText().isEmpty()||!position.path("partSha256").asText().matches("[a-f0-9]{64}"))return;
        JsonNode coverage=read.coverage();try{read.blocks();}catch(RuntimeException invalid){return;}JsonNode headings=coverage.path("nativeDocxOutline").path("headings");if(!headings.isArray())return;
        Set<Integer> visited=new HashSet<>();Set<String> exclusions=new LinkedHashSet<>();List<ObjectNode> edges=new ArrayList<>();
        for(JsonNode ordinal:refs){
            if(!ordinal.isIntegralNumber()||!ordinal.canConvertToInt()||ordinal.asInt()<0||!visited.add(ordinal.asInt())){scope.put("reason","native_outline_scope_binding_unknown");return;}JsonNode heading=headings.get(ordinal.asInt());if(heading==null||heading.path("ordinalZeroBased").asInt(-1)!=ordinal.asInt()){scope.put("reason","native_outline_scope_binding_unknown");return;}
            JsonNode at=heading.path("sourcePosition"),observation=heading.path("textObservation");boolean member=false;for(JsonNode id:heading.path("retainedBlockIds"))if(original.getId().equals(id.asText()))member=true;
            if(!member||!source.getSourceSha256().equals(at.path("sourceSha256").asText())||!position.path("partName").equals(at.path("partName"))||!position.path("partSha256").equals(at.path("partSha256"))||!at.path("nodePath").asText().startsWith("/{")||!at.equals(observation.path("sourcePosition"))||!"observed_parser_paragraph_channels".equals(observation.path("status").asText())||!observation.path("text").isTextual()){scope.put("reason","native_outline_scope_binding_unknown");return;}
            String text=observation.path("text").asText();List<com.consense.document.DocumentBlock> matches=read.headingMatches(at,text);if(matches.size()!=1){scope.put("reason","native_outline_heading_occurrence_not_unique");return;}
            String excluded=VettingPackageIdentity.explicitScopeExclusion(text);if(excluded==null)continue;exclusions.add(excluded);ObjectNode edge=JsonUtils.mapper().createObjectNode();edge.put("ordinalZeroBased",ordinal.asInt());edge.put("sourceBlockId",matches.get(0).getId());edge.put("memberBlockId",original.getId());edge.set("sourcePosition",at.deepCopy());edge.put("sourceTextSha256",VettingCorpus.hash(text));edge.put("relation","native_outline_ancestor");edge.put("classification",excluded);edge.put("basis","exact_bound_source_heading_exclusion_label");edges.add(edge);
        }
        if(exclusions.isEmpty())return;scope.put("status","bound_native_outline_exclusion");scope.put("reason","explicit_current_source_heading_and_ancestor_membership");scope.put("classification",exclusions.size()==1?exclusions.iterator().next():"excluded_explicit_scope");for(ObjectNode edge:edges)scope.withArray("edges").add(edge);
    }
    private static void bindNativeStory(RuleSourceRead read,JsonNode position,ObjectNode binding) {
        SourceDocument source=read.source;
        binding.put("storyStatus","unknown");binding.put("physicalWordPageStatus","unknown");if(position==null||!"docx_native".equals(position.path("kind").asText())||position.path("partName").asText().isEmpty()||!position.path("partSha256").asText().matches("[a-f0-9]{64}"))return;
        JsonNode coverage=read.coverage();
        JsonNode inventory=coverage.path("nativeDocxTables");if(!source.getSourceSha256().equals(inventory.path("sourceSha256").asText()))return;
        List<JsonNode> roots=new ArrayList<>();for(JsonNode part:inventory.path("parts"))if(position.path("partName").equals(part.path("partName"))&&position.path("partSha256").equals(part.path("partSha256")))for(JsonNode root:part.path("loadedRoots")){
            String rootPath=root.path("nodePath").asText(),path=position.path("nodePath").asText();if(rootPath.startsWith("/{")&&Arrays.asList("body","header","footer","footnote","endnote").contains(root.path("kind").asText())&&(path.equals(rootPath)||path.startsWith(rootPath+"/")))roots.add(root);
        }
        if(roots.size()!=1)return;JsonNode root=roots.get(0);binding.put("storyStatus","observed_loaded_native_root");binding.put("storyKind",root.path("kind").asText());binding.set("storyRoot",root.deepCopy());
        JsonNode sections=coverage.path("nativeDocxSections");if(!source.getSourceSha256().equals(sections.path("sourcePosition").path("sourceSha256").asText()))return;
        for(JsonNode section:sections.path("sections"))for(JsonNode relation:section.path("headerFooterBindings")){
            JsonNode target=relation.path("targetPart");if(position.path("partName").equals(target.path("partName"))&&position.path("partSha256").equals(target.path("partSha256"))&&root.path("nodePath").equals(target.path("rootNodePath"))){ObjectNode linked=binding.withArray("sectionRelations").addObject();linked.put("sectionId",section.path("sectionId").asText());linked.set("binding",relation.deepCopy());}
        }
    }
    /** Rebind a literal rule quote to its accepted part; ambiguous locations do not receive a pointer. */
    public void bindRuleEvidence(Session session,List<com.consense.web.dto.VettingDtos.FindingEvidence> quotes){
        current(session);bindRuleEvidenceUnchecked(session,quotes);
    }
    private static void bindRuleEvidenceUnchecked(Session session,List<com.consense.web.dto.VettingDtos.FindingEvidence> quotes){
        for(com.consense.web.dto.VettingDtos.FindingEvidence e:quotes){
            String normalizedQuote=VettingCorpus.normalize(e.getQuote());
            List<Chunk> matches=session.chunks.stream().filter(c->c.getDocumentId().equals(e.getDocumentId())&&Objects.equals(c.getCanonicalEvidence().getJavaParsedSourceHash(),e.getSourceHash())&&c.getParts().stream()
                    .anyMatch(p->(Objects.equals(p.getAnchor(),e.getAnchor())||Objects.equals(c.getClauseId(),e.getAnchor()))
                            &&!normalizedQuote.isEmpty()&&VettingCorpus.normalize(p.getText()).contains(normalizedQuote))).collect(Collectors.toList());
            if(matches.size()==1){EvidencePointer p=matches.get(0).getCanonicalEvidence();e.setCanonicalEvidence(JsonUtils.read(JsonUtils.write(p),EvidencePointer.class));e.setSourceHash(p.getJavaParsedSourceHash());e.setOriginalSourceSha256(p.getOriginalSourceSha256());}
            else {e.setLocated(false);e.setCanonicalEvidence(null);}
        }
    }
    /** UTF16 union of accepted block ranges, never whole original-document coverage. */
    public static int observedChars(List<Chunk> selected){
        Map<String,List<int[]>> groups=new LinkedHashMap<>();for(Chunk c:selected){require(canonicalViewStatus(c)==null,"canonical coverage view differs");
            for(Part p:c.getParts())groups.computeIfAbsent(c.getDocumentId()+"|"+c.getSourceHash()+"|"+p.getBlockId(),key->new ArrayList<>()).add(new int[]{p.getStartOffset(),p.getEndOffset()});
        }long total=0;for(List<int[]> ranges:groups.values()){
            ranges.sort(Comparator.comparingInt(a->a[0]));int start=-1,end=-1;for(int[] r:ranges){if(start<0){start=r[0];end=r[1];}
                else if(r[0]<=end)end=Math.max(end,r[1]);else{total+=end-start;start=r[0];end=r[1];}}if(start>=0)total+=end-start;
        }require(total<=Integer.MAX_VALUE,"canonical coverage overflow");return (int)total;
    }
    /** The new context is a separate observed transport envelope, never a ranked seed. */
    private static Map<String,JsonNode> canonicalParents(Session session){Map<String,JsonNode> parents=new LinkedHashMap<>();session.byId.forEach((id,view)->parents.put(id,view.getCanonicalPayload()));return parents;}
    private static boolean completeTransport(JsonNode unit){return Arrays.asList("complete_observed_unit","complete_observed_hierarchy_context","complete_observed_attachment_context","complete_observed_context_union").contains(unit.path("status").asText());}
    /** Same public hierarchy/attachment union as transportUnit, without reconstructing source relations. */
    private static VettingRetrievalSourceUnits.Unit semanticSourceUnit(Map<String,Chunk> byId,Map<String,Integer> sourceOrder,Chunk chunk){
        JsonNode unit=chunk.getCanonicalPayload().path("semanticUnit"),hierarchy=unit.get("hierarchyContext"),attachment=unit.get("attachmentContext");
        List<JsonNode> selected=new ArrayList<>();if(attachment!=null&&attachment.isObject()){
            if(hierarchy!=null&&hierarchy.isObject())selected.add(hierarchy);selected.add(attachment);
        }else selected.add(hierarchy!=null&&hierarchy.isObject()&&completeTransport(hierarchy)?hierarchy:unit);
        Set<String> required=new LinkedHashSet<>();String reason=null;
        for(JsonNode context:selected){if(!completeTransport(context)){reason=context.path("reason").asText("public_source_context_unknown");break;}
            List<String> ids=VettingRetrievalSourceUnits.textIds(context.path("requiredMemberIds"));if(chunk.getCanonicalPayload().has("semanticUnitRef")){require(ids.contains(chunk.getId()),"semantic dictionary closure omitted its origin");for(String id:ids){Chunk member=byId.get(id);if(member!=null)require(Objects.equals(member.getDocumentId(),chunk.getDocumentId())&&Objects.equals(member.getOriginalSourceSha256(),chunk.getOriginalSourceSha256())&&Objects.equals(member.getSourceHash(),chunk.getSourceHash())&&Objects.equals(member.getRole(),chunk.getRole()),"semantic dictionary closure crosses source scope");}if(ids.stream().anyMatch(id->!byId.containsKey(id))){reason="semantic_closure_member_unavailable";break;}}for(JsonNode member:context.path("members")){
                Chunk canonical=byId.get(member.path("id").asText());require(canonical!=null,"semantic closure member outside scanned scope");
                ObjectNode original=JsonUtils.mapper().createObjectNode();
                canonical.getCanonicalPayload().fields().forEachRemaining(field->{if(!"semanticUnit".equals(field.getKey()))original.set(field.getKey(),field.getValue());});
                require(original.equals(member),"semantic closure member differs from scanned origin");}
            required.addAll(ids);
        }
        List<String> ordered=new ArrayList<>();if(reason==null){for(String id:required)if(sourceOrder.containsKey(id))ordered.add(id);ordered.sort(Comparator.comparingInt(sourceOrder::get));}
        ObjectNode trace=JsonUtils.mapper().createObjectNode();
        ((ObjectNode)unit).fields().forEachRemaining(field->{
            if("members".equals(field.getKey()))return;
            JsonNode value=field.getValue();
            if(("hierarchyContext".equals(field.getKey())||"attachmentContext".equals(field.getKey()))&&value.isObject()){
                ObjectNode context=JsonUtils.mapper().createObjectNode();value.fields().forEachRemaining(inner->{if(!"members".equals(inner.getKey()))context.set(inner.getKey(),inner.getValue().deepCopy());});trace.set(field.getKey(),context);
            }else trace.set(field.getKey(),value.deepCopy());
        });trace.put("selectionPolicy",OerCanonicalRetrievalClient.SEMANTIC_READ);
        return new VettingRetrievalSourceUnits.Unit(chunk.getId(),reason==null?"complete_observed_unit":"unknown",reason,ordered,JsonUtils.readMap(JsonUtils.write(trace)));
    }
    private static JsonNode transportUnit(JsonNode unit,Map<String,JsonNode> parents){
        JsonNode attachment=unit.path("attachmentContext");
        JsonNode context=unit.path("hierarchyContext");
        // Preserve the v2 consumer behavior when no v3 attachment is declared.
        if(!attachment.isObject())return context.isObject()&&"complete_observed_hierarchy_context".equals(context.path("status").asText())?context:unit;
        if(context.isObject()&&!completeTransport(context))return context;
        if(!completeTransport(attachment))return attachment;
        if(!context.isObject())return attachment;
        Set<String> needed=new LinkedHashSet<>();Map<String,JsonNode> supplied=new LinkedHashMap<>();
        for(JsonNode envelope:Arrays.asList(context,attachment))for(JsonNode member:envelope.path("members")){
            String id=member.path("id").asText();require(parents.containsKey(id)&&parents.get(id).equals(member),"transport context member differs from source");
            require(!supplied.containsKey(id)||supplied.get(id).equals(member),"transport contexts disagree");supplied.put(id,member);needed.add(id);
        }
        // This envelope describes the union. Keep each original edge declaration
        // in its independently validated context on the unchanged source unit.
        String unionPolicy=OerCanonicalAttachmentContext.unionPolicy(attachment.path("policy").asText());
        ObjectNode union=JsonUtils.mapper().createObjectNode();union.put("policy",unionPolicy);union.put("status","complete_observed_context_union");union.putNull("reason");
        union.set("sourceIdentity",attachment.path("sourceIdentity"));union.set("seedObservedUnitId",unit.path("unitId"));
        union.set("hierarchyContextId",context.path("contextId"));union.set("attachmentContextId",attachment.path("contextId"));
        union.put("memberOrder","canonical_source_input");union.put("derivedMembersCanBecomeOrigins",false);union.putNull("memberScores");
        union.put("qualifiersComplete","unknown");union.put("semanticScopeVerified",false);union.put("legalClosureVerified",false);
        com.fasterxml.jackson.databind.node.ArrayNode ids=union.putArray("requiredMemberIds"),members=union.putArray("members"),hashes=union.putArray("memberPayloadSha256");
        parents.forEach((id,member)->{if(needed.contains(id)){ids.add(id);members.add(member.deepCopy());hashes.addObject().put("id",id).put("sha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(member))));}});
        require(!members.isEmpty(),"complete transport union has no source members");
        SortedSet<String> blocks=new TreeSet<>();for(JsonNode member:members)for(JsonNode part:member.path("parts"))blocks.add(part.path("blockId").asText());
        com.fasterxml.jackson.databind.node.ArrayNode blockIds=union.putArray("blockIds"),ranges=union.putArray("observedRanges");
        for(String block:blocks){blockIds.add(block);ranges.add(OerCanonicalSourceUnits.blockRange(parents,OerCanonicalSourceUnits.scopeIdentity(members.get(0)),block));}
        ObjectNode address=JsonUtils.mapper().createObjectNode();address.put("policy",unionPolicy);address.set("observedUnitId",unit.path("unitId"));address.set("hierarchyContextId",context.path("contextId"));address.set("attachmentContextId",attachment.path("contextId"));address.set("memberPayloadSha256",hashes);
        union.put("contextId",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(address))));return union;
    }
    private static VectorStore.SearchHit businessHit(Session session,String id,JsonNode unit,double score,String kind){
            Chunk seed=session.byId.get(id);require(seed!=null,"source-unit seed outside session");List<Chunk> members=new ArrayList<>();
            JsonNode transport=transportUnit(unit,canonicalParents(session));
            if(completeTransport(transport))for(JsonNode raw:transport.path("members")){
                Chunk member=session.byId.get(raw.path("id").asText());require(member!=null&&raw.equals(member.getCanonicalPayload()),"context member differs from selected canonical payload");members.add(member);
            }
            else members.add(seed);
            members.sort(Comparator.comparingInt(c->session.chunks.indexOf(c)));
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("projectId",session.projectId);payload.put("documentId",seed.getDocumentId());
            payload.put("sourceHash",seed.getCanonicalEvidence().getJavaParsedSourceHash());payload.put("sourceRole",seed.getRole());
            SortedSet<Integer> pages=new TreeSet<>();for(Chunk member:members)pages.addAll(member.getCanonicalEvidence().getPhysicalPages());
            payload.put("fileLabel",seed.getFileKey()+" · "+seed.getFileName());payload.put("pageNo",pages.stream().map(p->"P"+p).collect(Collectors.joining(", ")));
            payload.put("anchor",seed.getAnchor());payload.put("canonicalPrimaryAnchorScope","ranked_seed_only");
            payload.put("content",members.stream().map(Chunk::getContent).collect(Collectors.joining("\n")));
            payload.put("canonicalEvidence",members.stream().map(c->JsonUtils.read(JsonUtils.write(c.getCanonicalEvidence()),EvidencePointer.class)).collect(Collectors.toList()));
            payload.put("canonicalMembers",members.stream().map(c->c.getCanonicalPayload().deepCopy()).collect(Collectors.toList()));
            payload.put("canonicalSourceUnit",unit.deepCopy());payload.put("applicabilityContextComplete",null);
            payload.put("selectionKind",kind);payload.put("scoreKind","context_only".equals(kind)?"unranked_context":"actual_reranked_seed");
            return new VectorStore.SearchHit(seed.getId(),score,payload);
    }

    private static Chunk view(JsonNode raw,EvidencePointer pointer,SourceDocument source) {
        boolean nativeDocx=NativeCanonicalContract.SCHEMA.equals(pointer.getProjectionSchema());
        boolean normal=NormalObservedContract.isNormal(raw);
        Chunk c=new Chunk();c.setId(raw.path("id").asText());c.setContent(raw.path("content").asText());c.setDocumentId(raw.path("documentId").asText());
        c.setRole(raw.path("role").asText());c.setFileKey(source.getFileKey()==null?"OTHER":source.getFileKey());c.setFileName(pointer.getSourceFilename().asText());
        c.setSourceHash(raw.path("sourceHash").asText());c.setOriginalSourceSha256(raw.path("originalSourceSha256").asText());c.setSourceSha256(raw.path("sourceSha256").asText());
        c.setSourcePurpose(raw.path("sourcePurpose").asText());c.setSourceClass(raw.path("sourceClass").asText());
        c.setEligibleModules(JsonUtils.readList(JsonUtils.write(raw.path("eligibleModules")),String.class));
        c.setClauseId(raw.path("clauseId").isTextual()?raw.path("clauseId").asText():null);
        c.setClauseHeadingLocation(raw.path("clauseHeadingLocation").isTextual()?raw.path("clauseHeadingLocation").asText():null);
        c.setMetadataVersion(raw.path("metadataVersion").asText());c.setSegmentationVersion(raw.path("segmentationVersion").asText());
        c.setNativeTableMetadataVersion(raw.path("nativeTableMetadataVersion").asText());c.setSourceQualityMetadataVersion(raw.path("sourceQualityMetadataVersion").asText());
        c.setSourceQualityHash(raw.path("sourceQualityHash").asText());
        c.setSourceQuality(unknownQuality(normal));
        List<Part> parts=new ArrayList<>();for(JsonNode p:raw.path("parts")) {
            Part part=new Part();part.setText(p.path("text").asText());part.setAnchor(p.path("anchor").asText());part.setBlockId(p.path("blockId").asText());
            part.setStartOffset(p.path("startOffset").asInt());part.setEndOffset(p.path("endOffset").asInt());
            part.setPageNo(nativeDocx?null:normal?normalPage(p):"P"+p.path("physicalPageNo").asInt());
            part.setBbox(nativeDocx||normal?null:xywh(p.path("bbox")));part.setExtractionSource(normal?"normal_observed_candidate":"source_reviewed_canonical");
            if(normal){if(p.has("sourcePosition"))part.setSourcePosition(p.get("sourcePosition").deepCopy());if(p.has("wordObservation"))part.setWordObservation(p.get("wordObservation").deepCopy());}
            parts.add(part);
        }
        c.setParts(parts);c.setAnchor(raw.path("anchor").isTextual()?raw.path("anchor").asText():parts.get(0).getAnchor());
        List<Integer> pages=pointer.getPhysicalPages();c.setPageNo(nativeDocx||normal&&pages.isEmpty()?null:pages.size()==1?"P"+pages.get(0):"P"+pages.get(0)+"–P"+pages.get(pages.size()-1));
        c.setBbox(parts.size()==1?parts.get(0).getBbox():null);c.setCanonicalPayload(raw.deepCopy());c.setCanonicalEvidence(pointer);return c;
    }
    private static Chunk semanticView(Session session,JsonNode origin,JsonNode unit,boolean dictionary){
        CanonicalSourceBinding binding=null;for(CanonicalSourceBinding candidate:session.semanticScope.bindings())if(String.valueOf(candidate.getDocumentId()).equals(origin.path("documentId").asText()))binding=candidate;
        require(binding!=null,"semantic origin outside selected sources");SourceDocument source=session.semanticDocuments.get(binding.getDocumentId());
        ObjectNode raw=origin.deepCopy();raw.set("semanticUnit",dictionary?unit:unit.deepCopy());Chunk chunk=new Chunk();chunk.setId(raw.path("id").asText());chunk.setDocumentId(raw.path("documentId").asText());chunk.setContent(raw.path("text").asText());chunk.setRole(raw.path("role").asText());
        chunk.setFileKey(source.getFileKey());chunk.setFileName(source.getFileName());chunk.setSourceHash(binding.getCanonicalSourceHash());chunk.setOriginalSourceSha256(binding.getOriginalSourceSha256());chunk.setSourceSha256(binding.getOriginalSourceSha256());
        chunk.setMetadataVersion(raw.path("metadataVersion").asText());chunk.setSegmentationVersion(raw.path("segmentationVersion").asText());chunk.setNativeTableMetadataVersion(raw.path("nativeTableMetadataVersion").asText());chunk.setSourceQualityMetadataVersion(raw.path("sourceQualityMetadataVersion").asText());chunk.setSourceQualityHash(raw.path("sourceQualityHash").asText());
        chunk.setClauseId(textOrNull(raw,"clauseId"));chunk.setClauseHeadingLocation(session.semanticHeadingLocations.get(chunk.getId()));chunk.setSourceQuality(unknownQuality(NormalObservedContract.PRODUCER.equals(raw.path("producerKind").asText())));chunk.setEligibleModules(JsonUtils.readList(JsonUtils.write(raw.path("eligibleModules")),String.class));
        List<String> anchors=session.semanticAnchors.get(chunk.getId());require(anchors!=null&&anchors.size()==raw.path("parts").size(),"semantic origin differs from cold verified parts");
        List<Part> parts=new ArrayList<>();for(JsonNode part:raw.path("parts")){Part value=new Part();value.setBlockId(part.path("blockId").asText());value.setText(part.path("text").asText());value.setAnchor(anchors.get(parts.size()));value.setStartOffset(part.path("startOffset").asInt());value.setEndOffset(part.path("endOffset").asInt());value.setExtractionSource("semantic_source_unit");parts.add(value);}chunk.setParts(parts);chunk.setAnchor(parts.isEmpty()?chunk.getClauseId():parts.get(0).getAnchor());
        EvidencePointer pointer=new EvidencePointer();pointer.setProjectId(session.projectId);pointer.setDocumentId(binding.getDocumentId());pointer.setModule(session.module);pointer.setOriginalSourceSha256(binding.getOriginalSourceSha256());pointer.setJavaParsedSourceHash(binding.getJavaParsedSourceHash());
        pointer.setSourceRevisionSha256(binding.getSourceRevisionSha256());pointer.setProjectionSha256(binding.getProjectionSha256());pointer.setProjectionRevisionSha256(binding.getProjectionRevisionSha256());pointer.setCanonicalSourceHash(binding.getCanonicalSourceHash());pointer.setCanonicalChunkId(chunk.getId());pointer.setUnitId(raw.path("unitId").asText());
        pointer.setProjectionSchema(OerCanonicalRetrievalClient.SEMANTIC_READ);pointer.setProducerKind(raw.path("producerKind").asText());pointer.setAddressKind("semantic_source_unit");pointer.setBboxConvention(null);pointer.setOriginalGeometryStatus("unknown");pointer.setVisualReturnStatus("not_requested");pointer.setPhysicalPages(Collections.emptyList());
        pointer.setSourceFilename(JsonUtils.mapper().getNodeFactory().textNode(source.getFileName()));pointer.setSourceOrder(raw.path("sourceOrder").deepCopy());pointer.setParts(raw.path("parts").deepCopy());pointer.setEligibleModules(raw.path("eligibleModules").deepCopy());pointer.setUnknowns(raw.path("unknowns").deepCopy());pointer.setSourceQuality(raw.path("sourceQuality").deepCopy());
        chunk.setCanonicalEvidence(pointer);chunk.setCanonicalPayload(raw);return chunk;
    }
    private static List<Double> xywh(JsonNode b){if(!b.isArray()||b.size()!=4)return null;return Arrays.asList(b.get(0).asDouble(),b.get(1).asDouble(),b.get(2).asDouble()-b.get(0).asDouble(),b.get(3).asDouble()-b.get(1).asDouble());}
    private static String normalPage(JsonNode part){return part.path("physicalPageNo").isIntegralNumber()&&part.path("physicalPageNo").asInt()>0?"P"+part.path("physicalPageNo").asInt():null;}
    private static Chunk copy(Chunk c){require(c!=null,"ranked canonical chunk missing");return JsonUtils.read(JsonUtils.write(c),Chunk.class);}

    /** Checks an opaque canonical payload against the read-only model view; no legacy-version substitution. */
    static String canonicalViewStatus(Chunk c) {
        JsonNode raw=c.getCanonicalPayload();EvidencePointer p=c.getCanonicalEvidence();if(raw==null||p==null)return "canonical_view_missing";
        if(OerCanonicalRetrievalClient.SEMANTIC_READ.equals(raw.path("semanticProjectionVersion").asText())){
            if(!OerCanonicalRetrievalClient.SEMANTIC_READ.equals(p.getProjectionSchema())||!"semantic_source_unit".equals(p.getAddressKind())
                    ||!Objects.equals(c.getId(),raw.path("id").asText())||!Objects.equals(c.getId(),p.getCanonicalChunkId())||!Objects.equals(c.getContent(),raw.path("text").asText())
                    ||!Objects.equals(c.getDocumentId(),raw.path("documentId").asText())||!Objects.equals(c.getDocumentId(),String.valueOf(p.getDocumentId()))||!Objects.equals(c.getRole(),raw.path("role").asText())
                    ||!Objects.equals(c.getSourceHash(),raw.path("sourceHash").asText())||!Objects.equals(c.getSourceHash(),p.getCanonicalSourceHash())||!Objects.equals(c.getOriginalSourceSha256(),p.getOriginalSourceSha256())
                    ||!Objects.equals(p.getProjectionSha256(),raw.path("evidenceRef").path("projectionSha256").asText())||!Objects.equals(p.getParts(),raw.path("parts"))
                    ||c.getParts()==null||c.getParts().size()!=raw.path("parts").size())return "semantic_view_identity_differs";
            for(int i=0;i<c.getParts().size();i++){Part part=c.getParts().get(i);JsonNode original=raw.path("parts").get(i);if(!Objects.equals(part.getBlockId(),original.path("blockId").asText())||!Objects.equals(part.getText(),original.path("text").asText())||part.getStartOffset()!=original.path("startOffset").asInt()||part.getEndOffset()!=original.path("endOffset").asInt()||part.getEndOffset()-part.getStartOffset()!=part.getText().length())return "semantic_view_parts_differ";}
            return null;
        }
        boolean nativeDocx=NativeCanonicalContract.SCHEMA.equals(p.getProjectionSchema());
        boolean normal=NormalObservedContract.isNormal(raw)||NormalObservedContract.SCHEMA.equals(p.getProjectionSchema())||NormalObservedContract.PRODUCER.equals(p.getProducerKind());
        boolean family=NativePdfSourceFamilyContract.isFamily(raw)||NativePdfSourceFamilyContract.SCHEMA.equals(p.getProjectionSchema())||NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind());
        boolean nativePdf=!family&&(NativePdfCanonicalContract.isNativePdf(raw)||NativePdfCanonicalContract.SCHEMA.equals(p.getProjectionSchema())||NativePdfCanonicalContract.PRODUCER.equals(p.getProducerKind()));
        if(normal){
            try{NormalObservedContract.validateChunk(raw);}catch(RuntimeException rejected){return "canonical_view_normal_contract_differs";}
            List<Integer> pages=p.getPhysicalPages();String label=pages==null||pages.isEmpty()?null:pages.size()==1?"P"+pages.get(0):"P"+pages.get(0)+"–P"+pages.get(pages.size()-1);
            if(!NormalObservedContract.SCHEMA.equals(p.getProjectionSchema())||!NormalObservedContract.PRODUCER.equals(p.getProducerKind())
                    ||!NormalObservedContract.VERSION.equals(raw.path("projectionVersion").asText())||p.getStorageShape()!=null||p.getBboxConvention()!=null
                    ||!"unknown".equals(p.getOriginalGeometryStatus())||!"unknown".equals(p.getVisualReturnStatus())
                    ||p.getNativePdfEvidence()!=null||p.getNativePdfSourceFamilyEvidence()!=null||p.getSourceFamilyOriginRef()!=null
                    ||!Objects.equals(label,c.getPageNo())||c.getBbox()!=null)return "canonical_view_normal_contract_differs";
        }else if(family){
            try{NativePdfSourceFamilyContract.validateChunk(raw,c.getOriginalSourceSha256());NativePdfSourceFamilyContract.validateOriginDisplay(raw,p.getNativePdfSourceFamilyEvidence());}catch(RuntimeException rejected){return "canonical_view_native_pdf_family_contract_differs";}
            JsonNode d=p.getNativePdfSourceFamilyEvidence();JsonNode physical=raw.path("physicalPages");String pageView=physical.size()==1?"P"+physical.get(0).asInt():"P"+physical.get(0).asInt()+"–P"+physical.get(physical.size()-1).asInt();
            if(!NativePdfSourceFamilyContract.SCHEMA.equals(p.getProjectionSchema())||!NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())
                    ||!NativePdfSourceFamilyContract.STORAGE.equals(p.getStorageShape())||!NativePdfSourceFamilyContract.REVISION.equals(p.getSourceRevisionKind())
                    ||!NativePdfCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())||!NativePdfSourceFamilyContract.VISUAL.equals(p.getVisualReturnStatus())
                    ||!NativePdfCanonicalContract.BBOX.equals(p.getBboxConvention())||!Objects.equals(raw.path("sourceFamilyOriginRef"),p.getSourceFamilyOriginRef())
                    ||!Objects.equals(p.getFamilyCompositionSha256(),d.path("compositionSha256").asText())||!Objects.equals(p.getFamilyComponentSetSha256(),d.path("componentSetSha256").asText())
                    ||!raw.path("sourceQuality").equals(d.path("sourceQuality"))||!NativePdfSourceFamilyContract.unknowns(d).equals(p.getUnknowns())||p.getNativePdfEvidence()!=null
                    ||!Objects.equals(pageView,c.getPageNo())||!Objects.equals(c.getBbox(),raw.path("parts").size()==1?xywh(raw.path("parts").get(0).path("bbox")):null))return "canonical_view_native_pdf_family_contract_differs";
        }else if(nativePdf){
            try{NativePdfCanonicalContract.validateChunk(raw,c.getOriginalSourceSha256());NativePdfCanonicalContract.validateDisplayDeclarations(p.getNativePdfEvidence());}catch(RuntimeException rejected){return "canonical_view_native_pdf_contract_differs";}
            JsonNode declarations=p.getNativePdfEvidence();
            JsonNode physical=raw.path("physicalPages");String pageView=physical.size()==1?"P"+physical.get(0).asInt():"P"+physical.get(0).asInt()+"–P"+physical.get(physical.size()-1).asInt();
            if(!NativePdfCanonicalContract.SCHEMA.equals(p.getProjectionSchema())||!NativePdfCanonicalContract.PRODUCER.equals(p.getProducerKind())
                    ||!NativePdfCanonicalContract.STORAGE.equals(p.getStorageShape())||!NativePdfCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())
                    ||!NativePdfCanonicalContract.VISUAL.equals(p.getVisualReturnStatus())||!NativePdfCanonicalContract.BBOX.equals(p.getBboxConvention())
                    ||!Objects.equals(pageView,c.getPageNo())||!Objects.equals(c.getBbox(),raw.path("parts").size()==1?xywh(raw.path("parts").get(0).path("bbox")):null)
                    ||declarations==null||!declarations.isObject()||!declarations.path("semanticScopeVerified").isBoolean()||declarations.path("semanticScopeVerified").asBoolean()
                    ||!declarations.path("humanConfirmed").isBoolean()||declarations.path("humanConfirmed").asBoolean()
                    ||!"unknown".equals(declarations.path("wholeOriginalSourceCoverage").asText())
                    ||!NativePdfCanonicalContract.VISUAL.equals(declarations.path("visualReturnStatus").asText())
                    ||!declarations.path("limitations").isArray()||declarations.path("limitations").isEmpty())return "canonical_view_native_pdf_contract_differs";
        }else if(nativeDocx){
            try{NativeCanonicalContract.validateChunk(raw,c.getOriginalSourceSha256());}catch(RuntimeException rejected){return "canonical_view_native_contract_differs";}
            if(!NativeCanonicalContract.PRODUCER.equals(p.getProducerKind())||!NativeCanonicalContract.STORAGE.equals(p.getStorageShape())
                    ||!NativeCanonicalContract.GEOMETRY.equals(p.getOriginalGeometryStatus())||!NativeCanonicalContract.VISUAL.equals(p.getVisualReturnStatus())
                    ||p.getBboxConvention()!=null||c.getPageNo()!=null||c.getBbox()!=null)return "canonical_view_native_contract_differs";
        }else if(!"consense.canonical-projection.v1".equals(p.getProjectionSchema())||raw.has("storageShape"))return "canonical_view_identity_differs";
        if(!Objects.equals(c.getId(),raw.path("id").asText())||!Objects.equals(c.getId(),p.getCanonicalChunkId())
                ||!Objects.equals(c.getSourceHash(),raw.path("sourceHash").asText())||!Objects.equals(c.getSourceHash(),p.getCanonicalSourceHash())
                ||!Objects.equals(c.getDocumentId(),raw.path("documentId").asText())||!Objects.equals(c.getDocumentId(),String.valueOf(p.getDocumentId()))||!Objects.equals(c.getContent(),raw.path("content").asText())
                ||!Objects.equals(c.getRole(),raw.path("role").asText())||!Objects.equals(c.getSourceQualityHash(),raw.path("sourceQualityHash").asText())
                ||!Objects.equals(c.getMetadataVersion(),raw.path("metadataVersion").asText())||!Objects.equals(c.getSegmentationVersion(),raw.path("segmentationVersion").asText())
                ||!Objects.equals(c.getNativeTableMetadataVersion(),raw.path("nativeTableMetadataVersion").asText())||!Objects.equals(c.getSourceQualityMetadataVersion(),raw.path("sourceQualityMetadataVersion").asText())
                ||!Objects.equals(c.getSourceSha256(),raw.path("sourceSha256").asText())||!Objects.equals(c.getOriginalSourceSha256(),raw.path("originalSourceSha256").asText())
                ||!Objects.equals(c.getOriginalSourceSha256(),p.getOriginalSourceSha256())||!Objects.equals(p.getSourceRevisionSha256(),raw.path("sourceRevisionSha256").asText())
                ||!Objects.equals(p.getProjectionRevisionSha256(),raw.path("projectionRevisionSha256").asText())||!Objects.equals(p.getUnitId(),raw.path("unitId").asText())
                ||!Objects.equals(c.getSourcePurpose(),raw.path("sourcePurpose").asText())||!Objects.equals(c.getSourceClass(),raw.path("sourceClass").asText())
                ||!Objects.equals(c.getClauseId(),textOrNull(raw,"clauseId"))||!Objects.equals(c.getClauseHeadingLocation(),textOrNull(raw,"clauseHeadingLocation"))
                ||!Objects.equals(c.getSourceQuality(),unknownQuality(normal))||!Objects.equals(c.getFileName(),p.getSourceFilename().asText())
                ||!(normal?"normal_observed_unit":"canonical_adoption_unit").equals(p.getAddressKind())||(!normal&&!nativeDocx&&!nativePdf&&!family&&!"normalized_xyxy_original_page".equals(p.getBboxConvention()))
                ||!Objects.equals(raw.path("eligibleModules"),JsonUtils.mapper().valueToTree(c.getEligibleModules()))
                ||!Objects.equals(raw.path("physicalPages"),JsonUtils.mapper().valueToTree(p.getPhysicalPages()))
                ||!raw.path("eligibleModules").equals(p.getEligibleModules())||!raw.path("parts").equals(p.getParts())
                ||!raw.path("sourceSpans").equals(p.getSourceSpans())||!Objects.equals(raw.get("tableGroup"),p.getTableGroup())
                ||!Objects.equals(raw.get("nativeTableGroupsRef"),p.getNativeTableGroupsRef())||!Objects.equals(raw.get("nativeTableSummary"),p.getNativeTableSummary())
                ||!Objects.equals(textOrNull(raw,"sourceEditionId"),p.getSourceEditionId())||!Objects.equals(textOrNull(raw,"sourceUnitId"),p.getSourceUnitId())
                ||!Objects.equals(textOrNull(raw,"slUnitScopeManifestSha256"),p.getSlUnitScopeManifestSha256())
                ||!raw.path("assetRefs").equals(p.getAssetRefs())||!raw.path("relations").equals(p.getRelations())
                ||(normal?p.getAdoption()!=null&&!p.getAdoption().isMissingNode()&&!p.getAdoption().isNull():!raw.path("adoption").equals(p.getAdoption()))||!raw.path("sourceQuality").equals(p.getSourceQuality())
                ||!raw.path("sourceOrder").equals(p.getSourceOrder())
                ||p.isHumanConfirmed()||!"unknown".equals(raw.path("sourceQuality").path("semanticQuality").asText()))return "canonical_view_identity_differs";
        if(c.getParts()==null||c.getParts().size()!=raw.path("parts").size())return "canonical_view_parts_differ";
        for(int i=0;i<c.getParts().size();i++){Part part=c.getParts().get(i);JsonNode original=raw.path("parts").get(i);
            if(!Objects.equals(part.getText(),original.path("text").asText())||!Objects.equals(part.getAnchor(),original.path("anchor").asText())
                    ||!Objects.equals(part.getBlockId(),original.path("blockId").asText())||part.getStartOffset()!=original.path("startOffset").asInt()
                    ||part.getEndOffset()!=original.path("endOffset").asInt()||part.getEndOffset()-part.getStartOffset()!=part.getText().length()
                    ||!Objects.equals(part.getBbox(),nativeDocx||normal?null:xywh(original.path("bbox")))||!Objects.equals(part.getPageNo(),nativeDocx?null:normal?normalPage(original):"P"+original.path("physicalPageNo").asInt())
                    ||!(normal?"normal_observed_candidate":"source_reviewed_canonical").equals(part.getExtractionSource())
                    ||normal&&(!Objects.equals(part.getSourcePosition(),original.get("sourcePosition"))||!Objects.equals(part.getWordObservation(),original.get("wordObservation"))))return "canonical_view_parts_differ";
        }return null;
    }
    private static String textOrNull(JsonNode raw,String field){return raw.path(field).isTextual()?raw.path(field).asText():null;}
    private static VettingSourceQuality.Info unknownQuality(boolean normal){VettingSourceQuality.Info info=new VettingSourceQuality.Info();
        info.getLimitations().add(normal?"normal_observations_are_unreviewed_source_quality_and_whole_source_coverage_unknown":"canonical_adoption_does_not_establish_whole_source_semantics_or_coverage");return info;}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Canonical business evidence rejected: "+why);}
}
