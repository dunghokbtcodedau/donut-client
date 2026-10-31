package dev.donut.client.autobuild;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuilderProgressStoreTest {
    @TempDir Path directory;

    private static BuilderProgressStore.Snapshot snapshot(int originX, int... columnsOnLayer64) {
        TreeMap<Integer, List<int[]>> layers = new TreeMap<>();
        List<int[]> low = new ArrayList<>();
        for (int i = 0; i < columnsOnLayer64.length; i += 2) low.add(new int[]{columnsOnLayer64[i], columnsOnLayer64[i + 1]});
        layers.put(64, low);
        layers.put(65, new ArrayList<>(List.of(new int[]{-3, 7})));
        return new BuilderProgressStore.Snapshot("My Tower!.litematic", 8, new int[]{2, 2, 2}, new int[]{originX, 64, -5}, 1, true,
                low.size() + 1, 8, layers);
    }

    @Test void savedProgressLoadsBackExactlyAndIsGroupedByLayer() {
        var store = new BuilderProgressStore(directory);
        store.save(snapshot(10, 10, -5, 11, -5, 10, -4));
        var loaded = store.load("My Tower!.litematic", 8);
        assertNotNull(loaded);
        assertEquals(4, loaded.placed());
        assertEquals(3, loaded.layers().get(64).size());
        assertArrayEquals(new int[]{-3, 7}, loaded.layers().get(65).get(0));
        assertArrayEquals(new int[]{10, 64, -5}, loaded.origin());
        assertEquals(1, loaded.quarterTurns());
        assertTrue(loaded.mirrorX());
    }

    @Test void fileIsReadableAndRecordsPercent() throws IOException {
        var store = new BuilderProgressStore(directory);
        store.save(snapshot(10, 10, -5, 11, -5, 10, -4));
        var json = JsonParser.parseString(Files.readString(store.file("My Tower!.litematic", 8))).getAsJsonObject();
        assertEquals(50.0, json.get("percent").getAsDouble());
        assertEquals(4, json.get("placed").getAsInt());
        assertEquals(3, json.getAsJsonObject("layers").getAsJsonObject("64").get("placed").getAsInt());
    }

    @Test void editingTheFileChangesWhatIsLoaded() throws IOException {
        var store = new BuilderProgressStore(directory);
        store.save(snapshot(10, 10, -5, 11, -5));
        Path file = store.file("My Tower!.litematic", 8);
        Files.writeString(file, Files.readString(file).replace("[10,-5],", ""));
        assertEquals(2, store.load("My Tower!.litematic", 8).placed());
    }

    @Test void progressForAnotherPositionIsBackedUpNotLost() throws IOException {
        var store = new BuilderProgressStore(directory);
        store.save(snapshot(10, 10, -5));
        store.save(snapshot(500, 500, -5));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().contains(".backup-")).count());
        }
        assertArrayEquals(new int[]{500, 64, -5}, store.load("My Tower!.litematic", 8).origin());
    }

    @Test void missingOrBrokenFilesLoadAsNothingAndStayUntouched() throws IOException {
        var store = new BuilderProgressStore(directory);
        assertNull(store.load("none", 8));
        Path file = store.file("none", 8);
        Files.writeString(file, "{not json");
        assertNull(store.load("none", 8));
        assertEquals("{not json", Files.readString(file));
    }
}
