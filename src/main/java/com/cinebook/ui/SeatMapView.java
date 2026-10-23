package com.cinebook.ui;

import com.cinebook.dao.SeatDao;
import com.cinebook.exception.BookingException;
import com.cinebook.exception.DbUnavailableException;
import com.cinebook.exception.SeatUnavailableException;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.SeatClass;
import com.cinebook.service.BookingService;
import com.cinebook.service.CheckoutService;
import com.cinebook.util.Db;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The auditorium, drawn the way the reference ticket page does it: warm ivory
 * stock, one turquoise screen whose bottom edge sweeps down at the ends, and a
 * band per seat class rather than one undifferentiated grid.
 *
 * <p>Class is not a colour. Regular is a small cream block, premium is the same
 * block a few pixels wider under its own heading, and a recliner is a drawn
 * armchair — the shape does the work even when the page is printed grey.
 *
 * <p>State is the only strong signal: a free seat is cream, a sold one is faded
 * with an ×, and a picked one turns green. Picking is a preview until Confirm;
 * nothing is written until the hold transaction commits.
 *
 * <p>Sections, rows and columns all come from the data. The seed is 12 seats per
 * row, but {@code chk_seat_no} allows 60, so a typed 8 × 12 grid would silently
 * drop seats the first time the room changed.
 */
public final class SeatMapView {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    private static final double PAGE_W = 730;
    private static final double LETTER_W = 18;
    private static final double GUTTER = 40;

    private static final double SEAT_W = 30;
    private static final double SEAT_H = 26;
    private static final double PREMIUM_W = 33;
    private static final double SEAT_GAP = 7;

    private static final double CHAIR_W = 40;
    private static final double CHAIR_H = 44;
    private static final double CHAIR_GAP = 6;

    private static final double ROW_GAP = 5;

    private static final double SCREEN_W = 620;
    private static final double SCREEN_H = 44;
    private static final double SCREEN_DIP = 28;

    private final Db db;
    private final Showtime showtime;
    private final StaffUser soldBy;
    private final BookingService bookingService;
    private final Runnable onDone;
    private final VBox root = new VBox();
    private final HBox stage = new HBox();
    private final VBox paper = new VBox(26);
    private final Label status = new Label("Pick seats to see their class and their price.");
    private final Set<Seat> pickedSeats = new LinkedHashSet<>();
    private final Map<Seat, Button> seatButtons = new LinkedHashMap<>();
    private final Label selectedSeats = new Label();
    private final Label selectedClass = new Label();
    private final Label selectedTotal = new Label();
    private final Button confirmSeats = new Button("Confirm Seats");
    private Integer heldBookingId;

    public SeatMapView(Db db, Showtime showtime, StaffUser soldBy, Runnable onBack) {
        this.db = db;
        this.showtime = showtime;
        this.soldBy = soldBy;
        this.onDone = onBack;
        this.bookingService = new BookingService(db);
        root.getStyleClass().add("seat-page");
        root.getChildren().add(header(onBack));

        paper.getStyleClass().add("seat-paper");
        paper.setAlignment(Pos.TOP_CENTER);
        paper.setPadding(new Insets(34, 32, 34, 32));
        paper.setMinWidth(PAGE_W);
        paper.setPrefWidth(PAGE_W);
        paper.setMaxWidth(PAGE_W);

        HBox centre = new HBox(paper);
        centre.setAlignment(Pos.TOP_CENTER);
        centre.setMaxWidth(Double.MAX_VALUE);

        ScrollPane scroll = new ScrollPane(centre);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("seat-scroll");

        stage.getChildren().add(scroll);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        stage.setMaxWidth(Double.MAX_VALUE);
        root.getChildren().add(stage);
        VBox.setVgrow(stage, Priority.ALWAYS);
        root.getChildren().add(bookingBar());

        status.getStyleClass().add("seat-status");
        status.setWrapText(true);
        status.setMaxWidth(PAGE_W - 64);

        render();
    }

