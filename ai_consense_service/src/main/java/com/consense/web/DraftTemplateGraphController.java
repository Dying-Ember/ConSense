package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.common.BizException;
import com.consense.service.drafting.DraftTemplateGraphService;
import com.consense.service.drafting.DraftAutomationService;
import com.consense.web.dto.DraftingDtos.VariableVO;
import com.fasterxml.jackson.databind.JsonNode;
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
    @PostMapping("/automation") public ApiResponse<Map<String,Object>> startAutomation(@PathVariable String projectId,@RequestBody Map<String,Object> body){requireAutomationExpected(body);return ApiResponse.ok(automation.start(projectId,text(body.get("expectedCurrentRevisionId")),text(body.get("expectedDraftRevisionId"))));}
    @GetMapping("/automation/latest") public ApiResponse<Map<String,Object>> latestAutomation(@PathVariable String projectId){return ApiResponse.ok(automation.latest(projectId));}
    @GetMapping("/automation/{id}") public ApiResponse<Map<String,Object>> automation(@PathVariable String projectId,@PathVariable String id){return ApiResponse.ok(automation.get(projectId,id));}
    @PostMapping("/automation/{id}/resume") public ApiResponse<Map<String,Object>> resumeAutomation(@PathVariable String projectId,@PathVariable String id,@RequestBody Map<String,Object> body){requireAutomationExpected(body);return ApiResponse.ok(automation.resume(projectId,id,text(body.get("expectedCurrentRevisionId")),text(body.get("expectedDraftRevisionId"))));}
    @PostMapping("/automation/{id}/cancel") public ApiResponse<Map<String,Object>> cancelAutomation(@PathVariable String projectId,@PathVariable String id){return ApiResponse.ok(automation.cancel(projectId,id));}
    @GetMapping public ApiResponse<Map<String,Object>> current(@PathVariable String projectId){return ApiResponse.ok(service.current(projectId));}
    @PostMapping public ApiResponse<Map<String,Object>> save(@PathVariable String projectId,@RequestBody Map<String,Object> body){
        requireExpected(body);if(!body.containsKey("expectedDraftRevisionId"))throw new BizException(4090,"Expected draft graph revision is required, including null for a new draft.");Object graph=body.get("graph");if(!(graph instanceof Map))throw new BizException(4007,"A graph object is required.");
        return ApiResponse.ok(service.save(projectId,(Map<String,Object>)graph,text(body.get("expectedCurrentRevisionId")),text(body.get("expectedDraftRevisionId")),true));
    }
    @PostMapping("/bootstrap") public ApiResponse<Map<String,Object>> bootstrap(@PathVariable String projectId){return ApiResponse.ok(service.bootstrap(projectId));}
    @GetMapping("/revisions") public ApiResponse<List<Map<String,Object>>> history(@PathVariable String projectId){return ApiResponse.ok(service.history(projectId));}
    @GetMapping("/revisions/{revisionId}") public ApiResponse<Map<String,Object>> revision(@PathVariable String projectId,@PathVariable String revisionId){return ApiResponse.ok(service.revision(projectId,revisionId));}
    @PostMapping("/revisions/{revisionId}/confirm") public ApiResponse<Map<String,Object>> confirm(@PathVariable String projectId,@PathVariable String revisionId,@RequestBody Map<String,Object> body){requireExpected(body);return ApiResponse.ok(service.confirm(projectId,revisionId,text(body.get("expectedCurrentRevisionId"))));}
    @PostMapping("/revisions/{revisionId}/extract") public ApiResponse<List<VariableVO>> extract(@PathVariable String projectId,@PathVariable String revisionId){return ApiResponse.ok(service.extract(projectId,revisionId));}
    @PostMapping("/revisions/{revisionId}/discover") public ApiResponse<Map<String,Object>> discover(@PathVariable String projectId,@PathVariable String revisionId,@RequestBody Map<String,Object> body){requireExpected(body);Object budget=body.get("maxItems");if(!(budget instanceof Number)||((Number)budget).doubleValue()!=((Number)budget).intValue())throw new BizException(4007,"An explicit integer discovery budget is required.");return ApiResponse.ok(service.discover(projectId,revisionId,text(body.get("expectedCurrentRevisionId")),((Number)budget).intValue()));}
    @GetMapping("/revisions/{revisionId}/candidates") public ApiResponse<Map<String,Object>> candidates(@PathVariable String projectId,@PathVariable String revisionId){return ApiResponse.ok(service.candidates(projectId,revisionId));}
    @PostMapping("/revisions/{revisionId}/candidates/{key}") public ApiResponse<Map<String,Object>> correctCandidate(@PathVariable String projectId,@PathVariable String revisionId,@PathVariable String key,@RequestBody Map<String,Object> body){return ApiResponse.ok(service.correctCandidate(projectId,revisionId,key,body));}
    @GetMapping("/sources") public ApiResponse<List<Map<String,Object>>> sources(@PathVariable String projectId){return ApiResponse.ok(service.sources(projectId));}
    @GetMapping("/sources/{sourceId}/reading") public ApiResponse<JsonNode> reading(@PathVariable String projectId,@PathVariable Long sourceId){return ApiResponse.ok(service.reading(projectId,sourceId));}
    @GetMapping("/revisions/{revisionId}/sources/{sourceId}/source") public ResponseEntity<ByteArrayResource> archivedSource(@PathVariable String projectId,@PathVariable String revisionId,@PathVariable String sourceId){
        Map<String,Object> source=service.archivedSource(projectId,revisionId,sourceId);String format=text(source.get("format")),fileName=text(source.get("fileName"));
        MediaType type="pdf".equals(format)?MediaType.APPLICATION_PDF:"docx".equals(format)?MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"):MediaType.APPLICATION_OCTET_STREAM;
        String encoded;try{encoded=java.net.URLEncoder.encode(fileName,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename*=UTF-8''"+encoded).contentType(type).body(new ByteArrayResource((byte[])source.get("bytes")));
    }
    private static String text(Object value){return value==null?null:String.valueOf(value);}
    private static void requireAutomationExpected(Map<String,Object> body){requireExpected(body);if(!body.containsKey("expectedDraftRevisionId"))throw new BizException(4090,"Expected draft graph revision is required, including null.");}
    private static void requireExpected(Map<String,Object> body){if(!body.containsKey("expectedCurrentRevisionId"))throw new BizException(4090,"Expected current graph revision is required, including null for a new graph.");}
}
