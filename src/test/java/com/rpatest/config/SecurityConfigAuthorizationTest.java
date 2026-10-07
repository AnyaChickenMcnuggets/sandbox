package com.rpatest.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import com.rpatest.common.web.PageResponse;
import com.rpatest.auth.service.AppUserService;
import com.rpatest.auth.service.AuthService;
import com.rpatest.auth.service.JwtService;
import com.rpatest.auth.service.PermissionAuthorization;
import com.rpatest.auth.service.RolePermissionService;
import com.rpatest.auth.web.AdminUserController;
import com.rpatest.auth.web.AuthController;
import com.rpatest.auth.web.AuthCookies;
import com.rpatest.auth.web.RolePermissionController;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.service.CleanupService;
import com.rpatest.execution.service.ExecutionService;
import com.rpatest.execution.service.QueueAuditService;
import com.rpatest.execution.web.CleanupController;
import com.rpatest.execution.web.OrchestratorController;
import com.rpatest.execution.web.RobotAvailabilityResponse;
import com.rpatest.execution.web.RunController;
import com.rpatest.execution.web.RunResponse;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.service.ScenarioService;
import com.rpatest.scenario.web.ScenarioController;
import com.rpatest.scenario.web.ScenarioRequest;
import com.rpatest.scenario.web.ScenarioResponse;
import com.rpatest.scenario.web.StepRequest;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Единственное место, проверяющее привязку эндпоинтов к правам и то, что права берутся из
 * матрицы ролей (см. {@code SecurityConfig}, ADR 0005) — не HTTP-маппинг конкретных контроллеров
 * (это покрыто их *ControllerTest с {@code addFilters=false}). Реальный {@code SecurityConfig}
 * импортирован намеренно, фильтры НЕ отключены; {@code RolePermissionService} подменён моком с
 * дефолтной матрицей (та же, что засеяна миграцией V12), отдельные тесты меняют её на лету.
 */
@WebMvcTest(controllers = {RunController.class, ScenarioController.class, AdminUserController.class,
        AuthController.class, RolePermissionController.class, CleanupController.class, OrchestratorController.class})
@Import({SecurityConfig.class, PermissionAuthorization.class})
class SecurityConfigAuthorizationTest {

    private static final Set<Permission> VIEWER_DEFAULT =
            EnumSet.of(Permission.SCENARIO_READ, Permission.RUN_READ, Permission.ORCHESTRATOR_READ);
    private static final Set<Permission> OPERATOR_DEFAULT = EnumSet.of(Permission.SCENARIO_READ, Permission.RUN_READ,
            Permission.ORCHESTRATOR_READ, Permission.SCENARIO_WRITE, Permission.RUN_START, Permission.RUN_STOP,
            Permission.CLEANUP);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private QueueAuditService queueAuditService;

    @MockBean
    private CleanupService cleanupService;

    @MockBean
    private ScenarioService scenarioService;

    @MockBean
    private AppUserService appUserService;

    @MockBean
    private AuthService authService;

    @MockBean
    private RolePermissionService rolePermissionService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private AuthCookies authCookies;

    @BeforeEach
    void defaultMatrix() {
        when(rolePermissionService.permissionsOf(Role.ADMIN)).thenReturn(EnumSet.allOf(Permission.class));
        when(rolePermissionService.permissionsOf(Role.OPERATOR)).thenReturn(OPERATOR_DEFAULT);
        when(rolePermissionService.permissionsOf(Role.VIEWER)).thenReturn(VIEWER_DEFAULT);
        when(rolePermissionService.has(any(), any())).thenAnswer(inv ->
                rolePermissionService.permissionsOf(inv.getArgument(0)).contains((Permission) inv.getArgument(1)));
    }

