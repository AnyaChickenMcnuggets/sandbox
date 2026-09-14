package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.RobotDto;
import java.util.List;

public interface RobotsPort {

    List<RobotDto> list();
}
