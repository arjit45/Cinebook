package com.cinebook.ui;

import com.cinebook.dao.ShowtimeDao;
import com.cinebook.exception.DbUnavailableException;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.service.AuthService;
import com.cinebook.util.Db;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The application frame: a 208 px rail on ticket stock, rules instead of boxes,
 * and one section visible at a time.
 *
 * <p>The rail shows only what the signed-in role can reach, but the rail is not the
 * control — {@link AuthService#requireAdmin()} is (docs/PRD.md §16).
 */
public final class ShellView {

    private static final DateTimeFormatter TODAY =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY =
            DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_NUMBER =
            DateTimeFormatter.ofPattern("d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final AuthService auth;
    private final StaffUser user;
    private final Runnable onSignOut;

    private final BorderPane root = new BorderPane();
    /**
     * How often the open day re-checks whether a screening has come on. Fifteen
     * seconds is well inside a minute — the smallest unit the schedule prints —
     * and the check itself reads only memory, never the database.
     */
    private static final Duration STARTED_TICK = Duration.ofSeconds(15);
    private final Label title = new Label();
    private final Label meta = new Label();
    private final VBox body = new VBox(10);
    private final List<Button> navButtons = new ArrayList<>();
    // FlowPane, not HBox: if the programme ever holds more days than fit across, the
    // strip wraps above the table instead of being clipped by the no-hbar viewport.
    private final FlowPane dateStrip = new FlowPane(8, 8);
    private final VBox dayDetail = new VBox(10);
    private Map<LocalDate, List<Showtime>> scheduleByDate = new LinkedHashMap<>();
    /** What each rendered row believed about its screening, keyed by showtime id. */
    private final Map<Integer, Boolean> startedByShowtime = new HashMap<>();
    private LocalDate selectedDay;
    private boolean scheduleVisible;
    private final Clock clock;
    private final Timeline startedTicker;
    private ScrollPane contentScroll;
    private VBox scheduleCentre;

    public ShellView(AuthService auth, StaffUser user, Runnable onSignOut) {
        this(auth, user, onSignOut, STARTED_TICK, Clock.systemDefaultZone());
    }

    /**
     * The seam the flip test needs: a tick short enough to wait for, and a clock the
     * test moves by hand so "the film has started" arrives on cue rather than at 13:15.
     */
    ShellView(AuthService auth, StaffUser user, Runnable onSignOut,
              Duration startedTick, Clock clock) {
        this.auth = auth;
        this.user = user;
        this.onSignOut = onSignOut;
        this.clock = clock;
        build();
        // The role decides the landing screen; the rail only offers the sections it
        // can reach. Administrator.homeScreen() is Reports, operator is Schedule.
        select(user.homeScreen());
        startedTicker = playStartedTicker(startedTick);
    }

    /** Stops the schedule's started-check, so a test's short tick does not outlive it. */
    void stopStartedTicker() {
        startedTicker.stop();
    }

    private Timeline playStartedTicker(Duration tick) {
        Timeline timeline = new Timeline(new KeyFrame(
                javafx.util.Duration.millis(Math.max(50, tick.toMillis())),
                event -> refreshStartedRows()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        return timeline;
    }

    /**
     * Re-renders the open day the moment one of its screenings comes on, so the slate
     * cannot sit there offering seats for a film that is already running. Only a real
     * change of state triggers the rebuild: a row that still reads the way it was
     * drawn is left alone, so the tick costs one list walk and the day does not flash.
     */
    private void refreshStartedRows() {
        if (!scheduleVisible || selectedDay == null) {
            return;
        }
        for (Showtime show : scheduleByDate.getOrDefault(selectedDay, List.of())) {
            boolean started = isStarted(show);
            // An unknown row means the day has not been drawn yet; assume it disagrees
            // so the first pass after a rebuild always reconciles.
            if (startedByShowtime.getOrDefault(show.showtimeId(), !started) != started) {
                renderDay();
                return;
            }
        }
    }

    private boolean isStarted(Showtime show) {
        return !LocalDateTime.now(clock).isBefore(show.startsAt());
    }

    public Parent root() {
        return root;
    }

    private void build() {
        root.setLeft(rail());

        title.getStyleClass().add("section-title");
        meta.getStyleClass().add("muted");

        VBox heading = new VBox(6, title, meta);
        heading.setPadding(new Insets(40, 40, 18, 40));
        heading.getStyleClass().add("row-rule");

        body.setPadding(new Insets(28, 40, 40, 40));

        // The content scrolls rather than growing the window: a long schedule must not
        // push the rail (and the sign-out control) past the bottom edge.
        contentScroll = new ScrollPane(body);
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.getStyleClass().add("content-scroll");

        scheduleCentre = new VBox(heading, contentScroll);
        VBox.setVgrow(contentScroll, Priority.ALWAYS);
        root.setCenter(scheduleCentre);
    }

    private BorderPane rail() {
        Label brand = new Label("CineBook");
        brand.getStyleClass().add("wordmark");
        VBox brandBox = new VBox(brand);
        brandBox.getStyleClass().add("rail-brand");

        VBox nav = new VBox();
        for (String section : sections()) {
            Button button = new Button(section);
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setOnAction(event -> select(section));
            navButtons.add(button);
            nav.getChildren().add(button);
        }

        Label name = new Label(user.fullName());
        // Role only: "Operator · Schedule home" clipped in a 208 px rail, and the
        // landing screen already says where home is.
        Label role = new Label(user.role().name().charAt(0)
                + user.role().name().substring(1).toLowerCase(Locale.ENGLISH));
        role.getStyleClass().add("muted");
        Button signOut = new Button("Sign out");
        signOut.getStyleClass().add("link-button");
        signOut.setOnAction(event -> onSignOut.run());

        VBox userBox = new VBox(4, name, role, signOut);
        userBox.getStyleClass().add("rail-user");

        // BorderPane, not a VBox with a growing spacer: the top is pinned and the
        // user block is pinned to the bottom, so the block cannot be pushed past the
        // window edge when the content grows.
        VBox top = new VBox(brandBox, nav);
        BorderPane rail = new BorderPane();
        rail.setTop(top);
        rail.setBottom(userBox);
        rail.getStyleClass().add("rail");
        return rail;
    }

    private List<String> sections() {
        List<String> names = new ArrayList<>(List.of("Schedule", "Bookings"));
        if (user.canManageProgramme()) {
            names.add("Programme");
            names.add("Reports");
        }
        return names;
    }

    private void select(String section) {
        // Coming back from the seat map: the rail always returns to the section frame.
        if (scheduleCentre != null) {
            root.setCenter(scheduleCentre);
        }
        for (Button button : navButtons) {
            button.getStyleClass().remove("selected");
            if (button.getText().equals(section)) {
                button.getStyleClass().add("selected");
            }
        }
        title.setText(section);
        body.getChildren().clear();
        scheduleVisible = section.equals("Schedule");
        if (contentScroll != null) {
            contentScroll.setVvalue(0);
        }

        if (section.equals("Schedule")) {
            showSchedule();
        } else if (section.equals("Bookings")) {
            meta.setText("Sales register and refund cancellation");
            body.getChildren().add(new BookingsView(user).root());
        } else if (section.equals("Programme")) {
            meta.setText("Film catalogue and screening schedule");
            body.getChildren().add(new ProgrammeView(auth).root());
        } else if (section.equals("Reports")) {
            meta.setText("Five reports, the SQL console and the shift summary");
            body.getChildren().add(new ReportsView(auth).root());
        } else {
            meta.setText(TODAY.format(LocalDate.now()));
            Label empty = new Label("Nothing to show here yet.");
            empty.getStyleClass().add("muted");
            body.getChildren().add(empty);
        }
    }

    private void showSchedule() {
        try {
            // Every remaining day in the programme, so each date panel has the day's
            // full slate under it rather than the list stopping mid-week. The seed's
            // eight days are 32 rows; 40 is the headroom above that.
            List<Showtime> shows = new ShowtimeDao(Db.get()).upcoming(40);
            if (shows.isEmpty()) {
                meta.setText("No screenings scheduled");
                Label empty = new Label("Add one in Programme.");
                empty.getStyleClass().add("muted");
                body.getChildren().add(empty);
                return;
            }
            meta.setText(span(shows) + "  ·  " + shows.size() + " screenings");

            // Panels across the top, one day's screenings underneath. The whole week is
            // never printed as one long column: select a panel and only that day shows.
            scheduleByDate = shows.stream().collect(Collectors
                    .groupingBy(Showtime::showDate, LinkedHashMap::new, Collectors.toList()));
            startedByShowtime.clear();
            // Keep the day the person was looking at when the seat map closes; only a
            // first visit, or a day that no longer has screenings, falls back to today.
            if (selectedDay == null || !scheduleByDate.containsKey(selectedDay)) {
                selectedDay = scheduleByDate.containsKey(LocalDate.now())
                        ? LocalDate.now()
                        : scheduleByDate.keySet().iterator().next();
            }

            dateStrip.getChildren().clear();
            for (Map.Entry<LocalDate, List<Showtime>> day : scheduleByDate.entrySet()) {
                dateStrip.getChildren().add(datePanel(day.getKey(), day.getValue().size()));
            }
            dayDetail.getChildren().clear();
            body.getChildren().setAll(dateStrip, dayDetail);
            renderDay();
        } catch (DbUnavailableException unavailable) {
            meta.setText("MySQL is not running");
            body.getChildren().add(errorLine("Start the MySQL service, then open this section again."));
        } catch (SQLException failure) {
            meta.setText("The schedule could not be read");
            body.getChildren().add(errorLine(failure.getMessage()));
        }
    }

    private HBox headerRow() {
        HBox row = new HBox(16);
        row.getStyleClass().addAll("row-rule", "schedule-header");
        row.getChildren().addAll(
                cell("Film", 320, true),
                cell("Screen", 90, true),
                cell("Starts", 130, true),
                cell("Price", 90, true),
                cell("Free", 70, true));
        row.setPadding(new Insets(0, 0, 8, 0));
        return row;
    }

    /**
     * One clickable date stub in the strip: weekday, day number, month and the day's
     * screening count. Today's panel is marked; the selected panel is inked in.
     */
    private Button datePanel(LocalDate date, int count) {
        Label weekday = new Label(WEEKDAY.format(date).toUpperCase(Locale.ENGLISH));
        weekday.getStyleClass().add("date-weekday");

        HBox topLine = new HBox(6, weekday);
        topLine.setAlignment(Pos.CENTER_LEFT);
        if (date.equals(LocalDate.now())) {
            Label badge = new Label("TODAY");
            badge.getStyleClass().add("date-today");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            topLine.getChildren().addAll(gap, badge);
        }

        Label number = new Label(DAY_NUMBER.format(date));
        number.getStyleClass().add("date-day");
        Label month = new Label(MONTH.format(date).toUpperCase(Locale.ENGLISH));
        month.getStyleClass().add("date-month");
        HBox dayLine = new HBox(5, number, month);
        dayLine.setAlignment(Pos.BASELINE_LEFT);

        Label tally = new Label(count == 1 ? "1 screening" : count + " screenings");
        tally.getStyleClass().add("date-count");

        VBox stamp = new VBox(2, topLine, dayLine, tally);
        stamp.setPrefWidth(88);
        stamp.setMaxWidth(88);

        Button panel = new Button();
        panel.setGraphic(stamp);
        panel.setUserData(date);
        panel.getStyleClass().add("date-panel");
        if (date.equals(LocalDate.now())) {
            panel.getStyleClass().add("today");
        }
        panel.setMinWidth(112);
        panel.setPrefWidth(112);
        panel.setMaxWidth(112);
        panel.setAlignment(Pos.TOP_LEFT);
        panel.setOnAction(event -> {
            selectedDay = date;
            renderDay();
        });
        return panel;
    }

    /** Marks the chosen panel and swaps the table below it for that day's slate. */
    private void renderDay() {
        for (Node node : dateStrip.getChildren()) {
            Button panel = (Button) node;
            panel.getStyleClass().remove("selected");
            if (panel.getUserData().equals(selectedDay)) {
                panel.getStyleClass().add("selected");
            }
        }
        dayDetail.getChildren().setAll(headerRow());
        List<Showtime> dayShows = scheduleByDate.getOrDefault(selectedDay, List.of());
        if (dayShows.isEmpty()) {
            Label empty = new Label("No shows scheduled for this date. Try another day.");
            empty.getStyleClass().add("muted");
            empty.setPadding(new Insets(30, 0, 30, 0));
            dayDetail.getChildren().add(empty);
            return;
        }
        for (Showtime show : dayShows) {
            dayDetail.getChildren().add(scheduleRow(show));
        }
    }

    /**
     * A row is a button, not a decorated HBox: it gives the screening a focus stop,
     * Enter/Space, and a click target across the full width, which is what opens the
     * seat map.
     */
    private Button scheduleRow(Showtime show) {
        // A screening that is already on stays on the day's slate, muted and closed,
        // so the clerk sees the time and why it is gone instead of it vanishing
        // mid-day. The Free column carries the state because that is the column that
        // would otherwise read as an invitation to sell.
        boolean started = isStarted(show);
        startedByShowtime.put(show.showtimeId(), started);

        HBox cells = new HBox(16);
        Label price = cell(show.basePrice().toPlainString(), 90, false);
        price.getStyleClass().add("money");
        Label status = cell(started
                ? "STARTED"
                : String.valueOf(show.screen().seatsAvailable(show)), 70, false);
        if (started) {
            status.getStyleClass().add("started-flag");
        }
        cells.getChildren().addAll(
                cell(show.movie().title(), 320, false),
                cell(show.screen().name(), 90, false),
                cell(TIME.format(show.startsAt()), 130, false),
                price,
                status);

        Button row = new Button();
        row.setGraphic(cells);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("schedule-row");
        if (started) {
            row.getStyleClass().add("schedule-row-started");
            row.setDisable(true);
            return row;
        }
        row.setOnAction(event -> openSeatMap(show));
        return row;
    }

    private void openSeatMap(Showtime show) {
        root.setCenter(new SeatMapView(Db.get(), show, user, () -> select("Schedule")).root());
    }

    /** "20–27 Oct" when one month, "28 Sep – 4 Oct" when the week straddles two. */
    private static String span(List<Showtime> shows) {
        LocalDate first = shows.get(0).showDate();
        LocalDate last = shows.get(shows.size() - 1).showDate();
        if (first.equals(last)) {
            return SHORT_DATE.format(first);
        }
        if (first.getMonth() == last.getMonth() && first.getYear() == last.getYear()) {
            return first.getDayOfMonth() + "\u2013" + SHORT_DATE.format(last);
        }
        return SHORT_DATE.format(first) + " \u2013 " + SHORT_DATE.format(last);
    }

    private static Label cell(String text, double width, boolean header) {
        Label label = new Label(text);
        label.setMinWidth(width);
        label.setPrefWidth(width);
        if (header) {
            label.getStyleClass().add("field-label");
        }
        return label;
    }

    private static Label errorLine(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("error");
        label.setWrapText(true);
        return label;
    }
}
