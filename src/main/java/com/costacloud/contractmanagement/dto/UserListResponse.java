package com.costacloud.contractmanagement.dto;

import com.costacloud.contractmanagement.model.User;
import com.costacloud.contractmanagement.model.UserProfile;
import lombok.Data;

@Data
public class UserListResponse {

    private String id;
    private String email;
    private String fullName;
    private String department;
    private String organization;

    public UserListResponse(User user, UserProfile profile) {
        this.id = user.getId();
        this.email = user.getEmail();
        if (profile != null) {
            this.fullName = profile.getFullName();
            this.department = profile.getDepartment();
            this.organization = profile.getOrganization();
        }
    }
}
