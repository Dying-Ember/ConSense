package com.consense.web;

import com.consense.common.BizException;
import com.consense.service.drafting.DraftAutomationService;
import com.consense.service.drafting.DraftTemplateGraphService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftAutomationSourceSelectionContractTest {
    @Test void explicitSourceIdsMustBeAnActualNonemptyStringIdArray(){
        assertEquals(Arrays.asList("2","3"),DraftTemplateGraphController.automationSourceIds(Arrays.asList("2","3")));
        for(Object invalid:Arrays.asList(null,"2",Collections.emptyList(),Arrays.asList("2","2"),Collections.singletonList(2),Collections.singletonList("STD-SCT"),Collections.singletonList("0"),Collections.singletonList(" 2"),Collections.singletonList("9223372036854775808")))assertThrows(BizException.class,()->DraftTemplateGraphController.automationSourceIds(invalid),String.valueOf(invalid));
    }
    @Test void resumeCannotChangeOrRefreezeSourceSelection(){
        DraftAutomationService automation=mock(DraftAutomationService.class);DraftTemplateGraphController controller=new DraftTemplateGraphController(mock(DraftTemplateGraphService.class),automation);Map<String,Object> body=new LinkedHashMap<>();body.put("expectedCurrentRevisionId",null);body.put("expectedDraftRevisionId",null);body.put("sourceIds",Collections.singletonList("3"));assertThrows(BizException.class,()->controller.resumeAutomation("p","run",body));verifyNoInteractions(automation);
    }
}
