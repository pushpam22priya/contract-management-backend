package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.model.Template;
import com.costacloud.contractmanagement.repository.TemplateRepository;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Part;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final MinioClient minioClient;
    private final CustomMinioClient customMinioClient;
    private final MongoTemplate mongoTemplate;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public TemplateService(TemplateRepository templateRepository,
                           MinioClient minioClient,
                           CustomMinioClient customMinioClient,
                           MongoTemplate mongoTemplate) {
        this.templateRepository = templateRepository;
        this.minioClient = minioClient;
        this.customMinioClient = customMinioClient;
        this.mongoTemplate = mongoTemplate;
    }

    public String createTemplate(TemplateRequest request, String email) {
        Template template = new Template();
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setCategory(request.getCategory());
        template.setFileName(request.getFileName());
        template.setXfdfData(request.getXfdfData());
        template.setFormFields(request.getFormFields());
        template.setParties(request.getParties());
        template.setUploadedBy(email);
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        template.setTimesUsed(0);
        template.setFileUploaded(false);
        template.setHasFormFields(request.getFormFields() != null && !request.getFormFields().isEmpty());
        templateRepository.save(template);
        return template.getId();
    }

    public void uploadFile(String id, InputStream inputStream, long fileSize) throws Exception {
        Template template = findById(id);
        PushbackInputStream pis = validatePdf(inputStream);
        String objectKey = "templates/" + id + ".pdf";
        // When Content-Length is absent, fileSize is -1; MinIO requires partSize >= 5MB in that case
        long partSize = fileSize == -1 ? 10 * 1024 * 1024 : -1;
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .stream(pis, fileSize, partSize)
                .contentType("application/pdf")
                .build());
        template.setFileUrl(objectKey);
        template.setFileUploaded(true);
        template.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(template);
    }

    public InputStream getFile(String id) throws Exception {
        Template template = findById(id);
        if (!template.isFileUploaded()) {
            throw new RuntimeException("File not yet uploaded for this template");
        }
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(template.getFileUrl())
                .build());
    }

    public List<TemplateListResponse> listTemplates() {
        return templateRepository.findAllExcludingLargeFields()
                .stream()
                .map(TemplateListResponse::new)
                .collect(Collectors.toList());
    }

    public TemplateResponse getTemplate(String id) {
        return new TemplateResponse(findById(id));
    }

    public TemplateResponse updateTemplate(String id, TemplateRequest request) {
        Template template = findById(id);
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setCategory(request.getCategory());
        template.setFileName(request.getFileName());
        template.setXfdfData(request.getXfdfData());
        template.setFormFields(request.getFormFields());
        template.setParties(request.getParties());
        template.setHasFormFields(request.getFormFields() != null && !request.getFormFields().isEmpty());
        template.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(template);
        return new TemplateResponse(template);
    }

    public void deleteTemplate(String id) throws Exception {
        Template template = findById(id);
        if (template.isFileUploaded()) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object("templates/" + id + ".pdf")
                    .build());
        }
        templateRepository.deleteById(id);
    }

    public void incrementTimesUsed(String id) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("id").is(id)),
                new Update().inc("timesUsed", 1),
                Template.class
        );
    }

    // ─── Chunked Upload ─────────────────────────────────────────

    public ChunkUploadInitResponse initiateChunkedUpload(String id) throws Exception {
        findById(id);
        String objectKey = "templates/" + id + ".pdf";
        String uploadId = customMinioClient.startMultipartUpload(bucketName, objectKey);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("id").is(id)),
                new Update().set("uploadId", uploadId).set("uploadInitiatedAt", LocalDateTime.now()),
                Template.class
        );
        return new ChunkUploadInitResponse(uploadId, id);
    }

    public String generatePresignedPartUrl(String id, String uploadId, int partNumber) throws Exception {
        String objectKey = "templates/" + id + ".pdf";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("uploadId", uploadId);
        queryParams.put("partNumber", String.valueOf(partNumber));
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucketName)
                        .object(objectKey)
                        .expiry(15, TimeUnit.MINUTES)
                        .extraQueryParams(queryParams)
                        .build()
        );
    }

    public void completeChunkedUpload(String id, ChunkCompleteRequest request) throws Exception {
        String objectKey = "templates/" + id + ".pdf";
        List<Part> parts = new ArrayList<>();
        for (ChunkCompleteRequest.Part p : request.getParts()) {
            parts.add(new Part(p.getPartNumber(), p.getETag()));
        }
        customMinioClient.finishMultipartUpload(bucketName, objectKey, request.getUploadId(), parts.toArray(new Part[0]));
        Template template = findById(id);
        template.setFileUrl(objectKey);
        template.setFileUploaded(true);
        template.setUploadId(null);
        template.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(template);
    }

    public void abortChunkedUpload(String id, String uploadId) throws Exception {
        String objectKey = "templates/" + id + ".pdf";
        customMinioClient.cancelMultipartUpload(bucketName, objectKey, uploadId);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("id").is(id)),
                new Update().set("uploadId", null).set("fileUploaded", false),
                Template.class
        );
    }

    // ─── Cleanup (called by scheduler) ──────────────────────────

    public List<Template> findOrphanedUploads(LocalDateTime cutoff) {
        return templateRepository.findByFileUploadedFalseAndUploadInitiatedAtBefore(cutoff);
    }

    // ─── Private Helpers ─────────────────────────────────────────

    private Template findById(String id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Template not found"));
    }

    private PushbackInputStream validatePdf(InputStream inputStream) throws Exception {
        PushbackInputStream pis = new PushbackInputStream(inputStream, 4);
        byte[] header = new byte[4];
        int bytesRead = pis.read(header);
        if (bytesRead < 4 || !new String(header).startsWith("%PDF")) {
            throw new RuntimeException("File is not a valid PDF");
        }
        pis.unread(header, 0, bytesRead);
        return pis;
    }
}
