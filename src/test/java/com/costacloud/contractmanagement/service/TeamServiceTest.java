package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.TeamRequest;
import com.costacloud.contractmanagement.dto.TeamResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Team;
import com.costacloud.contractmanagement.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamService")
class TeamServiceTest {

    @Mock TeamRepository teamRepository;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks TeamService teamService;

    private static final String TEAM_ID    = "team-001";
    private static final String OWNER      = "owner@test.com";
    private static final String OTHER      = "other@test.com";
    private static final String TEAM_NAME  = "Legal";

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Team storedTeam(String id, String name, String owner) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setCreatedBy(owner);
        t.setCreatedAt(LocalDateTime.now().minusDays(5));
        t.setUpdatedAt(LocalDateTime.now().minusDays(5));
        return t;
    }

    private TeamRequest requestFor(String name) {
        TeamRequest r = new TeamRequest();
        r.setName(name);
        return r;
    }

    private Team captureLastSaved() {
        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — createTeam
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createTeam")
    class CreateTeam {

        @BeforeEach
        void stubDefaults() {
            lenient().when(teamRepository.existsByNameAndCreatedBy(anyString(), anyString())).thenReturn(false);
            lenient().when(teamRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("saves the team with the correct trimmed name")
        void shouldSave_correctName() {
            teamService.createTeam(requestFor("  Legal  "), OWNER);

            assertEquals("Legal", captureLastSaved().getName());
        }

        @Test
        @DisplayName("saves the team with createdBy set to the authenticated user's email")
        void shouldSave_createdBy_fromEmail() {
            teamService.createTeam(requestFor(TEAM_NAME), OWNER);

            assertEquals(OWNER, captureLastSaved().getCreatedBy());
        }

        @Test
        @DisplayName("saves the team with a non-null createdAt timestamp")
        void shouldSave_nonNull_createdAt() {
            teamService.createTeam(requestFor(TEAM_NAME), OWNER);

            assertNotNull(captureLastSaved().getCreatedAt());
        }

        @Test
        @DisplayName("saves the team with a non-null updatedAt timestamp")
        void shouldSave_nonNull_updatedAt() {
            teamService.createTeam(requestFor(TEAM_NAME), OWNER);

            assertNotNull(captureLastSaved().getUpdatedAt());
        }

        @Test
        @DisplayName("calls save exactly once per createTeam invocation")
        void shouldCallSave_exactlyOnce() {
            teamService.createTeam(requestFor(TEAM_NAME), OWNER);

            verify(teamRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("returns a TeamResponse with the correct name and createdBy")
        void shouldReturn_teamResponse_withCorrectFields() {
            TeamResponse response = teamService.createTeam(requestFor(TEAM_NAME), OWNER);

            assertEquals(TEAM_NAME, response.getName());
            assertEquals(OWNER,     response.getCreatedBy());
        }

        @Test
        @DisplayName("throws BadRequestException when a team with the same name already exists for this user")
        void shouldThrow_400_whenDuplicateName() {
            when(teamRepository.existsByNameAndCreatedBy(TEAM_NAME, OWNER)).thenReturn(true);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> teamService.createTeam(requestFor(TEAM_NAME), OWNER));

            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("does not call save when the name already exists")
        void shouldNotCallSave_onDuplicate() {
            when(teamRepository.existsByNameAndCreatedBy(TEAM_NAME, OWNER)).thenReturn(true);

            assertThrows(BadRequestException.class,
                    () -> teamService.createTeam(requestFor(TEAM_NAME), OWNER));

            verify(teamRepository, never()).save(any());
        }

        @Test
        @DisplayName("checks duplicate using the trimmed name, not the raw input")
        void shouldCheck_duplicate_usingTrimmedName() {
            teamService.createTeam(requestFor("  Legal  "), OWNER);

            verify(teamRepository).existsByNameAndCreatedBy("Legal", OWNER);
        }

        @Test
        @DisplayName("two users can have teams with the same name — uniqueness is per-user")
        void shouldAllow_sameNameForDifferentUsers() {
            // OWNER's team "Legal" exists, but OTHER is creating theirs
            when(teamRepository.existsByNameAndCreatedBy(TEAM_NAME, OTHER)).thenReturn(false);

            assertDoesNotThrow(() -> teamService.createTeam(requestFor(TEAM_NAME), OTHER));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — listTeams
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listTeams")
    class ListTeams {

        @Test
        @DisplayName("returns a TeamResponse for each team belonging to the user")
        void shouldReturn_allUserTeams() {
            when(teamRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(
                    storedTeam("t1", "Legal", OWNER),
                    storedTeam("t2", "Finance", OWNER)
            ));

            List<TeamResponse> result = teamService.listTeams(OWNER);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("maps id, name, createdBy, createdAt, updatedAt correctly to TeamResponse")
        void shouldMap_allFields_correctly() {
            Team t = storedTeam("t1", "Legal", OWNER);
            when(teamRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(t));

            TeamResponse r = teamService.listTeams(OWNER).get(0);

            assertEquals("t1",    r.getId());
            assertEquals("Legal", r.getName());
            assertEquals(OWNER,   r.getCreatedBy());
            assertNotNull(r.getCreatedAt());
            assertNotNull(r.getUpdatedAt());
        }

        @Test
        @DisplayName("returns an empty list when the user has no teams")
        void shouldReturn_emptyList_whenNoTeams() {
            when(teamRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of());

            assertTrue(teamService.listTeams(OWNER).isEmpty());
        }

        @Test
        @DisplayName("queries by the caller's email — does not return other users' teams")
        void shouldQuery_withCallerEmail() {
            when(teamRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of());

            teamService.listTeams(OWNER);

            verify(teamRepository).findByCreatedByOrderByCreatedAtDesc(OWNER);
            verify(teamRepository, never()).findByCreatedByOrderByCreatedAtDesc(OTHER);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — renameTeam
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("renameTeam")
    class RenameTeam {

        @BeforeEach
        void stubDefaults() {
            lenient().when(teamRepository.findById(TEAM_ID))
                    .thenReturn(Optional.of(storedTeam(TEAM_ID, TEAM_NAME, OWNER)));
            lenient().when(teamRepository.existsByNameAndCreatedByAndIdNot(anyString(), anyString(), anyString()))
                    .thenReturn(false);
            lenient().when(teamRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("saves the team with the new trimmed name")
        void shouldSave_newName() {
            teamService.renameTeam(TEAM_ID, requestFor("  Finance  "), OWNER);

            assertEquals("Finance", captureLastSaved().getName());
        }

        @Test
        @DisplayName("updates updatedAt on rename")
        void shouldUpdate_updatedAt() {
            Team original = storedTeam(TEAM_ID, TEAM_NAME, OWNER);
            LocalDateTime originalUpdatedAt = original.getUpdatedAt();
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(original));

            teamService.renameTeam(TEAM_ID, requestFor("Finance"), OWNER);

            assertNotEquals(originalUpdatedAt, captureLastSaved().getUpdatedAt());
        }

        @Test
        @DisplayName("returns TeamResponse with the new name")
        void shouldReturn_response_withNewName() {
            TeamResponse response = teamService.renameTeam(TEAM_ID, requestFor("Finance"), OWNER);

            assertEquals("Finance", response.getName());
        }

        @Test
        @DisplayName("calls save exactly once on a successful rename")
        void shouldCallSave_exactlyOnce() {
            teamService.renameTeam(TEAM_ID, requestFor("Finance"), OWNER);

            verify(teamRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("throws NotFoundException when team ID does not exist")
        void shouldThrow_404_whenTeamNotFound() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> teamService.renameTeam(TEAM_ID, requestFor("Finance"), OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when caller does not own the team")
        void shouldThrow_404_whenCallerIsNotOwner() {
            // Team exists but is owned by OWNER; OTHER is calling
            assertThrows(NotFoundException.class,
                    () -> teamService.renameTeam(TEAM_ID, requestFor("Finance"), OTHER));
        }

        @Test
        @DisplayName("not-found and wrong-owner throw the same NotFoundException to prevent ID enumeration")
        void shouldThrow_sameException_forNotFoundAndWrongOwner() {
            when(teamRepository.findById("missing")).thenReturn(Optional.empty());
            Exception notFound = assertThrows(NotFoundException.class,
                    () -> teamService.renameTeam("missing", requestFor("Finance"), OWNER));

            Exception wrongOwner = assertThrows(NotFoundException.class,
                    () -> teamService.renameTeam(TEAM_ID, requestFor("Finance"), OTHER));

            assertEquals(notFound.getMessage(), wrongOwner.getMessage());
        }

        @Test
        @DisplayName("throws BadRequestException when another team of the same user has the same name")
        void shouldThrow_400_whenDuplicateNameForUser() {
            when(teamRepository.existsByNameAndCreatedByAndIdNot("Finance", OWNER, TEAM_ID))
                    .thenReturn(true);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> teamService.renameTeam(TEAM_ID, requestFor("Finance"), OWNER));

            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("does not throw when renaming to the same name as the current team")
        void shouldNotThrow_whenRenamingToCurrentName() {
            // existsByNameAndCreatedByAndIdNot excludes the current team — returns false
            when(teamRepository.existsByNameAndCreatedByAndIdNot(TEAM_NAME, OWNER, TEAM_ID))
                    .thenReturn(false);

            assertDoesNotThrow(() -> teamService.renameTeam(TEAM_ID, requestFor(TEAM_NAME), OWNER));
        }

        @Test
        @DisplayName("does not call save when ownership check fails")
        void shouldNotCallSave_onOwnershipFailure() {
            assertThrows(NotFoundException.class,
                    () -> teamService.renameTeam(TEAM_ID, requestFor("Finance"), OTHER));

            verify(teamRepository, never()).save(any());
        }

        @Test
        @DisplayName("does not call save when duplicate name check fails")
        void shouldNotCallSave_onDuplicateNameFailure() {
            when(teamRepository.existsByNameAndCreatedByAndIdNot("Finance", OWNER, TEAM_ID))
                    .thenReturn(true);

            assertThrows(BadRequestException.class,
                    () -> teamService.renameTeam(TEAM_ID, requestFor("Finance"), OWNER));

            verify(teamRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 4 — deleteTeam
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteTeam")
    class DeleteTeam {

        @BeforeEach
        void stubDefaults() {
            lenient().when(teamRepository.findById(TEAM_ID))
                    .thenReturn(Optional.of(storedTeam(TEAM_ID, TEAM_NAME, OWNER)));
            lenient().when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(0L);
        }

        @Test
        @DisplayName("calls deleteById with the correct team ID when all guards pass")
        void shouldCall_deleteById_withCorrectId() {
            teamService.deleteTeam(TEAM_ID, OWNER);

            verify(teamRepository, times(1)).deleteById(TEAM_ID);
        }

        @Test
        @DisplayName("completes without throwing when the team exists and has no contracts")
        void shouldNotThrow_whenTeamExistsAndHasNoContracts() {
            assertDoesNotThrow(() -> teamService.deleteTeam(TEAM_ID, OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when team ID does not exist")
        void shouldThrow_404_whenTeamNotFound() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when caller does not own the team")
        void shouldThrow_404_whenCallerIsNotOwner() {
            assertThrows(NotFoundException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OTHER));
        }

        @Test
        @DisplayName("not-found and wrong-owner throw the same NotFoundException to prevent ID enumeration")
        void shouldThrow_sameException_forNotFoundAndWrongOwner() {
            when(teamRepository.findById("missing")).thenReturn(Optional.empty());
            Exception notFound = assertThrows(NotFoundException.class,
                    () -> teamService.deleteTeam("missing", OWNER));

            Exception wrongOwner = assertThrows(NotFoundException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OTHER));

            assertEquals(notFound.getMessage(), wrongOwner.getMessage());
        }

        @Test
        @DisplayName("throws BadRequestException when the team contains one or more contracts")
        void shouldThrow_400_whenTeamHasContracts() {
            when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(3L);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OWNER));

            assertTrue(ex.getMessage().contains("3"));
        }

        @Test
        @DisplayName("error message includes the contract count when deletion is blocked")
        void shouldInclude_contractCount_inErrorMessage() {
            when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(5L);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OWNER));

            assertTrue(ex.getMessage().contains("5"));
        }

        @Test
        @DisplayName("does not call deleteById when team ID does not exist")
        void shouldNotCallDeleteById_whenNotFound() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OWNER));

            verify(teamRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("does not call deleteById when the caller is not the owner")
        void shouldNotCallDeleteById_whenWrongOwner() {
            assertThrows(NotFoundException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OTHER));

            verify(teamRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("does not call deleteById when the team has contracts")
        void shouldNotCallDeleteById_whenTeamHasContracts() {
            when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(2L);

            assertThrows(BadRequestException.class,
                    () -> teamService.deleteTeam(TEAM_ID, OWNER));

            verify(teamRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("queries contracts collection using the team's ID")
        void shouldQuery_contractsByTeamId() {
            teamService.deleteTeam(TEAM_ID, OWNER);

            verify(mongoTemplate).count(any(Query.class), eq("contracts"));
        }
    }
}
