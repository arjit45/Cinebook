package com.cinebook.model;

import com.cinebook.model.enums.Role;

/** Sells tickets and food. No programme editing, no reports. */
public final class BoxOfficeOperator extends StaffUser {

    public BoxOfficeOperator(int staffId, String username, String fullName,
                             String passwordHash, String salt) {
        super(staffId, username, fullName, Role.OPERATOR, passwordHash, salt);
    }

    @Override
    public String homeScreen() {
        return "Schedule";
    }

    @Override
    public boolean canManageProgramme() {
        return false;
    }
}
