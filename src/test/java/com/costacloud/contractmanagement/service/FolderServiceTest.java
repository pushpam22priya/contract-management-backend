package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.FolderRequest;
import com.costacloud.contractmanagement.dto.FolderResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Folder;
import com.costacloud.contractmanagement.repository.FolderRepository;
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
@DisplayName("FolderService")
class FolderServiceTest {

    @Mock FolderRepository folderRepository;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks FolderService folderService;

    private static final String FOLDER_ID   = "folder-001";
    private static final String OWNER       = "owner@test.com";
    private static final String OTHER       = "other@test.com";
    private static final String FOLDER_NAME = "Legal";

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Folder storedFolder(String id, String name, String owner) {
        Folder f = new Folder();
        f.setId(id);
        f.setName(name);
        f.setCreatedBy(owner);
        f.setCreatedAt(LocalDateTime.now().minusDays(5));
        f.setUpdatedAt(LocalDateTime.now().minusDays(5));
        return f;
    }

    private FolderRequest requestFor(String name) {
        FolderRequest r = new FolderRequest();
        r.setName(name);
        return r;
    }

    private Folder captureLastSaved() {
        ArgumentCaptor<Folder> captor = ArgumentCaptor.forClass(Folder.class);
        verify(folderRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — createFolder
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createFolder")
    class CreateFolder {

        @BeforeEach
        void stubDefaults() {
            lenient().when(folderRepository.existsByNameAndCreatedBy(anyString(), anyString())).thenReturn(false);
            lenient().when(folderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("saves the folder with the correct trimmed name")
        void shouldSave_correctName() {
            folderService.createFolder(requestFor("  Legal  "), OWNER);

            assertEquals("Legal", captureLastSaved().getName());
        }

        @Test
        @DisplayName("saves the folder with createdBy set to the authenticated user's email")
        void shouldSave_createdBy_fromEmail() {
            folderService.createFolder(requestFor(FOLDER_NAME), OWNER);

            assertEquals(OWNER, captureLastSaved().getCreatedBy());
        }

        @Test
        @DisplayName("saves the folder with a non-null createdAt timestamp")
        void shouldSave_nonNull_createdAt() {
            folderService.createFolder(requestFor(FOLDER_NAME), OWNER);

            assertNotNull(captureLastSaved().getCreatedAt());
        }

        @Test
        @DisplayName("saves the folder with a non-null updatedAt timestamp")
        void shouldSave_nonNull_updatedAt() {
            folderService.createFolder(requestFor(FOLDER_NAME), OWNER);

            assertNotNull(captureLastSaved().getUpdatedAt());
        }

        @Test
        @DisplayName("calls save exactly once per createFolder invocation")
        void shouldCallSave_exactlyOnce() {
            folderService.createFolder(requestFor(FOLDER_NAME), OWNER);

            verify(folderRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("returns a FolderResponse with the correct name and createdBy")
        void shouldReturn_folderResponse_withCorrectFields() {
            FolderResponse response = folderService.createFolder(requestFor(FOLDER_NAME), OWNER);

            assertEquals(FOLDER_NAME, response.getName());
            assertEquals(OWNER,       response.getCreatedBy());
        }

        @Test
        @DisplayName("throws BadRequestException when a folder with the same name already exists for this user")
        void shouldThrow_400_whenDuplicateName() {
            when(folderRepository.existsByNameAndCreatedBy(FOLDER_NAME, OWNER)).thenReturn(true);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> folderService.createFolder(requestFor(FOLDER_NAME), OWNER));

            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("does not call save when the name already exists")
        void shouldNotCallSave_onDuplicate() {
            when(folderRepository.existsByNameAndCreatedBy(FOLDER_NAME, OWNER)).thenReturn(true);

            assertThrows(BadRequestException.class,
                    () -> folderService.createFolder(requestFor(FOLDER_NAME), OWNER));

            verify(folderRepository, never()).save(any());
        }

        @Test
        @DisplayName("checks duplicate using the trimmed name, not the raw input")
        void shouldCheck_duplicate_usingTrimmedName() {
            folderService.createFolder(requestFor("  Legal  "), OWNER);

            verify(folderRepository).existsByNameAndCreatedBy("Legal", OWNER);
        }

        @Test
        @DisplayName("two users can have folders with the same name — uniqueness is per-user")
        void shouldAllow_sameNameForDifferentUsers() {
            when(folderRepository.existsByNameAndCreatedBy(FOLDER_NAME, OTHER)).thenReturn(false);

            assertDoesNotThrow(() -> folderService.createFolder(requestFor(FOLDER_NAME), OTHER));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — listFolders
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listFolders")
    class ListFolders {

        @Test
        @DisplayName("returns a FolderResponse for each folder belonging to the user")
        void shouldReturn_allUserFolders() {
            when(folderRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(
                    storedFolder("f1", "Legal", OWNER),
                    storedFolder("f2", "Finance", OWNER)
            ));

            List<FolderResponse> result = folderService.listFolders(OWNER);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("maps id, name, createdBy, createdAt, updatedAt correctly to FolderResponse")
        void shouldMap_allFields_correctly() {
            Folder f = storedFolder("f1", "Legal", OWNER);
            when(folderRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(f));

            FolderResponse r = folderService.listFolders(OWNER).get(0);

            assertEquals("f1",    r.getId());
            assertEquals("Legal", r.getName());
            assertEquals(OWNER,   r.getCreatedBy());
            assertNotNull(r.getCreatedAt());
            assertNotNull(r.getUpdatedAt());
        }

        @Test
        @DisplayName("returns an empty list when the user has no folders")
        void shouldReturn_emptyList_whenNoFolders() {
            when(folderRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of());

            assertTrue(folderService.listFolders(OWNER).isEmpty());
        }

        @Test
        @DisplayName("queries by the caller's email — does not return other users' folders")
        void shouldQuery_withCallerEmail() {
            when(folderRepository.findByCreatedByOrderByCreatedAtDesc(OWNER)).thenReturn(List.of());

            folderService.listFolders(OWNER);

            verify(folderRepository).findByCreatedByOrderByCreatedAtDesc(OWNER);
            verify(folderRepository, never()).findByCreatedByOrderByCreatedAtDesc(OTHER);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — renameFolder
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("renameFolder")
    class RenameFolder {

        @BeforeEach
        void stubDefaults() {
            lenient().when(folderRepository.findById(FOLDER_ID))
                    .thenReturn(Optional.of(storedFolder(FOLDER_ID, FOLDER_NAME, OWNER)));
            lenient().when(folderRepository.existsByNameAndCreatedByAndIdNot(anyString(), anyString(), anyString()))
                    .thenReturn(false);
            lenient().when(folderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("saves the folder with the new trimmed name")
        void shouldSave_newName() {
            folderService.renameFolder(FOLDER_ID, requestFor("  Finance  "), OWNER);

            assertEquals("Finance", captureLastSaved().getName());
        }

        @Test
        @DisplayName("updates updatedAt on rename")
        void shouldUpdate_updatedAt() {
            Folder original = storedFolder(FOLDER_ID, FOLDER_NAME, OWNER);
            LocalDateTime originalUpdatedAt = original.getUpdatedAt();
            when(folderRepository.findById(FOLDER_ID)).thenReturn(Optional.of(original));

            folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OWNER);

            assertNotEquals(originalUpdatedAt, captureLastSaved().getUpdatedAt());
        }

        @Test
        @DisplayName("returns FolderResponse with the new name")
        void shouldReturn_response_withNewName() {
            FolderResponse response = folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OWNER);

            assertEquals("Finance", response.getName());
        }

        @Test
        @DisplayName("calls save exactly once on a successful rename")
        void shouldCallSave_exactlyOnce() {
            folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OWNER);

            verify(folderRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("throws NotFoundException when folder ID does not exist")
        void shouldThrow_404_whenFolderNotFound() {
            when(folderRepository.findById(FOLDER_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when caller does not own the folder")
        void shouldThrow_404_whenCallerIsNotOwner() {
            assertThrows(NotFoundException.class,
                    () -> folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OTHER));
        }

        @Test
        @DisplayName("not-found and wrong-owner throw the same NotFoundException to prevent ID enumeration")
        void shouldThrow_sameException_forNotFoundAndWrongOwner() {
            when(folderRepository.findById("missing")).thenReturn(Optional.empty());
            Exception notFound = assertThrows(NotFoundException.class,
                    () -> folderService.renameFolder("missing", requestFor("Finance"), OWNER));

            Exception wrongOwner = assertThrows(NotFoundException.class,
                    () -> folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OTHER));

            assertEquals(notFound.getMessage(), wrongOwner.getMessage());
        }

        @Test
        @DisplayName("throws BadRequestException when another folder of the same user has the same name")
        void shouldThrow_400_whenDuplicateNameForUser() {
            when(folderRepository.existsByNameAndCreatedByAndIdNot("Finance", OWNER, FOLDER_ID))
                    .thenReturn(true);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OWNER));

            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("does not throw when renaming to the same name as the current folder")
        void shouldNotThrow_whenRenamingToCurrentName() {
            when(folderRepository.existsByNameAndCreatedByAndIdNot(FOLDER_NAME, OWNER, FOLDER_ID))
                    .thenReturn(false);

            assertDoesNotThrow(() -> folderService.renameFolder(FOLDER_ID, requestFor(FOLDER_NAME), OWNER));
        }

        @Test
        @DisplayName("does not call save when ownership check fails")
        void shouldNotCallSave_onOwnershipFailure() {
            assertThrows(NotFoundException.class,
                    () -> folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OTHER));

            verify(folderRepository, never()).save(any());
        }

        @Test
        @DisplayName("does not call save when duplicate name check fails")
        void shouldNotCallSave_onDuplicateNameFailure() {
            when(folderRepository.existsByNameAndCreatedByAndIdNot("Finance", OWNER, FOLDER_ID))
                    .thenReturn(true);

            assertThrows(BadRequestException.class,
                    () -> folderService.renameFolder(FOLDER_ID, requestFor("Finance"), OWNER));

            verify(folderRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 4 — deleteFolder
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteFolder")
    class DeleteFolder {

        @BeforeEach
        void stubDefaults() {
            lenient().when(folderRepository.findById(FOLDER_ID))
                    .thenReturn(Optional.of(storedFolder(FOLDER_ID, FOLDER_NAME, OWNER)));
            lenient().when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(0L);
        }

        @Test
        @DisplayName("calls deleteById with the correct folder ID when all guards pass")
        void shouldCall_deleteById_withCorrectId() {
            folderService.deleteFolder(FOLDER_ID, OWNER);

            verify(folderRepository, times(1)).deleteById(FOLDER_ID);
        }

        @Test
        @DisplayName("completes without throwing when the folder exists and has no contracts")
        void shouldNotThrow_whenFolderExistsAndHasNoContracts() {
            assertDoesNotThrow(() -> folderService.deleteFolder(FOLDER_ID, OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when folder ID does not exist")
        void shouldThrow_404_whenFolderNotFound() {
            when(folderRepository.findById(FOLDER_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when caller does not own the folder")
        void shouldThrow_404_whenCallerIsNotOwner() {
            assertThrows(NotFoundException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OTHER));
        }

        @Test
        @DisplayName("not-found and wrong-owner throw the same NotFoundException to prevent ID enumeration")
        void shouldThrow_sameException_forNotFoundAndWrongOwner() {
            when(folderRepository.findById("missing")).thenReturn(Optional.empty());
            Exception notFound = assertThrows(NotFoundException.class,
                    () -> folderService.deleteFolder("missing", OWNER));

            Exception wrongOwner = assertThrows(NotFoundException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OTHER));

            assertEquals(notFound.getMessage(), wrongOwner.getMessage());
        }

        @Test
        @DisplayName("throws BadRequestException when the folder contains one or more contracts")
        void shouldThrow_400_whenFolderHasContracts() {
            when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(3L);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OWNER));

            assertTrue(ex.getMessage().contains("3"));
        }

        @Test
        @DisplayName("error message includes the contract count when deletion is blocked")
        void shouldInclude_contractCount_inErrorMessage() {
            when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(5L);

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OWNER));

            assertTrue(ex.getMessage().contains("5"));
        }

        @Test
        @DisplayName("does not call deleteById when folder ID does not exist")
        void shouldNotCallDeleteById_whenNotFound() {
            when(folderRepository.findById(FOLDER_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OWNER));

            verify(folderRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("does not call deleteById when the caller is not the owner")
        void shouldNotCallDeleteById_whenWrongOwner() {
            assertThrows(NotFoundException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OTHER));

            verify(folderRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("does not call deleteById when the folder has contracts")
        void shouldNotCallDeleteById_whenFolderHasContracts() {
            when(mongoTemplate.count(any(Query.class), eq("contracts"))).thenReturn(2L);

            assertThrows(BadRequestException.class,
                    () -> folderService.deleteFolder(FOLDER_ID, OWNER));

            verify(folderRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("queries contracts collection using the folder's ID")
        void shouldQuery_contractsByFolderId() {
            folderService.deleteFolder(FOLDER_ID, OWNER);

            verify(mongoTemplate).count(any(Query.class), eq("contracts"));
        }
    }
}
