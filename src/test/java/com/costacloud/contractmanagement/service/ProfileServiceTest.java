package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.ProfileRequest;
import com.costacloud.contractmanagement.dto.ProfileResponse;
import com.costacloud.contractmanagement.model.UserProfile;
import com.costacloud.contractmanagement.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileService")
class ProfileServiceTest {

    @Mock UserProfileRepository userProfileRepository;

    @InjectMocks ProfileService profileService;

    private static final String EMAIL = "user@test.com";

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** A fully populated UserProfile stored in the DB */
    private UserProfile fullProfile() {
        UserProfile p = new UserProfile();
        p.setEmail(EMAIL);
        p.setFullName("John Doe");
        p.setDepartment("Engineering");
        p.setOrganization("Acme Corp");
        p.setDateOfBirth(LocalDate.of(1990, 5, 15));
        p.setGender("MALE");
        p.setPermanentAddress("123 Main St");
        p.setPanCardNumber("ABCDE1234F");
        p.setAadharCardNumber("123456789012");
        return p;
    }

    /** A ProfileRequest with all 8 updatable fields set */
    private ProfileRequest fullRequest() {
        ProfileRequest r = new ProfileRequest();
        r.setFullName("John Doe");
        r.setDepartment("Engineering");
        r.setOrganization("Acme Corp");
        r.setDateOfBirth(LocalDate.of(1990, 5, 15));
        r.setGender("MALE");
        r.setPermanentAddress("123 Main St");
        r.setPanCardNumber("ABCDE1234F");
        r.setAadharCardNumber("123456789012");
        return r;
    }

    /** Returns the UserProfile captured by the last save() call */
    private UserProfile captureLastSaved() {
        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — getProfile
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getProfile")
    class GetProfile {

        @Test
        @DisplayName("returns all fields when a profile exists for the email")
        void shouldReturn_allFields_whenProfileExists() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));

            ProfileResponse response = profileService.getProfile(EMAIL);

