package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.ContractListResponse;
import com.costacloud.contractmanagement.dto.ContractRequest;
import com.costacloud.contractmanagement.dto.ContractResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import com.costacloud.contractmanagement.repository.ContractRepository;
import io.minio.MinioClient;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractService — CRUD operations")
class ContractServiceCrudTest {

    @Mock ContractRepository  contractRepository;
    @Mock MinioClient         minioClient;
    @Mock CustomMinioClient   customMinioClient;
    @Mock MongoTemplate       mongoTemplate;

    @InjectMocks ContractService contractService;

    private static final String CONTRACT_ID  = "contract-crud-01";
    private static final String OWNER_EMAIL  = "owner@test.com";
    private static final String OTHER_EMAIL  = "other@test.com";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contractService, "bucketName", "test-bucket");
        lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    private ContractRequest minimalRequest() {
        ContractRequest r = new ContractRequest();
        r.setTitle("Service Agreement");
        r.setClient("Acme Corp");
        r.setTemplateId("template-001");
        r.setTemplateName("NDA Template");
        return r;
    }

    private Contract draftContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setTitle("Service Agreement");
        c.setClient("Acme Corp");
        c.setCreatedBy(OWNER_EMAIL);
        c.setStatus(ContractStatus.DRAFT);
        c.setTemplateId("template-001");
        c.setStartDate(LocalDate.now().minusDays(5));
        c.setEndDate(LocalDate.now().plusYears(1));
        return c;
    }

    /** Captures the Contract that was last passed to contractRepository.save() */
    private Contract captureLastSaved() {
        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createContract
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createContract")
    class CreateContract {

        @BeforeEach
        void stubNoDuplicate() {
            lenient().when(contractRepository.existsByTitleAndCreatedBy(anyString(), anyString()))
                    .thenReturn(false);
        }

        @Test
        @DisplayName("saves contract with DRAFT status")
        void shouldSave_statusDraft() {
            contractService.createContract(minimalRequest(), OWNER_EMAIL);

            assertEquals(ContractStatus.DRAFT, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("saves contract with fileUploaded=false")
        void shouldSave_fileUploadedFalse() {
            contractService.createContract(minimalRequest(), OWNER_EMAIL);

            assertFalse(captureLastSaved().isFileUploaded());
        }

        @Test
        @DisplayName("saves createdBy from the authenticated email")
        void shouldSave_createdBy_fromEmail() {
            contractService.createContract(minimalRequest(), OWNER_EMAIL);

            assertEquals(OWNER_EMAIL, captureLastSaved().getCreatedBy());
        }

        @Test
        @DisplayName("trims leading/trailing whitespace from the title before saving")
        void shouldTrim_title() {
            ContractRequest r = minimalRequest();
            r.setTitle("  My Contract  ");

            contractService.createContract(r, OWNER_EMAIL);

            assertEquals("My Contract", captureLastSaved().getTitle());
        }

        @Test
        @DisplayName("trims leading/trailing whitespace from the client before saving")
        void shouldTrim_client() {
            ContractRequest r = minimalRequest();
            r.setClient("  Acme Corp  ");

            contractService.createContract(r, OWNER_EMAIL);

            assertEquals("Acme Corp", captureLastSaved().getClient());
        }

        @Test
        @DisplayName("defaults startDate to today when request omits it")
        void shouldDefault_startDate_toToday() {
            ContractRequest r = minimalRequest();
            r.setStartDate(null);

            contractService.createContract(r, OWNER_EMAIL);

            assertEquals(LocalDate.now(), captureLastSaved().getStartDate());
        }

        @Test
        @DisplayName("defaults endDate to startDate + 1 year when request omits it")
        void shouldDefault_endDate_toStartDatePlusOneYear() {
            ContractRequest r = minimalRequest();
            r.setStartDate(LocalDate.of(2026, 1, 1));
            r.setEndDate(null);

            contractService.createContract(r, OWNER_EMAIL);

            assertEquals(LocalDate.of(2027, 1, 1), captureLastSaved().getEndDate());
        }

        @Test
        @DisplayName("defaults endDate to today + 1 year when both startDate and endDate are null")
        void shouldDefault_endDate_toTodayPlusOneYear_whenBothNull() {
            ContractRequest r = minimalRequest();
            r.setStartDate(null);
            r.setEndDate(null);

            contractService.createContract(r, OWNER_EMAIL);

            assertEquals(LocalDate.now().plusYears(1), captureLastSaved().getEndDate());
        }

        @Test
        @DisplayName("uses provided startDate and endDate when both are supplied")
        void shouldUse_providedDates() {
            LocalDate start = LocalDate.of(2026, 3, 1);
            LocalDate end   = LocalDate.of(2027, 3, 1);
            ContractRequest r = minimalRequest();
            r.setStartDate(start);
            r.setEndDate(end);

            contractService.createContract(r, OWNER_EMAIL);

            Contract saved = captureLastSaved();
            assertEquals(start, saved.getStartDate());
            assertEquals(end, saved.getEndDate());
        }

        @Test
        @DisplayName("sets description to 'Contract based on <templateName>' when description is null")
        void shouldDefault_description_fromTemplateName() {
            ContractRequest r = minimalRequest();
            r.setDescription(null);
            r.setTemplateName("NDA Template");

            contractService.createContract(r, OWNER_EMAIL);

            assertEquals("Contract based on NDA Template", captureLastSaved().getDescription());
        }

        @Test
        @DisplayName("trims and uses provided description when not null")
        void shouldUse_providedDescription() {
            ContractRequest r = minimalRequest();
            r.setDescription("  A detailed service contract.  ");

            contractService.createContract(r, OWNER_EMAIL);

            assertEquals("A detailed service contract.", captureLastSaved().getDescription());
        }

        @Test
        @DisplayName("sets non-null createdAt timestamp")
        void shouldSet_nonNull_createdAt() {
            contractService.createContract(minimalRequest(), OWNER_EMAIL);

            assertNotNull(captureLastSaved().getCreatedAt());
        }

        @Test
        @DisplayName("sets non-null updatedAt timestamp")
        void shouldSet_nonNull_updatedAt() {
            contractService.createContract(minimalRequest(), OWNER_EMAIL);

            assertNotNull(captureLastSaved().getUpdatedAt());
        }

        @Test
        @DisplayName("calls contractRepository.save exactly once")
        void shouldCallSave_exactlyOnce() {
            contractService.createContract(minimalRequest(), OWNER_EMAIL);

            verify(contractRepository, times(1)).save(any(Contract.class));
        }

        @Test
        @DisplayName("returns a ContractResponse whose title matches the saved contract")
        void shouldReturn_contractResponse_withCorrectTitle() {
            ContractResponse response = contractService.createContract(minimalRequest(), OWNER_EMAIL);

            assertEquals("Service Agreement", response.getTitle());
        }

        @Test
        @DisplayName("throws BadRequestException when a contract with the same title already exists for this owner")
        void shouldThrow_400_whenDuplicateTitle() {
            when(contractRepository.existsByTitleAndCreatedBy("Service Agreement", OWNER_EMAIL))
                    .thenReturn(true);

            assertThrows(BadRequestException.class,
                    () -> contractService.createContract(minimalRequest(), OWNER_EMAIL));
        }

        @Test
        @DisplayName("does NOT call save when duplicate title check fails")
        void shouldNotSave_whenDuplicateTitle() {
            when(contractRepository.existsByTitleAndCreatedBy("Service Agreement", OWNER_EMAIL))
                    .thenReturn(true);

            assertThrows(BadRequestException.class,
                    () -> contractService.createContract(minimalRequest(), OWNER_EMAIL));

            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws BadRequestException when endDate is before startDate")
        void shouldThrow_400_whenEndDateBeforeStartDate() {
            ContractRequest r = minimalRequest();
            r.setStartDate(LocalDate.of(2026, 6, 1));
            r.setEndDate(LocalDate.of(2026, 5, 1));   // one month before start

            assertThrows(BadRequestException.class,
                    () -> contractService.createContract(r, OWNER_EMAIL));
        }

        @Test
        @DisplayName("does NOT throw when endDate equals startDate")
        void shouldNotThrow_whenEndDateEqualsStartDate() {
            ContractRequest r = minimalRequest();
            LocalDate same = LocalDate.of(2026, 6, 1);
            r.setStartDate(same);
            r.setEndDate(same);

            assertDoesNotThrow(() -> contractService.createContract(r, OWNER_EMAIL));
        }

        @Test
        @DisplayName("allows different owners to have contracts with the same title")
        void shouldAllow_sameTitle_forDifferentOwners() {
            // existsByTitleAndCreatedBy checks OWNER_EMAIL — returns false → no conflict
            when(contractRepository.existsByTitleAndCreatedBy("Service Agreement", OTHER_EMAIL))
                    .thenReturn(false);

            assertDoesNotThrow(() -> contractService.createContract(minimalRequest(), OTHER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listContracts
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listContracts")
    class ListContracts {

        private Contract contractStub() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setTitle("Service Agreement");
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.DRAFT);
            return c;
        }

        @Test
        @DisplayName("calls findByCreatedByOrderByCreatedAtDesc when no filters are provided")
        void shouldCallNoFilter_repository_whenBothNull() {
            when(contractRepository.findByCreatedByOrderByCreatedAtDesc(OWNER_EMAIL))
                    .thenReturn(List.of(contractStub()));

            contractService.listContracts(OWNER_EMAIL, null, null);

            verify(contractRepository).findByCreatedByOrderByCreatedAtDesc(OWNER_EMAIL);
        }

        @Test
        @DisplayName("calls findByCreatedByAndTeamIdOrderByCreatedAtDesc when only teamId is provided")
        void shouldCallTeamId_repository_whenStatusNull() {
            when(contractRepository.findByCreatedByAndTeamIdOrderByCreatedAtDesc(OWNER_EMAIL, "team-1"))
                    .thenReturn(List.of(contractStub()));

            contractService.listContracts(OWNER_EMAIL, "team-1", null);

            verify(contractRepository).findByCreatedByAndTeamIdOrderByCreatedAtDesc(OWNER_EMAIL, "team-1");
        }

        @Test
        @DisplayName("calls findByCreatedByAndStatusOrderByCreatedAtDesc when only status is provided")
        void shouldCallStatus_repository_whenTeamIdNull() {
            when(contractRepository.findByCreatedByAndStatusOrderByCreatedAtDesc(
                    OWNER_EMAIL, ContractStatus.DRAFT)).thenReturn(List.of(contractStub()));

            contractService.listContracts(OWNER_EMAIL, null, "DRAFT");

            verify(contractRepository).findByCreatedByAndStatusOrderByCreatedAtDesc(
                    OWNER_EMAIL, ContractStatus.DRAFT);
        }

        @Test
        @DisplayName("calls findByCreatedByAndTeamIdAndStatusOrderByCreatedAtDesc when both filters are provided")
        void shouldCallBothFilters_repository_whenBothProvided() {
            when(contractRepository.findByCreatedByAndTeamIdAndStatusOrderByCreatedAtDesc(
                    OWNER_EMAIL, "team-1", ContractStatus.DRAFT)).thenReturn(List.of(contractStub()));

            contractService.listContracts(OWNER_EMAIL, "team-1", "DRAFT");

            verify(contractRepository).findByCreatedByAndTeamIdAndStatusOrderByCreatedAtDesc(
                    OWNER_EMAIL, "team-1", ContractStatus.DRAFT);
        }

        @Test
        @DisplayName("returns a list of ContractListResponse — one per matching contract")
        void shouldReturn_listOfContractListResponse() {
            when(contractRepository.findByCreatedByOrderByCreatedAtDesc(OWNER_EMAIL))
                    .thenReturn(List.of(contractStub(), contractStub()));

            List<ContractListResponse> result =
                    contractService.listContracts(OWNER_EMAIL, null, null);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("returns an empty list when the owner has no contracts")
        void shouldReturn_emptyList_whenNoContracts() {
            when(contractRepository.findByCreatedByOrderByCreatedAtDesc(OWNER_EMAIL))
                    .thenReturn(List.of());

            List<ContractListResponse> result =
                    contractService.listContracts(OWNER_EMAIL, null, null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("status filter is case-insensitive — 'draft' and 'DRAFT' both resolve to the enum")
        void shouldResolveStatus_caseInsensitive() {
            when(contractRepository.findByCreatedByAndStatusOrderByCreatedAtDesc(
                    OWNER_EMAIL, ContractStatus.DRAFT)).thenReturn(List.of());

            assertDoesNotThrow(() -> contractService.listContracts(OWNER_EMAIL, null, "draft"));

            verify(contractRepository).findByCreatedByAndStatusOrderByCreatedAtDesc(
                    OWNER_EMAIL, ContractStatus.DRAFT);
        }

        @Test
        @DisplayName("throws IllegalArgumentException when status value is unknown")
        void shouldThrow_whenUnknownStatus() {
            assertThrows(IllegalArgumentException.class,
                    () -> contractService.listContracts(OWNER_EMAIL, null, "INVALID_STATUS"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getContract
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getContract")
    class GetContract {

        @Test
        @DisplayName("returns a ContractResponse for the contract owner")
        void shouldReturn_contractResponse_forOwner() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(draftContract()));

            ContractResponse response = contractService.getContract(CONTRACT_ID, OWNER_EMAIL);

            assertNotNull(response);
            assertEquals(CONTRACT_ID, response.getId());
        }

        @Test
        @DisplayName("throws NotFoundException when the contract ID does not exist")
        void shouldThrow_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> contractService.getContract(CONTRACT_ID, OWNER_EMAIL));
        }

        @Test
        @DisplayName("throws NotFoundException when the caller is not the owner — same message as not-found")
        void shouldThrow_404_whenCallerIsNotOwner() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(draftContract()));  // owned by OWNER_EMAIL

            assertThrows(NotFoundException.class,
                    () -> contractService.getContract(CONTRACT_ID, OTHER_EMAIL));
        }

        @Test
        @DisplayName("not-found and wrong-owner throw the same NotFoundException message (no enumeration)")
        void shouldThrow_sameMessage_forNotFoundAndWrongOwner() {
            when(contractRepository.findById("nonexistent")).thenReturn(Optional.empty());
            NotFoundException notFound = assertThrows(NotFoundException.class,
                    () -> contractService.getContract("nonexistent", OWNER_EMAIL));

            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(draftContract()));
            NotFoundException wrongOwner = assertThrows(NotFoundException.class,
                    () -> contractService.getContract(CONTRACT_ID, OTHER_EMAIL));

            assertEquals(notFound.getMessage(), wrongOwner.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // updateContract — field updates and guards
    // (mergeFormFieldsSafe is covered by ContractServiceMergeFieldsTest)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateContract — field updates and guards")
    class UpdateContract {

        @Test
        @DisplayName("throws BadRequestException when contract is IN_REVIEW")
        void shouldThrow_400_whenInReview() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> contractService.updateContract(CONTRACT_ID, minimalRequest(), OWNER_EMAIL));
        }

        @Test
        @DisplayName("throws BadRequestException when contract is IN_APPROVAL")
        void shouldThrow_400_whenInApproval() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_APPROVAL);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> contractService.updateContract(CONTRACT_ID, minimalRequest(), OWNER_EMAIL));
        }

        @Test
        @DisplayName("throws BadRequestException when contract is READY_FOR_SIGNATURE")
        void shouldThrow_400_whenReadyForSignature() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> contractService.updateContract(CONTRACT_ID, minimalRequest(), OWNER_EMAIL));
        }

        @Test
        @DisplayName("does NOT throw BadRequestException for DRAFT status — update is allowed")
        void shouldNotThrow_forDraftStatus() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            lenient().when(contractRepository.existsByTitleAndCreatedByAndIdNot(anyString(), anyString(), anyString()))
                    .thenReturn(false);

            assertDoesNotThrow(() ->
                    contractService.updateContract(CONTRACT_ID, minimalRequest(), OWNER_EMAIL));
        }

        @Test
        @DisplayName("throws BadRequestException when renaming to a title owned by another of this owner's contracts")
        void shouldThrow_400_whenDuplicateTitleOnRename() {
            Contract c = draftContract();
            c.setTitle("Old Title");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.existsByTitleAndCreatedByAndIdNot(
                    "Service Agreement", OWNER_EMAIL, CONTRACT_ID)).thenReturn(true);

            ContractRequest r = minimalRequest();
            r.setTitle("Service Agreement");   // different from "Old Title"

            assertThrows(BadRequestException.class,
                    () -> contractService.updateContract(CONTRACT_ID, r, OWNER_EMAIL));
        }

        @Test
        @DisplayName("does NOT check duplicate title when the title in the request matches the stored title")
        void shouldSkipDuplicateCheck_whenTitleUnchanged() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));

            ContractRequest r = new ContractRequest();
            r.setTitle("Service Agreement");   // same as stored

            contractService.updateContract(CONTRACT_ID, r, OWNER_EMAIL);

            verify(contractRepository, never())
                    .existsByTitleAndCreatedByAndIdNot(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("does not overwrite client when request.getClient() is null")
        void shouldPreserve_client_whenNotProvided() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));

            ContractRequest r = new ContractRequest();
            r.setTitle("Service Agreement");   // only title
            // client is null → should be preserved

            contractService.updateContract(CONTRACT_ID, r, OWNER_EMAIL);

            assertEquals("Acme Corp", captureLastSaved().getClient());
        }

        @Test
        @DisplayName("updates client when provided in the request")
        void shouldUpdate_client_whenProvided() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            lenient().when(contractRepository.existsByTitleAndCreatedByAndIdNot(anyString(), anyString(), anyString()))
                    .thenReturn(false);

            ContractRequest r = minimalRequest();
            r.setClient("  New Client  ");

            contractService.updateContract(CONTRACT_ID, r, OWNER_EMAIL);

            assertEquals("New Client", captureLastSaved().getClient());
        }

        @Test
        @DisplayName("throws BadRequestException when new endDate is before the stored startDate")
        void shouldThrow_400_whenEndDateBeforeStoredStartDate() {
            Contract c = draftContract();
            c.setStartDate(LocalDate.of(2026, 6, 1));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            ContractRequest r = new ContractRequest();
            r.setTitle("Service Agreement");
            r.setEndDate(LocalDate.of(2026, 5, 1));   // before stored startDate

            assertThrows(BadRequestException.class,
                    () -> contractService.updateContract(CONTRACT_ID, r, OWNER_EMAIL));
        }

        @Test
        @DisplayName("throws BadRequestException when new endDate is before the new startDate in the same request")
        void shouldThrow_400_whenEndDateBeforeNewStartDate() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));

            ContractRequest r = new ContractRequest();
            r.setTitle("Service Agreement");
            r.setStartDate(LocalDate.of(2026, 8, 1));
            r.setEndDate(LocalDate.of(2026, 7, 1));   // before the new startDate

            assertThrows(BadRequestException.class,
                    () -> contractService.updateContract(CONTRACT_ID, r, OWNER_EMAIL));
        }

        @Test
        @DisplayName("bumps updatedAt to current time on successful save")
        void shouldBump_updatedAt_onSave() {
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            lenient().when(contractRepository.existsByTitleAndCreatedByAndIdNot(anyString(), anyString(), anyString()))
                    .thenReturn(false);

            contractService.updateContract(CONTRACT_ID, minimalRequest(), OWNER_EMAIL);

            assertTrue(captureLastSaved().getUpdatedAt().isAfter(before));
        }

        @Test
        @DisplayName("throws NotFoundException when contract ID does not exist")
        void shouldThrow_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> contractService.updateContract(CONTRACT_ID, minimalRequest(), OWNER_EMAIL));
        }

        @Test
        @DisplayName("throws NotFoundException when caller is not the owner")
        void shouldThrow_404_whenNotOwner() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));

            assertThrows(NotFoundException.class,
                    () -> contractService.updateContract(CONTRACT_ID, minimalRequest(), OTHER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getInboxContracts
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getInboxContracts")
    class GetInboxContracts {

        @Test
        @DisplayName("delegates to findByAssignedToEmail with the caller's email")
        void shouldCall_findByAssignedToEmail() {
            when(contractRepository.findByAssignedToEmail(OTHER_EMAIL)).thenReturn(List.of());

            contractService.getInboxContracts(OTHER_EMAIL);

            verify(contractRepository).findByAssignedToEmail(OTHER_EMAIL);
        }

        @Test
        @DisplayName("returns ContractResponse list — one entry per assigned contract")
        void shouldReturn_contractResponseList() {
            Contract c1 = draftContract();
            Contract c2 = draftContract();
            c2.setId("contract-02");
            when(contractRepository.findByAssignedToEmail(OTHER_EMAIL))
                    .thenReturn(List.of(c1, c2));

            List<ContractResponse> result = contractService.getInboxContracts(OTHER_EMAIL);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("returns an empty list when the user has no assigned contracts")
        void shouldReturn_emptyList_whenNoAssignments() {
            when(contractRepository.findByAssignedToEmail(OTHER_EMAIL)).thenReturn(List.of());

            List<ContractResponse> result = contractService.getInboxContracts(OTHER_EMAIL);

            assertTrue(result.isEmpty());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findOrphanedUploads
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findOrphanedUploads")
    class FindOrphanedUploads {

        @Test
        @DisplayName("delegates to findByFileUploadedFalseAndUploadInitiatedAtBefore with the given cutoff")
        void shouldDelegate_toRepository() {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
            when(contractRepository.findByFileUploadedFalseAndUploadInitiatedAtBefore(cutoff))
                    .thenReturn(List.of());

            contractService.findOrphanedUploads(cutoff);

            verify(contractRepository).findByFileUploadedFalseAndUploadInitiatedAtBefore(cutoff);
        }

        @Test
        @DisplayName("returns contracts that have fileUploaded=false and uploadInitiatedAt before the cutoff")
        void shouldReturn_orphanedContracts() {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
            Contract stale = draftContract();
            stale.setFileUploaded(false);
            stale.setUploadInitiatedAt(cutoff.minusMinutes(30));

            when(contractRepository.findByFileUploadedFalseAndUploadInitiatedAtBefore(cutoff))
                    .thenReturn(List.of(stale));

            List<Contract> result = contractService.findOrphanedUploads(cutoff);

            assertEquals(1, result.size());
            assertFalse(result.get(0).isFileUploaded());
        }

        @Test
        @DisplayName("returns an empty list when no orphaned uploads exist")
        void shouldReturn_emptyList_whenNoOrphans() {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
            when(contractRepository.findByFileUploadedFalseAndUploadInitiatedAtBefore(cutoff))
                    .thenReturn(List.of());

            assertTrue(contractService.findOrphanedUploads(cutoff).isEmpty());
        }
    }
}
