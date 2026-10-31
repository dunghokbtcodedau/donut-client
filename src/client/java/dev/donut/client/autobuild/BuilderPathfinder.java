package dev.donut.client.autobuild;

import java.util.*;
import java.util.function.Predicate;

/** Bounded A* over walkable feet cells. Pure geometry keeps route behavior independently testable. */
public final class BuilderPathfinder {
    public record Cell(int x, int y, int z) {
        public Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        public double distance(Cell other) { return Math.sqrt((double)(x-other.x)*(x-other.x) + (double)(y-other.y)*(y-other.y) + (double)(z-other.z)*(z-other.z)); }
    }
    public interface Terrain {
        boolean standable(Cell cell);
        /** Jumping up requires clearance over the departure block too. */
        boolean headroom(Cell cell);
        /** A diagonal step on the same level; corner cells are the two cells the step cuts across. */
        default boolean standableDiagonal(Cell from, Cell to) { return standable(to); }
        /** Free body space for flying through this cell during a jump across a gap (no ground needed). */
        default boolean clear(Cell cell) { return true; }
    }
    private record Entry(Cell cell, double score) { }
    private BuilderPathfinder() { }

    public static List<Cell> find(Cell start, Cell target, Terrain terrain, Predicate<Cell> goal,
                                  int maxVisited, int maxDistance) {
        return find(start, target, terrain, goal, maxVisited, maxDistance, false);
    }

    /** With parkour, routes may also jump straight across a gap of 1 or 2 cells (sprint jump). */
    public static List<Cell> find(Cell start, Cell target, Terrain terrain, Predicate<Cell> goal,
                                  int maxVisited, int maxDistance, boolean parkour) {
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
        Map<Cell, Double> costs = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        Set<Cell> closed = new HashSet<>();
        costs.put(start, 0d);
        open.add(new Entry(start, start.distance(target)));
        int[][] axes = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        while (!open.isEmpty() && closed.size() < maxVisited) {
            Cell current = open.remove().cell;
            if (!closed.add(current)) continue;
            if (goal.test(current)) {
                LinkedList<Cell> route = new LinkedList<>();
                for (Cell cursor = current; !cursor.equals(start); cursor = previous.get(cursor)) route.addFirst(cursor);
                return List.copyOf(route);
            }
            for (int[] axis : axes) {
                boolean diagonal = axis[0] != 0 && axis[1] != 0;
                for (int dy : diagonal ? new int[]{0} : new int[]{0, 1, -1}) {
                    Cell next = current.offset(axis[0], dy, axis[1]);
                    if (Math.abs(next.x-start.x) > maxDistance || Math.abs(next.z-start.z) > maxDistance
                            || Math.abs(next.y-start.y) > 24 || closed.contains(next)
                            || !(diagonal ? terrain.standableDiagonal(current, next) : terrain.standable(next))) continue;
                    if (dy > 0 && !terrain.headroom(current.offset(0, 1, 0))) continue;
                    if (dy < 0 && !terrain.headroom(next.offset(0, 1, 0))) continue;
                    double cost = costs.get(current) + (diagonal ? 1.42 : dy == 0 ? 1 : 1.45);
                    if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, cost);
                    previous.put(next, current);
                    // Reach is a region around target. Subtracting its radius keeps this admissible.
                    open.add(new Entry(next, cost + Math.max(0, next.distance(target) - 4)));
                }
            }
            if (!parkour) continue;
            for (int[] axis : axes) {
                if (axis[0] != 0 && axis[1] != 0) continue; // straight jumps only
                for (int length = 2; length <= 3; length++) {
                    for (int dy : new int[]{0, -1, 1}) {
                        if (dy == 1 && length > 2) continue;
                        Cell next = current.offset(axis[0] * length, dy, axis[1] * length);
                        if (Math.abs(next.x-start.x) > maxDistance || Math.abs(next.z-start.z) > maxDistance
                                || Math.abs(next.y-start.y) > 24 || closed.contains(next) || !terrain.standable(next)) continue;
                        if (!terrain.headroom(current.offset(0, 1, 0))) continue;
                        boolean free = true;
                        for (int k = 1; k < length && free; k++) {
                            Cell mid = current.offset(axis[0] * k, dy > 0 ? 1 : 0, axis[1] * k);
                            free = terrain.clear(mid) && terrain.clear(mid.offset(0, 1, 0));
                        }
                        if (!free) continue;
                        double cost = costs.get(current) + length + 2.5 + (dy != 0 ? .3 : 0); // walking is always preferred
                        if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                        costs.put(next, cost);
                        previous.put(next, current);
                        open.add(new Entry(next, cost + Math.max(0, next.distance(target) - 4)));
                    }
                }
            }
        }
        return List.of();
    }
}
