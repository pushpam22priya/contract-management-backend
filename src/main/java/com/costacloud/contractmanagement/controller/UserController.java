package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.UserListResponse;
import com.costacloud.contractmanagement.model.UserProfile;
import com.costacloud.contractmanagement.repository.UserProfileRepository;
import com.costacloud.contractmanagement.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/users")
@Tag(name = "Users", description = "User listing for reviewer and approver selection")
public class UserController {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    public UserController(UserRepository userRepository,
                          UserProfileRepository userProfileRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
    }

    @Operation(summary = "List all users — used to populate reviewer and approver pickers")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<List<UserListResponse>> getAllUsers() {
        String callerEmail = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        // Fetch all profiles in one query and index by email
        Map<String, UserProfile> profileMap = userProfileRepository.findAll()
                .stream()
                .collect(Collectors.toMap(UserProfile::getEmail, p -> p));

        List<UserListResponse> users = userRepository.findAll()
                .stream()
                .filter(u -> !u.getEmail().equalsIgnoreCase(callerEmail))
                .map(u -> new UserListResponse(u, profileMap.get(u.getEmail())))
                .collect(Collectors.toList());

        return ResponseEntity.ok(users);
    }
}
