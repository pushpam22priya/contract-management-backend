package com.costacloud.contractmanagement.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Document(collection = "contracts")
@CompoundIndexes({
        @CompoundIndex(name = "idx_createdBy_teamId_status", def = "{'createdBy': 1, 'teamId': 1, 'status': 1}"),
        @CompoundIndex(name = "idx_title_createdBy",         def = "{'title': 1, 'createdBy': 1}"),
        @CompoundIndex(name = "idx_fileUploaded_uploadAt",   def = "{'fileUploaded': 1, 'uploadInitiatedAt': 1}")
})
@Data
public class Contract {

    @Id
    private String id;

    private String title;
    private String client;
    private String description;
    private String category;
    private ContractStatus status;

    private LocalDate startDate;
    private LocalDate endDate;

    private String templateId;
    private String templateName;
    private String templateFileName;

    private String xfdfData;
    private Map<String, String> fieldValues;
    private List<Map<String, Object>> formFields;
    private boolean hasFormFields;
    private List<Party> parties;

    private boolean fileUploaded;
    private String uploadId;
    private LocalDateTime uploadInitiatedAt;

    private String teamId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ─── Review / Approval Workflow ───────────────────────────────
    private WorkflowMode workflowMode;
    private List<ReviewerInfo> reviewers;
    private ReviewStatus reviewStatus;
    private ApproverInfo approver;
    private ApprovalStatus approvalStatus;
    private List<ModificationRequest> modificationRequests;

    // ─── Signature Workflow ───────────────────────────────────────
    private List<ExternalSigner> externalSigners;
    private List<InternalSigner> internalSigners;
    private List<PartyCompletion> partyCompletions;

    // "pending_signatures" | "all_completed" | "finalized"
    private String signatureFlowStatus;

    // Which order is currently active (null when not in signature flow or all done)
    private Integer currentSigningOrder;

    // Incremented after each signer completes — used for optimistic locking
    private int version;

    // Incremented each time a new signing round starts (re-share after all-completed)
    private int signingRound = 1;

    // Display name of the contractor who initiated the signature flow (for emails)
    private String signatureSenderName;

    // MinIO object keys
    private String signedPdfKey;    // contracts/{id}_signed.pdf — updated after each signer
    private String finalPdfKey;     // contracts/{id}_final.pdf  — set on finalization
}
