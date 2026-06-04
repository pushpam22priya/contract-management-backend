package com.costacloud.contractmanagement.model;

public enum ContractStatus {
    DRAFT,
    IN_REVIEW,
    IN_APPROVAL,
    APPROVED,
    READY_FOR_SIGNATURE,
    WAITING_FOR_SIGNATURE,
    SIGNED_BY_EVERYONE,
    SIGNED,
    ACTIVE,
    EXPIRING,
    EXPIRED,
    TERMINATED,
    REJECTED,
    REJECTED_BY_REVIEWER,
    REJECTED_BY_APPROVER
}
