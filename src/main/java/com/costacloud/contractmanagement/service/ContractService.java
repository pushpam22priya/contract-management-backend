package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.ConflictException;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        contract.setFolderId(request.getFolderId());
        contract.setCreatedBy(email);
        contract.setFileUploaded(false);
        contract.setCreatedAt(LocalDateTime.now());
        contract.setUpdatedAt(LocalDateTime.now());

        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── List ────────────────────────────────────────────────────

    public List<ContractListResponse> listContracts(String email, String folderId, String status) {
        // Validate status enum early so unknown values throw IAE before doing any DB work
        ContractStatus requestedStatus = null;
        if (status != null) {
            requestedStatus = ContractStatus.valueOf(status.toUpperCase());
        }

        // Fetch all owner contracts — required to build the suppression map correctly
        List<Contract> all = contractRepository.findByCreatedByOrderByCreatedAtDesc(email);

        // Chain suppression: hide contracts that have been superseded by a renewal.
        // A contract is suppressed when its renewedContractId points to another contract
        // that also belongs to this owner (i.e., the renewal exists in this list).
        Set<String> allIds = new HashSet<>();
        for (Contract c : all) allIds.add(c.getId());

        Set<String> suppressedIds = new HashSet<>();
        for (Contract c : all) {
            if (c.getRenewedContractId() != null && allIds.contains(c.getRenewedContractId())) {
                suppressedIds.add(c.getId());
            }
        }

        List<Contract> visible = new ArrayList<>();
        for (Contract c : all) {
            if (!suppressedIds.contains(c.getId())) visible.add(c);
        }

        // Apply folderId filter in memory
        if (folderId != null) {
            String f = folderId;
            visible = visible.stream()
                    .filter(c -> f.equals(c.getFolderId()))
                    .collect(Collectors.toList());
        }

        // Apply status filter using effective status (mirrors ContractListResponse logic)
        if (requestedStatus != null) {
            ContractStatus rs = requestedStatus;
            return visible.stream()
                    .map(ContractListResponse::new)
                    .filter(r -> r.getStatus() == rs)
                    .collect(Collectors.toList());
        }

        return visible.stream()
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
        if (s == ContractStatus.SIGNED
                || s == ContractStatus.ACTIVE
                || s == ContractStatus.EXPIRING
                || s == ContractStatus.EXPIRED
                || s == ContractStatus.TERMINATED) {
            throw new BadRequestException("Contract cannot be edited after it has been finalized");
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
        if (request.getFormFields() != null) mergeFormFieldsSafe(contract, request.getFormFields());
        if (request.getParties() != null) contract.setParties(request.getParties());
        if (request.getFolderId() != null) contract.setFolderId(request.getFolderId());

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

    // ─── Terminate Contract ───────────────────────────────────────

    public TerminationResponse terminateContract(String id, String email) {
        Contract contract = findByIdAndOwner(id, email);

        // Idempotent — already terminated
        if (contract.getStatus() == ContractStatus.TERMINATED) {
            return new TerminationResponse(true, true);
        }

        // Only EXPIRED contracts can be terminated
        String effectiveStatus = computeEffectiveStatusLabel(contract);
        if (!"EXPIRED".equals(effectiveStatus)) {
            throw new BadRequestException(
                "Cannot terminate a contract with status \"" + effectiveStatus + "\". Only expired contracts can be terminated."
            );
        }

        // Block if a renewal is in flight
        if ("in_progress".equals(contract.getRenewalStatus())) {
            throw new ConflictException(
                "Cannot terminate a contract that has an active renewal in progress. Cancel or complete the renewal first."
            );
        }

        contract.setStatus(ContractStatus.TERMINATED);
        contract.setTerminatedAt(LocalDateTime.now());
        contract.setTerminatedBy(email);           // JWT email — authoritative over request body
        contract.setRenewalStatus(null);           // clear renewal linkage per spec
        contract.setRenewedContractId(null);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);

        return new TerminationResponse(true, false);
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

    // Merges incoming formFields from the frontend with the authoritative stored fields,
    // preserving party assignment metadata that Apryse's exportFormFields() strips out.
    private void mergeFormFieldsSafe(Contract contract, List<Map<String, Object>> incoming) {
        List<Map<String, Object>> existing = contract.getFormFields();
        if (existing == null || existing.isEmpty()) {
            contract.setFormFields(incoming);
            return;
        }

        Map<String, Map<String, Object>> existingByName = new HashMap<>();
        for (Map<String, Object> f : existing) {
            Object key = f.get("fieldName") != null ? f.get("fieldName") : f.get("name");
            if (key != null) existingByName.put(key.toString(), f);
        }

        List<String> protectedKeys = List.of(
                "assignedParty", "partyLabel", "partyColor", "profileKey", "lockedBy");

        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> inc : incoming) {
            Object key = inc.get("fieldName") != null ? inc.get("fieldName") : inc.get("name");
            if (key != null && existingByName.containsKey(key.toString())) {
                Map<String, Object> stored = existingByName.get(key.toString());
                Map<String, Object> m = new HashMap<>(inc);
                for (String pk : protectedKeys) {
                    if (stored.containsKey(pk)) m.put(pk, stored.get(pk));
                }
                merged.add(m);
            } else {
                merged.add(inc);
            }
        }
        contract.setFormFields(merged);
    }

    // Mirrors ContractListResponse status computation — used to validate termination eligibility.
    // Returns the computed status label as a string so the error message shows what the user sees.
    private String computeEffectiveStatusLabel(Contract c) {
        ContractStatus s = c.getStatus();
        if (s != ContractStatus.SIGNED && s != ContractStatus.ACTIVE
                && s != ContractStatus.EXPIRING && s != ContractStatus.EXPIRED) {
            return s.name();
        }
        if (c.getEndDate() == null) return "SIGNED";
        if (c.getEndDate().isBefore(LocalDate.now())) return "EXPIRED";
        long days = ChronoUnit.DAYS.between(LocalDate.now(), c.getEndDate());
        if (days <= 30) return "EXPIRING";
        if (c.getStartDate() != null && !c.getStartDate().isAfter(LocalDate.now())) return "ACTIVE";
        return "SIGNED";
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
