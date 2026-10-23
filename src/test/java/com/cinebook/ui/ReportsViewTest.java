package com.cinebook.ui;

import com.cinebook.dao.StaffDao;
import com.cinebook.model.StaffUser;
import com.cinebook.service.AuthService;
import com.cinebook.service.SqlPreset;
import com.cinebook.util.Db;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Reports screen driven on the FX thread: the ledger tabs replace the sheet,
 * the console's preset list comes from the same registry as the tabs, and the
 * shift summary is a menu item rather than a sixth tab.
 */
@Tag("ui")
class ReportsViewTest {

    private static final Db DB = Db.get();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.start();
    }

    @AfterAll
    static void stopToolkit() {
        DB.close();
    }

    @Test
    void theLedgerOpensOnRevenueWithFiveTabsAndTheConsole() throws Exception {
        Parent root = openReports();

        assertEquals(6, runOnFx(() -> root.lookupAll(".report-tab").size()),
                "five report tabs plus the SQL console");
        assertEquals("Revenue by screen", runOnFx(() -> ((Label) root.lookup("#report-title")).getText()));
        assertTrue(runOnFx(() -> root.lookup("#report-tab-2").getStyleClass().contains("selected")));
        assertNotNull(runOnFx(() -> root.lookup("#shift-summary")),
                "the shift summary is the menu item beside the tabs");
        assertTrue(runOnFx(() -> !reportRows(root).isEmpty()),
                "the first report must have rows from the seed");
    }

    @Test
    void theAdminShellOpensReportsFromTheRail() throws Exception {
        Parent[] root = new Parent[1];
        runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            StaffUser admin = auth.login("admin", "admin123");
            root[0] = new ShellView(auth, admin, () -> { }).root();
            attach(root[0]);
            return null;
        });

        runOnFx(() -> {
            Button reports = root[0].lookupAll(".nav-button").stream()
                    .map(node -> (Button) node)
                    .filter(button -> "Reports".equals(button.getText()))
                    .findFirst().orElseThrow();
            reports.fire();
            return null;
        });

        awaitFx(() -> root[0].lookup("#report-table") != null
                && !reportRows(root[0]).isEmpty());
        assertEquals("Reports", runOnFx(() -> ((Label) root[0].lookup(".section-title")).getText()));
        assertEquals("Revenue by screen",
                runOnFx(() -> ((Label) root[0].lookup("#report-title")).getText()));
    }

    @Test
    void clickingOccupancyReplacesTheSheet() throws Exception {
        Parent root = openReports();

        runOnFx(() -> {
            ((Button) root.lookup("#report-tab-10")).fire();
            return null;
        });

        awaitFx(() -> "Occupancy".equals(
                ((Label) root.lookup("#report-title")).getText())
                && reportTable(root).getColumns().size() == 7
                && !reportRows(root).isEmpty());

        assertEquals(7, runOnFx(() -> reportTable(root).getColumns().size()));
        assertTrue(runOnFx(() -> root.lookup("#report-tab-10")
                .getStyleClass().contains("selected")));
    }

    @Test
    void theConsoleLoadsAPresetAndRunsAReadQuery() throws Exception {
        Parent root = openReports();
        runOnFx(() -> {
            ((Button) root.lookup("#report-tab-console")).fire();
            return null;
        });

        @SuppressWarnings("unchecked")
        ComboBox<SqlPreset> presets = runOnFx(() -> (ComboBox<SqlPreset>) root.lookup("#sql-preset"));
        assertEquals(13, runOnFx(() -> presets.getItems().size()));

        runOnFx(() -> {
            presets.setValue(presets.getItems().stream()
                    .filter(preset -> "2".equals(preset.id()))
                    .findFirst().orElseThrow());
            return null;
        });
        assertTrue(runOnFx(() -> ((TextArea) root.lookup("#sql-editor"))
                .getText().contains("total_revenue")),
                "the preset must load its SQL into the editor");

        runOnFx(() -> {
            ((TextArea) root.lookup("#sql-editor")).setText("SELECT 1 AS one");
            ((Button) root.lookup("#sql-run")).fire();
            return null;
        });
        awaitFx(() -> consoleTable(root).getItems().size() == 1
                && ((Label) root.lookup("#sql-status")).getText().contains("1 row"));

        assertEquals(List.of("One"), runOnFx(() -> consoleTable(root).getColumns().stream()
                .map(column -> column.getText()).toList()));
    }

    @Test
    void aWriteInTheConsoleIsRefusedWithoutRunning() throws Exception {
        Parent root = openReports();
        runOnFx(() -> {
            ((Button) root.lookup("#report-tab-console")).fire();
            ((TextArea) root.lookup("#sql-editor")).setText("DELETE FROM movie");
            ((Button) root.lookup("#sql-run")).fire();
            return null;
        });

        awaitFx(() -> ((Label) root.lookup("#sql-status")).getText().contains("read-only"));
        assertTrue(runOnFx(() -> consoleTable(root).getItems().isEmpty()),
                "a refused statement must not leave rows in the grid");
    }

    @Test
    void theShiftSummaryOpensFromTheMenuAndComesBack() throws Exception {
        Parent root = openReports();

        runOnFx(() -> {
            MenuButton menu = (MenuButton) root.lookup("#shift-summary");
            menu.getItems().get(0).fire();
            return null;
        });
        awaitFx(() -> root.lookup("#shift-total") != null
                && !((Label) root.lookup("#shift-total")).getText().isBlank());

        String net = runOnFx(() -> ((Label) root.lookup("#shift-total")).getText());
        assertTrue(net.startsWith("₹"), "the till total must read as money: " + net);
        assertTrue(runOnFx(() -> root.lookup("#shift-report") != null));

        runOnFx(() -> {
            ((Button) root.lookup("#shift-back")).fire();
            return null;
        });
        awaitFx(() -> root.lookup("#report-table") != null
                && !reportRows(root).isEmpty());
    }

    private static Parent openReports() {
        Parent[] root = new Parent[1];
        runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            StaffUser admin = auth.login("admin", "admin123");
            ReportsView view = new ReportsView(auth);
            root[0] = (Parent) view.root();
            attach(root[0]);
            return null;
        });
        awaitFx(() -> reportTable(root[0]) != null && !reportRows(root[0]).isEmpty());
        return root[0];
    }

    @SuppressWarnings("unchecked")
    private static TableView<List<String>> reportTable(Parent root) {
        return (TableView<List<String>>) root.lookup("#report-table");
    }

    @SuppressWarnings("unchecked")
    private static TableView<List<String>> consoleTable(Parent root) {
        return (TableView<List<String>>) root.lookup("#sql-results");
    }

    private static List<List<String>> reportRows(Parent root) {
        TableView<List<String>> table = reportTable(root);
        return table == null ? List.of() : table.getItems();
    }

    private static void attach(Parent root) {
        Scene scene = new Scene(root, 1280, 780);
        scene.getStylesheets().add(
                java.util.Objects.requireNonNull(
                        ReportsViewTest.class.getResource("/app.css")).toExternalForm());
        root.applyCss();
        root.layout();
    }

    private static void awaitFx(java.util.function.Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            if (Boolean.TRUE.equals(runOnFx(condition::get))) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the FX thread never reached the awaited state");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the FX thread", interrupted);
            }
        }
    }

    private static <T> T runOnFx(Callable<T> action) {
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
