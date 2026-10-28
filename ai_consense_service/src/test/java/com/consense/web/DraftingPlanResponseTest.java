package com.consense.web;

import com.consense.common.JsonUtils;
import com.consense.service.drafting.DraftingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DraftingPlanResponseTest {
    static Map<String,Object> map(Object... entries){Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)value.put(String.valueOf(entries[i]),entries[i+1]);return value;}
    static Map<String,Object> plan(){
        Map<String,Object> binding=map("status","bound","sourceSha256","original-sha","spans",Collections.singletonList(map("paragraphId","p1","start",0,"end",8,"expectedText","原件 text")));
        Map<String,Object> pending=map("id","pending","action","pending","document","SCT","clause","条款 source","sourceText","条款 source","value",null,"nativeBinding",binding,"target",map("sourceScopeObservation",map("originalEvidence","preserved server-only scope"),"nativeBinding",binding),"inputKeys",Collections.singletonList("choice"),"fieldKeys",Collections.singletonList("derived"),"overrideable",false,"historicalProjectionBlocked",true,"scopeResults",Collections.singletonList(map("itemId","row-1","matches",null)));
        return map("ruleVersion","v1","graphRevisionId","r1","snapshotId","snapshot","savedSnapshotId","saved","evidenceRevision","evidence","preview",false,"reviewStatus","DRAFT","sourceIdentities",Collections.singletonList(map("id",23,"sourceSha256","original-sha")),"inputValues",map("choice","Hardcopy"),"effectiveValues",map("choice","Hardcopy"),"historicalProjectionValues",map("old",map("status","needs_review")),"actions",Arrays.asList(pending,map("id","retain","action","retain","sourceText","retained source","target",Collections.emptyMap()),map("id","legacy","action","pending")),"unresolved",Collections.singletonList(map("id","u1","kind","ConditionUnknown","message",map("en","Unknown remains pending"))));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bothHttpPlanRoutesRemoveOnlyInternalTargetsAndNeverChangeTheServicePlan(boolean preview)throws Exception {
        DraftingService service=mock(DraftingService.class);Map<String,Object> original=plan();String before=JsonUtils.write(original);Map<String,String> patches=Collections.singletonMap("choice","Hardcopy");
        when(service.plan("p",preview?patches:null)).thenReturn(original);MockMvc mvc=MockMvcBuilders.standaloneSetup(new DraftingController(service,null)).build();
        String response=(preview?mvc.perform(post("/api/drafting/p/plan").param("view","browser").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(map("values",patches)))):mvc.perform(get("/api/drafting/p/plan").param("view","browser"))).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode expected=JsonUtils.parse(before);for(JsonNode action:expected.path("actions"))((ObjectNode)action).remove("target");JsonNode actual=JsonUtils.parse(response).path("data");
        assertEquals(expected,actual,"Every other field, source span, unknown state and value must remain exact");assertEquals(before,JsonUtils.write(original),"HTTP projection must not alter the plan used by compilation and rendering");
        verify(service).plan("p",preview?patches:null);verifyNoMoreInteractions(service);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bothHttpPlanRoutesKeepFullResponseUnlessBrowserViewIsExplicit(boolean preview)throws Exception {
        DraftingService service=mock(DraftingService.class);Map<String,Object> original=plan();String before=JsonUtils.write(original);Map<String,String> patches=Collections.singletonMap("choice","Hardcopy");
        when(service.plan("p",preview?patches:null)).thenReturn(original);MockMvc mvc=MockMvcBuilders.standaloneSetup(new DraftingController(service,null)).build();
        for(String view:Arrays.asList(null,"full","Browser")){
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request=preview?post("/api/drafting/p/plan").contentType(MediaType.APPLICATION_JSON).content(JsonUtils.write(map("values",patches))):get("/api/drafting/p/plan");
            if(view!=null)request.param("view",view);
            String response=mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals(JsonUtils.parse(before),JsonUtils.parse(response).path("data"),"Default and other view values keep the complete existing HTTP contract");assertEquals(before,JsonUtils.write(original));
        }
        verify(service,times(3)).plan("p",preview?patches:null);verifyNoMoreInteractions(service);
    }
    @SuppressWarnings("unchecked") @Test void projectionCopiesOnlyTheRootActionListAndActionMaps(){
        DraftingService service=mock(DraftingService.class);Map<String,Object> original=plan();Map<String,Object> response=ReflectionTestUtils.invokeMethod(new DraftingController(service,null),"planResponse",original);
        List<Map<String,Object>> sourceActions=(List<Map<String,Object>>)original.get("actions"),actions=(List<Map<String,Object>>)response.get("actions");
        assertNotSame(original,response);assertNotSame(sourceActions,actions);for(int i=0;i<actions.size();i++){assertNotSame(sourceActions.get(i),actions.get(i));assertFalse(actions.get(i).containsKey("target"));}
        assertSame(sourceActions.get(0).get("nativeBinding"),actions.get(0).get("nativeBinding"));assertSame(sourceActions.get(0).get("scopeResults"),actions.get(0).get("scopeResults"));assertSame(original.get("unresolved"),response.get("unresolved"));assertTrue(sourceActions.get(0).containsKey("target"));
    }
    @Test void planWithoutActionsKeepsItsExistingResponseShape(){
        DraftingService service=mock(DraftingService.class);Map<String,Object> original=map("snapshotId","legacy","unresolved",Collections.emptyList());assertEquals(original,ReflectionTestUtils.invokeMethod(new DraftingController(service,null),"planResponse",original));assertFalse(original.containsKey("actions"));
    }
}
