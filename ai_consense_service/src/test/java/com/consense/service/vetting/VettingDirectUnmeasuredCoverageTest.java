package com.consense.service.vetting;

import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.consense.web.dto.VettingDtos.SemanticTopicVO;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingDirectUnmeasuredCoverageTest {
    private SemanticPacketVO packet(String status, boolean dispatched) {
        SemanticPacketVO p=new SemanticPacketVO();
        p.setInputBudgetStatus("direct_unmeasured");p.setStatus(status);p.setActualGatewayCallStarted(dispatched);
        return p;
    }
    private SemanticTopicVO topic(SemanticPacketVO... packets) {
        SemanticTopicVO t=new SemanticTopicVO();t.setStatus("completed");t.setPacketAudits(Arrays.asList(packets));
        return t;
    }
    @Test void successfulDirectCallIsProcessedWithoutClaimingSemanticCompleteness() {
        SemanticTopicVO t=topic(packet("completed",true));
        VettingPacketCoverage.finish(t,null);
        assertEquals("observed_requests_decoded_scope_unknown",t.getAggregateReviewStatus());
        assertFalse(t.isSemanticScopeVerified());
    }
    @Test void failedOrUnsentDirectPacketDoesNotBecomeProcessed() {
        for(SemanticPacketVO other:Arrays.asList(packet("failed",true),packet("not_submitted_budget_unknown",false),packet("completed",false))) {
            SemanticTopicVO t=topic(packet("completed",true),other);
            VettingPacketCoverage.finish(t,null);
            assertEquals("partial",t.getAggregateReviewStatus());
        }
        SemanticTopicVO unsent=topic(packet("completed",false));VettingPacketCoverage.finish(unsent,null);
        assertEquals("not_submitted",unsent.getAggregateReviewStatus());
    }
    @Test void reportDescribesUnmeasuredTokensAndRetainsTransportFailure() throws Exception {
        Method m=VettingReportWriter.class.getDeclaredMethod("readerPacketLimitation",SemanticPacketVO.class,String.class);m.setAccessible(true);
        VettingReportWriter w=new VettingReportWriter();
        String done=(String)m.invoke(w,packet("completed",true),"en");
        assertTrue(done.contains("Input tokens were not counted before the request."));
        assertFalse(done.contains("complete input budget remains unconfirmed"));
        SemanticPacketVO failed=packet("failed",true);failed.setFailureKind("transport_failure");
        String text=(String)m.invoke(w,failed,"en");
        assertTrue(text.contains("model connection or transfer failed"));
        assertTrue(text.contains("Input tokens were not counted before the request."));
    }
}
