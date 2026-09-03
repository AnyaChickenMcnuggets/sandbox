package com.rpatest.scenario.service;

import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.scenario.web.StepRequest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Проверяет, что шаги сценария образуют корректный DAG: без циклов, все ссылки валидны. */
@Component
public class DagValidator {

    private enum VisitState {VISITING, DONE}

    public void validate(List<StepRequest> steps) {
        Set<String> localIds = new HashSet<>();
        for (StepRequest step : steps) {
            if (!localIds.add(step.localId())) {
                throw new InvalidRequestException("Дублирующийся localId шага: " + step.localId());
            }
        }

        Map<String, List<String>> adjacency = new HashMap<>();
        for (StepRequest step : steps) {
            for (String next : step.nextLocalIdsOrEmpty()) {
                if (!localIds.contains(next)) {
                    throw new InvalidRequestException(
                            "Шаг '" + step.localId() + "' ссылается на несуществующий localId: " + next);
                }
            }
            adjacency.put(step.localId(), step.nextLocalIdsOrEmpty());
        }

        Map<String, VisitState> state = new HashMap<>();
        for (String localId : localIds) {
            if (!state.containsKey(localId)) {
                detectCycle(localId, adjacency, state);
            }
        }
    }

    private void detectCycle(String node, Map<String, List<String>> adjacency, Map<String, VisitState> state) {
        state.put(node, VisitState.VISITING);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            VisitState nextState = state.get(next);
            if (nextState == VisitState.VISITING) {
                throw new InvalidRequestException("Обнаружен цикл в графе шагов сценария на узле: " + next);
            }
            if (nextState == null) {
                detectCycle(next, adjacency, state);
            }
        }
        state.put(node, VisitState.DONE);
    }
}