    public Parent root() {
        return root;
    }

    /**
     * Draws the page from the database. Called on open and again after a hold loses
     * a race: the sold set is re-read, so the seat that beat us is never left green.
     */
    private void render() {
        pickedSeats.clear();
        seatButtons.clear();
        heldBookingId = null;
        confirmSeats.setText("Confirm Seats");
        status.setText("Pick seats to see their class and their price.");
        status.getStyleClass().remove("error");
        paper.getChildren().clear();

        try {
            SeatDao seats = new SeatDao(db);
            List<Seat> room = seats.byScreen(showtime.screen().screenId());
            Set<String> sold = seats.soldLabels(showtime.showtimeId());
            if (room.isEmpty()) {
                paper.getChildren().add(note("This auditorium has no seats on record."));
            } else {
                paper.getChildren().addAll(screenGraphic(), sections(room, sold), status);
            }
        } catch (DbUnavailableException unavailable) {
            paper.getChildren().add(note("MySQL is not running — start the service, then reopen this screening."));
        } catch (SQLException failure) {
            paper.getChildren().add(note("The seat map could not be read: " + failure.getMessage()));
        }
        updateBar();
    }

    private VBox header(Runnable onBack) {
        Button back = new Button("\u2190 Schedule");
        back.getStyleClass().addAll("link-button", "back-link");
        back.setOnAction(event -> onBack.run());

        Label title = new Label(showtime.movie().title());
        title.getStyleClass().add("section-title");

        Label meta = new Label(showtime.screen().name() + "  \u00b7  "
                + WHEN.format(showtime.startsAt()) + "  \u00b7  "
                + showtime.movie().runtimeMin() + " min  \u00b7  "
                + showtime.movie().certificate());
        meta.getStyleClass().add("muted");

        VBox heading = new VBox(6, back, title, meta);
        heading.setPadding(new Insets(34, 40, 18, 40));
        heading.getStyleClass().add("row-rule");
        return heading;
    }

    /**
     * The screen: a flat-topped band whose lower edge rises to the centre, the
     * silhouette the reference uses instead of a labelled rectangle. The label
     * sits in the hollow under that curve.
     */
    private StackPane screenGraphic() {
        Path shape = new Path(
                new MoveTo(0, 0),
                new LineTo(SCREEN_W, 0),
                new LineTo(SCREEN_W, SCREEN_H),
                new CubicCurveTo(SCREEN_W * 0.82, SCREEN_DIP,
                        SCREEN_W * 0.66, SCREEN_DIP, SCREEN_W * 0.5, SCREEN_DIP),
                new CubicCurveTo(SCREEN_W * 0.34, SCREEN_DIP,
                        SCREEN_W * 0.18, SCREEN_DIP, 0, SCREEN_H),
                new ClosePath());
        shape.getStyleClass().add("screen-shape");

        Label caption = new Label("S C R E E N");
        caption.getStyleClass().add("screen-caption");

        StackPane pane = new StackPane(shape, caption);
        StackPane.setAlignment(shape, Pos.TOP_CENTER);
        StackPane.setAlignment(caption, Pos.TOP_CENTER);
        StackPane.setMargin(caption, new Insets(31, 0, 0, 0));
        pane.setMinSize(SCREEN_W, SCREEN_H + 12);
        pane.setPrefSize(SCREEN_W, SCREEN_H + 12);
        pane.setMaxSize(SCREEN_W, SCREEN_H + 12);
        return pane;
    }

