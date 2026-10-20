package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Preserve the original lexical boundary while rejecting long ordinary paragraphs promptly. */
class OerSourceListIntroductionTest {
    private static final String OLD="(?s).*\\p{L}.*[:\uFF1A]\\s*[-\u2013\u2014]?\\s*$";
    private static final String[] KEYS={"NTT","SCT","SCC"};
    private static final String[] HASHES={"60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208"};
    private static final Method INTRODUCTION;
    static {try{INTRODUCTION=OerTemplateEvidence.class.getDeclaredMethod("sourceListIntroduction",String.class);INTRODUCTION.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static boolean introduction(String text)throws Exception{return (Boolean)INTRODUCTION.invoke(null,text);}
    private static void equivalent(String text)throws Exception{assertEquals(text.trim().matches(OLD),introduction(text),()->"UTF-8 source hash "+DocumentParseProbe.sha256(text.getBytes(StandardCharsets.UTF_8)));}

    @Test void unicodeLettersWhitespaceTerminatorsAndOptionalSingleDashesKeepTheirExactMeaning()throws Exception {
        for(String letter:new String[]{"A","\u4E2D","\u03B1","\uD835\uDC9C"})for(String colon:new String[]{":","\uFF1A"})for(String dash:new String[]{"","-","\u2013","\u2014"}) {
            assertTrue(introduction("\u0000\t"+letter+"\r\n"+colon+" \t"+dash+"\r\n\u0000"));
            for(String tail:new String[]{"X","--","\u2212","\u2010","\u00A0","\u2003","\u0085","\u2028","\u2029"})assertFalse(introduction(letter+colon+" "+dash+tail));
        }
        for(String text:new String[]{"",":","123:\u2014",":A","\u0301:","\uD835:","\uDC9C:","A: B:","A: B", "A:\nB:\n", "A: \u0000 -", "A\u0000:\u0000", "A:\t\n-\r\n"})equivalent(text);
        assertTrue(introduction("A: B:"));assertFalse(introduction("A: B"));
    }

    @Test void exhaustiveShortInputsAndSeededMixedUnicodeMatchTheFrozenExpression()throws Exception {
        String[] alphabet={"A","\u4E2D","\uD835\uDC9C",":","\uFF1A","-","\u2013","\u2014"," ","\n","\u00A0","\u0301","\u0000"};
        for(int length=0;length<=4;length++){int count=(int)Math.pow(alphabet.length,length);for(int ordinal=0;ordinal<count;ordinal++){StringBuilder text=new StringBuilder();int value=ordinal;for(int at=0;at<length;at++){text.append(alphabet[value%alphabet.length]);value/=alphabet.length;}equivalent(text.toString());}}
        Random random=new Random(20261020L);String[] extra={"\u0085","\u2028","\u2029","\t","\r","\f","\u000B","\uD835","\uDC9C","\u2212","\u2003","1","."};
        List<String> mixed=new ArrayList<>(Arrays.asList(alphabet));mixed.addAll(Arrays.asList(extra));
        for(int sample=0;sample<5000;sample++){StringBuilder text=new StringBuilder();int length=random.nextInt(65);for(int at=0;at<length;at++)text.append(mixed.get(random.nextInt(mixed.size())));equivalent(text.toString());}
    }

    @Test void aLongOrdinarySourceParagraphDoesNotSpendSecondsSearchingForAnAbsentColon()throws Exception {
        String text="a".repeat(65536)+".";long started=System.nanoTime();
        try{assertTimeout(Duration.ofSeconds(1),()->assertFalse(introduction(text)),"An ordinary source paragraph must not cause quadratic regex backtracking");}
        finally{writeMetric("ordinary-long-paragraph",Map.of("utf16Length",text.length(),"sourceSha256",DocumentParseProbe.sha256(text.getBytes(StandardCharsets.UTF_8)),"elapsedNanos",System.nanoTime()-started));}
    }

    @ParameterizedTest(name="complete actual evidence {0}") @ValueSource(ints={0,1,2})
    void completeActualSourceParseAndEveryLexicalDecisionMatchTheFrozenBeforeState(int source)throws Exception {
        String configured=System.getProperty("consense.acceptance.listIntroductionOutput");assumeTrue(configured!=null,"Explicit immutable before/after evidence directory required");
        Path output=Paths.get(configured);Files.createDirectories(output);byte[] original=DocxTemplateEditorTest.source(source),unchanged=original.clone();assertEquals(HASHES[source],DocumentParseProbe.sha256(original));
        OcrClient ocr=mock(OcrClient.class);DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);
        long started=System.nanoTime();DocumentParser.ParsedDocument parsed=parser.parseTemplate(KEYS[source],"actual-standard.docx",original);long elapsed=System.nanoTime()-started;verifyNoInteractions(ocr);assertArrayEquals(unchanged,original);
        JsonNode snapshot=JsonUtils.mapper().valueToTree(parsed);byte[] bytes=JsonUtils.write(snapshot).getBytes(StandardCharsets.UTF_8);Files.write(output.resolve(KEYS[source]+"-complete-parse.json"),bytes,StandardOpenOption.CREATE_NEW);
        int paragraphs=0,introductions=0;List<Map<String,Object>> decisions=new ArrayList<>();
        for(JsonNode member:parsed.getCoverage().getNativeDocxParagraphs().path("paragraphs")){String text=member.path("textObservation").path("text").asText();equivalent(text);boolean intro=introduction(text);paragraphs++;if(intro)introductions++;decisions.add(new TreeMap<>(Map.of("id",member.path("id").asText(),"textSha256",DocumentParseProbe.sha256(text.getBytes(StandardCharsets.UTF_8)),"introduction",intro)));}
        byte[] decisionBytes=JsonUtils.write(decisions).getBytes(StandardCharsets.UTF_8);Files.write(output.resolve(KEYS[source]+"-lexical-decisions.json"),decisionBytes,StandardOpenOption.CREATE_NEW);
        writeMetric(KEYS[source],Map.of("sourceSha256",HASHES[source],"parseElapsedNanos",elapsed,"completeParseBytes",bytes.length,"completeParseSha256",DocumentParseProbe.sha256(bytes),"paragraphs",paragraphs,"introductions",introductions,"lexicalDecisionsSha256",DocumentParseProbe.sha256(decisionBytes),"serializationIncludedInParseTiming",false));
        String baseline=System.getProperty("consense.acceptance.listIntroductionBaseline");if(baseline!=null){assertEquals(-1L,Files.mismatch(Paths.get(baseline).resolve(KEYS[source]+"-complete-parse.json"),output.resolve(KEYS[source]+"-complete-parse.json")),"Complete persisted source parse/evidence bytes differ");assertArrayEquals(Files.readAllBytes(Paths.get(baseline).resolve(KEYS[source]+"-lexical-decisions.json")),decisionBytes,"Every actual paragraph decision must stay exact");}
    }

    private static void writeMetric(String name,Map<String,Object> metric)throws Exception {
        String configured=System.getProperty("consense.acceptance.listIntroductionOutput");if(configured==null)return;Path output=Paths.get(configured);Files.createDirectories(output);Files.write(output.resolve(name+"-metric.json"),JsonUtils.write(metric).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
}
