package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ContractRepository extends MongoRepository<Contract, String> {

    List<Contract> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    List<Contract> findByCreatedByAndFolderIdOrderByCreatedAtDesc(String createdBy, String folderId);

    List<Contract> findByCreatedByAndStatusOrderByCreatedAtDesc(String createdBy, ContractStatus status);

    List<Contract> findByCreatedByAndFolderIdAndStatusOrderByCreatedAtDesc(String createdBy, String folderId, ContractStatus status);

    boolean existsByTitleAndCreatedBy(String title, String createdBy);

    boolean existsByTitleAndCreatedByAndIdNot(String title, String createdBy, String id);

    List<Contract> findByFileUploadedFalseAndUploadInitiatedAtBefore(LocalDateTime cutoff);

    Optional<Contract> findFirstByRenewedFromId(String renewedFromId);

    @Query(
            value = "{ 'renewedFromId': { '$ne': null }, 'fileUploaded': false, " +
                    "'$or': [ { 'uploadInitiatedAt': { '$lt': ?0 } }, " +
                    "         { 'uploadInitiatedAt': null, 'createdAt': { '$lt': ?0 } } ] }"
    )
    List<Contract> findOrphanedRenewalDrafts(LocalDateTime cutoff);

    @Query(
            value = "{ '$or': [ " +
                    "  { 'reviewers.email': ?0 }, " +
                    "  { 'approver.email': ?0 }, " +
                    "  { 'internalSigners.email': ?0 } " +
                    "] }",
            sort = "{ 'createdAt': -1 }"
    )
    List<Contract> findByAssignedToEmail(String email);

    @Query("{ 'participants.email': ?0 }")
    List<Contract> findByParticipantsEmail(String email);

}
