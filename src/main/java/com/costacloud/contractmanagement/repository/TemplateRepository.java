package com.costacloud.contractmanagement.repository;

import com.costacloud.contractmanagement.model.Template;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TemplateRepository extends MongoRepository<Template, String> {

    @Query(value = "{}", fields = "{'xfdfData': 0, 'formFields': 0}")
    List<Template> findAllExcludingLargeFields();

    List<Template> findByFileUploadedFalseAndUploadInitiatedAtBefore(LocalDateTime cutoff);
}
