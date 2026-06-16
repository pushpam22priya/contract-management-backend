package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.SignatureRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SignatureRequestRepository extends MongoRepository<SignatureRequest, String> {

    Optional<SignatureRequest> findByToken(String token);

    List<SignatureRequest> findByContractIdAndStatus(String contractId, String status);

    List<SignatureRequest> findByContractIdAndStatusNot(String contractId, String status);
}
