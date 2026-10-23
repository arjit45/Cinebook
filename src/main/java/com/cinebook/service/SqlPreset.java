package com.cinebook.service;

/**
 * One entry in the SQL console's preset dropdown. A preset is the query text
 * that gets loaded into the editor; it has not been run yet.
 */
public record SqlPreset(String id, String title, String question, String sql) {
}
