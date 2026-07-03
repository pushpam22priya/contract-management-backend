package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class AutoAdvanceService {

    private static final Logger log = LoggerFactory.getLogger(AutoAdvanceService.class);

    private final ContractRepository contractRepository;
    private final SignatureRequestRepository signatureRequestRepository;
    private final EmailService emailService;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.signing-link-expiry-days:7}")
    private int expiryDays;

    public AutoAdvanceService(ContractRepository contractRepository,
                              SignatureRequestRepository signatureRequestRepository,
                              EmailService emailService) {
        this.contractRepository = contractRepository;
        this.signatureRequestRepository = signatureRequestRepository;
        this.emailService = emailService;
    }

    public void advance(String contractId) {
        Contract contract = contractRepository.findById(contractId).orElse(null);
        if (contract == null) return;

        // Guard — skip if already done or not in a signing flow
        String flowStatus = contract.getSignatureFlowStatus();
        if ("all_completed".equals(flowStatus) || "finalized".equals(flowStatus)) return;
        if (contract.getCurrentSigningOrder() == null) return;

        // Orders are globally unique across the entire contract — process all signers.
        List<ExternalSigner> externals = orEmpty(contract.getExternalSigners());
        List<InternalSigner> internals = orEmpty(contract.getInternalSigners());

        if (externals.isEmpty() && internals.isEmpty()) return;

        int currentOrder = contract.getCurrentSigningOrder();

        // Since orders are unique, each filter returns exactly one signer.
        // allMatch on a single-element list returns true only if that one is complete.
        boolean externalDone = externals.stream()
                .filter(s -> s.getOrder() == currentOrder)
                .allMatch(s -> "completed".equals(s.getStatus()));

        boolean internalDone = internals.stream()
                .filter(s -> s.getOrder() == currentOrder)
                .allMatch(s -> "completed".equals(s.getStatus()));

        if (!externalDone || !internalDone) {
            // Current order not yet finished — nothing to advance
            return;
        }

        // Find the next order number above current
        Optional<Integer> nextOrderOpt = externals.stream()
                .map(ExternalSigner::getOrder)
                .filter(o -> o > currentOrder)
                .min(Comparator.naturalOrder());

        Optional<Integer> nextInternalOpt = internals.stream()
                .map(InternalSigner::getOrder)
                .filter(o -> o > currentOrder)
                .min(Comparator.naturalOrder());

        int nextOrder = Integer.MAX_VALUE;
        if (nextOrderOpt.isPresent()) nextOrder = Math.min(nextOrder, nextOrderOpt.get());
        if (nextInternalOpt.isPresent()) nextOrder = Math.min(nextOrder, nextInternalOpt.get());

        if (nextOrder == Integer.MAX_VALUE) {
            // No more orders — all signers have completed
            contract.setSignatureFlowStatus("all_completed");
            contract.setStatus(ContractStatus.SIGNED_BY_EVERYONE);
            contract.setCurrentSigningOrder(null);
            contract.setUpdatedAt(LocalDateTime.now());
            contractRepository.save(contract);

            log.info("Contract {} — all signers completed", contractId);

        } else {
            // Unlock the signer at nextOrder
            int advanceTo = nextOrder;
            LocalDateTime now = LocalDateTime.now();

            externals.stream()
                    .filter(s -> s.getOrder() == advanceTo && "pending".equals(s.getStatus()))
                    .forEach(s -> {
                        s.setStatus("unlocked");
                        s.setUnlockedAt(now);
                    });

            internals.stream()
                    .filter(s -> s.getOrder() == advanceTo && "pending".equals(s.getStatus()))
                    .forEach(s -> {
                        s.setStatus("unlocked");
                        s.setUnlockedAt(now);
                    });

            List<PartyCompletion> completions = orEmpty(contract.getPartyCompletions());
            completions.stream()
                    .filter(p -> p.getOrder() == advanceTo && "pending".equals(p.getStatus()))
                    .forEach(p -> p.setStatus("unlocked"));

            contract.setCurrentSigningOrder(advanceTo);
            contract.setUpdatedAt(now);
            contractRepository.save(contract);

            // Create SignatureRequest docs and send emails for newly unlocked external signers
            String senderName = contract.getSignatureSenderName() != null
                    ? contract.getSignatureSenderName() : contract.getCreatedBy();

            externals.stream()
                    .filter(s -> s.getOrder() == advanceTo && s.getToken() != null)
                    .forEach(s -> {
                        // Create the SignatureRequest document — this is intentionally skipped at
                        // submit-for-signature time for pending (non-starting-order) external signers.
                        // It must be created here when auto-advance first unlocks this signer.
                        if (!signatureRequestRepository.findByToken(s.getToken()).isPresent()) {
                            SignatureRequest sr = new SignatureRequest();
                            sr.setToken(s.getToken());
                            sr.setContractId(contractId);
                            sr.setContractTitle(contract.getTitle());
                            sr.setSignerEmail(s.getEmail());
                            sr.setSignerName(s.getName());
                            sr.setCreatedBy(contract.getCreatedBy());
                            sr.setCreatedByName(senderName);
                            sr.setCreatedAt(now);
                            sr.setExpiresAt(now.plusDays(expiryDays));
                            sr.setStatus("pending");
                            sr.setAssignedParty(List.of(s.getPartyId()));
                            sr.setAssignedPartyLabel(List.of(s.getPartyLabel()));
                            sr.setOrder(s.getOrder());
                            sr.setFormFields(contract.getFormFields());
                            sr.setXfdfData(contract.getXfdfData());
                            sr.setFieldValues(contract.getFieldValues());
                            sr.setContractVersion(contract.getVersion());
                            signatureRequestRepository.save(sr);
                        }

                        String signingUrl  = baseUrl + "/sign/" + s.getToken();
                        String partyColor = resolvePartyColor(contract, s.getPartyId());
                        try {
                            emailService.sendSignatureRequestEmail(
                                    s.getEmail(),
                                    s.getName(),
                                    senderName,
                                    contract.getCreatedBy(),
                                    contract.getTitle(),
                                    signingUrl,
                                    null,
                                    s.getPartyLabel(),
                                    partyColor
                            );
                        } catch (RuntimeException emailEx) {
                            log.error("SIGNATURE EMAIL NOT SENT — contract={}, signer={}: {}",
                                    contractId, s.getEmail(), emailEx.getMessage());
                        }
                    });

            log.info("Contract {} — advanced to order {}", contractId, advanceTo);
        }
    }

    private String resolvePartyColor(Contract contract, String partyId) {
        if (contract.getParties() == null || partyId == null) return "#0e7c6b";
        return contract.getParties().stream()
                .filter(p -> partyId.equals(p.getId()))
                .map(Party::getColor)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse("#0e7c6b");
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}

