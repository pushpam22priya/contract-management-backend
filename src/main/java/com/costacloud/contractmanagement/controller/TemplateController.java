package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/templates")
@Tag(name = "Templates", description = "Manage contract templates. Write operations restricted to ADMIN.")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(summary = "Create template metadata")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<Map<String, String>> createTemplate(@Valid @RequestBody TemplateRequest request) {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String id = templateService.createTemplate(request, email);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @Operation(summary = "List all templates")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<List<TemplateListResponse>> listTemplates() {
        return ResponseEntity.ok(templateService.listTemplates());
    }

    @Operation(summary = "Get full template by ID")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<TemplateResponse> getTemplate(@PathVariable String id) {
        return ResponseEntity.ok(templateService.getTemplate(id));
    }

    @Operation(summary = "Update template metadata")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/{id}")
    public ResponseEntity<TemplateResponse> updateTemplate(@PathVariable String id,
                                                           @Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.ok(templateService.updateTemplate(id, request));
    }

    @Operation(summary = "Delete template")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) throws Exception {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    // ─── File Endpoints ──────────────────────────────────────────

    @Operation(summary = "Upload PDF file (single shot)")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/{id}/file")
    public ResponseEntity<Void> uploadFile(@PathVariable String id,
                                           HttpServletRequest request) throws Exception {
        long fileSize = request.getContentLengthLong();
        if (fileSize > 50 * 1024 * 1024) {
            throw new RuntimeException("File size must not exceed 50MB");
        }
        templateService.uploadFile(id, request.getInputStream(), fileSize);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Fetch PDF file")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> getFile(@PathVariable String id) throws Exception {
        InputStream stream = templateService.getFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(stream));
    }

    @Operation(summary = "Get presigned URL for viewing PDF directly from MinIO (use with Apryse WebViewer)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/file/view-url")
    public ResponseEntity<Map<String, String>> getViewUrl(@PathVariable String id) throws Exception {
        String url = templateService.generatePresignedViewUrl(id);
        return ResponseEntity.ok(Map.of("url", url));
    }

    // ─── Chunked Upload Endpoints ────────────────────────────────

    @Operation(summary = "Initiate chunked upload")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/file/initiate")
    public ResponseEntity<ChunkUploadInitResponse> initiateChunkedUpload(@PathVariable String id) throws Exception {
        return ResponseEntity.ok(templateService.initiateChunkedUpload(id));
    }

    @Operation(summary = "Get presigned URL for uploading a single part directly to MinIO")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/file/presign")
    public ResponseEntity<Map<String, Object>> getPresignedPartUrl(@PathVariable String id,
                                                                    @RequestParam String uploadId,
                                                                    @RequestParam int partNumber) throws Exception {
        String url = templateService.generatePresignedPartUrl(id, uploadId, partNumber);
        return ResponseEntity.ok(Map.of("url", url, "partNumber", partNumber));
    }

    @Operation(summary = "Complete chunked upload")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/file/complete")
    public ResponseEntity<Void> completeChunkedUpload(@PathVariable String id,
                                                      @RequestBody ChunkCompleteRequest request) throws Exception {
        templateService.completeChunkedUpload(id, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Abort chunked upload")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/file/abort")
    public ResponseEntity<Void> abortChunkedUpload(@PathVariable String id,
                                                   @RequestParam String uploadId) throws Exception {
        templateService.abortChunkedUpload(id, uploadId);
        return ResponseEntity.ok().build();
    }
}
