package com.cinebook.ui;

import com.cinebook.exception.BookingException;
import com.cinebook.model.BookingSummary;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.service.BookingService;
import com.cinebook.service.RefundPolicy;
import com.cinebook.util.Db;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Bookings list and cancellation view (docs/PRD.md §2, §13.1, §13.6).
 *
 * <p>Supports real-time search across customer name, phone number, and booking ID,
 * filter chips by status, detailed audit attributes (both sold_by and cancelled_by),
 * and an atomic, refund-aware cancellation flow that releases physical seat inventory.
 */
public final class BookingsView {

    private static final DateTimeFormatter SHOW_TIME_FMT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter AUDIT_TIME_FMT =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);

    private final BookingService bookingService;
    private final StaffUser clerk;

    private final VBox root = new VBox(16);
    private final TextField searchField = new TextField();
    private final VBox listContainer = new VBox(12);
    private final Label resultCountLabel = new Label();
    private final HBox filterBar = new HBox(8);

    private String currentFilter = "ALL";
    private List<BookingSummary> cachedBookings = new ArrayList<>();

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

        // Search and Filter Header
        searchField.setPromptText("Search by patron name, phone, or booking #...");
        searchField.setPrefWidth(380);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterAndRender());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().add("outline-button");
        refreshBtn.setOnAction(e -> refresh());

        HBox searchBox = new HBox(12, searchField, refreshBtn);
        searchBox.setAlignment(Pos.CENTER_LEFT);

        // Status Filter Chips
        setupFilterChips();

        HBox topControls = new HBox(16, searchBox, filterBar);
        topControls.setAlignment(Pos.CENTER_LEFT);

        resultCountLabel.getStyleClass().add("muted");

        // Bookings Scroll Container
        ScrollPane scrollPane = new ScrollPane(listContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("content-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root.getChildren().addAll(topControls, resultCountLabel, scrollPane);
    }

    private void setupFilterChips() {
        filterBar.getChildren().clear();
        String[] filters = {"ALL", "CONFIRMED", "HELD", "CANCELLED"};
        for (String f : filters) {
            Button chip = new Button(f.charAt(0) + f.substring(1).toLowerCase(Locale.ENGLISH));
            chip.getStyleClass().add("chip-button");
            if (f.equals(currentFilter)) {
                chip.getStyleClass().add("selected");
            }
            chip.setOnAction(e -> {
                currentFilter = f;
                setupFilterChips();
                filterAndRender();
            });
            filterBar.getChildren().add(chip);
        }
    }

    public void refresh() {
        searchField.setDisable(true);
        FxTasks.run(
                () -> bookingService.list(null, 100),
                bookings -> {
                    searchField.setDisable(false);
                    cachedBookings = bookings;
                    filterAndRender();
                },
                error -> {
                    searchField.setDisable(false);
                    showError("Failed to load bookings: " + error.getMessage());
                }
        );
    }

    private void filterAndRender() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ENGLISH);

        List<BookingSummary> filtered = cachedBookings.stream()
                .filter(b -> {
                    if (!currentFilter.equals("ALL") && !b.status().name().equals(currentFilter)) {
                        return false;
                    }
                    if (query.isEmpty()) {
                        return true;
                    }
                    return b.customerName().toLowerCase(Locale.ENGLISH).contains(query)
                            || b.customerPhone().toLowerCase(Locale.ENGLISH).contains(query)
                            || String.valueOf(b.bookingId()).contains(query)
                            || b.movieTitle().toLowerCase(Locale.ENGLISH).contains(query);
                })
                .toList();

        resultCountLabel.setText("Showing " + filtered.size() + " booking" + (filtered.size() == 1 ? "" : "s"));
        listContainer.getChildren().clear();

        if (filtered.isEmpty()) {
            Label empty = new Label("No bookings match that search.");
            empty.getStyleClass().add("muted");
            empty.setPadding(new Insets(30, 0, 30, 0));
            listContainer.getChildren().add(empty);
            return;
        }

        for (BookingSummary booking : filtered) {
            listContainer.getChildren().add(createBookingCard(booking));
        }
    }

    private Node createBookingCard(BookingSummary b) {
        VBox card = new VBox(10);
        card.getStyleClass().add("panel-card");
        card.setPadding(new Insets(16));

        // Top Row: Reference, Status Badge, and Cancel Button
        Label refLabel = new Label(b.reference());
        refLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");

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
            cancelBtn.setOnAction(e -> showCancelDialog(b, card, cancelBtn));
            header.getChildren().add(cancelBtn);
        } else if (b.status() == BookingStatus.CONFIRMED) {
            Label pastLabel = new Label("Show started");
            pastLabel.getStyleClass().add("muted");
            header.getChildren().add(pastLabel);
        }

        // Details Grid
        HBox details = new HBox(24);
        details.setAlignment(Pos.CENTER_LEFT);

        VBox col1 = new VBox(3,
                new Label(b.customerName()),
                new Label(b.customerPhone())
        );
        col1.getChildren().get(0).setStyle("-fx-font-weight: 600;");
        col1.getChildren().get(1).getStyleClass().add("muted");
        col1.setPrefWidth(160);

        VBox col2 = new VBox(3,
                new Label(b.movieTitle() + " (" + b.screenName() + ")"),
                new Label(b.startsAt().format(SHOW_TIME_FMT))
        );
        col2.getChildren().get(0).setStyle("-fx-font-weight: 600;");
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
                new Label("Tickets: ₹" + b.ticketAmount().toPlainString() + " · Food: ₹" + b.fnbAmount().toPlainString())
        );
        col4.getChildren().get(0).setStyle("-fx-font-weight: 600;");
        col4.getChildren().get(1).getStyleClass().add("muted");
        HBox.setHgrow(col4, Priority.ALWAYS);

        details.getChildren().addAll(col1, col2, col3, col4);

        // Audit Footer
        Label auditLabel = new Label("Sold by: " + b.soldByName()
                + (b.status() == BookingStatus.CANCELLED
                ? "  ·  Cancelled by: " + b.displayCancelledBy()
                + " (Refund: ₹" + b.refundAmount().toPlainString() + ")"
                : ""));
        auditLabel.getStyleClass().add("muted");
        auditLabel.setStyle("-fx-font-size: 11px;");

        card.getChildren().addAll(header, details, auditLabel);
        return card;
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
        confirmBox.setStyle("-fx-background-color: #2b1f20; -fx-padding: 12px; -fx-background-radius: 4px; -fx-border-color: #e57373; -fx-border-radius: 4px;");

        Label prompt = new Label("Confirm cancellation for " + booking.reference() + "?");
        prompt.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffebee;");

        boolean early = booking.startsAt().minus(RefundPolicy.EARLY_WINDOW).isAfter(now);
        String pct = early ? "90%" : "50%";
        Label calculation = new Label(pct + " refund of ₹" + refund.toPlainString()
                + " will be recorded on " + clerk.fullName() + "'s shift. Concession items are non-refundable.");
        calculation.setStyle("-fx-text-fill: #ffcdd2;");
        calculation.setWrapText(true);

        Button executeBtn = new Button("Execute cancellation");
        executeBtn.getStyleClass().add("danger-button");

        Button dismissBtn = new Button("Keep booking");
        dismissBtn.getStyleClass().add("outline-button");

        HBox actions = new HBox(8, executeBtn, dismissBtn);

        confirmBox.getChildren().addAll(prompt, calculation, actions);

        dismissBtn.setOnAction(e -> {
            card.getChildren().remove(confirmBox);
            cancelBtn.setDisable(false);
        });

        executeBtn.setOnAction(e -> {
            executeBtn.setDisable(true);
            dismissBtn.setDisable(true);
            FxTasks.run(
                    () -> bookingService.cancel(booking.bookingId(), clerk, LocalDateTime.now()),
                    res -> {
                        card.getChildren().remove(confirmBox);
                        refresh();
                        System.out.println("Booking #" + booking.bookingId() + " cancelled with refund ₹" + res.refundAmount());
                    },
                    err -> {
                        executeBtn.setDisable(false);
                        dismissBtn.setDisable(false);
                        cancelBtn.setDisable(false);
                        showError("Cancellation failed: " + err.getMessage());
                    }
            );
        });

        // Insert confirmation box right below header
        card.getChildren().add(1, confirmBox);
    }

    private void showError(String msg) {
        resultCountLabel.setText(msg);
        resultCountLabel.setStyle("-fx-text-fill: #e57373;");
    }
}
