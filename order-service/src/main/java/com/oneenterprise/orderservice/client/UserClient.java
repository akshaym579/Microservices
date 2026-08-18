package com.oneenterprise.orderservice.client;

import com.oneenterprise.orderservice.exception.UserServiceException;
import com.oneenterprise.orderservice.exception.UserServiceException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;

@Component
public class UserClient {

    private static final Logger log = LoggerFactory.getLogger(UserClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public UserClient(RestClient userRestClient,
                      @Value("${user-service.base-url}") String baseUrl) {
        this.restClient = userRestClient;
        this.baseUrl = baseUrl;
    }

    public UserServiceUser getUser(Long userId) {
        try {
            UserServiceUser user = restClient.get()
                    .uri("/api/users/{id}", userId)
                    .retrieve()
                    .body(UserServiceUser.class);

            if (user == null) {
                log.warn("User Service returned an empty body for user {}", userId);
                throw new UserServiceException(Reason.USER_SERVICE_ERROR,
                        "User Service returned an empty response for user " + userId);
            }
            return user;

        } catch (HttpClientErrorException.NotFound ex) {
            log.info("User Service reports user {} does not exist", userId);
            throw new UserServiceException(Reason.USER_NOT_FOUND,
                    "User " + userId + " does not exist in User Service");

        } catch (HttpStatusCodeException ex) {
            log.warn("User Service returned {} for user {}", ex.getStatusCode(), userId);
            throw new UserServiceException(Reason.USER_SERVICE_ERROR,
                    "User Service returned " + ex.getStatusCode() + " while looking up user " + userId);

        } catch (ResourceAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SocketTimeoutException) {
                log.warn("User Service did not respond in time for user {}", userId);
                throw new UserServiceException(Reason.USER_SERVICE_TIMEOUT,
                        "User Service did not respond in time while looking up user " + userId);
            }
            log.warn("User Service is not reachable at {} ({})", baseUrl, ex.getMostSpecificCause().toString());
            throw new UserServiceException(Reason.USER_SERVICE_UNAVAILABLE,
                    "User Service is not reachable at " + baseUrl);

        } catch (RestClientException ex) {
            log.warn("Unexpected failure looking up user {}", userId, ex);
            throw new UserServiceException(Reason.USER_SERVICE_ERROR,
                    "Call to User Service failed while looking up user " + userId);

        } catch (IllegalStateException ex) {
            log.warn("No USER-SERVICE instance is registered ({})", ex.getMessage());
            throw new UserServiceException(Reason.USER_SERVICE_UNAVAILABLE,
                    "No User Service instance is currently registered with the discovery server");
        }
    }
}
