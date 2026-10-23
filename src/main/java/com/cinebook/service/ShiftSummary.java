package com.cinebook.service;

import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The Z-report: one clerk, one day, and the two questions that matter — what
 * did I take, and what did I pay back?
 *
 * <p>Refunds are counted against {@code cancelled_by_staff_id}, never
 * {@code sold_by_staff_id} (docs/PRD.md §5F). A refund the evening clerk gave
 * belongs on the evening clerk's shift, even though the ticket was sold in the
 * morning.</p>
 */
public record ShiftSummary(String clerkName, LocalDate date, List<MethodTotal> methods,
                           int refundCount, BigDecimal refunded) {

    public ShiftSummary {
        methods = List.copyOf(methods);
        refunded = Money.round(refunded == null ? BigDecimal.ZERO : refunded);
    }

    public int bookings() {
        return methods.stream().mapToInt(MethodTotal::bookings).sum();
    }

    public BigDecimal tickets() {
        return total(methods.stream().map(MethodTotal::tickets).toList());
    }

    public BigDecimal food() {
        return total(methods.stream().map(MethodTotal::food).toList());
    }

    public BigDecimal gross() {
        return total(methods.stream().map(MethodTotal::gross).toList());
    }

    /** What the till should hold after today's refunds: gross less refunded. */
    public BigDecimal net() {
        return Money.round(gross().subtract(refunded));
    }

    public boolean isEmpty() {
        return methods.isEmpty() && refundCount == 0;
    }

    private static BigDecimal total(List<BigDecimal> values) {
        return Money.sum(values);
    }

    /** One payment method's line in the shift split. */
    public record MethodTotal(PaymentMethod method, int bookings, BigDecimal tickets,
                              BigDecimal food, BigDecimal gross) {

        public MethodTotal {
            tickets = Money.round(tickets == null ? BigDecimal.ZERO : tickets);
            food = Money.round(food == null ? BigDecimal.ZERO : food);
            gross = Money.round(gross == null ? BigDecimal.ZERO : gross);
        }
    }
}
