package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.RevokedToken;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RevokedTokenRepository extends MongoRepository<RevokedToken, String> {
    boolean existsByJti(String jti);
}
