package com.cinebook.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The tab and preset registry, tested without MySQL. */
class ReportCatalogTest {

    @Test
    void fiveTabsAreTheFiveRubricQueries() {
        List<ReportQuery> tabs = ReportCatalog.tabs();
        assertEquals(List.of("2", "5", "4", "10", "6"),
                tabs.stream().map(ReportQuery::id).toList(),
                "the ledger opens revenue, top films, dormant, occupancy, loyalty");
        assertEquals(5, tabs.size());
        assertFalse(tabs.stream().anyMatch(tab -> tab.title().isBlank() || tab.sql().isBlank()));
    }

    @Test
    void thirteenPresetsAreNumberedOneToThirteen() {
        List<SqlPreset> presets = ReportCatalog.presets();
        assertEquals(13, presets.size());
        assertEquals(IntStream.rangeClosed(1, 13).boxed().toList(),
                presets.stream().map(preset -> Integer.parseInt(preset.id())).toList());
    }

    @Test
    void aTabsPresetIsTheSameSqlNotASecondCopy() {
        for (ReportQuery tab : ReportCatalog.tabs()) {
            SqlPreset preset = ReportCatalog.presets().stream()
                    .filter(candidate -> candidate.id().equals(tab.id()))
                    .findFirst().orElseThrow();
            assertEquals(tab.sql(), preset.sql(), "preset " + tab.id() + " drifted from its tab");
            assertEquals(tab.title(), preset.title());
        }
    }
}
