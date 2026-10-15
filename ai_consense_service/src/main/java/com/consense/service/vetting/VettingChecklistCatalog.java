package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Generic requirements transcribed from Appendix I, not the example project's answers. */
public final class VettingChecklistCatalog {
    private static final List<Item> ITEMS = load();
    private static final LogicalMapping LOGICAL = loadLogicalMapping();
    private VettingChecklistCatalog() { }
    public static List<Item> items() { return ITEMS; }
    public static List<LogicalGroup> logicalGroups() { return LOGICAL.groups; }
    public static List<String> annexItemIds() { return LOGICAL.annexItemIds; }
    private static List<Item> load() {
        try (InputStream stream = VettingChecklistCatalog.class.getResourceAsStream("/vetting/checklist-template.json")) {
            if (stream == null) throw new IllegalStateException("Vetting checklist catalog is unavailable");
            List<Item> items = new ArrayList<>();
            for (JsonNode n : JsonUtils.mapper().readTree(stream))
                items.add(new Item(n.path("id").asText(), n.path("section").asText(), n.path("clause").asText(),
                        n.path("description").asText(), n.path("templatePage").asInt()));
            return Collections.unmodifiableList(items);
        } catch (Exception e) { throw new IllegalStateException("Cannot load vetting checklist catalog", e); }
    }

    private static LogicalMapping loadLogicalMapping() {
        try (InputStream stream = VettingChecklistCatalog.class.getResourceAsStream("/vetting/checklist-logical-groups-v1.json")) {
            require(stream != null, "Logical checklist mapping is unavailable");
            return parseLogicalMapping(JsonUtils.mapper().readTree(stream));
        } catch (Exception e) { throw new IllegalStateException("Cannot load logical vetting checklist mapping", e); }
    }

    static LogicalMapping parseLogicalMapping(JsonNode root) {
        require(root != null && root.isObject(), "Logical checklist mapping must be an object");
        require("vetting-checklist-logical-groups-v1".equals(root.path("version").asText()), "Unsupported logical checklist mapping version");
        require("checklist-template.json".equals(root.path("sourceTemplate").asText()), "Unexpected logical checklist source template");
        require(root.path("groups").isArray() && root.path("groups").size() == 44, "Exactly 44 logical checklist groups are required");
        require(root.path("annexItemIds").isArray() && root.path("annexItemIds").size() == 2, "Exactly two supplementary annex examples are required");
        Map<String, Item> byId = new LinkedHashMap<>();
        for (Item item : ITEMS) byId.put(item.getId(), item);
        require(byId.size() == 69 && ITEMS.size() == 69, "The original 69 checklist detail rows must be retained");
        Set<String> seen = new LinkedHashSet<>();
        Set<String> groupKeys = new HashSet<>();
        List<LogicalGroup> groups = new ArrayList<>();
        for (JsonNode node : root.path("groups")) {
            String id = text(node, "id"), section = text(node, "section"), clause = text(node, "clause");
            require(String.format(Locale.ROOT, "CK-%03d", groups.size() + 1).equals(id), "Logical checklist IDs must retain their source order");
            require(groupKeys.add(section + "\n" + clause), "A logical checklist requirement cannot be split into duplicate groups");
            require(node.path("sourceItemIds").isArray() && !node.path("sourceItemIds").isEmpty(), "Each logical group requires existing detail rows");
            List<String> members = new ArrayList<>();
            for (JsonNode member : node.path("sourceItemIds")) {
                String memberId = member.isTextual() ? member.textValue() : "";
                Item item = byId.get(memberId);
                require(item != null && seen.add(memberId), "Logical checklist members must be existing, unique APP-I IDs");
                require(!item.getSection().startsWith("ANNEX 1") && section.equals(item.getSection())
                        && clause.equals(item.getClause().replace(" (Cont’d)", "")), "Logical checklist membership must match the original requirement");
                members.add(memberId);
            }
            groups.add(new LogicalGroup(id, section, clause, members));
        }
        require(groups.get(18).getSourceItemIds().equals(Collections.singletonList("APP-I-026")), "CK-019 must retain the PRE.B6.590 requirement");
        List<String> annex = new ArrayList<>();
        for (JsonNode node : root.path("annexItemIds")) {
            String id = node.isTextual() ? node.textValue() : "";
            Item item = byId.get(id);
            require(item != null && item.getSection().startsWith("ANNEX 1") && seen.add(id), "Annex members must be existing, unique supplementary examples");
            annex.add(id);
        }
        require(new ArrayList<>(seen).equals(new ArrayList<>(byId.keySet())), "Logical groups and annex must cover all 69 rows once in their original order");
        return new LogicalMapping(groups, annex);
    }

