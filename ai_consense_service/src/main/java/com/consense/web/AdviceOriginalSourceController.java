package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.advice.AdviceOriginalSourceService;
import com.consense.service.sources.CanonicalEvidenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/advice/{projectId}/sources/{documentId}/original")
public class AdviceOriginalSourceController {
    private final AdviceOriginalSourceService sources;
    @GetMapping
    public ResponseEntity<byte[]> original(@PathVariable String projectId,@PathVariable long documentId,
            @RequestParam String sourceHash,@RequestParam String originalSourceSha256,@RequestParam String sourceRole,
            @RequestParam(defaultValue="fullset") String scope,@RequestParam(required=false) String projectionSha256,
            @RequestParam(required=false) String unitId){
        AdviceOriginalSourceService.OriginalSource source=sources.original(projectId,documentId,sourceHash,originalSourceSha256,scope,projectionSha256,unitId,sourceRole);
        String filename=source.getFileName().toLowerCase(Locale.ROOT);
        MediaType type=filename.endsWith(".pdf")?MediaType.APPLICATION_PDF:filename.endsWith(".docx")
                ?MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"):MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(type).contentLength(source.getBytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(source.getFileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
                .header("X-Source-SHA256",source.getSha256()).header("X-Source-Revision",source.getSourceHash()).body(source.getBytes());
    }
    @ExceptionHandler(CanonicalEvidenceException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(CanonicalEvidenceException error){return ResponseEntity.status(error.getStatus()).body(ApiResponse.fail(4300,error.getMessage()));}
}
