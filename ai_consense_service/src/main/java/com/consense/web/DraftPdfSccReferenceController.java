package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.drafting.DraftPdfSccReference;
import com.consense.service.drafting.DraftTemplateGraphService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/drafting/{projectId}/graph/revisions/{revisionId}/pdf-scc-reference")
public class DraftPdfSccReferenceController {
    private final DraftTemplateGraphService graphs;
    private final DraftPdfSccReference reference;
    @GetMapping public ApiResponse<Map<String,Object>> search(@PathVariable String projectId,@PathVariable String revisionId,@RequestParam String sourceId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int offset){return ApiResponse.ok(reference.search(graphs.revision(projectId,revisionId),sourceId,q,offset));}
    @GetMapping("/facts") public ApiResponse<Map<String,Object>> facts(@PathVariable String projectId,@PathVariable String revisionId,@RequestParam String sourceId){return ApiResponse.ok(reference.facts(graphs.revision(projectId,revisionId),sourceId));}
    @GetMapping("/related-rules") public ApiResponse<Map<String,Object>> relatedRules(@PathVariable String projectId,@PathVariable String revisionId,@RequestParam String sourceId,@RequestParam(defaultValue="0") int offset){return ApiResponse.ok(reference.relatedRules(graphs.revision(projectId,revisionId),sourceId,offset));}
    @GetMapping("/{pointId}") public ApiResponse<Map<String,Object>> detail(@PathVariable String projectId,@PathVariable String revisionId,@RequestParam String sourceId,@PathVariable String pointId){return ApiResponse.ok(reference.detail(graphs.revision(projectId,revisionId),sourceId,pointId));}
}
