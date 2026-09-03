package com.rpatest.orchestrator.dto;

import java.util.List;

/** Mirrors LTools.Dto.PageDto`1[T] from orc_swagger.json. */
public record PageDto<T>(Integer totalCount, List<T> items) {
}
