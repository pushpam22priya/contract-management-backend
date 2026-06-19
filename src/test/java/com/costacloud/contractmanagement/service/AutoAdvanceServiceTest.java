package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.model.*;
import com.costacloud.contractmanagement.repository.ContractRepository;
import com.costacloud.contractmanagement.repository.SignatureRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutoAdvanceService - advance")
class AutoAdvanceServiceTest {

    @Mock ContractRepository contractRepository;
    @Mock SignatureRequestRepository signatureRequestRepository;
    @Mock EmailService emailService;

    @InjectMocks AutoAdvanceService autoAdvanceService;

    private static final String CONTRACT_ID = "contract-789";

    @BeforeEach
    void injectValues() {
        // @Value fields are not injected by Mockito — set them manually
        ReflectionTestUtils.setField(autoAdvanceService, "baseUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(autoAdvanceService, "expiryDays", 7);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Contract inProgressContract(int signingRound) {
        Contract c = new Contract();
        c.setId(CONTRACT_ID);
        c.setStatus(ContractStatus.IN_SIGNATURE);
        c.setSignatureFlowStatus("pending_signatures");
        c.setCurrentSigningOrder(1);
        c.setSigningRound(signingRound);
        c.setCreatedBy("owner@test.com");
        c.setTitle("Service Agreement");
        c.setPartyCompletions(new ArrayList<>());
        return c;
    }

    private ExternalSigner extSigner(String partyId, int order, String status, int round) {
        ExternalSigner s = new ExternalSigner();
        s.setPartyId(partyId);
        s.setPartyLabel("Buyer");
        s.setEmail(partyId + "@client.com");
        s.setName("Signer " + partyId);
        s.setOrder(order);
        s.setStatus(status);
        s.setToken("tok_" + partyId);
        s.setSigningRound(round);
        return s;
    }

    private InternalSigner intSigner(String partyId, int order, String status, int round) {
        InternalSigner s = new InternalSigner();
        s.setPartyId(partyId);
        s.setOrder(order);
        s.setStatus(status);
        s.setSigningRound(round);
        return s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard conditions — should exit early
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Guard conditions")
    class Guards {

        @Test
        @DisplayName("does nothing when contract is not found")
        void shouldDoNothing_whenContractNotFound() {
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.empty());

            autoAdvanceService.advance(CONTRACT_ID);

            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("does nothing when flow status is already all_completed")
        void shouldDoNothing_whenAllCompleted() {
            Contract c = inProgressContract(1);
            c.setSignatureFlowStatus("all_completed");
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("does nothing when current order signer has not yet completed")
        void shouldDoNothing_whenCurrentOrderSigner_stillPending() {
            Contract c = inProgressContract(1);
            c.setExternalSigners(List.of(
                extSigner("party_1", 1, "unlocked", 1)  // still in progress, not completed
            ));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            verify(contractRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Advancement logic
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Advancement logic")
    class Advancement {

        @BeforeEach
        void stubSave() {
            when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(signatureRequestRepository.findByToken(any())).thenReturn(Optional.empty());
            lenient().doNothing().when(emailService).sendSignatureRequestEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("unlocks order-2 signer when order-1 completes")
        void shouldUnlock_nextOrderSigner_whenCurrentOrderComplete() {
            Contract c = inProgressContract(1);
            ExternalSigner p1 = extSigner("party_1", 1, "completed", 1);
            ExternalSigner p2 = extSigner("party_2", 2, "pending", 1);
            c.setExternalSigners(new ArrayList<>(List.of(p1, p2)));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            Contract saved = captureLastSaved();
            ExternalSigner unlocked = saved.getExternalSigners().stream()
                    .filter(s -> s.getOrder() == 2).findFirst().orElseThrow();

            assertEquals("unlocked", unlocked.getStatus());
            assertNotNull(unlocked.getUnlockedAt());
        }

        @Test
        @DisplayName("advances currentSigningOrder to the next order number")
        void shouldAdvance_currentSigningOrder() {
            Contract c = inProgressContract(1);
            c.setExternalSigners(new ArrayList<>(List.of(
                extSigner("party_1", 1, "completed", 1),
                extSigner("party_2", 2, "pending", 1)
            )));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            assertEquals(2, captureLastSaved().getCurrentSigningOrder());
        }

        @Test
        @DisplayName("sets status to SIGNED_BY_EVERYONE when no more orders remain")
        void shouldSetAllCompleted_whenNoMoreOrders() {
            Contract c = inProgressContract(1);
            c.setExternalSigners(new ArrayList<>(List.of(
                extSigner("party_1", 1, "completed", 1)  // last signer, just completed
            )));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            Contract saved = captureLastSaved();
            assertEquals("all_completed", saved.getSignatureFlowStatus());
            assertEquals(ContractStatus.SIGNED_BY_EVERYONE, saved.getStatus());
            assertNull(saved.getCurrentSigningOrder());
        }

        @Test
        @DisplayName("sends email to newly unlocked external signer")
        void shouldSendEmail_toNewlyUnlockedExternalSigner() {
            Contract c = inProgressContract(1);
            c.setExternalSigners(new ArrayList<>(List.of(
                extSigner("party_1", 1, "completed", 1),
                extSigner("party_2", 2, "pending", 1)
            )));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            verify(emailService, times(1)).sendSignatureRequestEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Round filtering — the key bug fix
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Linear chain continuation")
    class LinearChain {

        @BeforeEach
        void stubSave() {
            lenient().when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(signatureRequestRepository.findByToken(any())).thenReturn(Optional.empty());
            lenient().doNothing().when(emailService).sendSignatureRequestEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("does not advance when signer at currentSigningOrder is not yet completed")
        void shouldDoNothing_whenCurrentOrderSignerNotCompleted() {
            // P1–P4 completed, P5 is at order 5 and unlocked but not done yet
            Contract c = inProgressContract(1);
            c.setCurrentSigningOrder(5);

            c.setExternalSigners(new ArrayList<>(List.of(
                extSigner("party_1", 1, "completed", 1),
                extSigner("party_2", 2, "completed", 1),
                extSigner("party_3", 3, "completed", 1),
                extSigner("party_4", 4, "completed", 1),
                extSigner("party_5", 5, "unlocked",  1)  // not done yet
            )));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("advances to order 5 when P1–P4 all done and P5 is next in chain")
        void shouldAdvance_fromOrder4_toOrder5_inLinearChain() {
            // P1–P4 completed, currentOrder=4. P5 is pending at order 5.
            Contract c = inProgressContract(1);
            c.setCurrentSigningOrder(4);

            c.setExternalSigners(new ArrayList<>(List.of(
                extSigner("party_1", 1, "completed", 1),
                extSigner("party_2", 2, "completed", 1),
                extSigner("party_3", 3, "completed", 1),
                extSigner("party_4", 4, "completed", 1),
                extSigner("party_5", 5, "pending",   1)
            )));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            Contract saved = captureLastSaved();
            assertEquals("unlocked", saved.getExternalSigners().stream()
                    .filter(s -> s.getOrder() == 5).findFirst().orElseThrow().getStatus());
            assertEquals(5, saved.getCurrentSigningOrder());
        }

        @Test
        @DisplayName("sets all_completed when P5 is the last signer and completes")
        void shouldSetAllCompleted_whenP5IsLastAndCompletes() {
            Contract c = inProgressContract(1);
            c.setCurrentSigningOrder(5);

            c.setExternalSigners(new ArrayList<>(List.of(
                extSigner("party_1", 1, "completed", 1),
                extSigner("party_2", 2, "completed", 1),
                extSigner("party_3", 3, "completed", 1),
                extSigner("party_4", 4, "completed", 1),
                extSigner("party_5", 5, "completed", 1)  // just completed
            )));
            when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(c));

            autoAdvanceService.advance(CONTRACT_ID);

            Contract saved = captureLastSaved();
            assertEquals("all_completed", saved.getSignatureFlowStatus());
            assertEquals(ContractStatus.SIGNED_BY_EVERYONE, saved.getStatus());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    private Contract captureLastSaved() {
        org.mockito.ArgumentCaptor<Contract> captor =
                org.mockito.ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
