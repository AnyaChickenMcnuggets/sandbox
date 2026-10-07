package com.rpatest.auth.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.service.JwtService;
import com.rpatest.auth.service.RolePermissionService;
import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.web.GlobalExceptionHandler;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// addFilters=false: тестирует HTTP-маппинг/сериализацию; что доступ только у ROLE_MANAGE — в
// SecurityConfigAuthorizationTest
@WebMvcTest(controllers = RolePermissionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class RolePermissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RolePermissionService rolePermissionService;

    // JwtAuthenticationFilter (Filter-бин) попадает в @WebMvcTest slice даже при addFilters=false
    @MockBean
    private JwtService jwtService;

    @MockBean
    private AuthCookies authCookies;

    @Test
    void permissionsListsEveryPermissionWithDescription() throws Exception {
        mockMvc.perform(get("/api/v1/admin/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Permission.values().length))
                .andExpect(jsonPath("$[0].code").value(Permission.values()[0].name()))
                .andExpect(jsonPath("$[0].description").isNotEmpty());
    }

    @Test
    void rolesListsMatrixAndMarksAdminNotEditable() throws Exception {
        when(rolePermissionService.permissionsOf(Role.ADMIN)).thenReturn(EnumSet.allOf(Permission.class));
        when(rolePermissionService.permissionsOf(Role.OPERATOR)).thenReturn(EnumSet.of(Permission.RUN_START));
        when(rolePermissionService.permissionsOf(Role.VIEWER)).thenReturn(EnumSet.noneOf(Permission.class));

        mockMvc.perform(get("/api/v1/admin/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.role=='ADMIN')].editable").value(false))
                .andExpect(jsonPath("$[?(@.role=='OPERATOR')].editable").value(true))
                .andExpect(jsonPath("$[?(@.role=='OPERATOR')].permissions[0]").value("RUN_START"))
                .andExpect(jsonPath("$[?(@.role=='VIEWER')].permissions.length()").value(0));
    }

    @Test
    void setPermissionsReplacesSetAndReturnsIt() throws Exception {
        Set<Permission> requested = EnumSet.of(Permission.SCENARIO_READ, Permission.RUN_READ);
        when(rolePermissionService.replacePermissions(Role.VIEWER, requested)).thenReturn(requested);

        mockMvc.perform(put("/api/v1/admin/roles/VIEWER/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetRolePermissionsRequest(requested))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.permissions.length()").value(2));

        verify(rolePermissionService).replacePermissions(Role.VIEWER, requested);
    }

    @Test
    void setPermissionsForAdminReturnsBadRequest() throws Exception {
        when(rolePermissionService.replacePermissions(Role.ADMIN, EnumSet.noneOf(Permission.class)))
                .thenThrow(new InvalidRequestException("Права роли ADMIN не редактируются"));

        mockMvc.perform(put("/api/v1/admin/roles/ADMIN/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void setPermissionsRejectsUnknownPermissionCode() throws Exception {
        mockMvc.perform(put("/api/v1/admin/roles/VIEWER/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"MAKE_COFFEE\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPermissionsRejectsMissingPermissionsField() throws Exception {
        mockMvc.perform(put("/api/v1/admin/roles/VIEWER/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPermissionsRejectsUnknownRole() throws Exception {
        mockMvc.perform(put("/api/v1/admin/roles/SUPERUSER/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
