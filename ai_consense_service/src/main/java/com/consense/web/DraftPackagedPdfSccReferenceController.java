package com.consense.web;
import com.consense.common.ApiResponse;
import com.consense.service.drafting.DraftPdfSccReference;
import com.consense.service.drafting.DraftingService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Read-only historical reference; it does not select or edit either SCC edition. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/drafting/{projectId}/packaged-pdf-scc-reference")
public class DraftPackagedPdfSccReferenceController {
    private final DraftingService drafting;
    private final DraftPdfSccReference reference;
    private Map<String,Object> unavailable(){Map<String,Object> value=new LinkedHashMap<>();value.put("available",false);value.put("status","packaged_source_unavailable");value.put("executable",false);return value;}
    @GetMapping public ApiResponse<Map<String,Object>> search(@PathVariable String projectId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int offset) {
        return ApiResponse.ok(drafting.hasPackagedPdfReference(projectId)?reference.searchPackaged(q,offset):unavailable());
    }
    @GetMapping("/{pointId}") public ApiResponse<Map<String,Object>> detail(@PathVariable String projectId,@PathVariable String pointId) {
        return ApiResponse.ok(drafting.hasPackagedPdfReference(projectId)?reference.detailPackaged(pointId):unavailable());
    }
}
