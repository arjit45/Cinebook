package com.cinebook.ui;

import com.cinebook.service.AuthService;
import com.cinebook.service.QueryResult;
import com.cinebook.service.ReportQuery;
import com.cinebook.service.ReportService;
import com.cinebook.service.ReportTable;
import com.cinebook.service.ShiftSummary;
import com.cinebook.service.SqlPreset;
import com.cinebook.util.Db;
import com.cinebook.util.Money;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Reports screen (docs/PRD.md §3 screen 7): five ledger tabs, a read-only
 * SQL console, and the shift summary behind a menu item.
 *
 * <p>The tabs are the five rubric queries from {@code db/04_queries.sql}. The
 * console's presets are built from the same query objects, so selecting a tab's
 * query there shows the same SQL the tab ran rather than a second copy. The
 * console is read-only by design: SELECT, SHOW, EXPLAIN and DESCRIBE run; DML
 * and DDL belong in Workbench (§16).</p>
 *
 * <p>The shift summary is not a tab. Closing the till is a different act from
 * studying the business, so it sits in a menu beside the tabs and opens the
 * clerk's Z-report: money taken today, the payment split, and refunds counted
 * against the clerk who cancelled, not the clerk who sold.</p>
 */
public final class ReportsView {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final Pattern NUMBER = Pattern.compile("[₹]?-?\\d[\\d,.]*%?");
    private static final Set<String> NUMERIC_COLUMNS = Set.of(
            "movie_id", "booking_id", "bookings", "tickets_sold", "seats_sold",
            "seats_available", "days_dormant", "rank", "revenue_rank",
            "ticket_revenue", "fnb_revenue", "total_revenue", "ticket_taken",
            "fnb_taken", "gross", "refunded", "total_spent", "occupancy_pct",
            "pct_of_total", "fnb_pct_of_tickets", "price", "amount");

    private final ReportService service;

    private final VBox root = new VBox(14);
    private final HBox tabBar = new HBox(4);
    private final Region tabSpacer = new Region();
    private final MenuButton shiftMenu = new MenuButton("Shift summary");
    private final VBox content = new VBox();

    private final VBox reportSheet = new VBox(12);
    private final Label reportTitle = new Label();
    private final Label reportQuestion = new Label();
    private final Label reportStatus = new Label();
    private final TableView<List<String>> reportTable = new TableView<>();

    private final VBox consoleSheet = new VBox(12);
    private final ComboBox<SqlPreset> presetBox = new ComboBox<>();
    private final TextArea sqlEditor = new TextArea();
    private final Button runSql = new Button("Run query");
    private final Button clearSql = new Button("Clear");
    private final Label consoleStatus = new Label();
    private final TableView<List<String>> consoleTable = new TableView<>();

    private final VBox shiftSheet = new VBox(14);
    private final VBox shiftBody = new VBox(14);

    private final List<Button> tabButtons = new ArrayList<>();
    private ReportQuery activeReport;
    private boolean consoleActive;
    private int requestSerial;

    public ReportsView(ReportService service) {
        this.service = service;
        build();
        List<ReportQuery> tabs = service.tabs();
        if (!tabs.isEmpty()) {
            openReport(tabs.get(0));
        }
    }

    public ReportsView(AuthService auth) {
        this(new ReportService(Db.get(), auth));
    }

    public Node root() {
        return root;
    }

    private void build() {
        root.setPadding(new Insets(10, 0, 20, 0));
        root.getStyleClass().add("reports-view");

        tabBar.setAlignment(Pos.CENTER_LEFT);
        tabBar.getStyleClass().add("report-tabs");
        for (ReportQuery report : service.tabs()) {
            Button tab = new Button(report.title());
            tab.setId("report-tab-" + report.id());
            tab.setUserData(report);
            tab.getStyleClass().add("report-tab");
            tab.setOnAction(event -> openReport(report));
            tabButtons.add(tab);
            tabBar.getChildren().add(tab);
        }
        Button consoleTab = new Button("SQL console");
        consoleTab.setId("report-tab-console");
        consoleTab.getStyleClass().add("report-tab");
        consoleTab.setOnAction(event -> openConsole());
        tabButtons.add(consoleTab);
        tabBar.getChildren().add(consoleTab);

        HBox.setHgrow(tabSpacer, Priority.ALWAYS);
        shiftMenu.setId("shift-summary");
        shiftMenu.getStyleClass().add("shift-menu");
        MenuItem openShift = new MenuItem("Open today's Z-report");
        openShift.setOnAction(event -> showShift());
        shiftMenu.getItems().add(openShift);

        HBox top = new HBox(12, tabBar, tabSpacer, shiftMenu);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("report-toolbar");

        buildReportSheet();
        buildConsoleSheet();
        buildShiftSheet();

        content.setFillWidth(true);
        root.getChildren().addAll(top, content);
    }

