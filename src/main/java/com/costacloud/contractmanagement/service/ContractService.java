package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import com.costacloud.contractmanagement.repository.ContractRepository;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Part;
import io.minio.GetObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ContractService {

    private final ContractRepository contractRepository;
    private final MinioClient minioClient;
    private final CustomMinioClient customMinioClient;
    private final MongoTemplate mongoTemplate;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public ContractService(ContractRepository contractRepository,
                           MinioClient minioClient,
                           CustomMinioClient customMinioClient,
                           MongoTemplate mongoTemplate) {
        this.contractRepository = contractRepository;
        this.minioClient = minioClient;
        this.customMinioClient = customMinioClient;
        this.mongoTemplate = mongoTemplate;
    }

    // ─── Create ──────────────────────────────────────────────────

    public ContractResponse createContract(ContractRequest request, String email) {
        String title = request.getTitle().trim();

        if (contractRepository.existsByTitleAndCreatedBy(title, email)) {
            throw new BadRequestException("A contract with this title already exists");
        }

        LocalDate startDate = request.getStartDate() != null
                ? request.getStartDate() : LocalDate.now();
        LocalDate endDate = request.getEndDate() != null
                ? request.getEndDate() : startDate.plusYears(1);

        if (endDate.isBefore(startDate)) {
            throw new BadRequestException("End date must be on or after start date");
        }

        Contract contract = new Contract();
        contract.setTitle(title);
        contract.setClient(request.getClient().trim());
        contract.setDescription(request.getDescription() != null
                ? request.getDescription().trim()
                : "Contract based on " + request.getTemplateName());
        contract.setCategory(request.getCategory());
        contract.setStatus(ContractStatus.DRAFT);
        contract.setStartDate(startDate);
        contract.setEndDate(endDate);
        contract.setTemplateId(request.getTemplateId());
        contract.setTemplateName(request.getTemplateName());
        contract.setTemplateFileName(request.getTemplateFileName());
        contract.setXfdfData(request.getXfdfData());
        contract.setFieldValues(request.getFieldValues());
        contract.setFormFields(request.getFormFields());
        contract.setHasFormFields(request.isHasFormFields());
        contract.setParties(request.getParties());
        contract.setTeamId(request.getTeamId());
        contract.setCreatedBy(email);
        contract.setFileUploaded(false);
        contract.setCreatedAt(LocalDateTime.now());
        contract.setUpdatedAt(LocalDateTime.now());

        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── List ────────────────────────────────────────────────────

    public List<ContractListResponse> listContracts(String email, String teamId, String status) {
        List<Contract> contracts;

        if (teamId != null && status != null) {
            contracts = contractRepository.findByCreatedByAndTeamIdAndStatusOrderByCreatedAtDesc(
                    email, teamId, ContractStatus.valueOf(status.toUpperCase()));
        } else if (teamId != null) {
            contracts = contractRepository.findByCreatedByAndTeamIdOrderByCreatedAtDesc(email, teamId);
        } else if (status != null) {
            contracts = contractRepository.findByCreatedByAndStatusOrderByCreatedAtDesc(
                    email, ContractStatus.valueOf(status.toUpperCase()));
        } else {
            contracts = contractRepository.findByCreatedByOrderByCreatedAtDesc(email);
        }

        return contracts.stream()
                .map(ContractListResponse::new)
                .collect(Collectors.toList());
    }

    // ─── Get ─────────────────────────────────────────────────────

    public ContractResponse getContract(String id, String email) {
        return new ContractResponse(findByIdAndOwner(id, email));
    }

    // ─── Update (PATCH) ──────────────────────────────────────────

    public ContractResponse updateContract(String id, ContractRequest request, String email) {
        Contract contract = findByIdAndOwner(id, email);

        ContractStatus s = contract.getStatus();
        if (s == ContractStatus.IN_REVIEW
                || s == ContractStatus.IN_APPROVAL
                || s == ContractStatus.READY_FOR_SIGNATURE) {
            throw new BadRequestException(
                    "Contract cannot be edited while it is " + s.name().toLowerCase().replace("_", " ")
            );
        }

        if (request.getTitle() != null) {
            String title = request.getTitle().trim();
            if (!title.equals(contract.getTitle()) &&
                    contractRepository.existsByTitleAndCreatedByAndIdNot(title, email, id)) {
                throw new BadRequestException("A contract with this title already exists");
            }
            contract.setTitle(title);
        }
        if (request.getClient() != null) contract.setClient(request.getClient().trim());
        if (request.getDescription() != null) contract.setDescription(request.getDescription().trim());
        if (request.getCategory() != null) contract.setCategory(request.getCategory());
        if (request.getStartDate() != null) contract.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) {
            LocalDate endDate = request.getEndDate();
            LocalDate startDate = request.getStartDate() != null
                    ? request.getStartDate() : contract.getStartDate();
            if (endDate.isBefore(startDate)) {
                throw new BadRequestException("End date must be on or after start date");
            }
            contract.setEndDate(endDate);
        }
        if (request.getXfdfData() != null) contract.setXfdfData(request.getXfdfData());
        if (request.getFieldValues() != null) contract.setFieldValues(request.getFieldValues());
        if (request.getFormFields() != null) contract.setFormFields(request.getFormFields());
        if (request.getParties() != null) contract.setParties(request.getParties());
        if (request.getTeamId() != null) contract.setTeamId(request.getTeamId());

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── Single-Shot File Upload ──────────────────────────────────

    public void uploadFile(String id, InputStream inputStream, long fileSize, String email) throws Exception {
        Contract contract = findByIdAndOwner(id, email);
        PushbackInputStream pis = validatePdf(inputStream);
        String objectKey = "contracts/" + id + ".pdf";
        long partSize = fileSize == -1 ? 10 * 1024 * 1024 : -1;

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .stream(pis, fileSize, partSize)
                .contentType("application/pdf")
                .build());

        contract.setFileUploaded(true);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
    }

    // ─── Presigned View URL ───────────────────────────────────────

    public String generatePresignedViewUrl(String id, String email) throws Exception {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        boolean isOwner    = contract.getCreatedBy().equals(email);
        boolean isReviewer = contract.getReviewers() != null &&
                contract.getReviewers().stream()
                        .anyMatch(r -> r.getEmail().equalsIgnoreCase(email));
        boolean isApprover = contract.getApprover() != null &&
                contract.getApprover().getEmail().equalsIgnoreCase(email);
        boolean isInternalSigner = contract.getInternalSigners() != null &&
                contract.getInternalSigners().stream()
                        .anyMatch(s -> s.getEmail().equalsIgnoreCase(email));

        if (!isOwner && !isReviewer && !isApprover && !isInternalSigner) {
            throw new NotFoundException("Contract not found");
        }

        if (!contract.isFileUploaded()) {
            throw new BadRequestException("File not yet uploaded for this contract");
        }

        String signedKey   = "contracts/" + id + "_signed.pdf";
        String originalKey = "contracts/" + id + ".pdf";
        String objectKey   = objectExistsInMinio(signedKey) ? signedKey : originalKey;

        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectKey)
                        .expiry(15, TimeUnit.MINUTES)
                        .build()
        );
    }

    private boolean objectExistsInMinio(String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucketName).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Chunked Upload ───────────────────────────────────────────

    public ChunkUploadInitResponse initiateChunkedUpload(String id, String email) throws Exception {
        findByIdAndOwner(id, email);
        String objectKey = "contracts/" + id + ".pdf";
        String uploadId = customMinioClient.startMultipartUpload(bucketName, objectKey);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("id").is(id)),
                new Update().set("uploadId", uploadId).set("uploadInitiatedAt", LocalDateTime.now()),
                Contract.class
        );
        return new ChunkUploadInitResponse(uploadId, id);
    }

    public String generatePresignedPartUrl(String id, String uploadId, int partNumber, String email) throws Exception {
        findByIdAndOwner(id, email);
        String objectKey = "contracts/" + id + ".pdf";
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

    public void completeChunkedUpload(String id, ChunkCompleteRequest request, String email) throws Exception {
        Contract contract = findByIdAndOwner(id, email);
        String objectKey = "contracts/" + id + ".pdf";
        List<Part> parts = new ArrayList<>();
        for (ChunkCompleteRequest.Part p : request.getParts()) {
            parts.add(new Part(p.getPartNumber(), p.getETag()));
        }
        customMinioClient.finishMultipartUpload(bucketName, objectKey,
                request.getUploadId(), parts.toArray(new Part[0]));

        try (java.io.InputStream is = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectKey)
                        .offset(0L)
                        .length(4L)
                        .build())) {
            byte[] header = is.readNBytes(4);
            if (header.length < 4 || !new String(header).startsWith("%PDF")) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectKey)
                        .build());
                throw new BadRequestException("File is not a valid PDF");
            }
        }

        contract.setFileUploaded(true);
        contract.setUploadId(null);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
    }

    public void abortChunkedUpload(String id, String uploadId, String email) throws Exception {
        findByIdAndOwner(id, email);
        String objectKey = "contracts/" + id + ".pdf";
        customMinioClient.cancelMultipartUpload(bucketName, objectKey, uploadId);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("id").is(id)),
                new Update().set("uploadId", null),
                Contract.class
        );
    }

    // ─── Get Inbox Contracts ───────────────────────────

    public List<ContractResponse> getInboxContracts(String email) {
        return contractRepository.findByAssignedToEmail(email)
                .stream()
                .map(ContractResponse::new)
                .collect(Collectors.toList());
    }

    // ─── Cleanup (called by scheduler) ───────────────────────────

    public List<Contract> findOrphanedUploads(LocalDateTime cutoff) {
        return contractRepository.findByFileUploadedFalseAndUploadInitiatedAtBefore(cutoff);
    }

    // ─── Private Helpers ──────────────────────────────────────────

    private Contract findByIdAndOwner(String id, String email) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        if (!contract.getCreatedBy().equals(email)) {
            throw new NotFoundException("Contract not found");
        }
        return contract;
    }

    private PushbackInputStream validatePdf(InputStream inputStream) throws Exception {
        PushbackInputStream pis = new PushbackInputStream(inputStream, 4);
        byte[] header = new byte[4];
        int bytesRead = pis.read(header);
        if (bytesRead < 4 || !new String(header).startsWith("%PDF")) {
            throw new BadRequestException("File is not a valid PDF");
        }
        pis.unread(header, 0, bytesRead);
        return pis;
    }
}
