package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractWorkflowService")
class ContractWorkflowServiceTest {

    @Mock ContractRepository contractRepository;

    @InjectMocks ContractWorkflowService contractWorkflowService;

    private static final String CONTRACT_ID = "contract-wf-01";
    private static final String OWNER       = "owner@test.com";
    private static final String REVIEWER_1  = "reviewer1@test.com";
    private static final String REVIEWER_2  = "reviewer2@test.com";
    private static final String APPROVER    = "approver@test.com";
    private static final String STRANGER    = "stranger@test.com";

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Contract draftContract() {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setCreatedBy(OWNER);
        c.setStatus(ContractStatus.DRAFT);
        c.setStartDate(LocalDate.now().minusMonths(1));
        c.setEndDate(LocalDate.now().plusMonths(11));
        return c;
    }

    private Contract inReviewContract() {
        Contract c = draftContract();
        c.setStatus(ContractStatus.IN_REVIEW);
        c.setWorkflowMode(WorkflowMode.ONLY_REVIEW);
        c.setReviewStatus(ReviewStatus.PENDING);
        ReviewerInfo r = new ReviewerInfo();
        r.setEmail(REVIEWER_1);
        r.setStatus("pending");
        c.setReviewers(new ArrayList<>(List.of(r)));
        return c;
    }

    private Contract inReviewTwoReviewers() {
        Contract c = inReviewContract();
        ReviewerInfo r2 = new ReviewerInfo();
        r2.setEmail(REVIEWER_2);
        r2.setStatus("pending");
        c.getReviewers().add(r2);
        return c;
    }

    private Contract inApprovalContract() {
        Contract c = draftContract();
        c.setStatus(ContractStatus.IN_APPROVAL);
        c.setWorkflowMode(WorkflowMode.ONLY_APPROVE);
        ApproverInfo a = new ApproverInfo();
        a.setEmail(APPROVER);
        a.setStatus("pending");
        c.setApprover(a);
        c.setApprovalStatus(ApprovalStatus.PENDING);
        return c;
    }

    private Contract rejectedByReviewerContract() {
        Contract c = inReviewContract();
        c.setStatus(ContractStatus.REJECTED_BY_REVIEWER);
        c.setReviewStatus(ReviewStatus.REJECTED);
        c.getReviewers().get(0).setStatus("rejected");
        return c;
    }

    private Contract rejectedByApproverContract() {
        Contract c = inApprovalContract();
        c.setStatus(ContractStatus.REJECTED_BY_APPROVER);
        c.setApprovalStatus(ApprovalStatus.REJECTED);
        c.getApprover().setStatus("rejected");
        return c;
    }

    private SubmitWorkflowRequest onlyReviewRequest() {
        SubmitWorkflowRequest r = new SubmitWorkflowRequest();
        r.setMode(WorkflowMode.ONLY_REVIEW);
        r.setReviewerEmails(List.of(REVIEWER_1));
        return r;
    }

    private SubmitWorkflowRequest onlyApproveRequest() {
        SubmitWorkflowRequest r = new SubmitWorkflowRequest();
        r.setMode(WorkflowMode.ONLY_APPROVE);
        r.setApproverEmail(APPROVER);
        return r;
    }

    private SubmitWorkflowRequest reviewAndApproveRequest() {
        SubmitWorkflowRequest r = new SubmitWorkflowRequest();
        r.setMode(WorkflowMode.REVIEW_AND_APPROVE);
        r.setReviewerEmails(List.of(REVIEWER_1));
        r.setApproverEmail(APPROVER);
        return r;
    }

    private ReviewCompleteRequest completeRequest(String comments) {
        ReviewCompleteRequest r = new ReviewCompleteRequest();
        r.setComments(comments);
        return r;
    }

    private ForwardReviewRequest forwardRequest(String... emails) {
        ForwardReviewRequest r = new ForwardReviewRequest();
        r.setAdditionalReviewerEmails(new ArrayList<>(List.of(emails)));
        r.setMessage("Please review");
        return r;
    }

