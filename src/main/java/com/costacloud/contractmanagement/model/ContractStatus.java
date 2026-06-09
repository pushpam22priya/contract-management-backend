package com.costacloud.contractmanagement.model;

public enum ContractStatus {
    DRAFT,
    IN_REVIEW,
    IN_APPROVAL,
    READY_FOR_SIGNATURE,
    IN_SIGNATURE,
    ACTIVE,
    EXPIRING,
    EXPIRED,
    TERMINATED,
    REJECTED_BY_REVIEWER,
    REJECTED_BY_APPROVER
}
