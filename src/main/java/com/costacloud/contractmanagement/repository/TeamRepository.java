package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.Team;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TeamRepository extends MongoRepository<Team, String> {

    List<Team> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    boolean existsByNameAndCreatedBy(String name, String createdBy);

    // For rename: check duplicate excluding the team being renamed
    boolean existsByNameAndCreatedByAndIdNot(String name, String createdBy, String id);
}
