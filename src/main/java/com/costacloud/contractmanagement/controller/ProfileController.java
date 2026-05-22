package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.ProfileRequest;
import com.costacloud.contractmanagement.dto.ProfileResponse;
import com.costacloud.contractmanagement.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/profile")
@Tag(name = "Profile", description = "Manage the authenticated user's profile.")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "Get profile", description = "Returns the current user's profile. Empty fields are null if not yet filled.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(profileService.getProfile(email));
    }

    @Operation(summary = "Update profile", description = "Creates or updates the current user's profile. All fields are optional — only provided fields are updated.")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping
    public ResponseEntity<ProfileResponse> updateProfile(@Valid @RequestBody ProfileRequest request) {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(profileService.updateProfile(email, request));
    }
}
