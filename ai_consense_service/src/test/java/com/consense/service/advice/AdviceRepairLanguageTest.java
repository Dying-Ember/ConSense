package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Constructor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A synchronous repair belongs to the same answer, so it cannot change its validated opening language. */
class AdviceRepairLanguageTest {
    @Test void anEnglishFirstAnswerToAChineseQuestionIsRejectedBeforeItCanPublish() throws Exception {
        Object publication=publication("请解释已上传的条款");begin(publication,false);
        IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(publication,"finish",answer("en"),"请解释已上传的条款",false));
        assertTrue(rejected.getMessage().contains("language"));assertTrue(((List<?>)ReflectionTestUtils.getField(publication,"accepted")).isEmpty());
        begin(publication,true);assertTrue(((String)ReflectionTestUtils.invokeMethod(publication,"repairInstruction")).contains("Chinese"));
        assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(publication,"finish",answer("zhHans"),"请解释已上传的条款",false));
    }
    @Test void aRepairCannotSwitchAChineseAnswerToEnglishAfterTheUnpublishedUnitsAreCleared() throws Exception {
        Object publication=publication("请解释已上传的条款");begin(publication,false);
        ReflectionTestUtils.invokeMethod(publication,"accept",opening("zhHans"));
        begin(publication,true);
        String repair=ReflectionTestUtils.invokeMethod(publication,"repairInstruction");assertTrue(repair.contains("zhHans"),repair);
        IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(publication,"finish",answer("en"),"请解释已上传的条款",false));
        assertTrue(rejected.getMessage().contains("language"),rejected.getMessage());
    }
    @Test void anExplicitEnglishAnswerKeepsEnglishAcrossRepairWithoutGuessingFromChineseCharacters() throws Exception {
        Object publication=publication("请用英文回答这个问题");begin(publication,false);
        ReflectionTestUtils.invokeMethod(publication,"accept",opening("en"));begin(publication,true);
        ReflectionTestUtils.invokeMethod(publication,"finish",answer("en"),"请用英文回答这个问题",false);
        String repair=ReflectionTestUtils.invokeMethod(publication,"repairInstruction");assertTrue(repair.contains("en"));
    }
    @Test void anExplicitTraditionalChineseAnswerRejectsOtherScripts() throws Exception {
        String question="Explain this clause; reply in Traditional Chinese.";Object publication=publication(question);begin(publication,false);
        assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(publication,"finish",answer("zhHans"),question,false));
        begin(publication,true);assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(publication,"finish",answer("zhHant"),question,false));
    }
    private void begin(Object publication,boolean repair){ReflectionTestUtils.invokeMethod(publication,"beginAttempt",false,repair);}
    private AdvisoryHarness.Section opening(String language){return JsonUtils.mapper().convertValue(record(language).path("sections").get(0),AdvisoryHarness.Section.class);}
    private AdviceService.AnswerRecord answer(String language){return JsonUtils.mapper().convertValue(record(language),AdviceService.AnswerRecord.class);}
    private JsonNode record(String language){
        JsonNode result=JsonUtils.parse(AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"Supported clause [1]\",\"citations\":[\"[1]\"]}"));
        for(JsonNode section:result.path("sections"))((com.fasterxml.jackson.databind.node.ObjectNode)section).put("language",language);return result;
    }
    private Object publication(String question)throws Exception {
        AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);
        Class<?> context=Arrays.stream(AdviceService.class.getDeclaredClasses()).filter(type->type.getSimpleName().equals("QuestionContext")).findFirst().get();
        Constructor<?> questionConstructor=context.getDeclaredConstructor(String.class,List.class);questionConstructor.setAccessible(true);
        Object questionContext=questionConstructor.newInstance(question,Collections.emptyList());
        Class<?> type=Arrays.stream(AdviceService.class.getDeclaredClasses()).filter(item->item.getSimpleName().equals("Publication")).findFirst().get();
        Constructor<?> constructor=type.getDeclaredConstructor(AdviceService.class,List.class,boolean.class,AdviceProgress.class,Runnable.class,context);constructor.setAccessible(true);
        CitationVO source=new CitationVO();source.setEvidenceId("[1]");source.setSourceRole("tender");
        return constructor.newInstance(service,Collections.singletonList(source),false,AdviceProgress.NONE,(Runnable)()->{},questionContext);
    }
}
