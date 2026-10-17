package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerNoteReferenceMaterialTest {
    @Test void noteReferenceBindingAndUnknownReasonSurviveAsAnIndependentNestedProjection() {
        for(String status:new String[]{"resolved","unknown"}) {
            JsonNode raw=JsonUtils.parse("[{\"kind\":\"footnoteReference\",\"nativeXml\":\"REFERENCE_XML_DO_NOT_SEND\",\"semanticStatus\":\"unknown\",\"noteReference\":{\"rawId\":\"9\",\"rawAttributes\":{\"{w}customMarkFollows\":\"1\"},\"sourcePosition\":{\"nodePath\":\"reference\"},\"targetSourcePosition\":{\"nodePath\":\"note\"},\"relationship\":{\"id\":\"rId7\",\"rawTarget\":\"footnotes.xml\"}}}]");
            ObjectNode ref=(ObjectNode)raw.get(0).get("noteReference");ref.put("bindingStatus",status);if(status.equals("unknown"))ref.put("reason","duplicate_note_id");
            String before=JsonUtils.write(raw);JsonNode projected=OerModelMaterial.wordNativeObjects(raw);
            assertEquals(ref,projected.get(0).path("noteReference"));assertFalse(JsonUtils.write(projected).contains("REFERENCE_XML_DO_NOT_SEND"));assertTrue(projected.get(0).hasNonNull("nativeXmlSha256"));
            ((ObjectNode)projected.get(0).path("noteReference")).put("rawId","changed");assertEquals(before,JsonUtils.write(raw),"Projection must deep-copy the nested binding metadata");
        }
    }
}
