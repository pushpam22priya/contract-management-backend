package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ContractResponse extends ContractListResponse {

    private String templateFileName;
    private String xfdfData;
    private Map<String, String> fieldValues;
    private List<Map<String, Object>> formFields;
    private List<Party> parties;

    // True once a working copy (contracts/{id}_signed.pdf) exists — the frontend uses this to
    // always read AND write the working copy instead of the original .pdf. Cleared on rejection.
    private boolean hasSignedCopy;

    // ─── Review/Approval detail ───────────────────────────────────
    private List<ReviewerInfo> reviewers;
    private ApproverInfo approver;
    private List<ModificationRequest> modificationRequests;

    // ─── Signature detail ─────────────────────────────────────────
    private List<ExternalSigner> externalSigners;
    private List<InternalSigner> internalSigners;
    private List<PartyCompletion> partyCompletions;

    public ContractResponse(Contract c) {
        super(c);
        this.templateFileName = c.getTemplateFileName();
        this.xfdfData = c.getXfdfData();
        this.fieldValues = c.getFieldValues();
        this.formFields = c.getFormFields();
        this.parties = c.getParties();
        this.hasSignedCopy = c.getSignedPdfKey() != null;
        this.reviewers = c.getReviewers();
        this.approver = c.getApprover();
        this.modificationRequests = c.getModificationRequests();
        this.externalSigners = c.getExternalSigners();
        this.internalSigners = c.getInternalSigners();
        this.partyCompletions = c.getPartyCompletions();
    }
}
