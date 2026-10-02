package com.rpatest.auth.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.service.AppUserService;
import com.rpatest.auth.service.JwtService;
import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.GlobalExceptionHandler;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// addFilters=false: тестирует HTTP-маппинг/сериализацию, не то что доступ ограничен ADMIN'ом
// (это — SecurityConfigAuthorizationTest, единственное место, проверяющее матрицу доступа)
@WebMvcTest(controllers = AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AppUserService appUserService;

    // JwtAuthenticationFilter (Filter-бин) попадает в @WebMvcTest slice даже при addFilters=false
    @MockBean
    private JwtService jwtService;

    @MockBean
    private AuthCookies authCookies;

    @Test
    void createReturnsCreatedUser() throws Exception {
        AppUser user = user(1L, "alice", Role.OPERATOR);
        when(appUserService.create("alice", "password123", Role.OPERATOR)).thenReturn(user);

        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateUserRequest("alice", "password123", Role.OPERATOR))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void createReturnsConflictForDuplicateUsername() throws Exception {
        when(appUserService.create("alice", "password123", Role.OPERATOR))
                .thenThrow(new ConflictException("Пользователь 'alice' уже существует"));

        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateUserRequest("alice", "password123", Role.OPERATOR))))
                .andExpect(status().isConflict());
    }

    @Test
    void createRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateUserRequest("alice", "short", Role.OPERATOR))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsAllUsers() throws Exception {
        when(appUserService.list()).thenReturn(List.of(user(1L, "alice", Role.VIEWER)));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(appUserService.get(99L)).thenThrow(new NotFoundException("не найден"));

        mockMvc.perform(get("/api/v1/admin/users/99")).andExpect(status().isNotFound());
    }

    @Test
    void changeRoleUpdatesAndReturnsUser() throws Exception {
        when(appUserService.changeRole(1L, Role.ADMIN)).thenReturn(user(1L, "alice", Role.ADMIN));

        mockMvc.perform(put("/api/v1/admin/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeRoleRequest(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void setEnabledUpdatesAndReturnsUser() throws Exception {
        AppUser disabled = user(1L, "alice", Role.VIEWER);
        disabled.setEnabled(false);
        when(appUserService.setEnabled(1L, false)).thenReturn(disabled);

        mockMvc.perform(put("/api/v1/admin/users/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetEnabledRequest(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void resetPasswordReturnsNoContent() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/1/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest("newpassword123"))))
                .andExpect(status().isNoContent());

        verify(appUserService).resetPassword(1L, "newpassword123");
    }

    private AppUser user(Long id, String username, Role role) {
        AppUser user = new AppUser(username, "hash", role);
        setId(user, id);
        return user;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
