package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Part;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class UnifiedWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedWorkflowService.class);

    private final ContractRepository contractRepository;
    private final SignatureService signatureService;
    private final CustomMinioClient customMinioClient;
    private final MinioClient minioClient;
    private final MongoTemplate mongoTemplate;
    private final ContractRenewalService contractRenewalService;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public UnifiedWorkflowService(ContractRepository contractRepository,
                                  SignatureService signatureService,
                                  CustomMinioClient customMinioClient,
                                  MinioClient minioClient,
                                  MongoTemplate mongoTemplate,
                                  ContractRenewalService contractRenewalService) {
        this.contractRepository = contractRepository;
        this.signatureService = signatureService;
        this.customMinioClient = customMinioClient;
        this.minioClient = minioClient;
        this.mongoTemplate = mongoTemplate;
        this.contractRenewalService = contractRenewalService;
    }

    // ─── Submit ───────────────────────────────────────────────────

    public ContractResponse submit(String id, FlowSubmitRequest request, String callerEmail) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (!contract.getCreatedBy().equalsIgnoreCase(callerEmail)) {
            throw new NotFoundException("Contract not found");
        }

        ContractStatus status = contract.getStatus();
        boolean isRejected = status == ContractStatus.REJECTED
                || status == ContractStatus.REJECTED_BY_REVIEWER
                || status == ContractStatus.REJECTED_BY_APPROVER;
        if (status != ContractStatus.DRAFT && !isRejected) {
            throw new BadRequestException(
                    "Contract must be DRAFT or REJECTED to submit into the unified flow. Current status: " + status);
        }

        validateParticipants(request.getParticipants(), callerEmail);

        if (status == ContractStatus.DRAFT && contract.getRenewedFromId() != null) {
            contractRenewalService.linkRenewalIfApplicable(contract);
        }

        if (status == ContractStatus.DRAFT) {
            handleFreshSubmit(contract, request, callerEmail);
        } else {
            handleResubmit(contract, request, callerEmail);
        }

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    private void handleFreshSubmit(Contract contract, FlowSubmitRequest request, String callerEmail) {
        List<ParticipantAssignment> assignments = request.getParticipants();
        LocalDateTime now = LocalDateTime.now();

        int minOrder = assignments.stream()
                .mapToInt(ParticipantAssignment::getOrder)
                .min()
                .getAsInt();

        List<WorkflowParticipant> participants = buildParticipantList(assignments, minOrder, callerEmail, now);

        contract.setParticipants(participants);
        contract.setCurrentParticipantOrder(minOrder);
        contract.setExternalSigningIncluded(request.isExternalSigningIncluded());

        if (request.isExternalSigningIncluded()) {
            if (request.getExternalSigners() == null || request.getExternalSigners().isEmpty()) {
                throw new BadRequestException(
                        "externalSigners list is required when externalSigningIncluded is true");
            }
            contract.setPendingExternalSigners(request.getExternalSigners());
            contract.setFlowSenderName(request.getSenderName());
        } else {
            contract.setPendingExternalSigners(null);
            contract.setFlowSenderName(null);
        }

        ParticipantRole firstRole = assignments.stream()
                .filter(a -> a.getOrder() == minOrder)
                .map(ParticipantAssignment::getRole)
                .findFirst()
                .orElse(ParticipantRole.REVIEWER);

        contract.setStatus(firstRole == ParticipantRole.REVIEWER
                ? ContractStatus.IN_REVIEW
                : ContractStatus.IN_APPROVAL);
    }

    private void handleResubmit(Contract contract, FlowSubmitRequest request, String callerEmail) {
        // Contractor has refreshed the full document before resubmitting — always a full reset
        // regardless of whether the rejection was by a reviewer or approver.

        // Re-create the working copy from the owner's refreshed original, so participants in the new
        // flow (and the owner) again read/write contracts/{id}_signed.pdf as the single source of
        // truth — starting from the fresh document the owner just uploaded, not the old annotated one.
        String originalKey  = "contracts/" + contract.getId() + ".pdf";
        String signedKey    = "contracts/" + contract.getId() + "_signed.pdf";
        String rejectedKey  = "contracts/" + contract.getId() + "_rejected.pdf";
        try {
            copyPdfInMinio(originalKey, signedKey);
            contract.setSignedPdfKey(signedKey);
        } catch (Exception e) {
            log.warn("Could not re-create working copy on resubmit for contract {}: {}",
                    contract.getId(), e.getMessage());
            contract.setSignedPdfKey(null);
        }

        // Clean up the rejected archive — it is superseded by the fresh working copy above
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(rejectedKey)
                    .build());
        } catch (Exception e) {
            log.debug("No rejected PDF to clean up on resubmit for contract {}", contract.getId());
        }

        List<ParticipantAssignment> newAssignments = request.getParticipants();
        LocalDateTime now = LocalDateTime.now();

        int minOrder = newAssignments.stream()
                .mapToInt(ParticipantAssignment::getOrder)
                .min()
                .getAsInt();

        List<WorkflowParticipant> participants = buildParticipantList(newAssignments, minOrder, callerEmail, now);

        contract.setParticipants(participants);
        contract.setCurrentParticipantOrder(minOrder);
        contract.setExternalSigningIncluded(request.isExternalSigningIncluded());

        if (request.isExternalSigningIncluded()) {
            if (request.getExternalSigners() == null || request.getExternalSigners().isEmpty()) {
                throw new BadRequestException(
                        "externalSigners list is required when externalSigningIncluded is true");
            }
            contract.setPendingExternalSigners(request.getExternalSigners());
            contract.setFlowSenderName(request.getSenderName());
        } else {
            contract.setPendingExternalSigners(null);
            contract.setFlowSenderName(null);
        }

        ParticipantRole firstRole = newAssignments.stream()
                .filter(a -> a.getOrder() == minOrder)
                .map(ParticipantAssignment::getRole)
                .findFirst()
                .orElse(ParticipantRole.REVIEWER);

        contract.setStatus(firstRole == ParticipantRole.REVIEWER
                ? ContractStatus.IN_REVIEW
                : ContractStatus.IN_APPROVAL);

        appendModificationRequest(contract, callerEmail, "contractor", "Resubmitted after rejection");
    }

    // ─── Save Field Edits ─────────────────────────────────────────

    public ContractResponse saveFieldEdits(String id, FlowFieldEditRequest request, String callerEmail) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (contract.getStatus() != ContractStatus.IN_REVIEW
                && contract.getStatus() != ContractStatus.IN_APPROVAL) {
            throw new BadRequestException(
                    "Field edits are only allowed while the contract is IN_REVIEW or IN_APPROVAL. " +
                            "Current status: " + contract.getStatus());
        }

        WorkflowParticipant participant = findActiveParticipant(contract, callerEmail);

        // Transition from unlocked to in_progress on first save
        if ("unlocked".equals(participant.getStatus())) {
            participant.setStatus("in_progress");
        }

        if (request.getFormFields() != null && !request.getFormFields().isEmpty()) {
            mergeFormFields(contract, request.getFormFields(), callerEmail);
        }

        if (request.getFieldValues() != null && !request.getFieldValues().isEmpty()) {
            Map<String, String> existing = contract.getFieldValues() != null
                    ? new HashMap<>(contract.getFieldValues())
                    : new HashMap<>();
            existing.putAll(request.getFieldValues());
            contract.setFieldValues(existing);
        }

        if (request.getXfdfData() != null) {
            contract.setXfdfData(request.getXfdfData());
        }

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    // ─── Mark Complete ────────────────────────────────────────────

    public ContractResponse markComplete(String id, FlowCompleteRequest request,
                                         String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (contract.getStatus() != ContractStatus.IN_REVIEW
                && contract.getStatus() != ContractStatus.IN_APPROVAL) {
            throw new BadRequestException(
                    "Cannot mark complete — contract is not IN_REVIEW or IN_APPROVAL. " +
                            "Current status: " + contract.getStatus());
        }

        WorkflowParticipant participant = findActiveParticipant(contract, callerEmail);

        // Both REVIEWER and APPROVER must upload the edited PDF when completing
        if (request.getUploadId() == null || request.getUploadId().isBlank()) {
            throw new BadRequestException("uploadId is required — upload the edited PDF before marking complete.");
        }
        if (request.getParts() == null || request.getParts().isEmpty()) {
            throw new BadRequestException("parts list is required — complete the multipart upload before marking complete.");
        }

        String objectKey = "contracts/" + id + "_signed.pdf";
        List<Part> parts = new ArrayList<>();
        for (FlowCompleteRequest.Part p : request.getParts()) {
            parts.add(new Part(p.getPartNumber(), p.getEtag()));
        }
        customMinioClient.finishMultipartUpload(bucketName, objectKey,
                request.getUploadId(), parts.toArray(new Part[0]));
        contract.setSignedPdfKey(objectKey);
        contract.setFileUploaded(true);

        if (request.getFormFields() != null && !request.getFormFields().isEmpty()) {
            mergeFormFields(contract, request.getFormFields(), callerEmail);
        }
        if (request.getFieldValues() != null && !request.getFieldValues().isEmpty()) {
            Map<String, String> existing = contract.getFieldValues() != null
                    ? new HashMap<>(contract.getFieldValues())
                    : new HashMap<>();
            existing.putAll(request.getFieldValues());
            contract.setFieldValues(existing);
        }
        if (request.getXfdfData() != null) {
            contract.setXfdfData(request.getXfdfData());
        }

        if (participant.getRole() == ParticipantRole.APPROVER) {
            // Increment version and sync to any pending external signature requests
            contract.setVersion(contract.getVersion() + 1);
            syncVersionToAllPendingRequests(id, contract.getVersion());
        }

        participant.setStatus("completed");
        participant.setCompletedAt(LocalDateTime.now());
        if (request.getComments() != null && !request.getComments().isBlank()) {
            participant.setComments(request.getComments());
        }

        advanceWorkflow(contract);

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);

        // Auto-trigger external signing if all internal participants are done
        if (contract.getStatus() == ContractStatus.READY_FOR_SIGNATURE
                && contract.isExternalSigningIncluded()
                && contract.getPendingExternalSigners() != null
                && !contract.getPendingExternalSigners().isEmpty()) {
            try {
                triggerAutoExternalSigning(contract.getId(), contract.getCreatedBy(),
                        contract.getFlowSenderName());
            } catch (Exception e) {
                log.error("Auto-trigger external signing failed for contract {}: {}", id, e.getMessage(), e);
            }
            // Reload from DB — status may now be IN_SIGNATURE (or READY_FOR_SIGNATURE if trigger failed)
            contract = contractRepository.findById(id).orElse(contract);
        }

        return new ContractResponse(contract);
    }

    // ─── Reject ───────────────────────────────────────────────────

    public ContractResponse reject(String id, FlowRejectRequest request, String callerEmail) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (contract.getStatus() != ContractStatus.IN_REVIEW
                && contract.getStatus() != ContractStatus.IN_APPROVAL) {
            throw new BadRequestException(
                    "Cannot reject — contract is not IN_REVIEW or IN_APPROVAL. " +
                            "Current status: " + contract.getStatus());
        }

        WorkflowParticipant participant = findActiveParticipant(contract, callerEmail);

        participant.setStatus("rejected");
        participant.setRejectedAt(LocalDateTime.now());
        participant.setComments(request.getMessage());

        contract.setStatus(participant.getRole() == ParticipantRole.REVIEWER
                ? ContractStatus.REJECTED_BY_REVIEWER
                : ContractStatus.REJECTED_BY_APPROVER);

        appendModificationRequest(contract, callerEmail,
                participant.getRole().name().toLowerCase(),
                request.getMessage());

        // Archive the in-flight working copy and fall back to the original for the owner's revision.
        archiveSignedAsRejected(contract);

        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    /**
     * On rejection, rename the working copy contracts/{id}_signed.pdf → contracts/{id}_rejected.pdf
     * (copy + delete) and clear signedPdfKey, so the read resolver falls back to the original
     * contracts/{id}.pdf. The archived _rejected.pdf is kept for record.
     */
    private void archiveSignedAsRejected(Contract contract) {
        String signedKey = "contracts/" + contract.getId() + "_signed.pdf";
        if (!objectExistsInMinio(signedKey)) {
            contract.setSignedPdfKey(null);
            return;
        }
        String rejectedKey = "contracts/" + contract.getId() + "_rejected.pdf";
        try {
            copyPdfInMinio(signedKey, rejectedKey);
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(signedKey)
                    .build());
            log.info("Archived working copy as {} for rejected contract {}", rejectedKey, contract.getId());
        } catch (Exception e) {
            log.warn("Could not archive signed PDF as rejected for contract {}: {}",
                    contract.getId(), e.getMessage());
        }
        contract.setSignedPdfKey(null);
    }

    /**
     * Copies a PDF object within the bucket via read + write (same approach as
     * SignatureService.finalizeContract) rather than server-side copyObject, which is not reliable
     * in this MinIO deployment.
     */
    private void copyPdfInMinio(String srcKey, String dstKey) throws Exception {
        byte[] bytes;
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucketName).object(srcKey).build())) {
            bytes = is.readAllBytes();
        }
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(dstKey)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType("application/pdf")
                .build());
    }

    // ─── Upload — Approver Only ───────────────────────────────────

    public SignUploadInitResponse initiateUpload(String id, String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        verifyActiveApprover(contract, callerEmail);

        String objectKey = "contracts/" + id + "_signed.pdf";
        String uploadId = customMinioClient.startMultipartUpload(bucketName, objectKey);
        return new SignUploadInitResponse(uploadId);
    }

    public String getPresignedPartUrl(String id, String uploadId,
                                      int partNumber, String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        verifyActiveApprover(contract, callerEmail);

        if (partNumber < 1 || partNumber > 10000) {
            throw new BadRequestException("Part number must be between 1 and 10000");
        }

        String objectKey = "contracts/" + id + "_signed.pdf";
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

    public void abortUpload(String id, String uploadId, String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        verifyActiveApprover(contract, callerEmail);

        if (uploadId == null || uploadId.isBlank()) {
            throw new BadRequestException("uploadId is required");
        }

        customMinioClient.cancelMultipartUpload(
                bucketName, "contracts/" + id + "_signed.pdf", uploadId);
    }

    // ─── Working Copy — Owner Only ────────────────────────────────
    // The owner edits the single canonical working copy (contracts/{id}_signed.pdf) — the
    // same object reviewers/approvers/signers read and write. Allowed until the contract is
    // finalized. The original contracts/{id}.pdf is left untouched (audit + resubmit reset).

    public SignUploadInitResponse initiateWorkingCopyUpload(String id, String callerEmail) throws Exception {
        Contract contract = verifyOwnerCanEdit(id, callerEmail);

        String objectKey = "contracts/" + contract.getId() + "_signed.pdf";
        String uploadId = customMinioClient.startMultipartUpload(bucketName, objectKey);
        return new SignUploadInitResponse(uploadId);
    }

    public String getWorkingCopyPresignedPartUrl(String id, String uploadId,
                                                 int partNumber, String callerEmail) throws Exception {
        verifyOwnerCanEdit(id, callerEmail);

        if (partNumber < 1 || partNumber > 10000) {
            throw new BadRequestException("Part number must be between 1 and 10000");
        }

        String objectKey = "contracts/" + id + "_signed.pdf";
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

    public void abortWorkingCopyUpload(String id, String uploadId, String callerEmail) throws Exception {
        verifyOwnerCanEdit(id, callerEmail);

        if (uploadId == null || uploadId.isBlank()) {
            throw new BadRequestException("uploadId is required");
        }

        customMinioClient.cancelMultipartUpload(
                bucketName, "contracts/" + id + "_signed.pdf", uploadId);
    }

    public ContractResponse completeWorkingCopy(String id, FlowCompleteRequest request,
                                                String callerEmail) throws Exception {
        Contract contract = verifyOwnerCanEdit(id, callerEmail);

        if (request.getUploadId() == null || request.getUploadId().isBlank()) {
            throw new BadRequestException("uploadId is required — upload the edited PDF before saving.");
        }
        if (request.getParts() == null || request.getParts().isEmpty()) {
            throw new BadRequestException("parts list is required — complete the multipart upload before saving.");
        }

        String objectKey = "contracts/" + id + "_signed.pdf";
        List<Part> parts = new ArrayList<>();
        for (FlowCompleteRequest.Part p : request.getParts()) {
            parts.add(new Part(p.getPartNumber(), p.getEtag()));
        }
        customMinioClient.finishMultipartUpload(bucketName, objectKey,
                request.getUploadId(), parts.toArray(new Part[0]));
        contract.setSignedPdfKey(objectKey);
        contract.setFileUploaded(true);

        if (request.getFormFields() != null && !request.getFormFields().isEmpty()) {
            mergeFormFields(contract, request.getFormFields(), callerEmail);
        }
        if (request.getFieldValues() != null && !request.getFieldValues().isEmpty()) {
            Map<String, String> existing = contract.getFieldValues() != null
                    ? new HashMap<>(contract.getFieldValues())
                    : new HashMap<>();
            existing.putAll(request.getFieldValues());
            contract.setFieldValues(existing);
        }
        if (request.getXfdfData() != null) {
            contract.setXfdfData(request.getXfdfData());
        }

        // Owner edits neither advance the workflow nor bump the signing version — they only
        // update the shared working copy in place so every participant sees the latest bytes.
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        return new ContractResponse(contract);
    }

    /**
     * Authorizes the caller as the contract owner and ensures the contract is not finalized.
     * Owner edits to the working copy are permitted in every pre-finalization state.
     */
    private Contract verifyOwnerCanEdit(String id, String callerEmail) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (!contract.getCreatedBy().equalsIgnoreCase(callerEmail)) {
            throw new NotFoundException("Contract not found");
        }

        ContractStatus s = contract.getStatus();
        if (s == ContractStatus.SIGNED
                || s == ContractStatus.ACTIVE
                || s == ContractStatus.EXPIRING
                || s == ContractStatus.EXPIRED
                || s == ContractStatus.TERMINATED) {
            throw new BadRequestException("Contract cannot be edited after it has been finalized");
        }
        return contract;
    }

    // ─── File URL ─────────────────────────────────────────────────

    public Map<String, Object> getParticipantFileUrl(String id, String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        boolean isOwner = contract.getCreatedBy().equalsIgnoreCase(callerEmail);
        boolean isParticipant = orEmpty(contract.getParticipants()).stream()
                .anyMatch(p -> p.getEmail().equalsIgnoreCase(callerEmail));

        if (!isOwner && !isParticipant) {
            throw new NotFoundException("Contract not found");
        }

        String signedKey    = "contracts/" + id + "_signed.pdf";
        String rejectedKey  = "contracts/" + id + "_rejected.pdf";
        String originalKey  = "contracts/" + id + ".pdf";

        boolean hasSignedCopy   = objectExistsInMinio(signedKey);
        boolean hasRejectedCopy = !hasSignedCopy && objectExistsInMinio(rejectedKey);
        // Both _signed.pdf and _rejected.pdf have baked-in ink signatures — no XFDF overlay allowed.
        boolean isSignedCopy    = hasSignedCopy || hasRejectedCopy;
        String objectKey = hasSignedCopy   ? signedKey
                         : hasRejectedCopy ? rejectedKey
                         : originalKey;

        String url = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectKey)
                        .expiry(15, TimeUnit.MINUTES)
                        .build()
        );

        // isSignedCopy tells the client the served binary already has all signatures/values baked in,
        // so it must NOT overlay XFDF on top (that would wipe baked ink signatures).
        return Map.of("url", url, "isSignedCopy", isSignedCopy);
    }

    // ─── Send for Signature (with org-gate) ──────────────────────

    public ContractResponse sendForSignature(String id,
                                             SubmitForSignatureRequest request,
                                             String callerEmail) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (!contract.getCreatedBy().equalsIgnoreCase(callerEmail)) {
            throw new NotFoundException("Contract not found");
        }

        if (contract.getStatus() != ContractStatus.READY_FOR_SIGNATURE) {
            throw new BadRequestException(
                    "Contract must be READY_FOR_SIGNATURE to send for external signature. " +
                            "Current status: " + contract.getStatus());
        }

        if (!contract.isFileUploaded()) {
            throw new BadRequestException(
                    "Contract file must be uploaded before sending for signature");
        }

        validateOrgFieldsComplete(contract);

        for (SignerAssignmentDto a : request.getAssignments()) {
            if (!"external".equalsIgnoreCase(a.getType())) {
                throw new BadRequestException(
                        "Only external signers are allowed in the unified flow. " +
                                "Signer '" + a.getEmail() + "' has type '" + a.getType() + "'.");
            }
        }

        return signatureService.submitForSignature(id, request, callerEmail);
    }

    private void triggerAutoExternalSigning(String contractId, String ownerEmail, String senderName) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        validateOrgFieldsComplete(contract);

        // Build a lookup: signing order → EXTERNAL party, for resolving missing partyId/partyLabel
        Map<Integer, Party> externalPartiesByOrder = orEmpty(contract.getParties()).stream()
                .filter(p -> p.getType() == PartyType.EXTERNAL)
                .collect(Collectors.toMap(Party::getOrder, p -> p, (a, b) -> a));

        // Also index all EXTERNAL parties by id for any direct match
        Map<String, Party> externalPartiesById = orEmpty(contract.getParties()).stream()
                .filter(p -> p.getType() == PartyType.EXTERNAL)
                .filter(p -> p.getId() != null)
                .collect(Collectors.toMap(Party::getId, p -> p, (a, b) -> a));

        List<SignerAssignmentDto> assignments = orEmpty(contract.getPendingExternalSigners())
                .stream()
                .map(e -> {
                    SignerAssignmentDto dto = new SignerAssignmentDto();
                    dto.setEmail(e.getEmail());
                    dto.setName(e.getName() != null ? e.getName() : e.getEmail());
                    dto.setType("external");
                    dto.setOrder(e.getOrder());

                    String partyId = e.getPartyId();
                    String partyLabel = e.getPartyLabel();

                    // Resolve partyId — try direct id match, then match by order
                    if (partyId == null || partyId.isBlank()) {
                        Party matched = externalPartiesByOrder.get(e.getOrder());
                        if (matched == null && !externalPartiesByOrder.isEmpty()) {
                            matched = externalPartiesByOrder.values().iterator().next();
                        }
                        if (matched != null) {
                            partyId = matched.getId();
                            if (partyLabel == null || partyLabel.isBlank()) {
                                partyLabel = matched.getLabel();
                            }
                        }
                    } else if (partyLabel == null || partyLabel.isBlank()) {
                        Party matched = externalPartiesById.get(partyId);
                        if (matched != null) partyLabel = matched.getLabel();
                    }

                    // Final fallbacks — List.of() crashes on null
                    dto.setPartyId(partyId != null ? partyId : UUID.randomUUID().toString());
                    dto.setPartyLabel(partyLabel != null ? partyLabel : e.getEmail());
                    return dto;
                })
                .collect(Collectors.toList());

        SubmitForSignatureRequest sigRequest = new SubmitForSignatureRequest();
        sigRequest.setAssignments(assignments);
        sigRequest.setSenderName(senderName);

        signatureService.submitForSignature(contractId, sigRequest, ownerEmail);
    }

    // ─── Flow Status ──────────────────────────────────────────────

    public FlowStatusResponse getFlowStatus(String id, String callerEmail) {
        Contract contract = contractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        boolean isOwner = contract.getCreatedBy().equalsIgnoreCase(callerEmail);
        boolean isParticipant = orEmpty(contract.getParticipants()).stream()
                .anyMatch(p -> p.getEmail().equalsIgnoreCase(callerEmail));

        if (!isOwner && !isParticipant) {
            throw new NotFoundException("Contract not found");
        }

        List<String> unfilledFields = getUnfilledOrgFields(contract);

        FlowStatusResponse response = new FlowStatusResponse();
        response.setContractId(id);
        response.setStatus(contract.getStatus());
        response.setCurrentParticipantOrder(contract.getCurrentParticipantOrder());
        response.setParticipants(contract.getParticipants());
        response.setExternalSigningIncluded(contract.isExternalSigningIncluded());
        response.setOrgFieldsComplete(unfilledFields.isEmpty());
        response.setUnfilledOrgFields(unfilledFields);
        response.setParties(orEmpty(contract.getParties()));
        response.setFormFields(contract.getFormFields());
        response.setXfdfData(contract.getXfdfData());
        return response;
    }

    // ─── Inbox ────────────────────────────────────────────────────

    public List<ContractListResponse> getFlowInbox(String callerEmail) {
        List<Contract> contracts = contractRepository
                .findByParticipantsEmail(callerEmail.toLowerCase());

        return contracts.stream()
                .filter(c -> orEmpty(c.getParticipants()).stream()
                        .anyMatch(p -> p.getEmail().equalsIgnoreCase(callerEmail)
                                && ("unlocked".equals(p.getStatus())
                                || "in_progress".equals(p.getStatus()))))
                .map(ContractListResponse::new)
                .collect(Collectors.toList());
    }

    public List<ContractListResponse> getFlowSent(String callerEmail) {
        List<Contract> contracts = contractRepository
                .findByParticipantsEmail(callerEmail.toLowerCase());

        return contracts.stream()
                .filter(c -> orEmpty(c.getParticipants()).stream()
                        .anyMatch(p -> p.getEmail().equalsIgnoreCase(callerEmail)
                                && ("completed".equals(p.getStatus())
                                || "rejected".equals(p.getStatus()))))
                .map(ContractListResponse::new)
                .collect(Collectors.toList());
    }

    // ─── Private: Advance Workflow Engine ─────────────────────────

    private void advanceWorkflow(Contract contract) {
        if (contract.getCurrentParticipantOrder() == null) return;

        int currentOrder = contract.getCurrentParticipantOrder();

        boolean currentOrderFullyComplete = orEmpty(contract.getParticipants()).stream()
                .filter(p -> p.getOrder() == currentOrder)
                .allMatch(p -> "completed".equals(p.getStatus()));

        if (!currentOrderFullyComplete) return;

        OptionalInt nextOrderOpt = orEmpty(contract.getParticipants()).stream()
                .mapToInt(WorkflowParticipant::getOrder)
                .filter(o -> o > currentOrder)
                .min();

        if (nextOrderOpt.isEmpty()) {
            // All internal participants done
            contract.setStatus(ContractStatus.READY_FOR_SIGNATURE);
            contract.setCurrentParticipantOrder(null);
            return;
        }

        int nextOrder = nextOrderOpt.getAsInt();
        LocalDateTime now = LocalDateTime.now();

        orEmpty(contract.getParticipants()).stream()
                .filter(p -> p.getOrder() == nextOrder && "pending".equals(p.getStatus()))
                .forEach(p -> {
                    p.setStatus("unlocked");
                    p.setUnlockedAt(now);
                });

        contract.setCurrentParticipantOrder(nextOrder);

        ParticipantRole nextRole = orEmpty(contract.getParticipants()).stream()
                .filter(p -> p.getOrder() == nextOrder)
                .map(WorkflowParticipant::getRole)
                .findFirst()
                .orElse(ParticipantRole.REVIEWER);

        contract.setStatus(nextRole == ParticipantRole.REVIEWER
                ? ContractStatus.IN_REVIEW
                : ContractStatus.IN_APPROVAL);
    }

    // ─── Private: Org Gate ────────────────────────────────────────

    private void validateOrgFieldsComplete(Contract contract) {
        List<String> unfilled = getUnfilledOrgFields(contract);
        if (!unfilled.isEmpty()) {
            throw new BadRequestException(
                    "Cannot send for external signature — the following org fields are still empty: "
                            + String.join(", ", unfilled));
        }
    }

    private List<String> getUnfilledOrgFields(Contract contract) {
        if (contract.getFormFields() == null || contract.getFormFields().isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> internalPartyIds = orEmpty(contract.getParties()).stream()
                .filter(p -> p.getType() == null || p.getType() == PartyType.INTERNAL)
                .map(Party::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (internalPartyIds.isEmpty()) return Collections.emptyList();

        return contract.getFormFields().stream()
                .filter(f -> {
                    Object ap = f.get("assignedParty");
                    return ap != null && internalPartyIds.contains(ap.toString());
                })
                .filter(f -> {
                    Object val = f.get("value");
                    return val == null || val.toString().isBlank();
                })
                .map(f -> {
                    Object label = f.get("label");
                    if (label != null && !label.toString().isBlank()) return label.toString();
                    Object fieldName = f.get("fieldName");
                    if (fieldName != null && !fieldName.toString().isBlank()) return fieldName.toString();
                    Object name = f.get("name");
                    return name != null ? name.toString() : "Unnamed field";
                })
                .collect(Collectors.toList());
    }

    // ─── Private: Helpers ─────────────────────────────────────────

    private WorkflowParticipant findActiveParticipant(Contract contract, String callerEmail) {
        List<WorkflowParticipant> participants = contract.getParticipants();

        if (participants == null || participants.isEmpty()) {
            throw new BadRequestException("No participants are assigned to this contract");
        }

        boolean isAssigned = participants.stream()
                .anyMatch(p -> p.getEmail().equalsIgnoreCase(callerEmail));

        if (!isAssigned) {
            throw new BadRequestException("You are not an assigned participant for this contract");
        }

        return participants.stream()
                .filter(p -> p.getEmail().equalsIgnoreCase(callerEmail)
                        && ("unlocked".equals(p.getStatus())
                        || "in_progress".equals(p.getStatus())))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "It is not your turn yet, or you have already completed your action on this contract"));
    }

    private void verifyActiveApprover(Contract contract, String callerEmail) {
        if (contract.getStatus() != ContractStatus.IN_REVIEW
                && contract.getStatus() != ContractStatus.IN_APPROVAL) {
            throw new BadRequestException(
                    "Upload is only allowed during the review or approval stage. " +
                            "Current status: " + contract.getStatus());
        }
        findActiveParticipant(contract, callerEmail);
    }

    private void validateParticipants(List<ParticipantAssignment> assignments, String callerEmail) {
        if (assignments == null || assignments.isEmpty()) {
            throw new BadRequestException("At least one participant is required");
        }

        boolean hasApprover = assignments.stream()
                .anyMatch(a -> a.getRole() == ParticipantRole.APPROVER);
        if (!hasApprover) {
            throw new BadRequestException(
                    "At least one APPROVER is required in the participant list");
        }

        validateNoDuplicateOrders(assignments);
        validateNoDuplicateEmails(assignments);

        for (ParticipantAssignment a : assignments) {
            if (a.getEmail() == null || a.getEmail().isBlank()) {
                throw new BadRequestException("Participant email cannot be blank");
            }
            if (a.getEmail().equalsIgnoreCase(callerEmail)) {
                throw new BadRequestException(
                        "You cannot assign yourself as a participant");
            }
            if (a.getOrder() <= 0) {
                throw new BadRequestException(
                        "Order must be a positive integer, got: " + a.getOrder());
            }
        }

        // All reviewer orders must be strictly less than all approver orders
        OptionalInt maxReviewerOrder = assignments.stream()
                .filter(a -> a.getRole() == ParticipantRole.REVIEWER)
                .mapToInt(ParticipantAssignment::getOrder)
                .max();

        OptionalInt minApproverOrder = assignments.stream()
                .filter(a -> a.getRole() == ParticipantRole.APPROVER)
                .mapToInt(ParticipantAssignment::getOrder)
                .min();

        if (maxReviewerOrder.isPresent() && minApproverOrder.isPresent()
                && maxReviewerOrder.getAsInt() >= minApproverOrder.getAsInt()) {
            throw new BadRequestException(
                    "All REVIEWER orders must be lower than all APPROVER orders. " +
                            "Highest reviewer order: " + maxReviewerOrder.getAsInt() +
                            ", lowest approver order: " + minApproverOrder.getAsInt());
        }
    }

    private void validateNoDuplicateOrders(List<ParticipantAssignment> assignments) {
        long distinct = assignments.stream()
                .mapToInt(ParticipantAssignment::getOrder)
                .distinct()
                .count();
        if (distinct < assignments.size()) {
            throw new BadRequestException("Duplicate order numbers are not allowed");
        }
    }

    private void validateNoDuplicateEmails(List<ParticipantAssignment> assignments) {
        long distinct = assignments.stream()
                .map(a -> a.getEmail().toLowerCase())
                .distinct()
                .count();
        if (distinct < assignments.size()) {
            throw new BadRequestException("Duplicate participant emails are not allowed");
        }
    }

    private List<WorkflowParticipant> buildParticipantList(List<ParticipantAssignment> assignments,
                                                           int startingOrder,
                                                           String callerEmail,
                                                           LocalDateTime now) {
        List<WorkflowParticipant> result = new ArrayList<>();
        for (ParticipantAssignment a : assignments) {
            WorkflowParticipant p = new WorkflowParticipant();
            p.setEmail(a.getEmail().toLowerCase());
            p.setName(a.getName());
            p.setRole(a.getRole());
            p.setOrder(a.getOrder());
            p.setSentBy(callerEmail);
            p.setSentAt(now);
            if (a.getOrder() == startingOrder) {
                p.setStatus("unlocked");
                p.setUnlockedAt(now);
            } else {
                p.setStatus("pending");
            }
            result.add(p);
        }
        return result;
    }

    private void mergeFormFields(Contract contract,
                                 List<Map<String, Object>> incomingFields,
                                 String callerEmail) {
        List<Map<String, Object>> existing = contract.getFormFields();

        if (existing == null || existing.isEmpty()) {
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> f : incomingFields) {
                Map<String, Object> m = new HashMap<>(f);
                Object val = m.get("value");
                if (val != null && !val.toString().isBlank()) {
                    m.put("filledBy", callerEmail);
                }
                copy.add(m);
            }
            contract.setFormFields(copy);
            return;
        }

        // Build lookup of incoming fields by name
        Map<String, Map<String, Object>> incomingByName = new HashMap<>();
        for (Map<String, Object> f : incomingFields) {
            Object key = f.get("fieldName") != null ? f.get("fieldName") : f.get("name");
            if (key != null) incomingByName.put(key.toString(), f);
        }

        // Rebuild list with mutable maps and updated values
        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> existingField : existing) {
            Map<String, Object> mutable = new HashMap<>(existingField);
            Object key = mutable.get("fieldName") != null
                    ? mutable.get("fieldName") : mutable.get("name");
            if (key != null && incomingByName.containsKey(key.toString())) {
                Object val = incomingByName.get(key.toString()).get("value");
                if (val != null) {
                    mutable.put("value", val);
                    mutable.put("filledBy", callerEmail);
                }
            }
            updated.add(mutable);
        }
        contract.setFormFields(updated);
    }

    private void appendModificationRequest(Contract contract, String requestedBy,
                                           String role, String message) {
        if (contract.getModificationRequests() == null) {
            contract.setModificationRequests(new ArrayList<>());
        }
        ModificationRequest entry = new ModificationRequest();
        entry.setRequestedBy(requestedBy);
        entry.setRole(role);
        entry.setMessage(message);
        entry.setRequestedAt(LocalDateTime.now());
        contract.getModificationRequests().add(entry);
    }

    private void syncVersionToAllPendingRequests(String contractId, int newVersion) {
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("contractId").is(contractId)
                        .and("status").is("pending")),
                Update.update("contractVersion", newVersion),
                SignatureRequest.class
        );
    }

    private boolean objectExistsInMinio(String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
