package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingChecklistLogicalGroupsTest {
    @Test void promptRequirementPropertyOrderIsExplicitForRestartStableInputBytes() {
        List<String> groupFields=new ArrayList<>(),itemFields=new ArrayList<>();
        JsonUtils.mapper().valueToTree(VettingChecklistCatalog.logicalGroups().get(0)).fieldNames().forEachRemaining(groupFields::add);
        JsonUtils.mapper().valueToTree(VettingChecklistCatalog.items().get(0)).fieldNames().forEachRemaining(itemFields::add);
        assertEquals(Arrays.asList("id","section","clause","sourceItemIds"),groupFields);
        assertEquals(Arrays.asList("id","section","clause","description","templatePage","reviewScope","scopeNote","scopeNoteText"),itemFields);
    }

    @Test void fortyFourRequirementsAndAnnexCoverEveryOriginalRowExactlyOnceInSourceOrder() {
        assertEquals(44, VettingChecklistCatalog.logicalGroups().size());
        List<String> actual = new ArrayList<>();
        for (VettingChecklistCatalog.LogicalGroup group : VettingChecklistCatalog.logicalGroups()) actual.addAll(group.getSourceItemIds());
        assertEquals(67, actual.size());
        actual.addAll(VettingChecklistCatalog.annexItemIds());
        assertEquals(VettingChecklistCatalog.items().stream().map(VettingChecklistCatalog.Item::getId).collect(Collectors.toList()), actual);
        assertEquals(Arrays.asList("APP-I-068", "APP-I-069"), VettingChecklistCatalog.annexItemIds());
    }

    @Test void explicitContinuationAndLetteredSubrequirementsRetainTheirOriginalDetailIdentity() {
        VettingChecklistCatalog.LogicalGroup general = VettingChecklistCatalog.logicalGroups().get(1);
        assertEquals("CK-002", general.getId());
        assertEquals("1. All clauses", general.getClause());
        assertEquals(Arrays.asList("APP-I-002", "APP-I-003", "APP-I-004", "APP-I-005", "APP-I-006"), general.getSourceItemIds());
        assertEquals("1. All clauses (Cont’d)", VettingChecklistCatalog.items().get(5).getClause());
        assertEquals(2, VettingChecklistCatalog.items().get(5).getTemplatePage());
        assertEquals(Arrays.asList("APP-I-021", "APP-I-022", "APP-I-023", "APP-I-024"), VettingChecklistCatalog.logicalGroups().get(16).getSourceItemIds());
        assertEquals("CK-019", VettingChecklistCatalog.logicalGroups().get(18).getId());
        assertEquals(Arrays.asList("APP-I-026"), VettingChecklistCatalog.logicalGroups().get(18).getSourceItemIds());
    }

    @Test void allPublishedCollectionsAreImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> VettingChecklistCatalog.logicalGroups().clear());
        assertThrows(UnsupportedOperationException.class, () -> VettingChecklistCatalog.logicalGroups().get(0).getSourceItemIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> VettingChecklistCatalog.annexItemIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> VettingChecklistCatalog.items().clear());
    }

    @Test void wrongVersionCountsAndUnstableIdentifiersFailClosed() throws Exception {
        ObjectNode version = mapping(); version.put("version", "unapproved");
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(version));
        ObjectNode count = mapping(); ((ArrayNode) count.get("groups")).remove(43);
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(count));
        ObjectNode id = mapping(); group(id, 18).put("id", "CK-020");
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(id));
    }

    @Test void fabricatedDuplicateOrMissingMembersFailClosed() throws Exception {
        ObjectNode fabricated = mapping(); group(fabricated, 0).putArray("sourceItemIds").add("APP-I-999");
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(fabricated));
        ObjectNode duplicate = mapping(); ((ArrayNode) group(duplicate, 1).get("sourceItemIds")).add("APP-I-002");
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(duplicate));
        ObjectNode missing = mapping(); ((ArrayNode) group(missing, 1).get("sourceItemIds")).remove(4);
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(missing));
    }

    @Test void wrongRequirementOrAnnexMembershipAndChangedSourceOrderFailClosed() throws Exception {
        ObjectNode requirement = mapping(); group(requirement, 0).put("clause", "invented clause");
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(requirement));
        ObjectNode annex = mapping(); annex.putArray("annexItemIds").add("APP-I-001").add("APP-I-069");
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(annex));
        ObjectNode reordered = mapping(); group(reordered, 1).putArray("sourceItemIds").add("APP-I-003").add("APP-I-002").add("APP-I-004").add("APP-I-005").add("APP-I-006");
        assertThrows(IllegalStateException.class, () -> VettingChecklistCatalog.parseLogicalMapping(reordered));
    }

    private static ObjectNode mapping() throws Exception {
        try (InputStream stream = VettingChecklistCatalog.class.getResourceAsStream("/vetting/checklist-logical-groups-v1.json")) {
            assertNotNull(stream);
            return (ObjectNode) JsonUtils.mapper().readTree(stream);
        }
    }

    private static ObjectNode group(ObjectNode mapping, int index) {
        return (ObjectNode) mapping.path("groups").get(index);
    }
}
