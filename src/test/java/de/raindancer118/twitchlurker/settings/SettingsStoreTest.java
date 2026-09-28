package de.raindancer118.twitchlurker.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SettingsStoreTest {

    @TempDir
    Path dir;

    private SettingsStore store() {
        return new SettingsStore(dir, JsonMapper.builder().build());
    }

    @Test
    void defaultsWhenNoFile() {
        var s = store().get();
        assertThat(s.followers()).isTrue();
        assertThat(s.priority()).containsExactly("STREAK", "DROPS", "ORDER");
        assertThat(s.raffle().enabled()).isTrue();
        assertThat(s.raffle().joinCommands()).contains("join", "enter").doesNotContain("raffle", "giveaway");
        assertThat(s.raffle().bots()).contains("streamelements", "nightbot");
        assertThat(s.dropScout().enabled()).isTrue();
        assertThat(s.autostart()).isTrue();
    }

    @Test
    void savesNormalizedAndReloads() {
        var st = store();
        var s = st.get();
        var changed = s.withStreamers(List.of(" Papaplatte ", "zarbex", "zarbex")).withBlacklist(List.of("TRYMACS"));
        st.save(changed);
        var reloaded = store().get();
        assertThat(reloaded.streamers()).containsExactly("papaplatte", "zarbex");
        assertThat(reloaded.blacklist()).containsExactly("trymacs");
        assertThat(Files.exists(dir.resolve("settings.json"))).isTrue();
    }

    @Test
    void rejectsInvalidLogins() {
        var st = store();
        assertThatThrownBy(() -> st.save(st.get().withStreamers(List.of("ok_name", "bad name; rm -rf"))))
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("bad name");
    }

    @Test
    void rejectsUnknownPriorityAndBadDelays() {
        var st = store();
        var s = st.get();
        assertThatThrownBy(() -> st.save(new LurkerSettings(s.followers(), s.streamers(), s.blacklist(), List.of("STREAK", "MAGIC"),
                s.followRaid(), s.claimMoments(), s.watchStreak(), s.dropScout(), s.raffle(), s.autostart(), s.order(), s.slots(), s.lurk())))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("MAGIC");
        var badRaffle = new LurkerSettings.Raffle(true, s.raffle().joinCommands(), s.raffle().bots(), List.of(), 30, 5, 180);
        assertThatThrownBy(() -> st.save(s.withRaffle(badRaffle)))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("Verzögerung");
    }

    @Test
    void slotsAndOrderAreNormalizedAndValidated() {
        var st = store();
        assertThat(st.get().slots()).containsExactly(null, null);
        assertThat(st.get().order()).isEmpty();
        var saved = st.save(st.get().withSlots(java.util.Arrays.asList(" Zarbex ", "")).withOrder(List.of("B_b", "a_a", "b_b")));
        assertThat(saved.slots()).containsExactly("zarbex", null);
        assertThat(saved.order()).containsExactly("b_b", "a_a");
        assertThat(store().get().slots()).containsExactly("zarbex", null);
        assertThatThrownBy(() -> st.save(st.get().withSlots(List.of("no way"))))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("no way");
        assertThat(saved.minerRelevantDiff(saved.withSlots(List.of("x_x")))).isFalse();
        assertThat(saved.minerRelevantDiff(saved.withOrder(List.of("x_x")))).isFalse();
    }

    @Test
    void migratesTheOldDefaultJoinCommandsButKeepsCustomLists() throws Exception {
        Files.writeString(dir.resolve("settings.json"),
                "{\"raffle\":{\"joinCommands\":[\"join\",\"enter\",\"raffle\",\"giveaway\",\"gw\"]}}");
        assertThat(store().get().raffle().joinCommands()).containsExactly("join", "enter", "gw");
        Files.writeString(dir.resolve("settings.json"), "{\"raffle\":{\"joinCommands\":[\"raffle\",\"join\"]}}");
        assertThat(store().get().raffle().joinCommands()).containsExactly("raffle", "join");
    }

    @Test
    void watchedGamesAreNormalizedValidatedAndLive() {
        var st = store();
        var s = st.get();
        assertThat(s.dropScout().games()).isEmpty();
        var saved = st.save(s.withWatchGames(List.of(" Minecraft ", "minecraft", "VALORANT", "")));
        assertThat(saved.dropScout().games()).containsExactly("Minecraft", "VALORANT");
        assertThat(saved.minerRelevantDiff(saved.withWatchGames(List.of("Rust")))).isFalse();
        assertThatThrownBy(() -> st.save(s.withWatchGames(List.of("x".repeat(81)))))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("Spiel");
    }

    @Test
    void lurkDefaultsAndValidation() {
        var st = store();
        var lurk = st.get().lurk();
        assertThat(lurk.enabled()).isTrue();
        assertThat(lurk.message()).isEqualTo("!lurk");
        assertThat(lurk.repeatMinutes()).isZero();
        assertThatThrownBy(() -> st.save(st.get().withLurk(new LurkerSettings.Lurk(true, "!lurk\nspam", 0))))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("Lurk");
        assertThatThrownBy(() -> st.save(st.get().withLurk(new LurkerSettings.Lurk(true, "!lurk", 5))))
                .isInstanceOf(InvalidSettingsException.class).hasMessageContaining("Wiederholung");
        assertThat(st.save(st.get().withLurk(new LurkerSettings.Lurk(true, "  !lurk bin im Hintergrund ", 120))).lurk().message())
                .isEqualTo("!lurk bin im Hintergrund");
    }

    @Test
    void toleratesUnknownFieldsInStoredFile() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"followers\":false,\"somethingOld\":1}");
        var s = store().get();
        assertThat(s.followers()).isFalse();
        assertThat(s.priority()).containsExactly("STREAK", "DROPS", "ORDER");
    }
}
