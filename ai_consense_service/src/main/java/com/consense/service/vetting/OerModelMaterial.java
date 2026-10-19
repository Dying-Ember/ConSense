package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.OerTemplateEvidence;
import com.consense.document.ParseCoverage;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.NativePdfSourceFamilyContract;
import com.consense.service.sources.NormalObservedContract;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Selected business observations only. Complete pointers, CAS and verifier proofs stay outside model material. */
public final class OerModelMaterial {
    public static final String VERSION="selected-canonical-model-material-v1";
    public static final String SOURCE_VERSION="source-and-projection-model-declaration-v1";
    public static final String DICTIONARY_VERSION="exact-source-projection-unit-model-dictionary-v1";
    public static final String LEGEND="\nModel material version="+VERSION+". sourceDeclarationRef addresses a request-local source+projection declaration emitted once, not an external source. "
            +"Only selected complete source-unit observations are projected, with reviewed and unreviewed authorities kept explicit. Original evidence pointers retain the full capture, CAS and software-verification tree outside this model input. "
            +"Selected unit unknowns stay literal; other-unit unknown locations are outside this material and their statuses/reasons are summarized in the source declaration. "
            +"Neither selection, source-reviewed relations nor model material establishes legal closure, applicability, whole-source coverage or human confirmation.\n";
    public static final String WIRE_LEGEND=VettingSourceMetadataWire.LEGEND.replace("All original source fields, whole tables, blanks, relationships and unknowns remain present", "All supplied model-material fields, whole tables, blanks, relationships and unknowns remain present");
    public static final String DICTIONARY_LEGEND="\nModel dictionary version="+DICTIONARY_VERSION+". materialDictionary entries carry modelUnitAddress; ordered hitMembership.memberMaterialRefs refer to those exact entries. "
            +"Sharing requires the same complete source pointer, canonical member payload and role; it never merges differing revisions, adoption declarations, text or roles. "
            +"Each hit retains its ranked seed and original closure membership/order. Dictionary members receive no independent relevance score.\n";
    private OerModelMaterial() { }

