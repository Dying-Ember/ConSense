package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.SourceContentDigests;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored fixtures only; does not prove live Source or business acceptance. */
class OerVettingIndexGenerationTest {
    private static final String GENERATION="a".repeat(32);

    private static final class Fixture {
        final CanonicalEvidenceService registry=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient client=mock(OerCanonicalRetrievalClient.class);
        final CanonicalCurrentSourceReader current=mock(CanonicalCurrentSourceReader.class);
        final CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();
        final SourceDocument document=new SourceDocument();
        final CanonicalSourceBinding binding=new CanonicalSourceBinding();
        final OerBusinessEvidence service;
        Fixture(){
            config.setBaseUrl("http://127.0.0.1:1");document.setProjectId("project");document.setId(42L);
            document.setTextContentSha256("1".repeat(64));document.setStructuredContentSha256("2".repeat(64));
            document.setParseCoverageSha256("3".repeat(64));document.setNormalCandidateSha256("4".repeat(64));
            document.setBusinessContentSha256(SourceContentDigests.combined(document.getTextContentSha256(),document.getStructuredContentSha256(),document.getParseCoverageSha256(),document.getNormalCandidateSha256()));
            binding.setProjectId("project");binding.setDocumentId(42L);binding.setProjectionSha256("5".repeat(64));
            when(registry.currentBindings(eq("project"),eq("vetting"),anyList())).thenReturn(Collections.singletonList(binding));
            when(registry.currentDisplayBindings(eq("project"),eq("vetting"),anyList())).thenReturn(Collections.singletonList(binding));
            when(registry.currentDisplayBindings(eq("project"),eq("vetting"),anyList(),anyMap())).thenReturn(Collections.singletonList(binding));
            service=new OerBusinessEvidence(registry,client,config);ReflectionTestUtils.setField(service,"currentSources",current);
        }
        OerBusinessEvidence.Session session()throws Exception {
            Constructor<OerBusinessEvidence.Session> c=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class,Map.class);
            c.setAccessible(true);return c.newInstance("project","vetting",Collections.singletonList(42L),Collections.singletonList(binding),new LinkedHashMap<>(),Collections.emptyMap(),config.getVettingIndexGenerations());
        }
    }

    @Test void exactScopeIsStableAndDoesNotAffectOtherProjectsModulesOrSources(){
        List<String> projections=Arrays.asList("1".repeat(64),"2".repeat(64));
        Map<String,String> pins=new TreeMap<>();pins.put(projections.get(0),"3".repeat(64));
        String base=OerBusinessEvidence.canonicalBaseIndexKey("project","vetting",projections,pins);
        assertEquals("project::canonical::vetting::"+VettingCorpus.hash(JsonUtils.write(Arrays.asList(projections,pins))),base);
        assertEquals(base,OerBusinessEvidence.canonicalIndexGeneration(base,"vetting",Collections.emptyMap()));
        Map<String,String> config=Collections.singletonMap(base,GENERATION);
        String selected=OerBusinessEvidence.canonicalIndexGeneration(base,"vetting",config);
        assertEquals(base+"::g"+GENERATION,selected);
        assertEquals(selected,OerBusinessEvidence.canonicalIndexGeneration(base,"vetting",new LinkedHashMap<>(config)));
        String otherProject=OerBusinessEvidence.canonicalBaseIndexKey("other","vetting",projections,pins);
        String otherModule=OerBusinessEvidence.canonicalBaseIndexKey("project","advisory",projections,pins);
        String otherProjection=OerBusinessEvidence.canonicalBaseIndexKey("project","vetting",Collections.singletonList("4".repeat(64)),pins);
        String otherPin=OerBusinessEvidence.canonicalBaseIndexKey("project","vetting",projections,Collections.singletonMap(projections.get(0),"5".repeat(64)));
        for(String original:Arrays.asList(otherProject,otherModule,otherProjection,otherPin))
            assertEquals(original,OerBusinessEvidence.canonicalIndexGeneration(original,"vetting",config));
        assertEquals(base,OerBusinessEvidence.canonicalIndexGeneration(base,"advisory",config));
    }

    @Test void invalidOrOverlongGenerationIsRejected(){
        String base="project::canonical::vetting::"+"1".repeat(64);
        for(String invalid:Arrays.asList("", "A".repeat(32),"a".repeat(31),"../other"))
            assertThrows(IllegalStateException.class,()->OerBusinessEvidence.canonicalIndexGeneration(base,"vetting",Collections.singletonMap(base,invalid)));
        String longBase="p".repeat(170);
        assertThrows(IllegalStateException.class,()->OerBusinessEvidence.canonicalIndexGeneration(longBase,"vetting",Collections.singletonMap(longBase,GENERATION)));
    }

    @Test void externalPropertyBindingPreservesExactOriginalKey(){
        String base="project::canonical::vetting::"+"1".repeat(64);
        StandardEnvironment environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture",Collections.singletonMap(
                "consense.oer-evidence.vetting-index-generations.["+base+"]",GENERATION)));
        CanonicalEvidenceProperties bound=Binder.get(environment).bind("consense.oer-evidence",Bindable.of(CanonicalEvidenceProperties.class)).get();
        assertEquals(GENERATION,bound.getVettingIndexGenerations().get(base));
    }

    @Test void appSourceYamlWithColonKeyBindsTheExactMapping()throws Exception {
        String base="project::canonical::vetting::"+"1".repeat(64);
        String yaml="consense:\n  oer-evidence:\n    vetting-index-generations:\n      \"["+base+"]\": \""+GENERATION+"\"\n";
        Path file=Files.createTempFile("consense-generation-", ".yml");
        try {
            Files.write(file,yaml.getBytes(StandardCharsets.UTF_8));
            StandardEnvironment environment=new StandardEnvironment();
            for(org.springframework.core.env.PropertySource<?> source:new YamlPropertySourceLoader().load("fixture",new FileSystemResource(file)))
                environment.getPropertySources().addFirst(source);
            CanonicalEvidenceProperties bound=Binder.get(environment).bind("consense.oer-evidence",Bindable.of(CanonicalEvidenceProperties.class)).get();
            assertEquals(Collections.singletonMap(base,GENERATION),bound.getVettingIndexGenerations());
        } finally { Files.deleteIfExists(file); }
    }

    @Test void buildStatusAndOrdinaryRetrieveUseSameSelectedKey()throws Exception {
        Fixture wire=new Fixture();
        String base=wire.session().indexKey;
        wire.config.getVettingIndexGenerations().put(base,GENERATION);
        OerBusinessEvidence.Session session=wire.session();String key=base+"::g"+GENERATION;
        assertEquals(key,session.indexKey);
        wire.service.index(session);
        verify(wire.client).index(eq(key),eq("vetting"),anyList());
        when(wire.client.indexStatus(eq(key),eq("vetting"),anyList())).thenReturn(new OerCanonicalRetrievalClient.IndexState(true,"authored",1,null));
        wire.service.indexStatus(session);verify(wire.client).indexStatus(eq(key),eq("vetting"),anyList());
        wire.service.response(session,"fixture query",null);
        verify(wire.client).retrieve(eq(key),eq("vetting"),eq("fixture query"),isNull(),anyList());
        when(wire.client.sourceGenerationLinked(eq("vetting"),eq(key),anyString())).thenReturn(true);
        when(wire.client.indexAvailable(eq(key),eq("vetting"),anyList())).thenReturn(new OerCanonicalRetrievalClient.IndexState(true,"authored",1,null));
        assertEquals(key,wire.service.currentIndexAvailability("project","vetting",()->Collections.singletonList(wire.document)).indexKey);
    }

    @Test void changingGenerationInvalidatesAStartedSession()throws Exception {
        Fixture wire=new Fixture();String base=wire.session().indexKey;
        wire.config.getVettingIndexGenerations().put(base,GENERATION);
        OerBusinessEvidence.Session session=wire.session();
        wire.config.getVettingIndexGenerations().put(base,"b".repeat(32));
        assertThrows(IllegalStateException.class,()->wire.service.index(session));
        verify(wire.client,never()).index(anyString(),anyString(),anyList());
        assertThrows(IllegalStateException.class,()->wire.service.response(session,"fixture",null));
    }

    @Test void availabilityRejectsGenerationChangedDuringRemoteWait()throws Exception {
        Fixture wire=new Fixture();String base=wire.session().indexKey;
        wire.config.getVettingIndexGenerations().put(base,GENERATION);
        OerBusinessEvidence.Session session=wire.session();String key=session.indexKey;
        when(wire.client.indexAvailable(eq(key),eq("vetting"),anyList(),anyInt())).thenAnswer(call->{
            wire.config.getVettingIndexGenerations().put(base,"b".repeat(32));
            return new OerCanonicalRetrievalClient.IndexState(true,"authored",0,null);
        });
        assertThrows(IllegalStateException.class,()->wire.service.ensureAvailable(session,()->Collections.singletonList(wire.document)));
        verify(wire.client).indexAvailable(eq(key),eq("vetting"),anyList(),anyInt());
    }

    @Test void compactQueryScopeRejectsAChangedGenerationBeforeLookup()throws Exception {
        Fixture wire=new Fixture();String base=wire.session().indexKey;
        wire.config.getVettingIndexGenerations().put(base,GENERATION);
        OerBusinessEvidence.Session session=wire.session();
        ReflectionTestUtils.setField(session,"queryScope",Collections.singletonList(wire.binding));
        ReflectionTestUtils.setField(session,"querySelection",(java.util.function.Supplier<List<SourceDocument>>)()->Collections.singletonList(wire.document));
        ReflectionTestUtils.setField(session,"querySourceGeneration",VettingCorpus.hash(JsonUtils.write(Collections.singletonList(Arrays.asList(wire.document.getId(),wire.document.getBusinessContentSha256())))));
        ReflectionTestUtils.setField(session,"originalFileStates",Collections.emptyMap());
        wire.config.getVettingIndexGenerations().put(base,"b".repeat(32));
        assertThrows(IllegalStateException.class,()->wire.service.ensureAvailable(session,()->Collections.singletonList(wire.document)));
        verify(wire.client,never()).sourceGenerationLinked(anyString(),anyString(),anyString());
    }
}
