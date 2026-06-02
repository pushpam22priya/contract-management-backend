package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.TeamRequest;
import com.costacloud.contractmanagement.dto.TeamResponse;
import com.costacloud.contractmanagement.service.TeamService;
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
@RequestMapping("/teams")
@Tag(name = "Teams", description = "Manage contract teams. All operations are user-scoped — each user manages their own teams independently.")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @Operation(summary = "Create a new team")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public ResponseEntity<TeamResponse> createTeam(@Valid @RequestBody TeamRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.createTeam(request, getEmail()));
    }

    @Operation(summary = "List all teams owned by the current user")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<List<TeamResponse>> listTeams() {
        return ResponseEntity.ok(teamService.listTeams(getEmail()));
    }

    @Operation(summary = "Rename a team")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/{id}")
    public ResponseEntity<TeamResponse> renameTeam(@PathVariable String id,
                                                   @Valid @RequestBody TeamRequest request) {
        return ResponseEntity.ok(teamService.renameTeam(id, request, getEmail()));
    }

    @Operation(summary = "Delete a team — blocked if the team contains any contracts")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTeam(@PathVariable String id) {
        teamService.deleteTeam(id, getEmail());
        return ResponseEntity.noContent().build();
    }

    private String getEmail() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
