package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.TeamRequest;
import com.costacloud.contractmanagement.dto.TeamResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Team;
import com.costacloud.contractmanagement.repository.TeamRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final MongoTemplate mongoTemplate;

    public TeamService(TeamRepository teamRepository, MongoTemplate mongoTemplate) {
        this.teamRepository = teamRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public TeamResponse createTeam(TeamRequest request, String email) {
        String name = request.getName().trim();
        if (teamRepository.existsByNameAndCreatedBy(name, email)) {
            throw new BadRequestException("A team with this name already exists");
        }
        Team team = new Team();
        team.setName(name);

        team.setCreatedBy(email);
        team.setCreatedAt(LocalDateTime.now());
        team.setUpdatedAt(LocalDateTime.now());
        teamRepository.save(team);
        return new TeamResponse(team);
    }

    public List<TeamResponse> listTeams(String email) {
        return teamRepository.findByCreatedByOrderByCreatedAtDesc(email)
                .stream()
                .map(TeamResponse::new)
                .collect(Collectors.toList());
    }

    public TeamResponse renameTeam(String id, TeamRequest request, String email) {
        Team team = findByIdAndOwner(id, email);
        String name = request.getName().trim();
        if (teamRepository.existsByNameAndCreatedByAndIdNot(name, email, id)) {
            throw new BadRequestException("A team with this name already exists");
        }
        team.setName(name);
        team.setUpdatedAt(LocalDateTime.now());
        teamRepository.save(team);
        return new TeamResponse(team);
    }

    public void deleteTeam(String id, String email) {
        findByIdAndOwner(id, email);
        long contractCount = mongoTemplate.count(
                Query.query(Criteria.where("teamId").is(id)),
                "contracts"
        );
        if (contractCount > 0) {
            throw new BadRequestException(
                    "Cannot delete: this team contains " + contractCount + " contract(s)"
            );
        }
        teamRepository.deleteById(id);
    }

    // ─── Private Helpers ─────────────────────────────────────────

    private Team findByIdAndOwner(String id, String email) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Team not found"));
        // Return same error as not found to avoid leaking other users' team existence
        if (!team.getCreatedBy().equals(email)) {
            throw new NotFoundException("Team not found");
        }
        return team;
    }
}
