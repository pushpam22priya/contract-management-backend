package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.FileRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileRecordRepository extends MongoRepository<FileRecord, String> {
}
