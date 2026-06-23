package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Data
public class ContractListResponse {

    private String id;
    private String title;
    private String client;
    private String description;
    private String category;
    private ContractStatus status;
    private LocalDate startDate;
    private LocalDate endDate;
    private long expiresInDays;
    private String templateId;
    private String templateName;
    private boolean hasFormFields;
    private boolean fileUploaded;
    private String teamId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ─── Workflow summary ─────────────────────────────────────────
    private WorkflowMode workflowMode;
    private ReviewStatus reviewStatus;
    private ApprovalStatus approvalStatus;

    // ─── Signature summary ────────────────────────────────────────
    private String signatureFlowStatus;
    private Integer currentSigningOrder;

    // ─── Termination ──────────────────────────────────────────────
    private LocalDateTime terminatedAt;
    private String terminatedBy;

    // ─── Renewal ──────────────────────────────────────────────────
    private String renewalStatus;
    private String renewedContractId;
    private String renewedFromId;
    private String renewalStartDate;
    private String renewalNotes;

    protected ContractListResponse() {}

    public ContractListResponse(Contract c) {
        this.id = c.getId();
        this.title = c.getTitle();
        this.client = c.getClient();
        this.description = c.getDescription();
        this.category = c.getCategory();
        this.status = c.getStatus();
        this.startDate = c.getStartDate();
        this.endDate = c.getEndDate();
        this.expiresInDays = c.getEndDate() != null
                ? ChronoUnit.DAYS.between(LocalDate.now(), c.getEndDate()) : 0;
        this.templateId = c.getTemplateId();
        this.templateName = c.getTemplateName();
        this.hasFormFields = c.isHasFormFields();
        this.fileUploaded = c.isFileUploaded();
        this.teamId = c.getTeamId();
        this.createdBy = c.getCreatedBy();
        this.createdAt = c.getCreatedAt();
        this.updatedAt = c.getUpdatedAt();
        this.workflowMode = c.getWorkflowMode();
        this.reviewStatus = c.getReviewStatus();
        this.approvalStatus = c.getApprovalStatus();
        this.signatureFlowStatus = c.getSignatureFlowStatus();
        this.currentSigningOrder = c.getCurrentSigningOrder();
        this.terminatedAt = c.getTerminatedAt();
        this.terminatedBy = c.getTerminatedBy();
        this.renewalStatus = c.getRenewalStatus();
        this.renewedContractId = c.getRenewedContractId();
        this.renewedFromId = c.getRenewedFromId();
        this.renewalStartDate = c.getRenewalStartDate();
        this.renewalNotes = c.getRenewalNotes();

        // Recompute status at read time for post-finalization contracts.
        // The DB stores SIGNED; the response must reflect the actual date-driven state.
        // All other statuses (DRAFT, IN_REVIEW, IN_SIGNATURE, TERMINATED, etc.) pass through unchanged.
        if (this.status == ContractStatus.SIGNED
                || this.status == ContractStatus.ACTIVE
                || this.status == ContractStatus.EXPIRING
                || this.status == ContractStatus.EXPIRED) {
            if (this.endDate != null) {
                if (this.endDate.isBefore(LocalDate.now())) {
                    this.status = ContractStatus.EXPIRED;
                } else if (this.expiresInDays <= 30) {
                    this.status = ContractStatus.EXPIRING;
                } else if (this.startDate != null && !this.startDate.isAfter(LocalDate.now())) {
                    this.status = ContractStatus.ACTIVE;
                }
                // startDate is in the future → contract is finalized but not yet started → stays SIGNED
            }
        }
    }
}
