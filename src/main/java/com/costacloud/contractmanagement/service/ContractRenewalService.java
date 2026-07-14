package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.ContractResponse;
import com.costacloud.contractmanagement.dto.RenewalRequest;
import com.costacloud.contractmanagement.exception.BadRequestException;
import com.costacloud.contractmanagement.exception.ConflictException;
import com.costacloud.contractmanagement.exception.NotFoundException;
import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import com.costacloud.contractmanagement.model.Party;
import com.costacloud.contractmanagement.model.Template;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.TemplateRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ContractRenewalService {

    private final ContractRepository contractRepository;
    private final TemplateRepository templateRepository;

    public ContractRenewalService(ContractRepository contractRepository,
                                  TemplateRepository templateRepository) {
        this.contractRepository = contractRepository;
        this.templateRepository = templateRepository;
    }

    // ─── Create Renewal Draft ─────────────────────────────────────────────────

    /**
     * Creates a renewal draft linked to the original contract.
     * The original is NOT updated at this point — that happens only in confirmRenewal.
     * Returns the full ContractResponse so the frontend can open the editor immediately.
     */
    public ContractResponse createRenewal(String originalId, RenewalRequest request, String email) {

        // 1. Fetch and validate original
        Contract original = contractRepository.findById(originalId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        // 2. Ownership check — same masking as all other endpoints (404, not 403)
        if (!original.getCreatedBy().equals(email)) {
            throw new NotFoundException("Contract not found");
        }

        // 3. Status eligibility — only EXPIRING or EXPIRED contracts can be renewed
        ContractStatus effective = computeEffectiveStatus(original);
        if (effective != ContractStatus.EXPIRING && effective != ContractStatus.EXPIRED) {
            throw new BadRequestException(
                    "Only contracts with status EXPIRING or EXPIRED can be renewed. " +
                    "Current effective status: " + effective.name());
        }

        // 4. Conflict guard — block if a renewal is already confirmed for this contract
        if ("in_progress".equals(original.getRenewalStatus())) {
            throw new ConflictException(
                    "A renewal for this contract is already in progress.");
        }

        // 5. Idempotency — if a draft was already started (but not yet confirmed), return it
        Optional<Contract> existingDraft = contractRepository.findFirstByRenewedFromId(originalId);
        if (existingDraft.isPresent()) {
            return new ContractResponse(existingDraft.get());
        }

        // 6. Date validation
        if (original.getEndDate() != null && !request.getStartDate().isAfter(original.getEndDate())) {
            throw new BadRequestException(
                    "Renewal start date must be after the original contract's end date (" +
                    original.getEndDate() + ")");
        }
        if (!request.getEndDate().isAfter(request.getStartDate())) {
            throw new BadRequestException("Renewal end date must be after the start date");
        }

        // 7. Fetch and validate template
        Template template = templateRepository.findById(request.getTemplateId())
                .orElseThrow(() -> new NotFoundException("Template not found"));

        if (!template.isFileUploaded()) {
            throw new BadRequestException(
                    "The selected template does not have a PDF uploaded yet");
        }

        // 8. Build the renewal draft
        Contract renewal = new Contract();
        renewal.setTitle(generateRenewalTitle(original));
        renewal.setClient(original.getClient());
        renewal.setDescription(original.getDescription());
        renewal.setCategory(original.getCategory());
        renewal.setFolderId(original.getFolderId());
        renewal.setStartDate(request.getStartDate());
        renewal.setEndDate(request.getEndDate());
        renewal.setTemplateId(template.getId());
        renewal.setTemplateName(template.getName());
        renewal.setTemplateFileName(template.getFileName());
        renewal.setFormFields(enrichAndClearFormFields(template.getFormFields(), template.getParties()));
        renewal.setHasFormFields(template.getFormFields() != null && !template.getFormFields().isEmpty());
        renewal.setParties(convertParties(template.getParties()));
        renewal.setXfdfData(null);
        renewal.setFieldValues(null);
        renewal.setStatus(ContractStatus.DRAFT);
        renewal.setFileUploaded(false);
        renewal.setRenewedFromId(originalId);
        renewal.setRenewalNotes(request.getNotes());
        renewal.setCreatedBy(email);
        renewal.setCreatedAt(LocalDateTime.now());
        renewal.setUpdatedAt(LocalDateTime.now());

        contractRepository.save(renewal);
        return new ContractResponse(renewal);
    }

    // ─── Confirm Renewal ──────────────────────────────────────────────────────

    /**
     * Called when the user clicks "Submit Renewal" after uploading the PDF.
     * Marks the original contract with the renewal link.
     * Idempotent: calling it twice for the same renewal is a no-op.
     */
    public void confirmRenewal(String renewalId, String email) {

        // 1. Fetch and own the renewal draft
        Contract renewal = contractRepository.findById(renewalId)
                .orElseThrow(() -> new NotFoundException("Renewal contract not found"));

        if (!renewal.getCreatedBy().equals(email)) {
            throw new NotFoundException("Renewal contract not found");
        }

        // 2. Must actually be a renewal (not a regular contract)
        if (renewal.getRenewedFromId() == null) {
            throw new BadRequestException("This contract is not a renewal");
        }

        // 3. PDF must be uploaded before confirming
        if (!renewal.isFileUploaded()) {
            throw new BadRequestException(
                    "Please upload the renewal contract PDF before submitting");
        }

        linkToOriginal(renewal);
    }

    /**
     * Called automatically by the workflow/signature services when a DRAFT contract
     * that happens to be a renewal is about to leave DRAFT (fresh flow submit or direct
     * send-for-signature). No-op for ordinary contracts. This ensures the original
     * contract's renewal linkage can never be skipped by forgetting to call
     * confirmRenewal() explicitly.
     */
    public void linkRenewalIfApplicable(Contract renewal) {
        if (renewal.getRenewedFromId() == null) {
            return;
        }
        if (!renewal.isFileUploaded()) {
            throw new BadRequestException(
                    "Please upload the renewal contract PDF before submitting");
        }
        linkToOriginal(renewal);
    }

    private void linkToOriginal(Contract renewal) {
        // Fetch original
        Contract original = contractRepository.findById(renewal.getRenewedFromId())
                .orElseThrow(() -> new NotFoundException("Original contract not found"));

        // Idempotency — already confirmed for this renewal
        if (renewal.getId().equals(original.getRenewedContractId())) {
            return;
        }

        // Conflict guard — original already confirmed a different renewal
        if ("in_progress".equals(original.getRenewalStatus())
                && original.getRenewedContractId() != null
                && !renewal.getId().equals(original.getRenewedContractId())) {
            throw new ConflictException(
                    "This contract already has a different renewal in progress");
        }

        // Mark the original contract
        original.setRenewalStatus("in_progress");
        original.setRenewedContractId(renewal.getId());
        original.setRenewalStartDate(
                renewal.getStartDate() != null ? renewal.getStartDate().toString() : null);
        original.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(original);
    }

    // ─── Cancel Renewal ───────────────────────────────────────────────────────

    /**
     * Clears the renewal link on the original contract. Use when a renewal draft was
     * rejected/abandoned and the original needs to become terminable/visible again.
     * Fails if the linked renewal has already completed (effective status
     * SIGNED/ACTIVE/EXPIRING/EXPIRED). Idempotent: safe to call twice.
     */
    public void cancelRenewal(String originalId, String email) {
        Contract original = contractRepository.findById(originalId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (!original.getCreatedBy().equals(email)) {
            throw new NotFoundException("Contract not found");
        }

        if (original.getRenewalStatus() == null && original.getRenewedContractId() == null) {
            return; // idempotent no-op — nothing to cancel
        }

        if (original.getRenewedContractId() != null) {
            Optional<Contract> renewalOpt = contractRepository.findById(original.getRenewedContractId());
            if (renewalOpt.isPresent()) {
                ContractStatus effective = computeEffectiveStatus(renewalOpt.get());
                if (effective == ContractStatus.SIGNED || effective == ContractStatus.ACTIVE
                        || effective == ContractStatus.EXPIRING || effective == ContractStatus.EXPIRED) {
                    throw new BadRequestException(
                            "Cannot cancel — the renewal has already been completed");
                }
            }
        }

        original.setRenewalStatus(null);
        original.setRenewedContractId(null);
        original.setRenewalStartDate(null);
        original.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(original);
    }

    // ─── Cleanup (called by scheduler) ───────────────────────────────────────

    public List<Contract> findOrphanedRenewalDrafts(LocalDateTime cutoff) {
        return contractRepository.findOrphanedRenewalDrafts(cutoff);
    }

    // ─── Package-private: used by ContractService for chain suppression ───────

    static ContractStatus computeEffectiveStatus(Contract c) {
        ContractStatus s = c.getStatus();
        if (s != ContractStatus.SIGNED && s != ContractStatus.ACTIVE
                && s != ContractStatus.EXPIRING && s != ContractStatus.EXPIRED) {
            return s;
        }
        if (c.getEndDate() == null) return ContractStatus.SIGNED;
        if (c.getEndDate().isBefore(LocalDate.now())) return ContractStatus.EXPIRED;
        long days = ChronoUnit.DAYS.between(LocalDate.now(), c.getEndDate());
        if (days <= 30) return ContractStatus.EXPIRING;
        if (c.getStartDate() != null && !c.getStartDate().isAfter(LocalDate.now())) {
            return ContractStatus.ACTIVE;
        }
        return ContractStatus.SIGNED;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String generateRenewalTitle(Contract original) {
        // Strip any existing (RenewalN) suffix to get the canonical base title
        String base = original.getTitle()
                .replaceAll("(?i)\\s*\\(Renewal\\d*\\)\\s*$", "")
                .trim();

        // Walk the chain backward to compute the depth (1 = first renewal, 2 = second, …)
        int depth = 1;
        String cursor = original.getRenewedFromId();
        while (cursor != null) {
            Optional<Contract> parent = contractRepository.findById(cursor);
            if (parent.isEmpty()) break;
            depth++;
            cursor = parent.get().getRenewedFromId();
        }

        return base + " (Renewal" + depth + ")";
    }

    private List<Map<String, Object>> enrichAndClearFormFields(
            List<Template.FormField> templateFields,
            List<Template.Party> parties) {

        if (templateFields == null || templateFields.isEmpty()) return new ArrayList<>();

        Map<String, Template.Party> partyById = new HashMap<>();
        if (parties != null) {
            parties.forEach(p -> partyById.put(p.getId(), p));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Template.FormField field : templateFields) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", field.getName());
            f.put("fieldName", field.getName());
            f.put("type", field.getType());
            f.put("label", field.getLabel());
            f.put("x", field.getX());
            f.put("y", field.getY());
            f.put("width", field.getWidth());
            f.put("height", field.getHeight());
            f.put("pageNumber", field.getPageNumber());
            f.put("annotationId", field.getAnnotationId());
            f.put("required", field.isRequired());
            f.put("readOnly", field.isReadOnly());
            f.put("multiline", field.isMultiline());
            f.put("defaultValue", field.getDefaultValue());
            f.put("placeholder", field.getPlaceholder());
            f.put("options", field.getOptions());
            f.put("assignedParty", field.getAssignedParty());
            f.put("profileKey", field.getProfileKey());
            f.put("value", ""); // always cleared — user fills fresh in the editor

            Template.Party party = partyById.get(field.getAssignedParty());
            f.put("partyLabel",
                    field.getPartyLabel() != null && !field.getPartyLabel().isBlank()
                            ? field.getPartyLabel()
                            : (party != null ? party.getLabel() : ""));
            f.put("partyColor", party != null ? party.getColor() : "#888888");

            result.add(f);
        }
        return result;
    }

    private List<Party> convertParties(List<Template.Party> templateParties) {
        if (templateParties == null) return new ArrayList<>();
        List<Party> result = new ArrayList<>();
        for (Template.Party tp : templateParties) {
            Party p = new Party();
            p.setId(tp.getId());
            p.setLabel(tp.getLabel());
            p.setColor(tp.getColor());
            p.setOrder(tp.getOrder());
            p.setType(tp.getType());
            result.add(p);
        }
        return result;
    }
}
