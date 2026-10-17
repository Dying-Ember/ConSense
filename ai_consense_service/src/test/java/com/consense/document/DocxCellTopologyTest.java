package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DocxCellTopologyTest {
    @Test void colspanVerticalMergeNestedTablesAndStrikeChannelsKeepDistinctNativeAnchors() throws Exception {
        String body="<w:tbl><w:tblGrid><w:gridCol w:w=\"1000\"/><w:gridCol w:w=\"1000\"/><w:gridCol w:w=\"1000\"/></w:tblGrid>"
                +"<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:vMerge w:val=\"restart\"/></w:tcPr>"
                +p("active")+"<w:p><w:r><w:rPr><w:strike/></w:rPr><w:t>struck option</w:t></w:r><w:del w:id=\"1\"><w:r><w:delText>removed option</w:delText></w:r></w:del></w:p>"
                +"<w:tbl><w:tblGrid><w:gridCol w:w=\"800\"/></w:tblGrid><w:tr><w:tc>"+p("nested value")+"</w:tc></w:tr></w:tbl><w:p/></w:tc><w:tc>"+p("other column")+"</w:tc></w:tr>"
                +"<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc>"+p("other row")+"</w:tc></w:tr></w:tbl>";
        DocumentParser.ParsedDocument parsed=parse(body); assertEquals(2,parsed.getBlocks().size()); DocumentBlock first=parsed.getBlocks().get(0);
        DocumentBlock.WordCell merged=first.getWordCells().get(0), nested=first.getWordCells().get(1), other=first.getWordCells().get(2);
        assertEquals(Integer.valueOf(2),merged.getColumnSpan()); assertEquals(Integer.valueOf(2),merged.getRowSpan()); assertEquals(Integer.valueOf(0),merged.getGridColumn());
        assertEquals(Integer.valueOf(2),other.getGridColumn()); assertNotEquals(merged.getTableLocation(),nested.getTableLocation());
        assertTrue(merged.getText().contains("active")); assertTrue(merged.getText().contains("nested value")); assertFalse(merged.getText().contains("struck"));
        assertEquals("struck option",merged.getStrikeText()); assertEquals("removed option",merged.getDeletedText());
        DocumentBlock.WordCell continuation=parsed.getBlocks().get(1).getWordCells().get(0);
        assertEquals("continue",continuation.getVerticalMerge()); assertNull(continuation.getRowSpan()); assertNull(first.getPageNo());
        assertEquals("native_ooxml_unrendered",merged.getTopologyStatus()); assertNotNull(parsed.getSourceSha256());

        String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";
        String tablePath="/"+ns+"document[1]/"+ns+"body[1]/"+ns+"tbl[1]", outerPath=tablePath+"/"+ns+"tr[1]/"+ns+"tc[1]";
        assertEquals(outerPath,merged.getSourcePosition().path("nodePath").asText());
        assertEquals(tablePath,merged.getSourcePosition().path("tableNodePath").asText());
        assertEquals(tablePath+"/"+ns+"tr[1]",merged.getSourcePosition().path("rowNodePath").asText());
        assertFalse(merged.getSourcePosition().has("parentCellNodePath"));
        assertEquals(outerPath+"/"+ns+"tbl[1]/"+ns+"tr[1]/"+ns+"tc[1]",nested.getSourcePosition().path("nodePath").asText());
        assertEquals(outerPath+"/"+ns+"tbl[1]",nested.getSourcePosition().path("tableNodePath").asText());
        assertEquals(outerPath,nested.getSourcePosition().path("parentCellNodePath").asText());
        assertEquals(tablePath+"/"+ns+"tr[1]/"+ns+"tc[2]",other.getSourcePosition().path("nodePath").asText());
        assertFalse(other.getSourcePosition().has("parentCellNodePath"));
        assertEquals(tablePath+"/"+ns+"tr[2]/"+ns+"tc[1]",continuation.getSourcePosition().path("nodePath").asText());
        for(DocumentBlock block:parsed.getBlocks())for(DocumentBlock.WordCell cell:block.getWordCells()){
            assertEquals(parsed.getSourceSha256(),cell.getSourcePosition().path("sourceSha256").asText());
            assertEquals(block.getSourcePosition().path("partSha256"),cell.getSourcePosition().path("partSha256"));
            assertNull(block.getPageNo());
        }
        List<JsonNode> tables=new ArrayList<>(),rows=new ArrayList<>(),physicalCells=new ArrayList<>();
        for(JsonNode member:parsed.getCoverage().getNativeDocxTables().path("parts").get(0).path("members")){
            if("table".equals(member.path("kind").asText()))tables.add(member);
            if("row".equals(member.path("kind").asText()))rows.add(member);
            if("cell".equals(member.path("kind").asText()))physicalCells.add(member);
        }
        assertEquals(2,tables.size());assertEquals(3,rows.size());assertEquals(5,physicalCells.size());
        assertEquals(tablePath,tables.get(1).path("parentTableNodePath").asText());assertEquals(outerPath,tables.get(1).path("parentCellNodePath").asText());
        for(JsonNode row:rows)assertFalse(row.has("parentCellNodePath"),"Rows only repeat their direct owning table path");
        assertEquals(0,rows.get(1).path("retained").size(),"Nested row has no independent retained block");
        assertEquals(0,physicalCells.get(1).path("retained").get(0).path("blockOrdinalZeroBased").asInt());
        assertEquals(1,physicalCells.get(1).path("retained").get(0).path("wordCellOrdinalZeroBased").asInt());

    }
    @Test void invalidSpanIsUnknownAndCannotSilentlyMoveFollowingCells() throws Exception {
        DocumentParser.ParsedDocument parsed=parse("<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"0\"/></w:tcPr>"+p("bad span")+"</w:tc><w:tc>"+p("following")+"</w:tc></w:tr></w:tbl>");
        List<DocumentBlock.WordCell> cells=parsed.getBlocks().get(0).getWordCells(); assertNull(cells.get(0).getColumnSpan()); assertEquals("0",cells.get(0).getGridSpanRaw());
        assertNull(cells.get(1).getGridColumn()); assertEquals("native_ooxml_topology_unresolved",cells.get(1).getTopologyStatus()); assertFalse(parsed.getCoverage().isComplete());
    }
    @Test void badGridBeforeAndLegacyHorizontalMergeArePreservedAsUnresolved() throws Exception {
        DocumentParser.ParsedDocument parsed=parse("<w:tbl><w:tr><w:trPr><w:gridBefore w:val=\"invalid\"/></w:trPr><w:tc><w:tcPr><w:hMerge w:val=\"restart\"/></w:tcPr>"+p("legacy")+"</w:tc></w:tr></w:tbl>");
        DocumentBlock.WordCell cell=parsed.getBlocks().get(0).getWordCells().get(0); assertNull(cell.getGridColumn()); assertEquals("invalid",cell.getGridBeforeRaw());
        assertEquals("restart",cell.getHorizontalMerge()); assertFalse(cell.getLimitations().isEmpty()); assertEquals("native_ooxml_topology_unresolved",cell.getTopologyStatus());
    }
    @Test void blankNativeCellsStillRetainTheirTableTopology() throws Exception {
        DocumentParser.ParsedDocument parsed=parse("<w:tbl><w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>");
        assertEquals(1,parsed.getBlocks().size()); assertEquals(2,parsed.getBlocks().get(0).getWordCells().size());
        assertEquals(Integer.valueOf(1),parsed.getBlocks().get(0).getWordCells().get(1).getGridColumn()); assertNull(parsed.getBlocks().get(0).getPageNo());
    }
    @Test void inventoryIncludesZeroRowTablesZeroCellRowsAndUnrepresentedRawBranchesInPhysicalOrder() throws Exception {
        String blankTable="<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>";
        StringBuilder body=new StringBuilder("<w:tbl/><w:tbl><w:tr/><w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>");
        for(int i=0;i<10;i++)body.append(blankTable);
        body.append("<w:sdt><w:sdtContent>").append(blankTable).append("</w:sdtContent></w:sdt>")
                .append("<w:customXml>").append(blankTable).append("</w:customXml>")
                .append("<w:del w:id=\"1\">").append(blankTable).append("</w:del>")
                .append("<w:ins w:id=\"2\">").append(blankTable).append("</w:ins>")
                .append("<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"><mc:Choice Requires=\"w\">")
                .append(blankTable).append("</mc:Choice><mc:Fallback>").append(blankTable).append("</mc:Fallback></mc:AlternateContent>")
                .append("<f:tbl xmlns:f=\"urn:foreign\"><f:tr><f:tc/></f:tr></f:tbl>");
        DocumentParser.ParsedDocument parsed=parse(body.toString());JsonNode inventory=parsed.getCoverage().getNativeDocxTables();
        assertEquals(parsed.getSourceSha256(),inventory.path("sourceSha256").asText());assertFalse(inventory.path("semanticScopeVerified").asBoolean());
        assertEquals("unknown",inventory.path("effectiveContentStatus").asText());assertTrue(inventory.path("unobservedScope").asText().contains("not_loaded"));
        JsonNode part=inventory.path("parts").get(0);List<JsonNode> tables=new ArrayList<>(),rows=new ArrayList<>(),cells=new ArrayList<>();
        for(JsonNode member:part.path("members")){
            String kind=member.path("kind").asText();if("table".equals(kind))tables.add(member);if("row".equals(kind))rows.add(member);if("cell".equals(kind))cells.add(member);
        }
        assertEquals(18,tables.size());assertEquals(18,rows.size());assertEquals(18,cells.size());
        String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}",base="/"+ns+"document[1]/"+ns+"body[1]/";
        for(int i=0;i<12;i++)assertEquals(base+ns+"tbl["+(i+1)+"]",tables.get(i).path("nodePath").asText());
        assertEquals(base+ns+"tbl[2]/"+ns+"tr[1]",rows.get(0).path("nodePath").asText());assertTrue(rows.get(0).path("retained").isEmpty());
        assertEquals(base+ns+"tbl[2]/"+ns+"tr[2]/"+ns+"tc[1]",cells.get(0).path("nodePath").asText());
        assertEquals(base+ns+"tbl[2]/"+ns+"tr[2]/"+ns+"tc[2]",cells.get(1).path("nodePath").asText());
        assertNotEquals(cells.get(0).path("nodePath"),cells.get(1).path("nodePath"));
        assertEquals(0,cells.get(0).path("retained").get(0).path("wordCellOrdinalZeroBased").asInt());
        assertEquals(1,cells.get(1).path("retained").get(0).path("wordCellOrdinalZeroBased").asInt());
        for(int i=12;i<cells.size();i++)assertTrue(cells.get(i).path("retained").isEmpty(),"Unrepresented wrapper/revision/alternate member must not invent a carrier");
        assertEquals(inventory,JsonUtils.read(JsonUtils.write(parsed.getCoverage()),ParseCoverage.class).getNativeDocxTables());
        for(String forbidden:Arrays.asList("text","rawXml","tokens","sourcePosition","nativeNodes"))assertTrue(inventory.findValues(forbidden).isEmpty());
    }
    @Test void strictNamespaceInventoryHelperKeepsForeignNamesOutWithoutClaimingPoiStrictPackageSupport() throws Exception {
        String ns="http://purl.oclc.org/ooxml/wordprocessingml/main";
        String xml="<w:document xmlns:w=\""+ns+"\" xmlns:f=\"urn:foreign\"><w:body><w:tbl><w:tr><w:tc><f:tbl><f:tr><f:tc>"
                +"<w:tbl><w:tr><w:tc/></w:tr></w:tbl></f:tc></f:tr></f:tbl></w:tc></w:tr></w:tbl></w:body></w:document>";
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        org.w3c.dom.Node body=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement().getFirstChild();
        org.apache.poi.openxml4j.opc.PackagePart part=mock(org.apache.poi.openxml4j.opc.PackagePart.class);
        org.mockito.Mockito.when(part.getPartName()).thenReturn(org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName("/word/document.xml"));
        org.mockito.Mockito.when(part.getInputStream()).thenAnswer(ignored->new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Class<?> type=Class.forName("com.consense.document.DocumentParser$NativeWordTableInventory");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(Map.class,Map.class,String.class);constructor.setAccessible(true);
        Object collector=constructor.newInstance(new IdentityHashMap<>(),new HashMap<>(),"authored-helper-only-source");
        java.lang.reflect.Method load=type.getDeclaredMethod("load",org.w3c.dom.Node.class,String.class,org.apache.poi.openxml4j.opc.PackagePart.class,String.class);load.setAccessible(true);
        load.invoke(collector,body,"body",part,null);load.invoke(collector,body,"body",part,null);
        java.lang.reflect.Method finish=type.getDeclaredMethod("finish",List.class,Map.class);finish.setAccessible(true);
        JsonNode inventory=(JsonNode)finish.invoke(collector,Collections.emptyList(),new IdentityHashMap<>());JsonNode output=inventory.path("parts").get(0);
        assertEquals(1,output.path("loadedRoots").size());assertEquals(6,output.path("members").size());
        JsonNode outer=output.path("members").get(0),outerCell=output.path("members").get(2),nested=output.path("members").get(3),nestedCell=output.path("members").get(5);
        assertFalse(outer.has("parentTableNodePath"));assertFalse(outerCell.has("parentCellNodePath"));
        assertEquals(outer.path("nodePath"),nested.path("parentTableNodePath"));assertEquals(outerCell.path("nodePath"),nested.path("parentCellNodePath"));
        assertEquals(nested.path("nodePath"),nestedCell.path("tableNodePath"));assertEquals(outerCell.path("nodePath"),nestedCell.path("parentCellNodePath"));
        for(JsonNode member:output.path("members")){assertTrue(member.path("nodePath").asText().contains("{"+ns+"}"));assertTrue(member.path("retained").isEmpty());}
        org.mockito.Mockito.verify(part,org.mockito.Mockito.times(1)).getInputStream();
    }
    @Test void inventoryHelperMergesReverseLoadedNoteRootsInPartOrderAndLeavesUnloadedRootsUnknown() throws Exception {
        String table="<w:tbl><w:tr><w:tc/></w:tr></w:tbl>",ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        String xml="<w:footnotes xmlns:w=\""+ns+"\"><w:footnote w:id=\"9\">"+table+"</w:footnote><w:footnote w:id=\"0\">"+table+"</w:footnote><w:footnote w:id=\"2\">"+table+"</w:footnote></w:footnotes>";
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        org.w3c.dom.Node root=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        org.apache.poi.openxml4j.opc.PackagePart part=mock(org.apache.poi.openxml4j.opc.PackagePart.class);
        org.mockito.Mockito.when(part.getPartName()).thenReturn(org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName("/word/footnotes.xml"));
        org.mockito.Mockito.when(part.getInputStream()).thenAnswer(ignored->new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Class<?> type=Class.forName("com.consense.document.DocumentParser$NativeWordTableInventory");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(Map.class,Map.class,String.class);constructor.setAccessible(true);
        Object collector=constructor.newInstance(new IdentityHashMap<>(),new HashMap<>(),"authored-note-scope-helper");
        java.lang.reflect.Method load=type.getDeclaredMethod("load",org.w3c.dom.Node.class,String.class,org.apache.poi.openxml4j.opc.PackagePart.class,String.class);load.setAccessible(true);
        load.invoke(collector,root.getLastChild(),"footnote",part,"2");load.invoke(collector,root.getFirstChild(),"footnote",part,"9");load.invoke(collector,root.getFirstChild(),"footnote",part,"9");
        java.lang.reflect.Method finish=type.getDeclaredMethod("finish",List.class,Map.class);finish.setAccessible(true);JsonNode inventory=(JsonNode)finish.invoke(collector,Collections.emptyList(),new IdentityHashMap<>());
        JsonNode output=inventory.path("parts").get(0);assertEquals(2,output.path("loadedRoots").size());assertEquals("9",output.path("loadedRoots").get(0).path("noteId").asText());assertEquals("2",output.path("loadedRoots").get(1).path("noteId").asText());
        assertEquals(6,output.path("members").size());String base="/{"+ns+"}footnotes[1]/{"+ns+"}footnote[";
        assertEquals(base+"1]/{"+ns+"}tbl[1]",output.path("members").get(0).path("nodePath").asText());assertEquals(base+"3]/{"+ns+"}tbl[1]",output.path("members").get(3).path("nodePath").asText());
        for(JsonNode member:output.path("members"))assertFalse(member.path("nodePath").asText().contains("}footnote[2]"));
        assertEquals("observed_loaded_native_roots",inventory.path("status").asText());assertTrue(inventory.path("unobservedScope").asText().contains("not_loaded"));org.mockito.Mockito.verify(part,org.mockito.Mockito.times(1)).getInputStream();
    }
    private static String p(String text) {return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>";}
    private DocumentParser.ParsedDocument parse(String body) throws Exception {
        ByteArrayOutputStream initial=new ByteArrayOutputStream(); try(XWPFDocument doc=new XWPFDocument()){doc.createParagraph();doc.write(initial);}
        ByteArrayOutputStream modified=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(initial.toByteArray())); ZipOutputStream out=new ZipOutputStream(modified)){
            ZipEntry entry; while((entry=in.getNextEntry())!=null){ByteArrayOutputStream part=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1)part.write(buffer,0,count); byte[] bytes=part.toByteArray();
                if("word/document.xml".equals(entry.getName()))bytes=new String(bytes,StandardCharsets.UTF_8).replaceAll("(?s)<w:body>.*?</w:body>","<w:body>"+body+"</w:body>").getBytes(StandardCharsets.UTF_8);
                out.putNextEntry(new ZipEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        return new DocumentParser(new ConsenseProperties(),mock(OcrClient.class)).parse("anonymous.docx",modified.toByteArray());
    }
}
