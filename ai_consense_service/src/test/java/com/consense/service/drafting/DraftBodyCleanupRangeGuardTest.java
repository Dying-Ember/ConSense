package com.consense.service.drafting;
import com.consense.common.BizException;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.web.dto.DraftingDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native range endpoints are not visible text and may continue beyond a selected empty row. */
class DraftBodyCleanupRangeGuardTest {
 @Test void commentsRevisionRangesAndPermissionsCannotBeSplitByAnEmptyContainerDeletion()throws Exception {
  for(String[] tags:new String[][]{{"commentRangeStart","commentRangeEnd"},{"moveFromRangeStart","moveFromRangeEnd"},{"moveToRangeStart","moveToRangeEnd"},{"customXmlInsRangeStart","customXmlInsRangeEnd"},{"permStart","permEnd"}})for(boolean firstInside:new boolean[]{true,false}) {
   String inside=firstInside?tags[0]:tags[1],outside=firstInside?tags[1]:tags[0];
   String table="<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Clear range row</w:t></w:r><w:"+inside+" w:id=\"42\"/></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>Retained row</w:t></w:r></w:p></w:tc></w:tr></w:tbl>";
   DraftBodyParagraphRemovalTest.Saved saved=new DraftBodyParagraphRemovalTest.Saved(table+"<w:p><w:"+outside+" w:id=\"42\"/><w:r><w:t>Retained external endpoint</w:t></w:r></w:p>");
   BlockPatch clear=saved.block("Clear range row",null);clear.setText("");DraftDocumentVO current=saved.save(saved.patch(clear));assertTrue(current.getBodyCleanupScopes().isEmpty(),inside+" must remain protected");
   TemplateIndex index=DraftBodyParagraphRemovalTest.EDITOR.inspect(saved.current().getDocxBytes());Paragraph p=index.getMainParagraphs().stream().filter(x->x.getRowId()!=null&&x.getCatalogText().isEmpty()).findFirst().orElseThrow(AssertionError::new);NodeAnchor row=index.anchor(p.getRowId());
   BodyScopePatch forged=new BodyScopePatch();forged.setScopeId(row.getId());forged.setExpectedScopeHash(row.getSha256());forged.setOperation("delete_empty_row");forged.setMemberBindingIds(Collections.emptyList());DocumentPatch patch=saved.patch();patch.setScopeEdits(Collections.singletonList(forged));String revision=saved.current().getId();int count=saved.revisions.size();
   assertTrue(assertThrows(BizException.class,()->saved.save(patch)).getMessage().contains("BODY_SCOPE_NOT_AVAILABLE"));assertEquals(revision,saved.current().getId());assertEquals(count,saved.revisions.size());
  }
 }
}
