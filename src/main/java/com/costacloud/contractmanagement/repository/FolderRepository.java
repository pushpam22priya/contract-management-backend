package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.Folder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FolderRepository extends MongoRepository<Folder, String> {

    List<Folder> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    boolean existsByNameAndCreatedBy(String name, String createdBy);

    boolean existsByNameAndCreatedByAndIdNot(String name, String createdBy, String id);
}
