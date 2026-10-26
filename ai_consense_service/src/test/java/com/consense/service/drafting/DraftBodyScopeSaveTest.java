package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.DraftArtifact;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public save seam with real native scopes and mocked persistence; no service/API/database. */
class DraftBodyScopeSaveTest {
    private static DraftBodyParagraphRemovalTest.Saved cleared()throws Exception {
        DraftBodyParagraphRemovalTest.Saved saved=new DraftBodyParagraphRemovalTest.Saved("<w:tbl><w:tr><w:tc><w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"7\"/></w:numPr></w:pPr><w:r><w:t>Clear selected row</w:t><w:tab/></w:r></w:p><w:p><w:r><w:br/></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>Retained table row</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
        saved.document.setUnresolvedJson("[{\"kind\":\"CatalogAlignmentReview\",\"inputKeys\":[\"retain-variable-hold\"]}]");
        BlockPatch clear=saved.block("Clear selected row",null);clear.setText("");saved.save(saved.patch(clear));return saved;
    }
    private static BodyScopePatch selection(Map<String,Object> candidate) {
        BodyScopePatch scope=new BodyScopePatch();scope.setScopeId(String.valueOf(candidate.get("scopeId")));scope.setExpectedScopeHash(String.valueOf(candidate.get("nodeSha256")));scope.setOperation(String.valueOf(candidate.get("operation")));List<String> names=new ArrayList<>();for(Object name:DraftBusinessRules.list(candidate.get("memberBindingIds")))names.add(String.valueOf(name));scope.setMemberBindingIds(names);return scope;
    }
    private static BodyScopePatch available(DraftBodyParagraphRemovalTest.Saved saved) {
        List<Map<String,Object>> candidates=DraftBodyCleanup.candidates(saved.current().getDocxBytes(),DraftBodyParagraphRemovalTest.EDITOR.inspect(saved.current().getDocxBytes()),DraftDocumentBindings.read(saved.current().getBindingsJson()));assertEquals(1,candidates.size());return selection(candidates.get(0));
    }
    @Test void scopeOnlyPublicSavePublishesImmutableReceiptAndRemovedBindingsWhileKeepingBusinessHolds()throws Exception {
        DraftBodyParagraphRemovalTest.Saved saved=cleared();DraftArtifact parent=saved.current();byte[] frozen=parent.getDocxBytes().clone();BodyScopePatch scope=available(saved);DocumentPatch patch=saved.patch();patch.setScopeEdits(Collections.singletonList(scope));
        DocumentPatch replay=JsonUtils.mapper().readValue(JsonUtils.write(patch),DocumentPatch.class);DraftDocumentVO result=saved.save(patch);
        assertNotEquals(parent.getId(),result.getRevisionId());assertEquals(parent.getId(),saved.current().getParentRevisionId());assertArrayEquals(frozen,parent.getDocxBytes());assertTrue(result.getBodyCleanupScopes().isEmpty());assertEquals(1,result.getBodyCleanupReceipts().size());
        Map<String,Object> receipt=result.getBodyCleanupReceipts().get(0);assertEquals(parent.getId(),receipt.get("parentRevisionId"));assertEquals(parent.getDocxSha256(),receipt.get("parentDocxSha256"));assertEquals(result.getDocxSha256(),receipt.get("generatedDocxSha256"));assertEquals(false,receipt.get("graphOrValuesChanged"));
        Map<String,Object> proof=DraftBusinessRules.asMap(DraftBusinessRules.list(receipt.get("scopes")).get(0));assertEquals("EXPLICIT_STRUCTURAL_SCOPE_REMOVED",proof.get("status"));assertEquals(scope.getExpectedScopeHash(),proof.get("nodeSha256"));assertEquals(true,proof.get("beforeAllWordTextEmpty"));assertEquals(2,proof.get("paragraphCount"));assertEquals(1,DraftBusinessRules.list(proof.get("appliedNativeEdits")).size());
        Map<String,Object> binding=DraftDocumentBindings.read(saved.current().getBindingsJson()).stream().filter(b->scope.getMemberBindingIds().contains(String.valueOf(b.get("bindingId")))).findFirst().orElseThrow(AssertionError::new);assertEquals("body_deleted",binding.get("applicationStatus"));assertEquals("removed",binding.get("locationStatus"));assertNull(binding.get("resultParagraphId"));assertFalse(DraftBusinessRules.list(binding.get("bodyOperationIds")).isEmpty());
        assertEquals("CatalogAlignmentReview",result.getUnresolved().get(0).get("kind"));assertTrue(saved.document.getUnresolvedJson().contains("retain-variable-hold"));assertTrue(saved.current().getDocxSha256().equals(result.getDocxSha256()));
        assertTrue(assertThrows(BizException.class,()->saved.save(replay)).getMessage().contains("REVISION_CONFLICT"));
    }
    @Test void invalidWholeScopeHashRejectsAnOtherwiseValidBodyEditWithoutPublishingPartialContent()throws Exception {
        DraftBodyParagraphRemovalTest.Saved saved=cleared();DraftArtifact parent=saved.current();int revisions=saved.revisions.size();BodyScopePatch scope=available(saved);scope.setExpectedScopeHash("different-native-scope");BlockPatch body=saved.block("Retained after",null);body.setText("Must not be published");DocumentPatch patch=saved.patch(body);patch.setScopeEdits(Collections.singletonList(scope));
        assertTrue(assertThrows(BizException.class,()->saved.save(patch)).getMessage().contains("BODY_SCOPE_HASH_CONFLICT"));assertEquals(parent.getId(),saved.document.getRevisionId());assertEquals(revisions,saved.revisions.size());assertFalse(saved.current().getDocxSha256().isEmpty());assertArrayEquals(parent.getDocxBytes(),saved.current().getDocxBytes());
    }
    @Test void laterOrdinarySaveRetainsTheExactDeletionReceiptAndCannotConvertUnknownMissingBindingsIntoRemoval()throws Exception {
        DraftBodyParagraphRemovalTest.Saved saved=cleared();BodyScopePatch scope=available(saved);DocumentPatch patch=saved.patch();patch.setScopeEdits(Collections.singletonList(scope));DraftDocumentVO deleted=saved.save(patch);String receipt=JsonUtils.write(deleted.getBodyCleanupReceipts());BlockPatch body=saved.block("Retained table row",null);body.setText("Retained table row, independently reviewed.");DraftDocumentVO later=saved.save(saved.patch(body));
        assertEquals(receipt,JsonUtils.write(later.getBodyCleanupReceipts()));assertEquals(1,later.getBodyCleanupReceipts().size());assertEquals(1,later.getUnresolved().size());assertEquals("CatalogAlignmentReview",later.getUnresolved().get(0).get("kind"));
        List<Map<String,Object>> rows=DraftDocumentBindings.read(saved.current().getBindingsJson());assertEquals(1,rows.stream().filter(b->"body_deleted".equals(b.get("applicationStatus"))).count());
    }
}
