package com.rpatest.execution.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Splits the DAG into parallel branches ("lanes") for the step graph. */
final class DagLanes {

    private DagLanes() {
    }

    /**
     * Greedy chain decomposition: a step continues the lane of a parent that nobody continued yet
     * (the nearest such parent), otherwise it starts a new lane. Lanes are never reused, so a branch
     * keeps one column from fork to end.
     *
     * @param edges    only edges whose both ends are in {@code ids}
     * @param numberOf step number in the table, the tie-break that keeps the result stable
     */
    static Map<Long, Integer> assign(
            List<Long> ids, List<RunReportSnapshot.Edge> edges, Map<Long, Integer> layerOf, Map<Long, Integer> numberOf) {
        Map<Long, List<Long>> parents = new HashMap<>();
        edges.forEach(e -> parents.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from()));
        List<Long> order = new ArrayList<>(ids);
        order.sort(Comparator.<Long, Integer>comparing(layerOf::get).thenComparing(numberOf::get));

        Map<Long, Integer> laneOf = new HashMap<>();
        Map<Integer, Long> tailOfLane = new HashMap<>();
        int lanes = 0;
        for (Long id : order) {
            Long continued = parents.getOrDefault(id, List.of()).stream()
                    .filter(p -> p.equals(tailOfLane.get(laneOf.get(p))))
                    .max(Comparator.<Long, Integer>comparing(layerOf::get).thenComparing(p -> -numberOf.get(p)))
                    .orElse(null);
            int lane = continued != null ? laneOf.get(continued) : lanes++;
            laneOf.put(id, lane);
            tailOfLane.put(lane, id);
        }
        return laneOf;
    }
}
