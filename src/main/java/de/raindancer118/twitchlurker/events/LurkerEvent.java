package de.raindancer118.twitchlurker.events;

import java.time.Instant;

public record LurkerEvent(long id, Instant ts, String type, String login, Long amount, String detail) {

    public LurkerEvent withId(long newId) {
        return new LurkerEvent(newId, ts, type, login, amount, detail);
    }
}