    /** One request, never a cached validation verdict. */
    public static final class Request {
        private final Map<String,JsonNode> sourceUnknowns=new LinkedHashMap<>(),sourceLimits=new LinkedHashMap<>();
        private final Set<String> materialIds=new HashSet<>();
        private final Map<String,SourceDocument> ordinarySources=new LinkedHashMap<>();
        private final Map<String,JsonNode> ordinaryGraphs=new HashMap<>();
        private final Map<String,NormalSourceCandidateGraph.NativeDocxTableIndex> ordinaryTables=new HashMap<>();
        private final Map<String,Boolean> ordinaryClauseScopes=new HashMap<>();
        private final Map<String,JsonNode> ordinaryGuidanceEvidence=new HashMap<>();
        private final Map<String,JsonNode> wordSourceScopes;
        private final boolean ordinaryContextProvided;
        public Request(){this(Collections.emptyList(),false,Collections.emptyMap());}
        public Request(List<SourceDocument> sources){this(sources,true,Collections.emptyMap());}
        public Request(List<SourceDocument> sources,Map<String,JsonNode> wordSourceScopes){this(sources==null?Collections.emptyList():sources,sources!=null,wordSourceScopes);}
        private Request(List<SourceDocument> sources,boolean provided,Map<String,JsonNode> wordSourceScopes){
            ordinaryContextProvided=provided;
            this.wordSourceScopes=new LinkedHashMap<>();wordSourceScopes.forEach((id,context)->this.wordSourceScopes.put(id,context.deepCopy()));
            for(SourceDocument source:sources)if(ordinarySources.putIfAbsent(String.valueOf(source.getId()),source)!=null)
                throw new IllegalArgumentException("Duplicate ordinary model source identity");
        }
        public ObjectNode projectOrdinary(VettingCorpus.Chunk chunk){
            SourceDocument source=ordinarySources.get(chunk.getDocumentId());
            boolean word=chunk.getParts()!=null&&chunk.getParts().stream().anyMatch(p->"docx".equals(p.getExtractionSource())
                    &&(p.getWordCells()!=null&&!p.getWordCells().isEmpty()||p.getSourcePosition()!=null&&p.getSourcePosition().path("nodePath").asText().matches(".*\\}tr\\[[1-9][0-9]*\\]$")));
            boolean pdf=chunk.getParts()!=null&&chunk.getParts().stream().anyMatch(p->p.getPageNo()!=null&&("native".equals(p.getExtractionSource())||p.getNativeTableCandidate()!=null||p.getPdfFormField()!=null));
            if(!word&&!pdf&&(chunk.getParts()==null||chunk.getParts().stream().noneMatch(p->"docx".equals(p.getExtractionSource()))))return null;
            if(source==null){
                if(!word&&!pdf||!ordinaryContextProvided)return null;
                ObjectNode missing=object();missing.put("status","unknown");missing.put("reason",pdf?"selected_native_pdf_source_missing_from_request_context":"selected_word_source_missing_from_request_context");return missing;
            }
            if(JsonUtils.isBlankText(source.getNormalCandidateJson())){
                if(!word&&!pdf)return null;
                ObjectNode missing=object();missing.put("status","unknown");missing.put("reason","bound_normal_source_graph_unavailable");return missing;
            }
            JsonNode graph,selected;String projection;
            if(pdf){graph=ordinaryGraphs.computeIfAbsent(chunk.getDocumentId(),id->com.consense.service.sources.NormalObservedPreparationService.currentGraph(source));if(!graph.has("nativePdfSourceGroups"))return null;selected=NormalSourceCandidateGraph.selectedPdfForModel(graph,chunk);projection="ordinary-native-pdf-source-groups-model-v1";}
            else{NormalSourceCandidateGraph.NativeDocxTableIndex tables=ordinaryTables.computeIfAbsent(chunk.getDocumentId(),id->new NormalSourceCandidateGraph.NativeDocxTableIndex(source));
                boolean clauses=ordinaryClauseScopes.computeIfAbsent(chunk.getDocumentId(),id->!JsonUtils.parse(source.getParseCoverageJson()).path("nativeDocxOutline").path("sourceClauseScopes").path("ranges").isEmpty());
                if(!word&&!tables.present()&&!clauses)return null;graph=ordinaryGraphs.computeIfAbsent(chunk.getDocumentId(),id->com.consense.service.sources.NormalObservedPreparationService.currentGraph(source));selected=NormalSourceCandidateGraph.selectedForModel(graph,chunk,tables);projection=clauses?"ordinary-word-source-scope-model-v1":tables.present()?NormalSourceCandidateGraph.NativeDocxTableIndex.PROJECTION:"ordinary-physical-word-table-model-v1";}
            if(selected.path("units").isEmpty()&&selected.path("unknowns").isEmpty())return null;
            ObjectNode identity=object();identity.set("source",graph.path("source").deepCopy());identity.set("generationSha256",graph.path("generationSha256").deepCopy());
            identity.set("coverageSha256",graph.path("coverageSha256").deepCopy());identity.put("projectionVersion",projection);
            String ref="ordinary-model-source:"+VettingCorpus.hash(JsonUtils.write(identity));
            ObjectNode out=object();out.put("projectionVersion",projection);out.put("sourceDeclarationRef",ref);
            out.put("referenceMeaning","modelUnitRef addresses the same envelope's modelUnitAddress; sourceDeclarationRef addresses its one source declaration. These observed units have no independent rank, citation ID or quotation authority.");
            out.set("unknowns",selected.path("unknowns").deepCopy());if(selected.has("sourceGroupRelations"))out.set("sourceGroupRelations",selected.path("sourceGroupRelations").deepCopy());ArrayNode units=out.putArray("units");
            for(JsonNode raw:selected.path("units")){
                String address="ordinary-model-unit:"+VettingCorpus.hash(JsonUtils.write(Arrays.asList(identity,raw)));
                ObjectNode unit=object();unit.put("sourceDeclarationRef",ref);
                if(!materialIds.add(address)){unit.put("modelUnitRef",address);units.add(unit);continue;}
                unit.setAll((ObjectNode)raw.deepCopy());unit.put("modelUnitAddress",address);
                unit.set("parts",parts(raw.path("parts")));unit.set("sourceSpans",spans(raw.path("sourceSpans")));unit.set("tableGroup",table(raw.path("tableGroup")));units.add(unit);
            }
            if(!sourceUnknowns.containsKey(ref)){
                sourceUnknowns.put(ref,graph.path("unknowns").deepCopy());ObjectNode declaration=identity.deepCopy();
                declaration.put("sourceDeclarationRef",ref);declaration.put("adoptionStatus","candidate_unverified");declaration.put("sourceQualityAccepted",false);
                declaration.put("humanConfirmed",false);declaration.put("semanticScopeVerified",false);declaration.put("legalClosure","unknown");
                declaration.set("sourceUnknowns",graph.path("unknowns").deepCopy());out.set("sourceDeclaration",declaration);
            }
            if(!pdf){ObjectNode guidance=guidanceScopes(source,chunk);if(guidance!=null)out.set("observedGuidanceScopes",guidance);}
            return out;
        }

