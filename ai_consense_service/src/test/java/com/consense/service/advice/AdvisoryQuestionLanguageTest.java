package com.consense.service.advice;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class AdvisoryQuestionLanguageTest {
    @Test void naturalSimplifiedQuestionRequiresSimplifiedSectionLanguage() {
        for (String question : new String[]{"请解释这段文字。", "这个约定应怎样解释？"}) {
            assertTrue(AdvisoryHarness.languageMatches(question, "zhHans"));
            assertFalse(AdvisoryHarness.languageMatches(question, "zhHant"), question);
            assertEquals("zhHans", AdvisoryHarness.languageHint(question));
            assertTrue(AdvisoryHarness.languageInstruction(question).contains(": zhHans"));
        }
    }

    @Test void naturalTraditionalQuestionRequiresTraditionalSectionLanguage() {
        for (String question : new String[]{"請解釋這段文字。", "這個約定應怎樣解釋？"}) {
            assertTrue(AdvisoryHarness.languageMatches(question, "zhHant"));
            assertFalse(AdvisoryHarness.languageMatches(question, "zhHans"), question);
            assertEquals("zhHant", AdvisoryHarness.languageHint(question));
            assertTrue(AdvisoryHarness.languageInstruction(question).contains(": zhHant"));
        }
    }

    @Test void quotedSourceScriptDoesNotOverrideTheQuestion() {
        assertFalse(AdvisoryHarness.languageMatches("请解释“請說明”这句话的含义。", "zhHant"));
        assertFalse(AdvisoryHarness.languageMatches("請解釋「请说明」的意思。", "zhHans"));
    }

    @Test void mixedOrIndistinguishableChineseRemainsUndetermined() {
        for (String question : new String[]{"合同如何支付？", "請说明程序。", "后面如何支付？"}) {
            assertTrue(AdvisoryHarness.languageMatches(question, "zhHans"), question);
            assertTrue(AdvisoryHarness.languageMatches(question, "zhHant"), question);
        }
    }

    @Test void explicitLanguageStillOverridesScriptEvidence() {
        assertTrue(AdvisoryHarness.languageMatches("請用简体中文回答", "zhHans"));
        assertFalse(AdvisoryHarness.languageMatches("請用简体中文回答", "zhHant"));
        assertTrue(AdvisoryHarness.languageMatches("请用繁體中文回答", "zhHant"));
        assertFalse(AdvisoryHarness.languageMatches("请用繁體中文回答", "zhHans"));
        assertTrue(AdvisoryHarness.languageMatches("請用英文回答", "en"));
        assertTrue(AdvisoryHarness.languageMatches("Do not answer in English.", "zhHans"));
    }

    @Test void quotedLanguageInstructionsRemainSourceText() {
        assertFalse(AdvisoryHarness.languageMatches("请解释 'please answer in Traditional Chinese' 的含义。", "zhHant"));
        assertFalse(AdvisoryHarness.languageMatches("請解釋 `please answer in Simplified Chinese` 的意思。", "zhHans"));
    }

    @Test void quotedLanguageNamesAreNotScriptEvidenceButExplicitQuotedRequestsStillApply() {
        for (String question : new String[]{"合同“简体中文”如何支付？", "合同「繁體中文」如何支付？"}) {
            assertTrue(AdvisoryHarness.languageMatches(question, "zhHans"), question);
            assertTrue(AdvisoryHarness.languageMatches(question, "zhHant"), question);
        }
        assertTrue(AdvisoryHarness.languageMatches("合同请用‘繁體中文’回答", "zhHant"));
        assertFalse(AdvisoryHarness.languageMatches("合同请用‘繁體中文’回答", "zhHans"));
    }

    @Test void effectiveContractKeepsConditionsAndTriggerSubjectsInsideEachPublishedRuleUnit() {
        String system = AdvisoryHarness.system("AUTHORED supplemental instruction", Collections.emptyList(), "Explain the stated rule.");
        assertTrue(system.startsWith(AdvisoryHarness.POLICY));
        assertTrue(system.contains("governing condition, actual trigger subject, applicability and exceptions in the SAME analysis unit"));
        assertTrue(system.contains("A general scope paragraph or a self-check claiming complete reading does not replace these qualifications"));
        assertTrue(system.contains("A cross-reference does not broaden the source's actual incorporation trigger"));
    }
}
