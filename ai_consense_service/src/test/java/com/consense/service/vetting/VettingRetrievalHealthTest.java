package com.consense.service.vetting;
import com.consense.common.JsonUtils;import com.consense.config.ConsenseProperties;import com.consense.ai.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;import org.springframework.boot.env.YamlPropertySourceLoader;import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.bind.*;import org.springframework.core.env.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class VettingRetrievalHealthTest {
 @Test void portableWindowSignatureRequiresExplicitV5AndKeepsFrozenRecipePins(){
  ObjectNode health=VettingStrictHybridTest.currentHealth().deepCopy();health.put("indexSignatureVersion",5);
  ((ObjectNode)health.path("retrieval").path("sourceWindows").path("algorithms")).put("portable_index_signature.py",VettingCorpus.hash("authored portable adapter"));
  ConsenseProperties p=new ConsenseProperties();assertEquals(5,p.getVetting().getExpectedRetrievalSignatureVersion());p.getVetting().setExpectedRetrievalSignatureVersion(3);assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(health,p.getVetting()));
  p.getVetting().setExpectedRetrievalSignatureVersion(5);VettingRetrievalHealth.Identity accepted=VettingRetrievalHealth.validate(health,p.getVetting());assertEquals(5,accepted.signatureVersion);
  p.getVetting().setExpectedRetrievalRecipeFingerprint(accepted.fingerprint);assertEquals(accepted.fingerprint,VettingRetrievalHealth.validate(health,p.getVetting()).fingerprint);
  p.getVetting().setExpectedRetrievalSignatureVersion(6);health.put("indexSignatureVersion",6);assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(health,p.getVetting()));
  p.getVetting().setExpectedRetrievalSignatureVersion(5);health.put("indexSignatureVersion",5);((ObjectNode)health.path("retrieval").path("sourceWindows").path("algorithms")).put("portable_index_signature.py",VettingCorpus.hash("changed adapter"));
  assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(health,p.getVetting()));
 }
 @Test void zeroTextStructuresStayInTheCorpusButCannotBecomeEmbeddingText(){
  HttpSupport http=mock(HttpSupport.class);ConsenseProperties p=VettingStrictHybridTest.strict();
  when(http.get(endsWith("/health"),anyLong())).thenReturn(JsonUtils.write(VettingStrictHybridTest.currentHealth()));
  VettingStrictHybridTest.indexResponse(http,VettingStrictHybridTest.receipt(1));
  VettingCorpus.Chunk text=VettingStrictHybridTest.source("text","tender","Actual source text");
  VettingCorpus.Chunk control=VettingStrictHybridTest.source("control","tender","");
  new VettingRetrievalClient(http,p).index("p",Arrays.asList(text,control));
  verify(http).postJson(endsWith("/index"),argThat(body->{JsonNode request=JsonUtils.parse(body);
   return request.path("chunks").size()==1&&"Actual source text".equals(request.path("chunks").get(0).path("content").asText())
    &&"control".equals(request.path("unindexedObservations").get(0).path("id").asText());}),anyLong());
 }
 @Test void actualLocalProfileBindsStrictCurrentContractAndWindowLimits()throws Exception{
  StandardEnvironment env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
  for(PropertySource<?>s:new YamlPropertySourceLoader().load("local",new ClassPathResource("application-vetting-local.yml")))env.getPropertySources().addLast(s);
  ConsenseProperties p=new ConsenseProperties();Binder.get(env).bind("consense",Bindable.ofInstance(p));
  assertTrue(p.getVetting().isRequireHybridRetrieval());assertEquals(5,p.getVetting().getExpectedRetrievalSignatureVersion());
  assertEquals(512,p.getVetting().getExpectedEmbeddingWindowMaxTokens());assertEquals(512,p.getVetting().getExpectedRerankWindowMaxTokens());assertEquals(64,p.getVetting().getExpectedWindowOverlapTokens());
  ObjectNode current=VettingStrictHybridTest.currentHealth().deepCopy();current.put("indexSignatureVersion",5);((ObjectNode)current.path("retrieval").path("sourceWindows").path("algorithms")).put("portable_index_signature.py",VettingCorpus.hash("authored portable adapter"));
  VettingRetrievalHealth.validate(current,p.getVetting());assertFalse(new ConsenseProperties().getVetting().isRequireHybridRetrieval());
  env.getPropertySources().addFirst(new MapPropertySource("legacy-pin",Collections.singletonMap("CONSENSE_VETTING_RETRIEVAL_EXPECTED_SIGNATURE_VERSION",3)));ConsenseProperties legacy=new ConsenseProperties();Binder.get(env).bind("consense",Bindable.ofInstance(legacy));
  assertEquals(3,legacy.getVetting().getExpectedRetrievalSignatureVersion());VettingRetrievalHealth.validate(VettingStrictHybridTest.currentHealth(),legacy.getVetting());
 }
 @Test void capturedCurrentRuntimeHealthIsAcceptedAndDynamicLoadedMemoryFlagsDoNotChangeRecipe(){
  JsonNode original=VettingStrictHybridTest.currentHealth();VettingRetrievalHealth.Identity a=VettingRetrievalHealth.validate(original,VettingStrictHybridTest.strict().getVetting());
  ObjectNode changed=original.deepCopy();changed.put("cudaAllocatedMiB",8000);((ObjectNode)changed.path("loaded")).put("embedding",true);
  assertEquals(a.fingerprint,VettingRetrievalHealth.validate(changed,VettingStrictHybridTest.strict().getVetting()).fingerprint);
 }
 @Test void versionsCapsOverlapModelAndAlgorithmCorruptionsCannotPass(){
  for(String bad:Arrays.asList("version","versionOverflow","contract","cap","capOverflow","overlap","aggregation","model","modelRevision","rerankRuntime","dtype","algorithm","roleFilter","rrf","bm25")){
   ObjectNode h=VettingStrictHybridTest.currentHealth().deepCopy();ObjectNode w=(ObjectNode)h.path("retrieval").path("sourceWindows");
   if(bad.equals("version"))h.put("indexSignatureVersion",2);
   if(bad.equals("versionOverflow"))h.put("indexSignatureVersion",new java.math.BigInteger("4294967299"));
   if(bad.equals("contract"))w.put("contract","legacy-parent-vector");
   if(bad.equals("cap"))((ObjectNode)w.path("embeddingWindows")).put("max_tokens",513);
   if(bad.equals("capOverflow"))((ObjectNode)w.path("embeddingWindows")).put("max_tokens",new java.math.BigInteger("4294967808"));
   if(bad.equals("overlap"))((ObjectNode)w.path("rerankWindows")).put("overlap_tokens",512);
   if(bad.equals("aggregation"))w.put("denseAggregation","sum");
   if(bad.equals("model"))((ObjectNode)h.path("runtime").path("embedding")).put("name","different");
   if(bad.equals("modelRevision"))((ObjectNode)h.path("models").path("embedding")).remove("revision");
   if(bad.equals("rerankRuntime"))((ObjectNode)w.path("rerankRuntime")).put("batchSize",99);
   if(bad.equals("dtype"))((ObjectNode)h.path("runtime").path("embedding")).put("dtype","float16");
   if(bad.equals("algorithm"))((ObjectNode)w.path("algorithms")).put("server.py","unbound");
   if(bad.equals("roleFilter"))((ObjectNode)h.path("retrieval")).put("roleFilter",false);
   if(bad.equals("rrf"))((ObjectNode)h.path("retrieval")).put("rrfConstant",0);
   if(bad.equals("bm25"))((ObjectNode)h.path("retrieval").path("bm25")).put("b",2);
   assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,VettingStrictHybridTest.strict().getVetting()),bad);
  }
 }
 @Test void externalRecipeSealMustMatchObservedPublicIdentity(){
  ConsenseProperties p=VettingStrictHybridTest.strict();JsonNode h=VettingStrictHybridTest.currentHealth();String fingerprint=VettingRetrievalHealth.validate(h,p.getVetting()).fingerprint;
  p.getVetting().setExpectedRetrievalRecipeFingerprint(fingerprint);assertEquals(fingerprint,VettingRetrievalHealth.validate(h,p.getVetting()).fingerprint);
  p.getVetting().setExpectedRetrievalRecipeFingerprint(String.join("",Collections.nCopies(64,"0")));assertThrows(IllegalStateException.class,()->VettingRetrievalHealth.validate(h,p.getVetting()));
 }
 @Test void strictHealthFailurePreventsAnyIndexRequest(){
  HttpSupport http=mock(HttpSupport.class);when(http.get(endsWith("/health"),anyLong())).thenReturn("{}");ConsenseProperties p=VettingStrictHybridTest.strict();
  assertThrows(IllegalStateException.class,()->new VettingRetrievalClient(http,p).index("p",Collections.singletonList(VettingStrictHybridTest.source("a","tender","source"))));verify(http,never()).postJson(anyString(),anyString(),anyLong());
 }
 @Test void recipeChangeAfterHandshakeInvalidatesReceiptBeforeQueryAndCannotResumeWithoutReindex(){
  HttpSupport http=mock(HttpSupport.class);ConsenseProperties p=VettingStrictHybridTest.strict();VettingStrictHybridTest.indexResponse(http,VettingStrictHybridTest.receipt(1));
  ObjectNode changed=VettingStrictHybridTest.currentHealth().deepCopy();((ObjectNode)changed.path("retrieval")).put("rrfConstant",61);
  when(http.get(endsWith("/health"),anyLong())).thenReturn(JsonUtils.write(VettingStrictHybridTest.currentHealth()),JsonUtils.write(changed),JsonUtils.write(VettingStrictHybridTest.currentHealth()));
  VettingCorpus.Chunk c=VettingStrictHybridTest.source("a","tender","source");VettingRetrievalClient client=new VettingRetrievalClient(http,p);client.index("p",Collections.singletonList(c));
  assertThrows(IllegalStateException.class,()->client.retrieve("p","source","tender",Collections.singletonList(c)));
  assertThrows(IllegalStateException.class,()->client.retrieve("p","source","tender",Collections.singletonList(c)));verify(http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
 }
}
