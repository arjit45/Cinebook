package com.cinebook.util;

import com.cinebook.model.ConcessionLine;
import com.cinebook.model.Receipt;
import com.cinebook.model.enums.ItemCategory;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.model.enums.SeatClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slip the till prints: 40 columns, money in one right-aligned column, and a
 * title that wraps instead of being cut off.
 *
 * <p>These run without MySQL. A receipt is a value object, so a hand-built one is
 * enough to pin the layout that {@code docs/PRD.md} §5 and §14.2 promise.
 */
class ReceiptWriterTest {

    private static Receipt receipt(List<ConcessionLine> food) {
        return new Receipt(
                42,
                "Asha Rao",
                "9876543210",
                "The Quiet Cartographer of the Northern Railways",
                "Audi 1",
                LocalDate.of(2026, 10, 24),
                LocalTime.of(21, 0),
                List.of(
                        new Receipt.SeatLine("A4", SeatClass.PREMIUM, new BigDecimal("210.00")),
                        new Receipt.SeatLine("A5", SeatClass.PREMIUM, new BigDecimal("210.00"))),
                food,
                new BigDecimal("420.00"),
                food.isEmpty() ? new BigDecimal("0.00") : new BigDecimal("195.00"),
                new BigDecimal("420.00").add(food.isEmpty()
                        ? new BigDecimal("0.00") : new BigDecimal("195.00")),
                PaymentMethod.CASH,
                "TXN00000042",
                LocalDateTime.of(2026, 10, 21, 18, 5));
    }

    @Test
    void everyLineFitsFortyColumns() {
        String text = ReceiptWriter.format(receipt(List.of(
                new ConcessionLine("Popcorn", ItemCategory.SNACK, 2, new BigDecimal("120.00")),
                new ConcessionLine("Masala Chai", ItemCategory.DRINK, 1, new BigDecimal("40.00")))));

        for (String line : text.lines().toList()) {
            assertTrue(line.length() <= ReceiptWriter.WIDTH,
                    "a receipt line wider than 40 columns wraps in the printer: [" + line + "]");
        }
        assertTrue(text.contains("BK-0042"), "the slip must carry the booking reference");
        assertTrue(text.lines().anyMatch(line -> line.trim().equals("CINEBOOK")));
        assertTrue(text.contains("TXN00000042"), "the payment reference must be printed");
        assertTrue(text.contains("CASH"), "the method is printed for the customer");
    }

    @Test
    void amountsSitInOneRightAlignedColumn() {
        String text = ReceiptWriter.format(receipt(List.of(
                new ConcessionLine("Popcorn", ItemCategory.SNACK, 2, new BigDecimal("120.00")))));

        String total = text.lines()
                .filter(line -> line.startsWith("TOTAL"))
                .findFirst().orElseThrow();
        assertEquals(ReceiptWriter.WIDTH, total.length(),
                "the total line must run to the full width: [" + total + "]");
        assertTrue(total.endsWith("615.00"), "the total must end the line: [" + total + "]");

        String food = text.lines()
                .filter(line -> line.startsWith("Popcorn x2"))
                .findFirst().orElseThrow();
        assertEquals(ReceiptWriter.WIDTH, food.length());
        assertTrue(food.endsWith("240.00"), "the line total, not the unit price: [" + food + "]");
    }

    @Test
    void aLongTitleWrapsAndAFoodlessSaleHasNoFoodSection() {
        String text = ReceiptWriter.format(receipt(List.of()));

        assertFalse(text.contains("FOOD"), "no food lines means no food heading");
        assertFalse(text.contains("The Quiet Cartographer of the Northern Railways"),
                "a long title must wrap rather than print as one oversize line");
        assertTrue(text.contains("The Quiet Cartographer of the"), "the title must still be readable");
    }

    @Test
    void writingTheSlipNamesTheFileAfterTheBooking(@TempDir Path folder) throws Exception {
        Receipt paid = receipt(List.of(
                new ConcessionLine("Popcorn", ItemCategory.SNACK, 2, new BigDecimal("120.00"))));

        Path file = ReceiptWriter.write(paid, folder);

        assertEquals(folder.resolve("BK-0042.txt"), file);
        String written = Files.readString(file);
        assertEquals(ReceiptWriter.format(paid), written);
        assertTrue(written.lines().allMatch(line -> line.length() <= ReceiptWriter.WIDTH));
    }
}