    /** One band per class, in enum order, each sized against the widest room row. */
    private VBox sections(List<Seat> room, Set<String> sold) {
        Map<SeatClass, List<Seat>> byClass = new EnumMap<>(SeatClass.class);
        for (Seat seat : room) {
            byClass.computeIfAbsent(seat.seatClass(), key -> new ArrayList<>()).add(seat);
        }

        double seatArea = 0;
        for (Map.Entry<SeatClass, List<Seat>> group : byClass.entrySet()) {
            int columns = group.getValue().stream().mapToInt(Seat::seatNo).max().orElse(1);
            seatArea = Math.max(seatArea, rowWidth(group.getKey(), columns));
        }

        VBox stack = new VBox(26);
        stack.setAlignment(Pos.TOP_CENTER);
        for (SeatClass seatClass : SeatClass.values()) {
            List<Seat> group = byClass.get(seatClass);
            if (group != null && !group.isEmpty()) {
                stack.getChildren().add(section(seatClass, group, seatArea, sold));
            }
        }
        return stack;
    }

    private VBox section(SeatClass seatClass, List<Seat> group, double seatArea, Set<String> sold) {
        Seat sample = group.get(0);
        Label title = new Label(pretty(seatClass).toUpperCase(Locale.ENGLISH)
                + " SEATS  \u00b7  \u20b9" + sample.priceFor(showtime).toPlainString());
        title.getStyleClass().add("seat-section");

        Region left = rule();
        Region right = rule();
        HBox head = new HBox(16, left, title, right);
        head.setAlignment(Pos.CENTER);
        head.setMinWidth(seatArea + 2 * GUTTER);
        head.setPrefWidth(seatArea + 2 * GUTTER);
        head.setMaxWidth(seatArea + 2 * GUTTER);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        Map<Character, List<Seat>> rows = new LinkedHashMap<>();
        for (Seat seat : group) {
            rows.computeIfAbsent(seat.rowLabel(), key -> new ArrayList<>()).add(seat);
        }
        int columns = group.stream().mapToInt(Seat::seatNo).max().orElse(1);

        VBox body = new VBox(ROW_GAP);
        body.setAlignment(Pos.TOP_CENTER);
        for (Map.Entry<Character, List<Seat>> row : rows.entrySet()) {
            body.getChildren().add(seatRow(seatClass, row.getKey(), row.getValue(), columns, seatArea, sold));
        }

        VBox block = new VBox(9, head, body);
        block.setAlignment(Pos.TOP_CENTER);
        return block;
    }

    /** Row letter at each page edge, seats centred between them, as in the reference. */
    private HBox seatRow(SeatClass seatClass, char rowLabel, List<Seat> seats, int columns,
                         double seatArea, Set<String> sold) {
        Map<Integer, Seat> byNumber = seats.stream()
                .collect(Collectors.toMap(Seat::seatNo, seat -> seat));
        HBox grid = new HBox(seatGap(seatClass));
        grid.setAlignment(Pos.CENTER);
        for (int column = 1; column <= columns; column++) {
            Seat seat = byNumber.get(column);
            if (seat == null) {
                grid.getChildren().add(spacer(seatWidth(seatClass), seatHeight(seatClass)));
            } else {
                grid.getChildren().add(seatButton(seat, sold.contains(seat.label())));
            }
        }

        StackPane area = new StackPane(grid);
        area.setAlignment(Pos.CENTER);
        area.setMinWidth(seatArea);
        area.setPrefWidth(seatArea);
        area.setMaxWidth(seatArea);

        HBox line = new HBox(0,
                rowLetter(rowLabel), spacer(GUTTER, 1), area, spacer(GUTTER, 1), rowLetter(rowLabel));
        line.setAlignment(Pos.CENTER);
        return line;
    }

