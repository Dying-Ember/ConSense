package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeHeadingBoundariesTest.*;

/** Authored software-only evidence tests; no real source, native renderer, DB, services or models. */
class DraftNativeHeadingRemovalAuthorizationTest {
    static final String ACTION="scc-specialist-SCC20.303",OP=ACTION+"-remove-scope";
    static final class Scenario {
        byte[] retained,removed;Map<String,Object> boundary,layout;List<Map<String,Object>> bindings;List<Object> ledger;String name,id;
        Scenario()throws Exception {
            retained=fixture();NativeHeadingFlow flow=allFlows().get(25);name=flow.getBodyBookmark();
            Map<String,Object> registry;try(InputStream in=DraftNativeLayout.class.getResourceAsStream("/drafting/libreoffice-native-layout.json")){registry=JsonUtils.mapper().readValue(in,Map.class);}
            Map<String,Object> source=DraftBusinessRules.list(registry.get("sources")).stream().map(DraftBusinessRules::asMap).filter(r->ORIGINAL_SOURCE_SHA.equals(r.get("sourceSha256"))).findFirst().orElseThrow();
            boundary=DraftBusinessRules.list(source.get("paragraphBoundaries")).stream().map(DraftBusinessRules::asMap).filter(r->Objects.equals("CS"+DraftAdoption.hash("SCC|"+ORIGINAL_SOURCE_SHA+"|"+r.get("bodyId")).substring(0,28),name)).findFirst().orElseThrow();id=String.valueOf(boundary.get("bodyId"));
            removed=packageXml(xml(retained).replace(paragraph(xml(retained),name),""));TemplateIndex index=EDITOR.inspect(removed);bindings=new ArrayList<>();
            for(String slot:Arrays.asList("heading","body")){String sourceId=String.valueOf(boundary.get(slot+"Id")),bookmark="CS"+DraftAdoption.hash("SCC|"+ORIGINAL_SOURCE_SHA+"|"+sourceId).substring(0,28);String text="AUTHORED source endpoint evidence";
                Map<String,Object> row=DraftBusinessRules.map("bindingId",bookmark,"bookmarkName",bookmark,"sourceParagraphId",sourceId,"sourceText",text,"sourceTextHash",DraftAdoption.hash(text),"actionIds",Arrays.asList(ACTION),"appliedActionIds",Arrays.asList(ACTION),"operationIds",Arrays.asList(OP),"applicationStatus","applied","documentEditStatus","applied","locationStatus","body".equals(slot)?"removed":"exact");
                if("heading".equals(slot)){Paragraph p=DraftDocumentBindings.markers(index).get(bookmark).get(0);row.put("resultParagraphId",p.getId());row.put("textHash",DraftAdoption.hash(p.getText()));}bindings.add(row);
            }
            Map<String,Object> target=DraftBusinessRules.map("source",DraftBusinessRules.map("id",id,"sha256",boundary.get("bodySha256"),"paragraphIds",Arrays.asList(id)),"status","REMOVED","generatedId",null,"afterSha256",null);
            Map<String,Object> operation=DraftBusinessRules.map("operationId",OP,"type","REMOVE_NODES","sourceSha256",ORIGINAL_SOURCE_SHA,"generatedSha256",index.getSourceSha256(),"targets",Arrays.asList(target));
            layout=DraftBusinessRules.map("kind","native_layout","sourceSha256",ORIGINAL_SOURCE_SHA,"compiledDocxSha256",index.getSourceSha256(),"docxSha256",index.getSourceSha256());ledger=new ArrayList<>(Arrays.asList(operation,layout));
        }
        Map<String,Object> body(){return bindings.get(1);}Map<String,Object> operation(){return DraftBusinessRules.asMap(ledger.get(0));}
        List<NativeHeadingFlow> select(Map<String,Object> proof){return selectFlows(removed,bindings,ledger,proof);}
    }
    // The baseline adapter calls its actual full-denominator path, so pre-fix RED is semantic rather than a missing-method compile error.
    static List<NativeHeadingFlow> selectFlows(byte[] bytes,List<Map<String,Object>> bindings,List<Object> ledger,Map<String,Object> proof) {
        try{return (List<NativeHeadingFlow>)DraftNativeLayout.class.getDeclaredMethod("activeHeadingFlows",String.class,String.class,TemplateIndex.class,List.class,List.class,Map.class).invoke(null,ORIGINAL_SOURCE_SHA,"libreoffice",EDITOR.inspect(bytes),bindings,ledger,proof);}
        catch(NoSuchMethodException baseline){return DraftNativeLayout.headingFlows(ORIGINAL_SOURCE_SHA,"libreoffice");}
        catch(InvocationTargetException failure){throw (RuntimeException)failure.getCause();}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    static DraftTocFinalizer.Result finish(Scenario scenario,Function<byte[],DraftPdfConversionResult> renderer) {
        try{return (DraftTocFinalizer.Result)DraftTocFinalizer.class.getDeclaredMethod("finish",byte[].class,String.class,List.class,List.class,Function.class).invoke(null,scenario.removed,ORIGINAL_SOURCE_SHA,scenario.bindings,scenario.ledger,renderer);}
        catch(NoSuchMethodException baseline){return DraftTocFinalizer.finish(scenario.removed,ORIGINAL_SOURCE_SHA,renderer);}
        catch(InvocationTargetException failure){throw (RuntimeException)failure.getCause();}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    @Test void aSourceBoundRemovedBodyRetainsTheRegisteredDenominatorAndItsLiveHeading()throws Exception {
        Scenario s=new Scenario();Map<String,Object> proof=new LinkedHashMap<>();List<NativeHeadingFlow> active=s.select(proof);
        assertEquals(239,proof.get("headingFlowRegistered"));assertEquals(239,proof.get("headingFlowDenominator"));assertEquals(238,active.size());assertEquals(1,proof.get("headingFlowExcludedAuthorized"));
        assertFalse(active.stream().anyMatch(f->f.getBodyBookmark().equals(s.name)));assertEquals(1,DraftDocumentBindings.markers(EDITOR.inspect(s.removed)).get(String.valueOf(s.bindings.get(0).get("bookmarkName"))).size());
        assertDoesNotThrow(()->EDITOR.updateNativeHeadingBoundaries(s.removed,DraftPdfConverter.sha256(s.removed),active,Collections.emptySet()));
    }
    @Test void finalizerStillMeasuresTheRetainedPairAndFinalPdfAfterAnAuthorizedRemoval()throws Exception {
        Scenario s=new Scenario();DraftTocFinalizerDynamicHeadingTest.AuthoredRenderer renderer=new DraftTocFinalizerDynamicHeadingTest.AuthoredRenderer(DraftTocFinalizerDynamicHeadingTest.Mode.NO_SPLIT);
        DraftTocFinalizer.Result result=finish(s,renderer);assertEquals("PASS",result.proof.get("status"),String.valueOf(result.proof));assertEquals(2,renderer.inputs.size());assertEquals(239,result.proof.get("headingFlowDenominator"));assertEquals(238,result.proof.get("headingFlowActive"));assertEquals(1,result.proof.get("headingFlowExcludedAuthorized"));assertEquals(238,((List<?>)result.proof.get("finalHeadingPairs")).size());assertEquals(70,result.proof.get("targetDenominator"));assertEquals(result.proof.get("measurementPoints"),result.proof.get("finalPoints"));
    }
    @Test void removedStatusWithoutAnAppliedActionOrRealMatchingOperationCannotAuthorizeExclusion()throws Exception {
        for(int fault=0;fault<10;fault++){Scenario s=new Scenario();switch(fault){case 0:s.body().put("operationIds",Arrays.asList("missing-operation"));break;case 1:s.body().put("appliedActionIds",Collections.emptyList());break;case 2:s.operation().put("sourceSha256","foreign-sha");break;case 3:s.operation().put("generatedSha256",DraftPdfConverter.sha256(s.retained));break;case 4:s.body().put("bindingId","foreign-binding");break;case 5:s.operation().put("type","CLEAR_PARAGRAPH");break;case 6:s.ledger.remove(0);break;case 7:DraftBusinessRules.asMap(DraftBusinessRules.list(s.operation().get("targets")).get(0)).put("status","APPLIED");break;case 8:s.ledger.add(new LinkedHashMap<>(s.operation()));break;default:List<Object> targets=DraftBusinessRules.list(s.operation().get("targets"));s.operation().put("targets",Arrays.asList(targets.get(0),targets.get(0)));}
            assertThrows(IllegalStateException.class,()->s.select(new LinkedHashMap<>()),"fault "+fault);
        }
    }
    @Test void anUndeletedBodyOrADeletedRetainedNotUsedHeadingCannotHideBehindRemovalStatus()throws Exception {
        Scenario s=new Scenario();s.removed=s.retained;s.layout.put("docxSha256",DraftPdfConverter.sha256(s.retained));assertThrows(IllegalStateException.class,()->s.select(new LinkedHashMap<>()));
        Scenario missingHeading=new Scenario();String name=String.valueOf(missingHeading.bindings.get(0).get("bookmarkName"));missingHeading.removed=packageXml(xml(missingHeading.removed).replace(paragraph(xml(missingHeading.removed),name),""));missingHeading.layout.put("docxSha256",DraftPdfConverter.sha256(missingHeading.removed));assertThrows(IllegalStateException.class,()->missingHeading.select(new LinkedHashMap<>()));
    }
    @Test void unknownMissingBookmarksAndRetainedEndpointConflictsRemainStrict()throws Exception {
        Scenario s=new Scenario();String other=allFlows().get(0).getHeadingBookmark();s.removed=packageXml(xml(s.removed).replace(marker(bookmarkId(xml(s.removed),other),other),""));s.layout.put("docxSha256",DraftPdfConverter.sha256(s.removed));assertThrows(IllegalStateException.class,()->s.select(new LinkedHashMap<>()));
        Scenario duplicate=new Scenario();String heading=String.valueOf(duplicate.bindings.get(0).get("bookmarkName"));duplicate.removed=packageXml(xml(duplicate.removed).replace(paragraph(xml(duplicate.removed),heading),paragraph(xml(duplicate.removed),heading)+"<w:p>"+marker(900001,heading)+text("Conflicting live heading")+end(900001)+"</w:p>"));duplicate.layout.put("docxSha256",DraftPdfConverter.sha256(duplicate.removed));assertThrows(IllegalStateException.class,()->duplicate.select(new LinkedHashMap<>()));
    }
}
