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
}
