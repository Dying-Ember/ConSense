package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Application-required text fields remain nonempty under the source whitespace vocabulary. */
class AdvisoryUnicodeWhitespaceBoundaryTest {
    private final List<CitationVO> sources = Collections.singletonList(
            new CitationVO("Authored source", null, "authored anchor", "Authored source text only", 1,
                    "[1]", "42", "authored-source-hash", "tender", true));

    @Test void substantiveAnalysisCannotPublishUnicodeWhitespaceAsItsExplanation() {
        for (String blank : blanks()) {
            AdvisoryHarness.Section section = analysis();
            section.setContent(blank);
            String original = JsonUtils.write(section);
            assertThrows(IllegalArgumentException.class,
                    () -> AdvisoryHarness.validate(section, sources, false, (basis, refs) -> true));
            assertEquals(original, JsonUtils.write(section), "Validation must retain the literal rejected model output");
        }
    }

    @Test void anActionCannotUseUnicodeWhitespaceAsItsRequiredActor() {
        for (String blank : blanks()) {
            AdvisoryHarness.GraphPlan graph = plan();
            graph.getEdges().get(0).setActor(blank);
            assertThrows(IllegalArgumentException.class,
                    () -> AdvisoryHarness.graph(graph, Collections.singletonList("[1]"), sources));
        }
    }

    @Test void aDeadlineCannotUseUnicodeWhitespaceAsItsRequiredStartEvent() {
        for (String blank : blanks()) {
            AdvisoryHarness.GraphPlan graph = plan();
            graph.getEdges().get(0).setPeriod("28 days");
            graph.getEdges().get(0).setStartEvent(blank);
            assertThrows(IllegalArgumentException.class,
                    () -> AdvisoryHarness.graph(graph, Collections.singletonList("[1]"), sources));
        }
    }

    @Test void zeroAndRealChineseTextRemainPresentAndDoNotAlterApplicationPolicy() {
        assertTrue(AdvisoryHarness.text("0"));
        assertTrue(AdvisoryHarness.text("通知送達日"));
        for (String content : Arrays.asList("0", "通知已送達。[1]", "\u3000Supported source explanation [1]\u00a0")) {
            AdvisoryHarness.Section section = analysis();
            section.setContent(content);
            assertDoesNotThrow(() -> AdvisoryHarness.validate(section, sources, false, (basis, refs) -> true));
        }
        AdvisoryHarness.GraphPlan graph = plan();
        graph.getEdges().get(0).setActor("承包商");
        graph.getEdges().get(0).setPeriod("0 days");
        graph.getEdges().get(0).setStartEvent("通知送達日");
        assertDoesNotThrow(() -> AdvisoryHarness.graph(graph, Collections.singletonList("[1]"), sources));
        assertEquals("ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470", AdvisoryHarness.POLICY_SHA);
    }

    private static List<String> blanks() {
        return Arrays.asList("\u00a0", "\u2007", "\u202f", "\u3000", "\t\u00a0\n\u3000");
    }

    private static AdvisoryHarness.Section analysis() {
        return JsonUtils.mapper().convertValue(JsonUtils.parse(AdviceBudgetFixture.contract(
                "{\"grounded\":true,\"answer\":\"Supported source explanation [1]\",\"citations\":[\"[1]\"]}"))
                .path("sections").get(1), AdvisoryHarness.Section.class);
    }

    private static AdvisoryHarness.GraphPlan plan() {
        return JsonUtils.read("{\"nodes\":[{\"id\":\"s\",\"label\":\"\",\"kind\":\"start\",\"citations\":[]},"
                + "{\"id\":\"done\",\"label\":\"Documented outcome\",\"kind\":\"end\",\"citations\":[\"[1]\"]}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"done\",\"condition\":\"\",\"actor\":\"Contractor\","
                + "\"action\":\"give notice\",\"period\":\"\",\"startEvent\":\"\",\"citations\":[\"[1]\"]}]}",
                AdvisoryHarness.GraphPlan.class);
    }
}
