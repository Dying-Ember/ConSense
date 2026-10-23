package com.consense.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test void unexpectedFailuresArePrivateAndBusinessGuidanceRemainsVisible() {
        GlobalExceptionHandler handler=new GlobalExceptionHandler();
        ApiResponse<Void> unexpected=handler.handleOther(new javax.persistence.PersistenceException(
                "could not insert: [com.consense.domain.SourceDocument]; SQL insert into source_documents; D:/private/database"));
        assertEquals(5000,unexpected.getCode());
        assertEquals("服务内部错误，请稍后重试；若仍失败，请联系维护人员。",unexpected.getMessage());
        ApiResponse<Void> business=handler.handleBiz(new BizException(4012,"请使用替换模板操作。","template_replacement_required",null));
        assertEquals(4012,business.getCode());assertEquals("请使用替换模板操作。",business.getMessage());
        assertEquals("template_replacement_required",business.getReason());
    }
}