    private RejectRequest rejectRequest(String msg) {
        RejectRequest r = new RejectRequest();
        r.setMessage(msg);
        return r;
    }

    private ApproveRequest approveRequest(String comments) {
        ApproveRequest r = new ApproveRequest();
        r.setComments(comments);
        return r;
    }

    private Contract captureLastSaved() {
        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @BeforeEach
    void stubSave() {
        lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — submit: fresh DRAFT
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submit — fresh DRAFT")
    class SubmitFreshDraft {

        @Test
        @DisplayName("ONLY_REVIEW: sets contract status to IN_REVIEW")
        void onlyReview_setsStatus_inReview() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, onlyReviewRequest(), OWNER);
            assertEquals(ContractStatus.IN_REVIEW, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("ONLY_REVIEW: creates a reviewer with status 'pending'")
        void onlyReview_createsReviewer_withPendingStatus() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, onlyReviewRequest(), OWNER);
            Contract saved = captureLastSaved();
            assertEquals(1, saved.getReviewers().size());
            assertEquals("pending", saved.getReviewers().get(0).getStatus());
            assertEquals(REVIEWER_1, saved.getReviewers().get(0).getEmail());
        }

        @Test
        @DisplayName("ONLY_REVIEW: sets reviewStatus to PENDING")
        void onlyReview_setsReviewStatus_pending() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, onlyReviewRequest(), OWNER);
            assertEquals(ReviewStatus.PENDING, captureLastSaved().getReviewStatus());
        }

        @Test
        @DisplayName("ONLY_APPROVE: sets contract status to IN_APPROVAL")
        void onlyApprove_setsStatus_inApproval() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, onlyApproveRequest(), OWNER);
            assertEquals(ContractStatus.IN_APPROVAL, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("ONLY_APPROVE: creates an approver with status 'pending'")
        void onlyApprove_createsApprover_withPendingStatus() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, onlyApproveRequest(), OWNER);
            Contract saved = captureLastSaved();
            assertNotNull(saved.getApprover());
            assertEquals(APPROVER, saved.getApprover().getEmail());
            assertEquals("pending", saved.getApprover().getStatus());
        }

        @Test
        @DisplayName("REVIEW_AND_APPROVE: sets status to IN_REVIEW first (review before approval)")
        void reviewAndApprove_setsStatus_inReview_first() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, reviewAndApproveRequest(), OWNER);
            assertEquals(ContractStatus.IN_REVIEW, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("REVIEW_AND_APPROVE: creates both reviewer list and approver")
        void reviewAndApprove_createsBoth_reviewerAndApprover() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, reviewAndApproveRequest(), OWNER);
            Contract saved = captureLastSaved();
            assertFalse(saved.getReviewers().isEmpty());
            assertNotNull(saved.getApprover());
        }

        @Test
        @DisplayName("throws BadRequestException when contract is not in a submittable state")
        void throws_400_whenNotSubmittableStatus() {
            Contract c = draftContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, onlyReviewRequest(), OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when contract does not exist")
        void throws_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, onlyReviewRequest(), OWNER));
        }

        @Test
        @DisplayName("throws NotFoundException when caller is not the contract owner")
        void throws_404_whenCallerIsNotOwner() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            assertThrows(NotFoundException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, onlyReviewRequest(), STRANGER));
        }

        @Test
        @DisplayName("ONLY_REVIEW: throws BadRequestException when no reviewer emails provided")
        void onlyReview_throws_400_whenNoReviewers() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.ONLY_REVIEW);
            req.setReviewerEmails(List.of());
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("ONLY_APPROVE: throws BadRequestException when no approver email provided")
        void onlyApprove_throws_400_whenNoApprover() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.ONLY_APPROVE);
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("throws BadRequestException when duplicate reviewer emails are submitted")
        void throws_400_whenDuplicateReviewerEmails() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.ONLY_REVIEW);
            req.setReviewerEmails(List.of(REVIEWER_1, REVIEWER_1));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("throws BadRequestException when owner assigns themselves as reviewer")
        void throws_400_whenOwnerAssignsHimselfAsReviewer() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.ONLY_REVIEW);
            req.setReviewerEmails(List.of(OWNER));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("throws BadRequestException when owner assigns themselves as approver")
        void throws_400_whenOwnerAssignsHimselfAsApprover() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.ONLY_APPROVE);
            req.setApproverEmail(OWNER);
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("throws BadRequestException when the same email is assigned as both reviewer and approver")
        void throws_400_whenReviewerIsAlsoApprover() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.REVIEW_AND_APPROVE);
            req.setReviewerEmails(List.of(REVIEWER_1));
            req.setApproverEmail(REVIEWER_1);
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("calls contractRepository.save exactly once on successful submit")
        void callsSave_exactlyOnce() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(draftContract()));
            contractWorkflowService.submit(CONTRACT_ID, onlyReviewRequest(), OWNER);
            verify(contractRepository, times(1)).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — submit: resubmit after reviewer rejection
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submit — resubmit after reviewer rejection")
    class ResubmitAfterReviewerRejection {

        @Test
        @DisplayName("resets status to IN_REVIEW with the new reviewer list")
        void resetsStatus_toInReview() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByReviewerContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setReviewerEmails(List.of(REVIEWER_2));
            contractWorkflowService.submit(CONTRACT_ID, req, OWNER);
            assertEquals(ContractStatus.IN_REVIEW, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("replaces the old reviewer list entirely with new reviewers")
        void replacesReviewerList_withNewReviewers() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByReviewerContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setReviewerEmails(List.of(REVIEWER_2));
            contractWorkflowService.submit(CONTRACT_ID, req, OWNER);
            Contract saved = captureLastSaved();
            assertEquals(1, saved.getReviewers().size());
            assertEquals(REVIEWER_2, saved.getReviewers().get(0).getEmail());
        }

        @Test
        @DisplayName("throws BadRequestException when resubmission has no reviewer emails")
        void throws_400_whenNoReviewerEmailsOnResubmit() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByReviewerContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setReviewerEmails(List.of());
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("throws BadRequestException when workflow mode is changed on resubmission")
        void throws_400_whenModeChangedOnResubmit() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByReviewerContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.ONLY_APPROVE);
            req.setReviewerEmails(List.of(REVIEWER_2));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("appends a ModificationRequest for the resubmission audit trail")
        void appendsModificationRequest_onResubmit() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByReviewerContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setReviewerEmails(List.of(REVIEWER_2));
            contractWorkflowService.submit(CONTRACT_ID, req, OWNER);
            Contract saved = captureLastSaved();
            assertNotNull(saved.getModificationRequests());
            assertFalse(saved.getModificationRequests().isEmpty());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — submit: resubmit after approver rejection
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submit — resubmit after approver rejection")
    class ResubmitAfterApproverRejection {

        @Test
        @DisplayName("resets status to IN_APPROVAL")
        void resetsStatus_toInApproval() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByApproverContract()));
            contractWorkflowService.submit(CONTRACT_ID, new SubmitWorkflowRequest(), OWNER);
            assertEquals(ContractStatus.IN_APPROVAL, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("resets approver status back to 'pending'")
        void resetsApprover_toPending() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByApproverContract()));
            contractWorkflowService.submit(CONTRACT_ID, new SubmitWorkflowRequest(), OWNER);
            assertEquals("pending", captureLastSaved().getApprover().getStatus());
        }

        @Test
        @DisplayName("does not touch the reviewer list — review phase was already completed")
        void doesNotChangeReviewers_onApproverResubmit() {
            Contract c = rejectedByApproverContract();
            ReviewerInfo r = new ReviewerInfo();
            r.setEmail(REVIEWER_1);
            r.setStatus("reviewed");
            c.setReviewers(new ArrayList<>(List.of(r)));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            contractWorkflowService.submit(CONTRACT_ID, new SubmitWorkflowRequest(), OWNER);

            Contract saved = captureLastSaved();
            assertEquals(1, saved.getReviewers().size());
            assertEquals("reviewed", saved.getReviewers().get(0).getStatus());
        }

        @Test
        @DisplayName("throws BadRequestException when mode is changed on resubmission")
        void throws_400_whenModeChangedOnResubmit() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByApproverContract()));
            SubmitWorkflowRequest req = new SubmitWorkflowRequest();
            req.setMode(WorkflowMode.ONLY_REVIEW);
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.submit(CONTRACT_ID, req, OWNER));
        }

        @Test
        @DisplayName("appends a ModificationRequest for the resubmission audit trail")
        void appendsModificationRequest_onResubmit() {
            when(contractRepository.findById(CONTRACT_ID))
                    .thenReturn(Optional.of(rejectedByApproverContract()));
            contractWorkflowService.submit(CONTRACT_ID, new SubmitWorkflowRequest(), OWNER);
            Contract saved = captureLastSaved();
            assertFalse(saved.getModificationRequests().isEmpty());
            assertEquals("contractor", saved.getModificationRequests().get(0).getRole());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 4 — reviewComplete
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reviewComplete")
    class ReviewComplete {

        @Test
        @DisplayName("sets the reviewer's status to 'reviewed'")
        void setsReviewer_statusReviewed() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest("LGTM"), REVIEWER_1);
            assertEquals("reviewed", captureLastSaved().getReviewers().get(0).getStatus());
        }

        @Test
        @DisplayName("saves the reviewer's comments from the request")
        void savesReviewer_comments() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest("Looks good"), REVIEWER_1);
            assertEquals("Looks good", captureLastSaved().getReviewers().get(0).getComments());
        }

        @Test
        @DisplayName("sets a non-null reviewedAt timestamp on the reviewer")
        void setsReviewer_reviewedAt_notNull() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1);
            assertNotNull(captureLastSaved().getReviewers().get(0).getReviewedAt());
        }

        @Test
        @DisplayName("ONLY_REVIEW: advances to READY_FOR_SIGNATURE when all reviewers are done")
        void onlyReview_advancesTo_readyForSignature_whenAllDone() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1);
            assertEquals(ContractStatus.READY_FOR_SIGNATURE, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("REVIEW_AND_APPROVE: advances to IN_APPROVAL when all reviewers are done")
        void reviewAndApprove_advancesTo_inApproval_whenAllDone() {
            Contract c = inReviewContract();
            c.setWorkflowMode(WorkflowMode.REVIEW_AND_APPROVE);
            ApproverInfo a = new ApproverInfo();
            a.setEmail(APPROVER);
            a.setStatus("pending");
            c.setApprover(a);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1);

            assertEquals(ContractStatus.IN_APPROVAL, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("stays IN_REVIEW when other reviewers are still pending")
        void staysInReview_whenOtherReviewersPending() {
            Contract c = inReviewTwoReviewers();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1);

            // REVIEWER_2 is still pending — status should not be READY_FOR_SIGNATURE
            assertNotEquals(ContractStatus.READY_FOR_SIGNATURE, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("sets reviewStatus to IN_PROGRESS when other reviewers are still pending")
        void setsReviewStatus_inProgress_whenOthersPending() {
            Contract c = inReviewTwoReviewers();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1);

            assertEquals(ReviewStatus.IN_PROGRESS, captureLastSaved().getReviewStatus());
        }

        @Test
        @DisplayName("throws BadRequestException when contract is not IN_REVIEW")
        void throws_400_whenContractNotInReview() {
            Contract c = inReviewContract();
            c.setStatus(ContractStatus.DRAFT);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1));
        }

        @Test
        @DisplayName("throws BadRequestException when caller is not an assigned reviewer")
        void throws_400_whenCallerNotReviewer() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), STRANGER));
        }

        @Test
        @DisplayName("throws BadRequestException when the reviewer has already acted on the contract")
        void throws_400_whenReviewerAlreadyActed() {
            Contract c = inReviewContract();
            c.getReviewers().get(0).setStatus("reviewed");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1));
        }

        @Test
        @DisplayName("throws NotFoundException when contract does not exist")
        void throws_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class,
                    () -> contractWorkflowService.reviewComplete(CONTRACT_ID, completeRequest(null), REVIEWER_1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 5 — reviewForward
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reviewForward")
    class ReviewForward {

        @Test
        @DisplayName("sets current reviewer status to 'forwarded'")
        void setsCurrentReviewer_statusForwarded() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), REVIEWER_1);
            Contract saved = captureLastSaved();
            ReviewerInfo r1 = saved.getReviewers().stream()
                    .filter(r -> r.getEmail().equals(REVIEWER_1)).findFirst().orElseThrow();
            assertEquals("forwarded", r1.getStatus());
        }

        @Test
        @DisplayName("adds the new reviewer to the contract's reviewer list")
        void addsNewReviewer_toList() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), REVIEWER_1);
            Contract saved = captureLastSaved();
            assertTrue(saved.getReviewers().stream().anyMatch(r -> r.getEmail().equals(REVIEWER_2)));
        }

        @Test
        @DisplayName("new forwarded reviewer has status 'pending'")
        void newReviewer_hasPendingStatus() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), REVIEWER_1);
            Contract saved = captureLastSaved();
            ReviewerInfo newR = saved.getReviewers().stream()
                    .filter(r -> r.getEmail().equals(REVIEWER_2)).findFirst().orElseThrow();
            assertEquals("pending", newR.getStatus());
        }

        @Test
        @DisplayName("new reviewer has sentBy set to the forwarding reviewer's email")
        void newReviewer_hasSentBy_asForwarder() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), REVIEWER_1);
            Contract saved = captureLastSaved();
            ReviewerInfo newR = saved.getReviewers().stream()
                    .filter(r -> r.getEmail().equals(REVIEWER_2)).findFirst().orElseThrow();
            assertEquals(REVIEWER_1, newR.getSentBy());
        }

        @Test
        @DisplayName("sets reviewStatus to IN_PROGRESS after forward")
        void setsReviewStatus_inProgress() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), REVIEWER_1);
            assertEquals(ReviewStatus.IN_PROGRESS, captureLastSaved().getReviewStatus());
        }

        @Test
        @DisplayName("throws BadRequestException when forwarding to self")
        void throws_400_whenForwardingToSelf() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_1), REVIEWER_1));
        }

        @Test
        @DisplayName("throws BadRequestException when forwarding to the contract creator")
        void throws_400_whenForwardingToContractCreator() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(OWNER), REVIEWER_1));
        }

        @Test
        @DisplayName("throws BadRequestException when forwarding to an already-assigned reviewer")
        void throws_400_whenForwardingToExistingReviewer() {
            Contract c = inReviewTwoReviewers();
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            // REVIEWER_2 is already on the contract
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), REVIEWER_1));
        }

        @Test
        @DisplayName("throws BadRequestException when forwarding to the assigned approver")
        void throws_400_whenForwardingToApprover() {
            Contract c = inReviewContract();
            c.setWorkflowMode(WorkflowMode.REVIEW_AND_APPROVE);
            ApproverInfo a = new ApproverInfo();
            a.setEmail(APPROVER);
            c.setApprover(a);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(APPROVER), REVIEWER_1));
        }

        @Test
        @DisplayName("throws BadRequestException when contract is not IN_REVIEW")
        void throws_400_whenContractNotInReview() {
            Contract c = inReviewContract();
            c.setStatus(ContractStatus.IN_APPROVAL);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), REVIEWER_1));
        }

        @Test
        @DisplayName("throws BadRequestException when caller is not an assigned reviewer")
        void throws_400_whenCallerNotReviewer() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewForward(CONTRACT_ID, forwardRequest(REVIEWER_2), STRANGER));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 6 — reviewReject
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reviewReject")
    class ReviewReject {

        @Test
        @DisplayName("sets reviewer status to 'rejected'")
        void setsReviewer_statusRejected() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Not acceptable"), REVIEWER_1);
            assertEquals("rejected", captureLastSaved().getReviewers().get(0).getStatus());
        }

        @Test
        @DisplayName("sets contract status to REJECTED_BY_REVIEWER")
        void setsContractStatus_rejectedByReviewer() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Needs changes"), REVIEWER_1);
            assertEquals(ContractStatus.REJECTED_BY_REVIEWER, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("sets reviewStatus to REJECTED")
        void setsReviewStatus_rejected() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Needs changes"), REVIEWER_1);
            assertEquals(ReviewStatus.REJECTED, captureLastSaved().getReviewStatus());
        }

        @Test
        @DisplayName("stores the rejection message as reviewer's comments")
        void storesRejectionMessage_asComments() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Section 3 issue"), REVIEWER_1);
            assertEquals("Section 3 issue", captureLastSaved().getReviewers().get(0).getComments());
        }

        @Test
        @DisplayName("sets a non-null rejectedAt timestamp on the reviewer")
        void setsRejectedAt_notNull() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Issue"), REVIEWER_1);
            assertNotNull(captureLastSaved().getReviewers().get(0).getRejectedAt());
        }

        @Test
        @DisplayName("appends a ModificationRequest for the rejection audit trail")
        void appendsModificationRequest_forRejection() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Issue"), REVIEWER_1);
            Contract saved = captureLastSaved();
            assertNotNull(saved.getModificationRequests());
            assertFalse(saved.getModificationRequests().isEmpty());
            assertEquals("reviewer", saved.getModificationRequests().get(0).getRole());
        }

        @Test
        @DisplayName("throws BadRequestException when contract is not IN_REVIEW")
        void throws_400_whenContractNotInReview() {
            Contract c = inReviewContract();
            c.setStatus(ContractStatus.DRAFT);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Issue"), REVIEWER_1));
        }

        @Test
        @DisplayName("throws BadRequestException when caller is not an assigned reviewer")
        void throws_400_whenCallerNotReviewer() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inReviewContract()));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Issue"), STRANGER));
        }

        @Test
        @DisplayName("throws BadRequestException when reviewer has already acted")
        void throws_400_whenReviewerAlreadyActed() {
            Contract c = inReviewContract();
            c.getReviewers().get(0).setStatus("reviewed");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.reviewReject(CONTRACT_ID, rejectRequest("Issue"), REVIEWER_1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 7 — approvalApprove
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("approvalApprove")
    class ApprovalApprove {

        @Test
        @DisplayName("sets approver status to 'approved'")
        void setsApprover_statusApproved() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest("All good"), APPROVER);
            assertEquals("approved", captureLastSaved().getApprover().getStatus());
        }

        @Test
        @DisplayName("sets contract status to READY_FOR_SIGNATURE")
        void setsContractStatus_readyForSignature() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER);
            assertEquals(ContractStatus.READY_FOR_SIGNATURE, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("sets approvalStatus to APPROVED")
        void setsApprovalStatus_approved() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER);
            assertEquals(ApprovalStatus.APPROVED, captureLastSaved().getApprovalStatus());
        }

        @Test
        @DisplayName("saves the approver's comments from the request")
        void savesApprover_comments() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest("Approved!"), APPROVER);
            assertEquals("Approved!", captureLastSaved().getApprover().getComments());
        }

        @Test
        @DisplayName("sets a non-null approvedAt timestamp")
        void setsApprovedAt_notNull() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER);
            assertNotNull(captureLastSaved().getApprover().getApprovedAt());
        }

        @Test
        @DisplayName("throws BadRequestException when contract is not IN_APPROVAL")
        void throws_400_whenContractNotInApproval() {
            Contract c = inApprovalContract();
            c.setStatus(ContractStatus.IN_REVIEW);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER));
        }

        @Test
        @DisplayName("throws BadRequestException when caller is not the assigned approver")
        void throws_400_whenCallerNotApprover() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), STRANGER));
        }

        @Test
        @DisplayName("throws BadRequestException when no approver is assigned")
        void throws_400_whenNoApproverAssigned() {
            Contract c = inApprovalContract();
            c.setApprover(null);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER));
        }

        @Test
        @DisplayName("throws BadRequestException when approver has already acted")
        void throws_400_whenApproverAlreadyActed() {
            Contract c = inApprovalContract();
            c.getApprover().setStatus("approved");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER));
        }

        @Test
        @DisplayName("throws NotFoundException when contract does not exist")
        void throws_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class,
                    () -> contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER));
        }

        @Test
        @DisplayName("calls contractRepository.save exactly once on successful approval")
        void callsSave_exactlyOnce() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalApprove(CONTRACT_ID, approveRequest(null), APPROVER);
            verify(contractRepository, times(1)).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 8 — approvalReject
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("approvalReject")
    class ApprovalReject {

        @Test
        @DisplayName("sets approver status to 'rejected'")
        void setsApprover_statusRejected() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Not satisfied"), APPROVER);
            assertEquals("rejected", captureLastSaved().getApprover().getStatus());
        }

        @Test
        @DisplayName("sets contract status to REJECTED_BY_APPROVER")
        void setsContractStatus_rejectedByApprover() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issues found"), APPROVER);
            assertEquals(ContractStatus.REJECTED_BY_APPROVER, captureLastSaved().getStatus());
        }

        @Test
        @DisplayName("sets approvalStatus to REJECTED")
        void setsApprovalStatus_rejected() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issues"), APPROVER);
            assertEquals(ApprovalStatus.REJECTED, captureLastSaved().getApprovalStatus());
        }

        @Test
        @DisplayName("stores the rejection message as approver's comments")
        void storesRejectionMessage_asComments() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Clause 4 issue"), APPROVER);
            assertEquals("Clause 4 issue", captureLastSaved().getApprover().getComments());
        }

        @Test
        @DisplayName("sets a non-null rejectedAt timestamp")
        void setsRejectedAt_notNull() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issues"), APPROVER);
            assertNotNull(captureLastSaved().getApprover().getRejectedAt());
        }

        @Test
        @DisplayName("appends a ModificationRequest with role 'approver'")
        void appendsModificationRequest_withApproverRole() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issues"), APPROVER);
            Contract saved = captureLastSaved();
            assertFalse(saved.getModificationRequests().isEmpty());
            assertEquals("approver", saved.getModificationRequests().get(0).getRole());
        }

        @Test
        @DisplayName("throws BadRequestException when contract is not IN_APPROVAL")
        void throws_400_whenContractNotInApproval() {
            Contract c = inApprovalContract();
            c.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issue"), APPROVER));
        }

        @Test
        @DisplayName("throws BadRequestException when caller is not the assigned approver")
        void throws_400_whenCallerNotApprover() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(inApprovalContract()));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issue"), STRANGER));
        }

        @Test
        @DisplayName("throws BadRequestException when approver has already acted")
        void throws_400_whenApproverAlreadyActed() {
            Contract c = inApprovalContract();
            c.getApprover().setStatus("rejected");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));
            assertThrows(BadRequestException.class,
                    () -> contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issue"), APPROVER));
        }

        @Test
        @DisplayName("throws NotFoundException when contract does not exist")
        void throws_404_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class,
                    () -> contractWorkflowService.approvalReject(CONTRACT_ID, rejectRequest("Issue"), APPROVER));
        }
    }
}
