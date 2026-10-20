package com.cinebook.model;

import com.cinebook.exception.ValidationException;
import com.cinebook.model.enums.ItemCategory;
import com.cinebook.util.Money;

import java.math.BigDecimal;

/** Something from the counter. The price list, not a sold line. */
public final class ConcessionItem {

    private final int itemId;
    private final String name;
    private final ItemCategory category;
    private final BigDecimal unitPrice;
    private final boolean active;

    public ConcessionItem(int itemId, String name, ItemCategory category,
                          BigDecimal unitPrice, boolean active) {
        this.itemId = itemId;
        this.name = name;
        this.category = category;
        this.unitPrice = unitPrice;
        this.active = active;
    }

    public BigDecimal priceFor(int quantity) throws ValidationException {
        if (quantity < 1) {
            throw new ValidationException("Quantity must be at least 1.");
        }
        return Money.round(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public int itemId() {
        return itemId;
    }

    public String name() {
        return name;
    }

    public ItemCategory category() {
        return category;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public boolean active() {
        return active;
    }
}
