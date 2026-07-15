package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.config.CustomMinioClient;
import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.ConflictException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SignatureService {

    private static final Logger log = LoggerFactory.getLogger(SignatureService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final ContractRepository contractRepository;
    private final SignatureRequestRepository signatureRequestRepository;
    private final AutoAdvanceService autoAdvanceService;
    private final EmailService emailService;
    private final MinioClient minioClient;
    private final CustomMinioClient customMinioClient;
    private final MongoTemplate mongoTemplate;
    private final ContractRenewalService contractRenewalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.signing-link-expiry-days:7}")
    private int expiryDays;

    public SignatureService(ContractRepository contractRepository,
                            SignatureRequestRepository signatureRequestRepository,
                            AutoAdvanceService autoAdvanceService,
                            EmailService emailService,
                            MinioClient minioClient,
                            CustomMinioClient customMinioClient,
                            MongoTemplate mongoTemplate,
                            ContractRenewalService contractRenewalService) {
        this.contractRepository = contractRepository;
        this.signatureRequestRepository = signatureRequestRepository;
        this.autoAdvanceService = autoAdvanceService;
        this.emailService = emailService;
        this.minioClient = minioClient;
        this.customMinioClient = customMinioClient;
        this.mongoTemplate = mongoTemplate;
        this.contractRenewalService = contractRenewalService;
    }

    // ─────────────────────────────────────────────────────────────
    // SUBMIT FOR SIGNATURE
    // ─────────────────────────────────────────────────────────────

    public ContractResponse submitForSignature(String contractId,
                                               SubmitForSignatureRequest request,
                                               String callerEmail) {
        Contract contract = loadContractForOwner(contractId, callerEmail);

        ContractStatus status = contract.getStatus();
        if (status != ContractStatus.DRAFT
                && status != ContractStatus.READY_FOR_SIGNATURE
                && status != ContractStatus.IN_SIGNATURE) {
            if (status == ContractStatus.SIGNED_BY_EVERYONE || status == ContractStatus.SIGNED) {
                // Allow re-share only when there are parties that still have no signer assigned
                Set<String> assignedPartyIds = new HashSet<>();
                orEmpty(contract.getExternalSigners())
                        .forEach(s -> { if (s.getPartyId() != null) assignedPartyIds.add(s.getPartyId()); });
                orEmpty(contract.getInternalSigners())
                        .forEach(s -> { if (s.getPartyId() != null) assignedPartyIds.add(s.getPartyId()); });
                boolean hasUnassignedParties = orEmpty(contract.getParties()).stream()
                        .anyMatch(p -> !assignedPartyIds.contains(p.getId()));
                if (!hasUnassignedParties) {
                    throw new BadRequestException(
                            "Cannot add signers — the contract has already completed the signing workflow");
                }
                // Has unassigned parties — fall through and start a new signing round
            } else {
                throw new BadRequestException(
                        "Contract must be ready for signature or already in signature workflow. Current status: " + status);
            }
        }

        if (!contract.isFileUploaded()) {
            throw new BadRequestException(
                    "Contract file must be uploaded before sending for signature");
        }

        if (status == ContractStatus.DRAFT && contract.getRenewedFromId() != null) {
            contractRenewalService.linkRenewalIfApplicable(contract);
        }

        List<SignerAssignmentDto> assignments = request.getAssignments();

        for (SignerAssignmentDto a : assignments) {
            if (!"internal".equals(a.getType()) && !"external".equals(a.getType())) {
                throw new BadRequestException("Assignment type must be \"internal\" or \"external\"");
            }
            if (a.getEmail() == null || a.getEmail().isBlank()) {
                throw new BadRequestException("Signer email is required for party " + a.getPartyLabel());
            }
            if ("external".equals(a.getType()) && !EMAIL_PATTERN.matcher(a.getEmail()).matches()) {
                throw new BadRequestException("Invalid email format: " + a.getEmail());
            }
        }

        Set<String> emails = new HashSet<>();
        for (SignerAssignmentDto a : assignments) {
            if (!emails.add(a.getEmail().toLowerCase())) {
                throw new BadRequestException("Duplicate signer email: " + a.getEmail());
            }
        }

        if (emails.contains(callerEmail.toLowerCase())) {
            throw new BadRequestException("You cannot assign yourself as a signer");
        }

        Set<String> partyIds = new HashSet<>();
        for (SignerAssignmentDto a : assignments) {
            if (!partyIds.add(a.getPartyId())) {
                throw new BadRequestException("Party " + a.getPartyLabel() + " is already assigned");
            }
        }

        Set<Integer> submittedOrders = new HashSet<>();
        for (SignerAssignmentDto a : assignments) {
            if (!submittedOrders.add(a.getOrder())) {
                throw new BadRequestException(
                        "Order number " + a.getOrder() + " is assigned to more than one party in this submission. "
                                + "Each signer must have a unique order.");
            }
        }

        List<ExternalSigner> existingExternal = orEmpty(contract.getExternalSigners());
        List<InternalSigner> existingInternal = orEmpty(contract.getInternalSigners());
        boolean isReShare = !existingExternal.isEmpty() || !existingInternal.isEmpty();

        int startingOrder;
        // appendOnly = true means new signers are appended to an active in-progress chain.
        // They stay "pending" and currentSigningOrder is untouched —
        // AutoAdvanceService unlocks them naturally when the chain reaches their order.
        boolean appendOnly = false;

        if (isReShare) {
            // Orders are globally unique across the entire contract — no round concept.
            // New signers must always continue the chain at an order above the current maximum.
            int maxExistingOrder = existingExternal.stream().mapToInt(ExternalSigner::getOrder).max().orElse(0);
            maxExistingOrder = Math.max(maxExistingOrder,
                    existingInternal.stream().mapToInt(InternalSigner::getOrder).max().orElse(0));

            boolean hasInProgress = existingExternal.stream()
                    .anyMatch(s -> !"completed".equals(s.getStatus()))
                    || existingInternal.stream()
                    .anyMatch(s -> !"completed".equals(s.getStatus()));

            int newMinOrder = assignments.stream().mapToInt(SignerAssignmentDto::getOrder).min().orElse(1);

            if (newMinOrder <= maxExistingOrder) {
                if (hasInProgress) {
                    throw new BadRequestException(
                            "Order " + newMinOrder + " conflicts with an in-progress signer. "
                                    + "New signers must have order greater than " + maxExistingOrder
                                    + " because signers at orders 1–" + maxExistingOrder + " are still in progress.");
                } else {
                    throw new BadRequestException(
                            "Order " + newMinOrder + " is already used. "
                                    + "New signers must continue the chain at an order greater than " + maxExistingOrder + ".");
                }
            }

            startingOrder = newMinOrder;
            if (hasInProgress) {
                appendOnly = true;  // chain is active — new signers wait for auto-advance to reach them
            }
            // if all completed, appendOnly stays false → signer at startingOrder unlocks immediately
        } else {
            int newMinOrder = assignments.stream().mapToInt(SignerAssignmentDto::getOrder).min().orElse(1);
            if (newMinOrder != 1) {
                throw new BadRequestException(
                        "The signing chain must start at order 1. Lowest order provided: " + newMinOrder);
            }
            startingOrder = 1;
        }

        LocalDateTime now = LocalDateTime.now();
        String senderName = (request.getSenderName() != null && !request.getSenderName().isBlank())
                ? request.getSenderName() : callerEmail;

        List<ExternalSigner> newExternals = new ArrayList<>(existingExternal);
        List<InternalSigner> newInternals = new ArrayList<>(existingInternal);
        List<PartyCompletion> newCompletions = new ArrayList<>(orEmpty(contract.getPartyCompletions()));

        for (SignerAssignmentDto a : assignments) {
            // appendOnly: existing chain is active — new signer is never unlocked immediately
            boolean unlocked = !appendOnly && (a.getOrder() == startingOrder);
            String signerStatus = unlocked ? "unlocked" : "pending";

            if ("external".equals(a.getType())) {
                ExternalSigner ext = new ExternalSigner();
                ext.setEmail(a.getEmail());
                ext.setName(a.getName());
                ext.setPartyId(a.getPartyId());
                ext.setPartyLabel(a.getPartyLabel());
                ext.setOrder(a.getOrder());
                ext.setToken(generateToken());
                ext.setStatus(signerStatus);
                ext.setSentAt(now);
                if (unlocked) ext.setUnlockedAt(now);
                newExternals.add(ext);

            } else {
                InternalSigner intSigner = new InternalSigner();
                intSigner.setUserId(a.getUserId());
                intSigner.setEmail(a.getEmail());
                intSigner.setName(a.getName());
                intSigner.setPartyId(a.getPartyId());
                intSigner.setPartyLabel(a.getPartyLabel());
                intSigner.setOrder(a.getOrder());
                intSigner.setStatus(signerStatus);
                intSigner.setAssignedAt(now);
                if (unlocked) intSigner.setUnlockedAt(now);
                newInternals.add(intSigner);
            }

            PartyCompletion pc = new PartyCompletion();
            pc.setPartyId(a.getPartyId());
            pc.setPartyLabel(a.getPartyLabel());
            pc.setOrder(a.getOrder());
            pc.setAssigneeType(a.getType());
            pc.setAssigneeEmail(a.getEmail());
            pc.setAssigneeName(a.getName());
            pc.setStatus(signerStatus);
            newCompletions.add(pc);
        }

        contract.setExternalSigners(newExternals);
        contract.setInternalSigners(newInternals);
        contract.setPartyCompletions(newCompletions);
        contract.setStatus(ContractStatus.IN_SIGNATURE);
        contract.setSignatureFlowStatus("pending_signatures");
        // appendOnly: keep the existing currentSigningOrder intact so auto-advance
        // continues from where it left off and reaches the new signer in sequence
        if (!appendOnly) {
            contract.setCurrentSigningOrder(startingOrder);
        }
        contract.setSignatureSenderName(senderName);
        contract.setUpdatedAt(now);

        if (!isReShare) {
            contract.setVersion(0);
        }

        contractRepository.save(contract);

        for (ExternalSigner ext : newExternals) {
            if (ext.getOrder() == startingOrder) {
                SignatureRequest sr = new SignatureRequest();
                sr.setToken(ext.getToken());
                sr.setContractId(contractId);
                sr.setContractTitle(contract.getTitle());
                sr.setSignerEmail(ext.getEmail());
                sr.setSignerName(ext.getName());
                sr.setCreatedBy(callerEmail);
                sr.setCreatedByName(senderName);
                sr.setCreatedAt(now);
                sr.setExpiresAt(now.plusDays(expiryDays));
                sr.setStatus("pending");
                sr.setAssignedParty(List.of(ext.getPartyId()));
                sr.setAssignedPartyLabel(List.of(ext.getPartyLabel()));
                sr.setOrder(ext.getOrder());
                sr.setFormFields(contract.getFormFields());
                sr.setXfdfData(contract.getXfdfData());
                sr.setFieldValues(contract.getFieldValues());
                sr.setContractVersion(contract.getVersion());
                signatureRequestRepository.save(sr);

                String signingUrl  = baseUrl + "/sign/" + ext.getToken();
                String partyColor = resolvePartyColor(contract, ext.getPartyId());
                emailService.sendSignatureRequestEmail(
                        ext.getEmail(), ext.getName(), senderName, callerEmail,
                        contract.getTitle(), signingUrl, sr.getExpiresAt(),
                        ext.getPartyLabel(), partyColor
                );
            }
        }

        return new ContractResponse(contract);
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: GET SIGNING DATA
    // ─────────────────────────────────────────────────────────────

    public SignatureRequestResponse getSigningData(String token) {
        SignatureRequest sr = loadActiveSignatureRequest(token);

        Contract contract = contractRepository.findById(sr.getContractId())
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (sr.getContractVersion() != contract.getVersion()) {
            sr.setContractVersion(contract.getVersion());
            signatureRequestRepository.save(sr);
        }

        SignatureRequestResponse response = new SignatureRequestResponse();
        response.setToken(sr.getToken());
        response.setContractId(sr.getContractId());
        response.setContractTitle(sr.getContractTitle());
        response.setSignerEmail(sr.getSignerEmail());
        response.setSignerName(sr.getSignerName());
        response.setStatus(sr.getStatus());
        response.setExpiresAt(sr.getExpiresAt());
        // Frontend expects a single String, not a List — extracts [0] from storage
        String assignedPartyId = (sr.getAssignedParty() != null && !sr.getAssignedParty().isEmpty())
                ? sr.getAssignedParty().get(0) : null;
        response.setAssignedParty(assignedPartyId);
        response.setAssignedPartyLabel(sr.getAssignedPartyLabel());
        response.setFormFields(contract.getFormFields());
        response.setXfdfData(contract.getXfdfData());
        response.setFieldValues(contract.getFieldValues());
        response.setParties(contract.getParties());
        response.setContractVersion(sr.getContractVersion());
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: GET PRESIGNED URL FOR PDF DOWNLOAD
    // ─────────────────────────────────────────────────────────────

    public String getSigningPdfUrl(String token) throws Exception {
        SignatureRequest sr = loadActiveSignatureRequest(token);
        String contractId = sr.getContractId();

        String signedKey = "contracts/" + contractId + "_signed.pdf";
        String originalKey = "contracts/" + contractId + ".pdf";
        String objectKey = objectExistsInMinio(signedKey) ? signedKey : originalKey;

        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectKey)
                        .expiry(15, TimeUnit.MINUTES)
                        .build()
        );
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: MARK AS VIEWED
    // ─────────────────────────────────────────────────────────────

    public void markAsViewed(String token) {
        signatureRequestRepository.findByToken(token).ifPresent(sr -> {
            Contract contract = contractRepository.findById(sr.getContractId()).orElse(null);
            if (contract == null) return;

            contract.getExternalSigners().stream()
                    .filter(s -> token.equals(s.getToken()) && "unlocked".equals(s.getStatus()))
                    .findFirst()
                    .ifPresent(s -> {
                        s.setStatus("viewed");
                        s.setViewedAt(LocalDateTime.now());
                        contractRepository.save(contract);
                    });
        });
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: EXTERNAL SIGNER — INITIATE CHUNKED UPLOAD
    // ─────────────────────────────────────────────────────────────

    public SignUploadInitResponse initiateSignedPdfUpload(String token) throws Exception {
        SignatureRequest sr = loadActiveSignatureRequest(token);
        String objectKey = "contracts/" + sr.getContractId() + "_signed.pdf";
        String uploadId = customMinioClient.startMultipartUpload(bucketName, objectKey);
        return new SignUploadInitResponse(uploadId);
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: EXTERNAL SIGNER — PRESIGNED URL FOR ONE PART
    // ─────────────────────────────────────────────────────────────

    public String generatePresignedSignPart(String token, String uploadId, int partNumber) throws Exception {
        SignatureRequest sr = loadActiveSignatureRequest(token);
        String objectKey = "contracts/" + sr.getContractId() + "_signed.pdf";

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

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: EXTERNAL SIGNER — COMPLETE UPLOAD + BUSINESS LOGIC
    // ─────────────────────────────────────────────────────────────

    public SignCompleteResponse completeSignedPdfUpload(String token,
                                                        SignUploadCompleteRequest request) throws Exception {
        SignatureRequest sr = signatureRequestRepository.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Signing request not found"));

        boolean isAutoSave = request.isAutoSave();

        if (!isAutoSave) {
            validateTokenActive(sr);
        } else {
            if (LocalDateTime.now().isAfter(sr.getExpiresAt())) {
                throw new BadRequestException("This signing link has expired");
            }
        }

        Contract contract = contractRepository.findById(sr.getContractId())
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (!isAutoSave) {
            if (sr.getContractVersion() != contract.getVersion()) {
                throw new ConflictException(
                        "Contract has been modified since you started signing. "
                                + "Please reload the page and try again.");
            }
            if (request.getUploadId() == null || request.getParts() == null || request.getParts().isEmpty()) {
                throw new BadRequestException("Signed PDF upload is required — complete chunked upload first");
            }
        }

        // Finalize the multipart upload in MinIO (PDF is already there in parts)
        if (request.getUploadId() != null && request.getParts() != null && !request.getParts().isEmpty()) {
            String objectKey = "contracts/" + sr.getContractId() + "_signed.pdf";
            List<Part> parts = new ArrayList<>();
            for (SignUploadCompleteRequest.Part p : request.getParts()) {
                parts.add(new Part(p.getPartNumber(), p.getETag()));
            }
            customMinioClient.finishMultipartUpload(bucketName, objectKey, request.getUploadId(),
                    parts.toArray(new Part[0]));
            contract.setSignedPdfKey(objectKey);
        }

        if (request.getFormFields() != null && !request.getFormFields().isEmpty()) {
            mergeFormFields(contract, request.getFormFields());
        }
        if (request.getFieldValues() != null) {
            contract.setFieldValues(request.getFieldValues());
        }

        if (isAutoSave) {
            sr.setContractVersion(contract.getVersion());
            signatureRequestRepository.save(sr);
            contractRepository.save(contract);
            return SignCompleteResponse.ok("Progress saved");
        }

        // Final submit — mark signed
        sr.setStatus("signed");
        sr.setSignedAt(LocalDateTime.now());
        signatureRequestRepository.save(sr);

        orEmpty(contract.getExternalSigners()).stream()
                .filter(s -> token.equals(s.getToken()))
                .findFirst()
                .ifPresent(s -> {
                    s.setStatus("completed");
                    s.setCompletedAt(LocalDateTime.now());
                });

        updatePartyCompletion(contract, sr.getSignerEmail());
        contract.setVersion(contract.getVersion() + 1);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);

        syncVersionToAllPendingRequests(sr.getContractId(), contract.getVersion());

        try {
            autoAdvanceService.advance(sr.getContractId());
        } catch (Exception e) {
            log.error("Auto-advance failed for contract {}: {}", sr.getContractId(), e.getMessage());
        }

        return SignCompleteResponse.ok("Signature submitted successfully");
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: EXTERNAL SIGNER — ABORT UPLOAD
    // ─────────────────────────────────────────────────────────────

    public void abortSignedPdfUpload(String token, String uploadId) throws Exception {
        SignatureRequest sr = signatureRequestRepository.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Signing request not found"));
        String objectKey = "contracts/" + sr.getContractId() + "_signed.pdf";
        customMinioClient.cancelMultipartUpload(bucketName, objectKey, uploadId);
    }

    // ─────────────────────────────────────────────────────────────
    // INTERNAL SIGNER — INITIATE CHUNKED UPLOAD (JWT)
    // ─────────────────────────────────────────────────────────────

    public SignUploadInitResponse initiateInternalSignedPdfUpload(String contractId,
                                                                  String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        verifyInternalSignerUnlocked(contract, callerEmail);
        String objectKey = "contracts/" + contractId + "_signed.pdf";
        String uploadId = customMinioClient.startMultipartUpload(bucketName, objectKey);
        return new SignUploadInitResponse(uploadId);
    }

    // ─────────────────────────────────────────────────────────────
    // INTERNAL SIGNER — PRESIGNED URL FOR ONE PART (JWT)
    // ─────────────────────────────────────────────────────────────

    public String generatePresignedInternalSignPart(String contractId, String uploadId,
                                                    int partNumber, String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        verifyInternalSignerUnlocked(contract, callerEmail);

        String objectKey = "contracts/" + contractId + "_signed.pdf";
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

    // ─────────────────────────────────────────────────────────────
    // INTERNAL SIGNER — ABORT UPLOAD (JWT)
    // ─────────────────────────────────────────────────────────────

    public void abortInternalSignedPdfUpload(String contractId, String uploadId,
                                             String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        verifyInternalSignerUnlocked(contract, callerEmail);
        String objectKey = "contracts/" + contractId + "_signed.pdf";
        customMinioClient.cancelMultipartUpload(bucketName, objectKey, uploadId);
    }

    // ─────────────────────────────────────────────────────────────
    // INTERNAL SIGNER — COMPLETE (JWT)
    // ─────────────────────────────────────────────────────────────

    public SignCompleteResponse completeInternalSigning(String contractId,
                                                        InternalSignCompleteRequest request,
                                                        String callerEmail) throws Exception {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        InternalSigner signer = orEmpty(contract.getInternalSigners()).stream()
                .filter(s -> s.getEmail().equalsIgnoreCase(callerEmail))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("You are not assigned as a signer on this contract"));

        if ("completed".equals(signer.getStatus())) {
            throw new BadRequestException("You have already completed your signature on this contract");
        }
        if (!"unlocked".equals(signer.getStatus())) {
            throw new BadRequestException(
                    "It is not yet your turn to sign. Please wait for previous signers to complete.");
        }
        if (request.getUploadId() == null || request.getParts() == null || request.getParts().isEmpty()) {
            throw new BadRequestException("Signed PDF is required — complete chunked upload first");
        }

        // Finalize the multipart upload in MinIO
        String objectKey = "contracts/" + contractId + "_signed.pdf";
        List<Part> parts = new ArrayList<>();
        for (InternalSignCompleteRequest.Part p : request.getParts()) {
            parts.add(new Part(p.getPartNumber(), p.getETag()));
        }
        customMinioClient.finishMultipartUpload(bucketName, objectKey, request.getUploadId(),
                parts.toArray(new Part[0]));
        contract.setSignedPdfKey(objectKey);

        if (request.getFormFields() != null && !request.getFormFields().isEmpty()) {
            mergeFormFields(contract, request.getFormFields());
        }
        if (request.getFieldValues() != null) {
            contract.setFieldValues(request.getFieldValues());
        }

        signer.setStatus("completed");
        signer.setCompletedAt(LocalDateTime.now());
        updatePartyCompletion(contract, callerEmail);

        contract.setVersion(contract.getVersion() + 1);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);

        syncVersionToAllPendingRequests(contractId, contract.getVersion());

        try {
            autoAdvanceService.advance(contractId);
        } catch (Exception e) {
            log.error("Auto-advance failed for contract {}: {}", contractId, e.getMessage());
        }

        return SignCompleteResponse.ok("Signature submitted successfully");
    }

    // ─────────────────────────────────────────────────────────────
    // FINALIZE (contractor — after all_completed)
    // ─────────────────────────────────────────────────────────────

    public ContractResponse finalizeContract(String contractId, String callerEmail) {
        Contract contract = loadContractForOwner(contractId, callerEmail);

        if ("finalized".equals(contract.getSignatureFlowStatus())) {
            throw new BadRequestException("Contract has already been finalized");
        }
        if (!"all_completed".equals(contract.getSignatureFlowStatus())) {
            throw new BadRequestException(
                    "Cannot finalize — not all parties have signed yet. Current status: "
                            + contract.getSignatureFlowStatus());
        }

        String signedKey = "contracts/" + contractId + "_signed.pdf";
        String finalKey = "contracts/" + contractId + "_final.pdf";

        // TEMP DIAGNOSTIC TIMING — remove once the finalize-latency investigation is done
        long t0 = System.currentTimeMillis();
        byte[] finalPdfBytes = readFromMinio(signedKey);
        long t1 = System.currentTimeMillis();
        log.info("[finalize-timing] contract={} readFromMinio took {} ms ({} bytes)",
                contractId, t1 - t0, finalPdfBytes.length);

        writeRawPdfToMinio(finalKey, finalPdfBytes);
        long t2 = System.currentTimeMillis();
        log.info("[finalize-timing] contract={} writeRawPdfToMinio took {} ms", contractId, t2 - t1);

        contract.setFinalPdfKey(finalKey);
        contract.setStatus(ContractStatus.SIGNED);
        contract.setSignatureFlowStatus("finalized");
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
        long t3 = System.currentTimeMillis();
        log.info("[finalize-timing] contract={} contractRepository.save took {} ms", contractId, t3 - t2);

        if (contract.getRenewedFromId() != null) {
            contractRepository.findById(contract.getRenewedFromId()).ifPresent(original -> {
                if (contract.getId().equals(original.getRenewedContractId())) {
                    original.setRenewalStatus("completed");
                    original.setUpdatedAt(LocalDateTime.now());
                    contractRepository.save(original);
                }
            });
        }
        long t4 = System.currentTimeMillis();
        log.info("[finalize-timing] contract={} renewal-completion hook took {} ms", contractId, t4 - t3);

        String downloadUrl = baseUrl + "/contracts/" + contractId + "/final-pdf";

        orEmpty(contract.getExternalSigners()).forEach(s ->
                emailService.sendSignedCopyEmail(s.getEmail(), s.getName(),
                        contract.getTitle(), downloadUrl));

        orEmpty(contract.getInternalSigners()).forEach(s ->
                emailService.sendSignedCopyEmail(s.getEmail(), s.getName(),
                        contract.getTitle(), downloadUrl));
        long t5 = System.currentTimeMillis();
        log.info("[finalize-timing] contract={} email sending took {} ms", contractId, t5 - t4);
        log.info("[finalize-timing] contract={} TOTAL finalizeContract took {} ms", contractId, t5 - t0);

        return new ContractResponse(contract);
    }

    // ─────────────────────────────────────────────────────────────
    // SIGNATURE STATUS
    // ─────────────────────────────────────────────────────────────

    public SignatureStatusResponse getSignatureStatus(String contractId, String callerEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        boolean isOwner = callerEmail.equalsIgnoreCase(contract.getCreatedBy());
        boolean isReviewer = orEmpty(contract.getReviewers()).stream()
                .anyMatch(r -> r.getEmail().equalsIgnoreCase(callerEmail));
        boolean isApprover = contract.getApprover() != null
                && callerEmail.equalsIgnoreCase(contract.getApprover().getEmail());
        boolean isInternalSigner = orEmpty(contract.getInternalSigners()).stream()
                .anyMatch(s -> s.getEmail().equalsIgnoreCase(callerEmail));

        if (!isOwner && !isReviewer && !isApprover && !isInternalSigner) {
            throw new NotFoundException("Contract not found");
        }

        SignatureStatusResponse response = new SignatureStatusResponse();
        response.setContractId(contractId);
        response.setSignatureFlowStatus(contract.getSignatureFlowStatus());
        response.setCurrentSigningOrder(contract.getCurrentSigningOrder());
        response.setExternalSigners(contract.getExternalSigners());
        response.setInternalSigners(contract.getInternalSigners());
        response.setPartyCompletions(contract.getPartyCompletions());
        response.setVersion(contract.getVersion());
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    private String generateToken() {
        return "sig_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "");
    }

    private Contract loadContractForOwner(String contractId, String callerEmail) {
        Contract c = contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        if (!callerEmail.equalsIgnoreCase(c.getCreatedBy())) {
            throw new NotFoundException("Contract not found");
        }
        return c;
    }

    private SignatureRequest loadActiveSignatureRequest(String token) {
        SignatureRequest sr = signatureRequestRepository.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Signing request not found"));
        validateTokenActive(sr);
        return sr;
    }

    private void validateTokenActive(SignatureRequest sr) {
        if ("signed".equals(sr.getStatus())) {
            throw new BadRequestException("You have already submitted your signature");
        }
        if (LocalDateTime.now().isAfter(sr.getExpiresAt())) {
            throw new BadRequestException(
                    "This signing link expired on " + sr.getExpiresAt().toLocalDate());
        }
    }

    private void verifyInternalSignerUnlocked(Contract contract, String callerEmail) {
        InternalSigner signer = orEmpty(contract.getInternalSigners()).stream()
                .filter(s -> s.getEmail().equalsIgnoreCase(callerEmail))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("You are not assigned as a signer on this contract"));
        if ("completed".equals(signer.getStatus())) {
            throw new BadRequestException("You have already completed your signature");
        }
        if (!"unlocked".equals(signer.getStatus())) {
            throw new BadRequestException("It is not yet your turn to sign");
        }
    }

    private void updatePartyCompletion(Contract contract, String email) {
        orEmpty(contract.getPartyCompletions()).stream()
                .filter(p -> p.getAssigneeEmail() != null
                        && p.getAssigneeEmail().equalsIgnoreCase(email)
                        && !"completed".equals(p.getStatus()))
                .findFirst()
                .ifPresent(p -> {
                    p.setStatus("completed");
                    p.setCompletedBy(email);
                    p.setCompletedAt(LocalDateTime.now());
                });
    }

    private void syncVersionToAllPendingRequests(String contractId, int newVersion) {
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("contractId").is(contractId)
                        .and("status").is("pending")),
                Update.update("contractVersion", newVersion),
                SignatureRequest.class
        );
    }

    private void mergeFormFields(Contract contract, List<Map<String, Object>> signerFields) {
        List<Map<String, Object>> existing = contract.getFormFields();
        if (existing == null || existing.isEmpty()) {
            contract.setFormFields(signerFields);
            return;
        }

        // Index existing fields by both key variants Apryse may use
        Map<String, Map<String, Object>> metaByName = new HashMap<>();
        for (Map<String, Object> f : existing) {
            Object key = f.get("fieldName") != null ? f.get("fieldName") : f.get("name");
            if (key != null) metaByName.put(key.toString(), f);
        }

        // Party-owned fields that must never be overwritten by a signer submission
        List<String> protectedKeys = List.of(
                "assignedParty", "partyLabel", "partyColor", "profileKey", "lockedBy");

        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> sf : signerFields) {
            Object key = sf.get("fieldName") != null ? sf.get("fieldName") : sf.get("name");
            if (key != null && metaByName.containsKey(key.toString())) {
                Map<String, Object> authoritative = metaByName.get(key.toString());
                Map<String, Object> m = new HashMap<>(sf);
                for (String pk : protectedKeys) {
                    if (authoritative.containsKey(pk)) m.put(pk, authoritative.get(pk));
                }
                merged.add(m);
            } else {
                merged.add(sf);
            }
        }
        contract.setFormFields(merged);
    }

    private void writeRawPdfToMinio(String objectKey, byte[] pdfBytes) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                            .contentType("application/pdf")
                            .build()
            );
        } catch (Exception e) {
            log.error("MinIO write failed for {}: {}", objectKey, e.getMessage());
            throw new RuntimeException("Failed to store PDF: " + e.getMessage());
        }
    }

    private byte[] readFromMinio(String objectKey) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucketName).object(objectKey).build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            log.error("MinIO read failed for {}: {}", objectKey, e.getMessage());
            throw new NotFoundException("PDF not found");
        }
    }

    private boolean objectExistsInMinio(String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucketName).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            log.warn("MinIO stat failed for {}: {}", objectKey, e.getMessage());
            return false;
        }
    }

    // Treats 0 as round 1 — handles legacy MongoDB documents that pre-date the signingRound field
    private static int roundOf(int r) { return r <= 0 ? 1 : r; }

    private String resolvePartyColor(Contract contract, String partyId) {
        if (contract.getParties() == null || partyId == null) return "#0e7c6b";
        return contract.getParties().stream()
                .filter(p -> partyId.equals(p.getId()))
                .map(Party::getColor)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse("#0e7c6b");
    }

    private <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