    private void buildReportSheet() {
        reportTitle.setId("report-title");
        reportTitle.getStyleClass().add("report-title");
        reportQuestion.getStyleClass().add("report-question");
        reportQuestion.setWrapText(true);
        reportStatus.setId("report-status");
        reportStatus.getStyleClass().add("report-status");

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        VBox words = new VBox(3, reportTitle, reportQuestion);
        HBox heading = new HBox(12, words, gap, reportStatus);
        heading.setAlignment(Pos.BOTTOM_LEFT);

        reportTable.setId("report-table");
        reportTable.getStyleClass().add("report-table");
        reportTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        reportTable.setPlaceholder(emptyLabel("Nothing sold in this period yet."));
        reportTable.setPrefHeight(320);
        reportTable.setMinHeight(220);

        reportSheet.getChildren().addAll(heading, reportTable);
        reportSheet.getStyleClass().add("report-sheet");
    }

    private void buildConsoleSheet() {
        Label title = new Label("SQL console");
        title.getStyleClass().add("report-title");
        Label question = new Label(
                "Ask the database a question. SELECT, SHOW, EXPLAIN and DESCRIBE run; changes belong in Workbench.");
        question.getStyleClass().add("report-question");
        question.setWrapText(true);

        presetBox.setId("sql-preset");
        presetBox.setPromptText("Load a saved query");
        presetBox.getItems().setAll(service.presets());
        presetBox.setPrefWidth(330);
        presetBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SqlPreset preset) {
                return preset == null ? "" : preset.id() + " · " + preset.title();
            }

