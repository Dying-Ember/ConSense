package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.web.DraftTemplateGraphController;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftTemplateGraphControllerTest {
    @Test void saveRequiresBothExplicitRevisionExpectationsIncludingNullOnFirstSave(){
        DraftTemplateGraphService service=mock(DraftTemplateGraphService.class);DraftTemplateGraphController controller=new DraftTemplateGraphController(service);Map<String,Object> body=new LinkedHashMap<>();body.put("graph",Collections.emptyMap());body.put("expectedCurrentRevisionId",null);
        assertThrows(BizException.class,()->controller.save("p",body));verifyNoInteractions(service);body.put("expectedDraftRevisionId",null);controller.save("p",body);verify(service).save("p",Collections.emptyMap(),null,null,true);
    }
}
