package com.cinebook.util;

import com.cinebook.model.ConcessionLine;
import com.cinebook.model.Receipt;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The thermal slip. Exactly 40 columns, monospace, amounts right-aligned so the
 * rupees form a straight column; a proportional dialog would hide a ragged
 * receipt, so this is written as plain text and checked at a fixed width
 * (docs/PRD.md §5, §14.2).
 *
 * <p>The default directory is {@code receipts/} beside the running jar. A lab
 * machine can refuse that write — Program Files, a locked profile — so the
 * fallback is {@code ~/CineBook/receipts}, and the panel says which one was used.
 */
public final class ReceiptWriter {

    public static final int WIDTH = 40;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SHOW =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    private ReceiptWriter() {
    }

    /** Writes beside the working directory, falling back to the user's home. */
    public static Path write(Receipt receipt) throws IOException {
        try {
            return write(receipt, Path.of("receipts"));
        } catch (IOException primary) {
            Path fallback = Path.of(System.getProperty("user.home"), "CineBook", "receipts");
            return write(receipt, fallback);
        }
    }

    /** Writes into an explicit directory; the unit test uses a temp dir. */
    public static Path write(Receipt receipt, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(receipt.reference() + ".txt");
        Files.writeString(file, format(receipt));
        return file;
    }

    /** The text a print button sends, and the panel can preview. */
    public static String format(Receipt receipt) {
        List<String> lines = new ArrayList<>();
        lines.add(centre("CINEBOOK"));
        lines.add(centre("CINEMA TICKET"));
        lines.add("-".repeat(WIDTH));
        lines.add(line(receipt.reference(), DATE.format(receipt.paidAt())));
        lines.addAll(wrap(receipt.movieTitle()));
        lines.add(truncate(receipt.screenName() + "  " + SHOW.format(
                java.time.LocalDateTime.of(receipt.showDate(), receipt.startTime()))));
        lines.add(truncate("Customer: " + receipt.customerName()));
        lines.add("-".repeat(WIDTH));

        lines.add("SEATS");
        for (Receipt.SeatLine seat : receipt.seats()) {
            lines.add(line(seat.label() + "  " + pretty(seat.seatClass().name()),
                    money(seat.price())));
        }
        lines.add(line("Tickets", money(receipt.ticketAmount())));

        if (!receipt.concessions().isEmpty()) {
            lines.add("");
            lines.add("FOOD");
            for (ConcessionLine food : receipt.concessions()) {
                lines.add(line(food.name() + " x" + food.quantity(), money(food.lineTotal())));
            }
            lines.add(line("Food", money(receipt.fnbAmount())));
        }

        lines.add("-".repeat(WIDTH));
        lines.add(line("TOTAL", money(receipt.totalAmount())));
        lines.add(line(receipt.method().name(), receipt.txnRef()));
        lines.add("Paid " + DATE.format(receipt.paidAt()));
        lines.add("-".repeat(WIDTH));
        lines.add(centre("Thank you. Enjoy the show."));

        lines.replaceAll(ReceiptWriter::truncate);
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String money(BigDecimal amount) {
        return Money.round(amount).toPlainString();
    }

    private static String line(String left, String right) {
        int room = WIDTH - right.length() - 1;
        String text = left.length() > room ? truncate(left, Math.max(0, room - 2)) + ".." : left;
        return text + " ".repeat(Math.max(1, WIDTH - text.length() - right.length())) + right;
    }

    private static String centre(String text) {
        String value = truncate(text);
        int left = Math.max(0, (WIDTH - value.length()) / 2);
        return " ".repeat(left) + value;
    }

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        String rest = text;
        while (rest.length() > WIDTH) {
            int cut = rest.lastIndexOf(' ', WIDTH);
            if (cut <= 0) {
                cut = WIDTH;
            }
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        lines.add(rest);
        return lines;
    }

    private static String truncate(String text) {
        return truncate(text, WIDTH);
    }

    private static String truncate(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width);
    }

    private static String pretty(String enumName) {
        String lower = enumName.toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