        /** Guidance candidates are recomputed on demand from the same persisted parse; no stored generation changes. */
        ObjectNode guidanceScopes(SourceDocument source,VettingCorpus.Chunk chunk) {
            if(chunk==null)return null;
            Set<String> members=new LinkedHashSet<>();
            if(chunk.getParts()!=null)for(VettingCorpus.Part part:chunk.getParts())
                if(part!=null&&part.getBlockId()!=null&&!part.getBlockId().trim().isEmpty())members.add(part.getBlockId());
            return guidanceScopes(source,members);
        }
        ObjectNode guidanceScopes(SourceDocument source,Set<String> members) {
            if(source==null||members==null||members.isEmpty())return null;
            JsonNode evidence=guidanceEvidence(source);
            if(evidence==null)return null;
            ObjectNode out=object();out.put("version","ordinary-word-guidance-scopes-v1");out.put("status","observed");
            out.put("analysisRevision",evidence.path("sourceBinding").path("analysisRevision").asText());
            out.put("family",evidence.path("sourceBinding").path("fileKey").asText());
            out.put("selection","intersected_selected_chunk_members");
            out.put("scopeMeaning","Selected observed guidance, condition and marker candidates only; capture, source syntax, applicability and legal closure remain as declared");
            ArrayNode notes=out.putArray("guidanceNotes"),conditions=out.putArray("sourceConditions"),markers=out.putArray("markerCandidates"),groups=out.putArray("guidanceGroups");
            for(JsonNode note:evidence.path("guidanceNotes"))if(intersects(note.path("orderedMemberIds"),members))notes.add(note.deepCopy());
            for(JsonNode condition:evidence.path("conditions"))if(intersects(condition.path("memberRanges"),members)||members.contains(condition.path("memberId").asText()))conditions.add(condition.deepCopy());
            for(JsonNode marker:evidence.path("markerCandidates"))if(members.contains(marker.path("memberId").asText()))markers.add(marker.deepCopy());
            for(JsonNode group:evidence.path("groups"))if("guidance_capture".equals(group.path("kind").asText())&&intersects(group.path("orderedMemberIds"),members))groups.add(group.deepCopy());
            return out;
        }

        /** Only the observed Word families carry guidance syntax; other families and unparsable coverage stay absent. */
        private JsonNode guidanceEvidence(SourceDocument source) {
            if(source.getId()==null)return analyseGuidance(source);
            return ordinaryGuidanceEvidence.computeIfAbsent(String.valueOf(source.getId()),id->analyseGuidance(source));
        }
        private static JsonNode analyseGuidance(SourceDocument source) {
            try{
                JsonNode coverage=JsonUtils.parse(source.getParseCoverageJson()),blocks=JsonUtils.parse(source.getStructuredContentJson());
                if(!coverage.path("nativeDocxParagraphs").isObject()||!blocks.isArray())return null;
                List<DocumentBlock> parsedBlocks=JsonUtils.readList(JsonUtils.write(blocks),DocumentBlock.class);
                ParseCoverage parsedCoverage=JsonUtils.read(JsonUtils.write(coverage),ParseCoverage.class);
                DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument(source.getTextContent()==null?"":source.getTextContent(),
                        Collections.emptyList(),source.getPageCount()==null?0:source.getPageCount(),false,"on-demand template evidence",
                        parsedBlocks,parsedCoverage,source.getSourceSha256(),Collections.emptyList());
                return OerTemplateEvidence.analyse(source.getFileKey(),parsed);
            }catch(RuntimeException unsupported){return null;}
        }

