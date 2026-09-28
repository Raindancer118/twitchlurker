package de.raindancer118.twitchlurker.config;

import de.raindancer118.twitchlurker.events.EventRepository;
import de.raindancer118.twitchlurker.events.EventService;
import de.raindancer118.twitchlurker.events.SnapshotRepository;
import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.miner.MinerMessageHandler;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.miner.MinerSupervisor;
import de.raindancer118.twitchlurker.raffle.RaffleRepository;
import de.raindancer118.twitchlurker.raffle.RaffleService;
import de.raindancer118.twitchlurker.raffle.TwitchChatClient;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.twitch.TokenStore;
import de.raindancer118.twitchlurker.twitch.TwitchAuthService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService lurkerTimers() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("lurker-timer-", 0).daemon().factory());
    }

    @Bean
    RaffleService.Delayer delayer(ScheduledExecutorService lurkerTimers) {
        return (task, delay) -> lurkerTimers.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Bean
    LiveBus liveBus() {
        return new LiveBus();
    }

    @Bean
    SettingsStore settingsStore(LurkerProperties props, JsonMapper json) {
        try {
            Files.createDirectories(props.dataDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new SettingsStore(props.dataDir(), json);
    }

    @Bean
    TokenStore tokenStore(LurkerProperties props, JsonMapper json) {
        return new TokenStore(props.dataDir(), json);
    }

    @Bean
    TwitchAuthService twitchAuthService(RestClient.Builder builder, LurkerProperties props, TokenStore store,
                                        ApplicationEventPublisher events) {
        return new TwitchAuthService(builder.clone(), props.twitchIdBase(), store, events);
    }

    @Bean
    EventRepository eventRepository(JdbcClient jdbc) {
        return new EventRepository(jdbc);
    }

    @Bean
    SnapshotRepository snapshotRepository(JdbcClient jdbc) {
        return new SnapshotRepository(jdbc);
    }

    @Bean
    RaffleRepository raffleRepository(JdbcClient jdbc) {
        return new RaffleRepository(jdbc);
    }

    @Bean
    EventService eventService(EventRepository repo, LiveBus bus, Clock clock) {
        return new EventService(repo, bus, clock);
    }

    @Bean
    MinerState minerState() {
        return new MinerState();
    }

    @Bean
    MinerMessageHandler minerMessageHandler(JsonMapper json, MinerState state, EventService events, SnapshotRepository snapshots,
                                            LiveBus bus, Clock clock) {
        return new MinerMessageHandler(json, state, events, snapshots, bus, clock);
    }

    @Bean(destroyMethod = "shutdown")
    MinerSupervisor minerSupervisor(LurkerProperties props, SettingsStore settings, TwitchAuthService auth, TokenStore tokens,
                                    MinerMessageHandler handler, MinerState state, JsonMapper json) {
        return new MinerSupervisor(props.dataDir(), props.pythonCommand(), props.runnerScript(), tokens.file(), settings,
                auth::token, handler, state, json, Duration.ofSeconds(5));
    }

    @Bean(destroyMethod = "close")
    TwitchChatClient twitchChatClient(RaffleService.Delayer delayer) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return new TwitchChatClient(TwitchChatClient.webSocketConnector(http), delayer);
    }

    @Bean
    de.raindancer118.twitchlurker.raffle.LurkAnnouncer lurkAnnouncer(SettingsStore settings, MinerState state, TwitchChatClient chat,
                                                                     EventService events, EventRepository eventRepository,
                                                                     RaffleService.Delayer delayer, Clock clock) {
        return new de.raindancer118.twitchlurker.raffle.LurkAnnouncer(settings, state, chat, events, eventRepository, delayer, clock,
                new SecureRandom());
    }

    @Bean
    RaffleService raffleService(SettingsStore settings, TwitchAuthService auth, MinerState state, RaffleRepository raffles,
                                EventService events, TwitchChatClient chat, RaffleService.Delayer delayer, Clock clock) {
        return new RaffleService(settings, auth::token, state, raffles, events, chat, delayer, clock, new SecureRandom());
    }
}