    private Button seatButton(Seat seat, boolean isSold) {
        boolean chair = seat.seatClass() == SeatClass.RECLINER;
        double width = seatWidth(seat.seatClass());
        double height = seatHeight(seat.seatClass());

        Button button = new Button();
        button.getStyleClass().addAll("seat",
                "seat-" + seat.seatClass().name().toLowerCase(Locale.ENGLISH));
        button.setUserData(seat);
        button.setMinSize(width, height);
        button.setPrefSize(width, height);
        button.setMaxSize(width, height);

        if (chair) {
            button.getStyleClass().add("seat-chair");
            Label number = new Label(String.valueOf(seat.seatNo()));
            number.getStyleClass().add("chair-number");
            number.setVisible(!isSold);
            number.setMouseTransparent(true);
            Label cross = new Label("\u00d7");
            cross.getStyleClass().add("seat-cross");
            cross.setVisible(isSold);
            cross.setMouseTransparent(true);
            StackPane graphic = new StackPane(chairShape(), number, cross);
            StackPane.setAlignment(number, Pos.TOP_CENTER);
            StackPane.setMargin(number, new Insets(9, 0, 0, 0));
            graphic.setMinSize(width, height);
            graphic.setPrefSize(width, height);
            graphic.setMaxSize(width, height);
            button.setGraphic(graphic);
        } else {
            button.setText(isSold ? "\u00d7" : String.valueOf(seat.seatNo()));
        }

        if (isSold) {
            button.getStyleClass().add("seat-sold");
        }

        button.setTooltip(new Tooltip(seat.label() + " \u00b7 " + pretty(seat.seatClass())
                + " \u00b7 " + seat.priceFor(showtime).toPlainString()
                + (isSold ? " \u00b7 sold" : " \u00b7 available")));
        // Keyboard travel through 96 seats is not implemented; offering every seat as
        // a tab stop would only make the screen harder to leave.
        button.setFocusTraversable(false);
        button.setOnAction(event -> pick(seat, isSold, button));
        seatButtons.put(seat, button);
        return button;
    }

    /**
     * Draws the armchair that makes a recliner unmistakable at a glance: backrest,
     * wide seat body and two cushion seams, all hairline.
     */
    private static Pane chairShape() {
        Rectangle back = new Rectangle(9, 2, 22, 28);
        back.setArcWidth(12);
        back.setArcHeight(12);

        Rectangle body = new Rectangle(1, 20, 38, 22);
        body.setArcWidth(10);
        body.setArcHeight(10);

        // Union, not two stacked rectangles: one silhouette keeps the backrest and
        // the seat from drawing a seam where they meet.
        Shape silhouette = Shape.union(back, body);
        silhouette.getStyleClass().add("chair-body");

        Line upper = new Line(7, 31, 33, 31);
        upper.getStyleClass().add("chair-line");
        Line lower = new Line(7, 37, 33, 37);
        lower.getStyleClass().add("chair-line");

        Pane pane = new Pane(silhouette, upper, lower);
        pane.setMinSize(CHAIR_W, CHAIR_H);
        pane.setPrefSize(CHAIR_W, CHAIR_H);
        pane.setMaxSize(CHAIR_W, CHAIR_H);
        return pane;
    }

    /**
     * The booking footer from the reference: chosen seats on the left, the class they
     * add up to in the middle, the bill and the confirm action on the right. It spans
     * the window, so the total is in view while the seating scrolls.
     *
     * <p>Confirm writes one HELD booking for the picked seats. It stays disabled while
     * the bill is empty and after a hold exists, so the bar never promises a second
     * transaction against the same seats.
     */
    private HBox bookingBar() {
        selectedSeats.getStyleClass().add("seat-tally");
        selectedClass.getStyleClass().add("seat-class");
        selectedTotal.getStyleClass().add("seat-total");

        HBox left = new HBox(selectedSeats);
        left.setAlignment(Pos.CENTER_LEFT);

        confirmSeats.getStyleClass().add("confirm-seats");
        confirmSeats.setDisable(true);
        confirmSeats.setTooltip(new Tooltip(
                "Hold the picked seats. They are held under the walk-in guest until checkout."));
        confirmSeats.setOnAction(event -> holdSeats());

        HBox right = new HBox(18, selectedTotal, confirmSeats);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        ColumnConstraints tallyColumn = new ColumnConstraints();
        tallyColumn.setPercentWidth(40);
        ColumnConstraints classColumn = new ColumnConstraints();
        classColumn.setPercentWidth(20);
        ColumnConstraints totalColumn = new ColumnConstraints();
        totalColumn.setPercentWidth(40);
        grid.getColumnConstraints().addAll(tallyColumn, classColumn, totalColumn);
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.add(left, 0, 0);
        grid.add(selectedClass, 1, 0);
        GridPane.setHalignment(selectedClass, HPos.CENTER);
        grid.add(right, 2, 0);

        HBox bar = new HBox(grid);
        HBox.setHgrow(grid, Priority.ALWAYS);
        bar.getStyleClass().add("booking-bar");
        bar.setPadding(new Insets(12, 40, 12, 40));
        bar.setAlignment(Pos.CENTER_LEFT);
        updateBar();
        return bar;
    }

