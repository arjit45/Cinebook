package com.cinebook.model;

import com.cinebook.exception.ValidationException;
import com.cinebook.model.enums.ItemCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The cheap validation rules from docs/PRD.md §13.2 that the UI and the DAO share.
 * Duplicate email is deliberately absent: that one is a UNIQUE index, proved
 * against the database in the {@code db} group, not by a unit test pretending to
 * be a table.
 */
class ValidationRulesTest {

    @Test
    void phoneMustBeTenToThirteenDigits() throws Exception {
        assertEquals("9876543210", Customer.requirePhone("9876543210"));
        assertEquals("9876543210123", Customer.requirePhone("9876543210123"));
        assertEquals("123456789012", Customer.requirePhone("123456789012"));

        // The PRD's "phone 12 rejected" means the two-character value, not twelve digits.
        assertThrows(ValidationException.class, () -> Customer.requirePhone("12"));
        assertThrows(ValidationException.class, () -> Customer.requirePhone("98765abcde"));
        assertThrows(ValidationException.class, () -> Customer.requirePhone(""));
        assertThrows(ValidationException.class, () -> Customer.requirePhone(null));
    }

    @Test
    void emailNeedsAnAtSign() {
        assertThrows(ValidationException.class, () -> Customer.requireEmail("no-at-sign"));
        assertThrows(ValidationException.class, () -> Customer.requireEmail("   "));
    }

    @Test
    void nameCannotBeBlank() {
        assertThrows(ValidationException.class, () -> Customer.requireName("  "));
    }

    @Test
    void concessionQuantityMustBePositive() {
        ConcessionItem item = new ConcessionItem(
                1, "Masala Chai", ItemCategory.DRINK, new BigDecimal("40.00"), true);

        assertThrows(ValidationException.class, () -> item.priceFor(0));
        assertThrows(ValidationException.class, () -> item.priceFor(-1));
    }

    @Test
    void lineTotalMultipliesAndRounds() throws Exception {
        ConcessionItem item = new ConcessionItem(
                1, "Nachos", ItemCategory.SNACK, new BigDecimal("99.99"), true);
        assertEquals(new BigDecimal("299.97"), item.priceFor(3));

        ConcessionLine line = new ConcessionLine("Nachos", ItemCategory.SNACK, 3, new BigDecimal("99.99"));
        assertEquals(new BigDecimal("299.97"), line.lineTotal());
    }
}
