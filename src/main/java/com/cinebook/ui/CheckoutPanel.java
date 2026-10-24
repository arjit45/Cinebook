package com.cinebook.ui;

import com.cinebook.exception.BookingException;
import com.cinebook.model.ConcessionItem;
import com.cinebook.model.Customer;
import com.cinebook.model.Receipt;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.model.enums.SeatClass;
import com.cinebook.service.CheckoutService;
import com.cinebook.util.Money;
import com.cinebook.util.ReceiptWriter;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The checkout side panel from docs/PRD.md §5: the chosen seats and class totals,
 * a three-across food grid, the customer, and then the receipt.
 *
 * <p>It is a side panel, not a screen, so the seats never leave view. Everything
 * it shows is persisted as it happens: a customer is reassigned on the hold, food
 * is written to {@code booking_concession} immediately, and the payment is the
 * database procedure. The receipt shown here is read back after that call, which
 * is why it can agree with the till even if the panel's arithmetic was wrong.
 *
 * <p>The rail is split in two: everything above the rule scrolls, and the footer
 * holding the total and the payment button does not. The food grid is the one block
 * that grows as a clerk builds a big order, and a pay button that drifts below the
 * fold is a sale the clerk has to hunt for. Each food tile carries its own quantity
 * stepper, with the count on a badge, because the quantity is a number the database
 * constrains to 1–20 and a toggle cannot express "two popcorns".
 */
public final class CheckoutPanel {

    private static final DateTimeFormatter SHOW =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    /**
     * The stepper's ceiling, mirroring {@code CHECK (quantity BETWEEN 1 AND 20)} and
     * the service guard. The UI must not offer a number the database would refuse.
     */
    private static final int MAX_QUANTITY = 20;

    private final CheckoutService service;
    private final int bookingId;
    private final Showtime showtime;
    private final List<Seat> seats;
    private final StaffUser clerk;
    private final Runnable onDone;
    private final Runnable onReleased;
    private final Consumer<Receipt> onPaid;

    private final VBox content = new VBox(16);
    private final VBox form = new VBox(16);
    private final VBox footerActions = new VBox(9);
    private final VBox footer = new VBox(10);
    private final VBox customerResults = new VBox(2);
    private final VBox newCustomerForm = new VBox(6);
    private final VBox message = new VBox();
    private final Label messageText = new Label();
    private final Label customerName = new Label();
    private final Label customerPhone = new Label();
    private final Label heldSeats = new Label();
    private final Label runningTotal = new Label();
    private final Label foodCountBadge = new Label();
    private final Map<Integer, Integer> quantities = new LinkedHashMap<>();
    private final Map<Integer, FoodStepper> steppers = new LinkedHashMap<>();
    private final Map<PaymentMethod, Button> payChips = new EnumMap<>(PaymentMethod.class);
    private final Button payButton = new Button("Confirm payment");
    private final Button releaseButton = new Button("Release seats");
    private final Label countdownChip = new Label("⏱ 10:00");
    private final List<ConcessionItem> menu = new ArrayList<>();

    private javafx.animation.Timeline countdownTimeline;
    private int remainingSeconds = 600;

    private Customer customer;
    private PaymentMethod method = PaymentMethod.CARD;
    private Receipt receipt;
    private Path receiptPath;
    private HBox releaseLine;
    private ScrollPane scroll;

