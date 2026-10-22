package com.example.demo;

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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class ChatApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatController.ChatResponse> handleChatException(Exception exception) {
        Throwable cause = findRootCause(exception);
        HttpStatus status = resolveStatus(exception);
        String message = resolveMessage(status);

        logger.error("Chat request failed (rootCause={}): {}",
                cause.getClass().getName(), cause.getMessage(), exception);

        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(new ChatController.ChatResponse(message));
    }

    private static HttpStatus resolveStatus(Throwable exception) {
        boolean transientFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof GitHubMcpException) {
                return HttpStatus.BAD_GATEWAY;
            }
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
            return "AI、向量資料或 GitHub MCP 服務回覆錯誤，請查看應用程式日誌。";
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
