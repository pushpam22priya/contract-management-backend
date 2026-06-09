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
    }
}
