package com.cinebook.ui;

import com.cinebook.dao.SeatDao;
import com.cinebook.dao.ShowtimeDao;
import com.cinebook.dao.StaffDao;
import com.cinebook.model.BoxOfficeOperator;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.service.AuthService;
import com.cinebook.service.BookingService;
import com.cinebook.service.CheckoutService;
import com.cinebook.util.Db;
import com.cinebook.util.ReceiptWriter;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-in screen driven the way a person drives it: text into the two fields,
 * press the button, look at what the window does next.
 *
 * <p>{@link com.cinebook.service.AuthService} is already covered directly by
 * {@code AuthServiceTest}. What this class adds is the seam those tests cannot see —
 * that the controls on the screen reach the right service call, that a refusal lands
 * in the message label instead of a stack trace, and that the rail a role gets after
 * signing in is the rail that role is allowed.
 *
 * <p>Runs on the FX application thread, so it needs a display and is excluded from
 * {@code mvn test}: run it with {@code mvn test -Dgroups=ui}.
 */
@Tag("ui")
class LoginFlowTest {

    private static final Db DB = Db.get();

    /** Null when the attempted sign-in was refused; the message the person would read. */
    private record Attempt(StaffUser user, String message, String passwordAfterTyping) {
        boolean refused() {
            return user == null;
        }
    }

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            // The tests decide when the last window closes, not the toolkit.
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(30, TimeUnit.SECONDS), "the JavaFX toolkit did not start");
    }

    @AfterAll
    static void stopToolkit() {
        Platform.exit();
        DB.close();
    }

    @Test
    void operatorSignsInThroughTheFormAndGetsTheOperatorRail() {
        Race outcome = runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            Attempt attempt = signIn(auth, "operator", "operator123");
            return new Race(attempt, auth.currentUser().isPresent(), navFor(auth, attempt.user()));
        });

        assertFalse(outcome.attempt().refused(), "the seeded operator must sign in: " + outcome.attempt().message());
        assertInstanceOf(BoxOfficeOperator.class, outcome.attempt().user(),
                "the role on the row, not a default, decides the subclass");
        assertTrue(outcome.signedIn(), "a successful sign-in must leave someone signed in");
        assertEquals(List.of("Schedule", "Bookings"), outcome.nav(),
                "an operator has no admin sections to press");
    }

    @Test
    void adminSignsInAndSeesTheAdminSections() {
        Race outcome = runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            Attempt attempt = signIn(auth, "admin", "admin123");
            return new Race(attempt, auth.currentUser().isPresent(), navFor(auth, attempt.user()));
        });

        assertFalse(outcome.attempt().refused(), "the seeded admin must sign in: " + outcome.attempt().message());
        assertEquals(List.of("Schedule", "Bookings", "Programme", "Reports"), outcome.nav());
    }

    @Test
    void scheduleGroupsEachDayUnderItsOwnDatePanel() {
        // The screen used to head the list with today's date and then print tomorrow's
        // screenings underneath it. One panel per show date is the fix, so the number of
        // panels is compared with the dates the DAO returns, not with a hard-coded 8.
        ScheduleShape shape = runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            StaffUser operator = signIn(auth, "operator", "operator123").user();
            Parent shell = new ShellView(auth, operator, () -> { }).root();
            attach(shell);

            Set<Node> panels = shell.lookupAll(".date-panel");
            int todayPanels = (int) panels.stream()
                    .filter(panel -> panel.getStyleClass().contains("today"))
                    .count();
            int selectedPanels = shell.lookupAll(".date-panel.selected").size();
            Set<String> panelDays = shell.lookupAll(".date-day").stream()
                    .map(node -> ((Label) node).getText())
                    .collect(Collectors.toSet());
            Set<String> showDays = new ShowtimeDao(DB).upcoming(40).stream()
                    .map(show -> String.valueOf(show.showDate().getDayOfMonth()))
                    .collect(Collectors.toSet());
            return new ScheduleShape(panels.size(), todayPanels, selectedPanels,
                    panelDays, showDays);
        });

        assertFalse(shape.panels() == 0, "the schedule must offer a panel per day");
        assertEquals(1, shape.todayPanels(), "exactly one panel is today's");
        assertEquals(1, shape.selectedPanels(), "exactly one panel is selected");
        assertEquals(shape.showDays(), shape.panelDays(),
                "one panel per show date, and no panel without screenings");
        assertEquals(shape.showDays().size(), shape.panels(),
                "the panel count must be the number of distinct show dates");
    }

    @Test
    void clickingADatePanelShowsOnlyThatDaysScreenings() {
        runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            StaffUser operator = signIn(auth, "operator", "operator123").user();
            Parent shell = new ShellView(auth, operator, () -> { }).root();
            attach(shell);

            List<Button> panels = shell.lookupAll(".date-panel").stream()
                    .map(node -> (Button) node).toList();
            Button other = panels.stream()
                    .filter(panel -> !panel.getStyleClass().contains("selected"))
                    .findFirst().orElseThrow();
            LocalDate day = (LocalDate) other.getUserData();
            other.fire();

            assertTrue(other.getStyleClass().contains("selected"),
                    "the panel that was pressed must become the selected one");
            assertEquals(1, shell.lookupAll(".date-panel.selected").size());

            List<String> shown = shell.lookupAll(".schedule-row").stream()
                    .map(node -> (HBox) ((Button) node).getGraphic())
                    .map(row -> ((Label) row.getChildrenUnmodifiable().get(0)).getText())
                    .sorted().toList();
            List<String> expected = new ShowtimeDao(DB).upcoming(40).stream()
                    .filter(show -> show.showDate().equals(day))
                    .map(show -> show.movie().title())
                    .sorted().toList();

            assertFalse(shown.isEmpty(), "the chosen day has screenings in the seed");
            assertEquals(expected, shown, "only the chosen day's screenings may be listed");
            return null;
        });
    }

    @Test
    void openingAScreeningShowsItsSeatMapAndBackKeepsTheDay() {
        runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            StaffUser operator = signIn(auth, "operator", "operator123").user();
            Parent shell = new ShellView(auth, operator, () -> { }).root();
            attach(shell);

            // Open a day that is not today, so "back" has a date worth keeping.
            Button panel = shell.lookupAll(".date-panel").stream()
                    .map(node -> (Button) node)
                    .filter(node -> !node.getStyleClass().contains("selected"))
                    .findFirst().orElseThrow();
            LocalDate day = (LocalDate) panel.getUserData();
            panel.fire();

            Showtime first = new ShowtimeDao(DB).upcoming(40).stream()
                    .filter(show -> show.showDate().equals(day))
                    .findFirst().orElseThrow();

            Button row = (Button) shell.lookup(".schedule-row");
            assertNotNull(row, "the schedule must offer a row to open");
            row.fire();
            // The map lives in a scroll pane, whose content is only in the graph once
            // the skin has laid it out — the same pulse the window would run.
            shell.applyCss();
            shell.layout();

            assertNotNull(shell.lookup(".seat-page"), "a schedule row must open the seat map");
            assertEquals(96, shell.lookupAll(".seat").size(), "the seed auditorium holds 96 seats");
            assertEquals(first.seatsSold(), shell.lookupAll(".seat-sold").size(),
                    "the map's sold seats must be the schedule's sold count");
            assertEquals(3, shell.lookupAll(".seat-section").size(),
                    "regular, premium and recliner each get their own band");

            Button confirmButton = (Button) shell.lookup(".confirm-seats");
            assertNotNull(confirmButton, "the seat map must carry the booking bar");
            assertTrue(confirmButton.isDisabled(), "no picks means there is nothing to hold");

            Button free = shell.lookupAll(".seat").stream()
                    .map(node -> (Button) node)
                    .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                    .findFirst().orElseThrow();
            Seat chosen = (Seat) free.getUserData();
            free.fire();
            assertTrue(free.getStyleClass().contains("seat-picked"),
                    "picking a free seat must mark it on the map");
            String report = ((Label) shell.lookup(".seat-status")).getText();
            String className = chosen.seatClass().name().charAt(0)
                    + chosen.seatClass().name().substring(1).toLowerCase(java.util.Locale.ENGLISH);
            assertTrue(report.contains(chosen.label()), "the report must name the picked seat");
            assertTrue(report.contains(className), "the report must name the seat's class");
            assertTrue(report.contains(chosen.priceFor(first).toPlainString()),
                    "the report must carry the seat's price");
            assertTrue(report.contains("picked for preview"),
                    "a pick must say it is not a hold yet");

            // The booking footer is the reference's bar: seats, class, total, action.
            Label tally = (Label) shell.lookup(".seat-tally");
            Label classLine = (Label) shell.lookup(".seat-class");
            Label total = (Label) shell.lookup(".seat-total");
            assertNotNull(tally, "the seat map must carry the booking bar");
            assertTrue(tally.getText().contains("SELECTED SEATS: 1"), tally.getText());
            assertTrue(tally.getText().contains(chosen.label()), tally.getText());
            assertEquals(className.toUpperCase(java.util.Locale.ENGLISH) + " SEATS",
                    classLine.getText(), "one class picked names that class");
            assertTrue(total.getText().contains(money(chosen.priceFor(first))), total.getText());
            assertFalse(confirmButton.isDisabled(),
                    "one pick is enough to offer the hold");

            Button second = shell.lookupAll(".seat").stream()
                    .map(node -> (Button) node)
                    .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                    .filter(seat -> seat != free)
                    .findFirst().orElseThrow();
            Seat secondSeat = (Seat) second.getUserData();
            second.fire();
            assertEquals("SELECTED SEATS: 2 (" + chosen.label() + ", " + secondSeat.label() + ")",
                    tally.getText(), "the bar lists every picked seat in order");
            assertTrue(total.getText().contains(money(
                            chosen.priceFor(first).add(secondSeat.priceFor(first)))),
                    "the total must be the sum of the picked seats, not one price");

            Button sold = shell.lookupAll(".seat-sold").stream()
                    .map(node -> (Button) node)
                    .findFirst().orElse(null);
            if (sold != null) {
                sold.fire();
                assertTrue(((Label) shell.lookup(".seat-status")).getText().contains("sold"),
                        "a sold seat must say why it cannot be picked");
            }

            ((Button) shell.lookup(".back-link")).fire();
            assertNull(shell.lookup(".seat-page"), "back must restore the schedule");
            Button selected = (Button) shell.lookup(".date-panel.selected");
            assertEquals(day, selected.getUserData(), "coming back must keep the day that was open");
            // The rebuilt strip has no skin yet, so read the graphic directly rather
            // than asking lookup() to walk through a control that was never laid out.
            HBox dayLine = (HBox) ((VBox) selected.getGraphic()).getChildren().get(1);
            assertEquals(String.valueOf(day.getDayOfMonth()), ((Label) dayLine.getChildren().get(0)).getText());
            return null;
        });
    }

    @Test
    void wrongPasswordIsReportedOnTheFormAndLeavesNobodySignedIn() {
        Race outcome = runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            Attempt attempt = signIn(auth, "admin", "not-the-password");
            return new Race(attempt, auth.currentUser().isPresent(), List.of());
        });

        assertTrue(outcome.attempt().refused(), "a wrong password must not sign anyone in");
        assertFalse(outcome.attempt().message().isBlank(), "the screen must say something");
        assertTrue(outcome.attempt().message().contains("do not match"), outcome.attempt().message());
        assertEquals("", outcome.attempt().passwordAfterTyping(),
                "the mistyped password must not stay on screen");
        assertFalse(outcome.signedIn(), "a refused attempt leaves no session behind");
    }

    @Test
    void anUnknownUserReadsExactlyTheSameMessageAsAWrongPassword() {
        // If these two ever differ, the form becomes a way to discover real usernames.
        String wrongPassword = runOnFx(() ->
                signIn(new AuthService(new StaffDao(DB)), "admin", "nope").message());
        String unknownUser = runOnFx(() ->
                signIn(new AuthService(new StaffDao(DB)), "nobody", "nope").message());

        assertEquals(wrongPassword, unknownUser,
                "the screen must not reveal whether an account exists");
    }

    @Test
    void signingOutFromTheRailLandsBackOnTheForm() {
        int signOuts = runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            StaffUser operator = signIn(auth, "operator", "operator123").user();
            int[] count = {0};
            Parent rail = new ShellView(auth, operator, () -> count[0]++).root();
            attach(rail);

            Node link = rail.lookup(".link-button");
            assertInstanceOf(Button.class, link, "the rail must have a sign-out control");
            assertEquals("Sign out", ((Button) link).getText());
            ((Button) link).fire();
            return count[0];
        });

        assertEquals(1, signOuts, "the rail's sign-out must reach the window");
    }

    @Test
    void holdingSeatsOpensCheckoutAndFoodMovesTheTotal() throws Exception {
        int[] held = {0};
        Parent[] screen = new Parent[1];
        try {
            BigDecimal picked = runOnFx(() -> {
                AuthService auth = new AuthService(new StaffDao(DB));
                StaffUser operator = signIn(auth, "operator", "operator123").user();
                Parent shell = new ShellView(auth, operator, () -> { }).root();
                screen[0] = shell;
                attach(shell);
                openFirstScreening(shell);

                List<Button> free = shell.lookupAll(".seat").stream()
                        .map(node -> (Button) node)
                        .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                        .limit(2).toList();
                assertEquals(2, free.size(), "the seed must leave two seats free");
                for (Button seat : free) {
                    seat.fire();
                }
                BigDecimal total = amountIn(((Label) shell.lookup(".seat-total")).getText());

                ((Button) shell.lookup(".confirm-seats")).fire();
                return total;
            });

            // The hold is a background transaction now: wait for its completion to
            // land on the FX thread before reading the map or the panel. The probe
            // lays out first — the panel lives in a ScrollPane, whose content only
            // joins the graph once its skin has run.
            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".checkout-panel") != null;
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                Button confirm = (Button) shell.lookup(".confirm-seats");
                held[0] = Integer.parseInt(confirm.getText().replaceAll("\\D+", ""));
                assertTrue(held[0] > 0, "Confirm must hold the seats: " + confirm.getText());
                assertNotNull(shell.lookup(".checkout-panel"),
                        "a successful hold must open the checkout panel beside the map");
                assertTrue(((Label) shell.lookup(".checkout-ref")).getText().contains("BK-"),
                        "the panel must name the booking it is selling");

                Label total = (Label) shell.lookup(".checkout-total");
                assertEquals(0, picked.compareTo(amountIn(total.getText())),
                        "the panel total must be the picked seats' sum: " + total.getText());

                VBox card = (VBox) shell.lookup(".concession-card");
                assertNotNull(card, "the panel must offer the concession grid");
                BigDecimal price = amountIn(((Label) card.getChildren().get(1)).getText());
                Button plus = (Button) card.lookup(".stepper-plus");
                Button minus = (Button) card.lookup(".stepper-minus");
                Label badge = (Label) card.lookup(".stepper-badge");
                assertNotNull(plus, "a food tile must carry a stepper");
                assertNotNull(minus, "a food tile must carry a stepper");
                assertEquals("0", badge.getText(), "an untouched line starts at zero");
                assertTrue(minus.isDisabled(), "a line at zero cannot go below zero");

                plus.fire();
                settle(shell);
                assertEquals("1", badge.getText(), "the badge must count the line");
                assertTrue(card.getStyleClass().contains("selected"),
                        "a chosen line must read as chosen");
                assertEquals(0, picked.add(price).compareTo(amountIn(total.getText())),
                        "one item must add its price to the running total: "
                                + total.getText());

                plus.fire();
                settle(shell);
                assertEquals("2", badge.getText(), "a second press must count two");
                assertEquals(0, picked.add(price.multiply(BigDecimal.valueOf(2)))
                                .compareTo(amountIn(total.getText())),
                        "two items must cost twice one, not the same line twice: "
                                + total.getText());
                Label units = (Label) shell.lookup(".checkout-count-badge");
                assertEquals("2", units.getText(),
                        "the section badge must total the units on the order");
                assertTrue(units.isVisible(),
                        "the badge must be on screen once the order has food on it");

                try (Db.Handle handle = DB.borrow();
                     Connection connection = handle.connection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT quantity FROM booking_concession WHERE booking_id = ?")) {
                    statement.setInt(1, held[0]);
                    try (ResultSet row = statement.executeQuery()) {
                        assertTrue(row.next(), "food must be written to the hold, not kept in the panel");
                        assertEquals(2, row.getInt("quantity"),
                                "the stepper's count is what the database holds");
                    }
                }

                minus.fire();
                settle(shell);
                assertEquals("1", badge.getText(), "the stepper must come back down");
                assertEquals(0, picked.add(price).compareTo(amountIn(total.getText())),
                        "taking one back must take its price with it: " + total.getText());

                Button upi = shell.lookupAll(".pay-chip").stream()
                        .map(node -> (Button) node)
                        .filter(node -> "Upi".equals(node.getText()))
                        .findFirst().orElseThrow();
                upi.fire();
                assertTrue(upi.getStyleClass().contains("selected"),
                        "the pressed method must be the selected one");
                return null;
            });
        } finally {
            deleteBooking(held[0]);
        }
    }

    @Test
    void payingThroughThePanelShowsThePrintedReceiptAndConfirmsTheBooking() throws Exception {
        int[] held = {0};
        Parent[] screen = new Parent[1];
        String[] label = {""};
        try {
            // 1. Pick a seat and fire the hold. The transaction runs off the FX thread.
            runOnFx(() -> {
                AuthService auth = new AuthService(new StaffDao(DB));
                StaffUser operator = signIn(auth, "operator", "operator123").user();
                Parent shell = new ShellView(auth, operator, () -> { }).root();
                screen[0] = shell;
                attach(shell);
                openFirstScreening(shell);

                Button free = shell.lookupAll(".seat").stream()
                        .map(node -> (Button) node)
                        .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                        .findFirst().orElseThrow();
                label[0] = ((Seat) free.getUserData()).label();
                free.fire();
                ((Button) shell.lookup(".confirm-seats")).fire();
                return null;
            });

            // 2. Wait for the hold to commit and open the panel, laying out first so
            //    the ScrollPane's content is in the graph the lookup walks.
            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".checkout-panel") != null;
            });

            // 3. Fire the payment. It too is a background transaction, and the
            //    button must be disabled for its whole duration.
            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                held[0] = Integer.parseInt(((Button) shell.lookup(".confirm-seats"))
                        .getText().replaceAll("\\D+", ""));

                Button pay = (Button) shell.lookup(".checkout-pay");
                assertNotNull(pay, "the checkout panel must offer the payment button");
                assertFalse(pay.isDisable(), "an untouched pay button must be live");
                pay.fire();
                assertTrue(pay.isDisable(),
                        "the pay button must disable itself while the payment runs, "
                                + "so a second click cannot race the procedure");
                return null;
            });

            // 4. Wait for the receipt stub, which only appears once the procedure returns.
            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".receipt-preview") != null;
            });

            // 4b. The stub slides in. Wait for it to come to rest — an entrance
            //     animation that never finishes would leave the slip unreadable, which
            //     is worse than no animation at all.
            awaitFx(() -> {
                settle(screen[0]);
                Node stub = screen[0].lookup(".receipt-stub");
                return stub != null && stub.getOpacity() >= 0.999
                        && Math.abs(stub.getTranslateY()) < 0.001;
            });

            // 5. Assert on the slip and on the rows the procedure wrote.
            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);

                assertTrue(((Label) shell.lookup(".checkout-held")).getText().startsWith("SOLD SEATS:"),
                        "after payment the heading must stop calling the seats held");

                Node stub = shell.lookup(".receipt-stub");
                assertNotNull(stub, "the slip must be its own block in the rail");
                assertEquals(1.0, stub.getOpacity(), 0.001,
                        "the slip must end fully opaque, not mid-fade");
                assertEquals(0.0, stub.getTranslateY(), 0.001,
                        "the slip must end at rest, with no offset left");

                TextArea preview = (TextArea) shell.lookup(".receipt-preview");
                assertNotNull(preview, "a successful payment must show the printed slip");
                String slip = preview.getText();
                assertEquals("CINEBOOK", slip.lines().findFirst().orElseThrow().trim(),
                        "the slip must be the receipt, not a dialog");
                assertTrue(slip.contains("BK-" + String.format("%04d", held[0])),
                        "the slip must carry the booking reference");
                assertTrue(slip.contains(label[0]), "the slip must name the seat that was sold");
                assertTrue(slip.contains("TOTAL"));
                for (String line : slip.lines().toList()) {
                    assertTrue(line.length() <= 40, "every slip line must fit the printer: [" + line + "]");
                }

                try (Db.Handle handle = DB.borrow();
                     Connection connection = handle.connection();
                     PreparedStatement statement = connection.prepareStatement("""
                             SELECT b.status, COUNT(p.payment_id) AS paid
                               FROM booking b
                               LEFT JOIN payment p ON p.booking_id = b.booking_id
                              WHERE b.booking_id = ?
                              GROUP BY b.status
                             """)) {
                    statement.setInt(1, held[0]);
                    try (ResultSet row = statement.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals("CONFIRMED", row.getString("status"));
                        assertEquals(1, row.getInt("paid"), "one payment row per sale");
                    }
                }
                return null;
            });
        } finally {
            deleteBooking(held[0]);
        }
    }

    @Test
    void thePaymentFooterStaysOnScreenWhileThePanelScrolls() throws Exception {
        int[] held = {0};
        Parent[] screen = new Parent[1];
        try {
            runOnFx(() -> {
                AuthService auth = new AuthService(new StaffDao(DB));
                StaffUser operator = signIn(auth, "operator", "operator123").user();
                Parent shell = new ShellView(auth, operator, () -> { }).root();
                screen[0] = shell;
                // A window deliberately too short for the panel's content: the food
                // grid alone is taller than what is left under the heading, which is
                // exactly the case where a pay button below the fold is unpayable.
                attach(shell, 430);
                openFirstScreening(shell);

                Button free = shell.lookupAll(".seat").stream()
                        .map(node -> (Button) node)
                        .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                        .findFirst().orElseThrow();
                free.fire();
                ((Button) shell.lookup(".confirm-seats")).fire();
                return null;
            });

            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".checkout-panel") != null;
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                held[0] = Integer.parseInt(((Button) shell.lookup(".confirm-seats"))
                        .getText().replaceAll("\\D+", ""));

                ScrollPane scroller = (ScrollPane) shell.lookup(".checkout-scroll");
                VBox footer = (VBox) shell.lookup(".checkout-footer");
                Button pay = (Button) shell.lookup(".checkout-pay");
                assertNotNull(scroller, "the panel must scroll its blocks");
                assertNotNull(footer, "the panel must have a pinned footer");
                assertNotNull(pay, "the footer must carry the pay button");

                assertFalse(isInside(scroller.getContent(), pay),
                        "the pay button must sit outside the scrolling content");
                assertTrue(isInside(footer, pay),
                        "the pay button must be the footer's, not the content's");

                Label total = (Label) shell.lookup(".checkout-total");
                assertTrue(isInside(footer, total),
                        "the total belongs above the button that charges it");

                double contentHeight = scroller.getContent().getLayoutBounds().getHeight();
                double viewportHeight = scroller.getViewportBounds().getHeight();
                assertTrue(contentHeight > viewportHeight,
                        "the panel's content must overflow for this test to prove anything: "
                                + contentHeight + " vs " + viewportHeight);

                double windowHeight = shell.getScene().getHeight();
                assertTrue(pay.localToScene(pay.getBoundsInLocal()).getMaxY() <= windowHeight + 0.5,
                        "the pay button must stay inside the window however tall the order is");
                assertTrue(footer.localToScene(footer.getBoundsInLocal()).getMinY() >= 0,
                        "the footer must not be pushed off the top either");
                return null;
            });
        } finally {
            deleteBooking(held[0]);
        }
    }

    @Test
    void releasingAHoldFromThePanelTakesItDownAndFreesTheSeats() throws Exception {
        int[] held = {0};
        String[] label = {""};
        Parent[] screen = new Parent[1];
        try {
            runOnFx(() -> {
                AuthService auth = new AuthService(new StaffDao(DB));
                StaffUser operator = signIn(auth, "operator", "operator123").user();
                Parent shell = new ShellView(auth, operator, () -> { }).root();
                screen[0] = shell;
                attach(shell);
                openFirstScreening(shell);

                Button free = shell.lookupAll(".seat").stream()
                        .map(node -> (Button) node)
                        .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                        .findFirst().orElseThrow();
                label[0] = ((Seat) free.getUserData()).label();
                free.fire();
                ((Button) shell.lookup(".confirm-seats")).fire();
                return null;
            });

            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".checkout-rail") != null;
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                held[0] = Integer.parseInt(((Button) shell.lookup(".confirm-seats"))
                        .getText().replaceAll("\\D+", ""));

                Button heldSeat = seatNamed(shell, label[0]);
                assertNotNull(heldSeat, "the held seat must still be on the map");
                assertTrue(heldSeat.getStyleClass().contains("seat-held"),
                        "the map must show " + label[0] + " as held by this booking");
                assertEquals(1, shell.lookupAll(".seat-held").size(),
                        "one hold puts one seat in the held state");
                assertNull(heldSeat.getOnAction(),
                        "a held seat must stop accepting picks until it is released");

                Button release = shell.lookupAll(".link-button").stream()
                        .map(node -> (Button) node)
                        .filter(node -> "Release seats".equals(node.getText()))
                        .findFirst().orElseThrow();
                release.fire();
                // The transaction is off the FX thread; its completion tears the rail
                // down and redraws the map.
                return null;
            });

            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".checkout-rail") == null;
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                assertNull(shell.lookup(".checkout-rail"),
                        "a released hold must take the checkout rail with it");
                assertEquals(0, ((Button) shell.lookup(".confirm-seats"))
                                .getText().replaceAll("\\D+", "").length(),
                        "the bar must go back to offering a hold, not naming one");
                assertTrue(((Label) shell.lookup(".seat-status")).getText().contains("released"),
                        "the status line must say the seats came back");
                assertTrue(((Button) shell.lookup(".confirm-seats")).isDisabled(),
                        "with the rail gone and nothing picked, Confirm has nothing to do");

                // The seat itself: the redraw is only honest if the button that said
                // "held" is a pickable seat again, not merely repainted.
                assertTrue(shell.lookupAll(".seat-held").isEmpty(),
                        "no seat may still read as held after the release");
                Button freed = seatNamed(shell, label[0]);
                assertNotNull(freed, "the released seat must still be on the map");
                assertFalse(freed.getStyleClass().contains("seat-held"),
                        label[0] + " must stop reading as held");
                assertNotNull(freed.getOnAction(),
                        "the release must hand the seat's click handler back");

                try (Db.Handle handle = DB.borrow();
                     Connection connection = handle.connection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT status FROM booking WHERE booking_id = ?")) {
                    statement.setInt(1, held[0]);
                    try (ResultSet row = statement.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals("CANCELLED", row.getString("status"),
                                "releasing is a cancellation the till can account for");
                    }
                }

                // Release deletes the ticket rows, so the seat is on sale in the data
                // as well as on the map — the redraw is reading a real release.
                try (Db.Handle handle = DB.borrow();
                     Connection connection = handle.connection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT COUNT(*) AS remaining FROM ticket WHERE booking_id = ?")) {
                    statement.setInt(1, held[0]);
                    try (ResultSet row = statement.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(0, row.getInt("remaining"),
                                "a released hold must not keep its seat rows");
                    }
                }

                // And the map agrees with the data: the seat can be sold again.
                freed.fire();
                settle(shell);
                assertTrue(freed.getStyleClass().contains("seat-picked"),
                        "the freed seat must be pickable again, not just painted free");
                assertTrue(((Label) shell.lookup(".seat-tally")).getText().contains(label[0]),
                        "the bar must count the re-picked seat");
                assertFalse(((Button) shell.lookup(".confirm-seats")).isDisabled(),
                        "a re-picked seat must offer a hold again");
                return null;
            });
        } finally {
            deleteBooking(held[0]);
        }
    }

    @Test
    void bookingsScreenSearchesByReferenceAndCancelsWithARefund() throws Exception {
        // The first screening of the day may have started already, and a started show
        // has no cancel button by design. Pick one that is still hours away.
        Showtime show = new ShowtimeDao(DB).upcoming(200).stream()
                .filter(screening -> screening.startsAt()
                        .isAfter(LocalDateTime.now().plusHours(3)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the seed has no refundable screening"));
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService service = new BookingService(DB);
        BookingService.Held held = service.hold(show, List.of(firstFreeSeat(show)), operator);
        int bookingId = held.bookingId();
        try {
            new CheckoutService(DB).pay(bookingId, PaymentMethod.CARD);
            String reference = String.format("BK-%04d", bookingId);
            Parent[] screen = new Parent[1];

            runOnFx(() -> {
                AuthService auth = new AuthService(new StaffDao(DB));
                screen[0] = new ShellView(auth, operator, () -> { }).root();
                attach(screen[0]);
                Button nav = buttonWithText(screen[0], "Bookings");
                assertNotNull(nav, "the operator's rail must offer Bookings");
                nav.fire();
                return null;
            });

            awaitFx(() -> {
                // The register lives inside a ScrollPane; its viewport joins the
                // scene graph on the first layout, which a test must ask for.
                settle(screen[0]);
                return screen[0].lookup(".panel-card") != null;
            });

            runOnFx(() -> {
                TextField search = (TextField) screen[0].lookup(".text-field");
                assertNotNull(search, "the register must carry a search field");
                search.setText(reference);
                return null;
            });

            // Search is debounced and the query runs on FxTasks, so wait for the
            // one row the reference names rather than for a sleep.
            awaitFx(() -> {
                settle(screen[0]);
                Set<Node> cards = screen[0].lookupAll(".panel-card");
                if (cards.size() != 1) {
                    return false;
                }
                Label shown = (Label) cards.iterator().next().lookup(".booking-ref");
                return shown != null && reference.equals(shown.getText());
            });

            runOnFx(() -> {
                Button cancel = buttonWithText(screen[0], "Cancel with refund");
                assertNotNull(cancel, "a refundable booking must offer cancellation");
                cancel.fire();
                settle(screen[0]);
                Button execute = buttonWithText(screen[0], "Execute cancellation");
                assertNotNull(execute, "the confirmation must show the refund before committing");
                execute.fire();
                return null;
            });

            assertTrue(waitForBookingStatus(bookingId, "CANCELLED", 10_000),
                    "confirming the dialog must commit the cancellation");

            try (Db.Handle handle = DB.borrow();
                 Connection connection = handle.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT status, refund_amount, cancelled_by_staff_id, "
                                 + "(SELECT COUNT(*) FROM ticket WHERE booking_id = b.booking_id) AS seats "
                                 + "FROM booking b WHERE booking_id = ?")) {
                statement.setInt(1, bookingId);
                try (ResultSet row = statement.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("CANCELLED", row.getString("status"));
                    assertTrue(row.getBigDecimal("refund_amount").signum() > 0,
                            "a paid ticket refunds part of its ticket amount");
                    assertEquals(operator.staffId(), row.getInt("cancelled_by_staff_id"),
                            "the refund belongs to the clerk who executed it");
                    assertEquals(0, row.getInt("seats"), "the seats must be released");
                }
            }

            awaitFx(() -> screen[0].lookup(".badge-cancelled") != null);
        } finally {
            deleteBooking(bookingId);
        }
    }

    @Test
    void printingTheReceiptWritesA40ColumnSlipAndNamesTheFile() throws Exception {
        int[] held = {0};
        Parent[] screen = new Parent[1];
        Path[] written = {null};
        try {
            runOnFx(() -> {
                AuthService auth = new AuthService(new StaffDao(DB));
                StaffUser operator = signIn(auth, "operator", "operator123").user();
                Parent shell = new ShellView(auth, operator, () -> { }).root();
                screen[0] = shell;
                attach(shell);
                openFirstScreening(shell);

                Button free = shell.lookupAll(".seat").stream()
                        .map(node -> (Button) node)
                        .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                        .findFirst().orElseThrow();
                free.fire();
                ((Button) shell.lookup(".confirm-seats")).fire();
                return null;
            });

            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".checkout-rail") != null;
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                held[0] = Integer.parseInt(((Button) shell.lookup(".confirm-seats"))
                        .getText().replaceAll("\\D+", ""));
                ((Button) shell.lookup(".checkout-pay")).fire();
                return null;
            });

            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".receipt-preview") != null;
            });

            // The print button lives in the block that slides in: it must be at rest
            // before the test presses it.
            awaitFx(() -> {
                settle(screen[0]);
                Node stub = screen[0].lookup(".receipt-stub");
                return stub != null && stub.getOpacity() >= 0.999;
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                Button print = shell.lookupAll(".button").stream()
                        .map(node -> (Button) node)
                        .filter(button -> "Print receipt".equals(button.getText()))
                        .findFirst().orElseThrow();
                print.fire();
                settle(shell);

                Label message = (Label) shell.lookup(".checkout-message");
                assertNotNull(message, "the panel must say where the receipt went");
                String note = message.getText();
                assertTrue(note.startsWith("Receipt saved to "),
                        "printing must report the file it wrote, not stay silent: " + note);
                written[0] = Path.of(note.substring("Receipt saved to ".length()).trim());
                return null;
            });

            // The assertion is on the file the panel named, read back from disk — the
            // preview in the panel proves nothing about what a printer would receive.
            assertNotNull(written[0], "print must name the file it wrote");
            assertTrue(Files.isRegularFile(written[0]),
                    "the panel named a file that does not exist: " + written[0]);

            List<String> slip = Files.readAllLines(written[0]);
            assertFalse(slip.isEmpty(), "a printed receipt must have lines");
            assertEquals("CINEBOOK", slip.get(0).trim(),
                    "the slip must start with the house name");
            String text = String.join("\n", slip);
            assertTrue(text.contains("BK-" + String.format("%04d", held[0])),
                    "the printed slip must carry this booking's reference");
            assertTrue(slip.stream().anyMatch(line -> line.startsWith("TOTAL")),
                    "the printed slip must total the sale");
            for (String line : slip) {
                assertTrue(line.length() <= ReceiptWriter.WIDTH,
                        "every printed line must fit the thermal printer: [" + line + "]");
            }
        } finally {
            deletePrinted(written[0]);
            deleteBooking(held[0]);
        }
    }

    @Test
    void releasingAHoldAnotherTillAlreadyPaidIsRefusedAndThePanelStays() throws Exception {
        int[] held = {0};
        Parent[] screen = new Parent[1];
        try {
            runOnFx(() -> {
                AuthService auth = new AuthService(new StaffDao(DB));
                StaffUser operator = signIn(auth, "operator", "operator123").user();
                Parent shell = new ShellView(auth, operator, () -> { }).root();
                screen[0] = shell;
                attach(shell);
                openFirstScreening(shell);

                Button free = shell.lookupAll(".seat").stream()
                        .map(node -> (Button) node)
                        .filter(seat -> !seat.getStyleClass().contains("seat-sold"))
                        .findFirst().orElseThrow();
                free.fire();
                ((Button) shell.lookup(".confirm-seats")).fire();
                return null;
            });

            awaitFx(() -> {
                settle(screen[0]);
                return screen[0].lookup(".checkout-rail") != null;
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                held[0] = Integer.parseInt(((Button) shell.lookup(".confirm-seats"))
                        .getText().replaceAll("\\D+", ""));

                // The other till takes the money behind this panel's back. The demo
                // runs two windows on the same hold, so this is the race the release
                // guard exists for — not a contrived state.
                assertNotNull(new CheckoutService(DB).pay(held[0], PaymentMethod.CARD),
                        "the other till's payment must go through");

                Button release = shell.lookupAll(".link-button").stream()
                        .map(node -> (Button) node)
                        .filter(node -> "Release seats".equals(node.getText()))
                        .findFirst().orElseThrow();
                release.fire();
                return null;
            });

            // The refusal is a background round trip, and it lands in the footer.
            awaitFx(() -> {
                settle(screen[0]);
                Label message = (Label) screen[0].lookup(".checkout-message");
                return message != null && message.isVisible() && !message.getText().isBlank();
            });

            runOnFx(() -> {
                Parent shell = screen[0];
                settle(shell);
                assertNotNull(shell.lookup(".checkout-rail"),
                        "a refused release must leave the panel up — the sale is still open");

                Label message = (Label) shell.lookup(".checkout-message");
                assertTrue(message.getText().contains("no longer open"),
                        "the clerk must be told why: " + message.getText());
                assertTrue(message.getStyleClass().contains("checkout-error"),
                        "a refusal must read as an error, not a note");

                // The failure path must hand every control back. A panel left disabled
                // after a refused release is a till that cannot sell anything else.
                assertFalse(((VBox) shell.lookup(".checkout-panel")).isDisabled(),
                        "the form must be usable again after a refused release");
                assertFalse(((Button) shell.lookup(".checkout-pay")).isDisabled(),
                        "the pay button must come back");
                assertFalse(shell.lookupAll(".link-button").stream()
                                .map(node -> (Button) node)
                                .filter(node -> "Release seats".equals(node.getText()))
                                .findFirst().orElseThrow().isDisabled(),
                        "the release link must come back too");

                try (Db.Handle handle = DB.borrow();
                     Connection connection = handle.connection();
                     PreparedStatement statement = connection.prepareStatement("""
                             SELECT b.status,
                                    (SELECT COUNT(*) FROM ticket WHERE booking_id = b.booking_id) AS seats,
                                    (SELECT COUNT(*) FROM payment WHERE booking_id = b.booking_id) AS paid
                               FROM booking b
                              WHERE b.booking_id = ?
                             """)) {
                    statement.setInt(1, held[0]);
                    try (ResultSet row = statement.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals("CONFIRMED", row.getString("status"),
                                "a refused release must not cancel a paid booking");
                        assertEquals(1, row.getInt("seats"),
                                "the rollback must leave the sold seat in place");
                        assertEquals(1, row.getInt("paid"),
                                "the other till's payment must survive the refusal");
                    }
                }
                return null;
            });
        } finally {
            deleteBooking(held[0]);
        }
    }

    // --- the screen, driven -------------------------------------------------

    /** The first seat of this screening that no ticket holds. */
    private static Seat firstFreeSeat(Showtime show) throws Exception {
        SeatDao seats = new SeatDao(DB);
        Set<String> sold = seats.soldLabels(show.showtimeId());
        return seats.byScreen(show.screen().screenId()).stream()
                .filter(seat -> !sold.contains(seat.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the screening has no free seat"));
    }

    private static Button buttonWithText(Parent root, String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    /** Waits from the test thread, so the FxTasks completion can reach the FX thread. */
    private static boolean waitForBookingStatus(int bookingId, String expected, long timeoutMillis)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            try (Db.Handle handle = DB.borrow();
                 Connection connection = handle.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT status FROM booking WHERE booking_id = ?")) {
                statement.setInt(1, bookingId);
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next() && expected.equals(row.getString("status"))) {
                        return true;
                    }
                }
            }
            Thread.sleep(25);
        }
        return false;
    }

    /** The seat button for a label on the current map, or null when it is gone. */
    private static Button seatNamed(Parent shell, String label) {
        for (Node node : shell.lookupAll(".seat")) {
            Button button = (Button) node;
            if (button.getUserData() instanceof Seat seat && seat.label().equals(label)) {
                return button;
            }
        }
        return null;
    }

    /** Opens the first screening on the schedule and waits for the map's skin. */
    private static void openFirstScreening(Parent shell) {
        ((Button) shell.lookup(".schedule-row")).fire();
        settle(shell);
        assertNotNull(shell.lookup(".seat-paper"), "the schedule row must open a seat map");
    }

    /** A scroll pane's content only joins the graph once its skin has laid out. */
    private static void settle(Parent root) {
        root.applyCss();
        root.layout();
        root.applyCss();
        root.layout();
    }

    /**
     * Waits, from the test thread, until the FX thread reports the condition true.
     *
     * <p>Holding and paying now run off the FX thread (docs/PRD.md \u00a711.7), so their
     * completion is a {@code Platform.runLater} that needs a free FX thread to run.
     * Waiting <em>inside</em> a {@code runOnFx} would therefore deadlock: the queue
     * this test is blocking could never turn. Each probe is its own {@code runOnFx},
     * so between probes the FX thread is free to receive that completion.
     */
    private static void awaitFx(java.util.function.Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            if (Boolean.TRUE.equals(runOnFx(condition::get))) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "the FX thread never reached the awaited state \u2014 an async hold or "
                                + "payment did not complete");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the FX thread", interrupted);
            }
        }
    }

    /** ₹750 or 362.50, read out of the label the panel prints. */
    private static BigDecimal amountIn(String text) {
        int mark = text.indexOf('\u20b9');
        assertTrue(mark >= 0, "expected a rupee amount in: " + text);
        String digits = text.substring(mark + 1).replaceAll("[^0-9.]", "");
        return new BigDecimal(digits);
    }

    /**
     * Removes a receipt the test asked the app to print, and the directory with it
     * when that leaves the directory empty. A cleanup failure is reported rather than
     * thrown: it would otherwise replace the test's own result with a teardown noise.
     */
    private static void deletePrinted(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
            Path directory = file.getParent();
            if (directory != null && Files.isDirectory(directory)) {
                try (Stream<Path> left = Files.list(directory)) {
                    if (left.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        } catch (IOException failure) {
            System.err.println("could not remove the test receipt " + file + ": " + failure);
        }
    }

    private static void deleteBooking(int bookingId) throws Exception {
        if (bookingId <= 0) {
            return;
        }
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, bookingId);
            statement.executeUpdate();
        }
    }

    /**
     * Types into the real controls and presses the real button, returning what the
     * person would be looking at. Field order is the form's, not a lookup by prompt
     * text, so a wording change does not silently break the test.
     */
    private static Attempt signIn(AuthService auth, String username, String password) {
        StaffUser[] landed = {null};
        Parent root = new LoginView(auth, user -> landed[0] = user).root();
        attach(root);

        List<TextField> text = new ArrayList<>();
        List<PasswordField> secrets = new ArrayList<>();
        // Not `lookupAll`: that returns a Set, and the form's order is the thing that
        // says which field is the username.
        for (Node node : inOrder(root)) {
            if (node instanceof PasswordField field) {
                secrets.add(field);
            } else if (node instanceof TextField field) {
                text.add(field);
            }
        }
        assertEquals(1, text.size(), "one username field");
        assertEquals(1, secrets.size(), "one password field");

        text.get(0).setText(username);
        secrets.get(0).setText(password);
        submitButton(root).fire();

        Label message = (Label) root.lookup(".error");
        assertTrue(message != null, "the form must have a place to write a refusal");
        return new Attempt(landed[0], message.getText(), secrets.get(0).getText());
    }

    private static List<String> navFor(AuthService auth, StaffUser user) {
        Parent shell = new ShellView(auth, user, () -> { }).root();
        attach(shell);
        List<String> labels = new ArrayList<>();
        for (Node node : shell.lookupAll(".nav-button")) {
            labels.add(((Button) node).getText());
        }
        return labels;
    }

    private static Button submitButton(Parent root) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && "Sign in".equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError("the form has no Sign in button");
    }

    /** Children in the order they appear on screen, deepest last. */
    private static List<Node> inOrder(Parent root) {
        List<Node> ordered = new ArrayList<>();
        collect(root, ordered);
        return ordered;
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, into);
            }
        }
    }

    /** The view's whole-rupee rule, repeated so the assertion is not a substring guess. */
    private static String money(BigDecimal amount) {
        String text = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return text.endsWith(".00") ? text.substring(0, text.length() - 3) : text;
    }

    /** True when {@code node} sits inside {@code ancestor}, at any depth. */
    private static boolean isInside(Node ancestor, Node node) {
        for (Node walk = node; walk != null; walk = walk.getParent()) {
            if (walk == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static void attach(Parent root) {
        attach(root, 780);
    }

    /** Lookup only sees nodes that belong to a scene, so put the screen in one. */
    private static void attach(Parent root, double height) {
        Scene scene = new Scene(root, 1280, height);
        scene.getStylesheets().add(
                java.util.Objects.requireNonNull(LoginFlowTest.class.getResource("/app.css")).toExternalForm());
        root.applyCss();
        root.layout();
    }

    /** What the assertions outside the FX thread are allowed to see. */
    private record Race(Attempt attempt, boolean signedIn, List<String> nav) {
    }

    /** Date panels the schedule built, against the dates the schedule should cover. */
    private record ScheduleShape(int panels, int todayPanels, int selectedPanels,
                                 Set<String> panelDays, Set<String> showDays) {
    }

    private static <T> T runOnFx(java.util.concurrent.Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("the FX thread did not finish in 30s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the FX thread", interrupted);
        }
        if (failure.get() != null) {
            if (failure.get() instanceof Error error) {
                throw error;
            }
            if (failure.get() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(failure.get());
        }
        return result.get();
    }
}
