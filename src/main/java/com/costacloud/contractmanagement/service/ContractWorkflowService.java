package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ContractWorkflowService {

    private final ContractRepository contractRepository;

    public ContractWorkflowService(ContractRepository contractRepository) {
        this.contractRepository = contractRepository;
    }

    // ─── Submit / Resubmit ────────────────────────────────────────

    public ContractResponse submit(String id, SubmitWorkflowRequest request, String callerEmail) {
        Contract contract = findByIdAndOwner(id, callerEmail);
        ContractStatus status = contract.getStatus();

        if (status != ContractStatus.DRAFT
                && status != ContractStatus.REJECTED_BY_REVIEWER
                && status != ContractStatus.REJECTED_BY_APPROVER) {
            throw new BadRequestException(
                    "Contract is not in a submittable state. Current status: " + status);
        }

        if (status == ContractStatus.DRAFT) {
            handleFreshSubmit(contract, request, callerEmail);
        } else if (status == ContractStatus.REJECTED_BY_REVIEWER) {
            handleResubmitAfterReviewerRejection(contract, request, callerEmail);
        } else {
            // REJECTED_BY_APPROVER
            handleResubmitAfterApproverRejection(contract, callerEmail);
        }

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    private void handleFreshSubmit(Contract contract, SubmitWorkflowRequest request, String callerEmail) {
        WorkflowMode mode = request.getMode();
        List<String> reviewerEmails = request.getReviewerEmails();
        String approverEmail = request.getApproverEmail();

        validateModeRequirements(mode, reviewerEmails, approverEmail);
        validateNoDuplicateReviewers(reviewerEmails);
        validateNoOverlap(reviewerEmails, approverEmail, callerEmail);

        contract.setWorkflowMode(mode);

        if (mode == WorkflowMode.ONLY_REVIEW || mode == WorkflowMode.REVIEW_AND_APPROVE) {
            contract.setReviewers(buildReviewerList(reviewerEmails, request.getReviewerMessage(), callerEmail));
            contract.setReviewStatus(ReviewStatus.PENDING);
            contract.setStatus(ContractStatus.IN_REVIEW);
        }

        if (mode == WorkflowMode.ONLY_APPROVE || mode == WorkflowMode.REVIEW_AND_APPROVE) {
            contract.setApprover(buildApprover(approverEmail, request.getApproverMessage(), callerEmail));
            contract.setApprovalStatus(ApprovalStatus.PENDING);
        }

        if (mode == WorkflowMode.ONLY_APPROVE) {
            contract.setStatus(ContractStatus.IN_APPROVAL);
        }
    }

    private void handleResubmitAfterReviewerRejection(Contract contract, SubmitWorkflowRequest request, String callerEmail) {
        List<String> reviewerEmails = request.getReviewerEmails();

        if (reviewerEmails == null || reviewerEmails.isEmpty()) {
            throw new BadRequestException("At least one reviewer email is required for resubmission");
        }

        validateNoDuplicateReviewers(reviewerEmails);

        String existingApproverEmail = contract.getApprover() != null
                ? contract.getApprover().getEmail() : null;

        for (String email : reviewerEmails) {
            if (email == null || email.isBlank()) {
                throw new BadRequestException("Reviewer email cannot be blank");
            }
            if (email.equalsIgnoreCase(callerEmail)) {
                throw new BadRequestException("You cannot assign yourself as a reviewer");
            }
            if (existingApproverEmail != null && email.equalsIgnoreCase(existingApproverEmail)) {
                throw new BadRequestException("Reviewer cannot also be the approver: " + email);
            }
        }

        // Replace reviewer list entirely with new assignments
        contract.setReviewers(buildReviewerList(reviewerEmails, request.getReviewerMessage(), callerEmail));
        contract.setReviewStatus(ReviewStatus.PENDING);
        contract.setStatus(ContractStatus.IN_REVIEW);

        appendModificationRequest(contract, callerEmail, "contractor", "Resubmitted after reviewer rejection");
    }

    private void handleResubmitAfterApproverRejection(Contract contract, String callerEmail) {
        ApproverInfo approver = contract.getApprover();
        if (approver == null) {
            throw new BadRequestException("No approver found on this contract");
        }
        // Reset approver only — reviewers are not touched (review already completed)
        approver.setStatus("pending");
        approver.setApprovedAt(null);
        approver.setRejectedAt(null);
        approver.setComments(null);
        contract.setApprover(approver);
        contract.setApprovalStatus(ApprovalStatus.PENDING);
        contract.setStatus(ContractStatus.IN_APPROVAL);

        appendModificationRequest(contract, callerEmail, "contractor", "Resubmitted after approver rejection");
    }

    // ─── Review: Complete ─────────────────────────────────────────

    public ContractResponse reviewComplete(String id, ReviewCompleteRequest request, String callerEmail) {
        Contract contract = findById(id);

        if (contract.getStatus() != ContractStatus.IN_REVIEW) {
            throw new BadRequestException("Contract is not currently under review");
        }

        ReviewerInfo reviewer = findPendingReviewer(contract, callerEmail);
        reviewer.setStatus("reviewed");
        reviewer.setReviewedAt(LocalDateTime.now());
        reviewer.setComments(request.getComments());

        advanceReviewIfComplete(contract);

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── Review: Forward ──────────────────────────────────────────

    public ContractResponse reviewForward(String id, ForwardReviewRequest request, String callerEmail) {
        Contract contract = findById(id);

        if (contract.getStatus() != ContractStatus.IN_REVIEW) {
            throw new BadRequestException("Contract is not currently under review");
        }

        ReviewerInfo reviewer = findPendingReviewer(contract, callerEmail);

        List<String> newEmails = request.getAdditionalReviewerEmails();
        String approverEmail = contract.getApprover() != null ? contract.getApprover().getEmail() : null;

        for (String email : newEmails) {
            if (email == null || email.isBlank()) {
                throw new BadRequestException("Reviewer email cannot be blank");
            }
            if (email.equalsIgnoreCase(callerEmail)) {
                throw new BadRequestException("You cannot forward to yourself");
            }
            if (email.equalsIgnoreCase(contract.getCreatedBy())) {
                throw new BadRequestException("Cannot forward to the contract creator: " + email);
            }
            if (isAlreadyReviewer(contract, email)) {
                throw new BadRequestException(email + " is already an assigned reviewer");
            }
            if (approverEmail != null && email.equalsIgnoreCase(approverEmail)) {
                throw new BadRequestException("Reviewer cannot also be the approver: " + email);
            }
        }

        reviewer.setStatus("forwarded");
        reviewer.setReviewedAt(LocalDateTime.now());

        for (String email : newEmails) {
            ReviewerInfo newReviewer = new ReviewerInfo();
            newReviewer.setEmail(email);
            newReviewer.setStatus("pending");
            newReviewer.setSentAt(LocalDateTime.now());
            newReviewer.setSentBy(callerEmail);
            newReviewer.setSubmissionMessage(request.getMessage());
            contract.getReviewers().add(newReviewer);
        }

        contract.setReviewStatus(ReviewStatus.IN_PROGRESS);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── Review: Reject ───────────────────────────────────────────

    public ContractResponse reviewReject(String id, RejectRequest request, String callerEmail) {
        Contract contract = findById(id);

        if (contract.getStatus() != ContractStatus.IN_REVIEW) {
            throw new BadRequestException("Contract is not currently under review");
        }

        ReviewerInfo reviewer = findPendingReviewer(contract, callerEmail);
        reviewer.setStatus("rejected");
        reviewer.setRejectedAt(LocalDateTime.now());
        reviewer.setComments(request.getMessage());

        contract.setStatus(ContractStatus.REJECTED_BY_REVIEWER);
        contract.setReviewStatus(ReviewStatus.REJECTED);
        appendModificationRequest(contract, callerEmail, "reviewer", request.getMessage());

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── Approval: Approve ────────────────────────────────────────

    public ContractResponse approvalApprove(String id, ApproveRequest request, String callerEmail) {
        Contract contract = findById(id);

        if (contract.getStatus() != ContractStatus.IN_APPROVAL) {
            throw new BadRequestException("Contract is not currently under approval");
        }

        ApproverInfo approver = contract.getApprover();
        validatePendingApprover(approver, callerEmail);

        approver.setStatus("approved");
        approver.setApprovedAt(LocalDateTime.now());
        approver.setComments(request.getComments());

        contract.setStatus(ContractStatus.READY_FOR_SIGNATURE);
        contract.setApprovalStatus(ApprovalStatus.APPROVED);

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── Approval: Reject ─────────────────────────────────────────

    public ContractResponse approvalReject(String id, RejectRequest request, String callerEmail) {
        Contract contract = findById(id);

        if (contract.getStatus() != ContractStatus.IN_APPROVAL) {
            throw new BadRequestException("Contract is not currently under approval");
        }

        ApproverInfo approver = contract.getApprover();
        validatePendingApprover(approver, callerEmail);

        approver.setStatus("rejected");
        approver.setRejectedAt(LocalDateTime.now());
        approver.setComments(request.getMessage());

        contract.setStatus(ContractStatus.REJECTED_BY_APPROVER);
        contract.setApprovalStatus(ApprovalStatus.REJECTED);
        appendModificationRequest(contract, callerEmail, "approver", request.getMessage());

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── Private Helpers ──────────────────────────────────────────

    private Contract findById(String id) {
        return contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
    }

    private Contract findByIdAndOwner(String id, String email) {
        Contract contract = findById(id);
        if (!contract.getCreatedBy().equals(email)) {
            throw new NotFoundException("Contract not found");
        }
        return contract;
    }

    private ReviewerInfo findPendingReviewer(Contract contract, String callerEmail) {
        if (contract.getReviewers() == null || contract.getReviewers().isEmpty()) {
            throw new BadRequestException("No reviewers are assigned to this contract");
        }
        boolean isAssigned = contract.getReviewers().stream()
                .anyMatch(r -> r.getEmail().equalsIgnoreCase(callerEmail));
        if (!isAssigned) {
            throw new BadRequestException("You are not an assigned reviewer for this contract");
        }
        return contract.getReviewers().stream()
                .filter(r -> r.getEmail().equalsIgnoreCase(callerEmail) && "pending".equals(r.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("You have already acted on this contract"));
    }

    private void validatePendingApprover(ApproverInfo approver, String callerEmail) {
        if (approver == null) {
            throw new BadRequestException("No approver is assigned to this contract");
        }
        if (!approver.getEmail().equalsIgnoreCase(callerEmail)) {
            throw new BadRequestException("You are not the assigned approver for this contract");
        }
        if (!"pending".equals(approver.getStatus())) {
            throw new BadRequestException("You have already acted on this contract");
        }
    }

    private void validateModeRequirements(WorkflowMode mode, List<String> reviewerEmails, String approverEmail) {
        boolean hasReviewers = reviewerEmails != null && !reviewerEmails.isEmpty();
        boolean hasApprover = approverEmail != null && !approverEmail.isBlank();

        switch (mode) {
            case ONLY_REVIEW -> {
                if (!hasReviewers) throw new BadRequestException("At least one reviewer is required for ONLY_REVIEW mode");
                if (hasApprover)  throw new BadRequestException("Approver must not be set for ONLY_REVIEW mode");
            }
            case ONLY_APPROVE -> {
                if (!hasApprover)  throw new BadRequestException("Approver is required for ONLY_APPROVE mode");
                if (hasReviewers)  throw new BadRequestException("Reviewers must not be set for ONLY_APPROVE mode");
            }
            case REVIEW_AND_APPROVE -> {
                if (!hasReviewers) throw new BadRequestException("At least one reviewer is required for REVIEW_AND_APPROVE mode");
                if (!hasApprover)  throw new BadRequestException("Approver is required for REVIEW_AND_APPROVE mode");
            }
        }
    }

    private void validateNoDuplicateReviewers(List<String> reviewerEmails) {
        if (reviewerEmails == null) return;
        long distinct = reviewerEmails.stream().map(String::toLowerCase).distinct().count();
        if (distinct < reviewerEmails.size()) {
            throw new BadRequestException("Duplicate reviewer emails are not allowed");
        }
    }

    private void validateNoOverlap(List<String> reviewerEmails, String approverEmail, String callerEmail) {
        if (reviewerEmails != null) {
            for (String email : reviewerEmails) {
                if (email == null || email.isBlank()) {
                    throw new BadRequestException("Reviewer email cannot be blank");
                }
                if (email.equalsIgnoreCase(callerEmail)) {
                    throw new BadRequestException("You cannot assign yourself as a reviewer");
                }
                if (approverEmail != null && email.equalsIgnoreCase(approverEmail)) {
                    throw new BadRequestException("Approver cannot also be a reviewer: " + email);
                }
            }
        }
        if (approverEmail != null && approverEmail.equalsIgnoreCase(callerEmail)) {
            throw new BadRequestException("You cannot assign yourself as the approver");
        }
    }

    private List<ReviewerInfo> buildReviewerList(List<String> emails, String message, String sentBy) {
        List<ReviewerInfo> list = new ArrayList<>();
        for (String email : emails) {
            ReviewerInfo r = new ReviewerInfo();
            r.setEmail(email);
            r.setStatus("pending");
            r.setSentAt(LocalDateTime.now());
            r.setSentBy(sentBy);
            r.setSubmissionMessage(message);
            list.add(r);
        }
        return list;
    }

    private ApproverInfo buildApprover(String email, String message, String sentBy) {
        ApproverInfo a = new ApproverInfo();
        a.setEmail(email);
        a.setStatus("pending");
        a.setSentAt(LocalDateTime.now());
        a.setSentBy(sentBy);
        a.setSubmissionMessage(message);
        return a;
    }

    private void advanceReviewIfComplete(Contract contract) {
        boolean allDone = contract.getReviewers().stream()
                .allMatch(r -> !"pending".equals(r.getStatus()));

        if (allDone) {
            contract.setReviewStatus(ReviewStatus.COMPLETED);
            if (contract.getWorkflowMode() == WorkflowMode.ONLY_REVIEW) {
                contract.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            } else {
                // REVIEW_AND_APPROVE — hand off to approver
                contract.setStatus(ContractStatus.IN_APPROVAL);
                contract.setApprovalStatus(ApprovalStatus.PENDING);
            }
        } else {
            contract.setReviewStatus(ReviewStatus.IN_PROGRESS);
        }
    }

    private boolean isAlreadyReviewer(Contract contract, String email) {
        return contract.getReviewers() != null && contract.getReviewers().stream()
                .anyMatch(r -> r.getEmail().equalsIgnoreCase(email));
    }

    private void appendModificationRequest(Contract contract, String requestedBy, String role, String message) {
        if (contract.getModificationRequests() == null) {
            contract.setModificationRequests(new ArrayList<>());
        }
        ModificationRequest entry = new ModificationRequest();
        entry.setRequestedBy(requestedBy);
        entry.setRole(role);
        entry.setMessage(message);
        entry.setRequestedAt(LocalDateTime.now());
        contract.getModificationRequests().add(entry);
    }
}
