package com.costacloud.contractmanagement.dto;

import java.time.LocalDate;

public class ProfileResponse {

    private String email;
    private String fullName;
    private String department;
    private String organization;
    private LocalDate dateOfBirth;
    private String gender;
    private String permanentAddress;
    private String panCardNumber;
    private String aadharCardNumber;
    private boolean profileComplete;

    public ProfileResponse(String email, String fullName, String department, String organization,
                           LocalDate dateOfBirth, String gender, String permanentAddress,
                           String panCardNumber, String aadharCardNumber) {
        this.email = email;
        this.fullName = fullName;
        this.department = department;
        this.organization = organization;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.permanentAddress = permanentAddress;
        this.panCardNumber = panCardNumber;
        this.aadharCardNumber = aadharCardNumber;
        this.profileComplete = fullName != null && department != null && organization != null
                && dateOfBirth != null && gender != null && permanentAddress != null
                && panCardNumber != null && aadharCardNumber != null;
    }

    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getDepartment() { return department; }
    public String getOrganization() { return organization; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getGender() { return gender; }
    public String getPermanentAddress() { return permanentAddress; }
    public String getPanCardNumber() { return panCardNumber; }
    public String getAadharCardNumber() { return aadharCardNumber; }
    public boolean isProfileComplete() { return profileComplete; }
}
