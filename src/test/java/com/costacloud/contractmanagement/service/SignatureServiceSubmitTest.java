package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.SignerAssignmentDto;
import com.costacloud.contractmanagement.dto.SubmitForSignatureRequest;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureService - submitForSignature")
class SignatureServiceSubmitTest {

    // ─── Mocks (fake versions of every dependency) ───────────────────────────

    @Mock ContractRepository contractRepository;
    @Mock SignatureRequestRepository signatureRequestRepository;
    @Mock AutoAdvanceService autoAdvanceService;
    @Mock EmailService emailService;
    @Mock MinioClient minioClient;
    @Mock CustomMinioClient customMinioClient;
    @Mock MongoTemplate mongoTemplate;

    // The real class under test — Mockito injects the mocks above into it
    @InjectMocks SignatureService signatureService;

    // ─── Shared test data ─────────────────────────────────────────────────────

    private static final String CONTRACT_ID  = "contract-123";
    private static final String OWNER_EMAIL  = "owner@test.com";
    private static final String SIGNER_EMAIL = "signer@client.com";

    // ─── Helper: build a minimal valid contract ───────────────────────────────

    private Contract readyContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setCreatedBy(OWNER_EMAIL);
        c.setStatus(ContractStatus.READY_FOR_SIGNATURE);
        c.setFileUploaded(true);
        c.setSigningRound(1);

        // Two parties defined on the template
        Party p1 = new Party(); p1.setId("party_1"); p1.setLabel("Buyer");
        Party p2 = new Party(); p2.setId("party_2"); p2.setLabel("Seller");
        c.setParties(List.of(p1, p2));