    private static String text(JsonNode node, String field) {
        require(node != null && node.path(field).isTextual() && !node.path(field).textValue().trim().isEmpty(), "Logical checklist field is missing: " + field);
        return node.path(field).textValue();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    static final class LogicalMapping {
        final List<LogicalGroup> groups;
        final List<String> annexItemIds;
        LogicalMapping(List<LogicalGroup> groups, List<String> annexItemIds) {
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.annexItemIds = Collections.unmodifiableList(new ArrayList<>(annexItemIds));
        }
    }

    @com.fasterxml.jackson.annotation.JsonPropertyOrder({"id","section","clause","sourceItemIds"})
    public static final class LogicalGroup {
        private final String id, section, clause;
        private final List<String> sourceItemIds;
        private LogicalGroup(String id, String section, String clause, List<String> sourceItemIds) {
            this.id = id; this.section = section; this.clause = clause;
            this.sourceItemIds = Collections.unmodifiableList(new ArrayList<>(sourceItemIds));
        }
        public String getId() { return id; }
        public String getSection() { return section; }
        public String getClause() { return clause; }
        public List<String> getSourceItemIds() { return sourceItemIds; }
    }

    @com.fasterxml.jackson.annotation.JsonPropertyOrder({"id","section","clause","description","templatePage","reviewScope","scopeNote","scopeNoteText"})
    public static final class Item {
        private final String id, section, clause, description;
        private final int templatePage;
        public Item(String id, String section, String clause, String description, int templatePage) {
            this.id=id; this.section=section; this.clause=clause; this.description=description; this.templatePage=templatePage;
        }
        public String getId() { return id; }
        public String getSection() { return section; }
        public String getClause() { return clause; }
        public String getDescription() { return description; }
        public int getTemplatePage() { return templatePage; }
        /** Sample structure is retained; it does not expand the competition's admitted sources. */
        public String getReviewScope() {
            if (Arrays.asList("APP-I-046","APP-I-051","APP-I-058","APP-I-059","APP-I-060","APP-I-061",
                    "APP-I-062","APP-I-063","APP-I-064","APP-I-065","APP-I-066","APP-I-067","APP-I-068","APP-I-069").contains(id))
                return "pricing_document_excluded";
            if (id.equals("APP-I-007") || id.equals("APP-I-027") || id.equals("APP-I-048") || id.equals("APP-I-055"))
                return "mixed_contract_pricing";
            return "in_scope";
        }
        public String getScopeNote() { return getScopeNoteText().getEn(); }
        public LocalizedText getScopeNoteText() {
            if ("pricing_document_excluded".equals(getReviewScope())) return LocalizedText.of(
                    "样板保留此计价文件核对项仅用于保持结构。计价文件审查不属于比赛范围，系统不会自动审查此项；人工判定须另行说明依据。",
                    "樣板保留此計價文件核對項僅用於保持結構。計價文件審查不屬於比賽範圍，系統不會自動審查此項；人工判定須另行說明依據。",
                    "The sample retains this pricing-document countercheck for structure only. Pricing-document review is outside the competition scope. No automated assessment is performed; any manual decision requires its own stated basis.");
            if ("mixed_contract_pricing".equals(getReviewScope())) return LocalizedText.of(
                    "本项同时包含范围内的 PRE／合同条文核对与范围外的计价文件核对。系统仅审查已提供的 PRE／合同文字；BQ／计价核对不属于比赛自动审查范围。",
                    "本項同時包含範圍內的 PRE／合同條文核對與範圍外的計價文件核對。系統僅審查已提供的 PRE／合同文字；BQ／計價核對不屬於比賽自動審查範圍。",
                    "This row combines in-scope PRE/contract checks with an excluded pricing-document countercheck. Review supplied PRE/contract wording only; BQ/pricing checks are outside the automated competition scope.");
            return LocalizedText.empty();
        }
    }
    /** Only persisted human decisions may populate this object. No finding implies fulfilment. */
    public static final class Result {
        private final String id, status, remarks;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Result(@com.fasterxml.jackson.annotation.JsonProperty("id") String id,@com.fasterxml.jackson.annotation.JsonProperty("status") String status,@com.fasterxml.jackson.annotation.JsonProperty("remarks") String remarks) { this.id=id; this.status=status; this.remarks=remarks; }
        public String getId() { return id; }
        public String getStatus() { return status; }
        public String getRemarks() { return remarks; }
    }
}
