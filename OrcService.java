package kamaz.project.sandbox.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import kamaz.project.sandbox.dto.orc.AuthRequest;
import kamaz.project.sandbox.dto.orc.AuthResponse;
import kamaz.project.sandbox.dto.orc.RpaProject;
import kamaz.project.sandbox.dto.orc.RpaRobot;
import kamaz.project.sandbox.dto.orc.RpaRobotResponse;
import kamaz.project.sandbox.dto.orc.RpaTask;
import kamaz.project.sandbox.dto.orc.RpaTaskArgumentsRead;
import kamaz.project.sandbox.dto.orc.RpaTaskArgumentsWrite;
import kamaz.project.sandbox.exception.AppException;
import kamaz.project.sandbox.interfaces.RetryOnUnauthorized;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrcService {

    private final RestTemplate restTemplate;

    @Value("${api.orc.url}")
    private String endpoint;
    private static final String TOKEN_PATH = System.getenv("USERPROFILE") + "\\token.txt";

    @Value("${api.orc.username}")
    private String username;

    @Value("${api.orc.password}")
    private String pswd;
    private String token;

    @PostConstruct
    public void init() {
        this.token = readToken();
        if (isTokenInvalid()) {
            this.token = auth();
            updateToken();
        }
    }
    private HttpHeaders getHeaders(){
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + this.token);
        return headers;
    }

    private String readToken() {
        try {
            Path path = Paths.get(TOKEN_PATH);
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
                Files.writeString(path, "1");
                return "1";
            }
            token = Files.readString(path).trim();

            return token.isEmpty() ? "1" : token;
        } catch (IOException e) {
            log.error("Error reading token file: " + e.getMessage());
            return "1";
        }
    }

    private void updateToken() {
        try {
            Path path = Paths.get(TOKEN_PATH);
            Files.writeString(path, this.token != null ? this.token : "");
        } catch (IOException e) {
            log.error("Error updating token file" + e.getMessage());
        }
    }

    private boolean isTokenInvalid() {
        try {
            String url = endpoint + "api/Assets";
            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);
            restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            return false; // Token is valid
        } catch (HttpClientErrorException e) {
            return e.getStatusCode() == HttpStatus.UNAUTHORIZED;
        } catch (Exception e) {
            log.error("Error checking token validity: " + e.getMessage());
            return true;
        }
    }

    private String auth() {
        try {
            AuthRequest authRequest = new AuthRequest(username, pswd);

            String url = endpoint + "api/Account";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<AuthRequest> entity = new HttpEntity<>(authRequest, headers);
            ResponseEntity<AuthResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, AuthResponse.class);

            if (response.getBody() == null || response.getBody().token() == null) {
                throw new RuntimeException("Authentication failed: No token in response");
            }
            return response.getBody().token();
        } catch (HttpClientErrorException e) {
            log.error("❌ HTTP Error during authentication:");
            log.error("Status code: " + e.getStatusCode());
            log.error("Status text: " + e.getStatusText());
            log.error("Response headers: " + e.getResponseHeaders());
            log.error("Response body: " + e.getResponseBodyAsString());
            log.error("Full error: " + e);

            throw new RuntimeException("Authentication failed with HTTP error: " + e.getStatusCode(), e);

        } catch (Exception e) {
            log.error("❌ Unexpected error during authentication:" + e);
            throw new RuntimeException("Authentication error: " + e.getMessage(), e);
        }

    }

    private void reauthenticate() {
        log.info("Re-authenticating due to unauthorized access...");
        this.token = auth();
        updateToken();
        log.info("Re-authentication successful");
    }

    public <T> T executeWithAuthRetry(OrcOperation<T> operation) {
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                attempt++;
                log.info("Execution attempt for operation " + attempt);
                return operation.execute();

            } catch (HttpClientErrorException.Unauthorized e) {
                log.warn("Unauthorized access on attempt " + attempt);

                if (attempt == maxRetries) {
                    log.error("Max retries reached for unauthorized operation");
                    throw new RuntimeException(
                            "Operation failed after " + maxRetries + " attempts due to authorization issues", e);
                }

                // Повторная аутентификация
                reauthenticate();
                log.info("Retrying operation after re-authentication...");

            } catch (Exception e) {
                log.error("Operation failed with error: " + e.getMessage());
                throw new RuntimeException("Operation failed: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException("Unexpected error in executeWithAuthRetry");
    }

    @FunctionalInterface
    public interface OrcOperation<T> {
        T execute();
    }

    @RetryOnUnauthorized
    public String getAsset(String assetName) {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/Assets/GetByName?name=" + assetName;

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new RuntimeException("Ассет не найден: " + assetName);
            }

            return response.getBody();
        });
    }

    @RetryOnUnauthorized
    public List<RpaProject> getRpaProjects() {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/RpaProjects/v3/short";

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<List<RpaProject>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<List<RpaProject>>() {
                    });

            return response.getBody();
        });
    }

    @RetryOnUnauthorized
    public List<RpaTask> getRpaTasks() {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/Assignments/v2";

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<List<RpaTask>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<List<RpaTask>>() {
                    });

            return response.getBody();

        });
    }

    @RetryOnUnauthorized
    public RpaTask getRpaTaskById(Long id) {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/Assignments/v2/" + id;

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<RpaTask> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<RpaTask>() {
                    });
            
                   
            return response.getBody();

        });
    }

    @RetryOnUnauthorized
    public RpaTask postRpaTask(RpaTask rpaTask) {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/Assignments/v2";
            RpaTask withDesc = new RpaTask(rpaTask.id(), rpaTask.name() + "_Sandbox", rpaTask.rpaProjectId(), rpaTask.rpaProjectName(), "Тестовое задание для автоматического тестирования Sandbox");
            HttpHeaders headers = getHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<RpaTask> entity = new HttpEntity<>(withDesc, headers);

            ResponseEntity<RpaTask> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<RpaTask>() {
                    });

            for (RpaTask task : getRpaTasks()) {
                if (task.name().equals(withDesc.name())) {
                    return task;
                }
            } 
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось найти созданное задание на оркестраторе");

        });
    }

    @RetryOnUnauthorized
    public List<RpaTaskArgumentsRead> getArgumentsByRpaTaskId(Long id) {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/RpaProjectVariables/Assignment/" + id;

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<List<RpaTaskArgumentsRead>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<List<RpaTaskArgumentsRead>>() {
                    });

            return response.getBody();

        });
    }

    @RetryOnUnauthorized
    public boolean deleteRpaTaskById(Long id) {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/Assignments/" + id;

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<Void> response = restTemplate.exchange(
                url, HttpMethod.DELETE, entity, Void.class);
            System.out.println(response);        
            return true;

        });
    }

    @RetryOnUnauthorized
    public List<RpaTaskArgumentsWrite> putArgumentsByRpaTaskId(Long id, List<RpaTaskArgumentsWrite> arguments) {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/RpaProjectVariables/Assignment/" + id;

            HttpHeaders headers = getHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<List<RpaTaskArgumentsWrite>> entity = new HttpEntity<>(arguments, headers);

            ResponseEntity<List<RpaTaskArgumentsWrite>> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, new ParameterizedTypeReference<List<RpaTaskArgumentsWrite>>() {
                    });

            return response.getBody();

        });
    }

    @RetryOnUnauthorized
    public boolean startRpaTask(Long id) {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/Assignments/" + id + "/Start";

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<Void> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, Void.class);

            return response.getStatusCode() == HttpStatus.OK;

        });
    }

    @RetryOnUnauthorized
    public List<RpaRobot> getRpaRobots() {
        return executeWithAuthRetry(() -> {
            String url = endpoint + "api/Robots/v2";

            HttpHeaders headers = getHeaders();

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<RpaRobotResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, RpaRobotResponse.class);

            return response.getBody().result();

        });
    }

}