    @Test
    void unauthenticatedRequestIsRejectedWithUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/scenarios")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanReadScenarios() throws Exception {
        when(scenarioService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/scenarios")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotCreateScenario() throws Exception {
        mockMvc.perform(post("/api/v1/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validScenarioRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCanCreateAndUpdateScenario() throws Exception {
        when(scenarioService.create(any())).thenReturn(scenarioResponse());
        when(scenarioService.update(anyLong(), any())).thenReturn(scenarioResponse());

        mockMvc.perform(post("/api/v1/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validScenarioRequest())))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/scenarios/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validScenarioRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotDeleteScenario() throws Exception {
        mockMvc.perform(delete("/api/v1/scenarios/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteScenario() throws Exception {
        mockMvc.perform(delete("/api/v1/scenarios/1")).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotStartRunStopRunOrCleanup() throws Exception {
        mockMvc.perform(post("/api/v1/scenarios/5/run")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/runs/1/stop")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/scenarios/5/cleanup")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCanStartRunStopRunAndCleanup() throws Exception {
        RunResponse run = new RunResponse(1L, 5L, "s", "alice", RunStatus.PENDING, null, null, null, List.of());
        when(executionService.startRun(eq(5L), any(), any())).thenReturn(run);
        when(executionService.stopRun(1L)).thenReturn(run);
        when(cleanupService.cleanupLastRun(5L)).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/scenarios/5/run")).andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/runs/1/stop")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/scenarios/5/cleanup")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanReadRunsAndOrchestratorAvailability() throws Exception {
        when(executionService.getRun(1L)).thenReturn(
                new RunResponse(1L, 5L, "s", "alice", RunStatus.PENDING, null, null, null, List.of()));
        when(executionService.getRobotAvailability()).thenReturn(new RobotAvailabilityResponse(2, 3, 2, true));

        mockMvc.perform(get("/api/v1/runs/1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orchestrator/robots-availability")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void runsListIsGuardedByRunReadPermission() throws Exception {
        when(executionService.listRuns(null, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isOk());

        when(rolePermissionService.permissionsOf(Role.VIEWER)).thenReturn(Set.of(Permission.SCENARIO_READ));
        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isForbidden());
    }

    @Test
    void runsListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/roles")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/permissions")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotAccessAdminEndpointsByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/roles")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessUserAndRoleManagement() throws Exception {
        when(appUserService.list()).thenReturn(List.of(new AppUser("alice", "hash", Role.VIEWER)));

        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/roles")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/permissions")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void permissionRevokedFromRoleTakesEffectImmediately() throws Exception {
        // админ убрал RUN_START у OPERATOR — тот же запрос, что проходил, теперь 403
        when(rolePermissionService.permissionsOf(Role.OPERATOR))
                .thenReturn(EnumSet.of(Permission.SCENARIO_READ, Permission.RUN_READ));

        mockMvc.perform(post("/api/v1/scenarios/5/run")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void permissionGrantedToRoleTakesEffectImmediately() throws Exception {
        // админ выдал VIEWER право RUN_START — до этого 403, теперь запуск проходит
        EnumSet<Permission> widened = EnumSet.copyOf(VIEWER_DEFAULT);
        widened.add(Permission.RUN_START);
        when(rolePermissionService.permissionsOf(Role.VIEWER)).thenReturn(widened);
        when(executionService.startRun(eq(5L), any(), any())).thenReturn(
                new RunResponse(1L, 5L, "s", "alice", RunStatus.PENDING, null, null, null, List.of()));

        mockMvc.perform(post("/api/v1/scenarios/5/run")).andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorGrantedUserManageCanReachUserAdmin() throws Exception {
        EnumSet<Permission> widened = EnumSet.copyOf(OPERATOR_DEFAULT);
        widened.add(Permission.USER_MANAGE);
        when(rolePermissionService.permissionsOf(Role.OPERATOR)).thenReturn(widened);
        when(appUserService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isOk());
        // USER_MANAGE не открывает управление матрицей — это отдельное право ROLE_MANAGE
        mockMvc.perform(get("/api/v1/admin/roles")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void requestWithoutMappedPermissionIsDeniedEvenForAdmin() throws Exception {
        // новый эндпоинт/метод без явной привязки к праву закрыт по умолчанию, а не "просто
        // аутентифицирован"
        mockMvc.perform(patch("/api/v1/scenarios/1")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/something-new")).andExpect(status().isForbidden());
    }

    @Test
    void meAndChangePasswordRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"long-enough-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void meIsReachableByAnyAuthenticatedRoleEvenWithNoPermissions() throws Exception {
        // "кто я" — не операция уровня прав: даже роль без единого права должна получить 200, иначе
        // UI не узнает, что у него нет прав
        when(rolePermissionService.permissionsOf(Role.VIEWER)).thenReturn(EnumSet.noneOf(Permission.class));

        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void anyAuthenticatedRoleCanChangeOwnPassword() throws Exception {
        when(authService.changePassword(any(), any(), any()))
                .thenReturn(new AuthService.TokenPair("access", "refresh", 900));
        when(authCookies.accessTokenCookie(any())).thenReturn(
                org.springframework.http.ResponseCookie.from("access_token", "access").build());
        when(authCookies.refreshTokenCookie(any())).thenReturn(
                org.springframework.http.ResponseCookie.from("refresh_token", "refresh").build());

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"long-enough-1\"}"))
                .andExpect(status().isOk());
    }

    private ScenarioResponse scenarioResponse() {
        return new ScenarioResponse(1L, "s1", null, OffsetDateTime.now(), OffsetDateTime.now(), List.of());
    }

    private ScenarioRequest validScenarioRequest() {
        return new ScenarioRequest("s1", "desc", List.of(
                new StepRequest("job1", ScenarioStepType.JOB, "Job 1", Map.of("rpaProjectId", 1), List.of())));
    }
}
