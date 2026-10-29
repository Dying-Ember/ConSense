package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.common.BizException;
import com.consense.service.drafting.DraftTemplateGraphService;
import com.consense.service.drafting.DraftAutomationService;
import com.consense.web.dto.DraftingDtos.VariableVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/drafting/{projectId}/graph")
public class DraftTemplateGraphController {
    private final DraftTemplateGraphService service;
    private final DraftAutomationService automation;
    @PostMapping("/automation") public ApiResponse<Map<String,Object>> startAutomation(@PathVariable String projectId,@RequestBody Map<String,Object> body){requireAutomationExpected(body);String current=text(body.get("expectedCurrentRevisionId")),draft=text(body.get("expectedDraftRevisionId"));return ApiResponse.ok(body.containsKey("sourceIds")?automation.start(projectId,current,draft,automationSourceIds(body.get("sourceIds"))):automation.start(projectId,current,draft));}
    @GetMapping("/automation/latest") public ApiResponse<Map<String,Object>> latestAutomation(@PathVariable String projectId){return ApiResponse.ok(automation.latest(projectId));}
    @GetMapping("/automation/{id}") public ApiResponse<Map<String,Object>> automation(@PathVariable String projectId,@PathVariable String id){return ApiResponse.ok(automation.get(projectId,id));}
    @GetMapping("/automation/{id}/diagnostics.json") public ResponseEntity<ByteArrayResource> automationDiagnostics(@PathVariable String projectId,@PathVariable String id){
        byte[] bytes=automation.diagnostics(projectId,id);String fileName="draft-automation-"+id.replaceAll("[^A-Za-z0-9_-]","_")+"-diagnostics.json";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+fileName).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).contentLength(bytes.length).body(new ByteArrayResource(bytes));
    }
    @PostMapping("/automation/{id}/resume") public ApiResponse<Map<String,Object>> resumeAutomation(@PathVariable String projectId,@PathVariable String id,@RequestBody Map<String,Object> body){requireAutomationExpected(body);if(body.containsKey("sourceIds"))throw new BizException(4007,"AUTOMATION_SOURCE_SELECTION_IMMUTABLE_USE_NEW_RUN");return ApiResponse.ok(automation.resume(projectId,id,text(body.get("expectedCurrentRevisionId")),text(body.get("expectedDraftRevisionId"))));}
    @PostMapping("/automation/{id}/cancel") public ApiResponse<Map<String,Object>> cancelAutomation(@PathVariable String projectId,@PathVariable String id){return ApiResponse.ok(automation.cancel(projectId,id));}
    @GetMapping public ApiResponse<Map<String,Object>> current(@PathVariable String projectId){return ApiResponse.ok(service.current(projectId));}
    @PostMapping public ApiResponse<Map<String,Object>> save(@PathVariable String projectId,@RequestBody Map<String,Object> body){
        requireExpected(body);if(!body.containsKey("expectedDraftRevisionId"))throw new BizException(4090,"Expected draft graph revision is required, including null for a new draft.");
        if(body.containsKey("graphDelta")){if(body.containsKey("graph")||!(body.get("graphDelta") instanceof Map))throw new BizException(4007,"Supply either a graph or an existing-node delta.");return ApiResponse.ok(service.saveDelta(projectId,(Map<String,Object>)body.get("graphDelta"),text(body.get("expectedCurrentRevisionId")),text(body.get("expectedDraftRevisionId"))));}
        Object graph=body.get("graph");if(!(graph instanceof Map))throw new BizException(4007,"A graph object is required.");
        return ApiResponse.ok(service.save(projectId,(Map<String,Object>)graph,text(body.get("expectedCurrentRevisionId")),text(body.get("expectedDraftRevisionId")),true));
    }
    @PostMapping("/bootstrap") public ApiResponse<Map<String,Object>> bootstrap(@PathVariable String projectId){return ApiResponse.ok(service.bootstrap(projectId));}
    @GetMapping("/revisions") public ApiResponse<List<Map<String,Object>>> history(@PathVariable String projectId){return ApiResponse.ok(service.history(projectId));}
    @GetMapping("/revisions/{revisionId}") public ApiResponse<Map<String,Object>> revision(@PathVariable String projectId,@PathVariable String revisionId){return ApiResponse.ok(service.revision(projectId,revisionId));}
    @PostMapping("/revisions/{revisionId}/confirm") public ApiResponse<Map<String,Object>> confirm(@PathVariable String projectId,@PathVariable String revisionId,@RequestBody Map<String,Object> body){requireExpected(body);return ApiResponse.ok(service.confirm(projectId,revisionId,text(body.get("expectedCurrentRevisionId"))));}
    @PostMapping("/revisions/{revisionId}/confirm-definitions") public ApiResponse<Map<String,Object>> confirmDefinitions(@PathVariable String projectId,@PathVariable String revisionId,@RequestBody Map<String,Object> body){requireExpected(body);if(!(body.get("reason") instanceof String)||!(body.get("acknowledgeUnresolved") instanceof Boolean)||body.containsKey("reuseForEdition")&&!(body.get("reuseForEdition") instanceof Boolean)||body.containsKey("completeEdition")&&!(body.get("completeEdition") instanceof Boolean))throw new BizException(4007,"Graph confirmation reason and unresolved acknowledgement are required.");List<String> ids=null;if(body.containsKey("fieldIds")){if(!(body.get("fieldIds") instanceof List)||((List<?>)body.get("fieldIds")).size()>4096)throw new BizException(4007,"GRAPH_DEFINITION_SELECTION_INVALID");ids=new ArrayList<>();for(Object id:(List<?>)body.get("fieldIds")){if(!(id instanceof String)||((String)id).trim().isEmpty())throw new BizException(4007,"GRAPH_DEFINITION_SELECTION_INVALID");ids.add((String)id);}}return ApiResponse.ok(service.confirmDefinitions(projectId,revisionId,text(body.get("expectedCurrentRevisionId")),(String)body.get("reason"),(Boolean)body.get("acknowledgeUnresolved"),ids,Boolean.TRUE.equals(body.get("reuseForEdition")),Boolean.TRUE.equals(body.get("completeEdition"))));}
    @PostMapping("/revisions/{revisionId}/extract") public ApiResponse<List<VariableVO>> extract(@PathVariable String projectId,@PathVariable String revisionId){return ApiResponse.ok(service.extract(projectId,revisionId));}
    @PostMapping("/revisions/{revisionId}/discover") public ApiResponse<Map<String,Object>> discover(@PathVariable String projectId,@PathVariable String revisionId,@RequestBody Map<String,Object> body){requireExpected(body);Object budget=body.get("maxItems");if(!(budget instanceof Number)||((Number)budget).doubleValue()!=((Number)budget).intValue())throw new BizException(4007,"An explicit integer discovery budget is required.");return ApiResponse.ok(service.discover(projectId,revisionId,text(body.get("expectedCurrentRevisionId")),((Number)budget).intValue()));}
    @GetMapping("/revisions/{revisionId}/candidates") public ApiResponse<Map<String,Object>> candidates(@PathVariable String projectId,@PathVariable String revisionId){return ApiResponse.ok(service.candidates(projectId,revisionId));}
    @GetMapping("/revisions/{revisionId}/historical-projections/{nodeId}/sources") public ApiResponse<Map<String,Object>> historicalProjectionSources(@PathVariable String projectId,@PathVariable String revisionId,@PathVariable String nodeId){return ApiResponse.ok(service.historicalProjectionSources(projectId,revisionId,nodeId));}
    @PostMapping("/revisions/{revisionId}/candidates/{key}") public ApiResponse<Map<String,Object>> correctCandidate(@PathVariable String projectId,@PathVariable String revisionId,@PathVariable String key,@RequestBody Map<String,Object> body){return ApiResponse.ok(service.correctCandidate(projectId,revisionId,key,body));}
    @GetMapping("/sources") public ApiResponse<List<Map<String,Object>>> sources(@PathVariable String projectId){return ApiResponse.ok(service.sources(projectId));}
    @GetMapping("/sources/{sourceId}/reading") public ApiResponse<JsonNode> reading(@PathVariable String projectId,@PathVariable Long sourceId,@RequestParam(defaultValue="false") boolean compact){return ApiResponse.ok(service.reading(projectId,sourceId,compact));}
    @GetMapping("/revisions/{revisionId}/sources/{sourceId}/source") public ResponseEntity<ByteArrayResource> archivedSource(@PathVariable String projectId,@PathVariable String revisionId,@PathVariable String sourceId){
        Map<String,Object> source=service.archivedSource(projectId,revisionId,sourceId);String format=text(source.get("format")),fileName=text(source.get("fileName"));
        MediaType type="pdf".equals(format)?MediaType.APPLICATION_PDF:"docx".equals(format)?MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"):MediaType.APPLICATION_OCTET_STREAM;
        String encoded;try{encoded=java.net.URLEncoder.encode(fileName,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename*=UTF-8''"+encoded).contentType(type).body(new ByteArrayResource((byte[])source.get("bytes")));
    }
    private static String text(Object value){return value==null?null:String.valueOf(value);}
    static List<String> automationSourceIds(Object value){
        if(!(value instanceof List)||((List<?>)value).isEmpty()||((List<?>)value).size()>512)throw new BizException(4007,"AUTOMATION_SOURCE_SELECTION_INVALID");
        List<String> ids=new ArrayList<>();for(Object id:(List<?>)value){if(!(id instanceof String)||!((String)id).matches("[1-9][0-9]*")||ids.contains(id))throw new BizException(4007,"AUTOMATION_SOURCE_SELECTION_INVALID");try{Long.parseLong((String)id);}catch(NumberFormatException invalid){throw new BizException(4007,"AUTOMATION_SOURCE_SELECTION_INVALID");}ids.add((String)id);}return ids;
    }
    private static void requireAutomationExpected(Map<String,Object> body){requireExpected(body);if(!body.containsKey("expectedDraftRevisionId"))throw new BizException(4090,"Expected draft graph revision is required, including null.");}
    private static void requireExpected(Map<String,Object> body){if(!body.containsKey("expectedCurrentRevisionId"))throw new BizException(4090,"Expected current graph revision is required, including null for a new graph.");}
}
