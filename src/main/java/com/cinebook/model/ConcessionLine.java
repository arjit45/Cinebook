package com.cinebook.model;

import com.cinebook.model.enums.ItemCategory;
import com.cinebook.util.Money;

import java.math.BigDecimal;

/**
 * An immutable line on a booking: name, category and the unit price at the time
 * of sale. Changing the price list later must not restate this.
 */
public record ConcessionLine(String name, ItemCategory category, int quantity, BigDecimal unitPrice) {

    public ConcessionLine {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A concession line needs a name");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("A concession line needs a positive quantity");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("A concession line cannot have a negative price");
        }
    }

    public BigDecimal lineTotal() {
        return Money.round(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }
}
