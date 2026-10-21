package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.drafting.DraftPrompts;
import com.consense.service.prompt.PromptCatalog;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VettingPromptPolicyTest {
    @Test void newBusinessDetailsHaveAnExplicitFingerprintSerializationOrder() {
        BusinessDetailsVO details=new BusinessDetailsVO("Reference validity","The quoted provision is deleted.",Collections.emptyList(),"review");
        List<String> fields=new ArrayList<>();JsonUtils.mapper().valueToTree(details).fieldNames().forEachRemaining(fields::add);
        assertEquals(Arrays.asList("subDimension","factBasis","pendingFacts","suggestedAction","checklistItemIds"),fields);
    }

    @Test void exactVersionedResourceIsTheSharedDefaultAndRuntimePolicy() throws Exception {
        byte[] bytes;
        try(InputStream stream=getClass().getResourceAsStream(VettingPromptPolicy.RESOURCE)) { assertNotNull(stream);bytes=org.springframework.util.StreamUtils.copyToByteArray(stream); }
        assertEquals(new String(bytes,StandardCharsets.UTF_8),VettingPromptPolicy.text());
        StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));
        assertEquals(sha.toString(),VettingPromptPolicy.sha256());
        assertEquals(VettingPromptPolicy.text(),DraftPrompts.VETTING_SYSTEM);
        assertEquals(VettingPromptPolicy.text(),PromptCatalog.find(PromptCatalog.KEY_VETTING).defaultSystem);
        assertTrue(VettingPromptPolicy.text().contains("R1"));assertTrue(VettingPromptPolicy.text().contains("R12"));
        assertTrue(VettingSemanticReview.policyContractSha256().matches("[a-f0-9]{64}"));
    }

    @Test void sevenSchemaClassesKeepTheirCodesAndRejectUnknownOrLegacyModelWireTypes() {
        JsonNode choices=VettingOutputSchema.forChunkIds(Collections.singleton("source")).path("items").path("properties").path("type").path("enum");
        List<String> actual=new ArrayList<>();choices.forEach(value->actual.add(value.asText()));
        assertEquals(Arrays.asList("reference","conflict","language","risk","gap","substantive","requirement"),actual);
        assertEquals(Arrays.asList("REF","CONF","LANG","RISK","GAP","SUB","REQ"),
                Arrays.asList(VettingIssueType.values()).stream().map(VettingIssueType::getCode).collect(java.util.stream.Collectors.toList()));
        for(String value:Arrays.asList(null,"","unknown","REF","f2-x"))assertThrows(IllegalArgumentException.class,()->VettingIssueType.require(value));
        assertEquals(VettingIssueType.REFERENCE,VettingIssueType.require("f2-iv"));
        assertEquals(VettingIssueType.LANGUAGE,VettingIssueType.require("f2-v"));
        assertEquals("f2-v",VettingIssueType.storedType("f2-v"));
        for(String group:Arrays.asList("gap","substantive","requirement"))assertEquals(group,VettingIssueType.storedType(group));
        FindingVO finding=new FindingVO();finding.setGroup("substantive");assertEquals("SUB",finding.getIssueTypeCode());
        finding.setGroup("unknown");assertNull(finding.getIssueTypeCode());
    }

    @Test void legacyResultsDoNotAcquireTheCurrentPolicyAndNewResultsRetainTheirCapturedPolicy() {
        RunResultVO old=JsonUtils.read("{\"total\":0,\"model\":\"legacy\"}",RunResultVO.class);assertNull(old.getPromptPolicy());
        RunResultVO current=new RunResultVO(0,new MetricsVO(0,0,0,0,0,0),Collections.emptyList(),"fixture");
        Map<String,String> saved=new LinkedHashMap<>(VettingPromptPolicy.metadata());current.setPromptPolicy(saved);
        RunResultVO restored=JsonUtils.read(JsonUtils.write(current),RunResultVO.class);assertEquals(saved,restored.getPromptPolicy());
        assertEquals(0,restored.getMetrics().getGap());assertEquals(0,restored.getMetrics().getSubstantive());assertEquals(0,restored.getMetrics().getRequirement());
    }
    @Test void finalPacketSystemsRetainCustomTextAndRequireEvidenceForUntypedLiteralRoles()throws Exception {
        VettingSemanticReview review=new VettingSemanticReview(null,new com.consense.config.ConsenseProperties(),null);
        java.lang.reflect.Method system=VettingSemanticReview.class.getDeclaredMethod("effectiveSystem",String.class,boolean.class);system.setAccessible(true);
        String custom="AUTHORED_USER_STYLE keep supported review comments concise";
        try(VettingSemanticReview.SupplementScope ignored=review.bindSupplement(custom)){
            for(boolean projectReference:new boolean[]{false,true}){
                String effective=(String)system.invoke(review,"en",projectReference);
                for(String clause:new String[]{"如需引用应原样保留并注明含义待核","不得仅凭位数、位置、格式或来源识别标签",
                        "日期、合约编号、适用版本或签署／采用状态","来源识别标签不等于已核日期元数据",
                        "当前证据的明示文字及上下文","绑定该来源的已核字段元数据",
                        "不能把发布日期改作签署日期或据此证明采用","用户陈述与假设保留其来源和状态","局部待核不抹去独立有据分析"})assertTrue(effective.contains(clause),clause);
                assertTrue(effective.contains(custom));assertTrue(effective.indexOf(custom)<effective.indexOf(VettingPromptPolicy.text()));
                String marker="字段含义未确认的裸数字";assertEquals(effective.indexOf(marker),effective.lastIndexOf(marker));
                assertEquals(VettingPromptPolicy.text(),PromptCatalog.find(PromptCatalog.KEY_VETTING).defaultSystem);
            }
        }
    }

}
