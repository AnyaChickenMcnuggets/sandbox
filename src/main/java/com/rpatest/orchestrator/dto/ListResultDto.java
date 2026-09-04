package com.rpatest.orchestrator.dto;

import java.util.List;

/** Mirrors LTools.Orchestrator.WebApi.Bl.Queries.ListResult`1[T] from orc_swagger.json. */
public record ListResultDto<T>(Integer totalCount, Integer filterCount, List<T> result) {

    public static <T> ListResultDto<T> of(Integer totalCount, List<T> result) {
        return new ListResultDto<>(totalCount, totalCount, result);
    }
}
