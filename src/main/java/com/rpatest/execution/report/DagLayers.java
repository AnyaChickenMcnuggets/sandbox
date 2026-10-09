package com.rpatest.execution.report;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Longest-path depth of every node from the roots: the column a node is drawn in. */
final class DagLayers {

    private DagLayers() {
    }

    /** @param edges only edges whose both ends are in {@code ids} */
    static Map<Long, Integer> compute(Collection<Long> ids, List<RunReportSnapshot.Edge> edges) {
        Map<Long, Integer> layer = new HashMap<>();
        ids.forEach(id -> layer.put(id, 0));
        // Bounded by the node count: a cycle (the scenario validator forbids it) must not loop forever.
        for (int i = 0; i < ids.size(); i++) {
            boolean changed = false;
            for (RunReportSnapshot.Edge edge : edges) {
                int candidate = layer.get(edge.from()) + 1;
                if (candidate > layer.get(edge.to())) {
                    layer.put(edge.to(), candidate);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return layer;
    }
}