    private void updateBar() {
        String labels = pickedSeats.stream()
                .map(Seat::label)
                .collect(Collectors.joining(", "));
        selectedSeats.setText("SELECTED SEATS: " + pickedSeats.size()
                + (pickedSeats.isEmpty() ? "" : " (" + labels + ")"));

        Set<SeatClass> classes = pickedSeats.stream()
                .map(Seat::seatClass)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        selectedClass.setText(classes.isEmpty() ? ""
                : classes.size() == 1
                ? classes.iterator().next().name() + " SEATS"
                : "MIXED CLASSES");

        BigDecimal total = BigDecimal.ZERO;
        for (Seat seat : pickedSeats) {
            total = total.add(seat.priceFor(showtime));
        }
        selectedTotal.setText("TOTAL: \u20b9" + money(total) + " (INR)");

        if (heldBookingId == null) {
            confirmSeats.setText("Confirm Seats");
        }
        confirmSeats.setDisable(pickedSeats.isEmpty() || heldBookingId != null);
    }

    /**
     * One transaction, and the map only changes after it commits. A seat taken by
     * someone else reloads the page rather than leaving a green seat that is gone.
     *
     * <p>The transaction runs off the FX thread (docs/PRD.md §11.7): the picks are
     * snapshotted before it starts, so a clerk clicking seats while the hold is in
     * flight cannot change what is being held. The button is disabled for the
     * duration, so a second press cannot queue a second hold.
     */
    private void holdSeats() {
        if (pickedSeats.isEmpty() || heldBookingId != null) {
            return;
        }
        confirmSeats.setDisable(true);
        status.getStyleClass().remove("error");
        status.setText("Holding " + pickedSeats.size()
                + (pickedSeats.size() == 1 ? " seat" : " seats") + "\u2026");

        List<Seat> chosen = List.copyOf(pickedSeats);
        FxTasks.run(
                () -> bookingService.hold(showtime, chosen, soldBy),
                held -> heldSeats(held, chosen),
                failure -> holdFailed(failure));
    }

    /** Runs on the FX thread once the hold has committed. */
    private void heldSeats(BookingService.Held held, List<Seat> chosen) {
        heldBookingId = held.bookingId();
        for (Seat seat : chosen) {
            Button button = seatButtons.get(seat);
            if (button != null) {
                button.getStyleClass().remove("seat-picked");
                button.getStyleClass().add("seat-held");
                button.setOnAction(null);
            }
        }
        confirmSeats.setText("Held #" + held.bookingId());
        status.setText("Held  \u00b7  booking " + held.bookingId() + "  \u00b7  "
                + chosen.stream().map(Seat::label).collect(Collectors.joining(", "))
                + "  \u00b7  \u20b9" + money(held.total())
                + "  \u00b7  checkout is open on the right");
        stage.getChildren().add(new CheckoutPanel(
                new CheckoutService(db), held.bookingId(), showtime, chosen, soldBy, onDone,
                this::holdReleased).root());
        updateBar();
    }

    /**
     * Runs on the FX thread after the clerk releases an unpaid hold. The panel comes
     * down and the map is redrawn from the database: the seats are no longer in the
     * ticket table, so they come back free rather than still showing as held.
     */
    private void holdReleased() {
        stage.getChildren().removeIf(node -> isCheckout(node));
        render();
        status.setText("Hold released \u2014 the seats are back on sale.");
    }

