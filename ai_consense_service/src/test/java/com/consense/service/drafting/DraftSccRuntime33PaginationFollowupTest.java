package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Source-bound follow-up to the actual runtime33 75-page PDF; no visual acceptance is inferred. */
class DraftSccRuntime33PaginationFollowupTest {
    private static final String SOURCE="9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208";
    private static final int[][] PAIRS={{1829,1830},{1963,1965},{1997,1999},{1999,2001},{2001,2003}};
    private static String id(int p){return DraftNativeHeadingBoundariesTest.nativeId(p);}
    private static Map<String,Object> registry()throws IOException {
        try(InputStream in=DraftNativeLayout.class.getResourceAsStream("/drafting/libreoffice-native-layout.json")){
            assertNotNull(in);Map<String,Object> full=JsonUtils.mapper().readValue(in,Map.class);
            return DraftBusinessRules.list(full.get("sources")).stream().map(DraftBusinessRules::asMap)
                .filter(r->SOURCE.equals(r.get("sourceSha256"))).findFirst().orElseThrow();
        }
    }
    @Test void appendsFiveExactScopesAndPreservesEveryOriginalBoundaryAndFlow()throws Exception {
        Map<String,Object> source=registry();assertEquals("consense-lo-scc-cleared-20261022.v2",source.get("profile"));List<Object> rows=DraftBusinessRules.list(source.get("paragraphBoundaries"));
        assertEquals(245,rows.size());
        assertEquals("20e1363183732ba771718c84c87d841a895bf0f0076c3f7f0862d08c0779759e",DraftAdoption.hash(JsonUtils.write(rows.subList(0,240))),"All 240 old native declarations remain byte-equivalent as JSON values");
        List<NativeHeadingFlow> flows=DraftNativeLayout.headingFlows(SOURCE,"libreoffice");assertEquals(244,flows.size());
        for(int i=0;i<PAIRS.length;i++){
            int[] pair=PAIRS[i];Map<String,Object> row=DraftBusinessRules.asMap(rows.get(240+i));
            assertEquals(id(pair[0]),row.get("headingId"));assertEquals(id(pair[1]),row.get("bodyId"));assertEquals(Boolean.TRUE,row.get("compactClearedGaps"));
            assertEquals(i==0,Boolean.TRUE.equals(row.get("releaseTrailingPageBreak")));assertEquals(i>0,Boolean.TRUE.equals(row.get("keepBodyLines")));
            List<Object> gaps=DraftBusinessRules.list(row.get("gaps"));assertEquals(pair[1]-pair[0]-1,gaps.size());
            for(int g=0;g<gaps.size();g++)assertEquals(id(pair[0]+g+1),DraftBusinessRules.asMap(gaps.get(g)).get("paragraphId"));
            NativeHeadingFlow flow=flows.get(239+i);assertEquals(DraftNativeHeadingBoundariesTest.sourceBookmark(pair[0]),flow.getHeadingBookmark());assertEquals(DraftNativeHeadingBoundariesTest.sourceBookmark(pair[1]),flow.getBodyBookmark());assertEquals(gaps.size(),flow.getEmptyParagraphs());
        }
        assertTrue(flows.subList(0,239).stream().anyMatch(f->DraftNativeHeadingBoundariesTest.sourceBookmark(1965).equals(f.getHeadingBookmark())&&DraftNativeHeadingBoundariesTest.sourceBookmark(1967).equals(f.getBodyBookmark())),"Option B reuses the complete existing (c)-to-quote relationship");
    }
    @Test void actualOriginalFormatterAppliesOnlyTheFrozenTrailingBreakAndCompleteNewChains()throws Exception {
        String directory=System.getProperty("consense.acceptance.sourceDir");assumeTrue(directory!=null&&!directory.isBlank(),"Explicit actual sourceDir required");
        byte[] original=Files.readAllBytes(Paths.get(directory,"06_Special Conditions of Contract (SCC).docx"));DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(original);assertEquals(SOURCE,index.getSourceSha256());
        List<Map<String,Object>> bindings=DraftDocumentBindings.source("SCC",index);
        Map<String,Object> plan=DraftBusinessRules.map("actions",new ArrayList<>(),"effectiveValues",Collections.emptyMap());
        DocxEditResult result=DraftFormattedRules.compile(original,"SCC",plan,DraftDocumentBindings.bookmarkNames(bindings),DraftNativeLayout.plan(index,"SCC","libreoffice"));
        List<Map<String,Object>> generated=DraftDocumentBindings.generated(bindings,result);
        for(int[] pair:PAIRS)for(int ordinal:pair){Map<String,Object> row=generated.stream().filter(b->id(ordinal).equals(b.get("sourceParagraphId"))).findFirst().orElseThrow();Paragraph current=result.getIndex().paragraph(String.valueOf(row.get("resultParagraphId")));String expected=index.paragraph(id(ordinal)).getText();if(ordinal==1829){assertTrue(expected.endsWith("\n"));expected=expected.substring(0,expected.length()-1);assertTrue(current.hasReleasedNativeTrailingPageBreak(),"Actual SCC22.304(3) must release its source trailing hard page break");}assertEquals(expected,current.getText(),"No source glyph/content replacement: "+ordinal);}
        List<Object> rows=DraftBusinessRules.list(registry().get("paragraphBoundaries"));
        for(int i=0;i<PAIRS.length;i++){
            Map<String,Object> row=DraftBusinessRules.asMap(rows.get(240+i));assertEquals(index.anchor(id(PAIRS[i][0])).getSha256(),row.get("headingSha256"));assertEquals(index.anchor(id(PAIRS[i][1])).getSha256(),row.get("bodySha256"));
            for(Object raw:DraftBusinessRules.list(row.get("gaps"))){Map<String,Object> gap=DraftBusinessRules.asMap(raw);String gapId=String.valueOf(gap.get("paragraphId"));assertEquals(index.anchor(gapId).getSha256(),gap.get("paragraphSha256"));assertTrue(index.paragraph(gapId).getText().isBlank());}
        }
        for(int i=0;i<PAIRS.length;i++){String head=id(PAIRS[i][0]);assertEquals(1,result.getNativeLayoutLedger().stream().filter(a->"keep_verified_heading_flow".equals(a.getAction())&&head.equals(a.getSource().getId())&&"APPLIED".equals(a.getStatus())).count());}
        assertEquals(1,result.getNativeLayoutLedger().stream().filter(a->"release_verified_trailing_page_break".equals(a.getAction())&&id(1829).equals(a.getSource().getId())&&"APPLIED".equals(a.getStatus())).count());
        for(int n:new int[]{1965,1999,2001,2003})assertEquals(1,result.getNativeLayoutLedger().stream().filter(a->"keep_verified_boundary_body_lines".equals(a.getAction())&&id(n).equals(a.getSource().getId())&&"APPLIED".equals(a.getStatus())).count());
        assertEquals(92,editor.tocPageReferences(result.getDocxBytes()).size());
    }
    @Test void appendedSourceChainsRetainIdentityDuplicateAndNonemptyGapRejection()throws Exception {
        byte[] original=DraftNativeHeadingBoundariesTest.fixture();DocxTemplateEditor editor=new DocxTemplateEditor();List<NativeHeadingFlow> flows=DraftNativeHeadingBoundariesTest.allFlows();Set<String> selected=new LinkedHashSet<>();for(int[] pair:PAIRS)selected.add(DraftNativeHeadingBoundariesTest.sourceBookmark(pair[0]));
        assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(original,"wrong",flows,selected));
        assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(original,DraftPdfConverter.sha256(original),flows,Collections.singleton("CSunknownRuntime33Scope")));
        List<NativeHeadingFlow> duplicate=new ArrayList<>(flows);duplicate.add(flows.get(239));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(original,DraftPdfConverter.sha256(original),duplicate,selected));
        for(int i=240;i<244;i++){String xml=DraftNativeHeadingBoundariesTest.xml(original),gap=DraftNativeHeadingBoundariesTest.paragraph(xml,"CLdynamic"+i+"Gap0");byte[] altered=DraftNativeHeadingBoundariesTest.packageXml(xml.replace(gap,gap.replace("</w:p>",DraftNativeHeadingBoundariesTest.text("Unauthorized nonempty gap")+"</w:p>")));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(altered,DraftPdfConverter.sha256(altered),flows,selected));}
    }
}
