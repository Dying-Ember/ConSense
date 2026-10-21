package com.consense.service.advice;

import com.consense.service.prompt.PromptCatalog;
import com.consense.service.drafting.DraftPrompts;
import com.consense.service.vetting.VettingPromptPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AdvisoryPromptDefaultsTest {
    @Test void settingsDefaultsExposeBothCompleteConfirmedCompetitionPolicies() throws Exception {
        String advice;
        try(InputStream input=new ClassPathResource("advice/contract-system-20261014.md").getInputStream()) {
            advice=StreamUtils.copyToString(input,StandardCharsets.UTF_8);
        }
        assertEquals(advice,PromptCatalog.find(PromptCatalog.KEY_ADVICE).defaultSystem);
        assertEquals(advice,DraftPrompts.ADVICE_SYSTEM);
        assertEquals(advice,AdvisoryHarness.POLICY);
        assertEquals(VettingPromptPolicy.text(),PromptCatalog.find(PromptCatalog.KEY_VETTING).defaultSystem);
        assertFalse(DraftPrompts.ADVICE_USER_TEMPLATE.contains("\"answer\""));
        assertEquals(3,DraftPrompts.ADVICE_USER_TEMPLATE.split("%s",-1).length-1);
    }

    @Test void fullDefaultPolicyAppearsOnceAndCustomSupplementCannotReplaceIt() {
        String policy=AdvisoryHarness.POLICY;
        String effective=AdvisoryHarness.system(policy);
        assertEquals(effective.indexOf(policy),effective.lastIndexOf(policy));
        assertTrue(effective.startsWith(policy));
        assertTrue(effective.contains(AdvisoryHarness.CONTRACT));
        String custom=AdvisoryHarness.system("AUTHORED_CUSTOM_INSTRUCTION");
        assertTrue(custom.startsWith(policy));
        assertTrue(custom.contains("AUTHORED_CUSTOM_INSTRUCTION"));
        assertTrue(custom.contains(AdvisoryHarness.CONTRACT));
    }
    @Test void finalSystemRequiresEvidenceForTheRoleOfAnUntypedLiteralWithoutReplacingTheCustomSupplement() {
        String custom="AUTHORED_USER_STYLE keep each supported answer concise";
        String effective=AdvisoryHarness.system(custom);
        for(String clause:new String[]{"如需引用应原样保留并注明含义待核","不得仅凭位数、位置、格式或来源识别标签",
                "日期、合约编号、适用版本或签署／采用状态","来源识别标签不等于已核日期元数据",
                "当前证据的明示文字及上下文","绑定该来源的已核字段元数据",
                "不能把发布日期改作签署日期或据此证明采用","用户陈述与假设保留其来源和状态","局部待核不抹去独立有据分析"})assertTrue(effective.contains(clause),clause);
        assertTrue(effective.startsWith(AdvisoryHarness.POLICY));assertTrue(effective.contains(custom));
        assertTrue(effective.indexOf(custom)<effective.indexOf(AdvisoryHarness.CONTRACT));
        for(String clause:new String[]{"正文与报告用业务语言说明证据状态","解析候选是否核验","项目采用是否确认","不得互相代换","不把解析候选未核说成合同未采用或原文无效","技术定位沿用应用已有的引用详情承载","正式条号未知时称“所引段落／列项”","不能按段落顺序、检索次序或内部路径猜编号","原文引句及其中交叉引用保留原样","初答与修复都须回看用户原问题","本次实际提供的完整原始来源集合","独立有据的规则、触发条件、例外和各自后果","不得用模型先前答案、诊断节选或局部候选替代原始来源","partial 候选只代表局部留存，不证明已经覆盖全题","局部采用缺证只限制受影响的项目结论","不抹去其他可解释机制及其真实边界","不改变依据范围","不虚称读过未提供的原件","不为完整作答强行把 grounded 或检查状态改为通过"})assertTrue(effective.contains(clause),clause);
        String marker="字段含义未确认的裸数字";assertEquals(effective.indexOf(marker),effective.lastIndexOf(marker));
        assertEquals(AdvisoryHarness.POLICY,PromptCatalog.find(PromptCatalog.KEY_ADVICE).defaultSystem);
    }

}
