package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.ProfileRequest;
import com.costacloud.contractmanagement.dto.ProfileResponse;
import com.costacloud.contractmanagement.model.UserProfile;
import com.costacloud.contractmanagement.repository.UserProfileRepository;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    private final UserProfileRepository userProfileRepository;

    public ProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public ProfileResponse getProfile(String email) {
        UserProfile profile = userProfileRepository.findByEmail(email)
                .orElse(new UserProfile());
        profile.setEmail(email);
        return toResponse(profile);
    }

    public ProfileResponse updateProfile(String email, ProfileRequest request) {
        UserProfile profile = userProfileRepository.findByEmail(email)
                .orElse(new UserProfile());

        profile.setEmail(email);

        if (request.getFullName() != null) profile.setFullName(request.getFullName());
        if (request.getDepartment() != null) profile.setDepartment(request.getDepartment());
        if (request.getOrganization() != null) profile.setOrganization(request.getOrganization());
        if (request.getDateOfBirth() != null) profile.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null) profile.setGender(request.getGender());
        if (request.getPermanentAddress() != null) profile.setPermanentAddress(request.getPermanentAddress());
        if (request.getPanCardNumber() != null) profile.setPanCardNumber(request.getPanCardNumber());
        if (request.getAadharCardNumber() != null) profile.setAadharCardNumber(request.getAadharCardNumber());

        userProfileRepository.save(profile);
        return toResponse(profile);
    }

    private ProfileResponse toResponse(UserProfile profile) {
        return new ProfileResponse(
                profile.getEmail(),
                profile.getFullName(),
                profile.getDepartment(),
                profile.getOrganization(),
                profile.getDateOfBirth(),
                profile.getGender(),
                profile.getPermanentAddress(),
                profile.getPanCardNumber(),
                profile.getAadharCardNumber()
        );
    }
}
