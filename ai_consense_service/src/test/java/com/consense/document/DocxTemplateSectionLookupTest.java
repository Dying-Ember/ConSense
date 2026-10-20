package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Section lookup reuses only a read-only part traversal; native identities and fresh DOM reads remain exact. */
class DocxTemplateSectionLookupTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String[] KEYS={"NTT","SCT","SCC"};
    private static final String[] HASHES={"60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"};
    private final DocxTemplateEditor editor=new DocxTemplateEditor();

    @Test void sectionOwnershipStaysInNativeOrderAndWithinItsOwnStory()throws Exception {
        byte[] body=DocxTemplateEditorSafetyTest.document("<w:p><w:r><w:t>First</w:t></w:r></w:p><w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>Section end</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Table</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>Last</w:t></w:r></w:p>");
        Map<String,byte[]> parts=DocxTemplateEditorTest.entries(body);
        parts.put("word/header1.xml",("<w:hdr xmlns:w=\""+W+"\"><w:p><w:r><w:t>Header</w:t></w:r></w:p></w:hdr>").getBytes(StandardCharsets.UTF_8));
        byte[] original=DocxTemplateEditorSafetyTest.zip(parts);DocxTemplateEditor.TemplateIndex index=editor.prepare(original).getIndex();
        assertEquals("word/document.xml#/w:document[1]/w:body[1]/w:p[2]/w:pPr[1]/w:sectPr[1]",index.mainParagraph(1).getSectionId());
        assertEquals(index.mainParagraph(1).getSectionId(),index.mainParagraph(2).getSectionId());
        assertEquals("word/document.xml#/w:document[1]/w:body[1]/w:sectPr[1]",index.mainParagraph(3).getSectionId());
        assertEquals(index.mainParagraph(3).getSectionId(),index.mainParagraph(4).getSectionId());
        DocxTemplateEditor.Paragraph header=index.getParagraphs().stream().filter(p->"word/header1.xml".equals(p.getPart())).findFirst().orElseThrow();assertNull(header.getSectionId());
        assertArrayEquals(original,editor.apply(original,new DocxTemplateEditor.SourceEditBatch(index.getSourceSha256(),Collections.emptyList())).getDocxBytes());
    }

    @Test void theStandaloneConstructorRechecksSectionsAfterItsDocumentChanges()throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        Document document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(("<w:document xmlns:w=\""+W+"\"><w:body><w:p><w:r><w:t>First</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p><w:sectPr/></w:body></w:document>").getBytes(StandardCharsets.UTF_8)));
        Element first=(Element)document.getElementsByTagNameNS(W,"p").item(0),second=(Element)document.getElementsByTagNameNS(W,"p").item(1);
        Constructor<DocxTemplateEditor.Paragraph> constructor=DocxTemplateEditor.Paragraph.class.getDeclaredConstructor(String.class,String.class,Element.class,int.class);constructor.setAccessible(true);
        String path="/w:document[1]/w:body[1]/w:p[1]";
        assertEquals("word/document.xml#/w:document[1]/w:body[1]/w:sectPr[1]",constructor.newInstance("word/document.xml",path,first,1).getSectionId());
        Element properties=document.createElementNS(W,"w:pPr");properties.appendChild(document.createElementNS(W,"w:sectPr"));second.insertBefore(properties,second.getFirstChild());
        assertEquals("word/document.xml#/w:document[1]/w:body[1]/w:p[2]/w:pPr[1]/w:sectPr[1]",constructor.newInstance("word/document.xml",path,first,1).getSectionId());
        second.removeChild(properties);
        assertEquals("word/document.xml#/w:document[1]/w:body[1]/w:sectPr[1]",constructor.newInstance("word/document.xml",path,first,1).getSectionId());
    }

    @ParameterizedTest(name="complete actual prepared index {0}") @ValueSource(ints={0,1,2})
    void completeActualPreparedIndexMatchesTheFrozenBeforeState(int source)throws Exception {
        String output=System.getProperty("consense.acceptance.sectionIndexOutput");assumeTrue(output!=null,"Set sectionIndexOutput for the immutable before/after evidence.");
        Path directory=Paths.get(output);Files.createDirectories(directory);byte[] original=DocxTemplateEditorTest.source(source),unchanged=original.clone();
        long started=System.nanoTime();DocxTemplateEditor.PreparedTemplate prepared=editor.prepare(original);double elapsed=(System.nanoTime()-started)/1_000_000_000.0;
        DocxTemplateEditor.TemplateIndex index=prepared.getIndex();assertEquals(HASHES[source],index.getSourceSha256());assertArrayEquals(unchanged,original);
        JsonNode snapshot=JsonUtils.mapper().valueToTree(frozenFields(index));byte[] snapshotBytes=JsonUtils.write(snapshot).getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve(KEYS[source]+"-complete-index.json"),snapshotBytes,StandardOpenOption.CREATE_NEW);
        Map<String,Object> metric=new LinkedHashMap<>();metric.put("source",KEYS[source]);metric.put("sourceSha256",index.getSourceSha256());metric.put("prepareElapsedSeconds",elapsed);metric.put("mainParagraphs",index.getMainParagraphs().size());metric.put("allParagraphs",index.getParagraphs().size());metric.put("anchors",index.getAnchors().size());metric.put("features",index.getFeatures().size());metric.put("snapshotBytes",snapshotBytes.length);metric.put("snapshotSha256",hex(MessageDigest.getInstance("SHA-256").digest(snapshotBytes)));metric.put("serializationIncludedInPrepareTiming",false);
        Files.write(directory.resolve(KEYS[source]+"-prepare-metric.json"),JsonUtils.write(metric).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        String baseline=System.getProperty("consense.acceptance.sectionIndexBaseline");if(baseline!=null){JsonNode before=JsonUtils.parse(Files.readString(Paths.get(baseline).resolve(KEYS[source]+"-complete-index.json")));assertNull(firstDifference(before,snapshot,"$"),"Complete immutable TemplateIndex differs from the frozen original implementation");}
    }

    /** Every private immutable value is included, including raw paragraph snapshot XML, not only its digest. */
    private static Object frozenFields(Object value)throws Exception {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof byte[])return Base64.getEncoder().encodeToString((byte[])value);
        if(value instanceof Map){Map<String,Object> result=new LinkedHashMap<>();for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())result.put(String.valueOf(entry.getKey()),frozenFields(entry.getValue()));return result;}
        if(value instanceof Iterable){List<Object> result=new ArrayList<>();for(Object item:(Iterable<?>)value)result.add(frozenFields(item));return result;}
        Map<String,Object> result=new LinkedHashMap<>();for(Field field:value.getClass().getDeclaredFields()){if(Modifier.isStatic(field.getModifiers()))continue;field.setAccessible(true);result.put(field.getName(),frozenFields(field.get(value)));}return result;
    }
    private static String firstDifference(JsonNode expected,JsonNode actual,String path){if(expected.equals(actual))return null;if(expected.getNodeType()!=actual.getNodeType())return path+": node type";if(expected.isObject()){Set<String> keys=new LinkedHashSet<>();expected.fieldNames().forEachRemaining(keys::add);actual.fieldNames().forEachRemaining(keys::add);for(String key:keys){if(!expected.has(key)||!actual.has(key))return path+"."+key+": missing";String difference=firstDifference(expected.get(key),actual.get(key),path+"."+key);if(difference!=null)return difference;}}else if(expected.isArray()){if(expected.size()!=actual.size())return path+": array size";for(int i=0;i<expected.size();i++){String difference=firstDifference(expected.get(i),actual.get(i),path+"["+i+"]");if(difference!=null)return difference;}}return path+": value";}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format("%02x",value&255));return result.toString();}
}
