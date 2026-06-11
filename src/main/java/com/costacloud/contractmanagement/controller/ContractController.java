package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.service.ContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.InputStreamResource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/contracts")
@Tag(name = "Contracts", description = "Manage contracts. All operations are user-scoped.")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    // ─── Metadata Endpoints ──────────────────────────────────────

    @Operation(summary = "Create contract metadata — returns { id } to use for file upload")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<ContractResponse> createContract(
            @Valid @RequestBody ContractRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractService.createContract(request, getEmail()));
    }

    @Operation(summary = "List contracts for current user — filter by teamId and/or status")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<List<ContractListResponse>> listContracts(
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(contractService.listContracts(getEmail(), teamId, status));
    }

    @Operation(summary = "Get full contract including xfdfData and formFields")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<ContractResponse> getContract(@PathVariable String id) {
        return ResponseEntity.ok(contractService.getContract(id, getEmail()));
    }

    @Operation(summary = "Partial update — update any metadata field or persist xfdfData after PDF upload")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{id}")
    public ResponseEntity<ContractResponse> updateContract(
            @PathVariable String id,
            @RequestBody ContractRequest request) {
        return ResponseEntity.ok(contractService.updateContract(id, request, getEmail()));
    }

    // ─── File Endpoints ───────────────────────────────────────────

    @Operation(summary = "Upload filled PDF — single shot (files under 30MB only)")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/{id}/file")
    public ResponseEntity<Void> uploadFile(@PathVariable String id,
                                           HttpServletRequest request) throws Exception {
        long fileSize = request.getContentLengthLong();
        if (fileSize == -1) {
            throw new com.costacloud.contractmanagement.exception.BadRequestException(
                    "Content-Length header is required");
        }
        if (fileSize >= 30 * 1024 * 1024) {
            throw new com.costacloud.contractmanagement.exception.BadRequestException(
                    "File is 30MB or larger — use the chunked upload endpoints instead: POST /{id}/file/initiate");
        }
        contractService.uploadFile(id, request.getInputStream(), fileSize, getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get presigned MinIO URL for viewing contract PDF in Apryse WebViewer")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/file/view-url")
    public ResponseEntity<Map<String, String>> getViewUrl(@PathVariable String id) throws Exception {
        String url = contractService.generatePresignedViewUrl(id, getEmail());
        return ResponseEntity.ok(Map.of("url", url));
    }

    // ─── Chunked Upload Endpoints ─────────────────────────────────

    @Operation(summary = "Initiate chunked upload session")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/file/initiate")
    public ResponseEntity<ChunkUploadInitResponse> initiateChunkedUpload(
            @PathVariable String id) throws Exception {
        return ResponseEntity.ok(contractService.initiateChunkedUpload(id, getEmail()));
    }

    @Operation(summary = "Get presigned URL for uploading a single chunk directly to MinIO")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/file/presign")
    public ResponseEntity<Map<String, Object>> getPresignedPartUrl(
            @PathVariable String id,
            @RequestParam String uploadId,
            @RequestParam int partNumber) throws Exception {
        String url = contractService.generatePresignedPartUrl(id, uploadId, partNumber, getEmail());
        return ResponseEntity.ok(Map.of("url", url, "partNumber", partNumber));
    }

    @Operation(summary = "Complete chunked upload — assembles all chunks into final PDF")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/file/complete")
    public ResponseEntity<Void> completeChunkedUpload(
            @PathVariable String id,
            @RequestBody ChunkCompleteRequest request) throws Exception {
        contractService.completeChunkedUpload(id, request, getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Abort chunked upload — cleans up all partial chunks from MinIO")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/file/abort")
    public ResponseEntity<Void> abortChunkedUpload(
            @PathVariable String id,
            @RequestParam String uploadId) throws Exception {
        contractService.abortChunkedUpload(id, uploadId, getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get contracts assigned to the current user for review or approval")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/inbox")
    public ResponseEntity<List<ContractResponse>> getInboxContracts() {
        return ResponseEntity.ok(contractService.getInboxContracts(getEmail()));
    }


    private String getEmail() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
