package de.raindancer118.twitchlurker.raffle;

import java.time.Instant;

public record RaffleEntry(long id, Instant ts, String channel, String triggerUser, String triggerMessage, String command,
                          Status status, String detail, Instant sentAt) {

    public enum Status { PENDING, JOINED, SKIPPED, FAILED, WON }
}
