package com.cinebook.model;

/** An auditorium. Its capacity is the ceiling the seat map is built from. */
public final class Screen {

    private final int screenId;
    private final String name;
    private final int seatCapacity;

    public Screen(int screenId, String name, int seatCapacity) {
        this.screenId = screenId;
        this.name = name;
        this.seatCapacity = seatCapacity;
    }

    public int screenId() {
        return screenId;
    }

    public String name() {
        return name;
    }

    public int seatCapacity() {
        return seatCapacity;
    }

    /** Seats still free for a screening, never negative even if the data drifts. */
    public int seatsAvailable(Showtime showtime) {
        return Math.max(0, seatCapacity - showtime.seatsSold());
    }
}
