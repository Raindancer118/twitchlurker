package de.raindancer118.twitchlurker.web;

import de.raindancer118.twitchlurker.live.LiveBus;
import de.raindancer118.twitchlurker.miner.MinerState;
import de.raindancer118.twitchlurker.miner.MinerSupervisor;
import de.raindancer118.twitchlurker.raffle.RaffleService;
import de.raindancer118.twitchlurker.settings.InvalidSettingsException;
import de.raindancer118.twitchlurker.settings.LurkerSettings;
import de.raindancer118.twitchlurker.settings.SettingsStore;
import de.raindancer118.twitchlurker.twitch.TwitchAuthService;
import de.raindancer118.twitchlurker.twitch.TwitchAuthStatus;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,25}");

    public record Me(String name, String email) {}

    public record BotView(DashboardService.Bot bot, List<MinerState.LogLine> logs) {}

    public record ToggleRequest(boolean enabled) {}

    public record AddChannelRequest(String login) {}

    private final DashboardService dashboard;
    private final MinerSupervisor supervisor;
    private final MinerState state;
    private final TwitchAuthService auth;
    private final RaffleService raffles;
    private final SettingsStore settings;
    private final LiveBus bus;

    public ApiController(DashboardService dashboard, MinerSupervisor supervisor, MinerState state, TwitchAuthService auth,
                         RaffleService raffles, SettingsStore settings, LiveBus bus) {
        this.dashboard = dashboard;
        this.supervisor = supervisor;
        this.state = state;
        this.auth = auth;
        this.raffles = raffles;
        this.settings = settings;
        this.bus = bus;
    }

    @GetMapping("/me")
    Me me(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OidcUser oidc) {
            String name = oidc.getGivenName() != null ? oidc.getGivenName()
                    : oidc.getPreferredUsername() != null ? oidc.getPreferredUsername() : oidc.getName();
            return new Me(name, oidc.getEmail());
        }
        return new Me(((Principal) authentication).getName(), null);
    }

    @GetMapping("/overview")
    DashboardService.Overview overview() {
        return dashboard.overview();
    }

    @GetMapping("/channels")
    List<DashboardService.Channel> channels() {
        return dashboard.channels();
    }

    @GetMapping("/channels/{login}")
    DashboardService.ChannelDetail channel(@PathVariable String login, @RequestParam(defaultValue = "30") int days) {
        return dashboard.channel(requireLogin(login), Math.clamp(days, 1, 365));
    }

    @PostMapping("/channels")
    List<DashboardService.Channel> addChannel(@RequestBody AddChannelRequest request) {
        String login = requireLogin(request.login() == null ? "" : request.login().strip().toLowerCase());
        var s = settings.get();
        if (!s.streamers().contains(login)) {
            var list = new ArrayList<>(s.streamers());
            list.add(login);
            updateSettings(s.withStreamers(list));
        }
        return dashboard.channels();
    }

    @PostMapping("/channels/{login}/raffles")
    DashboardService.Channel toggleRaffles(@PathVariable String login, @RequestBody ToggleRequest request) {
        requireLogin(login);
        var s = settings.get();
        var r = s.raffle();
        var disabled = new ArrayList<>(r.disabledChannels());
        disabled.remove(login);
        if (!request.enabled()) {
            disabled.add(login);
        }
        updateSettings(s.withRaffle(new LurkerSettings.Raffle(r.enabled(), r.joinCommands(), r.bots(), disabled,
                r.minDelaySeconds(), r.maxDelaySeconds(), r.cooldownSeconds())));
        return dashboard.channels().stream().filter(c -> c.login().equals(login)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/drops")
    DashboardService.Drops drops() {
        return dashboard.drops();
    }

    @GetMapping("/raffles")
    DashboardService.Raffles raffles() {
        return dashboard.raffles();
    }

    @GetMapping("/bot")
    BotView bot(@RequestParam(defaultValue = "300") int logs) {
        return new BotView(dashboard.bot(), state.logs(Math.clamp(logs, 1, 1000)));
    }

    @PostMapping("/bot/{action}")
    DashboardService.Bot botAction(@PathVariable String action) {
        switch (action) {
            case "start" -> supervisor.start();
            case "stop" -> supervisor.stop();
            case "restart" -> supervisor.restart();
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        bus.publish("bot", dashboard.bot());
        return dashboard.bot();
    }

    @GetMapping("/twitch")
    TwitchAuthStatus twitch() {
        return auth.status();
    }

    @PostMapping("/twitch/login")
    TwitchAuthStatus twitchLogin() {
        return auth.startDeviceLogin();
    }

    @PostMapping("/twitch/cancel")
    TwitchAuthStatus twitchCancel() {
        auth.cancelDeviceLogin();
        return auth.status();
    }

    @PostMapping("/twitch/logout")
    TwitchAuthStatus twitchLogout() {
        auth.logout();
        return auth.status();
    }

    @GetMapping("/settings")
    LurkerSettings settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    LurkerSettings saveSettings(@RequestBody LurkerSettings incoming) {
        // autostart, order and slots have their own controls and are never changed by the settings form
        var current = settings.get();
        return updateSettings(new LurkerSettings(incoming.followers(), incoming.streamers(), incoming.blacklist(),
                incoming.priority(), incoming.followRaid(), incoming.claimMoments(), incoming.watchStreak(),
                incoming.dropScout(), incoming.raffle(), current.autostart(), current.order(), current.slots()));
    }

    public record SlotsRequest(List<String> slots) {}

    public record OrderRequest(List<String> order) {}

    @PutMapping("/slots")
    LurkerSettings slots(@RequestBody SlotsRequest request) {
        return updateSettings(settings.get().withSlots(request.slots()));
    }

    @PutMapping("/order")
    LurkerSettings order(@RequestBody OrderRequest request) {
        return updateSettings(settings.get().withOrder(request.order()));
    }

    @GetMapping(path = "/live", produces = "text/event-stream")
    SseEmitter live() {
        return bus.subscribe();
    }

    private LurkerSettings updateSettings(LurkerSettings next) {
        var before = settings.get();
        var saved = settings.save(next);
        Thread.ofVirtual().name("settings-apply").start(() -> {
            supervisor.onSettingsChanged(before, saved);
            raffles.sync();
        });
        return saved;
    }

    private static String requireLogin(String login) {
        if (!LOGIN.matcher(login).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültiger Twitch-Name");
        }
        return login;
    }

    @ExceptionHandler(InvalidSettingsException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidSettingsException e) {
        return ResponseEntity.badRequest().body(Map.of("errors", e.errors()));
    }
}
