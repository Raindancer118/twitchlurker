package de.raindancer118.twitchlurker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class ShutdownGreeter implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ShutdownGreeter.class);

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Backend shutting down. Goodbye & thank you for using twitchlurker!");
    }
}
