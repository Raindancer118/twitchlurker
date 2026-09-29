package de.raindancer118.twitchlurker.config;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class ShutdownGreeter implements ApplicationListener<ContextClosedEvent> {

    private final BackendLog log;

    public ShutdownGreeter(BackendLog log) {
        this.log = log;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Backend shutting down. Goodbye & thank you for using twitchlurker!");
    }
}
