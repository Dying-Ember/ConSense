package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.common.ApiResponse;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.web.dto.DraftingDtos.VariableVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.persistence.*;
import org.h2.tools.Server;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
 "spring.jpa.properties.hibernate.generate_statistics=true","spring.jpa.show-sql=true",
 "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.consense.service.drafting.ValueTextTcpJdbcProbe$Sql",
 "spring.datasource.hikari.minimum-idle=0","spring.datasource.hikari.maximum-pool-size=2",
 "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="consense.probe.tcp.enabled",matches="true")
@org.springframework.context.annotation.Import(ValueTextTcpJdbcProbe.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DraftingValueTextTcpBindingTest {
 static final String DB="value_text_"+UUID.randomUUID().toString().replace("-","");
 static Server server;static String url;
 @DynamicPropertySource static void resources(DynamicPropertyRegistry r)throws Exception{
  server=Server.createTcpServer("-tcpPort","0","-tcpDaemon","-ifNotExists").start();
  Field f=Server.class.getDeclaredField("service");f.setAccessible(true);Object service=f.get(server);
  Field sf=service.getClass().getDeclaredField("serverSocket");sf.setAccessible(true);ServerSocket socket=(ServerSocket)sf.get(service);
  assertTrue(socket.getInetAddress().isLoopbackAddress());assertNotEquals(18082,server.getPort());assertNotEquals(18084,server.getPort());
  url="jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/mem:"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
  r.add("spring.datasource.url",()->url);r.add("consense.storage-root",()->Paths.get("target","uploads",DB).toAbsolutePath().toString());
 }
 @Autowired DraftingService service;@Autowired ProjectRepository projects;@Autowired DraftVariableRepository variables;
 @Autowired EntityManagerFactory emf;@Autowired ObjectMapper mapper;
 Path output(){return Paths.get(System.getProperty("consense.probe.output")).toAbsolutePath();}
 void save(String name,Object value)throws Exception{Files.write(output().resolve(name),JsonUtils.write(value).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
 @Test void originalEightyInputPathAndCompleteHistoricalValuesSurviveTcp()throws Throwable{
  boolean red="red".equals(System.getProperty("consense.probe.variant"));
  assertEquals(red,DraftVariable.class.getDeclaredField("valueText").isAnnotationPresent(Lob.class));
  assertEquals("TEXT",DraftVariable.class.getDeclaredField("valueText").getAnnotation(Column.class).columnDefinition());
  for(String field:Arrays.asList("optionsJson","resultText","noteText","candidatesJson","adoptedSourcesJson","colsJson"))assertTrue(DraftVariable.class.getDeclaredField(field).isAnnotationPresent(Lob.class),field);
  List<Map<String,Object>> ddl=new ArrayList<>();
  try(Connection c=DriverManager.getConnection(url,"sa","");ResultSet rs=c.getMetaData().getColumns(null,null,"draft_variable",null)){
   while(rs.next())ddl.add(new LinkedHashMap<String,Object>(){{put("column",rs.getString("COLUMN_NAME"));put("jdbcType",rs.getInt("DATA_TYPE"));put("type",rs.getString("TYPE_NAME"));put("size",rs.getLong("COLUMN_SIZE"));put("nullable",rs.getInt("NULLABLE"));}});
  }
  save("physical-columns.json",ddl);save("owned-resource.json",Arrays.asList(url,DB,server.getPort(),System.getProperty("h2.lobClientMaxSizeMemory"),Runtime.getRuntime().maxMemory()));
  Project p=new Project();p.setId("value-text-input-"+UUID.randomUUID());p.setNameZhHans("Fictional owned TCP default input fixture");projects.save(p);
  Statistics stats=emf.unwrap(SessionFactory.class).getStatistics();stats.clear();ValueTextTcpJdbcProbe.begin();List<VariableVO> fresh;
  try{fresh=service.listVariables(p.getId());}
  catch(Throwable failure){ValueTextTcpJdbcProbe.finish("fresh-failed",stats);save("fresh-failure.json",Arrays.asList(failure.getClass().getName(),failure.getMessage(),Arrays.asList(failure.getStackTrace())));throw failure;}
  ValueTextTcpJdbcProbe.finish("fresh",stats);
  assertEquals(1,stats.getTransactionCount());assertEquals(1,stats.getSuccessfulTransactionCount());assertEquals(80,stats.getEntityInsertCount());assertEquals(0,stats.getEntityUpdateCount());
  assertEquals(80,fresh.size());assertTrue(fresh.stream().allMatch(v->!v.isConfirmed()&&!v.isManuallyEdited()&&v.getValue().isEmpty()));
  assertEquals(80,fresh.stream().map(VariableVO::getKey).distinct().count());
  byte[] expected=Files.readAllBytes(Paths.get(System.getProperty("consense.probe.expectedBody")));
  byte[] freshBody=mapper.writeValueAsBytes(ApiResponse.ok(fresh));Files.write(output().resolve("fresh-complete-response.bin"),freshBody,StandardOpenOption.CREATE_NEW);
  assertEquals(mapper.readTree(expected).get("data"),mapper.valueToTree(fresh));assertArrayEquals(expected,freshBody);
  stats.clear();ValueTextTcpJdbcProbe.begin();List<VariableVO> warm=service.listVariables(p.getId());ValueTextTcpJdbcProbe.finish("warm",stats);
  byte[] warmBody=mapper.writeValueAsBytes(ApiResponse.ok(warm));Files.write(output().resolve("warm-complete-response.bin"),warmBody,StandardOpenOption.CREATE_NEW);assertArrayEquals(freshBody,warmBody);
  StringBuilder text=new StringBuilder();for(int i=0;i<12000;i++)text.append("中文測試\uD83D\uDE00");String longText=text.toString();assertTrue(longText.length()>65536);
  List<Object> history=new ArrayList<>();int index=0;
  for(String value:Arrays.asList(null,"",longText)){
   DraftVariable v=historical(p.getId(),"_history_"+index++,value);v=variables.save(v);
   String first=read(v.getId());assertEquals(value,JsonUtils.parse(first).path("valueText").isNull()?null:JsonUtils.parse(first).path("valueText").asText());
   service.listVariables(p.getId());String after=read(v.getId());assertEquals(first,after,"all persisted historical fields unchanged");
   history.add(Arrays.asList(v.getVarKey(),value==null?null:value.length(),first,after));
  }
  DraftVariable adopted=variables.findByProjectIdAndVarKey(p.getId(),"nscAlternativeText").get();adopted.setValueText(longText);adopted.setConfirmed(true);adopted.setManuallyEdited(true);adopted.setReviewRequired(true);adopted.setConfirmedFrom("MANUAL");adopted.setSourceRef("Fictional preserved source quote 🌟");adopted.setResultText("Preserved result 🌟");adopted.setNoteText("Preserved note 🌟");adopted.setCandidatesJson("[]");adopted.setAdoptedSourcesJson("[]");variables.save(adopted);
  String beforeAdopted=read(adopted.getId());service.listVariables(p.getId());String afterAdopted=read(adopted.getId());assertEquals(beforeAdopted,afterAdopted);
  save("history-full-fields.json",history);save("adopted-input-full-fields.json",Arrays.asList(beforeAdopted,afterAdopted));
  save("assertions.json",Arrays.asList("80 empty/unconfirmed/notmanual unique variables","fresh complete38564byte response exact","warm equals fresh","physical full column metadata retained","null/empty/72000UTF16 long unicode roundtrip","all fields of three historical rows exact before/after","adopted input flags/source and full fields exact before/after","other six @Lob annotations retained","not MySQL acceptance"));
 }
 DraftVariable historical(String project,String key,String value){
  DraftVariable v=new DraftVariable();v.setProjectId(project);v.setVarKey(key);v.setScope("FILE");v.setFileKey("HISTORY");v.setValueText(value);v.setAction("fill");v.setKind("text");v.setAffects("NTT,SCT,SCC");v.setLabelEn("Preserved history");v.setLabelZhHans("历史保留");v.setLabelZhHant("歷史保留");v.setConfirmed(true);v.setManuallyEdited(true);v.setReviewRequired(true);v.setConfirmedFrom("MANUAL");v.setSourceRef("Preserved fictional original source 🌟");v.setChoice("kept");v.setOptionsJson("[\"保留🌟\"]");v.setCandidatesJson("[]");v.setAdoptedSourcesJson("[]");v.setResultText("保留结果🌟");v.setNoteText("保留备注🌟");v.setColsJson("[\"保留列🌟\"]");v.setLinkedBase("legacyBase");v.setDerivedFrom("legacyDependency");v.setSortOrder(1000);v.setUpdatedAt(Instant.now());return v;
 }
 String read(Long id){EntityManager em=emf.createEntityManager();try{em.getTransaction().begin();DraftVariable actual=em.find(DraftVariable.class,id);String json=JsonUtils.write(actual);em.getTransaction().commit();return json;}finally{em.close();}}
}
