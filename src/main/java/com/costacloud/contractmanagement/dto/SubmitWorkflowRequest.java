package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.WorkflowMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SubmitWorkflowRequest {

    @NotNull(message = "Workflow mode is required")
    private WorkflowMode mode;

    private List<String> reviewerEmails;    // who to assign as reviewers
    private String approverEmail;           // who to assign as approver
    private String reviewerMessage;         // optional message to reviewers
    private String approverMessage;         // optional message to approver
}
