package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedWorkflowService")
class UnifiedWorkflowServiceTest {

    @Mock ContractRepository contractRepository;
    @Mock SignatureService signatureService;
    @Mock CustomMinioClient customMinioClient;
    @Mock MinioClient minioClient;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks UnifiedWorkflowService service;

    private static final String CONTRACT_ID    = "contract-uw-01";
    private static final String OWNER          = "owner@test.com";
    private static final String REVIEWER_EMAIL = "reviewer@test.com";
    private static final String APPROVER_EMAIL = "approver@test.com";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "bucketName", "test-bucket");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Contract draftContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setCreatedBy(OWNER);
        c.setStatus(ContractStatus.DRAFT);
        c.setStartDate(LocalDate.now().minusMonths(1));
        c.setEndDate(LocalDate.now().plusMonths(11));
        return c;
    }

    private Contract rejectedContract(ContractStatus rejectionStatus) {
        Contract c = draftContract();
        c.setStatus(rejectionStatus);
        c.setModificationRequests(new ArrayList<>());
        return c;
    }

    private ParticipantAssignment assignment(String email, String name,
                                             ParticipantRole role, int order) {
        ParticipantAssignment a = new ParticipantAssignment();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setOrder(order);
        return a;
    }

    private WorkflowParticipant participant(String email, ParticipantRole role,
                                            int order, String status) {
        WorkflowParticipant p = new WorkflowParticipant();
        p.setEmail(email);
        p.setRole(role);
        p.setOrder(order);
        p.setStatus(status);
        return p;
    }

    private FlowSubmitRequest approverOnlyRequest() {
        FlowSubmitRequest req = new FlowSubmitRequest();
        req.setParticipants(List.of(
                assignment(APPROVER_EMAIL, "Approver One", ParticipantRole.APPROVER, 1)));
        req.setExternalSigningIncluded(false);
        return req;
    }

    private FlowSubmitRequest reviewerThenApproverRequest() {
        FlowSubmitRequest req = new FlowSubmitRequest();
        req.setParticipants(List.of(
                assignment(REVIEWER_EMAIL, "Reviewer One", ParticipantRole.REVIEWER, 1),
                assignment(APPROVER_EMAIL, "Approver One", ParticipantRole.APPROVER, 2)));
        req.setExternalSigningIncluded(false);
        return req;
    }

    private FlowCompleteRequest completeRequestWithUpload() {
        FlowCompleteRequest.Part part = new FlowCompleteRequest.Part();
        part.setPartNumber(1);
        part.setEtag("etag-abc");
        FlowCompleteRequest req = new FlowCompleteRequest();
        req.setUploadId("upload-xyz");
        req.setParts(List.of(part));
        return req;
    }

    private SubmitForSignatureRequest externalSignerRequest() {
        SubmitForSignatureRequest req = new SubmitForSignatureRequest();
        SignerAssignmentDto signer = new SignerAssignmentDto();
        signer.setEmail("client@example.com");
        signer.setName("Client");
        signer.setPartyId("p-external");
        signer.setPartyLabel("Client Party");
        signer.setType("external");
        signer.setOrder(1);
        req.setAssignments(List.of(signer));
        req.setSenderName("Owner Name");
        return req;
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("submit — fresh DRAFT")
    class SubmitFreshDraft {

        @Test
        @DisplayName("approver-only → IN_APPROVAL, approver unlocked at order 1")
        void approverOnly_goesToInApproval() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            assertEquals(ContractStatus.IN_APPROVAL, saved.getStatus());
            assertEquals(1, saved.getCurrentParticipantOrder());
            assertEquals(1, saved.getParticipants().size());
            assertEquals("unlocked", saved.getParticipants().get(0).getStatus());
        }

        @Test
        @DisplayName("reviewer(1) then approver(2) → IN_REVIEW, reviewer unlocked, approver pending")
        void reviewerThenApprover_goesToInReview() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submit(CONTRACT_ID, reviewerThenApproverRequest(), OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            assertEquals(ContractStatus.IN_REVIEW, saved.getStatus());
            assertEquals(1, saved.getCurrentParticipantOrder());

            WorkflowParticipant reviewer = saved.getParticipants().stream()
                    .filter(p -> p.getRole() == ParticipantRole.REVIEWER).findFirst().orElseThrow();
            assertEquals("unlocked", reviewer.getStatus());

            WorkflowParticipant approver = saved.getParticipants().stream()
                    .filter(p -> p.getRole() == ParticipantRole.APPROVER).findFirst().orElseThrow();
            assertEquals("pending", approver.getStatus());
        }

        @Test
        @DisplayName("sentBy is set to caller email on every participant")
        void sentByIsSetToCaller() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            captor.getValue().getParticipants()
                    .forEach(p -> assertEquals(OWNER, p.getSentBy()));
        }

        @Test
        @DisplayName("externalSigningIncluded flag persisted on contract")
        void externalSigningIncluded_persisted() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FlowSubmitRequest req = approverOnlyRequest();
            req.setExternalSigningIncluded(true);
            ExternalSignerAssignment ext = new ExternalSignerAssignment();
            ext.setEmail("client@example.com");
            ext.setOrder(1);
            req.setExternalSigners(List.of(ext));

            service.submit(CONTRACT_ID, req, OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            assertTrue(captor.getValue().isExternalSigningIncluded());
            assertNotNull(captor.getValue().getPendingExternalSigners());
        }

        @Test
        @DisplayName("externalSigningIncluded=true without externalSigners → BadRequestException")
        void externalSigningTrue_withoutExternalSigners_throws() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowSubmitRequest req = approverOnlyRequest();
            req.setExternalSigningIncluded(true);
            req.setExternalSigners(Collections.emptyList());

            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("contract not found → NotFoundException")
        void notFound_throws() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class,
                    () -> service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER));
        }

        @Test
        @DisplayName("non-owner → NotFoundException (ownership masking)")
        void nonOwner_throwsNotFound() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(NotFoundException.class,
                    () -> service.submit(CONTRACT_ID, approverOnlyRequest(), "other@test.com"));
        }

        @Test
        @DisplayName("wrong status (IN_REVIEW) → BadRequestException")
        void wrongStatus_throws() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER));
        }

        @Test
        @DisplayName("empty participant list → BadRequestException")
        void noParticipants_throws() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            FlowSubmitRequest req = new FlowSubmitRequest();
            req.setParticipants(Collections.emptyList());
            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("no approver in list → BadRequestException")
        void noApprover_throws() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            FlowSubmitRequest req = new FlowSubmitRequest();
            req.setParticipants(List.of(
                    assignment(REVIEWER_EMAIL, "R", ParticipantRole.REVIEWER, 1)));
            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("duplicate order numbers → BadRequestException")
        void duplicateOrders_throws() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            FlowSubmitRequest req = new FlowSubmitRequest();
            req.setParticipants(List.of(
                    assignment(REVIEWER_EMAIL, "R", ParticipantRole.REVIEWER, 1),
                    assignment(APPROVER_EMAIL, "A", ParticipantRole.APPROVER, 1)));
            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("duplicate participant emails → BadRequestException")
        void duplicateEmails_throws() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            FlowSubmitRequest req = new FlowSubmitRequest();
            req.setParticipants(List.of(
                    assignment(REVIEWER_EMAIL, "R", ParticipantRole.REVIEWER, 1),
                    assignment(REVIEWER_EMAIL, "A", ParticipantRole.APPROVER, 2)));
            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("self-assignment → BadRequestException")
        void selfAssignment_throws() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            FlowSubmitRequest req = new FlowSubmitRequest();
            req.setParticipants(List.of(
                    assignment(OWNER, "Self", ParticipantRole.APPROVER, 1)));
            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("reviewer order >= approver order → BadRequestException")
        void reviewerOrderHigherThanApprover_throws() {
            Contract c = draftContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            FlowSubmitRequest req = new FlowSubmitRequest();
            req.setParticipants(List.of(
                    assignment(APPROVER_EMAIL, "A", ParticipantRole.APPROVER, 1),
                    assignment(REVIEWER_EMAIL, "R", ParticipantRole.REVIEWER, 2)));
            assertThrows(BadRequestException.class,
                    () -> service.submit(CONTRACT_ID, req, OWNER));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("submit — resubmit after rejection")
    class Resubmit {

        @Test
        @DisplayName("REJECTED_BY_REVIEWER → full reset, all participants replaced, status IN_REVIEW")
        void afterRejectedByReviewer_fullReset() {
            Contract c = rejectedContract(ContractStatus.REJECTED_BY_REVIEWER);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "rejected"))));

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submit(CONTRACT_ID, reviewerThenApproverRequest(), OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            assertEquals(ContractStatus.IN_REVIEW, saved.getStatus());
            assertEquals(2, saved.getParticipants().size());
            saved.getParticipants().forEach(p -> assertNotEquals("rejected", p.getStatus()));
        }

        @Test
        @DisplayName("REJECTED_BY_APPROVER → full reset (not partial), all participants replaced")
        void afterRejectedByApprover_fullReset() {
            Contract c = rejectedContract(ContractStatus.REJECTED_BY_APPROVER);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "completed"),
                    participant(APPROVER_EMAIL, ParticipantRole.APPROVER, 2, "rejected"))));

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Full new list with both reviewer and approver
            FlowSubmitRequest req = new FlowSubmitRequest();
            req.setParticipants(List.of(
                    assignment("newreviewer@test.com", "New Reviewer", ParticipantRole.REVIEWER, 1),
                    assignment("newapprover@test.com", "New Approver", ParticipantRole.APPROVER, 2)));

            service.submit(CONTRACT_ID, req, OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            assertEquals(ContractStatus.IN_REVIEW, saved.getStatus());
            assertEquals(2, saved.getParticipants().size());
            // Old completed reviewer is gone — completely replaced
            assertTrue(saved.getParticipants().stream()
                    .noneMatch(p -> p.getEmail().equalsIgnoreCase(REVIEWER_EMAIL)));
            // New reviewer is unlocked
            assertTrue(saved.getParticipants().stream()
                    .anyMatch(p -> p.getEmail().equalsIgnoreCase("newreviewer@test.com")
                            && "unlocked".equals(p.getStatus())));
        }

        @Test
        @DisplayName("legacy REJECTED status also accepted for resubmission")
        void legacyRejectedStatus_accepted() {
            Contract c = rejectedContract(ContractStatus.REJECTED);
            c.setParticipants(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertDoesNotThrow(() -> service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER));
        }

        @Test
        @DisplayName("resubmit deletes _signed.pdf from MinIO and clears signedPdfKey")
        void resubmit_deletesSignedPdf_andClearsSignedPdfKey() throws Exception {
            Contract c = rejectedContract(ContractStatus.REJECTED_BY_APPROVER);
            c.setSignedPdfKey("contracts/" + CONTRACT_ID + "_signed.pdf");
            c.setParticipants(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER);

            verify(minioClient).removeObject(any(RemoveObjectArgs.class));

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            assertNull(captor.getValue().getSignedPdfKey());
        }

        @Test
        @DisplayName("resubmit appends modification request with role 'contractor'")
        void resubmit_appendsModificationRequest() {
            Contract c = rejectedContract(ContractStatus.REJECTED_BY_REVIEWER);
            c.setParticipants(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            List<ModificationRequest> mods = captor.getValue().getModificationRequests();
            assertFalse(mods.isEmpty());
            assertEquals("contractor", mods.get(mods.size() - 1).getRole());
        }

        @Test
        @DisplayName("resubmit with externalSigningIncluded=true stores new externalSigners")
        void resubmit_externalSigners_stored() {
            Contract c = rejectedContract(ContractStatus.REJECTED_BY_REVIEWER);
            c.setParticipants(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FlowSubmitRequest req = approverOnlyRequest();
            req.setExternalSigningIncluded(true);
            ExternalSignerAssignment ext = new ExternalSignerAssignment();
            ext.setEmail("client@example.com");
            ext.setOrder(1);
            req.setExternalSigners(List.of(ext));

            service.submit(CONTRACT_ID, req, OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();
            assertTrue(saved.isExternalSigningIncluded());
            assertNotNull(saved.getPendingExternalSigners());
            assertEquals(1, saved.getPendingExternalSigners().size());
        }

        @Test
        @DisplayName("resubmit with externalSigningIncluded=false clears pendingExternalSigners")
        void resubmit_externalSigningFalse_clearsPendingSigners() {
            Contract c = rejectedContract(ContractStatus.REJECTED_BY_APPROVER);
            c.setParticipants(new ArrayList<>());
            ExternalSignerAssignment old = new ExternalSignerAssignment();
            old.setEmail("old@example.com");
            c.setPendingExternalSigners(new ArrayList<>(List.of(old)));

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.submit(CONTRACT_ID, approverOnlyRequest(), OWNER);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            assertNull(captor.getValue().getPendingExternalSigners());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("saveFieldEdits")
    class SaveFieldEdits {

        private Contract inReviewContract() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setCurrentParticipantOrder(1);
            WorkflowParticipant p =
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked");
            c.setParticipants(new ArrayList<>(List.of(p)));

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldName", "field1");
            field.put("name", "field1");
            field.put("assignedParty", "party-1");
            field.put("value", "");
            c.setFormFields(new ArrayList<>(List.of(field)));
            return c;
        }

        @Test
        @DisplayName("first save — participant moves to in_progress")
        void firstSave_setsInProgress() {
            Contract c = inReviewContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FlowFieldEditRequest req = new FlowFieldEditRequest();
            req.setFieldValues(Map.of("field1", "hello"));

            service.saveFieldEdits(CONTRACT_ID, req, REVIEWER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            assertEquals("in_progress",
                    captor.getValue().getParticipants().get(0).getStatus());
        }

        @Test
        @DisplayName("formFields merged and filledBy set to caller")
        void formFieldsMerged_withFilledBy() {
            Contract c = inReviewContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("fieldName", "field1");
            incoming.put("value", "Jane");
            FlowFieldEditRequest req = new FlowFieldEditRequest();
            req.setFormFields(List.of(incoming));

            service.saveFieldEdits(CONTRACT_ID, req, REVIEWER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Map<String, Object> saved = captor.getValue().getFormFields().get(0);

            assertEquals("Jane", saved.get("value"));
            assertEquals(REVIEWER_EMAIL, saved.get("filledBy"));
        }

        @Test
        @DisplayName("second save — stays in_progress, value updated")
        void secondSave_keepsInProgress() {
            Contract c = inReviewContract();
            c.getParticipants().get(0).setStatus("in_progress");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FlowFieldEditRequest req = new FlowFieldEditRequest();
            req.setFieldValues(Map.of("field1", "updated"));

            service.saveFieldEdits(CONTRACT_ID, req, REVIEWER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            assertEquals("in_progress",
                    captor.getValue().getParticipants().get(0).getStatus());
        }

        @Test
        @DisplayName("xfdfData saved when provided")
        void xfdfData_savedWhenProvided() {
            Contract c = inReviewContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FlowFieldEditRequest req = new FlowFieldEditRequest();
            req.setXfdfData("<?xml version=\"1.0\"?><xfdf/>");

            service.saveFieldEdits(CONTRACT_ID, req, REVIEWER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            assertEquals("<?xml version=\"1.0\"?><xfdf/>", captor.getValue().getXfdfData());
        }

        @Test
        @DisplayName("non-participant → BadRequestException")
        void nonParticipant_throws() {
            Contract c = inReviewContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowFieldEditRequest req = new FlowFieldEditRequest();
            req.setFieldValues(Map.of("field1", "x"));
            assertThrows(BadRequestException.class,
                    () -> service.saveFieldEdits(CONTRACT_ID, req, "stranger@test.com"));
        }

        @Test
        @DisplayName("pending (locked) participant → BadRequestException")
        void pendingParticipant_throws() {
            Contract c = inReviewContract();
            c.getParticipants().get(0).setStatus("pending");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowFieldEditRequest req = new FlowFieldEditRequest();
            req.setFieldValues(Map.of("field1", "x"));
            assertThrows(BadRequestException.class,
                    () -> service.saveFieldEdits(CONTRACT_ID, req, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("wrong contract status (DRAFT) → BadRequestException")
        void wrongStatus_throws() {
            Contract c = draftContract();
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked"))));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowFieldEditRequest req = new FlowFieldEditRequest();
            req.setFieldValues(Map.of("field1", "x"));
            assertThrows(BadRequestException.class,
                    () -> service.saveFieldEdits(CONTRACT_ID, req, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("contract not found → NotFoundException")
        void notFound_throws() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            FlowFieldEditRequest req = new FlowFieldEditRequest();
            assertThrows(NotFoundException.class,
                    () -> service.saveFieldEdits(CONTRACT_ID, req, REVIEWER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("markComplete")
    class MarkComplete {

        private Contract reviewerActiveContract() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "in_progress"))));
            c.setFormFields(new ArrayList<>());
            return c;
        }

        private Contract approverActiveContract() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_APPROVAL);
            c.setCurrentParticipantOrder(2);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "completed"),
                    participant(APPROVER_EMAIL, ParticipantRole.APPROVER, 2, "in_progress"))));
            c.setFormFields(new ArrayList<>());
            return c;
        }

        @Test
        @DisplayName("reviewer completes — PDF finalized to _signed.pdf, fileUploaded set true")
        void reviewer_pdfFinalizedAndFileUploadedTrue() throws Exception {
            Contract c = reviewerActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markComplete(CONTRACT_ID, completeRequestWithUpload(), REVIEWER_EMAIL);

            verify(customMinioClient).finishMultipartUpload(
                    eq("test-bucket"),
                    eq("contracts/" + CONTRACT_ID + "_signed.pdf"),
                    eq("upload-xyz"),
                    any());

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();
            assertTrue(saved.isFileUploaded());
            assertEquals("contracts/" + CONTRACT_ID + "_signed.pdf", saved.getSignedPdfKey());
        }

        @Test
        @DisplayName("reviewer completes — next pending participant unlocked, status to IN_APPROVAL")
        void reviewerCompletes_advancesToApprover() throws Exception {
            Contract c = reviewerActiveContract();
            c.getParticipants().add(
                    participant(APPROVER_EMAIL, ParticipantRole.APPROVER, 2, "pending"));

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markComplete(CONTRACT_ID, completeRequestWithUpload(), REVIEWER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            WorkflowParticipant done = saved.getParticipants().stream()
                    .filter(p -> p.getEmail().equalsIgnoreCase(REVIEWER_EMAIL))
                    .findFirst().orElseThrow();
            assertEquals("completed", done.getStatus());
            assertNotNull(done.getCompletedAt());

            WorkflowParticipant unlocked = saved.getParticipants().stream()
                    .filter(p -> p.getEmail().equalsIgnoreCase(APPROVER_EMAIL))
                    .findFirst().orElseThrow();
            assertEquals("unlocked", unlocked.getStatus());

            assertEquals(ContractStatus.IN_APPROVAL, saved.getStatus());
            assertEquals(2, saved.getCurrentParticipantOrder());
        }

        @Test
        @DisplayName("reviewer completes — no more participants → READY_FOR_SIGNATURE")
        void reviewerCompletes_noNext_readyForSignature() throws Exception {
            Contract c = reviewerActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markComplete(CONTRACT_ID, completeRequestWithUpload(), REVIEWER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            assertEquals(ContractStatus.READY_FOR_SIGNATURE, saved.getStatus());
            assertNull(saved.getCurrentParticipantOrder());
        }

        @Test
        @DisplayName("reviewer missing uploadId → BadRequestException")
        void reviewer_missingUploadId_throws() {
            Contract c = reviewerActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowCompleteRequest req = new FlowCompleteRequest();
            FlowCompleteRequest.Part part = new FlowCompleteRequest.Part();
            part.setPartNumber(1);
            part.setEtag("etag");
            req.setParts(List.of(part));
            // no uploadId
            assertThrows(BadRequestException.class,
                    () -> service.markComplete(CONTRACT_ID, req, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("reviewer missing parts → BadRequestException")
        void reviewer_missingParts_throws() {
            Contract c = reviewerActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowCompleteRequest req = new FlowCompleteRequest();
            req.setUploadId("upload-id");
            // no parts
            assertThrows(BadRequestException.class,
                    () -> service.markComplete(CONTRACT_ID, req, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("approver completes — PDF finalized, version incremented, signedPdfKey set, fileUploaded true")
        void approverCompletes_pdfUploaded() throws Exception {
            Contract c = approverActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markComplete(CONTRACT_ID, completeRequestWithUpload(), APPROVER_EMAIL);

            verify(customMinioClient).finishMultipartUpload(
                    eq("test-bucket"),
                    eq("contracts/" + CONTRACT_ID + "_signed.pdf"),
                    eq("upload-xyz"),
                    any());

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            WorkflowParticipant doneApprover = saved.getParticipants().stream()
                    .filter(p -> p.getEmail().equalsIgnoreCase(APPROVER_EMAIL))
                    .findFirst().orElseThrow();
            assertEquals("completed", doneApprover.getStatus());
            assertEquals(1, saved.getVersion());
            assertEquals("contracts/" + CONTRACT_ID + "_signed.pdf", saved.getSignedPdfKey());
            assertTrue(saved.isFileUploaded());
        }

        @Test
        @DisplayName("approver completes — no next participant → READY_FOR_SIGNATURE")
        void approverCompletes_noNext_readyForSignature() throws Exception {
            Contract c = approverActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markComplete(CONTRACT_ID, completeRequestWithUpload(), APPROVER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            assertEquals(ContractStatus.READY_FOR_SIGNATURE, captor.getValue().getStatus());
            assertNull(captor.getValue().getCurrentParticipantOrder());
        }

        @Test
        @DisplayName("approver missing uploadId → BadRequestException")
        void approver_missingUploadId_throws() {
            Contract c = approverActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowCompleteRequest req = new FlowCompleteRequest();
            FlowCompleteRequest.Part part = new FlowCompleteRequest.Part();
            part.setPartNumber(1);
            part.setEtag("etag");
            req.setParts(List.of(part));
            assertThrows(BadRequestException.class,
                    () -> service.markComplete(CONTRACT_ID, req, APPROVER_EMAIL));
        }

        @Test
        @DisplayName("approver missing parts → BadRequestException")
        void approver_missingParts_throws() {
            Contract c = approverActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowCompleteRequest req = new FlowCompleteRequest();
            req.setUploadId("upload-xyz");
            assertThrows(BadRequestException.class,
                    () -> service.markComplete(CONTRACT_ID, req, APPROVER_EMAIL));
        }

        @Test
        @DisplayName("non-participant → BadRequestException")
        void nonParticipant_throws() {
            Contract c = reviewerActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.markComplete(CONTRACT_ID, completeRequestWithUpload(),
                            "stranger@test.com"));
        }

        @Test
        @DisplayName("pending (locked) participant → BadRequestException")
        void pendingParticipant_throws() {
            Contract c = reviewerActiveContract();
            c.getParticipants().get(0).setStatus("pending");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.markComplete(CONTRACT_ID, completeRequestWithUpload(),
                            REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("wrong contract status (DRAFT) → BadRequestException")
        void wrongStatus_throws() {
            Contract c = draftContract();
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "in_progress"))));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.markComplete(CONTRACT_ID, completeRequestWithUpload(),
                            REVIEWER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("reject")
    class Reject {

        private Contract inReviewActiveContract() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "in_progress"))));
            c.setModificationRequests(new ArrayList<>());
            return c;
        }

        @Test
        @DisplayName("reviewer rejects → REJECTED_BY_REVIEWER, participant marked rejected, mod role='reviewer'")
        void reviewerRejects_setsRejectedByReviewer() {
            Contract c = inReviewActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FlowRejectRequest req = new FlowRejectRequest();
            req.setMessage("Clause 3 needs revision");

            service.reject(CONTRACT_ID, req, REVIEWER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            assertEquals(ContractStatus.REJECTED_BY_REVIEWER, saved.getStatus());

            WorkflowParticipant p = saved.getParticipants().get(0);
            assertEquals("rejected", p.getStatus());
            assertNotNull(p.getRejectedAt());
            assertEquals("Clause 3 needs revision", p.getComments());

            assertFalse(saved.getModificationRequests().isEmpty());
            ModificationRequest mod = saved.getModificationRequests().get(0);
            assertEquals(REVIEWER_EMAIL, mod.getRequestedBy());
            assertEquals("reviewer", mod.getRole());
            assertEquals("Clause 3 needs revision", mod.getMessage());
        }

        @Test
        @DisplayName("approver rejects → REJECTED_BY_APPROVER, mod role='approver'")
        void approverRejects_setsRejectedByApprover() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_APPROVAL);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(APPROVER_EMAIL, ParticipantRole.APPROVER, 1, "in_progress"))));
            c.setModificationRequests(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FlowRejectRequest req = new FlowRejectRequest();
            req.setMessage("Needs CFO sign-off");

            service.reject(CONTRACT_ID, req, APPROVER_EMAIL);

            ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(captor.capture());
            Contract saved = captor.getValue();

            assertEquals(ContractStatus.REJECTED_BY_APPROVER, saved.getStatus());
            assertEquals("approver", saved.getModificationRequests().get(0).getRole());
        }

        @Test
        @DisplayName("non-participant → BadRequestException")
        void nonParticipant_throws() {
            Contract c = inReviewActiveContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowRejectRequest req = new FlowRejectRequest();
            req.setMessage("reason");
            assertThrows(BadRequestException.class,
                    () -> service.reject(CONTRACT_ID, req, "outsider@test.com"));
        }

        @Test
        @DisplayName("pending (locked) participant → BadRequestException")
        void pendingParticipant_throws() {
            Contract c = inReviewActiveContract();
            c.getParticipants().get(0).setStatus("pending");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowRejectRequest req = new FlowRejectRequest();
            req.setMessage("reason");
            assertThrows(BadRequestException.class,
                    () -> service.reject(CONTRACT_ID, req, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("wrong status (DRAFT) → BadRequestException")
        void wrongStatus_throws() {
            Contract c = draftContract();
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "in_progress"))));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowRejectRequest req = new FlowRejectRequest();
            req.setMessage("reason");
            assertThrows(BadRequestException.class,
                    () -> service.reject(CONTRACT_ID, req, REVIEWER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("initiateUpload")
    class InitiateUpload {

        @Test
        @DisplayName("active reviewer in IN_REVIEW gets uploadId")
        void reviewer_inReview_getsUploadId() throws Exception {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked"))));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(customMinioClient.startMultipartUpload(
                    "test-bucket", "contracts/" + CONTRACT_ID + "_signed.pdf"))
                    .thenReturn("upload-id-reviewer");

            SignUploadInitResponse resp = service.initiateUpload(CONTRACT_ID, REVIEWER_EMAIL);

            assertEquals("upload-id-reviewer", resp.getUploadId());
        }

        @Test
        @DisplayName("active approver in IN_APPROVAL gets uploadId")
        void approver_inApproval_getsUploadId() throws Exception {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_APPROVAL);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(APPROVER_EMAIL, ParticipantRole.APPROVER, 1, "unlocked"))));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(customMinioClient.startMultipartUpload(
                    "test-bucket", "contracts/" + CONTRACT_ID + "_signed.pdf"))
                    .thenReturn("upload-id-123");

            SignUploadInitResponse resp = service.initiateUpload(CONTRACT_ID, APPROVER_EMAIL);

            assertEquals("upload-id-123", resp.getUploadId());
        }

        @Test
        @DisplayName("wrong status (READY_FOR_SIGNATURE) → BadRequestException")
        void wrongStatus_throws() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(APPROVER_EMAIL, ParticipantRole.APPROVER, 1, "completed"))));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(BadRequestException.class,
                    () -> service.initiateUpload(CONTRACT_ID, APPROVER_EMAIL));
        }

        @Test
        @DisplayName("contract not found → NotFoundException")
        void notFound_throws() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class,
                    () -> service.initiateUpload(CONTRACT_ID, APPROVER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getPresignedPartUrl")
    class GetPresignedPartUrl {

        private Contract reviewInProgressContract() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked"))));
            return c;
        }

        @Test
        @DisplayName("partNumber 0 → BadRequestException")
        void partNumberZero_throws() {
            Contract c = reviewInProgressContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.getPresignedPartUrl(CONTRACT_ID, "upload-id", 0, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("partNumber 10001 → BadRequestException")
        void partNumberTooHigh_throws() {
            Contract c = reviewInProgressContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.getPresignedPartUrl(CONTRACT_ID, "upload-id", 10001, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("active reviewer in IN_REVIEW can get presigned URL")
        void reviewer_inReview_canPresign() throws Exception {
            Contract c = reviewInProgressContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertDoesNotThrow(
                    () -> service.getPresignedPartUrl(CONTRACT_ID, "upload-id", 1, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("contract not found → NotFoundException")
        void notFound_throws() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class,
                    () -> service.getPresignedPartUrl(CONTRACT_ID, "upload-id", 1, APPROVER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("abortUpload")
    class AbortUpload {

        private Contract approvalContract() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_APPROVAL);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(APPROVER_EMAIL, ParticipantRole.APPROVER, 1, "unlocked"))));
            return c;
        }

        @Test
        @DisplayName("valid uploadId — cancelMultipartUpload called with correct args")
        void validUploadId_cancelsCalled() throws Exception {
            Contract c = approvalContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            service.abortUpload(CONTRACT_ID, "upload-abc", APPROVER_EMAIL);

            verify(customMinioClient).cancelMultipartUpload(
                    "test-bucket",
                    "contracts/" + CONTRACT_ID + "_signed.pdf",
                    "upload-abc");
        }

        @Test
        @DisplayName("blank uploadId → BadRequestException")
        void blankUploadId_throws() {
            Contract c = approvalContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.abortUpload(CONTRACT_ID, "  ", APPROVER_EMAIL));
        }

        @Test
        @DisplayName("null uploadId → BadRequestException")
        void nullUploadId_throws() {
            Contract c = approvalContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.abortUpload(CONTRACT_ID, null, APPROVER_EMAIL));
        }

        @Test
        @DisplayName("active reviewer in IN_REVIEW can abort upload")
        void reviewer_inReview_canAbort() throws Exception {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setParticipants(new ArrayList<>(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked"))));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertDoesNotThrow(
                    () -> service.abortUpload(CONTRACT_ID, "upload-id", REVIEWER_EMAIL));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("sendForSignature")
    class SendForSignature {

        private Contract readyContract() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            c.setFileUploaded(true);
            c.setParticipants(new ArrayList<>());

            Party internalParty = new Party();
            internalParty.setId("p-internal");
            internalParty.setLabel("Our Company");
            internalParty.setType(PartyType.INTERNAL);
            c.setParties(List.of(internalParty));

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldName", "ceoName");
            field.put("label", "CEO Name");
            field.put("assignedParty", "p-internal");
            field.put("value", "Jane CEO");
            c.setFormFields(new ArrayList<>(List.of(field)));
            return c;
        }

        @Test
        @DisplayName("owner sends — signatureService.submitForSignature called")
        void owner_sendsSuccessfully() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(signatureService.submitForSignature(eq(CONTRACT_ID), any(), eq(OWNER)))
                    .thenReturn(new ContractResponse(c));

            ContractResponse result = service.sendForSignature(CONTRACT_ID, externalSignerRequest(), OWNER);

            assertNotNull(result);
            verify(signatureService).submitForSignature(eq(CONTRACT_ID), any(), eq(OWNER));
        }

        @Test
        @DisplayName("non-owner → NotFoundException")
        void nonOwner_throwsNotFound() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(NotFoundException.class,
                    () -> service.sendForSignature(CONTRACT_ID, externalSignerRequest(), "other@test.com"));
        }

        @Test
        @DisplayName("wrong status (IN_REVIEW) → BadRequestException")
        void wrongStatus_throws() {
            Contract c = readyContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.sendForSignature(CONTRACT_ID, externalSignerRequest(), OWNER));
        }

        @Test
        @DisplayName("file not uploaded → BadRequestException")
        void fileNotUploaded_throws() {
            Contract c = readyContract();
            c.setFileUploaded(false);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> service.sendForSignature(CONTRACT_ID, externalSignerRequest(), OWNER));
        }

        @Test
        @DisplayName("org-gate — unfilled INTERNAL party field → BadRequestException with field label")
        void orgGate_unfilledInternalField_throws() {
            Contract c = readyContract();
            c.getFormFields().get(0).put("value", "");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> service.sendForSignature(CONTRACT_ID, externalSignerRequest(), OWNER));
            assertTrue(ex.getMessage().contains("CEO Name"));
        }

        @Test
        @DisplayName("internal signer type in assignments → BadRequestException")
        void internalSigner_rejected() {
            Contract c = readyContract();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            SubmitForSignatureRequest req = new SubmitForSignatureRequest();
            SignerAssignmentDto signer = new SignerAssignmentDto();
            signer.setEmail("internal@company.com");
            signer.setType("internal");
            signer.setPartyId("p-internal");
            signer.setPartyLabel("Our Company");
            signer.setOrder(1);
            req.setAssignments(List.of(signer));

            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> service.sendForSignature(CONTRACT_ID, req, OWNER));
            assertTrue(ex.getMessage().contains("internal@company.com"));
        }

        @Test
        @DisplayName("org-gate — EXTERNAL party empty field does NOT block")
        void orgGate_externalFieldEmpty_passes() {
            Contract c = readyContract();

            Party extParty = new Party();
            extParty.setId("p-ext");
            extParty.setLabel("Client");
            extParty.setType(PartyType.EXTERNAL);

            Map<String, Object> extField = new LinkedHashMap<>();
            extField.put("fieldName", "clientSig");
            extField.put("label", "Client Signature");
            extField.put("assignedParty", "p-ext");
            extField.put("value", "");

            c.setParties(List.of(c.getParties().get(0), extParty));
            c.getFormFields().add(extField);

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(signatureService.submitForSignature(eq(CONTRACT_ID), any(), eq(OWNER)))
                    .thenReturn(new ContractResponse(c));

            assertDoesNotThrow(() -> service.sendForSignature(CONTRACT_ID, externalSignerRequest(), OWNER));
        }

        @Test
        @DisplayName("org-gate — no form fields → passes (nothing to check)")
        void noFormFields_orgGatePasses() {
            Contract c = readyContract();
            c.setFormFields(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(signatureService.submitForSignature(eq(CONTRACT_ID), any(), eq(OWNER)))
                    .thenReturn(new ContractResponse(c));

            assertDoesNotThrow(() -> service.sendForSignature(CONTRACT_ID, externalSignerRequest(), OWNER));
        }

        @Test
        @DisplayName("org-gate — no parties → passes (internalPartyIds empty)")
        void noParties_orgGatePasses() {
            Contract c = readyContract();
            c.setParties(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            when(signatureService.submitForSignature(eq(CONTRACT_ID), any(), eq(OWNER)))
                    .thenReturn(new ContractResponse(c));

            assertDoesNotThrow(() -> service.sendForSignature(CONTRACT_ID, externalSignerRequest(), OWNER));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getFlowStatus")
    class GetFlowStatus {

        @Test
        @DisplayName("owner can view — returns populated FlowStatusResponse")
        void owner_canView() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setCurrentParticipantOrder(1);
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked")));
            c.setExternalSigningIncluded(false);
            c.setFormFields(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowStatusResponse resp = service.getFlowStatus(CONTRACT_ID, OWNER);

            assertEquals(CONTRACT_ID, resp.getContractId());
            assertEquals(ContractStatus.IN_REVIEW, resp.getStatus());
            assertEquals(1, resp.getCurrentParticipantOrder());
            assertEquals(1, resp.getParticipants().size());
            assertFalse(resp.isExternalSigningIncluded());
        }

        @Test
        @DisplayName("participant can view their own status")
        void participant_canView() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked")));
            c.setFormFields(new ArrayList<>());

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertDoesNotThrow(() -> service.getFlowStatus(CONTRACT_ID, REVIEWER_EMAIL));
        }

        @Test
        @DisplayName("outsider (not owner or participant) → NotFoundException")
        void outsider_throwsNotFound() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked")));

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            assertThrows(NotFoundException.class,
                    () -> service.getFlowStatus(CONTRACT_ID, "outsider@test.com"));
        }

        @Test
        @DisplayName("orgFieldsComplete=true when all INTERNAL party fields are filled")
        void orgFieldsComplete_allFilled() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            c.setParticipants(new ArrayList<>());

            Party internal = new Party();
            internal.setId("p1");
            internal.setType(PartyType.INTERNAL);
            c.setParties(List.of(internal));

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldName", "ceoName");
            field.put("label", "CEO Name");
            field.put("assignedParty", "p1");
            field.put("value", "Jane CEO");
            c.setFormFields(List.of(field));

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowStatusResponse resp = service.getFlowStatus(CONTRACT_ID, OWNER);

            assertTrue(resp.isOrgFieldsComplete());
            assertTrue(resp.getUnfilledOrgFields().isEmpty());
        }

        @Test
        @DisplayName("orgFieldsComplete=false when INTERNAL party field is empty, returns field label")
        void orgFieldsComplete_fieldEmpty() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            c.setParticipants(new ArrayList<>());

            Party internal = new Party();
            internal.setId("p1");
            internal.setType(PartyType.INTERNAL);
            c.setParties(List.of(internal));

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldName", "ceoName");
            field.put("label", "CEO Name");
            field.put("assignedParty", "p1");
            field.put("value", "");
            c.setFormFields(List.of(field));

            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            FlowStatusResponse resp = service.getFlowStatus(CONTRACT_ID, OWNER);

            assertFalse(resp.isOrgFieldsComplete());
            assertTrue(resp.getUnfilledOrgFields().contains("CEO Name"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getFlowInbox")
    class GetFlowInbox {

        @Test
        @DisplayName("unlocked participant appears in inbox")
        void unlockedParticipant_inInbox() {
            Contract c = draftContract();
            c.setId("c1");
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            List<ContractListResponse> inbox = service.getFlowInbox(REVIEWER_EMAIL);

            assertEquals(1, inbox.size());
            assertEquals("c1", inbox.get(0).getId());
        }

        @Test
        @DisplayName("in_progress participant appears in inbox")
        void inProgressParticipant_inInbox() {
            Contract c = draftContract();
            c.setId("c2");
            c.setStatus(ContractStatus.IN_REVIEW);
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "in_progress")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            List<ContractListResponse> inbox = service.getFlowInbox(REVIEWER_EMAIL);

            assertEquals(1, inbox.size());
        }

        @Test
        @DisplayName("completed participant NOT in inbox")
        void completedParticipant_notInInbox() {
            Contract c = draftContract();
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "completed")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            assertTrue(service.getFlowInbox(REVIEWER_EMAIL).isEmpty());
        }

        @Test
        @DisplayName("pending participant NOT in inbox")
        void pendingParticipant_notInInbox() {
            Contract c = draftContract();
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "pending")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            assertTrue(service.getFlowInbox(REVIEWER_EMAIL).isEmpty());
        }

        @Test
        @DisplayName("rejected participant NOT in inbox")
        void rejectedParticipant_notInInbox() {
            Contract c = draftContract();
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "rejected")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            assertTrue(service.getFlowInbox(REVIEWER_EMAIL).isEmpty());
        }

        @Test
        @DisplayName("multiple contracts — only contracts with active tasks returned")
        void multipleContracts_onlyActiveReturned() {
            Contract c1 = draftContract();
            c1.setId("c1");
            c1.setStatus(ContractStatus.IN_REVIEW);
            c1.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked")));

            Contract c2 = draftContract();
            c2.setId("c2");
            c2.setStatus(ContractStatus.IN_REVIEW);
            c2.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "completed")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c1, c2));

            List<ContractListResponse> inbox = service.getFlowInbox(REVIEWER_EMAIL);

            assertEquals(1, inbox.size());
            assertEquals("c1", inbox.get(0).getId());
        }

        @Test
        @DisplayName("empty repository result → empty inbox")
        void emptyRepo_emptyInbox() {
            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(Collections.emptyList());

            assertTrue(service.getFlowInbox(REVIEWER_EMAIL).isEmpty());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getFlowSent")
    class GetFlowSent {

        @Test
        @DisplayName("completed participant appears in sent tab")
        void completedParticipant_inSent() {
            Contract c = draftContract();
            c.setId("c1");
            c.setStatus(ContractStatus.IN_APPROVAL);
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "completed")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            List<ContractListResponse> sent = service.getFlowSent(REVIEWER_EMAIL);

            assertEquals(1, sent.size());
            assertEquals("c1", sent.get(0).getId());
        }

        @Test
        @DisplayName("rejected participant appears in sent tab")
        void rejectedParticipant_inSent() {
            Contract c = draftContract();
            c.setId("c2");
            c.setStatus(ContractStatus.REJECTED_BY_REVIEWER);
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "rejected")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            List<ContractListResponse> sent = service.getFlowSent(REVIEWER_EMAIL);

            assertEquals(1, sent.size());
            assertEquals("c2", sent.get(0).getId());
        }

        @Test
        @DisplayName("unlocked participant NOT in sent tab")
        void unlockedParticipant_notInSent() {
            Contract c = draftContract();
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            assertTrue(service.getFlowSent(REVIEWER_EMAIL).isEmpty());
        }

        @Test
        @DisplayName("in_progress participant NOT in sent tab")
        void inProgressParticipant_notInSent() {
            Contract c = draftContract();
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "in_progress")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            assertTrue(service.getFlowSent(REVIEWER_EMAIL).isEmpty());
        }

        @Test
        @DisplayName("pending participant NOT in sent tab")
        void pendingParticipant_notInSent() {
            Contract c = draftContract();
            c.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "pending")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(c));

            assertTrue(service.getFlowSent(REVIEWER_EMAIL).isEmpty());
        }

        @Test
        @DisplayName("multiple contracts — completed and rejected both appear, active excluded")
        void multipleContracts_completedAndRejectedIncluded_activeExcluded() {
            Contract completed = draftContract();
            completed.setId("c-done");
            completed.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "completed")));

            Contract rejected = draftContract();
            rejected.setId("c-rejected");
            rejected.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "rejected")));

            Contract active = draftContract();
            active.setId("c-active");
            active.setParticipants(List.of(
                    participant(REVIEWER_EMAIL, ParticipantRole.REVIEWER, 1, "unlocked")));

            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(List.of(completed, rejected, active));

            List<ContractListResponse> sent = service.getFlowSent(REVIEWER_EMAIL);

            assertEquals(2, sent.size());
            assertTrue(sent.stream().anyMatch(r -> "c-done".equals(r.getId())));
            assertTrue(sent.stream().anyMatch(r -> "c-rejected".equals(r.getId())));
            assertFalse(sent.stream().anyMatch(r -> "c-active".equals(r.getId())));
        }

        @Test
        @DisplayName("empty repository result → empty sent list")
        void emptyRepo_emptySent() {
            when(contractRepository.findByParticipantsEmail(REVIEWER_EMAIL.toLowerCase()))
                    .thenReturn(Collections.emptyList());

            assertTrue(service.getFlowSent(REVIEWER_EMAIL).isEmpty());
        }
    }
}
