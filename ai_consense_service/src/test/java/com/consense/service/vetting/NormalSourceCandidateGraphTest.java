package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalSourceCandidateGraphTest {
    @Test void nativeInventoryClosesMixedCarrierWithNestedEmptyAndUnrepresentedMembersWithoutChangingPublicEvidence()throws Exception{
        SourceDocument source=nativeInventorySource(89);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        String saved=JsonUtils.write(source),chunkJson=JsonUtils.write(chunks);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        java.lang.reflect.Method fp=VettingRetrievalClient.class.getDeclaredMethod("corpusFingerprint",List.class);fp.setAccessible(true);Object strict=fp.invoke(null,chunks);
        NormalSourceCandidateGraph.NativeDocxTableIndex index=new NormalSourceCandidateGraph.NativeDocxTableIndex(source,graph);
        JsonNode old=NormalSourceCandidateGraph.selectedForModel(graph,chunks.get(0));assertTrue(old.path("units").isEmpty());
        JsonNode projected=NormalSourceCandidateGraph.selectedForModel(graph,chunks.get(0),index),unit=projected.path("units").get(0),tables=unit.path("tableGroup").path("tables");
        assertEquals(3,tables.size());assertEquals(90,tables.get(0).path("rows").size());assertEquals(0,tables.get(2).path("rows").size());
        assertEquals(0,tables.get(0).path("rows").get(89).path("cells").size());assertEquals(3,tables.get(0).path("rows").get(88).path("cells").size());
        assertTrue(tables.get(0).path("rows").get(88).path("cells").get(2).path("observations").isEmpty());
        assertEquals(179,tables.findValues("wordCell").size());assertEquals(1,unit.path("requiredMemberBlockIds").size());assertTrue(unit.path("parts").isEmpty());assertTrue(unit.path("sourceSpans").isEmpty());
        JsonNode first=tables.get(0).path("rows").get(0).path("cells").get(0).path("observations").get(0).path("wordCell");
        assertEquals("outer includes Nested literal",first.path("text").asText());assertEquals("Original channel",first.path("originalText").asText());
        assertEquals("Deleted channel",first.path("deletedText").asText());assertEquals("Strike channel",first.path("strikeText").asText());
        assertEquals("",tables.get(0).path("rows").get(0).path("cells").get(1).path("observations").get(0).path("wordCell").path("text").asText());
        assertEquals(first.path("sourcePosition").path("nodePath"),tables.get(1).path("parentCellNodePath"));
        assertEquals(saved,JsonUtils.write(source));assertEquals(chunkJson,JsonUtils.write(chunks));assertEquals(strict,fp.invoke(null,chunks));assertEquals(graph,NormalSourceCandidateGraph.requireBound(source));
        VettingCorpus.Chunk broken=JsonUtils.read(JsonUtils.write(chunks.get(0)),VettingCorpus.Chunk.class);broken.setContent("different body");
        assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.selectedForModel(graph,broken,index));
    }
    @Test void invalidPresentNativeInventoryAndSameRequestChangesRejectWhileAbsentInventoryIsExact(){
        SourceDocument legacy=ordinaryWordSource();JsonNode graph=NormalSourceCandidateGraph.requireBound(legacy);VettingCorpus.Chunk c=VettingCorpus.chunks(Collections.singletonList(legacy)).get(0);
        assertEquals(NormalSourceCandidateGraph.selectedForModel(graph,c),NormalSourceCandidateGraph.selectedForModel(graph,c,new NormalSourceCandidateGraph.NativeDocxTableIndex(legacy,graph)));
        for(int kind=0;kind<7;kind++){
            SourceDocument source=nativeInventorySource(2);ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode inventory=(ObjectNode)coverage.path("nativeDocxTables");
            ObjectNode part=(ObjectNode)inventory.path("parts").get(0);ArrayNode members=(ArrayNode)part.path("members");
            if(kind==0)inventory.put("version","unsupported");if(kind==1)part.put("partSha256",VettingCorpus.hash("different part"));
            if(kind==2)((ObjectNode)members.get(2).path("retained").get(0)).put("wordCellOrdinalZeroBased",999);
            if(kind==3)((ArrayNode)members.get(2).path("retained")).add(members.get(2).path("retained").get(0).deepCopy());
            if(kind==4)((ObjectNode)members.get(2)).put("rowNodePath","invented row");if(kind==5)((ObjectNode)members.get(2)).putArray("retained");
            if(kind==6)members.add(members.get(0).deepCopy());source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);
            assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(VettingCorpus.chunks(Collections.singletonList(source)).get(0)));
        }
        SourceDocument source=nativeInventorySource(2);OerModelMaterial.Request request=new OerModelMaterial.Request(Collections.singletonList(source));VettingCorpus.Chunk chunk=VettingCorpus.chunks(Collections.singletonList(source)).get(0);
        assertNotNull(request.projectOrdinary(chunk));source.setParseCoverageJson(source.getParseCoverageJson()+" ");assertThrows(IllegalArgumentException.class,()->request.projectOrdinary(chunk));
    }
    @Test void retainedNativeCellWithoutPhysicalTableAncestorFailsClosedRatherThanDroppingLegacyUnknown(){
        SourceDocument source=nativeInventorySource(2);List<DocumentBlock> blocks=VettingCorpus.blocks(source);DocumentBlock carrier=blocks.get(0);
        ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());JsonNode part=coverage.path("nativeDocxTables").path("parts").get(0);String body=part.path("loadedRoots").get(0).path("nodePath").asText();String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";
        String row=body+"/"+ns+"tr[1]",path=row+"/"+ns+"tc[1]";ArrayNode members=(ArrayNode)part.path("members");nativeMember(members,"row",row,null,null,null);
        int ordinal=carrier.getWordCells().size();nativeMember(members,"cell",path,null,row,null).withArray("retained").addObject().put("blockOrdinalZeroBased",0).put("wordCellOrdinalZeroBased",ordinal);
        DocumentBlock.WordCell orphan=JsonUtils.read(JsonUtils.write(carrier.getWordCells().get(0)),DocumentBlock.WordCell.class);ObjectNode p=(ObjectNode)orphan.getSourcePosition();p.put("nodePath",path);p.put("rowNodePath",row);p.remove(Arrays.asList("tableNodePath","parentCellNodePath"));orphan.setLocation("body/0/cell/"+ordinal);carrier.getWordCells().add(orphan);
        source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);VettingCorpus.Chunk chunk=VettingCorpus.chunks(Collections.singletonList(source)).get(0);
        assertFalse(NormalSourceCandidateGraph.selectedForModel(graph,chunk).path("unknowns").isEmpty());
        assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.selectedForModel(graph,chunk,new NormalSourceCandidateGraph.NativeDocxTableIndex(source,graph)));
    }
    @Test void inventoryReverseChecksActualRowBlocksIncludingBlankAndZeroCellRows(){
        SourceDocument source=nativeRowInventorySource();List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        JsonNode unit=new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(chunks.get(0)).path("units").get(0);
        JsonNode rows=unit.path("tableGroup").path("tables").get(0).path("rows");assertEquals(9,rows.size());assertEquals(1,rows.get(8).path("retained").size());assertTrue(rows.get(8).path("cells").isEmpty());
        for(int missing:new int[]{0,1,8}){
            SourceDocument invalid=JsonUtils.read(JsonUtils.write(source),SourceDocument.class);ObjectNode coverage=(ObjectNode)JsonUtils.parse(invalid.getParseCoverageJson());
            int seen=0;for(JsonNode member:coverage.path("nativeDocxTables").path("parts").get(0).path("members"))if("row".equals(member.path("kind").asText())&&seen++==missing)((ObjectNode)member).putArray("retained");
            invalid.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(invalid);VettingCorpus.Chunk selected=VettingCorpus.chunks(Collections.singletonList(invalid)).get(0);
            assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(Collections.singletonList(invalid)).projectOrdinary(selected));
        }
    }
    @Test void exactCellPositionCannotBeCarriedByAnUnrelatedRowInTheSameLoadedStory(){
        SourceDocument source=nativeRowInventorySource();List<DocumentBlock> blocks=VettingCorpus.blocks(source);DocumentBlock.WordCell moved=blocks.get(0).getWordCells().remove(0);int ordinal=blocks.get(1).getWordCells().size();blocks.get(1).getWordCells().add(moved);
        ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());
        for(JsonNode member:coverage.path("nativeDocxTables").path("parts").get(0).path("members"))if(moved.getSourcePosition().path("nodePath").equals(member.path("nodePath"))){
            ObjectNode join=(ObjectNode)member.path("retained").get(0);join.put("blockOrdinalZeroBased",1);join.put("wordCellOrdinalZeroBased",ordinal);
        }
        source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);VettingCorpus.Chunk selected=VettingCorpus.chunks(Collections.singletonList(source)).get(0);
        assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(selected));
    }
    private static SourceDocument nativeRowInventorySource(){
        SourceDocument source=ordinaryWordSource();List<DocumentBlock> blocks=VettingCorpus.blocks(source);String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";JsonNode first=blocks.get(0).getSourcePosition();
        String table=first.path("nodePath").asText();table=table.substring(0,table.lastIndexOf("/"+ns+"tr["));String sha=first.path("partSha256").asText();
        DocumentBlock zero=new DocumentBlock();zero.setId("actual-saved-zero-cell-row");zero.setSource("docx");zero.setKind("table_row");zero.setLocation("body/0/table-row/8");zero.setText("");zero.setSourcePosition(nativePosition(source,sha,table+"/"+ns+"tr[9]"));blocks.add(zero);
        ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());JsonNode inventory=JsonUtils.parse(nativeInventorySource(2).getParseCoverageJson()).path("nativeDocxTables");coverage.set("nativeDocxTables",inventory);ObjectNode part=(ObjectNode)inventory.path("parts").get(0);part.put("partSha256",sha);ArrayNode members=part.putArray("members");nativeMember(members,"table",table,null,null,null);
        for(int b=0;b<blocks.size();b++){
            DocumentBlock block=blocks.get(b);String row=block.getSourcePosition().path("nodePath").asText();nativeMember(members,"row",row,table,null,null).withArray("retained").addObject().put("blockOrdinalZeroBased",b);
            for(int c=0;c<block.getWordCells().size();c++){
                String path=row+"/"+ns+"tc["+(c+1)+"]";ObjectNode p=nativePosition(source,sha,path);p.put("tableNodePath",table);p.put("rowNodePath",row);block.getWordCells().get(c).setSourcePosition(p);
                nativeMember(members,"cell",path,table,row,null).withArray("retained").addObject().put("blockOrdinalZeroBased",b).put("wordCellOrdinalZeroBased",c);
            }
        }
        source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    /** Authored saved-inventory fixture: one mixed carrier, with no fabricated independent row blocks. */
    static SourceDocument nativeInventorySource(int rowCount){
        SourceDocument source=source();String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}",body="/"+ns+"document[1]/"+ns+"body[1]",table=body+"/"+ns+"tbl[1]";
        String partSha=VettingCorpus.hash("I20 authored saved XML identity");DocumentBlock carrier=new DocumentBlock();carrier.setId("i20-carrier");carrier.setLocation("body/0");carrier.setKind("paragraph");carrier.setSource("docx");
        carrier.setSourcePosition(nativePosition(source,partSha,body));List<DocumentBlock.WordCell> cells=new ArrayList<>();
        ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),inventory=coverage.putObject("nativeDocxTables");
        inventory.put("version","docx-native-tables-v1");inventory.put("kind","docx_native");inventory.put("sourceSha256",source.getSourceSha256());inventory.put("status","observed_loaded_native_roots");
        inventory.put("scope","normal_loaded_body_header_footer_footnote_endnote_roots_only");inventory.put("unobservedScope","package_stories_or_roots_not_loaded_by_normal_parser_including_unsupported_stories");inventory.put("semanticScopeVerified",false);inventory.put("effectiveContentStatus","unknown");
        ObjectNode part=inventory.putArray("parts").addObject();part.put("partName","word/document.xml");part.put("partSha256",partSha);part.putArray("loadedRoots").addObject().put("kind","body").put("nodePath",body);
        ArrayNode members=part.putArray("members");nativeMember(members,"table",table,null,null,null);
        for(int r=0;r<rowCount;r++){
            String row=table+"/"+ns+"tr["+(r+1)+"]";nativeMember(members,"row",row,table,null,null);
            for(int c=0;c<2;c++){
                String cell=row+"/"+ns+"tc["+(c+1)+"]";String value=r==0?(c==0?"outer includes Nested literal":""):"Actual "+r+":"+c;
                nativeCell(source,carrier,cells,members,partSha,table,row,cell,null,value);
                if(r==0&&c==0){
                    cells.get(0).setOriginalText("Original channel");cells.get(0).setDeletedText("Deleted channel");cells.get(0).setStrikeText("Strike channel");
                    String nested=cell+"/"+ns+"tbl[1]",innerRow=nested+"/"+ns+"tr[1]";nativeMember(members,"table",nested,table,null,cell);nativeMember(members,"row",innerRow,nested,null,null);
                    nativeCell(source,carrier,cells,members,partSha,nested,innerRow,innerRow+"/"+ns+"tc[1]",cell,"Nested literal");
                    nativeMember(members,"table",cell+"/"+ns+"tbl[2]",table,null,cell);
                }
            }
            if(r==rowCount-1)nativeMember(members,"cell",row+"/"+ns+"tc[3]",table,row,null);
        }
        nativeMember(members,"row",table+"/"+ns+"tr["+(rowCount+1)+"]",table,null,null);
        carrier.setWordCells(cells);carrier.setCells(cells.stream().map(DocumentBlock.WordCell::getText).collect(java.util.stream.Collectors.toList()));
        carrier.setText("Selected mixed carrier "+String.join(" ",Collections.nCopies(400,"literal")));source.setTextContent(carrier.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(carrier)));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    private static ObjectNode nativePosition(SourceDocument source,String sha,String path){return JsonUtils.mapper().createObjectNode().put("kind","docx_native").put("sourceSha256",source.getSourceSha256()).put("partName","word/document.xml").put("partSha256",sha).put("nodePath",path);}
    private static ObjectNode nativeMember(ArrayNode members,String kind,String path,String table,String row,String parentCell){
        ObjectNode m=members.addObject();m.put("kind",kind);m.put("nodePath",path);if(table!=null)m.put("table".equals(kind)?"parentTableNodePath":"tableNodePath",table);if(row!=null)m.put("rowNodePath",row);if(parentCell!=null)m.put("parentCellNodePath",parentCell);m.putArray("retained");return m;
    }
    private static void nativeCell(SourceDocument source,DocumentBlock carrier,List<DocumentBlock.WordCell> cells,ArrayNode members,String sha,String table,String row,String path,String parentCell,String value){
        DocumentBlock.WordCell cell=new DocumentBlock.WordCell();cell.setLocation(carrier.getLocation()+"/cell/"+cells.size());cell.setTableLocation("actual-carrier-table-location");cell.setText(value);cell.setRowIndex(0);cell.setCellIndex(cells.size());cell.setGridColumn(0);cell.setColumnSpan(1);cell.setRowSpan(1);cell.setVerticalMerge("none");cell.setHorizontalMerge("none");cell.setTopologyStatus("observed_native");
        ObjectNode position=nativePosition(source,sha,path);position.put("tableNodePath",table);position.put("rowNodePath",row);if(parentCell!=null)position.put("parentCellNodePath",parentCell);cell.setSourcePosition(position);
        nativeMember(members,"cell",path,table,row,parentCell).withArray("retained").addObject().put("blockOrdinalZeroBased",0).put("wordCellOrdinalZeroBased",cells.size());cells.add(cell);
    }
    @Test void coverageOnlyNativeInventoryKeepsSourceQualityAndChunkIdsButInvalidatesOldGenerationAndMaterial() throws Exception {
        SourceDocument legacy=ordinaryWordSource();String saved=JsonUtils.write(legacy),oldGraph=legacy.getNormalCandidateJson(),oldHash=VettingCorpus.sourceHash(legacy);
        List<VettingCorpus.Chunk> oldChunks=VettingCorpus.chunks(Collections.singletonList(legacy));String oldChunksJson=JsonUtils.write(oldChunks);
        JsonNode oldMaterial=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(oldChunks,Collections.singletonList(legacy)));
        java.lang.reflect.Method fingerprint=VettingRetrievalClient.class.getDeclaredMethod("corpusFingerprint",List.class);fingerprint.setAccessible(true);Object oldFingerprint=fingerprint.invoke(null,oldChunks);
        SourceDocument replay=JsonUtils.read(saved,SourceDocument.class);NormalSourceCandidateGraph.capture(replay);assertEquals(saved,JsonUtils.write(replay));
        assertEquals(oldChunksJson,JsonUtils.write(VettingCorpus.chunks(Collections.singletonList(replay))));assertEquals(oldFingerprint,fingerprint.invoke(null,VettingCorpus.chunks(Collections.singletonList(replay))));
        SourceDocument current=JsonUtils.read(saved,SourceDocument.class);
        com.fasterxml.jackson.databind.node.ObjectNode coverage=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(current.getParseCoverageJson());
        com.fasterxml.jackson.databind.node.ObjectNode inventory=coverage.putObject("nativeDocxTables");inventory.put("version","docx-native-tables-v1");inventory.put("status","observed_loaded_native_roots");inventory.putArray("parts");
        current.setParseCoverageJson(JsonUtils.write(coverage));
        assertEquals(oldHash,VettingCorpus.sourceHash(current));assertEquals(legacy.getStructuredContentJson(),current.getStructuredContentJson());assertEquals(legacy.getTextContent(),current.getTextContent());
        assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(current));
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.requireCurrentDocuments(Collections.singletonList(legacy),Collections.singletonList(current)));
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(com.consense.service.sources.CanonicalEvidenceException.class,()->VettingSourceMaterial.project(oldChunks,Collections.singletonList(current))).getMessage());
        NormalSourceCandidateGraph.capture(current);JsonNode graph=NormalSourceCandidateGraph.requireBound(current),before=JsonUtils.parse(oldGraph);
        assertNotEquals(before.path("coverageSha256"),graph.path("coverageSha256"));assertNotEquals(before.path("generationSha256"),graph.path("generationSha256"));
        assertNotEquals(before.path("units").get(0).path("unitId"),graph.path("units").get(0).path("unitId"));
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(current));assertEquals(oldChunks.size(),chunks.size());
        for(int i=0;i<chunks.size();i++){
            assertEquals(oldChunks.get(i).getId(),chunks.get(i).getId());assertEquals(oldChunks.get(i).getContent(),chunks.get(i).getContent());
            assertEquals(oldChunks.get(i).getSourceQualityHash(),chunks.get(i).getSourceQualityHash());assertEquals(JsonUtils.write(oldChunks.get(i).getSourceQuality()),JsonUtils.write(chunks.get(i).getSourceQuality()));
            assertEquals(JsonUtils.write(oldChunks.get(i).getParts()),JsonUtils.write(chunks.get(i).getParts()));
        }
        assertNotEquals(oldFingerprint,fingerprint.invoke(null,chunks));
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(oldChunks,Collections.singletonList(current)));
        // The deliberately incomplete inventory above is not a valid physical observation.
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(chunks,Collections.singletonList(current)));
        assertFalse(graph.path("sourceQualityAccepted").asBoolean());assertEquals("unknown",graph.path("legalClosure").asText());
        String exact=JsonUtils.write(current);SourceDocument stored=JsonUtils.read(exact,SourceDocument.class);NormalSourceCandidateGraph.capture(stored);assertEquals(exact,JsonUtils.write(stored));
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(VettingCorpus.chunks(Collections.singletonList(stored)),Collections.singletonList(stored)));
        coverage=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(current.getParseCoverageJson());((com.fasterxml.jackson.databind.node.ObjectNode)coverage.path("nativeDocxTables")).put("status","changed-only-inventory");current.setParseCoverageJson(JsonUtils.write(coverage));
        assertEquals(oldHash,VettingCorpus.sourceHash(current));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(current));
    }
    static SourceDocument ordinaryWordSource(){
        SourceDocument source=source();List<DocumentBlock> blocks=new ArrayList<>();int[] sizes={1,1,2,2,1,2,2,2};
        String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";
        for(int row=0;row<sizes.length;row++){
            DocumentBlock block=new DocumentBlock();block.setId("word-row-"+row);block.setKind("table_row");block.setSource("docx");block.setLocation("body/0/table-row/"+row);block.setWordStructureVersion("docx-numbering-symbol-v1");
            List<String> values=new ArrayList<>();List<DocumentBlock.WordCell> cells=new ArrayList<>();
            for(int col=0;col<sizes[row];col++){
                String text=row==1||row==4?"":row==3&&col==0?String.join("",Collections.nCopies(1800,"x")):"Actual row "+row+" cell "+col;
                values.add(text);DocumentBlock.WordCell cell=new DocumentBlock.WordCell();cell.setLocation(block.getLocation()+"/cell/"+col);cell.setTableLocation("word/document[0]/body[0]/tbl[0]");cell.setRowIndex(row);cell.setCellIndex(col);cell.setGridColumn(col);cell.setColumnSpan(sizes[row]==1?2:1);cell.setGridSpanRaw(sizes[row]==1?"2":null);cell.setRowSpan(1);cell.setText(text);cell.setVerticalMerge(row==4?"continue":"none");cell.setHorizontalMerge("none");cell.setTopologyStatus("observed_native");cells.add(cell);
            }
            block.setCells(values);block.setWordCells(cells);block.setText(String.join(" | ",values));
            block.setSourcePosition(JsonUtils.mapper().createObjectNode().put("kind","docx_native").put("sourceSha256",source.getSourceSha256()).put("partName","word/document.xml").put("partSha256",VettingCorpus.hash("authored DOCX XML part"))
                    .put("nodePath","/"+ns+"document[1]/"+ns+"body[1]/"+ns+"tbl[1]/"+ns+"tr["+(row+1)+"]"));blocks.add(block);
        }
        source.setStructuredContentJson(JsonUtils.write(blocks));source.setTextContent(blocks.stream().map(DocumentBlock::getText).collect(java.util.stream.Collectors.joining("\n")));NormalSourceCandidateGraph.capture(source);return source;
    }

    @Test void optionalCellProvenanceChangesNewGenerationAndStrictFingerprintButLegacyReplayStaysExact() throws Exception {
        SourceDocument legacy=ordinaryWordSource();String legacyJson=JsonUtils.write(legacy),oldHash=VettingCorpus.sourceHash(legacy),oldGraph=legacy.getNormalCandidateJson();
        List<VettingCorpus.Chunk> oldChunks=VettingCorpus.chunks(Collections.singletonList(legacy));String oldChunksJson=JsonUtils.write(oldChunks);
        java.lang.reflect.Method fingerprint=VettingRetrievalClient.class.getDeclaredMethod("corpusFingerprint",List.class);fingerprint.setAccessible(true);Object oldFingerprint=fingerprint.invoke(null,oldChunks);
        SourceDocument replay=JsonUtils.read(legacyJson,SourceDocument.class);
        assertEquals(legacy.getStructuredContentJson(),JsonUtils.write(VettingCorpus.blocks(replay)));
        for(DocumentBlock block:VettingCorpus.blocks(replay))for(DocumentBlock.WordCell cell:block.getWordCells())assertFalse(JsonUtils.mapper().valueToTree(cell).has("sourcePosition"));
        NormalSourceCandidateGraph.capture(replay);assertEquals(oldGraph,replay.getNormalCandidateJson());
        assertEquals(oldChunksJson,JsonUtils.write(VettingCorpus.chunks(Collections.singletonList(replay))));
        assertEquals(oldFingerprint,fingerprint.invoke(null,VettingCorpus.chunks(Collections.singletonList(replay))));
        SourceDocument current=JsonUtils.read(legacyJson,SourceDocument.class);List<DocumentBlock> blocks=VettingCorpus.blocks(current);String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";
        for(DocumentBlock block:blocks)for(DocumentBlock.WordCell cell:block.getWordCells()){
            String row=block.getSourcePosition().path("nodePath").asText(),table=row.substring(0,row.lastIndexOf("/"+ns+"tr["));
            com.fasterxml.jackson.databind.node.ObjectNode position=block.getSourcePosition().deepCopy();
            position.put("nodePath",row+"/"+ns+"tc["+(cell.getCellIndex()+1)+"]");position.put("rowNodePath",row);position.put("tableNodePath",table);cell.setSourcePosition(position);
        }
        current.setStructuredContentJson(JsonUtils.write(blocks));assertNotEquals(oldHash,VettingCorpus.sourceHash(current));
        assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(current));
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.requireCurrentDocuments(Collections.singletonList(legacy),Collections.singletonList(current)));
        assertEquals("CANONICAL_SOURCE_STALE",assertThrows(com.consense.service.sources.CanonicalEvidenceException.class,()->VettingSourceMaterial.project(oldChunks,Collections.singletonList(current))).getMessage());
        NormalSourceCandidateGraph.capture(current);JsonNode graph=NormalSourceCandidateGraph.requireBound(current);
        assertNotEquals(JsonUtils.parse(oldGraph).path("generationSha256"),graph.path("generationSha256"));
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(current));assertEquals(oldChunks.size(),chunks.size());
        for(int i=0;i<chunks.size();i++){assertEquals(oldChunks.get(i).getContent(),chunks.get(i).getContent());assertNotEquals(oldChunks.get(i).getId(),chunks.get(i).getId());}
        Object strict=fingerprint.invoke(null,chunks);assertNotEquals(oldFingerprint,strict);assertEquals(strict,fingerprint.invoke(null,VettingCorpus.chunks(Collections.singletonList(current))));
        JsonNode model=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks,Collections.singletonList(current)));
        JsonNode expected=blocks.get(0).getWordCells().get(0).getSourcePosition();assertTrue(graph.findValues("sourcePosition").contains(expected));assertTrue(model.findValues("sourcePosition").contains(expected));
        assertEquals(model,JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks,Collections.singletonList(current))));
        com.fasterxml.jackson.databind.node.ArrayNode oldFields=JsonUtils.mapper().valueToTree(blocks);for(JsonNode block:oldFields)for(JsonNode cell:block.path("wordCells"))((com.fasterxml.jackson.databind.node.ObjectNode)cell).remove("sourcePosition");
        assertEquals(JsonUtils.parse(legacy.getStructuredContentJson()),oldFields);
        ((com.fasterxml.jackson.databind.node.ObjectNode)blocks.get(0).getWordCells().get(0).getSourcePosition()).put("rowNodePath","changed-only-cell-provenance");current.setStructuredContentJson(JsonUtils.write(blocks));
        assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(current));
    }
    @Test void actualNestedCellParentPathReachesGraphAndModelWhileWholeTableSelectionStaysUnknown() throws Exception {
        byte[] bytes;
        try(org.apache.poi.xwpf.usermodel.XWPFDocument doc=new org.apache.poi.xwpf.usermodel.XWPFDocument()){
            org.apache.poi.xwpf.usermodel.XWPFTable table=doc.createTable(1,2);table.getRow(0).getCell(0).setText("Outer text");
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl nested=table.getRow(0).getCell(0).getCTTc().addNewTbl();
            nested.addNewTblGrid().addNewGridCol().setW(java.math.BigInteger.valueOf(1000));
            nested.addNewTr().addNewTc().addNewP().addNewR().addNewT().setStringValue("Nested text");table.getRow(0).getCell(0).getCTTc().addNewP();
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();doc.write(out);bytes=out.toByteArray();
        }
        com.consense.ocr.OcrClient ocr=org.mockito.Mockito.mock(com.consense.ocr.OcrClient.class);
        com.consense.document.DocumentParser.ParsedDocument parsed=new com.consense.document.DocumentParser(new com.consense.config.ConsenseProperties(),ocr).parse("authored-nested.docx",bytes);
        SourceDocument source=source();source.setSourceSha256(parsed.getSourceSha256());source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));
        source=JsonUtils.read(JsonUtils.write(source),SourceDocument.class);NormalSourceCandidateGraph.capture(source);
        DocumentBlock row=VettingCorpus.blocks(source).get(0);assertEquals(3,row.getWordCells().size());JsonNode outer=row.getWordCells().get(0).getSourcePosition(),inner=row.getWordCells().get(1).getSourcePosition();
        assertEquals(outer.path("nodePath"),inner.path("parentCellNodePath"));assertNotEquals(outer.path("tableNodePath"),inner.path("tableNodePath"));
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source),model=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(VettingCorpus.chunks(Collections.singletonList(source)),Collections.singletonList(source)));
        assertTrue(graph.findValues("sourcePosition").contains(inner));assertTrue(model.findValues("sourcePosition").contains(inner));
        int checked=0;for(JsonNode excerpt:model)for(JsonNode unit:excerpt.path("observedSourceUnits").path("units"))if(unit.has("tableGroup")){
            checked++;assertEquals("ordinary_docx_native_table_tree",unit.path("kind").asText());assertEquals(2,unit.path("tableGroup").path("tables").size());
            assertTrue(unit.path("parts").isEmpty());assertTrue(unit.path("sourceSpans").isEmpty());assertFalse(unit.path("unknowns").isEmpty());
        }
        assertTrue(checked>0,"Actual nested carrier must now deliver complete physical context with semantic unknowns");
        assertFalse(graph.path("sourceQualityAccepted").asBoolean());assertEquals("unknown",graph.path("legalClosure").asText());org.mockito.Mockito.verifyNoInteractions(ocr);
    }
    @Test void modelOnlyWordTablePreservesPhysicalBlanksMergesAndReferencesWithoutChangingChunks(){
        SourceDocument source=ordinaryWordSource();List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        List<VettingCorpus.Chunk> selected=new ArrayList<>();for(VettingCorpus.Chunk c:chunks)if(c.getParts().stream().anyMatch(p->"word-row-3".equals(p.getBlockId())))selected.add(c);
        assertTrue(selected.size()>1);String before=JsonUtils.write(chunks),saved=source.getNormalCandidateJson();
        JsonNode old=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(selected));assertFalse(old.toString().contains("observedSourceUnits"));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(selected,Collections.singletonList(source)));
        JsonNode first=material.get(0).path("observedSourceUnits"),unit=first.path("units").get(0);assertEquals(8,unit.path("parts").size());assertEquals(8,unit.path("requiredMemberBlockIds").size());
        int cells=0;for(JsonNode row:unit.path("tableGroup").path("rows"))cells+=row.path("wordCells").size();assertEquals(13,cells);
        assertEquals("",unit.path("parts").get(1).path("text").asText());assertEquals("continue",unit.path("parts").get(4).path("wordCells").get(0).path("verticalMerge").asText());assertEquals("2",unit.path("parts").get(1).path("wordCells").get(0).path("gridSpanRaw").asText());
        assertEquals("unknown",first.path("sourceDeclaration").path("legalClosure").asText());assertFalse(first.path("sourceDeclaration").path("sourceQualityAccepted").asBoolean());
        assertEquals(unit.path("modelUnitAddress"),material.get(1).path("observedSourceUnits").path("units").get(0).path("modelUnitRef"));assertFalse(material.get(1).path("observedSourceUnits").has("sourceDeclaration"));
        for(int i=0;i<selected.size();i++){com.fasterxml.jackson.databind.node.ObjectNode copy=material.get(i).deepCopy();copy.remove("observedSourceUnits");assertEquals(old.get(i),copy);}
        assertEquals(material,JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(selected,Collections.singletonList(source))),"New envelope creates a fresh Request and complete first declaration");
        assertEquals(before,JsonUtils.write(chunks));assertEquals(saved,source.getNormalCandidateJson());
    }
    @Test void ordinaryModelBindingRejectsContradictoryBytesCellsRangesAndMissingContext(){
        SourceDocument source=ordinaryWordSource();List<VettingCorpus.Chunk> all=VettingCorpus.chunks(Collections.singletonList(source));VettingCorpus.Chunk original=all.get(0);
        assertEquals("unknown",JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(original),Collections.emptyList())).get(0).path("observedSourceUnits").path("status").asText());
        for(int kind=0;kind<4;kind++){
            VettingCorpus.Chunk c=JsonUtils.read(JsonUtils.write(original),VettingCorpus.Chunk.class);
            if(kind==0)c.setOriginalSourceSha256(VettingCorpus.hash("different bytes"));
            if(kind==1)c.getParts().get(0).getWordCells().get(0).setText("contradictory selected cell");
            if(kind==2)c.getParts().get(0).setText("different selected text");
            if(kind==3)((com.fasterxml.jackson.databind.node.ObjectNode)c.getParts().get(0).getSourcePosition()).put("partSha256",VettingCorpus.hash("different part"));
            assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Collections.singletonList(c),Collections.singletonList(source)));
        }
        assertThrows(IllegalArgumentException.class,()->new OerModelMaterial.Request(Arrays.asList(source,source)));
    }
    @Test void ordinaryGenerationGuardRejectsCoverageGraphRoutingAndStructureChanges(){
        SourceDocument source=ordinaryWordSource();
        for(int kind=0;kind<5;kind++){
            SourceDocument fresh=JsonUtils.read(JsonUtils.write(source),SourceDocument.class);
            if(kind==0)fresh.setParseCoverageJson("{\"complete\":false,\"newUnknown\":true}");
            if(kind==1)fresh.setNormalCandidateJson(source.getNormalCandidateJson()+" ");
            if(kind==2)fresh.setReviewRole("standard");
            if(kind==3)fresh.setSourceSha256(VettingCorpus.hash("changed bytes"));
            if(kind==4)fresh.setStructuredContentJson(source.getStructuredContentJson()+" ");
            assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.requireCurrentDocuments(Collections.singletonList(source),Collections.singletonList(fresh)));
        }
        assertDoesNotThrow(()->VettingSourceMaterial.requireCurrentDocuments(Collections.singletonList(source),Collections.singletonList(JsonUtils.read(JsonUtils.write(source),SourceDocument.class))));
        SourceDocument another=JsonUtils.read(JsonUtils.write(source),SourceDocument.class);another.setId(72L);
        assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.requireCurrentDocuments(Arrays.asList(source,source),Arrays.asList(source,another)));
    }
    @Test void missingOriginalByteIdentityNeverExpandsARecapturedWordTable(){
        SourceDocument source=ordinaryWordSource();source.setSourceSha256("");List<DocumentBlock> rows=VettingCorpus.blocks(source);
        for(DocumentBlock row:rows)((com.fasterxml.jackson.databind.node.ObjectNode)row.getSourcePosition()).put("sourceSha256","");
        source.setStructuredContentJson(JsonUtils.write(rows));NormalSourceCandidateGraph.capture(source);String preserved=JsonUtils.write(source);
        List<VettingCorpus.Chunk> seed=Collections.singletonList(VettingCorpus.chunks(Collections.singletonList(source)).get(0));
        JsonNode structure=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(seed,Collections.singletonList(source))).get(0).path("observedSourceUnits");
        assertEquals(0,structure.path("units").size());assertFalse(structure.path("unknowns").isEmpty());assertEquals(preserved,JsonUtils.write(source));
    }
    @Test void nestedOrConflictingPhysicalTablesRemainUnknownAndNeverInventGridSlots(){
        SourceDocument source=ordinaryWordSource();List<DocumentBlock> rows=VettingCorpus.blocks(source);DocumentBlock.WordCell nested=JsonUtils.read(JsonUtils.write(rows.get(0).getWordCells().get(0)),DocumentBlock.WordCell.class);nested.setTableLocation(nested.getTableLocation()+"/tr[0]/tc[0]/tbl[0]");rows.get(0).getWordCells().add(nested);
        source.setStructuredContentJson(JsonUtils.write(rows));NormalSourceCandidateGraph.capture(source);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks,Collections.singletonList(source)));
        for(JsonNode excerpt:material)if(excerpt.has("observedSourceUnits")){assertEquals(0,excerpt.path("observedSourceUnits").path("units").size());assertFalse(excerpt.path("observedSourceUnits").path("unknowns").isEmpty());}
    }
    @Test void contradictoryUnselectedPhysicalRowBlocksTheWholeTableWithoutRewritingObservations(){
        for(int kind=0;kind<4;kind++){
            SourceDocument source=ordinaryWordSource();List<DocumentBlock> rows=VettingCorpus.blocks(source);DocumentBlock blank=rows.get(1);
            if(kind==0)blank.getWordCells().get(0).setText("contradicts actual empty cell");
            if(kind==1)blank.setCells(Collections.emptyList());
            if(kind==2)blank.getWordCells().get(0).setCellIndex(2);
            if(kind==3)blank.getWordCells().get(0).setLocation("wrong physical cell");
            source.setStructuredContentJson(JsonUtils.write(rows));NormalSourceCandidateGraph.capture(source);String preserved=JsonUtils.write(source);
            List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));List<VettingCorpus.Chunk> seed=chunks.stream().filter(c->c.getParts().stream().anyMatch(p->"word-row-3".equals(p.getBlockId()))).limit(1).collect(java.util.stream.Collectors.toList());
            assertEquals(1,seed.size());JsonNode structure=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(seed,Collections.singletonList(source))).get(0).path("observedSourceUnits");
            assertEquals(0,structure.path("units").size());assertFalse(structure.path("unknowns").isEmpty());assertEquals(preserved,JsonUtils.write(source));
        }
    }
    @Test void savedNativeSectionReferencesReachWholeAndPartialOrdinaryMaterialWithoutSelectingOtherMembers() throws Exception {
        byte[] bytes;
        try(org.apache.poi.xwpf.usermodel.XWPFDocument doc=new org.apache.poi.xwpf.usermodel.XWPFDocument()){
            doc.createParagraph().createRun().setText("Notes "+String.join("",Collections.nCopies(1800,"x")));
            doc.createParagraph().getCTP().addNewPPr().addNewSectPr();
            org.apache.poi.xwpf.usermodel.XWPFTable table=doc.createTable(1,2);table.getRow(0).getCell(0).setText("table member");
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();doc.write(out);bytes=out.toByteArray();
        }
        com.consense.ocr.OcrClient ocr=org.mockito.Mockito.mock(com.consense.ocr.OcrClient.class);
        com.consense.document.DocumentParser.ParsedDocument parsed=new com.consense.document.DocumentParser(new com.consense.config.ConsenseProperties(),ocr).parse("authored.docx",bytes);
        org.mockito.Mockito.verifyNoInteractions(ocr);SourceDocument source=source();source.setSourceSha256(parsed.getSourceSha256());
        source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));
        source=JsonUtils.read(JsonUtils.write(source),SourceDocument.class);assertEquals(parsed.getCoverage().getNativeDocxSections(),JsonUtils.parse(source.getParseCoverageJson()).path("nativeDocxSections"));
        NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source),reference=parsed.getBlocks().get(0).getReadingContext().path("nativeDocxSection");
        assertEquals(reference,graph.path("units").get(0).path("parts").get(0).path("readingContext").path("nativeDocxSection"));
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertTrue(chunks.size()>1);boolean partial=false;
        for(JsonNode material:JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks)))for(JsonNode unit:material.path("candidateStructure").path("units"))for(JsonNode part:unit.path("parts")){
            JsonNode ref=part.path("readingContext").path("nativeDocxSection");assertTrue(ref.path("sectionId").isTextual());assertFalse(ref.has("retainedBlockIds"));
            if(part.path("blockId").asText().equals(parsed.getBlocks().get(0).getId())){
                assertEquals(reference,ref);assertFalse(unit.path("text").asText().contains("table member"));
                if("selected_source_range_only".equals(unit.path("closureStatus").asText()))partial=true;
            }
        }
        assertTrue(partial,"Actual selected paragraph slices keep only their small section source reference");
        com.fasterxml.jackson.databind.node.ObjectNode coverage=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(source.getParseCoverageJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)coverage.path("nativeDocxSections").path("sections").get(0)).put("sectionId","changed-source-container");
        source.setParseCoverageJson(JsonUtils.write(coverage));SourceDocument changed=source;assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(changed));
    }
    @Test void emptyEffectiveCpuGapRetainsActualGeometryAndUnverifiedTextInOrdinaryMaterial(){
        SourceDocument source=source();DocumentBlock old=new DocumentBlock();old.setId("old-correct");old.setLocation("pdf-page/1/old");
        old.setKind("text");old.setSource("ocr");old.setPageNo(1);old.setText("OLD_CORRECT_BODY");
        DocumentBlock gap=new DocumentBlock();gap.setId("cpu-gap");gap.setLocation("pdf-page/1/visual-region/cpu-gap");
        gap.setKind("unassigned_text_line");gap.setSource("ocr");gap.setPageNo(1);gap.setText("");gap.setOriginalText("MERGED_UNVERIFIED_TEXT");
        gap.setBbox(new double[]{.1,.3,.2,.05});
        JsonNode region=JsonUtils.parse("{\"id\":\"cpu-gap\",\"kind\":\"unassigned_text_line\",\"text\":\"\",\"originalText\":\"MERGED_UNVERIFIED_TEXT\",\"bbox\":[10,60,20,10],\"polygon\":[[10,60],[30,60],[30,70],[10,70]],\"channel\":\"rapidocr\",\"semanticStatus\":\"unverified\",\"geometryStatus\":\"observed_input_frame\",\"readingOrder\":{\"declared\":null,\"outputSequence\":0,\"status\":\"detection_sequence_unverified\"},\"unknowns\":[\"writing state and text remain unknown\"],\"assetRefs\":[{\"kind\":\"actual_cpu_line_observation\",\"lineId\":\"actual-det-0\",\"terminalStage\":\"below_text_score\",\"recognitionConfidence\":0.2,\"observation\":{\"kind\":\"complete_cpu_line_stages\",\"jsonPointer\":\"/stages/lineStageProbe/pages/0/lines/0\",\"artifactDescriptor\":{\"schema\":\"consense.artifact-descriptor.v1\",\"sha256\":\"fixture-observation-sha\",\"identityScope\":\"actual_complete_cpu_ocr_line_stages\"}}}]}");
        gap.setVisualRegion(region);gap.setVisualFrame(JsonUtils.parse("{\"imageSha256\":\"fixture-raster-sha\",\"width\":100,\"height\":200,\"coordinateUnit\":\"pixel\",\"bboxFormat\":\"xywh\"}"));
        source.setTextContent(old.getText());source.setStructuredContentJson(JsonUtils.write(Arrays.asList(old,gap)));NormalSourceCandidateGraph.capture(source);
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source);assertEquals("OLD_CORRECT_BODY",graph.path("units").get(0).path("text").asText());
        JsonNode part=graph.path("units").get(1).path("parts").get(0);assertEquals("",graph.path("units").get(1).path("text").asText());
        assertEquals(region.path("polygon"),part.path("visualRegion").path("polygon"));assertEquals(region.path("originalText"),part.path("visualRegion").path("originalText"));
        assertEquals("unknown",part.path("readingContext").path("valueState").asText("unknown"));
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        assertTrue(chunks.stream().noneMatch(c->c.getContent().contains("MERGED_UNVERIFIED_TEXT")));
        String material=JsonUtils.write(VettingSourceMaterial.project(chunks));assertTrue(material.contains("MERGED_UNVERIFIED_TEXT"));
        assertTrue(material.contains("fixture-observation-sha"));assertTrue(material.contains("below_text_score"));
        assertFalse(graph.path("sourceQualityAccepted").asBoolean());
    }
    @Test void persistedWordNumberingBindsToTheSameCompleteJsonGeneration(){
        SourceDocument source=numberedSource();NormalSourceCandidateGraph.capture(source);String raw=source.getNormalCandidateJson();JsonNode expected=JsonUtils.parse(raw);
        JsonNode current=assertDoesNotThrow(()->NormalSourceCandidateGraph.requireBound(source));
        assertEquals(expected,current);assertEquals(raw,source.getNormalCandidateJson());
        assertEquals(1,current.path("units").get(0).path("parts").get(0).path("wordNumbering").get(0).path("value").asLong());
        assertFalse(current.path("humanConfirmed").asBoolean());assertFalse(current.path("sourceQualityAccepted").asBoolean());
    }
    @Test void numberStorageNormalizationStillRejectsBooleanStringFloatingAndChangedIntegerValues(){
        SourceDocument source=numberedSource();NormalSourceCandidateGraph.capture(source);String raw=source.getNormalCandidateJson();
        for(JsonNode replacement:Arrays.asList(com.fasterxml.jackson.databind.node.BooleanNode.TRUE,com.fasterxml.jackson.databind.node.TextNode.valueOf("1"),com.fasterxml.jackson.databind.node.DoubleNode.valueOf(1.0),com.fasterxml.jackson.databind.node.IntNode.valueOf(2))){
            com.fasterxml.jackson.databind.node.ObjectNode changed=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(raw);
            ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("units").get(0).path("parts").get(0).path("wordNumbering").get(0)).set("value",replacement);
            source.setNormalCandidateJson(JsonUtils.write(changed));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source),replacement.getClass().getSimpleName());
        }
    }
    private SourceDocument numberedSource(){
        SourceDocument source=source();DocumentBlock block=new DocumentBlock();block.setId("body/0/paragraph");block.setLocation("body/0/paragraph");block.setSource("native");block.setText("1. AUTHORED numbered obligation");
        DocumentBlock.WordNumbering numbering=new DocumentBlock.WordNumbering();numbering.setValue(1L);numbering.setLabel("1.");numbering.setAppliedToEffectiveText(true);numbering.setResolutionStatus("observed");block.setWordNumbering(Collections.singletonList(numbering));
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));return source;
    }
    @Test void zeroTextWordMathAndImageRemainWholeUnknownObjectsWithoutRawXmlInModelInput() {
        SourceDocument source=source();DocumentBlock object=new DocumentBlock();object.setId("body:7:paragraph");
        object.setLocation("body/7/paragraph");object.setKind("paragraph");object.setSource("docx");object.setWordStructureVersion("docx-numbering-symbol-v1");
        object.setWordWarnings(Collections.singletonList("Unrendered native Word math; source semantics unknown"));
        object.setWordNativeObjects(Collections.singletonList(JsonUtils.parse("{\"kind\":\"math\",\"location\":\"body/7/paragraph/node/0\",\"status\":\"native_ooxml_unrendered\",\"semanticStatus\":\"unknown\",\"nativeXml\":\"RAW_OMML_DO_NOT_SEND_IN_PROMPT\",\"sourcePart\":\"/word/document.xml\",\"relationshipRefs\":[{\"relationshipId\":\"rId4\",\"targetPart\":\"/word/media/image1.png\",\"sha256\":\"bound-original-asset-sha\",\"status\":\"original_package_asset_bytes_bound\"}]}")));
        source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(object)));source.setTextContent("");NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertEquals(1,chunks.size());assertEquals("",chunks.get(0).getContent());
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks)).get(0).path("candidateStructure");
        JsonNode unit=material.path("units").get(0);assertEquals("word_native_object",unit.path("kind").asText());
        assertEquals("complete_actual_structural_unit",unit.path("closureStatus").asText());
        String wire=material.toString();assertTrue(wire.contains("native_ooxml_unrendered"));assertTrue(wire.contains("bound-original-asset-sha"));
        assertTrue(wire.contains("semantics_unknown"));assertFalse(wire.contains("RAW_OMML_DO_NOT_SEND_IN_PROMPT"));
        assertTrue(source.getStructuredContentJson().contains("RAW_OMML_DO_NOT_SEND_IN_PROMPT"));
    }
    @Test void completeAlternativeParserPagesRemainOneWholeCandidateWithoutMixingOrRawAudit() {
        SourceDocument source=source();
        com.fasterxml.jackson.databind.node.ObjectNode pageGroup=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse("{\"physicalPageNo\":1,\"scope\":\"whole_page\",\"selectionStatus\":\"candidate_unverified\",\"selectedParser\":null,\"policySha256\":null,\"sourceQualityAccepted\":false,\"adoptionStatus\":\"candidate_unverified\",\"reason\":\"no approved generic policy\",\"candidates\":[{\"parser\":\"paddleocr-vl\",\"runId\":\"paddle-run\",\"pageId\":\"paddle-page\",\"features\":{\"hasTables\":true,\"allTableStructuresObserved\":false,\"allGeometryBound\":false,\"completeRawCapture\":true},\"blocks\":[{\"id\":\"paddle-table\",\"kind\":\"table\",\"text\":\"PADDLE_WHOLE_BODY\",\"table\":{\"cells\":[{\"text\":\"\",\"rowSpan\":2}]},\"relations\":[{\"type\":\"note\",\"qualifiersComplete\":\"unknown\"}],\"unknowns\":[\"blank meaning\"]}],\"page\":{\"frame\":{\"sourceSha256\":\"bound\",\"status\":\"unknown\"},\"unknowns\":[\"control state\"],\"rawPayload\":\"DO_NOT_SEND_PAGE_RAW_AUDIT\"}},{\"parser\":\"mineru\",\"runId\":\"mineru-run\",\"pageId\":\"mineru-page\",\"features\":{\"hasTables\":false,\"allTableStructuresObserved\":false,\"allGeometryBound\":true,\"completeRawCapture\":true},\"blocks\":[{\"id\":\"mineru-control\",\"kind\":\"checkbox\",\"text\":\"MINERU_WHOLE_BODY\",\"fields\":[{\"state\":\"unknown\"}],\"unknowns\":[\"whole page scope\"]}],\"page\":{\"frame\":{\"status\":\"bound\"},\"unknowns\":[\"whole page scope\"],\"softwareProof\":\"DO_NOT_SEND_PAGE_SOFTWARE_AUDIT\"}}],\"dualEvidenceRef\":{\"relativePath\":\"DO_NOT_SEND_PAGE_CAS_AUDIT\"}}");
        pageGroup.set("source",JsonUtils.mapper().createObjectNode().put("sourceSha256",source.getSourceSha256()).put("sourceFilename","authored.pdf").put("pageCount",1));
        ((com.fasterxml.jackson.databind.node.ObjectNode)pageGroup.path("candidates").get(0).path("page")).set("mapping",JsonUtils.parse("{\"physicalPageNo\":1,\"providerInputPageNo\":1,\"providerBboxFrame\":\"normalized_1000\",\"originalPageTransform\":{\"kind\":\"affine\",\"matrix\":[0.5,0,0,0,0.75,0]}}"));
        DocumentBlock group=new DocumentBlock();group.setId("whole-page-1");group.setLocation("pdf-page/1/visual-parser-group");group.setPageNo(1);group.setKind("visual_parser_group");
        group.setSource("parser_page_group_candidate");group.setText("DO_NOT_EMBED_GROUP_CANDIDATE");group.setParserPageGroup(pageGroup);
        source.setTextContent("");source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(group)));NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertEquals(1,chunks.size(),"A zero-text whole parser page must remain selectable source structure");
        assertEquals("",chunks.get(0).getContent());assertTrue(JsonUtils.write(chunks).contains("DO_NOT_SEND_PAGE_RAW_AUDIT"),"Original full alternatives and audits remain in saved source Parts");
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));JsonNode unit=material.get(0).path("candidateStructure").path("units").get(0);
        assertEquals("whole_parser_page",unit.path("kind").asText());assertEquals("complete_actual_structural_unit",unit.path("closureStatus").asText());
        JsonNode projected=unit.path("parts").get(0).path("parserPageGroup");assertEquals(2,projected.path("candidates").size());assertTrue(projected.path("selectedParser").isNull());assertFalse(projected.path("sourceQualityAccepted").asBoolean());
        for(int i=0;i<2;i++)assertEquals(pageGroup.path("candidates").get(i).path("blocks"),projected.path("candidates").get(i).path("blocks"),"Both complete alternatives keep their own blocks, blanks, relationships and unknowns");
        assertEquals(pageGroup.path("candidates").get(1).path("page").path("unknowns"),projected.path("candidates").get(1).path("page").path("unknowns"));
        assertEquals(pageGroup.path("candidates").get(0).path("page").path("mapping"),projected.path("candidates").get(0).path("page").path("mapping"),"Whole alternatives retain their own provider-to-original page frame mapping");
        assertTrue(material.toString().contains("PADDLE_WHOLE_BODY"));assertTrue(material.toString().contains("MINERU_WHOLE_BODY"));
        for(String audit:Arrays.asList("DO_NOT_SEND_PAGE_RAW_AUDIT","DO_NOT_SEND_PAGE_SOFTWARE_AUDIT","DO_NOT_SEND_PAGE_CAS_AUDIT","DO_NOT_EMBED_GROUP_CANDIDATE"))assertFalse(material.toString().contains(audit));
        JsonNode method=material.get(0).path("selectedPartExtractionObservations").get(0);assertEquals("whole_independent_parser_page_candidates",method.path("parserPageGroupStatus").asText());
        assertEquals(projected,method.path("parserPageGroup"));
    }
    @Test void nativeWholeMethodCandidatesRemainIndependentZeroTextStructureWithoutRawGlyphAudit() {
        SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);
        for(String method:Arrays.asList("native-regions","docling")){
            DocumentBlock candidate=new DocumentBlock();candidate.setId("native-method-"+method);candidate.setLocation("pdf-page/1/native-method/"+method);candidate.setPageNo(1);
            candidate.setSource("native_table_candidate");candidate.setText("DO_NOT_EMBED_METHOD_CANDIDATE");
            candidate.setNativeTableCandidate(JsonUtils.parse("{\"method\":\""+method+"\",\"provider\":\"authored-cpu\",\"status\":\"candidate\",\"physicalPageNo\":1,\"frame\":{\"coordinateUnit\":\"pt\"},\"candidate\":{\"schema\":\"authored-independent-table\",\"tables\":[{\"cells\":[{\"text\":\"\",\"rowSpan\":2}],\"glyphs\":[{\"text\":\"DO_NOT_SEND_RAW_GLYPH_AUDIT\"}]}],\"logicalTables\":[],\"conditionCandidates\":[{\"status\":\"unknown\"}],\"unassignedSourceGlyphIds\":[\"actual-unassigned\"],\"completeSourceGroupAccepted\":false,\"quality\":{\"status\":\"unverified\"}},\"bundleRef\":{\"rawPayload\":\"DO_NOT_SEND_CAS_AUDIT\"}}"));
            blocks.add(candidate);
        }
        source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        assertTrue(chunks.stream().noneMatch(chunk->chunk.getContent().contains("DO_NOT_EMBED_METHOD_CANDIDATE")));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));assertFalse(material.toString().contains("DO_NOT_SEND_RAW_GLYPH_AUDIT"));assertFalse(material.toString().contains("DO_NOT_SEND_CAS_AUDIT"));
        JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());assertEquals(3,graph.path("units").size());
        assertEquals("native_table_method",graph.path("units").get(1).path("kind").asText());assertEquals("",graph.path("units").get(1).path("text").asText());
        assertEquals("native-regions",graph.path("units").get(1).path("parts").get(0).path("nativeTableCandidate").path("method").asText());
        assertEquals("docling",graph.path("units").get(2).path("parts").get(0).path("nativeTableCandidate").path("method").asText());
        assertTrue(JsonUtils.write(chunks).contains("DO_NOT_SEND_RAW_GLYPH_AUDIT"),"Original method audit is retained outside compact model material");
    }
    @Test void ordinaryCandidateRetainsWholeNativeTableAndUnreadScopeWithoutAcceptedClaims() {
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);
        JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());
        assertEquals("consense.canonical-candidate.v1",graph.path("schema").asText());
        assertEquals("candidate_unverified",graph.path("adoptionStatus").asText());
        assertFalse(graph.path("humanConfirmed").asBoolean());assertFalse(graph.path("sourceQualityAccepted").asBoolean());
        JsonNode table=graph.path("units").get(0);assertEquals(2,table.path("parts").size());
        assertEquals("native_table",table.path("kind").asText());assertEquals(2,table.path("tableGroup").path("rows").size());
        assertEquals("",table.path("tableGroup").path("rows").get(0).path("cells").get(1).asText());
        assertEquals("unknown",table.path("tableGroup").path("headerMeaning").asText());
        assertTrue(graph.path("unknowns").toString().contains("failed_page"));assertTrue(graph.path("unknowns").toString().contains("needs_review_page"));
        assertFalse(graph.toString().contains("FULL_SOURCE_AUDIT_GLYPH"));
        assertEquals(2,table.path("requiredMemberBlockIds").size());
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));
        assertEquals("candidate_unverified",material.get(0).path("candidateStructure").path("adoptionStatus").asText());
        assertEquals(2,material.get(0).path("candidateStructure").path("units").get(0).path("parts").size());
        assertTrue(material.get(0).path("candidateStructure").path("unknowns").toString().contains("failed_page"));
        assertFalse(material.toString().contains("FULL_SOURCE_AUDIT_GLYPH"));
        for(JsonNode selected:material) {
            assertFalse(selected.path("content").asText().contains("DELETED_UNSELECTED_TEXT"));
            for(JsonNode unit:selected.path("candidateStructure").path("units")) {
                assertFalse(unit.path("text").asText().contains("DELETED_UNSELECTED_TEXT"));
                for(JsonNode part:unit.path("parts")) {
                    assertFalse(part.path("text").asText().contains("DELETED_UNSELECTED_TEXT"));
                    assertEquals("DELETED_UNSELECTED_TEXT",part.path("wordObservation").path("deletedText").asText());
                    ObjectNode effective=(ObjectNode)part.deepCopy();effective.remove("wordObservation");
                    assertFalse(effective.toString().contains("DELETED_UNSELECTED_TEXT"),"Deleted text remains solely in its explicit revision observation");
                }
            }
        }
        assertNull(chunks.get(0).getCanonicalPayload());assertNull(chunks.get(0).getCanonicalEvidence());
    }

    @Test void candidateGenerationCannotAttachAfterOriginalOrCoverageChanges() {
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);
        String generation=source.getNormalCandidateJson();
        source.setParseCoverageJson("{\"failedPages\":[2,3]}");
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(VettingCorpus.chunks(Collections.singletonList(source))));
        assertEquals("generation_binding_unknown",material.get(0).path("candidateStructure").path("status").asText());
        assertEquals(0,material.get(0).path("candidateStructure").path("units").size());
        assertEquals(generation,source.getNormalCandidateJson(),"Consumer never rewrites a prior generation or grants acceptance");
    }

    @Test void legacySourceRemainsUsableWithoutInventingCandidateAcceptance() {
        SourceDocument source=source();List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));
        assertTrue(material.get(0).path("content").asText().contains("Actual obligation"));
        assertTrue(material.get(0).path("candidateStructure").isNull());
    }

    @Test void partialParagraphCandidateKeepsSelectedEffectiveRangeWithoutFullParentText() {
        SourceDocument source=source();DocumentBlock block=new DocumentBlock();block.setId("long:paragraph");block.setLocation("body/0/paragraph");block.setSource("native");
        block.setText(String.join("",Collections.nCopies(1600,"x"))+"UNSELECTED_PARENT_TAIL");block.setOriginalText("UNSELECTED_REVISION");block.setDeletedText("UNSELECTED_DELETION");block.setWordStructureVersion("docx-numbering-symbol-v1");
        source.setTextContent(block.getText());source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertEquals(2,chunks.size());
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(chunks.get(0))));
        assertFalse(material.toString().contains("UNSELECTED_PARENT_TAIL"));assertFalse(material.toString().contains("UNSELECTED_REVISION"));assertFalse(material.toString().contains("UNSELECTED_DELETION"));
        assertEquals("selected_source_range_only",material.get(0).path("candidateStructure").path("units").get(0).path("closureStatus").asText());
    }

    @Test void conflictedVisualTextRetainsUnknownStructureWithoutEnteringEmbeddingBody() {
        SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);DocumentBlock blocked=new DocumentBlock();blocked.setId("blocked-region");blocked.setLocation("pdf-page/1/visual-region/blocked");
        blocked.setPageNo(1);blocked.setSource("ocr_candidate");blocked.setText("CONFLICTED_VISUAL_LITERAL");blocked.setVisualRegion(JsonUtils.parse("{\"id\":\"blocked\",\"text\":\"CONFLICTED_VISUAL_LITERAL\",\"kind\":\"table\",\"table\":{\"cells\":[{\"text\":\"CONFLICTED_VISUAL_LITERAL\"}]}}"));
        blocked.setReadingContext(JsonUtils.parse("{\"adoptionStatus\":\"blocked_native_visual_conflict\",\"nativeIds\":[\"native-observation\"]}"));blocks.add(blocked);source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertTrue(chunks.stream().noneMatch(c->c.getContent().contains("CONFLICTED_VISUAL_LITERAL")));
        JsonNode graph=JsonUtils.parse(source.getNormalCandidateJson());JsonNode visual=graph.path("units").get(1);
        assertEquals("",visual.path("text").asText());assertTrue(visual.path("parts").get(0).path("visualRegion").toString().contains("CONFLICTED_VISUAL_LITERAL"));
        assertEquals("blocked_native_visual_conflict",visual.path("parts").get(0).path("readingContext").path("adoptionStatus").asText());
        assertTrue(visual.path("unknowns").toString().contains("native_visual_conflict"));
    }

    @Test void oneSplitTableSeedActuallyExpandsCompleteCurrentTableMembers() {
        SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);
        DocumentBlock row=blocks.get(0);row.setCells(Arrays.asList(String.join("",Collections.nCopies(1800,"x")),""));row.setText(String.join(" | ",row.getCells()));
        row.getWordCells().get(0).setText(row.getCells().get(0));source.setStructuredContentJson(JsonUtils.write(blocks));source.setTextContent(row.getText()+"\n"+blocks.get(1).getText());
        NormalSourceCandidateGraph.capture(source);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertTrue(chunks.size()>1);
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(chunks.get(0)))).get(0).path("candidateStructure");
        JsonNode unit=material.path("units").get(0);assertEquals("complete_actual_structural_unit",unit.path("closureStatus").asText());assertEquals(2,unit.path("parts").size());
        assertTrue(unit.path("text").asText().contains("Second member"));assertEquals(1800,unit.path("tableGroup").path("rows").get(0).path("cells").get(0).asText().length());
        assertEquals("candidate_unverified",unit.path("adoptionStatus").asText());
    }

    @Test void mutatedClaimsStayUnknownAndOversizedWholeUnitsRemainCompleteOrdinaryMaterial() {
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);
        com.fasterxml.jackson.databind.node.ObjectNode saved=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(source.getNormalCandidateJson());saved.put("humanConfirmed",true);source.setNormalCandidateJson(JsonUtils.write(saved));
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(VettingCorpus.chunks(Collections.singletonList(source)))).get(0).path("candidateStructure");
        assertEquals("generation_binding_unknown",material.path("status").asText());assertFalse(material.path("humanConfirmed").asBoolean());assertEquals(0,material.path("units").size());
        List<DocumentBlock> blocks=VettingCorpus.blocks(source);DocumentBlock row=blocks.get(0);String literal=String.join("",Collections.nCopies(70000,"x"))+"TAIL_LITERAL_OUTSIDE_SELECTED_BODY";
        row.setCells(Arrays.asList(literal,""));row.setText(String.join(" | ",row.getCells()));row.getWordCells().get(0).setText(literal);row.getWordCells().get(0).setRowSpan(2);
        source.setTextContent(row.getText()+"\n"+blocks.get(1).getText());source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);
        String savedSource=JsonUtils.write(source),savedGraph=source.getNormalCandidateJson();JsonNode complete=NormalSourceCandidateGraph.requireBound(source).path("units").get(0);
        List<VettingCorpus.Chunk> chunks=assertDoesNotThrow(()->VettingCorpus.chunks(Collections.singletonList(source)));
        JsonNode selected=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(chunks.get(0)))).get(0).path("candidateStructure"),whole=selected.path("units").get(0);
        assertFalse(chunks.get(0).getContent().contains("TAIL_LITERAL_OUTSIDE_SELECTED_BODY"));assertTrue(JsonUtils.write(whole).length()>120000);
        com.fasterxml.jackson.databind.node.ObjectNode expected=(com.fasterxml.jackson.databind.node.ObjectNode)complete.deepCopy();expected.put("closureStatus","complete_actual_structural_unit");assertEquals(expected,whole);
        assertEquals(2,whole.path("requiredMemberBlockIds").size());assertEquals(2,whole.path("parts").size());assertEquals(literal,whole.path("tableGroup").path("rows").get(0).path("cells").get(0).asText());
        assertEquals("",whole.path("tableGroup").path("rows").get(0).path("cells").get(1).asText());assertEquals(2,whole.path("parts").get(0).path("wordCells").get(0).path("rowSpan").asInt());
        assertEquals(JsonUtils.parse(savedGraph).path("unknowns"),selected.path("unknowns"));assertFalse(selected.path("sourceQualityAccepted").asBoolean());
        assertEquals(savedSource,JsonUtils.write(source));assertEquals(savedGraph,source.getNormalCandidateJson());assertEquals(JsonUtils.parse(savedGraph),NormalSourceCandidateGraph.requireBound(source));
    }

    private static SourceDocument source() {
        SourceDocument source=new SourceDocument();source.setId(71L);source.setProjectId("authored-candidate");source.setFileName("authored.docx");
        source.setReviewRole("tender");source.setDocumentScope("package");source.setFileKey("SCC");source.setSourceSha256(VettingCorpus.hash("authored original bytes"));
        source.setParseStatus("PARTIAL");source.setParseCoverageJson("{\"complete\":false,\"totalPages\":2,\"failedPages\":[2],\"needsReviewPages\":[1],\"visualCoverage\":[{\"rawPayload\":\"FULL_SOURCE_AUDIT_GLYPH\"}]}");
        List<DocumentBlock> blocks=new ArrayList<>();
        for(int i=0;i<2;i++) {
            DocumentBlock row=new DocumentBlock();row.setId("body:0:table-row:"+i);row.setLocation("body/0/table-row/"+i);row.setSource("native");row.setKind("table_row");
            row.setCells(Arrays.asList(i==0?"Actual obligation":"Second member", ""));row.setText(String.join(" | ",row.getCells()));
            row.setDeletedText("DELETED_UNSELECTED_TEXT");row.setWordStructureVersion("docx-numbering-symbol-v1");
            DocumentBlock.WordCell cell=new DocumentBlock.WordCell();cell.setTableLocation("body/0");cell.setRowIndex(i);cell.setCellIndex(0);cell.setLocation(row.getLocation()+"/cell/0");
            cell.setGridColumn(0);cell.setColumnSpan(1);cell.setRowSpan(1);cell.setTopologyStatus("observed_native");cell.setText(row.getCells().get(0));row.setWordCells(Collections.singletonList(cell));blocks.add(row);
        }
        source.setTextContent("Actual obligation | \nSecond member | ");source.setStructuredContentJson(JsonUtils.write(blocks));return source;
    }
}
