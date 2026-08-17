package com.oneenterprise.apigateway.error;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        String path = request.path();
        HttpStatus status = resolveStatus(getError(request));

        String code = switch (status) {
            case NOT_FOUND -> "NO_ROUTE";
            case SERVICE_UNAVAILABLE -> "BACKEND_UNAVAILABLE";
            case GATEWAY_TIMEOUT -> "BACKEND_TIMEOUT";
            default -> "GATEWAY_ERROR";
        };

        String message = switch (status) {
            case NOT_FOUND -> "No route is configured for " + path;
            case SERVICE_UNAVAILABLE -> "The service behind " + path + " is not reachable";
            case GATEWAY_TIMEOUT -> "The service behind " + path + " did not respond in time";
            default -> "The gateway could not complete the request for " + path;
        };

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("path", path);
        return body;
    }

    private HttpStatus resolveStatus(Throwable error) {
        if (hasCause(error, ConnectException.class)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (isTimeout(error)) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (error instanceof ResponseStatusException statusException) {
            return HttpStatus.valueOf(statusException.getStatusCode().value());
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }
}