    public CheckoutPanel(CheckoutService service, int bookingId, Showtime showtime,
                         List<Seat> seats, StaffUser clerk, Runnable onDone,
                         Runnable onReleased, Consumer<Receipt> onPaid) {
        this.service = service;
        this.bookingId = bookingId;
        this.showtime = showtime;
        this.seats = List.copyOf(seats);
        this.clerk = clerk;
        this.onDone = onDone;
        this.onReleased = onReleased;
        this.onPaid = onPaid == null ? receipt -> { } : onPaid;

        countdownChip.setStyle("-fx-background-color: rgba(229,165,10,0.15); -fx-text-fill: #e5a50a; -fx-padding: 2px 8px; -fx-background-radius: 4px; -fx-font-size: 11px; -fx-font-weight: bold;");
        countdownTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.seconds(1), event -> tickCountdown())
        );
        countdownTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        countdownTimeline.play();

        content.getStyleClass().add("checkout-panel");
        content.setPadding(new Insets(18, 18, 22, 18));
        content.setSpacing(16);

        form.getChildren().addAll(
                seatBlock(),
                customerBlock(),
                foodBlock());

        content.getChildren().addAll(heading(), form);

        payButton.getStyleClass().add("checkout-pay");
        payButton.setMaxWidth(Double.MAX_VALUE);
        payButton.setOnAction(event -> pay());

        footerActions.getChildren().addAll(methodsRow(), totalRow(), payButton);
        footer.getChildren().addAll(messageBox(), footerActions);
        footer.setPadding(new Insets(12, 18, 16, 18));
        footer.setSpacing(10);
        footer.getStyleClass().add("checkout-footer");

        updateRunningTotal();

        try {
            customer = service.walkIn();
            showCustomer(customer);
            quantities.putAll(service.selectedFood(bookingId));
            refreshFoodSteppers();
            updateRunningTotal();
        } catch (BookingException | SQLException failure) {
            error(failure.getMessage());
        }
    }

    /**
     * The panel itself: a fixed 330 px rail whose middle scrolls and whose footer
     * does not, so the seat map keeps its 730 px sheet and the pay button keeps its
     * place on screen however long the order gets.
     */
    public Node root() {
        scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("checkout-scroll");
        // Without this the scroll pane's minimum is its content's, and the footer
        // would be pushed off the bottom instead of the content scrolling.
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox rail = new VBox(scroll, footer);
        rail.getStyleClass().add("checkout-rail");
        rail.setMinWidth(330);
        rail.setPrefWidth(330);
        rail.setMaxWidth(330);
        return rail;
    }

    private VBox heading() {
        Label eyebrow = new Label("CHECKOUT");
        eyebrow.getStyleClass().add("checkout-eyebrow");

        Label reference = new Label("Booking " + String.format("BK-%04d", bookingId));
        reference.getStyleClass().add("checkout-ref");

        Label where = new Label(showtime.movie().title() + "  ·  "
                + showtime.screen().name() + "  ·  " + SHOW.format(showtime.startsAt()));
        where.getStyleClass().add("checkout-where");
        where.setWrapText(true);

        heldSeats.setText("HELD SEATS: " + labels());
        heldSeats.getStyleClass().addAll("checkout-label", "checkout-held");
        heldSeats.setWrapText(true);

        // The escape hatch. A hold that is never paid still owns its seats, so the
        // counter needs a way to give them back without waiting out the 60-second
        // expiry — placed here, at the top, where the clerk sees it before paying.
        releaseButton.getStyleClass().addAll("link-button", "checkout-small-action");
        releaseButton.setOnAction(event -> releaseHold());
        releaseLine = releaseLine();

        HBox refRow = new HBox(10, reference, countdownChip);
        refRow.setAlignment(Pos.CENTER_LEFT);

        return new VBox(3, eyebrow, refRow, where, heldSeats, releaseLine);
    }

    private void tickCountdown() {
        remainingSeconds--;
        if (remainingSeconds <= 0) {
            if (countdownTimeline != null) {
                countdownTimeline.stop();
            }
            countdownChip.setText("⏱ EXPIRED");
            countdownChip.setStyle("-fx-background-color: rgba(229,62,62,0.15); -fx-text-fill: #e53e3e; -fx-padding: 2px 8px; -fx-background-radius: 4px; -fx-font-size: 11px; -fx-font-weight: bold;");
            payButton.setDisable(true);
            releaseButton.setDisable(true);
            error("Hold expired (10 min TTL). Reopen the screening.");
            releaseHold();
        } else {
            int mins = remainingSeconds / 60;
            int secs = remainingSeconds % 60;
            countdownChip.setText(String.format("⏱ %02d:%02d", mins, secs));
        }
    }

    /** The release link, on its own line so it never reads as part of the seat list. */
    private HBox releaseLine() {
        HBox line = new HBox(releaseButton);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    /**
     * Gives the seats back. Runs off the FX thread like every other transaction here
     * (docs/PRD.md §11.7), and disables the whole panel for its duration — payment
     * racing a release would be a booking confirming as it is being cancelled.
     *
     * <p>Success hands control to {@code onReleased}, which tears this panel down and
     * redraws the map: the seats are free again, and a map still showing them held
     * would be a lie the clerk acts on.
     */
    private void releaseHold() {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }
        releaseButton.setDisable(true);
        form.setDisable(true);
        footerActions.setDisable(true);
        message.setVisible(false);
        message.setManaged(false);

        FxTasks.run(
                () -> service.release(bookingId, clerk),
                freed -> onReleased.run(),
                failure -> {
                    releaseButton.setDisable(false);
                    form.setDisable(false);
                    footerActions.setDisable(false);
                    error(failure.getMessage());
                });
    }

    private VBox seatBlock() {
        Map<SeatClass, List<Seat>> byClass = new EnumMap<>(SeatClass.class);
        BigDecimal ticketTotal = BigDecimal.ZERO;
        for (Seat seat : seats) {
            byClass.computeIfAbsent(seat.seatClass(), key -> new ArrayList<>()).add(seat);
            ticketTotal = ticketTotal.add(seat.priceFor(showtime));
        }

        VBox block = new VBox(5, sectionLabel("TICKETS"));
        for (Map.Entry<SeatClass, List<Seat>> line : byClass.entrySet()) {
            BigDecimal lineTotal = line.getValue().stream()
                    .map(seat -> seat.priceFor(showtime))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            block.getChildren().add(moneyLine(
                    pretty(line.getKey()) + " ×" + line.getValue().size(), lineTotal));
        }
        block.getChildren().add(moneyLine("Tickets", ticketTotal));
        return block;
    }

    private VBox customerBlock() {
        VBox block = new VBox(7, sectionLabel("CUSTOMER"));

        customerName.getStyleClass().add("checkout-value");
        customerPhone.getStyleClass().add("checkout-where");
        block.getChildren().addAll(customerName, customerPhone);

        TextField search = new TextField();
        search.setPromptText("Phone or name");
        search.getStyleClass().add("checkout-search");
        HBox.setHgrow(search, Priority.ALWAYS);
        Button find = new Button("Find");
        find.getStyleClass().addAll("link-button", "checkout-small-action");
        find.setOnAction(event -> search(search.getText()));
        search.setOnAction(event -> search(search.getText()));
        HBox searchLine = new HBox(8, search, find);
        searchLine.setAlignment(Pos.CENTER_LEFT);

        customerResults.setSpacing(0);
        Button walkIn = new Button("Use walk-in guest");
        walkIn.getStyleClass().addAll("link-button", "checkout-small-action");
        walkIn.setOnAction(event -> chooseWalkIn());

        Button addNew = new Button("New customer");
        addNew.getStyleClass().addAll("link-button", "checkout-small-action");
        addNew.setOnAction(event -> {
            newCustomerForm.setVisible(!newCustomerForm.isVisible());
            newCustomerForm.setManaged(newCustomerForm.isVisible());
        });
        HBox links = new HBox(14, walkIn, addNew);

        newCustomerForm.setVisible(false);
        newCustomerForm.setManaged(false);
        TextField name = new TextField();
        name.setPromptText("Full name");
        TextField email = new TextField();
        email.setPromptText("Email");
        TextField phone = new TextField();
        phone.setPromptText("Phone (10–13 digits)");
        Button create = new Button("Add & use");
        create.getStyleClass().addAll("link-button", "checkout-small-action");
        create.setOnAction(event -> createCustomer(name.getText(), email.getText(), phone.getText()));
        newCustomerForm.getChildren().addAll(name, email, phone, create);

        block.getChildren().addAll(searchLine, customerResults, links, newCustomerForm);
        return block;
    }

    private VBox foodBlock() {
        VBox block = new VBox(8, foodHeading());
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.getStyleClass().add("checkout-food-grid");
        for (int column = 0; column < 3; column++) {
            ColumnConstraints width = new ColumnConstraints();
            width.setPercentWidth(100.0 / 3);
            grid.getColumnConstraints().add(width);
        }

        try {
            menu.addAll(service.menu());
        } catch (SQLException failure) {
            error("The concession price list could not be read: " + failure.getMessage());
        }

        for (int i = 0; i < menu.size(); i++) {
            VBox card = foodCard(menu.get(i));
            GridPane.setHgrow(card, Priority.ALWAYS);
            grid.add(card, i % 3, i / 3);
        }

        block.getChildren().add(grid);
        return block;
    }

    /** "FOOD & DRINK" with the number of items on the order, so the badge states it. */
    private HBox foodHeading() {
        Label label = sectionLabel("FOOD & DRINK");
        foodCountBadge.getStyleClass().add("checkout-count-badge");
        foodCountBadge.setVisible(false);
        foodCountBadge.setManaged(false);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox line = new HBox(6, label, gap, foodCountBadge);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    /**
     * One menu tile: the name and price, then the stepper that owns the quantity.
     *
     * <p>A chip that toggles 0/1 cannot sell two popcorns, and the quantity is part
     * of the schema — {@code booking_concession.quantity} with its own price
     * snapshot. So the tile is a small card, the badge between the buttons is the
     * count, and the buttons are the only way to change it: no free text, no way to
     * ask for twenty-one.
     */
    private VBox foodCard(ConcessionItem item) {
        Label name = new Label(item.name());
        name.getStyleClass().add("checkout-food-name");
        name.setWrapText(true);
        // A wrapped label in a VBox keeps its preferred width unless it is told it
        // may grow — without this a longer menu name spreads across its neighbour
        // instead of wrapping inside its own card.
        name.setMaxWidth(Double.MAX_VALUE);
        Label price = new Label("\u20b9" + Money.plain(item.unitPrice()));
        price.getStyleClass().add("checkout-food-price");
        price.setMaxWidth(Double.MAX_VALUE);

        Button minus = stepperButton("\u2212", "stepper-minus");
        Button plus = stepperButton("+", "stepper-plus");
        Label badge = new Label("0");
        badge.getStyleClass().add("stepper-badge");

        minus.setOnAction(event -> changeFood(item, -1));
        plus.setOnAction(event -> changeFood(item, +1));

        HBox stepper = new HBox(3, minus, badge, plus);
        stepper.setAlignment(Pos.CENTER);
        stepper.getStyleClass().add("concession-stepper");

        // The spacer keeps every stepper on one line of the grid, whatever height the
        // wrapped names take, so the buttons do not jump between rows.
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);

        VBox card = new VBox(3, name, price, gap, stepper);
        card.getStyleClass().add("concession-card");
        card.setMaxWidth(Double.MAX_VALUE);

        steppers.put(item.itemId(), new FoodStepper(card, minus, badge, plus));
        return card;
    }

    /** A menu tile's live parts: the card, its two buttons and the badge between them. */
    private record FoodStepper(VBox card, Button minus, Label badge, Button plus) {
    }

    private static Button stepperButton(String glyph, String extra) {
        Button button = new Button(glyph);
        button.getStyleClass().addAll("stepper-button", extra);
        return button;
    }

    /**
     * Moves one line by one, and writes it before the widgets move.
     *
     * <p>The order matters: the badge and the total are re-read from what was
     * persisted, so a failed write leaves the tile showing the quantity the database
     * still holds rather than a number the clerk only saw on screen.
     */
    private void changeFood(ConcessionItem item, int delta) {
        int current = quantities.getOrDefault(item.itemId(), 0);
        int next = Math.max(0, Math.min(MAX_QUANTITY, current + delta));
        if (next == current) {
            return;
        }
        try {
            service.setFood(bookingId, item, next);
            if (next == 0) {
                quantities.remove(item.itemId());
            } else {
                quantities.put(item.itemId(), next);
            }
            refreshFoodSteppers();
            updateRunningTotal();
        } catch (BookingException | SQLException failure) {
            refreshFoodSteppers();
            error(failure.getMessage());
        }
    }

    private void refreshFoodSteppers() {
        for (ConcessionItem item : menu) {
            FoodStepper stepper = steppers.get(item.itemId());
            if (stepper == null) {
                continue;
            }
            int quantity = quantities.getOrDefault(item.itemId(), 0);
            stepper.badge().setText(String.valueOf(quantity));
            stepper.badge().getStyleClass().remove("filled");
            stepper.card().getStyleClass().remove("selected");
            if (quantity > 0) {
                stepper.badge().getStyleClass().add("filled");
                stepper.card().getStyleClass().add("selected");
            }
            // The floors are the database's, not the panel's: no line below zero and
            // none above twenty, so a stepper press can only ever be a legal value.
            stepper.minus().setDisable(quantity <= 0);
            stepper.plus().setDisable(quantity >= MAX_QUANTITY);
        }
    }

    /** The three payment methods, as chips a clerk can hit with a thumb. */
    private HBox methodsRow() {
        HBox methods = new HBox(6);
        for (PaymentMethod candidate : PaymentMethod.values()) {
            Button chip = new Button(pretty(candidate));
            chip.getStyleClass().add("pay-chip");
            chip.setOnAction(event -> selectMethod(candidate));
            payChips.put(candidate, chip);
            methods.getChildren().add(chip);
        }
        selectMethod(method);
        return methods;
    }

    /** The footer's amount line: the caption on the left, the money on the right. */
    private HBox totalRow() {
        Label caption = new Label("TOTAL");
        caption.getStyleClass().add("checkout-label");
        runningTotal.getStyleClass().addAll("checkout-total", "checkout-footer-total");

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(8, caption, gap, runningTotal);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox moneyLine(String label, BigDecimal amount) {
        Label left = new Label(label);
        left.getStyleClass().add("checkout-line");
        Label right = new Label("\u20b9" + Money.plain(amount));
        right.getStyleClass().add("checkout-money");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox line = new HBox(8, left, gap, right);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    private void search(String query) {
        customerResults.getChildren().clear();
        try {
            List<Customer> found = service.findCustomers(query);
            if (found.isEmpty()) {
                Label none = new Label("No match. Use the walk-in guest or add them.");
                none.getStyleClass().add("checkout-where");
                none.setWrapText(true);
                customerResults.getChildren().add(none);
                return;
            }
            for (Customer match : found) {
                Button row = new Button(match.fullName() + "  ·  " + match.phone());
                row.getStyleClass().add("customer-result");
                row.setMaxWidth(Double.MAX_VALUE);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setOnAction(event -> choose(match));
                customerResults.getChildren().add(row);
            }
        } catch (BookingException | SQLException failure) {
            error(failure.getMessage());
        }
    }

    private void chooseWalkIn() {
        try {
            choose(service.walkIn());
        } catch (BookingException | SQLException failure) {
            error(failure.getMessage());
        }
    }

    private void choose(Customer chosen) {
        try {
            service.assignCustomer(bookingId, chosen.customerId());
            customer = chosen;
            showCustomer(chosen);
            customerResults.getChildren().clear();
            note("Customer set to " + chosen.fullName() + ".");
        } catch (BookingException | SQLException failure) {
            error(failure.getMessage());
        }
    }

    private void createCustomer(String name, String email, String phone) {
        try {
            choose(service.createCustomer(name, email, phone));
            newCustomerForm.setVisible(false);
            newCustomerForm.setManaged(false);
        } catch (BookingException | SQLException failure) {
            error(failure.getMessage());
        }
    }

    private void showCustomer(Customer chosen) {
        customerName.setText(chosen.fullName());
        customerPhone.setText(chosen.phone() + "  ·  " + chosen.email());
    }

    private void selectMethod(PaymentMethod chosen) {
        method = chosen;
        for (Map.Entry<PaymentMethod, Button> chip : payChips.entrySet()) {
            chip.getValue().getStyleClass().remove("selected");
            if (chip.getKey() == chosen) {
                chip.getValue().getStyleClass().add("selected");
            }
        }
    }

    private void updateRunningTotal() {
        BigDecimal tickets = seats.stream()
                .map(seat -> seat.priceFor(showtime))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal food = menu.stream()
                .filter(item -> quantities.getOrDefault(item.itemId(), 0) > 0)
                .map(item -> item.unitPrice()
                        .multiply(BigDecimal.valueOf(quantities.get(item.itemId()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        runningTotal.setText("\u20b9" + Money.plain(tickets.add(food)));

        int units = quantities.values().stream().mapToInt(Integer::intValue).sum();
        foodCountBadge.setText(String.valueOf(units));
        foodCountBadge.setVisible(units > 0);
        foodCountBadge.setManaged(units > 0);
    }

    /**
     * Takes the payment off the FX thread (docs/PRD.md \u00a711.7): the procedure locks
     * the booking and recomputes the totals, and a lock wait long enough to matter
     * would otherwise freeze the window at exactly the moment the clerk is watching.
     *
     * <p>The button is disabled on entry and only re-enabled if the payment fails.
     * That is what makes the double-click hazard disappear: the second click has
     * nowhere to land, so it can never race the procedure into a confusing
     * "not in HELD status" error on a sale that actually succeeded.
     */
    private void pay() {
        if (customer == null) {
            error("Choose a customer before taking payment.");
            return;
        }
        message.setVisible(false);
        message.setManaged(false);
        payButton.setDisable(true);

        FxTasks.run(
                () -> service.pay(bookingId, method),
                paid -> {
                    receipt = paid;
                    showReceipt();
                },
                failure -> {
                    // The sale did not go through, so the clerk must be able to try
                    // again — with the same seats, customer and food still held.
                    payButton.setDisable(false);
                    error(failure.getMessage());
                });
    }

    private void showReceipt() {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }
        countdownChip.setVisible(false);
        countdownChip.setManaged(false);

        form.setVisible(false);
        form.setManaged(false);
        // Past payment the seats are sold, not held; the heading must not keep
        // telling the clerk to expect them back.
        heldSeats.setText("SOLD SEATS: " + labels());
        releaseLine.setVisible(false);
        releaseLine.setManaged(false);
        // Hide the payment action, but keep the footer's status line: printing the
        // receipt reports the file it wrote there, and a note under a hidden parent
        // is a note the clerk never sees.
        footerActions.setVisible(false);
        footerActions.setManaged(false);

        Label paid = new Label("PAID");
        paid.getStyleClass().add("checkout-eyebrow");
        Label reference = new Label(receipt.reference());
        reference.getStyleClass().add("checkout-ref");
        Label amount = new Label("\u20b9" + Money.plain(receipt.totalAmount())
                + "  ·  " + pretty(receipt.method()) + "  ·  " + receipt.txnRef());
        amount.getStyleClass().add("checkout-where");
        amount.setWrapText(true);

        TextArea preview = new TextArea(ReceiptWriter.format(receipt));
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("receipt-preview");
        preview.setPrefRowCount(17);

        Button print = new Button("Print receipt");
        print.getStyleClass().add("checkout-pay");
        print.setOnAction(event -> printReceipt());
        Button open = new Button("Open receipt");
        open.getStyleClass().addAll("link-button", "checkout-small-action");
        open.setOnAction(event -> openReceipt());
        HBox actions = new HBox(14, print, open);
        actions.setAlignment(Pos.CENTER_LEFT);

        Button done = new Button("Done");
        done.getStyleClass().addAll("link-button", "checkout-small-action");
        done.setOnAction(event -> onDone.run());

        note("Receipt ready. Print it for the customer or open the file.");

        VBox stub = new VBox(8, paid, reference, amount, preview, actions, done);
        stub.getStyleClass().add("receipt-stub");
        content.getChildren().add(stub);
        slideIn(stub);
        onPaid.accept(receipt);

        // The form is gone, so the rail is shorter than it was; read the slip from
        // its top rather than wherever the scroll happened to be parked.
        if (scroll != null) {
            scroll.setVvalue(0);
        }
    }

    /**
     * The slip arrives from the bottom edge of the rail, where the payment footer
     * just was, and settles. One movement, answering the clerk's action — not a
     * staggered reveal of every line, which would slow the counter down to decorate it.
     *
     * <p>It ends at rest in every case: opacity 1, no offset. If the animation is
     * interrupted (a window closing mid-payment) the same final state is written by
     * the finish handler, so the slip can never be left half-faded and unreadable.
     */
    private static void slideIn(Node stub) {
        double offset = 20;
        stub.setOpacity(0);
        stub.setTranslateY(offset);

        FadeTransition fade = new FadeTransition(Duration.millis(200), stub);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition rise = new TranslateTransition(Duration.millis(260), stub);
        rise.setFromY(offset);
        rise.setToY(0);
        rise.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition entry = new ParallelTransition(fade, rise);
        entry.setOnFinished(event -> {
            stub.setOpacity(1);
            stub.setTranslateY(0);
        });
        entry.play();
    }

    private void printReceipt() {
        try {
            receiptPath = ReceiptWriter.write(receipt);
            note("Receipt saved to " + receiptPath.toAbsolutePath());
        } catch (IOException failure) {
            error("The receipt could not be written: " + failure.getMessage());
        }
    }

    private void openReceipt() {
        try {
            if (receiptPath == null) {
                receiptPath = ReceiptWriter.write(receipt);
            }
            if (!Desktop.isDesktopSupported()) {
                messageText.setText("No desktop shell to open the file. Saved to "
                        + receiptPath.toAbsolutePath());
                return;
            }
            Desktop.getDesktop().open(receiptPath.toFile());
        } catch (IOException failure) {
            error("The receipt could not be opened: " + failure.getMessage());
        }
    }

    private VBox messageBox() {
        messageText.setWrapText(true);
        // The footer's status line: named so a test can read the same sentence the
        // clerk reads, rather than guessing which label carries it.
        messageText.getStyleClass().addAll("checkout-where", "checkout-message");
        message.getChildren().add(messageText);
        message.setVisible(false);
        message.setManaged(false);
        return message;
    }

    private void note(String text) {
        messageText.setText(text);
        messageText.getStyleClass().remove("checkout-error");
        message.setVisible(true);
        message.setManaged(true);
    }

    private void error(String text) {
        messageText.setText(text == null ? "Something went wrong." : text);
        if (!messageText.getStyleClass().contains("checkout-error")) {
            messageText.getStyleClass().add("checkout-error");
        }
        message.setVisible(true);
        message.setManaged(true);
        // The message lives in the pinned footer, beside the button that failed, so
        // an error is never something the clerk has to scroll down to discover.
    }

    private String labels() {
        return seats.stream().map(Seat::label).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("checkout-label");
        return label;
    }

    private static String pretty(Enum<?> value) {
        String lower = value.name().toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
