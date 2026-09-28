package de.raindancer118.twitchlurker.events;

import de.raindancer118.twitchlurker.live.LiveBus;
import java.time.Clock;

public class EventService {

    private final EventRepository repository;
    private final LiveBus bus;
    private final Clock clock;

    public EventService(EventRepository repository, LiveBus bus, Clock clock) {
        this.repository = repository;
        this.bus = bus;
        this.clock = clock;
    }

    public LurkerEvent record(String type, String login, Long amount, String detail) {
        var stored = repository.insert(new LurkerEvent(0, clock.instant(), type, login, amount, detail));
        bus.publish("event", stored);
        return stored;
    }
}