        private static boolean intersects(JsonNode list,Set<String> members) {
            if(list==null||!list.isArray())return false;
            for(JsonNode value:list){String id=value.isTextual()?value.asText():value.path("memberId").asText();if(members.contains(id))return true;}
            return false;
        }
        public ObjectNode project(EvidencePointer p){return project(p,null,null);}
        public ObjectNode project(EvidencePointer p,JsonNode canonicalMember,String role) {
            Objects.requireNonNull(p,"Canonical evidence pointer required");
            JsonNode word=p.getNativeWordSourceScopesContext(),requested=wordSourceScopes.get(p.getCanonicalChunkId());
            if(word!=null&&requested!=null)require(JsonUtils.write(word).equals(JsonUtils.write(requested)),"normal_word_source_scope_contexts_disagree");
            if(word==null)word=requested;
            ObjectNode identity=object();identity.put("projectId",p.getProjectId());identity.put("module",p.getModule());identity.put("documentId",p.getDocumentId());identity.put("originalSourceSha256",p.getOriginalSourceSha256());
            identity.put("sourceRevisionSha256",p.getSourceRevisionSha256());identity.put("canonicalSourceHash",p.getCanonicalSourceHash());
            identity.put("projectionSha256",p.getProjectionSha256());identity.put("projectionRevisionSha256",p.getProjectionRevisionSha256());
            identity.put("projectionSchema",p.getProjectionSchema());identity.put("producerKind",p.getProducerKind());
            identity.put("role",role);
            if(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())){identity.put("sourceRevisionKind",p.getSourceRevisionKind());identity.put("familyCompositionSha256",p.getFamilyCompositionSha256());identity.put("familyComponentSetSha256",p.getFamilyComponentSetSha256());}
            String ref="model-source:"+VettingCorpus.hash(JsonUtils.write(identity));
            JsonNode unknown=p.getUnknowns()==null?array():p.getUnknowns();require(unknown.isArray(),"source_unknowns_array_required");
            ObjectNode limits=sourceLimits(p);boolean first=!sourceUnknowns.containsKey(ref);
            if(first){sourceUnknowns.put(ref,unknown.deepCopy());sourceLimits.put(ref,limits.deepCopy());}
            else require(sourceUnknowns.get(ref).equals(unknown)&&sourceLimits.get(ref).equals(limits),"same_source_projection_declarations_differ");
            UnknownProjection selected=unknowns(p,unknown);
            ObjectNode fullIdentity=identity.deepCopy();fullIdentity.set("completePointer",JsonUtils.mapper().valueToTree(p));fullIdentity.set("canonicalMember",copy(canonicalMember));
            if(word!=null)fullIdentity.set("nativeWordSourceScopesContext",word.deepCopy());
            String address="model-unit:"+VettingCorpus.hash(JsonUtils.write(fullIdentity));
            if(!materialIds.add(address)){
                ObjectNode refOnly=object();refOnly.put("modelMaterialVersion",VERSION);refOnly.put("modelUnitRef",address);refOnly.put("sourceDeclarationRef",ref);
                refOnly.put("unitId",p.getUnitId());refOnly.put("canonicalChunkId",p.getCanonicalChunkId());return refOnly;
            }
            ObjectNode structure=object();structure.put("modelMaterialVersion",VERSION);structure.put("sourceDeclarationRef",ref);
            structure.put("modelUnitAddress",address);
            structure.put("unitId",p.getUnitId());structure.put("canonicalChunkId",p.getCanonicalChunkId());
            structure.put("projectionSchema",p.getProjectionSchema());structure.put("producerKind",p.getProducerKind());
            structure.put("storageShape",p.getStorageShape());structure.put("originalGeometryStatus",p.getOriginalGeometryStatus());
            structure.put("visualReturnStatus",p.getVisualReturnStatus());structure.put("bboxConvention",p.getBboxConvention());
            structure.set("physicalPages",JsonUtils.mapper().valueToTree(p.getPhysicalPages()));
            structure.set("sourceOrder",copy(p.getSourceOrder()));structure.set("parts",parts(p.getParts()));
            structure.set("sourceSpans",spans(p.getSourceSpans()));structure.set("tableGroup",table(p.getTableGroup()));
            structure.set("relations",relations(p.getRelations()));structure.set("assetRefs",table(p.getAssetRefs()));
            structure.set("sourceQuality",fields(p.getSourceQuality(),"attributes","semanticQuality","notes","reviewMeaning","parseStatus","version","humanConfirmed","wholeSourceAccepted","legalQualityAccepted","meaning","components","status","adoptionStatus","sourceQualityAccepted","semanticScopeVerified","legalClosure"));
            if(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind()))structure.set("sourceFamilyOrigin",fields(p.getSourceFamilyOriginRef(),"componentOrdinal","childProjectionSha256","childChunkId","childUnitId","fullChildChunkSha256"));
            structure.set("adoption",NormalObservedContract.PRODUCER.equals(p.getProducerKind())?com.fasterxml.jackson.databind.node.NullNode.instance:fields(p.getAdoption(),"scope","unitId","clause","clauseHeadingLocation","nodeId","regionIds","referenceRegionIds","physicalPages","partSeparator","textChannel","reviewStatus","reviewedAttributes","humanConfirmed","originalGeometryStatus"));
            structure.set("unknowns",selected.units);
            if(NormalObservedContract.PRODUCER.equals(p.getProducerKind())){
                require(canonicalMember!=null&&NormalObservedContract.isNormal(canonicalMember),"normal_member_required");
                for(String key:Arrays.asList("kind","requiredMemberBlockIds","qualifiersComplete","unknowns"))structure.set(key,copy(canonicalMember.get(key)));
                JsonNode context=p.getNativePdfSourceGroupsContext();if(context!=null&&!context.isNull()){
                    require("native-pdf-source-groups-v1".equals(context.path("version").asText())&&Objects.equals(p.getOriginalSourceSha256(),context.path("sourceSha256").asText())&&Objects.equals(p.getOriginalSourceSha256(),context.path("originalSourceSha256").asText())&&Objects.equals(p.getSourceRevisionSha256(),context.path("sourceRevisionSha256").asText())&&Objects.equals(p.getProjectionSha256(),context.path("projectionSha256").asText())&&Objects.equals(p.getProjectId(),context.path("source").path("projectId").asText())&&Objects.equals(String.valueOf(p.getDocumentId()),context.path("source").path("documentId").asText())&&Objects.equals(p.getJavaParsedSourceHash(),context.path("source").path("parsedSourceHash").asText())&&context.path("generationSha256").asText().matches("[a-f0-9]{64}"),"normal_pdf_source_group_context_binding_differs");
                    structure.set("observedSourceGroups",NormalSourceCandidateGraph.pdfModelGroups(context));structure.set("sourceGroupUnknowns",context.path("issues").deepCopy());structure.set("sourceGroupRelations",context.path("relations").deepCopy());
                }
                if(word!=null&&!word.isNull()){
                    require("docx-source-clause-scopes-v1".equals(word.path("version").asText())&&Objects.equals(p.getOriginalSourceSha256(),word.path("sourceSha256").asText())
                            &&Objects.equals(p.getOriginalSourceSha256(),word.path("originalSourceSha256").asText())&&Objects.equals(p.getSourceRevisionSha256(),word.path("sourceRevisionSha256").asText())
                            &&Objects.equals(p.getProjectionSha256(),word.path("projectionSha256").asText())&&Objects.equals(p.getProjectId(),word.path("source").path("projectId").asText())
                            &&Objects.equals(String.valueOf(p.getDocumentId()),word.path("source").path("documentId").asText())&&Objects.equals(p.getJavaParsedSourceHash(),word.path("source").path("parsedSourceHash").asText())
                            &&word.path("generationSha256").asText().matches("[a-f0-9]{64}")&&Objects.equals(p.getCanonicalChunkId(),word.path("canonicalChunkId").asText())
                            &&Objects.equals(VettingCorpus.hash(JsonUtils.write(p.getParts())),word.path("selectedPartsSha256").asText()),"normal_word_source_scope_context_binding_differs");
                    structure.set("observedWordSourceScopes",fields(word,"version","sourceSha256","ranges","referenceFrames","members","issues"));
                }
                SourceDocument guidanceSource=ordinarySources.get(String.valueOf(p.getDocumentId()));
                if(guidanceSource!=null&&Objects.equals(guidanceSource.getSourceSha256(),p.getOriginalSourceSha256())
                        &&Objects.equals(VettingCorpus.sourceHash(guidanceSource),p.getJavaParsedSourceHash())){
                    Set<String> memberIds=new LinkedHashSet<>();
                    for(JsonNode part:p.getParts())if(part!=null&&part.path("blockId").isTextual())memberIds.add(part.path("blockId").asText());
                    ObjectNode guidance=guidanceScopes(guidanceSource,memberIds);
                    if(guidance!=null)structure.set("observedGuidanceScopes",guidance);
                }
            }
            if(first){
                ObjectNode declaration=identity.deepCopy();declaration.put("declarationVersion",SOURCE_VERSION);declaration.put("sourceDeclarationRef",ref);
                declaration.set("sourceFilename",copy(p.getSourceFilename()));declaration.set("limits",limits);
                declaration.set("sourceUnknowns",selected.source);declaration.set("unknownStatusReasonSummary",selected.summary);
                declaration.put("declaredUnknownCount",unknown.size());declaration.put("unknownProjectionScope","selected unitId or exact region path; other-unit locations remain in complete evidence pointers");
                structure.set("sourceDeclaration",declaration);
            }
            return structure;
        }
    }

    private static ObjectNode sourceLimits(EvidencePointer p) {
        ObjectNode out=object();out.put("humanConfirmed",p.isHumanConfirmed());out.put("wholeSourceSemantics","unknown");
        out.put("originalGeometryStatus",p.getOriginalGeometryStatus());out.put("visualReturnStatus",p.getVisualReturnStatus());
        if(NormalObservedContract.PRODUCER.equals(p.getProducerKind())){out.put("adoptionStatus","candidate_unverified");out.put("sourceQualityAccepted",false);out.put("semanticScopeVerified",false);out.put("legalClosure","unknown");}
        JsonNode pdf=p.getNativePdfEvidence();
        if("native_pdf".equals(p.getProducerKind())){
            com.consense.service.sources.NativePdfCanonicalContract.validateDisplayDeclarations(pdf);
            out.setAll(fields(pdf,"humanConfirmed","semanticScopeVerified","wholeOriginalSourceCoverage","visualReturnStatus","limitations"));
        }
        if(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())){
            JsonNode d=p.getNativePdfSourceFamilyEvidence();NativePdfSourceFamilyContract.validateDisplayDeclarations(d);
            out.setAll(fields(d,"humanConfirmed","semanticScopeVerified","wholeOriginalSourceCoverage","visualReturnStatus","limitations","sourceRevisionKind","compositionSha256","componentSetSha256"));
            out.set("sourceQuality",fields(d.path("sourceQuality"),"semanticQuality","humanConfirmed","wholeSourceAccepted","legalQualityAccepted","meaning","components"));
        }
        return out;
    }
    private static ArrayNode parts(JsonNode raw) {
        require(raw!=null&&raw.isArray(),"canonical_parts_required");ArrayNode out=array();
        for(JsonNode part:raw){ObjectNode p=fields(part,"anchor","blockId","startOffset","endOffset","offsetUnit","physicalPageNo","bbox","bboxConvention","referenceRegionId","text","textChannel","sourceSha256","native","nativeBlockId","nativeEditingState","nativeSemanticQuality","nativeLimitations","qualityAttributes","candidateTextStatus","originalGeometryStatus","nativeEffectiveVisibility","geometryStatus","apTextAdoption");
            if(part.has("sourcePosition"))p.set("sourcePosition",position(part.get("sourcePosition")));
            if(part.has("textTransform"))p.set("textTransform",fields(part.get("textTransform"),"kind","textChannel","offsetUnit","sourceChannel","normalization","status"));
            if(part.has("cells"))p.set("cells",table(part.get("cells")));
            for(String key:Arrays.asList("wordNumbering","wordSymbols","wordWarnings","wordCells","wordTextSpans","wordFields"))if(part.has(key))p.set(key,table(part.get(key)));
            if(part.has("wordNativeObjects"))p.set("wordNativeObjects",wordNativeObjects(part.get("wordNativeObjects")));
            if(part.has("wordObservation"))p.set("wordObservation",table(wordObservation(part.get("wordObservation"),true)));
            for(String key:Arrays.asList("visualRegion","visualFrame","visualProvenance","readingContext","nativeTableCandidate","parserPageGroup","pdfFormField"))if(part.has(key))p.set(key,table(part.get(key)));
            out.add(p);
        }return out;
    }
    private static ArrayNode spans(JsonNode raw){require(raw!=null&&raw.isArray(),"canonical_source_spans_required");ArrayNode out=array();
        for(JsonNode span:raw){ObjectNode s=fields(span,"blockId","cellId","startOffset","endOffset","offsetUnit","sourceSha256","physicalPageNo");
            if(span.has("sourcePosition"))s.set("sourcePosition",position(span.get("sourcePosition")));out.add(s);}return out;}
    private static JsonNode position(JsonNode raw){return raw==null||raw.isNull()?copy(raw):fields(raw,"kind","physicalPageNo","partName","nodePath","partSha256","sourceSha256","story","tableId","rowIndex","cellIndex","rowNodePath","tableNodePath","parentCellNodePath");}
    private static ArrayNode relations(JsonNode raw){require(raw!=null&&raw.isArray(),"canonical_relations_required");ArrayNode out=array();
        for(JsonNode r:raw)out.add(fields(r,"type","status","scope","sameParentClause","sourceReview","basis","humanConfirmed","fromUnitId","toUnitId","fromRegionId","toRegionId","fromBlockId","toBlockId","fromCellId","toCellId","condition","qualifiers","qualifiersComplete","exceptions"));return out;}

    /** All table/cell/grid/merge/nesting values remain. Only known software/CAS proof expansions become artifact identities. */
    static JsonNode visualRegion(JsonNode raw) {
        ObjectNode selected=fields(raw,"id","kind","text","originalText","bbox","polygon","geometryStatus","channel","semanticStatus",
                "parentId","readingOrder","fields","controls","table","relations","unknowns","assetRefs","groupId","ownerId");
        if(raw!=null&&raw.has("originalGeometry")){
            JsonNode geometry=raw.get("originalGeometry");ObjectNode compact=fields(geometry,"status","reason","xyxyNormalized","xyxyOriginal","polygonOriginal","transformSha256");
            if(geometry.has("targetFrame"))compact.set("targetFrame",geometry.get("targetFrame").isNull()?geometry.get("targetFrame")
                    :fields(geometry.get("targetFrame"),"coordinateFrame","coordinateUnit","width","height","physicalPageNo","sourceSha256"));
            compact.put("meaning","Explicit geometry projection status; source text, semantics and original bytes remain unverified");
            selected.set("originalGeometry",compact);
        }
        return table(selected);
    }
    static JsonNode wordObservation(JsonNode raw,boolean whole) {
        return whole?fields(raw,"parentBlockId","nativePath","wordStructureVersion","effectiveText","parentEffectiveTextSha256",
                "originalText","deletedText","strikeText","wordNumbering","wordSymbols","wordWarnings","wordNativeObjects","wordTextSpans","wordFields")
                :fields(raw,"parentBlockId","nativePath","wordStructureVersion","parentEffectiveTextSha256");
    }
    static JsonNode wordNativeObjects(JsonNode raw) {
        ArrayNode out=JsonUtils.mapper().createArrayNode();
        if(raw!=null&&raw.isArray())for(JsonNode object:raw){
            ObjectNode selected=fields(object,"kind","location","status","semanticStatus","deleted","struck","sourcePart","sourcePosition","relationshipRefs","nativeXmlStatus","mathProjection","noteReference","alternateContent","nativeXmlSha256");
            if(object.has("nativeXml"))selected.put("nativeXmlSha256",VettingCorpus.hash(object.path("nativeXml").asText()));
            out.add(table(selected));
        }
        return out;
    }
    static JsonNode visualFrame(JsonNode raw) {
        return table(fields(raw,"imageSha256","sourceSha256","inputSha256","width","height","coordinateUnit","bboxFormat",
                "pageNo","sourceFrame","transform","mediaBox","cropBox","rotation","userUnit"));
    }
    static JsonNode visualProvenance(JsonNode raw) {
        return fields(raw,"engine","modelRevision","configSha256","inputSha256","terminal","rawResponseSha256","rawResponseRef","limitations","sourceBindingStatus");
    }
    static JsonNode nativeTableCandidate(JsonNode raw) {
        ObjectNode selected=fields(raw,"method","provider","status","failures","physicalPageNo","frame","coverage","nativeGlyphNamespace","sourceSha256","semanticStatus");
        JsonNode candidate=raw.path("candidate");
        if(candidate.isObject()){
            selected.set("candidate",table(fields(candidate,"schema","tables","logicalTables","observationGroups","conditionCandidates","completeSourceGroupAccepted","quality")));
            selected.put("unassignedSourceGlyphCount",candidate.path("unassignedSourceGlyphIds").size());
            selected.put("unassignedSourceGlyphMeaning","Unassigned original glyph observations remain unknown in the complete source candidate; raw glyph records are not model material");
        }else selected.putNull("candidate");
        selected.put("candidateSourceSha256",VettingSourceMaterial.formSourceSha256(raw));
        selected.put("meaning","Whole independent native method observation; no best-method fusion, field adoption, source-quality or complete-source acceptance");
        return table(selected);
    }

    static JsonNode parserPageGroup(JsonNode raw) {
        ObjectNode selected=fields(raw,"physicalPageNo","scope","source","selectionStatus","selectedParser","policySha256",
                "sourceQualityAccepted","adoptionStatus","reason");
        ArrayNode alternatives=selected.putArray("candidates");
        for(JsonNode candidate:raw.path("candidates")){
            ObjectNode alternative=fields(candidate,"parser","runId","pageId","features");
            alternative.set("blocks",table(candidate.path("blocks")));
            alternative.set("page",table(fields(candidate.path("page"),"pageId","physicalPageNo","parserPageIndex","width","height",
                    "coordinateUnit","frame","mapping","providerInputPageNo","inputToOriginalMappingStatus","qualityStatus",
                    "originalPageGeometryStatus","coverageStatus","readingOrder","relations","unknowns")));
            alternatives.add(alternative);
        }
        return selected;
    }

    private static JsonNode table(JsonNode raw){
        if(raw==null)return com.fasterxml.jackson.databind.node.NullNode.instance;
        if(raw.isArray()){ArrayNode a=array();for(JsonNode x:raw)a.add(table(x));return a;}
        if(!raw.isObject())return raw.deepCopy();ObjectNode out=object();Iterator<String> names=raw.fieldNames();
        while(names.hasNext()){String k=names.next();JsonNode v=raw.get(k);
            if(Arrays.asList("observerIdentitySha256","internalProjectionSha256","transportValidation","rawNode","rawResponse","rawPayload","softwareProof","reviewActor","referenceRelationIndex","glyphs","rawGlyphs","glyphRecords","operators","nativeObservation","sourceContext").contains(k))continue;
            if(k.equals("sourcePosition")){out.set(k,position(v));continue;}
            if(v.isObject()&&v.has("artifactDescriptor")){out.set(k,reference(v));continue;}
            out.set(k,table(v));
        }return out;
    }
    private static ObjectNode reference(JsonNode raw){ObjectNode out=fields(raw,"kind","jsonPointer","payloadSha256","sourceSha256","sourceRevisionSha256");
        JsonNode d=raw.path("artifactDescriptor");out.set("artifactIdentity",fields(d,"sha256","schema","identityScope"));return out;}

    private static final class UnknownProjection{final ArrayNode units=array(),source=array(),summary=array();}
    private static UnknownProjection unknowns(EvidencePointer p,JsonNode raw){
        UnknownProjection out=new UnknownProjection();Set<String> regions=new LinkedHashSet<>();
        if(p.getAdoption()!=null)for(String key:Arrays.asList("regionIds","referenceRegionIds"))for(JsonNode r:p.getAdoption().path(key))if(r.isTextual())regions.add(r.asText());
        for(JsonNode part:p.getParts())if(part.path("referenceRegionId").isTextual())regions.add(part.path("referenceRegionId").asText());
        Map<String,Integer> summary=new LinkedHashMap<>();Map<String,JsonNode> summaries=new LinkedHashMap<>();
        for(JsonNode u:raw){require(u.isObject(),"unknown_entry_object_required");Set<String> keys=new HashSet<>();u.fieldNames().forEachRemaining(keys::add);
            if(keys.equals(new HashSet<>(Arrays.asList("componentOrdinal","childProjectionSha256","childSourceRevisionSha256","unknown")))){
                require(NativePdfSourceFamilyContract.PRODUCER.equals(p.getProducerKind())&&u.path("componentOrdinal").isIntegralNumber()&&u.path("childProjectionSha256").isTextual()&&u.path("childSourceRevisionSha256").isTextual(),"family_unknown_identity_invalid");
                JsonNode nested=u.path("unknown");require(nested.isObject()&&nested.size()==3&&nested.path("path").isTextual()&&nested.path("status").isTextual()&&nested.path("reason").isTextual(),"family_unknown_fields_invalid");
                JsonNode origin=p.getSourceFamilyOriginRef();boolean component=u.path("componentOrdinal").equals(origin.path("componentOrdinal"))&&u.path("childProjectionSha256").equals(origin.path("childProjectionSha256"));
                String path=nested.path("path").asText();boolean local=false;for(String region:regions)if(path.equals("regions."+region)||path.startsWith("regions."+region+".")){local=true;break;}
                if(component&&local)out.units.add(u.deepCopy());else if(!path.startsWith("regions."))out.source.add(u.deepCopy());
                ObjectNode summaryRow=fields(u,"componentOrdinal","childProjectionSha256","childSourceRevisionSha256");summaryRow.setAll(fields(nested,"status","reason"));String key=JsonUtils.write(summaryRow);summary.merge(key,1,Integer::sum);summaries.put(key,summaryRow);
            }else if(keys.equals(new HashSet<>(Arrays.asList("path","status","reason")))){
                require(u.path("path").isTextual()&&u.path("status").isTextual()&&u.path("reason").isTextual(),"pdf_unknown_fields_invalid");
                String path=u.path("path").asText();boolean local=false;
                for(String region:regions)if(path.equals("regions."+region)||path.startsWith("regions."+region+".")){local=true;break;}
                if(local)out.units.add(u.deepCopy());else if(!path.startsWith("regions."))out.source.add(u.deepCopy());
                ObjectNode s=fields(u,"status","reason");String key=JsonUtils.write(s);summary.merge(key,1,Integer::sum);summaries.put(key,s);
            }else if(keys.equals(new HashSet<>(Arrays.asList("unitId","attributes")))){
                require(u.path("unitId").isTextual()&&u.path("attributes").isObject(),"docx_unknown_fields_invalid");
                if(Objects.equals(p.getUnitId(),u.path("unitId").asText()))out.units.add(u.deepCopy());
                ObjectNode s=object();s.set("attributes",u.get("attributes").deepCopy());String key=JsonUtils.write(s);summary.merge(key,1,Integer::sum);summaries.put(key,s);
            }else throw new IllegalArgumentException("Model material unknown_schema_not_supported; complete source evidence unchanged");
        }
        for(String key:summary.keySet()){ObjectNode s=(ObjectNode)summaries.get(key).deepCopy();s.put("declaredEntries",summary.get(key));out.summary.add(s);}return out;
    }
    private static ObjectNode fields(JsonNode raw,String... keys){ObjectNode out=object();if(raw==null||raw.isNull())return out;
        require(raw.isObject(),"model_structure_object_required");for(String key:keys)if(raw.has(key))out.set(key,raw.get(key).deepCopy());return out;}
    private static JsonNode copy(JsonNode n){return n==null?com.fasterxml.jackson.databind.node.NullNode.instance:n.deepCopy();}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static ArrayNode array(){return JsonUtils.mapper().createArrayNode();}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException("Model material rejected: "+reason);}
}
