package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixedVettingProgressiveCatalogTest {
    @ParameterizedTest @ValueSource(strings={"valid","text","manifest","edition","original","selection","project"})
    void selectedCatalogRetainsPriorAuthorityAndRejectsIdentityConflictsWithoutReretrieval(String defect){
        FixedBaselineRetrievalClient client=mock(FixedBaselineRetrievalClient.class);when(client.enabled()).thenReturn(true);
        AtomicReference<JsonNode> selection=new AtomicReference<>(JsonUtils.parse("{\"SL\":\"AUTHORED edition\"}"));
        when(client.projectSelection(anyString())).thenAnswer(call->selection.get());
        ObjectNode first=payload("fixed-a"),second=first.deepCopy();
        if("text".equals(defect))second.put("content","AUTHORED changed source text");
        if("manifest".equals(defect))second.put("fixedBaselineManifestSha256",VettingCorpus.hash("AUTHORED different manifest"));
        if("edition".equals(defect))second.put("fixedBaselineEditionId","AUTHORED other edition");
        if("original".equals(defect))second.put("originalSourceSha256",VettingCorpus.hash("AUTHORED different original"));
        FixedReferenceEvidence firstEvidence=new FixedReferenceEvidence("fixed-a",0.9,first,null,null),secondEvidence=new FixedReferenceEvidence("fixed-a",0.9,second,null,null),added=new FixedReferenceEvidence("fixed-b",0.8,payload("fixed-b"),null,null);
        SearchHit a=firstEvidence.asHit(),again=secondEvidence.asHit(),b=added.asHit();Map<SearchHit,FixedReferenceEvidence> records=new IdentityHashMap<>();records.put(a,firstEvidence);records.put(again,secondEvidence);records.put(b,added);
        when(client.evidence(any())).thenAnswer(call->records.get(call.getArgument(0)));
        when(client.searchForProject(anyString(),eq("vetting"),eq("first"),anyInt())).thenReturn(Collections.singletonList(a));
        when(client.searchForProject(anyString(),eq("vetting"),eq("second"),anyInt())).thenReturn(Arrays.asList(again,b));
        FixedVettingReferences before=FixedVettingReferences.retrieve(client,"authored","first",2),after=FixedVettingReferences.retrieve(client,"project".equals(defect)?"other":"authored","second",2);
        if("selection".equals(defect))selection.set(JsonUtils.parse("{\"SL\":\"CHANGED edition\"}"));
        if(!"valid".equals(defect)){assertThrows(RuntimeException.class,()->FixedVettingReferences.combine(before,after));}
        else{
            FixedVettingReferences combined=FixedVettingReferences.combine(before,after);assertEquals(Arrays.asList("fixed-a","fixed-b"),combined.chunks().stream().map(VettingCorpus.Chunk::getId).collect(java.util.stream.Collectors.toList()));
            combined.assertCurrent(combined.chunks());assertEquals(firstEvidence.pointer(),combined.pointer("fixed-a"));
            assertSame(combined,FixedVettingReferences.combine(FixedVettingReferences.empty(),combined));assertSame(combined,FixedVettingReferences.combine(combined,FixedVettingReferences.empty()));
        }
        verify(client,times(2)).searchForProject(anyString(),eq("vetting"),anyString(),eq(2));
    }
    private static ObjectNode payload(String id){
        ObjectNode value=JsonUtils.mapper().createObjectNode();value.put("id",id).put("role","standard").put("fileKey","SL").put("content","AUTHORED exact standard text "+id)
            .put("originalSourceSha256",VettingCorpus.hash("AUTHORED original")).put("sourceHash",VettingCorpus.hash("AUTHORED parsed")).put("fixedBaselineManifestSha256",VettingCorpus.hash("AUTHORED manifest")).put("fixedBaselineEditionId","AUTHORED edition");
        value.putObject("sourceQuality").put("family","SL");return value;
    }
}
