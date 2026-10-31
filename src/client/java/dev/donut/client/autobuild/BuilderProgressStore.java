package dev.donut.client.autobuild;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One readable JSON file per schematic holding every block that was confirmed placed, grouped by layer (Y).
 * It is loaded on its own, so it can be edited while the builder is not running: delete a coordinate to have that
 * block built again. Only plain Java types are used so the format can be tested without Minecraft.
 */
public final class BuilderProgressStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/BuilderProgress");
    private static final Gson GSON = new Gson();

    /** {@code layers} maps a layer's Y to the {x, z} columns already placed on it. */
    public record Snapshot(String schematic, int blocks, int[] dimensions, int[] origin, int quarterTurns,
                           boolean mirrorX, int placed, int total, SortedMap<Integer, List<int[]>> layers) {
        public boolean sameTransform(Snapshot other) {
            return Arrays.equals(origin, other.origin) && quarterTurns == other.quarterTurns && mirrorX == other.mirrorX;
        }
    }

    private final Path directory;

    public BuilderProgressStore(Path directory) { this.directory = directory; }

    public Path file(String schematic, int blocks) {
        String safe = schematic.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return directory.resolve(safe + "-" + blocks + ".json");
    }

    /** Returns null when there is no file or it cannot be read; an unreadable file is left untouched. */
    public Snapshot load(String schematic, int blocks) {
        Path path = file(schematic, blocks);
        if (!Files.isRegularFile(path)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("blocks").getAsInt() != blocks) return null;
            SortedMap<Integer, List<int[]>> layers = new TreeMap<>();
            int placed = 0;
            for (Map.Entry<String, JsonElement> layer : root.getAsJsonObject("layers").entrySet()) {
                List<int[]> columns = new ArrayList<>();
                for (JsonElement column : layer.getValue().getAsJsonObject().getAsJsonArray("blocks")) {
                    JsonArray pair = column.getAsJsonArray();
                    columns.add(new int[]{pair.get(0).getAsInt(), pair.get(1).getAsInt()});
                }
                layers.put(Integer.parseInt(layer.getKey()), columns);
                placed += columns.size();
            }
            return new Snapshot(root.get("schematic").getAsString(), blocks, ints(root.getAsJsonArray("dimensions")),
                    ints(root.getAsJsonArray("origin")), root.get("quarterTurns").getAsInt(),
                    root.get("mirrorX").getAsBoolean(), placed, root.get("total").getAsInt(), layers);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Cannot read builder progress {}", path, e);
            return null;
        }
    }

    /** Writes atomically. Progress recorded for a different position is kept as a .backup file, never discarded. */
    public void save(Snapshot snapshot) {
        Path path = file(snapshot.schematic(), snapshot.blocks());
        try {
            Files.createDirectories(directory);
            Snapshot old = Files.isRegularFile(path) ? load(snapshot.schematic(), snapshot.blocks()) : null;
            if (old != null && old.placed() > 0 && !old.sameTransform(snapshot)) {
                String name = path.getFileName().toString();
                Files.copy(path, directory.resolve(name.substring(0, name.length() - 5) + ".backup-" + System.currentTimeMillis() + ".json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, render(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Could not save builder progress to {}", path, e);
        }
    }

    static String render(Snapshot s) {
        double percent = s.total() == 0 ? 0 : Math.round(s.placed() * 10000.0 / s.total()) / 100.0;
        StringBuilder out = new StringBuilder(64 + s.placed() * 12);
        out.append("{\n");
        out.append("  \"schematic\": ").append(GSON.toJson(s.schematic())).append(",\n");
        out.append("  \"blocks\": ").append(s.blocks()).append(",\n");
        out.append("  \"dimensions\": ").append(Arrays.toString(s.dimensions())).append(",\n");
        out.append("  \"origin\": ").append(Arrays.toString(s.origin())).append(",\n");
        out.append("  \"quarterTurns\": ").append(s.quarterTurns()).append(",\n");
        out.append("  \"mirrorX\": ").append(s.mirrorX()).append(",\n");
        out.append("  \"placed\": ").append(s.placed()).append(",\n");
        out.append("  \"total\": ").append(s.total()).append(",\n");
        out.append("  \"percent\": ").append(percent).append(",\n");
        out.append("  \"updated\": \"").append(Instant.now()).append("\",\n");
        out.append("  \"layers\": {");
        boolean firstLayer = true;
        for (Map.Entry<Integer, List<int[]>> layer : s.layers().entrySet()) {
            out.append(firstLayer ? "\n" : ",\n");
            firstLayer = false;
            out.append("    \"").append(layer.getKey()).append("\": {\"placed\": ").append(layer.getValue().size())
                    .append(", \"blocks\": [");
            boolean first = true;
            for (int[] column : layer.getValue()) {
                if (!first) out.append(',');
                first = false;
                out.append('[').append(column[0]).append(',').append(column[1]).append(']');
            }
            out.append("]}");
        }
        out.append(firstLayer ? "}\n" : "\n  }\n").append("}\n");
        return out.toString();
    }

    private static int[] ints(JsonArray array) {
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsInt();
        return values;
    }
}