            @Override
            public SqlPreset fromString(String text) {
                return null;
            }
        });
        presetBox.valueProperty().addListener((observable, oldValue, preset) -> {
            if (preset != null) {
                sqlEditor.setText(preset.sql());
                consoleStatus.getStyleClass().remove("error");
                consoleStatus.setText(preset.question());
            }
        });

        runSql.setId("sql-run");
        runSql.setOnAction(event -> runConsole());
        clearSql.getStyleClass().add("outline-button");
        clearSql.setOnAction(event -> clearConsole());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox toolbar = new HBox(10, presetBox, gap, runSql, clearSql);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("sql-toolbar");

        sqlEditor.setId("sql-editor");
        sqlEditor.getStyleClass().add("sql-editor");
        sqlEditor.setPromptText("SELECT ... FROM ...");
        sqlEditor.setWrapText(false);
        sqlEditor.setPrefRowCount(7);
        sqlEditor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F5) {
                runConsole();
                event.consume();
            }
        });

        consoleStatus.setId("sql-status");
        consoleStatus.getStyleClass().add("report-status");
        consoleStatus.setText("Read-only · 13 saved queries");

        consoleTable.setId("sql-results");
        consoleTable.getStyleClass().add("report-table");
        consoleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        consoleTable.setPlaceholder(emptyLabel("Run a query to see rows."));
        consoleTable.setPrefHeight(300);
        consoleTable.setMinHeight(180);

        Label hint = new Label("F5 runs the editor.");
        hint.getStyleClass().add("report-hint");

        consoleSheet.getStyleClass().add("report-sheet");
        consoleSheet.getChildren().addAll(title, question, toolbar, sqlEditor, consoleStatus,
                consoleTable, hint);
    }

    private void buildShiftSheet() {
        Button back = new Button("Back to reports");
        back.setId("shift-back");
        back.getStyleClass().add("link-button");
        back.setOnAction(event -> returnToActive());
        Label title = new Label("The till at close");
        title.getStyleClass().add("report-title");
        Label question = new Label(
                "Today's takings and refunds for the signed-in clerk, with the split by payment method.");
        question.getStyleClass().add("report-question");
        question.setWrapText(true);

        shiftSheet.setId("shift-report");
        shiftSheet.getStyleClass().add("shift-sheet");
        shiftSheet.getChildren().addAll(back, title, question, shiftBody);
    }

    private void openReport(ReportQuery report) {
        activeReport = report;
        consoleActive = false;
        selectTab(report.id());
        reportTitle.setText(report.title());
        reportQuestion.setText(report.question());
        reportStatus.getStyleClass().remove("error");
        reportStatus.setText("Reading…");
        reportTable.getItems().clear();
        reportTable.getColumns().clear();
        content.getChildren().setAll(reportSheet);

        int serial = ++requestSerial;
        FxTasks.run(
                () -> service.run(report),
                table -> {
                    if (serial == requestSerial) {
                        renderReport(table);
                    }
                },
                failure -> {
                    if (serial == requestSerial) {
                        reportStatus.getStyleClass().add("error");
                        reportStatus.setText("Could not read the report: " + failure.getMessage());
                    }
                });
    }

    private void openConsole() {
        consoleActive = true;
        selectTab("console");
        content.getChildren().setAll(consoleSheet);
    }

    private void showShift() {
        int serial = ++requestSerial;
        shiftBody.getChildren().setAll(emptyLabel("Reading the till…"));
        content.getChildren().setAll(shiftSheet);
        FxTasks.run(
                () -> service.shiftSummary(LocalDate.now()),
                summary -> {
                    if (serial == requestSerial) {
                        renderShift(summary);
                    }
                },
                failure -> {
                    if (serial == requestSerial) {
                        Label error = new Label("Could not read the till: " + failure.getMessage());
                        error.getStyleClass().add("error");
                        error.setWrapText(true);
                        shiftBody.getChildren().setAll(error);
                    }
                });
    }

    private void returnToActive() {
        if (consoleActive) {
            content.getChildren().setAll(consoleSheet);
        } else if (activeReport != null) {
            openReport(activeReport);
        }
    }

    private void selectTab(String id) {
        for (Button tab : tabButtons) {
            tab.getStyleClass().remove("selected");
            if (id.equals(tab.getId().replace("report-tab-", ""))) {
                tab.getStyleClass().add("selected");
            }
        }
    }

    private void renderReport(ReportTable table) {
        reportStatus.getStyleClass().remove("error");
        reportStatus.setText(table.rows().size() + (table.rows().size() == 1 ? " row" : " rows"));
        fillTable(reportTable, table.columns(), table.rows(), table.emptyMessage(),
                table.rows().size());
    }

    private void runConsole() {
        if (runSql.isDisabled()) {
            return;
        }
        String sql = sqlEditor.getText();
        runSql.setDisable(true);
        clearSql.setDisable(true);
        consoleStatus.getStyleClass().remove("error");
        consoleStatus.setText("Running…");
        FxTasks.run(
                () -> service.runSql(sql),
                result -> {
                    runSql.setDisable(false);
                    clearSql.setDisable(false);
                    renderConsole(result);
                },
                failure -> {
                    runSql.setDisable(false);
                    clearSql.setDisable(false);
                    consoleStatus.getStyleClass().add("error");
                    consoleStatus.setText(failure.getMessage());
                });
    }

    private void renderConsole(QueryResult result) {
        consoleStatus.getStyleClass().remove("error");
        String suffix = result.truncated() ? " · first " + 500 + " rows" : "";
        consoleStatus.setText(result.rows().size()
                + (result.rows().size() == 1 ? " row" : " rows")
                + " · " + result.millis() + " ms" + suffix);
        fillTable(consoleTable, result.columns(), result.rows(), "No rows returned.",
                result.rows().size());
    }

    private void clearConsole() {
        sqlEditor.clear();
        presetBox.setValue(null);
        consoleStatus.getStyleClass().remove("error");
        consoleStatus.setText("Read-only · 13 saved queries");
        consoleTable.getItems().clear();
        consoleTable.getColumns().clear();
        consoleTable.setPlaceholder(emptyLabel("Run a query to see rows."));
    }

    private void renderShift(ShiftSummary summary) {
        Label who = new Label(summary.clerkName() + "  ·  " + DATE.format(summary.date()));
        who.getStyleClass().add("shift-who");

        Label netCaption = new Label("Net in the till");
        netCaption.getStyleClass().add("shift-caption");
        Label net = new Label("₹" + Money.plain(summary.net()));
        net.setId("shift-total");
        net.getStyleClass().add("shift-total");
        if (summary.net().signum() < 0) {
            net.getStyleClass().add("error");
        }
        VBox netBlock = new VBox(2, netCaption, net);
        netBlock.getStyleClass().add("shift-net");

        HBox metrics = new HBox(30,
                metric("Bookings", String.valueOf(summary.bookings())),
                metric("Tickets", "₹" + Money.plain(summary.tickets())),
                metric("Food", "₹" + Money.plain(summary.food())),
                metric("Refunded", summary.refundCount() + "  ·  ₹" + Money.plain(summary.refunded())));
        metrics.getStyleClass().add("shift-metrics");

        HBox top = new HBox(12, new VBox(3, who, netBlock), new Region(), metrics);
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setAlignment(Pos.TOP_LEFT);

        Label splitTitle = new Label("By payment method");
        splitTitle.getStyleClass().add("report-section-title");
        VBox split = new VBox(0);
        split.getStyleClass().add("shift-methods");
        if (summary.methods().isEmpty()) {
            split.getChildren().add(emptyLabel("No payments on this shift yet."));
        } else {
            split.getChildren().add(methodHeader());
            for (ShiftSummary.MethodTotal method : summary.methods()) {
                split.getChildren().add(methodRow(method));
            }
        }

        Label note = new Label(
                "Refunds are counted against the clerk who cancelled, not the clerk who sold.");
        note.getStyleClass().add("report-hint");
        note.setWrapText(true);

        shiftBody.getChildren().setAll(top, splitTitle, split, note);
    }

    private static VBox metric(String caption, String value) {
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("shift-caption");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("shift-metric");
        return new VBox(2, captionLabel, valueLabel);
    }

    private static HBox methodHeader() {
        HBox row = new HBox(12,
                methodCell("Method", 110, true),
                methodCell("Bookings", 100, true),
                methodCell("Tickets", 130, true),
                methodCell("Food", 130, true),
                methodCell("Gross", 130, true));
        row.getStyleClass().add("shift-method-header");
        return row;
    }

    private static HBox methodRow(ShiftSummary.MethodTotal method) {
        HBox row = new HBox(12,
                methodCell(method.method().name(), 110, false),
                methodCell(String.valueOf(method.bookings()), 100, false),
                methodCell("₹" + Money.plain(method.tickets()), 130, false),
                methodCell("₹" + Money.plain(method.food()), 130, false),
                methodCell("₹" + Money.plain(method.gross()), 130, false));
        row.getStyleClass().add("shift-method-row");
        return row;
    }

    private static Label methodCell(String text, double width, boolean header) {
        Label label = new Label(text);
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.getStyleClass().add(header ? "field-label" : "shift-method-value");
        return label;
    }

    private void fillTable(TableView<List<String>> table, List<String> columns,
                           List<List<String>> rows, String emptyText, int rowCount) {
        table.getColumns().clear();
        for (int index = 0; index < columns.size(); index++) {
            final int cellIndex = index;
            String raw = columns.get(index);
            TableColumn<List<String>, String> column = new TableColumn<>(heading(raw));
            column.setSortable(false);
            column.setCellValueFactory(data -> new ReadOnlyStringWrapper(
                    cellIndex < data.getValue().size() ? data.getValue().get(cellIndex) : ""));
            if (isNumeric(raw, rows, index)) {
                column.getStyleClass().add("report-number-column");
                column.setCellFactory(ignored -> {
                    TableCell<List<String>, String> cell = new TableCell<>() {
                        @Override
                        protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty ? null : item);
                        }
                    };
                    cell.setAlignment(Pos.CENTER_RIGHT);
                    cell.getStyleClass().add("report-number-cell");
                    return cell;
                });
            }
            table.getColumns().add(column);
        }
        table.setItems(FXCollections.observableArrayList(rows));
        table.setPlaceholder(emptyLabel(emptyText));
        table.setPrefHeight(Math.min(420, Math.max(180, 52 + rowCount * 38)));
    }

    private static boolean isNumeric(String raw, List<List<String>> rows, int index) {
        if (NUMERIC_COLUMNS.contains(raw.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (rows.isEmpty()) {
            return false;
        }
        return rows.stream().allMatch(row -> index < row.size()
                && NUMBER.matcher(row.get(index)).matches());
    }

    /** SQL labels are readable at a glance without a second column-name table. */
    private static String heading(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "fnb_revenue" -> "Food revenue";
            case "fnb_taken" -> "Food taken";
            case "ticket_taken" -> "Tickets taken";
            case "ticket_revenue" -> "Ticket revenue";
            case "total_revenue" -> "Total revenue";
            case "total_spent" -> "Total spent";
            case "occupancy_pct" -> "Occupancy";
            case "pct_of_total" -> "% of total";
            case "fnb_pct_of_tickets" -> "Food % of tickets";
            case "movie_id" -> "Film ID";
            case "booking_id" -> "Booking";
            case "full_name" -> "Customer";
            case "last_screened_on" -> "Last screened";
            case "days_dormant" -> "Days dormant";
            case "seats_sold" -> "Seats sold";
            case "seats_available" -> "Seats free";
            case "show_date" -> "Date";
            case "start_time" -> "Starts";
            case "revenue_rank" -> "Rank";
            case "sold_by" -> "Sold by";
            case "tickets_sold" -> "Tickets sold";
            default -> {
                String words = key.replace('_', ' ');
                yield Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }

    private static Label emptyLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("report-empty");
        label.setWrapText(true);
        return label;
    }
}
