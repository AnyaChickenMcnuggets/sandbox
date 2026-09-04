package com.rpatest.orchestrator.dto;

import java.util.List;

/** Mirrors LTools.Orchestrator.WebApi.Bl.Queries.ListResult`1[T] from orc_swagger.json. */
public record ListResultDto<T>(Integer totalCount, Integer filterCount, List<T> result) {
}