        return c;
    }

    // ─── Helper: build a minimal external assignment ──────────────────────────

    private SignerAssignmentDto externalAssignment(String partyId, String email, int order) {
        SignerAssignmentDto a = new SignerAssignmentDto();
        a.setPartyId(partyId);
        a.setPartyLabel("Buyer");
        a.setType("external");
        a.setEmail(email);
        a.setName("Test Signer");
        a.setOrder(order);
        return a;
    }

    // ─── Helper: build a minimal internal assignment ──────────────────────────

    private SignerAssignmentDto internalAssignment(String partyId, String email, int order) {
        SignerAssignmentDto a = new SignerAssignmentDto();
        a.setPartyId(partyId);
        a.setPartyLabel("Seller");
        a.setType("internal");
        a.setEmail(email);
        a.setName("Internal User");
        a.setOrder(order);
        return a;
    }

    // ─── Helper: wrap assignments in a request ────────────────────────────────

    private SubmitForSignatureRequest requestWith(SignerAssignmentDto... assignments) {
        SubmitForSignatureRequest req = new SubmitForSignatureRequest();
        req.setAssignments(List.of(assignments));
        req.setSenderName("Priya");
        return req;
    }

    // ─── Helper: build an ExternalSigner already on the contract ─────────────

    private ExternalSigner existingExternal(String partyId, String email, int order, String status) {
        ExternalSigner s = new ExternalSigner();
        s.setPartyId(partyId);
        s.setEmail(email);
        s.setOrder(order);
        s.setStatus(status);
        s.setToken("tok_" + partyId);
        s.setSigningRound(1);
        return s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — Contract ownership & status validation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Contract status validation")
    class StatusValidation {

        @Test
        @DisplayName("throws NotFoundException when contract does not exist")
        void shouldThrow_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(), OWNER_EMAIL)
            );
        }

        @Test
        @DisplayName("throws NotFoundException when caller is not the contract owner")
        void shouldThrow_whenCallerIsNotOwner() {
            Contract c = readyContract();
            c.setCreatedBy("someone-else@test.com");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(NotFoundException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(), OWNER_EMAIL)
            );
        }

        @Test
        @DisplayName("throws BadRequestException when contract status is DRAFT")
        void shouldThrow_whenStatusIsDraft() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.DRAFT);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("Contract must be ready for signature"));
        }

        @Test
        @DisplayName("throws BadRequestException when SIGNED_BY_EVERYONE and all parties already assigned")
        void shouldThrow_whenSignedByEveryoneAndNoUnassignedParties() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.SIGNED_BY_EVERYONE);
            // Both parties already have signers
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "completed"),
                existingExternal("party_2", "p2@test.com", 2, "completed")
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(
                    externalAssignment("party_1", SIGNER_EMAIL, 1)
                ), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("already completed the signing workflow"));
        }

        @Test
        @DisplayName("allows re-share when SIGNED_BY_EVERYONE but an unassigned party exists")
        void shouldAllow_whenSignedByEveryoneAndUnassignedPartyExists() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.SIGNED_BY_EVERYONE);
            c.setSignatureFlowStatus("all_completed");
            // Only party_1 was assigned — party_2 is still unassigned
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "completed")
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            doNothing().when(emailService).sendSignatureRequestEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any());

            // party_2 is unassigned — order 2 continues the chain (P1 was at order 1)
            assertDoesNotThrow(() ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(
                    externalAssignment("party_2", SIGNER_EMAIL, 2)
                ), OWNER_EMAIL)
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — File upload validation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("File upload validation")
    class FileValidation {

        @Test
        @DisplayName("throws BadRequestException when file has not been uploaded")
        void shouldThrow_whenFileNotUploaded() {
            Contract c = readyContract();
            c.setFileUploaded(false);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(
                    externalAssignment("party_1", SIGNER_EMAIL, 1)
                ), OWNER_EMAIL)
            );

            assertEquals("Contract file must be uploaded before sending for signature", ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — Assignment field validation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Assignment field validation")
    class AssignmentValidation {

        @BeforeEach
        void stubRepo() {
            when(contractRepository.findById(CONTRACT_ID))
                .thenReturn(Optional.of(readyContract()));
        }

        @Test
        @DisplayName("throws BadRequestException when assignment type is invalid")
        void shouldThrow_whenInvalidType() {
            SignerAssignmentDto a = externalAssignment("party_1", SIGNER_EMAIL, 1);
            a.setType("admin");   // invalid

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("Assignment type must be"));
        }

        @Test
        @DisplayName("throws BadRequestException when signer email is blank")
        void shouldThrow_whenEmailIsBlank() {
            SignerAssignmentDto a = externalAssignment("party_1", "", 1);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("Signer email is required"));
        }

        @Test
        @DisplayName("throws BadRequestException when external signer email has invalid format")
        void shouldThrow_whenEmailFormatInvalid() {
            SignerAssignmentDto a = externalAssignment("party_1", "not-an-email", 1);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("Invalid email format"));
        }

        @Test
        @DisplayName("throws BadRequestException when two assignments share the same email")
        void shouldThrow_whenDuplicateEmail() {
            SignerAssignmentDto a1 = externalAssignment("party_1", SIGNER_EMAIL, 1);
            SignerAssignmentDto a2 = externalAssignment("party_2", SIGNER_EMAIL, 2); // same email

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a1, a2), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("Duplicate signer email"));
        }

        @Test
        @DisplayName("throws BadRequestException when contractor assigns themselves as a signer")
        void shouldThrow_whenSelfAssignment() {
            // signer email = same as the caller (owner)
            SignerAssignmentDto a = externalAssignment("party_1", OWNER_EMAIL, 1);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a), OWNER_EMAIL)
            );

            assertEquals("You cannot assign yourself as a signer", ex.getMessage());
        }

        @Test
        @DisplayName("throws BadRequestException when the same party is assigned twice")
        void shouldThrow_whenDuplicatePartyId() {
            SignerAssignmentDto a1 = externalAssignment("party_1", "signer1@test.com", 1);
            SignerAssignmentDto a2 = externalAssignment("party_1", "signer2@test.com", 2); // same partyId

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a1, a2), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("is already assigned"));
        }

        @Test
        @DisplayName("throws BadRequestException when two assignments share the same order number")
        void shouldThrow_whenDuplicateOrder() {
            SignerAssignmentDto a1 = externalAssignment("party_1", "signer1@test.com", 1);
            SignerAssignmentDto a2 = internalAssignment("party_2", "signer2@test.com", 1); // same order

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a1, a2), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("assigned to more than one party"));
        }

        @Test
        @DisplayName("throws BadRequestException when first submission does not start at order 1")
        void shouldThrow_whenFirstSubmissionOrderNotOne() {
            SignerAssignmentDto a = externalAssignment("party_1", SIGNER_EMAIL, 2); // starts at 2

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("signing chain must start at order 1"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 4 — Re-share order validation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Re-share order validation")
    class ReShareValidation {

        @Test
        @DisplayName("throws BadRequestException when new order conflicts with an in-progress signer")
        void shouldThrow_whenNewOrderConflictsWithInProgress() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.IN_SIGNATURE);
            // P1 at order 1 is still in progress (unlocked)
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "unlocked")
            ));
            c.setCurrentSigningOrder(1);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            // Trying to add a new signer at order 1 (conflicts)
            SignerAssignmentDto a = externalAssignment("party_2", SIGNER_EMAIL, 1);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("conflicts with an in-progress signer"));
        }

        @Test
        @DisplayName("throws BadRequestException when new order is not greater than max existing order")
        void shouldThrow_whenNewOrderNotGreaterThanMaxExisting() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.IN_SIGNATURE);
            // P1 at order 1 completed — max existing order is 1
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "completed")
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            // order 1 is not greater than maxExistingOrder (1) — must use order 2 or higher
            SignerAssignmentDto a = externalAssignment("party_2", SIGNER_EMAIL, 1);

            BadRequestException ex = assertThrows(BadRequestException.class, () ->
                signatureService.submitForSignature(CONTRACT_ID, requestWith(a), OWNER_EMAIL)
            );

            assertTrue(ex.getMessage().contains("Order 1 is already used"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 5 — Happy paths (correct state after success)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Successful submission — correct signer state")
    class HappyPaths {

        @BeforeEach
        void stubSave() {
            when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().doNothing().when(emailService).sendSignatureRequestEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("external signer at order 1 gets status unlocked on first submission")
        void shouldSetUnlocked_forOrder1ExternalSigner() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_1", SIGNER_EMAIL, 1)
            ), OWNER_EMAIL);

            // Capture what was saved
            Contract saved = captureLastSavedContract();
            ExternalSigner signer = saved.getExternalSigners().get(0);

            assertEquals("unlocked", signer.getStatus());
            assertNotNull(signer.getUnlockedAt());
        }

        @Test
        @DisplayName("external signer at order 2 gets status pending on first submission")
        void shouldSetPending_forOrder2Signer() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_1", "signer1@test.com", 1),
                externalAssignment("party_2", "signer2@test.com", 2)
            ), OWNER_EMAIL);

            Contract saved = captureLastSavedContract();
            ExternalSigner order2Signer = saved.getExternalSigners().stream()
                .filter(s -> s.getOrder() == 2).findFirst().orElseThrow();

            assertEquals("pending", order2Signer.getStatus());
            assertNull(order2Signer.getUnlockedAt());
        }

        @Test
        @DisplayName("first submission sets currentSigningOrder to 1")
        void shouldSetCurrentSigningOrderToOne_onFirstSubmission() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_1", SIGNER_EMAIL, 1)
            ), OWNER_EMAIL);

            assertEquals(1, captureLastSavedContract().getCurrentSigningOrder());
        }

        @Test
        @DisplayName("first submission sets contract status to IN_SIGNATURE")
        void shouldSetStatusToInSignature_onFirstSubmission() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_1", SIGNER_EMAIL, 1)
            ), OWNER_EMAIL);

            assertEquals(ContractStatus.IN_SIGNATURE, captureLastSavedContract().getStatus());
        }

        @Test
        @DisplayName("first submission sets currentSigningOrder to 1")
        void shouldSetCurrentSigningOrder_toOne_onFirstSubmission() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_1", SIGNER_EMAIL, 1)
            ), OWNER_EMAIL);

            assertEquals(1, captureLastSavedContract().getCurrentSigningOrder());
        }

        @Test
        @DisplayName("re-share while in-progress sets all new signers to pending (appendOnly)")
        void shouldSetPending_forAllNewSigners_whenAppendOnly() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.IN_SIGNATURE);
            c.setCurrentSigningOrder(1);
            // P1 is in-progress
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "unlocked")
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_2", SIGNER_EMAIL, 3)
            ), OWNER_EMAIL);

            Contract saved = captureLastSavedContract();
            ExternalSigner newSigner = saved.getExternalSigners().stream()
                .filter(s -> s.getOrder() == 3).findFirst().orElseThrow();

            assertEquals("pending", newSigner.getStatus());
        }

        @Test
        @DisplayName("re-share while in-progress does NOT change currentSigningOrder (appendOnly)")
        void shouldNotChangeCurrentSigningOrder_whenAppendOnly() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.IN_SIGNATURE);
            c.setCurrentSigningOrder(1); // currently at order 1
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "unlocked")
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_2", SIGNER_EMAIL, 3)
            ), OWNER_EMAIL);

            // Must still be 1 — not changed to 3
            assertEquals(1, captureLastSavedContract().getCurrentSigningOrder());
        }

        @Test
        @DisplayName("re-share after all completed sets currentSigningOrder to the new signer's order")
        void shouldSetCurrentSigningOrder_toNewOrder_whenAllCompleted() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.IN_SIGNATURE);
            // P1–P4 all completed, max order = 4
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "completed"),
                existingExternal("party_2", "p2@test.com", 2, "completed"),
                existingExternal("party_3", "p3@test.com", 3, "completed"),
                existingExternal("party_4", "p4@test.com", 4, "completed")
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            // P5 continues the chain at order 5
            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_5", SIGNER_EMAIL, 5)
            ), OWNER_EMAIL);

            assertEquals(5, captureLastSavedContract().getCurrentSigningOrder());
        }

        @Test
        @DisplayName("re-share after all completed unlocks the new signer immediately")
        void shouldUnlockNewSigner_immediately_whenAllCompleted() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.IN_SIGNATURE);
            // P1 completed — max order = 1
            c.setExternalSigners(List.of(
                existingExternal("party_1", "p1@test.com", 1, "completed")
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            // P2 at order 2 — all previous done, so unlocks immediately
            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_2", SIGNER_EMAIL, 2)
            ), OWNER_EMAIL);

            Contract saved = captureLastSavedContract();
            ExternalSigner newSigner = saved.getExternalSigners().stream()
                .filter(s -> "party_2".equals(s.getPartyId())).findFirst().orElseThrow();

            assertEquals("unlocked", newSigner.getStatus());
            assertNotNull(newSigner.getUnlockedAt());
        }

        @Test
        @DisplayName("invitation email is sent for the unlocked external signer only")
        void shouldSendEmail_onlyForUnlockedExternalSigner() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                externalAssignment("party_1", SIGNER_EMAIL, 1),   // unlocked → email sent
                externalAssignment("party_2", "signer2@test.com", 2) // pending → no email
            ), OWNER_EMAIL);

            // sendSignatureRequestEmail should be called exactly once (for order 1 only)
            verify(emailService, times(1)).sendSignatureRequestEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            );
        }

        @Test
        @DisplayName("internal signer at order 1 gets status unlocked on first submission")
        void shouldSetUnlocked_forOrder1InternalSigner() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            signatureService.submitForSignature(CONTRACT_ID, requestWith(
                internalAssignment("party_1", "internal@company.com", 1)
            ), OWNER_EMAIL);

            Contract saved = captureLastSavedContract();
            InternalSigner signer = saved.getInternalSigners().get(0);

            assertEquals("unlocked", signer.getStatus());
            assertNotNull(signer.getUnlockedAt());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helper — captures what was passed to contractRepository.save()
    // ─────────────────────────────────────────────────────────────────────────

    private Contract captureLastSavedContract() {
        org.mockito.ArgumentCaptor<Contract> captor =
            org.mockito.ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
