package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoMineSettingsTest {
    @TempDir Path directory;

    @Test
    void miningNeverResumesFromSavedConfigurationAndValuesAreClamped() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        ClientConfig config = new ClientConfig();
        config.autoMine.enabled = true;
        config.autoMine.retryLimit = 999;
        config.autoMine.quantity = -5;
        store.save(config);

        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("autoMine");
        assertFalse(json.has("enabled"));
        assertFalse(json.has("status"));
        ClientConfig loaded = store.load();
        assertFalse(loaded.autoMine.enabled);
        assertEquals(200, loaded.autoMine.retryLimit);
        assertEquals(0, loaded.autoMine.quantity);
        assertTrue(loaded.autoMine.retryRejected);
    }

    @Test
    void olderConfigurationsGetDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, "{\"autoMine\":null}");
        ClientConfig loaded = new ConfigStore(path).load();
        assertNotNull(loaded.autoMine);
        assertEquals(0, loaded.autoMine.retryLimit);
        assertTrue(loaded.autoMine.blockNames().contains("diamond_ore"));
    }

    @Test
    void presetsIncludeDeepslateVariantsAndCustomNamesAreFiltered() {
        AutoMineSettings s = new AutoMineSettings();
        s.diamond = false;
        s.iron = true;
        s.customBlocks = new ArrayList<>(List.of("minecraft:obsidian", "Bad Name!", " GLOWSTONE "));
        s.sanitize();
        List<String> names = s.blockNames();
        assertTrue(names.containsAll(List.of("iron_ore", "deepslate_iron_ore", "minecraft:obsidian", "glowstone")));
        assertFalse(names.contains("diamond_ore"));
        assertEquals(2, s.customBlocks.size());
    }

    @Test
    void areaIsNormalisedFromAnyTwoCorners() {
        AutoMineSettings s = new AutoMineSettings();
        assertTrue(s.areaMode);
        assertFalse(s.areaReady());
        s.setPoint1(10, 64, -5);
        s.setPoint2(2, 60, 3);
        assertTrue(s.areaReady());
        assertEquals(2, s.minX());
        assertEquals(10, s.maxX());
        assertEquals(60, s.minY());
        assertEquals(64, s.maxY());
        assertEquals(-5, s.minZ());
        assertEquals(3, s.maxZ());
        assertEquals(9L * 5 * 9, s.areaVolume());
        s.clearPoints();
        assertEquals(0, s.areaVolume());
    }
}
