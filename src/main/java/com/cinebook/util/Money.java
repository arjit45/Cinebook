package com.cinebook.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Money arithmetic in one place, because "rounded to 2 dp with HALF_UP" appears
 * in three different rules (seat multipliers, refunds, line totals) and three
 * different implementations of it is how totals stop adding up.
 */
public final class Money {

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal times(BigDecimal base, BigDecimal factor) {
        return round(base.multiply(factor));
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return round(total);
    }

    /** 750 not 750.00, but 362.50 keeps its paise — the way a ticket reads. */
    public static String plain(BigDecimal value) {
        String text = round(value).toPlainString();
        return text.endsWith(".00") ? text.substring(0, text.length() - 3) : text;
    }
}
