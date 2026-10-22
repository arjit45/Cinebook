package com.cinebook.ui;

import com.cinebook.model.BookingSummary;
import com.cinebook.model.ConcessionLine;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.service.BookingService;
import com.cinebook.service.RefundPolicy;
import com.cinebook.util.Db;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The Bookings list and cancellation view (docs/PRD.md §2, §3 screen 5, §13.1).
 *
 * <p>The search runs against the whole register, not the page already loaded: a
 * clerk looking up a booking from three weeks ago must not be told it does not
 * exist because it fell outside a client-side filter. Status chips are part of
 * the same query for the same reason.
 *
 * <p>A card carries both names — who sold it and who refunded it — plus the
 * concession lines the PRD asks the detail row to show. The food is read only
 * when the row is expanded, so the list itself stays one grouped query.
 */
public final class BookingsView {

    private static final int PAGE_SIZE = 100;

    private static final DateTimeFormatter SHOW_TIME_FMT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter AUDIT_TIME_FMT =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);

    private static final List<String> FILTERS =
            List.of("ALL", "CONFIRMED", "HELD", "CANCELLED");

    private final BookingService bookingService;
    private final StaffUser clerk;

    private final VBox root = new VBox(16);
    private final TextField searchField = new TextField();
    private final VBox listContainer = new VBox(12);
    private final Label resultCountLabel = new Label();
    private final HBox filterBar = new HBox(8);
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(250));

    private String currentFilter = "ALL";
    private int requestSerial;

    public BookingsView(StaffUser clerk) {
        this.clerk = clerk;
        this.bookingService = new BookingService(Db.get());
        build();
        refresh();
    }

    public Node root() {
        return root;
    }

    private void build() {
        root.setPadding(new Insets(10, 0, 20, 0));

        searchField.setPromptText("Search by patron, phone, date (yyyy-MM-dd) or BK-number…");
        searchField.setPrefWidth(380);
        searchDebounce.setOnFinished(event -> refresh());
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchDebounce.playFromStart());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().add("outline-button");
        refreshBtn.setOnAction(event -> refresh());

        HBox searchBox = new HBox(12, searchField, refreshBtn);
        searchBox.setAlignment(Pos.CENTER_LEFT);

        buildFilterChips();

        HBox topControls = new HBox(16, searchBox, filterBar);
        topControls.setAlignment(Pos.CENTER_LEFT);

        resultCountLabel.getStyleClass().add("muted");

        ScrollPane scrollPane = new ScrollPane(listContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("content-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root.getChildren().addAll(topControls, resultCountLabel, scrollPane);
    }

    private void buildFilterChips() {
        filterBar.getChildren().clear();
        for (String filter : FILTERS) {
            Button chip = new Button(pretty(filter));
            chip.getStyleClass().add("chip-button");
            chip.setUserData(filter);
            chip.setOnAction(event -> {
                currentFilter = filter;
                updateFilterChips();
                refresh();
            });
            filterBar.getChildren().add(chip);
        }
        updateFilterChips();
    }

    private void updateFilterChips() {
        for (Node node : filterBar.getChildren()) {
            Button chip = (Button) node;
            chip.getStyleClass().remove("selected");
            if (currentFilter.equals(chip.getUserData())) {
                chip.getStyleClass().add("selected");
            }
        }
    }

    /** Re-queries the register with the current search text and chip. */
    public void refresh() {
        int serial = ++requestSerial;
        String query = searchField.getText();
        BookingStatus status = "ALL".equals(currentFilter)
                ? null
                : BookingStatus.valueOf(currentFilter);

        setCount("Searching…");
        FxTasks.run(
                () -> bookingService.list(query, status, PAGE_SIZE),
                found -> {
                    if (serial == requestSerial) {
                        render(found);
                    }
                },
                error -> {
                    if (serial == requestSerial) {
                        showError("Failed to load bookings: " + error.getMessage());
                    }
                }
        );
    }

    private void render(List<BookingSummary> found) {
        setCount("Showing " + found.size() + " booking" + (found.size() == 1 ? "" : "s"));
        listContainer.getChildren().clear();

        if (found.isEmpty()) {
            Label empty = new Label("No bookings match that search.");
            empty.getStyleClass().add("muted");
            empty.setPadding(new Insets(30, 0, 30, 0));
            listContainer.getChildren().add(empty);
            return;
        }

        for (BookingSummary booking : found) {
            listContainer.getChildren().add(createBookingCard(booking));
        }
    }

    private Node createBookingCard(BookingSummary b) {
        VBox card = new VBox(10);
        card.getStyleClass().add("panel-card");
        card.setPadding(new Insets(16));

        // Top row: reference, status, and the one action this row offers.
        Label refLabel = new Label(b.reference());
        refLabel.getStyleClass().add("booking-ref");

        Label statusBadge = new Label(b.status().name());
        statusBadge.getStyleClass().add("badge-" + b.status().name().toLowerCase(Locale.ENGLISH));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        LocalDateTime now = LocalDateTime.now();
        HBox header = new HBox(10, refLabel, statusBadge, spacer);
        header.setAlignment(Pos.CENTER_LEFT);

        if (b.canCancel(now)) {
            Button cancelBtn = new Button("Cancel with refund");
            cancelBtn.getStyleClass().add("danger-button");
            cancelBtn.setOnAction(event -> showCancelDialog(b, card, cancelBtn));
            header.getChildren().add(cancelBtn);
        }

        // Details: who bought, what and when, which seats, and the money split.
        HBox details = new HBox(24);
        details.setAlignment(Pos.CENTER_LEFT);

        VBox col1 = new VBox(3,
                new Label(b.customerName()),
                new Label(b.customerPhone())
        );
        col1.getChildren().get(0).getStyleClass().add("booking-value");
        col1.getChildren().get(1).getStyleClass().add("muted");
        col1.setPrefWidth(160);

        VBox col2 = new VBox(3,
                new Label(b.movieTitle() + " (" + b.screenName() + ")"),
                new Label(b.startsAt().format(SHOW_TIME_FMT))
        );
        col2.getChildren().get(0).getStyleClass().add("booking-value");
        col2.getChildren().get(1).getStyleClass().add("muted");
        col2.setPrefWidth(240);

        VBox col3 = new VBox(3,
                new Label("Seats: " + b.formattedSeats()),
                new Label("Booked " + (b.bookedAt() != null ? b.bookedAt().format(AUDIT_TIME_FMT) : "—"))
        );
        col3.getChildren().get(1).getStyleClass().add("muted");
        col3.setPrefWidth(180);

        VBox col4 = new VBox(3,
                new Label("Total: ₹" + b.totalAmount().toPlainString()),
                new Label("Tickets: ₹" + b.ticketAmount().toPlainString()
                        + " · Food: ₹" + b.fnbAmount().toPlainString())
        );
        col4.getChildren().get(0).getStyleClass().add("booking-value");
        col4.getChildren().get(1).getStyleClass().add("muted");
        HBox.setHgrow(col4, Priority.ALWAYS);

        details.getChildren().addAll(col1, col2, col3, col4);

        // Audit footer: the seller, and for a cancelled row the refunding clerk.
        Label auditLabel = new Label("Sold by: " + b.soldByName()
                + (b.status() == BookingStatus.CANCELLED
                ? "  ·  Cancelled by: " + b.displayCancelledBy()
                + " (Refund: ₹" + b.refundAmount().toPlainString() + ")"
                : ""));
        auditLabel.getStyleClass().addAll("muted", "booking-audit");
        auditLabel.setWrapText(true);

        HBox footer = new HBox(12, auditLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(auditLabel, Priority.ALWAYS);

        FoodDetail food = new FoodDetail(b);
        if (b.fnbAmount().signum() > 0) {
            Button foodToggle = new Button("Food ₹" + b.fnbAmount().toPlainString());
            foodToggle.getStyleClass().add("link-button");
            foodToggle.setOnAction(event -> food.toggle(foodToggle));
            footer.getChildren().add(foodToggle);
        }

        card.getChildren().addAll(header, details, footer);

        if (b.status() == BookingStatus.CONFIRMED && !now.isBefore(b.startsAt())) {
            Label closed = new Label(
                    "This screening has already started — cancellation is no longer available.");
            closed.getStyleClass().add("muted");
            closed.setWrapText(true);
            card.getChildren().add(closed);
        }

        if (b.fnbAmount().signum() > 0) {
            card.getChildren().add(food.node);
        }
        return card;
    }

    /**
     * The concession lines for one card, fetched the first time it is opened.
     * A per-card query on demand is not an N+1: the list itself never asks.
     */
    private final class FoodDetail {

        private final BookingSummary booking;
        private final VBox node = new VBox(3);
        private boolean loaded;

        private FoodDetail(BookingSummary booking) {
            this.booking = booking;
            node.getStyleClass().add("booking-food-lines");
            node.setVisible(false);
            node.setManaged(false);
        }

        private void toggle(Button trigger) {
            if (loaded) {
                setOpen(!node.isVisible());
                return;
            }
            trigger.setDisable(true);
            FxTasks.run(
                    () -> bookingService.foodLines(booking.bookingId()),
                    lines -> {
                        trigger.setDisable(false);
                        loaded = true;
                        fill(lines);
                        setOpen(true);
                    },
                    error -> {
                        trigger.setDisable(false);
                        showError("Could not read the food lines: " + error.getMessage());
                    }
            );
        }

        private void fill(List<ConcessionLine> lines) {
            node.getChildren().clear();
            if (lines.isEmpty()) {
                Label none = new Label("No food lines are recorded on this booking.");
                none.getStyleClass().add("muted");
                node.getChildren().add(none);
                return;
            }
            for (ConcessionLine line : lines) {
                Label row = new Label(line.quantity() + " × " + line.name()
                        + "  —  ₹" + line.lineTotal().toPlainString());
                row.getStyleClass().add("booking-food-line");
                node.getChildren().add(row);
            }
        }

        private void setOpen(boolean open) {
            node.setVisible(open);
            node.setManaged(open);
        }
    }

    private void showCancelDialog(BookingSummary booking, VBox card, Button cancelBtn) {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal refund;
        try {
            refund = bookingService.previewRefund(booking, now);
        } catch (Exception ex) {
            showError("Cannot cancel: " + ex.getMessage());
            return;
        }

        cancelBtn.setDisable(true);
        VBox confirmBox = new VBox(10);
        confirmBox.getStyleClass().add("cancel-confirm");

        Label prompt = new Label("Confirm cancellation for " + booking.reference() + "?");
        prompt.getStyleClass().add("cancel-confirm-title");

        boolean early = booking.startsAt().minus(RefundPolicy.EARLY_WINDOW).isAfter(now);
        String pct = early ? "90%" : "50%";
        Label calculation = new Label(pct + " refund of ₹" + refund.toPlainString()
                + " will be recorded on " + clerk.fullName()
                + "'s shift. Concession items are non-refundable.");
        calculation.setWrapText(true);
        calculation.getStyleClass().add("cancel-confirm-text");

        Button executeBtn = new Button("Execute cancellation");
        executeBtn.getStyleClass().add("danger-button");

        Button dismissBtn = new Button("Keep booking");
        dismissBtn.getStyleClass().add("outline-button");

        HBox actions = new HBox(8, executeBtn, dismissBtn);

        confirmBox.getChildren().addAll(prompt, calculation, actions);

        dismissBtn.setOnAction(event -> {
            card.getChildren().remove(confirmBox);
            cancelBtn.setDisable(false);
        });

        executeBtn.setOnAction(event -> {
            executeBtn.setDisable(true);
            dismissBtn.setDisable(true);
            FxTasks.run(
                    () -> bookingService.cancel(booking.bookingId(), clerk, LocalDateTime.now()),
                    result -> {
                        card.getChildren().remove(confirmBox);
                        refresh();
                        System.out.println("Booking " + booking.reference()
                                + " cancelled with refund ₹" + result.refundAmount());
                    },
                    error -> {
                        executeBtn.setDisable(false);
                        dismissBtn.setDisable(false);
                        cancelBtn.setDisable(false);
                        showError("Cancellation failed: " + error.getMessage());
                    }
            );
        });

        // Directly under the header, so the refund is read before the action.
        card.getChildren().add(1, confirmBox);
    }

    private void setCount(String text) {
        resultCountLabel.getStyleClass().remove("error");
        if (!resultCountLabel.getStyleClass().contains("muted")) {
            resultCountLabel.getStyleClass().add("muted");
        }
        resultCountLabel.setText(text);
    }

    private void showError(String message) {
        resultCountLabel.getStyleClass().remove("muted");
        if (!resultCountLabel.getStyleClass().contains("error")) {
            resultCountLabel.getStyleClass().add("error");
        }
        resultCountLabel.setText(message);
    }

    private static String pretty(String filter) {
        String lower = filter.toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
