package com.example.demo;

import com.example.demo.dto.ChatResponse;
import com.google.genai.errors.ApiException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ChatApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatApiExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ChatResponse> handleAccessDenied(AccessDeniedException exception) {
        logger.warn("Chat request denied: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(new ChatResponse(
                        ChatResponse.Status.RBAC_DENIED, "此帳號沒有聊天 API 權限。"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ChatResponse> handleInvalidRequestBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(new ChatResponse(ChatResponse.Status.VALIDATION_ERROR, "請提供有效的聊天請求內容。"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ChatResponse> handleResponseStatus(ResponseStatusException exception) {
        boolean accessDenied = exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value();
        boolean invalidRequest = exception.getStatusCode().value() == HttpStatus.BAD_REQUEST.value();
        if (accessDenied) {
            logger.warn("Chat request denied: {}", exception.getReason());
        }
        ChatResponse.Status responseStatus = accessDenied
                ? ChatResponse.Status.RBAC_DENIED
                : invalidRequest ? ChatResponse.Status.VALIDATION_ERROR : ChatResponse.Status.ERROR;
        String message = accessDenied
                ? "您沒有使用此聊天服務的權限。"
                : invalidRequest
                ? "請提供有效的聊天請求內容。"
                : "聊天請求處理失敗，請查看應用程式日誌。";
        return ResponseEntity.status(exception.getStatusCode())
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(new ChatResponse(responseStatus, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatResponse> handleChatException(Exception exception) {
        Throwable cause = findRootCause(exception);
        HttpStatus status = resolveStatus(exception);
        String message = resolveMessage(status);

        logger.error("Chat request failed (rootCause={}): {}",
                cause.getClass().getName(), cause.getMessage(), exception);

        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(new ChatResponse(ChatResponse.Status.ERROR, message));
    }

    private static HttpStatus resolveStatus(Throwable exception) {
        boolean transientFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException responseException) {
                if (responseException.getStatusCode().value() == 429
                        || responseException.getStatusCode().is5xxServerError()) {
                    return HttpStatus.SERVICE_UNAVAILABLE;
                }
                return HttpStatus.BAD_GATEWAY;
            }
            if (cause instanceof ApiException apiException) {
                if (apiException.code() == 429 || apiException.code() >= 500) {
                    return HttpStatus.SERVICE_UNAVAILABLE;
                }
                return HttpStatus.BAD_GATEWAY;
            }
            if (cause instanceof StatusRuntimeException grpcException) {
                Status.Code code = grpcException.getStatus().getCode();
                if (code == Status.Code.UNAVAILABLE
                        || code == Status.Code.RESOURCE_EXHAUSTED
                        || code == Status.Code.DEADLINE_EXCEEDED) {
                    return HttpStatus.SERVICE_UNAVAILABLE;
                }
                return HttpStatus.BAD_GATEWAY;
            }
            if (cause instanceof TransientAiException || cause instanceof ResourceAccessException) {
                transientFailure = true;
            }
            if (cause instanceof NonTransientAiException) {
                transientFailure = false;
            }
        }
        if (transientFailure) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof NonTransientAiException) {
                return HttpStatus.BAD_GATEWAY;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String resolveMessage(HttpStatus status) {
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return "AI 或向量資料服務目前暫時無法使用或受到限流，請稍後再試；若持續發生，請查看應用程式日誌。";
        }
        if (status == HttpStatus.BAD_GATEWAY) {
            return "AI 或向量資料服務回覆錯誤，請查看應用程式日誌。";
        }
        return "聊天請求處理失敗，請查看應用程式日誌。";
    }

    private static Throwable findRootCause(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
