package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.Contract;
import com.costacloud.contractmanagement.model.ContractStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContractListResponse — effective status computation")
class ContractListResponseTest {

    // ─── Helper ───────────────────────────────────────────────────────────────

    private Contract signedContract(LocalDate startDate, LocalDate endDate) {
        Contract c = new Contract();
        c.setId("contract-001");
        c.setTitle("Service Agreement");
        c.setCreatedBy("owner@test.com");
        c.setStatus(ContractStatus.SIGNED);   // raw DB value
        c.setStartDate(startDate);
        c.setEndDate(endDate);
        return c;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SIGNED contracts — status must be recomputed from dates
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SIGNED contract — date-driven recomputation")
    class SignedContracts {

        @Test
        @DisplayName("returns EXPIRED when endDate is in the past")
        void shouldReturn_EXPIRED_whenEndDateIsPast() {
            Contract c = signedContract(
                LocalDate.now().minusYears(2),
                LocalDate.now().minusDays(1)   // yesterday
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertEquals(ContractStatus.EXPIRED, resp.getStatus());
        }

        @Test
        @DisplayName("returns EXPIRING when endDate is within 30 days")
        void shouldReturn_EXPIRING_whenEndDateWithin30Days() {
            Contract c = signedContract(
                LocalDate.now().minusYears(1),
                LocalDate.now().plusDays(15)   // 15 days away
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertEquals(ContractStatus.EXPIRING, resp.getStatus());
        }

        @Test
        @DisplayName("returns EXPIRING when endDate is exactly 30 days away")
        void shouldReturn_EXPIRING_whenEndDateIsExactly30DaysAway() {
            Contract c = signedContract(
                LocalDate.now().minusYears(1),
                LocalDate.now().plusDays(30)
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertEquals(ContractStatus.EXPIRING, resp.getStatus());
        }

        @Test
        @DisplayName("returns ACTIVE when startDate <= today and endDate > 30 days away")
        void shouldReturn_ACTIVE_whenStartedAndEndDateFarFuture() {
            Contract c = signedContract(
                LocalDate.now().minusMonths(6),
                LocalDate.now().plusMonths(6)   // ~180 days away
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertEquals(ContractStatus.ACTIVE, resp.getStatus());
        }

        @Test
        @DisplayName("returns ACTIVE when startDate is today and endDate > 30 days away")
        void shouldReturn_ACTIVE_whenStartsToday() {
            Contract c = signedContract(
                LocalDate.now(),
                LocalDate.now().plusMonths(6)
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertEquals(ContractStatus.ACTIVE, resp.getStatus());
        }

        @Test
        @DisplayName("stays SIGNED when startDate is in the future (contract not yet started)")
        void shouldStay_SIGNED_whenStartDateIsInFuture() {
            Contract c = signedContract(
                LocalDate.now().plusMonths(1),   // starts next month
                LocalDate.now().plusMonths(13)
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertEquals(ContractStatus.SIGNED, resp.getStatus());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // expiresInDays — must be positive (future) or negative (past)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("expiresInDays computation")
    class ExpiresInDays {

        @Test
        @DisplayName("is negative when endDate is in the past")
        void shouldBeNegative_whenExpired() {
            Contract c = signedContract(
                LocalDate.now().minusYears(2),
                LocalDate.now().minusDays(10)
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertTrue(resp.getExpiresInDays() < 0);
        }

        @Test
        @DisplayName("is positive when endDate is in the future")
        void shouldBePositive_whenNotExpired() {
            Contract c = signedContract(
                LocalDate.now().minusMonths(1),
                LocalDate.now().plusDays(60)
            );

            ContractListResponse resp = new ContractListResponse(c);

            assertTrue(resp.getExpiresInDays() > 0);
        }

        @Test
        @DisplayName("is zero when no endDate is set")
        void shouldBeZero_whenEndDateIsNull() {
            Contract c = new Contract();
            c.setStatus(ContractStatus.DRAFT);
            c.setEndDate(null);

            ContractListResponse resp = new ContractListResponse(c);

            assertEquals(0, resp.getExpiresInDays());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-post-finalization statuses — must NEVER be recomputed
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Non-post-finalization statuses pass through unchanged")
    class PassThroughStatuses {

        private Contract contractWith(ContractStatus status) {
            Contract c = new Contract();
            c.setStatus(status);
            c.setStartDate(LocalDate.now().minusYears(2));
            c.setEndDate(LocalDate.now().minusDays(1));  // past end date — would trigger EXPIRED if recomputed
            return c;
        }

        @Test
        @DisplayName("DRAFT stays DRAFT even if endDate is in the past")
        void shouldKeep_DRAFT() {
            assertEquals(ContractStatus.DRAFT,
                new ContractListResponse(contractWith(ContractStatus.DRAFT)).getStatus());
        }

        @Test
        @DisplayName("IN_REVIEW stays IN_REVIEW even if endDate is in the past")
        void shouldKeep_IN_REVIEW() {
            assertEquals(ContractStatus.IN_REVIEW,
                new ContractListResponse(contractWith(ContractStatus.IN_REVIEW)).getStatus());
        }

        @Test
        @DisplayName("IN_SIGNATURE stays IN_SIGNATURE even if endDate is in the past")
        void shouldKeep_IN_SIGNATURE() {
            assertEquals(ContractStatus.IN_SIGNATURE,
                new ContractListResponse(contractWith(ContractStatus.IN_SIGNATURE)).getStatus());
        }

        @Test
        @DisplayName("SIGNED_BY_EVERYONE stays SIGNED_BY_EVERYONE even if endDate is in the past")
        void shouldKeep_SIGNED_BY_EVERYONE() {
            assertEquals(ContractStatus.SIGNED_BY_EVERYONE,
                new ContractListResponse(contractWith(ContractStatus.SIGNED_BY_EVERYONE)).getStatus());
        }

        @Test
        @DisplayName("TERMINATED stays TERMINATED even if endDate is in the past")
        void shouldKeep_TERMINATED() {
            assertEquals(ContractStatus.TERMINATED,
                new ContractListResponse(contractWith(ContractStatus.TERMINATED)).getStatus());
        }

        @Test
        @DisplayName("REJECTED_BY_REVIEWER stays REJECTED_BY_REVIEWER")
        void shouldKeep_REJECTED_BY_REVIEWER() {
            assertEquals(ContractStatus.REJECTED_BY_REVIEWER,
                new ContractListResponse(contractWith(ContractStatus.REJECTED_BY_REVIEWER)).getStatus());
        }
    }
}