    /** The checkout rail is the last thing on the stage after a hold. */
    private static boolean isCheckout(Node node) {
        return node.getStyleClass().contains("checkout-rail");
    }

    /** Runs on the FX thread when the hold failed; nothing was written. */
    private void holdFailed(Throwable failure) {
        if (failure instanceof SeatUnavailableException taken) {
            render();
            status.setText(taken.getMessage());
            status.getStyleClass().add("error");
        } else if (failure instanceof BookingException || failure instanceof SQLException) {
            status.setText(failure.getMessage());
            status.getStyleClass().add("error");
        } else if (failure instanceof DbUnavailableException) {
            status.setText("MySQL is not running \u2014 the seats were not held.");
            status.getStyleClass().add("error");
        } else {
            // Unexpected: report it rather than leaving the clerk staring at a
            // disabled button that never comes back.
            status.setText("The seats were not held: " + failure.getMessage());
            status.getStyleClass().add("error");
        }
        updateBar();
    }

    /**
     * A pick is a preview, not a hold: the seat turns green and the footer adds it to
     * the bill. Nothing reaches the database until Confirm runs {@link #holdSeats()}.
     */
    private void pick(Seat seat, boolean isSold, Button button) {
        status.getStyleClass().remove("error");
        if (isSold) {
            status.setText(seat.label() + " is already sold for this screening.");
            status.getStyleClass().add("error");
            return;
        }

        if (pickedSeats.remove(seat)) {
            button.getStyleClass().remove("seat-picked");
            status.setText(seat.label() + " removed. Pick seats to build the bill.");
        } else {
            pickedSeats.add(seat);
            button.getStyleClass().add("seat-picked");
            status.setText(seat.label() + "  \u00b7  " + pretty(seat.seatClass())
                    + "  \u00b7  \u20b9" + seat.priceFor(showtime).toPlainString()
                    + "  \u00b7  picked for preview, not held");
        }
        updateBar();
    }

    /** Whole rupees print without a decimal tail; 362.50 keeps its paise. */
    private static String money(BigDecimal amount) {
        String text = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return text.endsWith(".00") ? text.substring(0, text.length() - 3) : text;
    }

    private static Label rowLetter(char label) {
        Label letter = new Label(String.valueOf(label));
        letter.getStyleClass().add("seat-row-letter");
        letter.setMinWidth(LETTER_W);
        letter.setPrefWidth(LETTER_W);
        letter.setMaxWidth(LETTER_W);
        return letter;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("seat-rule");
        rule.setMinHeight(1);
        rule.setPrefHeight(1);
        rule.setMaxHeight(1);
        return rule;
    }

    private static double seatWidth(SeatClass seatClass) {
        return switch (seatClass) {
            case REGULAR -> SEAT_W;
            case PREMIUM -> PREMIUM_W;
            case RECLINER -> CHAIR_W;
        };
    }

    private static double seatHeight(SeatClass seatClass) {
        return seatClass == SeatClass.RECLINER ? CHAIR_H : SEAT_H;
    }

    private static double seatGap(SeatClass seatClass) {
        return seatClass == SeatClass.RECLINER ? CHAIR_GAP : SEAT_GAP;
    }

    private static double rowWidth(SeatClass seatClass, int columns) {
        return columns * seatWidth(seatClass) + Math.max(0, columns - 1) * seatGap(seatClass);
    }

    private static Region spacer(double width, double height) {
        Region spacer = new Region();
        spacer.setMinSize(width, height);
        spacer.setPrefSize(width, height);
        spacer.setMaxSize(width, height);
        return spacer;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("error");
        label.setWrapText(true);
        label.setMaxWidth(PAGE_W - 80);
        return label;
    }

    private static String pretty(SeatClass seatClass) {
        String lower = seatClass.name().toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
