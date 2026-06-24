package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.FolderRequest;
import com.costacloud.contractmanagement.dto.FolderResponse;
import com.costacloud.contractmanagement.service.FolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/folders")
@Tag(name = "Folders", description = "Manage contract folders. All operations are user-scoped — each user manages their own folders independently.")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @Operation(summary = "Create a new folder")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<FolderResponse> createFolder(@Valid @RequestBody FolderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(folderService.createFolder(request, getEmail()));
    }

    @Operation(summary = "List all folders owned by the current user")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<List<FolderResponse>> listFolders() {
        return ResponseEntity.ok(folderService.listFolders(getEmail()));
    }

    @Operation(summary = "Rename a folder")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/{id}")
    public ResponseEntity<FolderResponse> renameFolder(@PathVariable String id,
                                                       @Valid @RequestBody FolderRequest request) {
        return ResponseEntity.ok(folderService.renameFolder(id, request, getEmail()));
    }

    @Operation(summary = "Delete a folder — blocked if the folder contains any contracts")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFolder(@PathVariable String id) {
        folderService.deleteFolder(id, getEmail());
        return ResponseEntity.noContent().build();
    }

    private String getEmail() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
