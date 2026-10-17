package com.consense.web;

import com.consense.service.drafting.DraftingService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Reading an alternate edition must never select the primary template by family name. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/drafting/{projectId}/graph/sources/{sourceId}")
public class DraftReviewAssetController {
    private final DraftingService drafting;
    @GetMapping("/original")
    public ResponseEntity<ByteArrayResource> original(@PathVariable String projectId,@PathVariable Long sourceId,@RequestParam String sourceSha256) {
        return asset(drafting.reviewSource(projectId,sourceId,sourceSha256,false));
    }
    @GetMapping("/preview.pdf")
    public ResponseEntity<ByteArrayResource> preview(@PathVariable String projectId,@PathVariable Long sourceId,@RequestParam String sourceSha256) {
        return asset(drafting.reviewSource(projectId,sourceId,sourceSha256,true));
    }
    private ResponseEntity<ByteArrayResource> asset(DraftingService.TemplateSourceFile source) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType(source.getContentType())).body(new ByteArrayResource(source.getBytes()));
    }
}
