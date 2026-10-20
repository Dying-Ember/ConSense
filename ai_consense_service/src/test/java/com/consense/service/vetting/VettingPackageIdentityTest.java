package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real pure-rule decisions on explicit source ranges; no provider or business-acceptance claim. */
class VettingPackageIdentityTest {
    private DocumentBlock block(String id,String text){DocumentBlock b=new DocumentBlock();b.setId(id);b.setLocation("source/"+id);b.setText(text);ObjectNode reading=JsonUtils.mapper().createObjectNode(),context=reading.putObject(VettingPackageIdentity.CONTEXT);context.put("status","source_bound");context.put("role","tender");context.put("documentScope","package");context.put("originalSourceSha256",String.join("",Collections.nCopies(64,"a")));context.put("startOffset",0);context.put("endOffset",text.length());context.put("selectedStartsAtSourceLineBoundary",true);context.put("selectedEndsAtSourceLineBoundary",true);b.setReadingContext(reading);return b;}
    private VettingRuleEngine.Source source(String id,DocumentBlock... blocks){return new VettingRuleEngine.Source(id,"NTT","authored-"+id+".docx","source-hash-"+id,Arrays.asList(blocks),"");}
    private JsonNode authority(String number,String title){ObjectNode snapshot=JsonUtils.mapper().createObjectNode();snapshot.put("policy","adopted-package-identity-v1");snapshot.put("authoritySha256","frozen-authority-link");ObjectNode fields=snapshot.putObject("core").putObject("fields");for(String key:Arrays.asList("number","title")){String raw="number".equals(key)?number:title;ObjectNode field=fields.putObject(key);field.put("eligible",raw!=null&&!raw.isEmpty());field.put("value",raw);}return snapshot;}
    @Test void adoptedNumberDifferenceProducesOneSourceQuotedCandidateAndKeepsMissingTitleUnknown(){
        DocumentBlock b=block("body1","Contract No.: PKG-002");VettingPackageIdentity.Review result=VettingPackageIdentity.review(Collections.singletonList(source("1",b)),authority("PKG-001",null));
        assertEquals(1,result.outputs().size());assertEquals("package_identity_number_coordination",result.outputs().get(0).getRuleId());assertEquals(b.getText(),result.outputs().get(0).getEvidence().get(0).getQuote());assertEquals(1,result.outputs().get(0).getEvidence().size(),"Manual adopted baseline is metadata, never a fabricated source quotation");assertEquals("unknown_no_own_declaration",result.ledger().path("fields").path("title").path("status").asText());
    }
    @Test void observedDisagreementNeedsNoGuessedWinnerOrProjectBaseline(){
        VettingPackageIdentity.Review result=VettingPackageIdentity.review(Arrays.asList(source("1",block("a","Contract Number: A-001")),source("2",block("b","Contract Number: A-002"))),null);
        assertEquals(1,result.outputs().size());assertEquals(2,result.outputs().get(0).getEvidence().size());assertEquals("unknown",result.ledger().path("fields").path("number").path("authorityStatus").asText());assertFalse(result.outputs().get(0).getSummary().contains("adopted number"));
    }
    @Test void externalReferenceSampleAndBareFooterDoNotBecomeOwnIdentity(){
        VettingPackageIdentity.Review result=VettingPackageIdentity.review(Collections.singletonList(source("1",block("external","Other Contract No: OTHER-1"),block("sample","Sample Contract No: SAMPLE-1"),block("bare","PKG-999"))),authority("PKG-1",null));
        assertTrue(result.outputs().isEmpty());JsonNode declarations=result.ledger().path("declarations");assertEquals("external_reference",declarations.get(0).path("classification").asText());assertEquals("template_or_example",declarations.get(1).path("classification").asText());assertEquals("unknown_no_own_declaration",result.ledger().path("fields").path("number").path("status").asText());assertEquals(1,result.ledger().path("unknowns").size());
    }
    @Test void emptyUnobservedAndPlaceholderFieldsNeverProducePass(){
        for(List<VettingRuleEngine.Source> sources:Arrays.asList(Collections.<VettingRuleEngine.Source>emptyList(),Collections.singletonList(source("1",block("a","Contract No: ____"))))){VettingPackageIdentity.Review review=VettingPackageIdentity.review(sources,authority("",null));assertTrue(review.outputs().isEmpty());assertEquals("unknown_no_own_declaration",review.ledger().path("fields").path("number").path("status").asText());}
    }
    @Test void whitespaceComparisonKeepsRawUnicodePunctuationCaseAndLeadingZeroes(){
        String text="Project Title: New\u00a0\tHousing";VettingPackageIdentity.Review agreement=VettingPackageIdentity.review(Collections.singletonList(source("1",block("a",text))),authority(null,"New Housing"));
        assertTrue(agreement.outputs().isEmpty());assertEquals("New\u00a0\tHousing",agreement.ledger().path("declarations").get(0).path("rawValue").asText());assertEquals("candidate_agreement_scope_unknown",agreement.ledger().path("fields").path("title").path("status").asText());
        for(String value:Arrays.asList("AB001","ab-001","AB-01"))assertEquals(1,VettingPackageIdentity.review(Collections.singletonList(source("1",block("a","Contract No: "+value))),authority("AB-001",null)).outputs().size());
    }
    @Test void unadoptedAliasDifferenceRemainsReviewWithoutClaimingWrongProject(){
        VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",block("a","Project Name: New Housing Phase One"))),authority(null,"New Housing Phase 1"));assertEquals(1,review.outputs().size());assertTrue(review.outputs().get(0).getSummary().contains("No adopted alias map"));assertFalse(review.outputs().get(0).getSummary().toLowerCase(Locale.ROOT).contains("wrong project"));
    }
    @Test void sourceContextAndTemplateRolesCannotBeInferredFromAnchorNames(){
        DocumentBlock unknown=block("footer/1","Contract No: WRONG"),standard=block("body/1","Contract No: TEMPLATE");((ObjectNode)unknown.getReadingContext().path(VettingPackageIdentity.CONTEXT)).put("status","unknown");((ObjectNode)standard.getReadingContext().path(VettingPackageIdentity.CONTEXT)).put("role","standard");
        VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",unknown,standard)),authority("PKG-1",null));assertTrue(review.outputs().isEmpty());assertEquals("source_context_unbound",review.ledger().path("declarations").get(0).path("reason").asText());assertEquals("template_or_example",review.ledger().path("declarations").get(1).path("classification").asText());
    }
    @Test void inheritedRelationsDoNotDuplicateTheSamePhysicalObservation(){
        DocumentBlock original=block("footer/1","Contract No: A-001"),repeat=JsonUtils.read(JsonUtils.write(original),DocumentBlock.class);((ObjectNode)original.getReadingContext().path(VettingPackageIdentity.CONTEXT)).withArray("sectionRelations").add("section1");((ObjectNode)repeat.getReadingContext().path(VettingPackageIdentity.CONTEXT)).withArray("sectionRelations").add("section2");
        VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",original,repeat)),authority("A-002",null));assertEquals(1,review.ledger().path("declarations").size());assertEquals(1,review.outputs().get(0).getEvidence().size());assertNull(review.outputs().get(0).getEvidence().get(0).getPageNo());assertEquals("unknown",review.ledger().path("declarations").get(0).path("physicalWordPageStatus").asText());
    }
    @Test void repeatedDifferingOccurrencesProduceOneCandidatePerFieldAndKeepEveryQuote(){
        VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",block("a","Contract No: A-001"),block("b","Contract No: A-002"),block("c","Contract No: A-003"),block("d","Project Title: One"),block("e","Project Title: Two"))),null);assertEquals(2,review.outputs().size());assertEquals(3,review.outputs().get(0).getEvidence().size());assertEquals(2,review.outputs().get(1).getEvidence().size());assertEquals("unknown",review.ledger().path("legalProblemCountStatus").asText());
        for(VettingRuleEngine.Output output:review.outputs())assertEquals(VettingIssueType.CONFLICT,VettingIssueType.require(output.getType()),"Every identity candidate uses the existing issue taxonomy");
    }
    @Test void multiLabelTextWithoutBoundValueRangesStaysUnknown(){
        VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",block("a","Contract No: A-001 Project Title: Mixed"))),authority("A-001","Mixed"));assertTrue(review.outputs().isEmpty());assertEquals("multiple_labels_without_bound_value_ranges",review.ledger().path("declarations").get(0).path("reason").asText());
    }
    @Test void frozenLedgerAndInputsCannotBeChangedThroughReturnedJson(){
        DocumentBlock b=block("a","Contract No: A-001");String before=JsonUtils.write(b);JsonNode authority=authority("A-002",null);String authorityBefore=JsonUtils.write(authority);VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",b)),authority);ObjectNode returned=(ObjectNode)review.ledger();returned.removeAll();assertEquals(1,review.ledger().path("declarations").size());assertEquals(before,JsonUtils.write(b));assertEquals(authorityBefore,JsonUtils.write(authority));
    }
    @Test void secondaryLabelsUseTheSameOptionalColonGrammarAsPrimaryDeclarations(){
        for(String text:Arrays.asList("Contract No PKG-001 Project Title New Housing","Contract No: PKG-001 Project Title New Housing","Contract No PKG-001 Project Title: New Housing","Contract No=PKG-001 Project Title New Housing","Contract No：PKG-001 Project Title=New Housing","合同编号 PKG-001 项目名称 New Housing")){
            VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",block("a",text))),authority("PKG-001","New Housing"));assertTrue(review.outputs().isEmpty(),text);assertEquals("unknown",review.ledger().path("declarations").get(0).path("classification").asText(),text);assertEquals("multiple_labels_without_bound_value_ranges",review.ledger().path("declarations").get(0).path("reason").asText(),text);
        }
    }
    @Test void independentSampleOrExternalTextDoesNotCreateAnUnprovenScopeEdgeOrOwnIdentity(){
        for(String heading:Arrays.asList("Sample","External reference")){
            VettingPackageIdentity.Review review=VettingPackageIdentity.review(Collections.singletonList(source("1",block("heading",heading),block("value","Contract No: PKG-001"))),authority("PKG-002",null));assertEquals(1,review.outputs().size());JsonNode candidate=review.ledger().path("declarations").get(0);assertEquals("identity_candidate",candidate.path("classification").asText());assertEquals("unknown",candidate.path("scopeStatus").asText());assertEquals(0,review.ledger().path("fields").path("number").path("ownDeclarations").asInt());assertEquals(1,review.ledger().path("fields").path("number").path("candidateDeclarations").asInt());assertEquals("candidate_difference_requires_review",review.ledger().path("fields").path("number").path("status").asText());assertTrue(review.outputs().get(0).getSummary().contains("Whether these candidates identify this package remains unknown"));assertFalse(review.outputs().get(0).getSummary().contains("own-identity"));
        }
    }
}
