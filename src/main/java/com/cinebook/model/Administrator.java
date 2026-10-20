package com.cinebook.model;

import com.cinebook.model.enums.Role;

/** Full access: programme, reports, SQL console presets, shift summary. */
public final class Administrator extends StaffUser {

    public Administrator(int staffId, String username, String fullName,
                         String passwordHash, String salt) {
        super(staffId, username, fullName, Role.ADMIN, passwordHash, salt);
    }

    @Override
    public String homeScreen() {
        return "Reports";
    }

    @Override
    public boolean canManageProgramme() {
        return true;
    }
}