            assertEquals(EMAIL, response.getEmail());
            assertEquals("John Doe", response.getFullName());
            assertEquals("Engineering", response.getDepartment());
            assertEquals("Acme Corp", response.getOrganization());
            assertEquals(LocalDate.of(1990, 5, 15), response.getDateOfBirth());
            assertEquals("MALE", response.getGender());
            assertEquals("123 Main St", response.getPermanentAddress());
            assertEquals("ABCDE1234F", response.getPanCardNumber());
            assertEquals("123456789012", response.getAadharCardNumber());
        }

        @Test
        @DisplayName("returns response with only email when no profile exists yet")
        void shouldReturn_emailOnly_whenNoProfileFound() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            ProfileResponse response = profileService.getProfile(EMAIL);

            assertEquals(EMAIL, response.getEmail());
            assertNull(response.getFullName());
            assertNull(response.getDepartment());
            assertNull(response.getOrganization());
        }

        @Test
        @DisplayName("profileComplete is true when all 8 fields are populated")
        void shouldReturn_profileComplete_true_whenAllFieldsPresent() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));

            assertTrue(profileService.getProfile(EMAIL).isProfileComplete());
        }

        @Test
        @DisplayName("profileComplete is false when any field is missing")
        void shouldReturn_profileComplete_false_whenAnyFieldMissing() {
            UserProfile partial = fullProfile();
            partial.setAadharCardNumber(null);  // one missing field
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(partial));

            assertFalse(profileService.getProfile(EMAIL).isProfileComplete());
        }

        @Test
        @DisplayName("profileComplete is false for a brand-new empty profile")
        void shouldReturn_profileComplete_false_forEmptyProfile() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertFalse(profileService.getProfile(EMAIL).isProfileComplete());
        }

        @Test
        @DisplayName("email is always set from the method parameter, not the DB record")
        void shouldSetEmail_fromParameter() {
            UserProfile stored = fullProfile();
            stored.setEmail("old@email.com");  // DB email differs
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(stored));

            assertEquals(EMAIL, profileService.getProfile(EMAIL).getEmail());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — updateProfile: field merging
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateProfile — field merging")
    class UpdateProfileFields {

        @BeforeEach
        void stubSave() {
            lenient().when(userProfileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("updates fullName when provided in request")
        void shouldUpdate_fullName() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setFullName("Jane Smith");

            profileService.updateProfile(EMAIL, r);

            assertEquals("Jane Smith", captureLastSaved().getFullName());
        }

        @Test
        @DisplayName("does NOT overwrite existing fullName when request fullName is null")
        void shouldPreserve_fullName_whenRequestFieldIsNull() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setFullName(null);

            profileService.updateProfile(EMAIL, r);

            assertEquals("John Doe", captureLastSaved().getFullName());
        }

        @Test
        @DisplayName("updates department when provided in request")
        void shouldUpdate_department() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setDepartment("Marketing");

            profileService.updateProfile(EMAIL, r);

            assertEquals("Marketing", captureLastSaved().getDepartment());
        }

        @Test
        @DisplayName("updates organization when provided in request")
        void shouldUpdate_organization() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setOrganization("New Corp");

            profileService.updateProfile(EMAIL, r);

            assertEquals("New Corp", captureLastSaved().getOrganization());
        }

        @Test
        @DisplayName("updates dateOfBirth when provided in request")
        void shouldUpdate_dateOfBirth() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setDateOfBirth(LocalDate.of(1985, 3, 20));

            profileService.updateProfile(EMAIL, r);

            assertEquals(LocalDate.of(1985, 3, 20), captureLastSaved().getDateOfBirth());
        }

        @Test
        @DisplayName("updates gender when provided in request")
        void shouldUpdate_gender() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setGender("FEMALE");

            profileService.updateProfile(EMAIL, r);

            assertEquals("FEMALE", captureLastSaved().getGender());
        }

        @Test
        @DisplayName("updates permanentAddress when provided in request")
        void shouldUpdate_permanentAddress() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setPermanentAddress("456 New Ave");

            profileService.updateProfile(EMAIL, r);

            assertEquals("456 New Ave", captureLastSaved().getPermanentAddress());
        }

        @Test
        @DisplayName("updates panCardNumber when provided in request")
        void shouldUpdate_panCardNumber() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setPanCardNumber("ZYXWV9876A");

            profileService.updateProfile(EMAIL, r);

            assertEquals("ZYXWV9876A", captureLastSaved().getPanCardNumber());
        }

        @Test
        @DisplayName("updates aadharCardNumber when provided in request")
        void shouldUpdate_aadharCardNumber() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));
            ProfileRequest r = new ProfileRequest();
            r.setAadharCardNumber("999988887777");

            profileService.updateProfile(EMAIL, r);

            assertEquals("999988887777", captureLastSaved().getAadharCardNumber());
        }

        @Test
        @DisplayName("a null request field does not erase any of the other existing fields")
        void shouldPreserveAllExistingFields_whenAllRequestFieldsAreNull() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));

            profileService.updateProfile(EMAIL, new ProfileRequest());  // all nulls

            UserProfile saved = captureLastSaved();
            assertEquals("John Doe",       saved.getFullName());
            assertEquals("Engineering",    saved.getDepartment());
            assertEquals("Acme Corp",      saved.getOrganization());
            assertEquals(LocalDate.of(1990, 5, 15), saved.getDateOfBirth());
            assertEquals("MALE",           saved.getGender());
            assertEquals("123 Main St",    saved.getPermanentAddress());
            assertEquals("ABCDE1234F",     saved.getPanCardNumber());
            assertEquals("123456789012",   saved.getAadharCardNumber());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — updateProfile: upsert and save
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateProfile — upsert and save")
    class UpdateProfilePersistence {

        @BeforeEach
        void stubSave() {
            lenient().when(userProfileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("creates a new profile when no existing profile is found (upsert)")
        void shouldCreate_newProfile_whenNoExistingProfile() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            profileService.updateProfile(EMAIL, fullRequest());

            verify(userProfileRepository, times(1)).save(any(UserProfile.class));
        }

        @Test
        @DisplayName("saves profile with email set from the method parameter")
        void shouldSave_emailFromParameter() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            profileService.updateProfile(EMAIL, fullRequest());

            assertEquals(EMAIL, captureLastSaved().getEmail());
        }

        @Test
        @DisplayName("calls save exactly once per updateProfile invocation")
        void shouldCallSave_exactlyOnce() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.of(fullProfile()));

            profileService.updateProfile(EMAIL, fullRequest());

            verify(userProfileRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("returns profileComplete=true when all 8 fields are set after update")
        void shouldReturn_profileComplete_true_afterFullUpdate() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            ProfileResponse response = profileService.updateProfile(EMAIL, fullRequest());

            assertTrue(response.isProfileComplete());
        }

        @Test
        @DisplayName("returns profileComplete=false when some fields are still null after partial update")
        void shouldReturn_profileComplete_false_afterPartialUpdate() {
            when(userProfileRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            ProfileRequest r = new ProfileRequest();
            r.setFullName("John Doe");  // only one field set

            ProfileResponse response = profileService.updateProfile(EMAIL, r);

            assertFalse(response.isProfileComplete());
        }
    }
}
