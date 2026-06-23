package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.TerminationResponse;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.ConflictException;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractService — terminateContract")
class ContractServiceTerminationTest {

    @Mock ContractRepository contractRepository;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks ContractService contractService;

    private static final String CONTRACT_ID  = "contract-term-01";
    private static final String OWNER_EMAIL  = "owner@test.com";
    private static final String OTHER_EMAIL  = "other@test.com";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contractService, "bucketName", "test-bucket");
        lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Builds a contract with stored status SIGNED and endDate in the past → computes EXPIRED */
    private Contract expiredContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setCreatedBy(OWNER_EMAIL);
        c.setStatus(ContractStatus.SIGNED);
        c.setStartDate(LocalDate.now().minusYears(1));
        c.setEndDate(LocalDate.now().minusDays(1));   // yesterday → EXPIRED
        return c;
    }

    /** Returns the Contract captured by the last contractRepository.save() call */
    private Contract captureLastSavedContract() {
        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — Success: EXPIRED contract terminates correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Success — terminating an EXPIRED contract")
    class SuccessTermination {

        @BeforeEach
        void stub() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(expiredContract()));
        }

        @Test
        @DisplayName("returns success=true and alreadyTerminated=false")
        void shouldReturn_successTrue_andAlreadyTerminatedFalse() {
            TerminationResponse response = contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertTrue(response.isSuccess());
            assertFalse(response.isAlreadyTerminated());
        }

        @Test
        @DisplayName("saves the contract with status TERMINATED")
        void shouldSave_statusTerminated() {
            contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertEquals(ContractStatus.TERMINATED, captureLastSavedContract().getStatus());
        }

        @Test
        @DisplayName("saves terminatedBy as the JWT email — never from request body")
        void shouldSave_terminatedBy_fromJwtEmail() {
            contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertEquals(OWNER_EMAIL, captureLastSavedContract().getTerminatedBy());
        }

        @Test
        @DisplayName("saves a non-null terminatedAt timestamp set by the server")
        void shouldSave_terminatedAt_notNull() {
            contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertNotNull(captureLastSavedContract().getTerminatedAt());
        }

        @Test
        @DisplayName("clears renewalStatus to null on termination")
        void shouldClear_renewalStatus() {
            Contract c = expiredContract();
            c.setRenewalStatus("some_status");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertNull(captureLastSavedContract().getRenewalStatus());
        }

        @Test
        @DisplayName("clears renewedContractId to null on termination")
        void shouldClear_renewedContractId() {
            Contract c = expiredContract();
            c.setRenewedContractId("renewal-draft-abc");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertNull(captureLastSavedContract().getRenewedContractId());
        }

        @Test
        @DisplayName("updates the updatedAt timestamp on the saved contract")
        void shouldUpdate_updatedAt() {
            contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertNotNull(captureLastSavedContract().getUpdatedAt());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — Idempotency: already TERMINATED
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Idempotency — contract is already TERMINATED")
    class AlreadyTerminated {

        private Contract terminatedContract() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.TERMINATED);
            c.setEndDate(LocalDate.now().minusDays(30));
            return c;
        }

        @BeforeEach
        void stub() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(terminatedContract()));
        }

        @Test
        @DisplayName("returns success=true and alreadyTerminated=true")
        void shouldReturn_alreadyTerminatedTrue() {
            TerminationResponse response = contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            assertTrue(response.isSuccess());
            assertTrue(response.isAlreadyTerminated());
        }

        @Test
        @DisplayName("does NOT call contractRepository.save() on an already-terminated contract")
        void shouldNotCallSave_onAlreadyTerminated() {
            contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL);

            verify(contractRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — Status guard: only EXPIRED contracts can be terminated
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status guard — non-EXPIRED contracts are rejected with 400")
    class StatusGuard {

        @Test
        @DisplayName("throws BadRequestException with \"ACTIVE\" in message for an active contract")
        void shouldThrow_400_forActiveContract() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.SIGNED);
            c.setStartDate(LocalDate.now().minusMonths(6));
            c.setEndDate(LocalDate.now().plusMonths(6));     // far future → ACTIVE
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("\"ACTIVE\""));
            assertTrue(ex.getMessage().contains("Only expired contracts can be terminated"));
        }

        @Test
        @DisplayName("throws BadRequestException with \"EXPIRING\" in message for an expiring contract")
        void shouldThrow_400_forExpiringContract() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.SIGNED);
            c.setStartDate(LocalDate.now().minusMonths(6));
            c.setEndDate(LocalDate.now().plusDays(15));      // 15 days → EXPIRING
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("\"EXPIRING\""));
        }

        @Test
        @DisplayName("throws BadRequestException with \"SIGNED\" for a finalized contract with future startDate")
        void shouldThrow_400_forSignedContract_futureStartDate() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.SIGNED);
            c.setStartDate(LocalDate.now().plusMonths(1));   // starts next month → SIGNED
            c.setEndDate(LocalDate.now().plusMonths(13));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("\"SIGNED\""));
        }

        @Test
        @DisplayName("throws BadRequestException with \"SIGNED\" for a finalized contract with no endDate")
        void shouldThrow_400_forSignedContract_noEndDate() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.SIGNED);
            c.setStartDate(LocalDate.now().minusMonths(1));
            c.setEndDate(null);                              // no endDate → SIGNED
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("\"SIGNED\""));
        }

        @Test
        @DisplayName("throws BadRequestException with \"DRAFT\" in message for a draft contract")
        void shouldThrow_400_forDraftContract() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.DRAFT);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("\"DRAFT\""));
        }

        @Test
        @DisplayName("throws BadRequestException with \"IN_REVIEW\" in message for a contract under review")
        void shouldThrow_400_forInReviewContract() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.IN_REVIEW);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("\"IN_REVIEW\""));
        }

        @Test
        @DisplayName("throws BadRequestException with \"IN_SIGNATURE\" for a contract mid-signing")
        void shouldThrow_400_forInSignatureContract() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.IN_SIGNATURE);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("\"IN_SIGNATURE\""));
        }

        @Test
        @DisplayName("does not call save on a status-guard rejection")
        void shouldNotSave_onStatusRejection() {
            Contract c = new Contract();
            c.setId(CONTRACT_ID);
            c.setCreatedBy(OWNER_EMAIL);
            c.setStatus(ContractStatus.DRAFT);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            verify(contractRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 4 — Renewal guard: active renewal blocks termination
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Renewal guard — 409 when renewal is in progress")
    class RenewalGuard {

        @Test
        @DisplayName("throws ConflictException when renewalStatus is \"in_progress\"")
        void shouldThrow_409_whenRenewalInProgress() {
            Contract c = expiredContract();
            c.setRenewalStatus("in_progress");
            c.setRenewedContractId("renewal-draft-xyz");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            ConflictException ex = assertThrows(ConflictException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            assertTrue(ex.getMessage().contains("active renewal in progress"));
        }

        @Test
        @DisplayName("does NOT throw 409 when renewalStatus is null")
        void shouldNotThrow_409_whenRenewalStatusIsNull() {
            Contract c = expiredContract();
            c.setRenewalStatus(null);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertDoesNotThrow(() -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));
        }

        @Test
        @DisplayName("does not call save on a renewal-guard rejection")
        void shouldNotSave_onRenewalGuardRejection() {
            Contract c = expiredContract();
            c.setRenewalStatus("in_progress");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(ConflictException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));

            verify(contractRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 5 — Ownership and existence guard
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Ownership guard — 404 for unknown or unowned contracts")
    class OwnershipGuard {

        @Test
        @DisplayName("throws NotFoundException when contract ID does not exist")
        void shouldThrow_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OWNER_EMAIL));
        }

        @Test
        @DisplayName("throws NotFoundException when the caller is not the contract owner")
        void shouldThrow_404_whenCallerIsNotOwner() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(expiredContract()));   // owned by OWNER_EMAIL

            // OTHER_EMAIL is authenticated but does not own the contract
            assertThrows(NotFoundException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OTHER_EMAIL));
        }

        @Test
        @DisplayName("does not reveal ownership — both not-found and wrong-owner throw NotFoundException")
        void shouldThrow_sameException_forNotFoundAndWrongOwner() {
            // not found
            when(contractRepository.findById("nonexistent")).thenReturn(Optional.empty());
            Exception notFound = assertThrows(NotFoundException.class,
                    () -> contractService.terminateContract("nonexistent", OWNER_EMAIL));

            // wrong owner
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(expiredContract()));
            Exception wrongOwner = assertThrows(NotFoundException.class,
                    () -> contractService.terminateContract(CONTRACT_ID, OTHER_EMAIL));

            // Both must expose the same message — contract ID enumeration prevention
            assertEquals(notFound.getMessage(), wrongOwner.getMessage());
        }
    }
}
