package com.cinebook.model;

import com.cinebook.model.enums.BookingStatus;

import java.time.LocalDateTime;

/** One row written by the {@code booking_audit} trigger. Read-only in the app. */
public record AuditEntry(int auditId, int bookingId, BookingStatus oldStatus,
                         BookingStatus newStatus, LocalDateTime changedAt) {
}
