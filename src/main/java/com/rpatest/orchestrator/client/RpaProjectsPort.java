package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.RpaProjectShortDto;
import java.util.List;
import java.util.Optional;

public interface RpaProjectsPort {

    List<RpaProjectShortDto> list();

    /** Ищет проект по точному имени; если совпадений несколько (разные версии) — предпочитает активный. */
    Optional<RpaProjectShortDto> findByName(String name);
}
