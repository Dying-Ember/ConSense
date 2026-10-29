package com.consense.service.export;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnglishSegmentCacheTest {
    @TempDir Path root;
    final AtomicInteger calls=new AtomicInteger();
    final List<String> submitted=new ArrayList<>();
    final Map<String,String> translations=new HashMap<>(),languages=new HashMap<>();
    final Set<Integer> emptyCalls=new HashSet<>();
    Runnable afterResponse=()->{};

    final class Fixture {
        final ConsenseProperties props=new ConsenseProperties();
        final LlmClient client=mock(LlmClient.class);
        final LlmProfiles profiles;
        final EnglishFileOutput output;
        Fixture(){this(value->{});}
        Fixture(Consumer<ConsenseProperties> configure){
            props.setStorageRoot(root.toString());props.getLlm().setEnabled(true);props.getLlm().setProvider("authored-provider");
            props.getLlm().setBaseUrl("http://authored.invalid");props.getLlm().setChatModel("authored-model");configure.accept(props);
            org.springframework.beans.BeanUtils.copyProperties(props.getLlm(),props.getMinimaxCn());props.getMinimaxCn().setApiKey("authored-placeholder");
            when(client.available()).thenReturn(true);
            doAnswer(call->{
                int number=calls.incrementAndGet();if(emptyCalls.contains(number))return "[]";List<LlmClient.ChatTurn> turns=call.getArgument(0);
                JsonNode input=JsonUtils.parse(turns.get(1).getContent());ArrayNode result=JsonUtils.mapper().createArrayNode();
                for(JsonNode item:input){String text=item.path("text").asText();submitted.add(text);boolean changed=translations.containsKey(text);
                    result.addObject().put("id",item.path("id").asText()).put("sourceLanguage",languages.getOrDefault(text,changed?"fr":"en"))
                            .put("translated",changed?1:0).put("text",changed?translations.get(text):"");}
                afterResponse.run();return JsonUtils.write(result);
            }).when(client).chatStructured(anyList(),any());
            profiles=new LlmProfiles(props,client,client,client,client);
            output=new EnglishFileOutput(new AiGateway(client,profiles),null,props);
        }
    }

    @Test void exactValidatedTextIsReusedAcrossReportReferencesAndExporterInstances(){
        String text="Payment is due within 14 days.";
        assertEquals(text,new Fixture().output.text(text,"report:first",()->{}));
        assertEquals(text,new Fixture().output.text(text,"report:second",()->{}));
        assertEquals(1,calls.get(),"A new report reference must not repeat exact validated language work");
        assertEquals(List.of(text),submitted);
    }

    @Test void fullyValidatedBatchesSurviveALaterFailureWithoutPublishingPartialOutput()throws Exception{
        Fixture fixture=new Fixture();ArrayNode notes=JsonUtils.mapper().createArrayNode();for(int i=0;i<65;i++)notes.add("English report segment "+i);
        JsonNode source=JsonUtils.mapper().createObjectNode().set("notes",notes);emptyCalls.addAll(List.of(2,3));
        assertThrows(BizException.class,()->fixture.output.json(source,"report:later-failure",()->{}));assertEquals(3,calls.get());
        try(Stream<Path> files=Files.list(root.resolve("english-file-output").resolve(EnglishFileOutput.POLICY))){assertEquals(32,files.filter(path->path.getFileName().toString().startsWith("segment-")).count());}
        submitted.clear();emptyCalls.clear();assertEquals(source,fixture.output.json(source,"report:retry",()->{}));
        assertEquals(5,calls.get());assertEquals(33,submitted.size());assertEquals("English report segment 32",submitted.get(0));
    }

    @Test void validSegmentsSurviveTargetedRecheckFailureWithoutPublishingMixedOutput()throws Exception{
        Fixture fixture=new Fixture();String english="English contract clause 7.",foreign="\u8bf7\u572814\u5929\u5185\u4ed8\u6b3e\u3002";
        translations.put(foreign,"Please pay within 21 days.");languages.put(foreign,"zh");
        JsonNode source=JsonUtils.mapper().createObjectNode().set("notes",JsonUtils.mapper().createArrayNode().add(english).add(foreign));
        assertThrows(BizException.class,()->fixture.output.json(source,"authored-first",()->{}));
        assertEquals(2,calls.get());assertEquals(List.of(english,foreign,foreign),submitted);
        try(Stream<Path> files=Files.list(root.resolve("english-file-output").resolve(EnglishFileOutput.POLICY))){assertEquals(1,files.filter(path->path.getFileName().toString().startsWith("segment-")).count());}
        translations.put(foreign,"Please pay within 14 days.");submitted.clear();
        JsonNode result=fixture.output.json(source,"authored-second",()->{});
        assertEquals(3,calls.get());assertEquals(List.of(foreign),submitted);
        assertEquals(english,result.path("notes").get(0).asText());assertEquals("English translation: Please pay within 14 days.",result.path("notes").get(1).asText());
    }

    @Test void validPeersAreCachedWhenAnUncertainLanguageRecheckRemainsInvalid()throws Exception{
        Fixture fixture=new Fixture();String english="An English finding.",french="Paiement dans 14 jours.";
        languages.put(french,"und");
        afterResponse=()->{if(calls.get()==1){languages.put(french,"fr");translations.put(french,"Payment within 21 days.");}};
        JsonNode source=JsonUtils.mapper().createObjectNode().set("notes",JsonUtils.mapper().createArrayNode().add(english).add(french));
        assertThrows(BizException.class,()->fixture.output.json(source,"authored-uncertain-first",()->{}));
        assertEquals(3,calls.get());assertEquals(List.of(english,french,french,french),submitted);
        try(Stream<Path> files=Files.list(root.resolve("english-file-output").resolve(EnglishFileOutput.POLICY))){assertEquals(1,files.filter(path->path.getFileName().toString().startsWith("segment-")).count());}
        translations.put(french,"Payment within 14 days.");submitted.clear();
        JsonNode result=fixture.output.json(source,"authored-uncertain-second",()->{});
        assertEquals(List.of(french),submitted);assertEquals(4,calls.get());
        assertEquals(english,result.path("notes").get(0).asText());assertEquals("English translation: Payment within 14 days.",result.path("notes").get(1).asText());
    }

    @Test void changedListReusesEnglishAndTranslatedSegmentsButAssessesNewText(){
        String english="Payment is due within 14 days.",french="Bonjour, paiement dans 14 jours.",added="A new remark requires review.";
        translations.put(french,"Hello, payment is due within 14 days.");languages.put(french,"fr");Fixture fixture=new Fixture();
        JsonNode first=JsonUtils.parse("{\"notes\":[]}");((ArrayNode)first.path("notes")).add(english).add(french);
        JsonNode second=JsonUtils.parse("{\"notes\":[]}");((ArrayNode)second.path("notes")).add(french).add(added).add(english);
        String original=JsonUtils.write(second);fixture.output.json(first,"report:first-list",()->{});
        JsonNode result=fixture.output.json(second,"report:changed-list",()->{});
        assertEquals(List.of(english,french,added),submitted,"Only a cache miss is supplied for language assessment");
        assertEquals(2,calls.get());assertEquals("English translation: Hello, payment is due within 14 days.",result.path("notes").get(0).asText());
        assertEquals(added,result.path("notes").get(1).asText());assertEquals(english,result.path("notes").get(2).asText());assertEquals(original,JsonUtils.write(second));
    }

    @ParameterizedTest @ValueSource(strings={"source","digits","foreign","id","rows"})
    void corruptSegmentCannotBypassExistingValidation(String mutation)throws Exception{
        String source="Bonjour, paiement dans 14 jours.";translations.put(source,"Hello, payment is due within 14 days.");languages.put(source,"fr");Fixture fixture=new Fixture();
        fixture.output.text(source,"report:valid",()->{});Path file=segmentFile();ObjectNode saved=(ObjectNode)JsonUtils.parse(Files.readString(file));ObjectNode row=(ObjectNode)saved.path("segments").get(0);
        if("source".equals(mutation))row.put("sourceTextSha256","0".repeat(64));
        else if("digits".equals(mutation))row.put("text","Payment is due within 21 days.");
        else if("foreign".equals(mutation))row.put("text","\u4ed8\u6b3e 14");
        else if("id".equals(mutation))row.put("id","T1");
        else ((ArrayNode)saved.path("segments")).add(row.deepCopy());
        Files.writeString(file,JsonUtils.write(saved));
        assertThrows(BizException.class,()->fixture.output.text(source,"report:corrupt",()->{}));assertEquals(1,calls.get(),"A corrupt cache is rejected before another paid request");
    }

    @ParameterizedTest @ValueSource(strings={"model","provider","profile","configuration"})
    void configuredIdentityChangesRequireFreshLanguageAssessment(String changed){
        String source="Payment is due within 14 days.";Fixture first=new Fixture();first.output.text(source,"report:first",()->{});
        Fixture next=new Fixture(props->{
            if("model".equals(changed))props.getLlm().setChatModel("other-authored-model");
            else if("provider".equals(changed))props.getLlm().setProvider("other-authored-provider");
            else if("configuration".equals(changed))props.getLlm().setBaseUrl("http://other-authored.invalid");
        });
        try(LlmProfiles.Scope selected="profile".equals(changed)?next.profiles.bind(next.profiles.resolve("minimax-cn")):null){
            assertEquals(source,next.output.text(source,"report:next",()->{}));
        }
        assertEquals(2,calls.get(),"The complete captured model identity partitions segment reuse");
    }

    @Test void aDifferentStoredPolicyRequiresReassessmentBeforeReuse()throws Exception{
        String source="Payment is due within 14 days.";Fixture fixture=new Fixture();fixture.output.text(source,"report:first",()->{});
        Path file=segmentFile();ObjectNode saved=(ObjectNode)JsonUtils.parse(Files.readString(file));saved.put("policy","english-delivery-copy-v6");Files.writeString(file,JsonUtils.write(saved));
        assertEquals(source,fixture.output.text(source,"report:policy-changed",()->{}));assertEquals(2,calls.get());
        assertEquals(source,fixture.output.text(source,"report:policy-repaired",()->{}));assertEquals(2,calls.get());
    }

    @Test void aCacheHitStillRunsTheCurrentSourceGuard(){
        String source="Payment is due within 14 days.";Fixture fixture=new Fixture();fixture.output.text(source,"report:first",()->{});AtomicInteger guards=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->fixture.output.text(source,"report:stale",()->{if(guards.incrementAndGet()>1)throw new IllegalStateException("Authored source changed");}));
        assertTrue(guards.get()>1);assertEquals(1,calls.get());
    }

    @Test void partialCacheReuseRechecksCurrentSourcesBeforeAssessingMisses(){
        Fixture fixture=new Fixture();String cached="Payment is due within 14 days.";fixture.output.text(cached,"report:first",()->{});
        JsonNode expanded=JsonUtils.parse("{\"notes\":[]}");((ArrayNode)expanded.path("notes")).add(cached).add("A new remark requires review.");AtomicInteger guards=new AtomicInteger();
        assertThrows(RuntimeException.class,()->fixture.output.json(expanded,"report:changed-source",()->{if(guards.incrementAndGet()==4)throw new IllegalStateException("Authored source changed during cache reading");}));
        assertEquals(1,calls.get(),"A stale source must be rejected after cache reading and before a cache-miss model request");
    }

    @Test void aProfileChangeAfterCaptureRejectsACacheHit(){
        String source="Payment is due within 14 days.";Fixture fixture=new Fixture();fixture.output.text(source,"report:first",()->{});AtomicInteger guards=new AtomicInteger();List<LlmProfiles.Scope> selections=new ArrayList<>();
        try{
            BizException failure=assertThrows(BizException.class,()->fixture.output.text(source,"report:changed-model",()->{if(guards.incrementAndGet()==2)selections.add(fixture.profiles.bind(fixture.profiles.resolve("minimax-cn")));}));
            assertTrue(failure.getMessage().contains("model changed"));assertEquals(1,calls.get());
        }finally{for(LlmProfiles.Scope selected:selections)selected.close();}
    }

    @ParameterizedTest @ValueSource(strings={"legacy","missing-provider"})
    void unprovenModelIdentityKeepsOnlyTheExistingReferenceScopedCache(String kind){
        Fixture fixture=new Fixture(props->{if("missing-provider".equals(kind))props.getLlm().setProvider("");});
        EnglishFileOutput exporter="legacy".equals(kind)?new EnglishFileOutput(new AiGateway(fixture.client),null,fixture.props):fixture.output;
        String source="Payment is due within 14 days.";assertEquals(source,exporter.text(source,"report:first",()->{}));assertEquals(source,exporter.text(source,"report:second",()->{}));
        assertEquals(2,calls.get());assertEquals(source,exporter.text(source,"report:first",()->{}));assertEquals(2,calls.get());
    }

    @Test void changedNumericTextIsNeverTreatedAsAnExactSegment(){
        Fixture fixture=new Fixture();String first="Payment is due within 14 days.",changed="Payment is due within 21 days.";
        fixture.output.text(first,"report:first",()->{});assertEquals(changed,fixture.output.text(changed,"report:changed",()->{}));
        assertEquals(List.of(first,changed),submitted);assertEquals(2,calls.get());
    }

    @Test void aModelChangeDuringAssessmentDoesNotPublishOrSeedTheSegmentCache(){
        Fixture fixture=new Fixture();List<LlmProfiles.Scope> selections=new ArrayList<>();afterResponse=()->selections.add(fixture.profiles.bind(fixture.profiles.resolve("minimax-cn")));
        try{assertThrows(BizException.class,()->fixture.output.text("Payment is due within 14 days.","report:late-model-change",()->{}));
            assertEquals(1,calls.get());assertFalse(Files.exists(root.resolve("english-file-output").resolve(EnglishFileOutput.POLICY)));
        }finally{for(LlmProfiles.Scope selected:selections)selected.close();}
    }

    @Test void invalidNewTextCannotProduceAPartialDeliveryOrInvalidateAValidatedPeer(){
        Fixture fixture=new Fixture();String english="Payment is due within 14 days.",foreign="Bonjour, paiement dans 14 jours.";languages.put(foreign,"fr");
        fixture.output.text(english,"report:valid",()->{});JsonNode invalid=JsonUtils.parse("{\"notes\":[]}");((ArrayNode)invalid.path("notes")).add(english).add(foreign);
        assertThrows(BizException.class,()->fixture.output.json(invalid,"report:invalid-added-text",()->{}));assertEquals(3,calls.get());
        assertEquals(english,fixture.output.text(english,"report:valid-peer",()->{}));assertEquals(3,calls.get());
    }

    private Path segmentFile()throws Exception{
        try(Stream<Path> files=Files.list(root.resolve("english-file-output").resolve(EnglishFileOutput.POLICY))){
            return files.filter(path->path.getFileName().toString().startsWith("segment-")).findFirst().orElseThrow(()->new AssertionError("A validated segment cache was not written"));
        }
    }
}
