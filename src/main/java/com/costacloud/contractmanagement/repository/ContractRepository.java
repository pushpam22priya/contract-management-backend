package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ContractRepository extends MongoRepository<Contract, String> {

    List<Contract> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    List<Contract> findByCreatedByAndTeamIdOrderByCreatedAtDesc(String createdBy, String teamId);

    List<Contract> findByCreatedByAndStatusOrderByCreatedAtDesc(String createdBy, ContractStatus status);

    List<Contract> findByCreatedByAndTeamIdAndStatusOrderByCreatedAtDesc(String createdBy, String teamId, ContractStatus status);

    boolean existsByTitleAndCreatedBy(String title, String createdBy);

    boolean existsByTitleAndCreatedByAndIdNot(String title, String createdBy, String id);

    List<Contract> findByFileUploadedFalseAndUploadInitiatedAtBefore(LocalDateTime cutoff);

    @Query(
            value = "{ '$or': [ { 'reviewers.email': ?0 }, { 'approver.email': ?0 } ] }",
            sort  = "{ 'createdAt': -1 }"
    )
    List<Contract> findByAssignedToEmail(String email);

}
