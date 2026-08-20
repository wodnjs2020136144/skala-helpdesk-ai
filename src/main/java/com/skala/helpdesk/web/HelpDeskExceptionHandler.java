package com.skala.helpdesk.web;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 완성 상태로 제공된다 — 손대지 않는다.
 *
 * <p>AI 호출 실패·도구 예외가 전체 API 응답 실패로 번지되, 스택트레이스는 절대 노출하지
 * 않는다(CLAUDE.md). 로그에는 traceId와 함께 상세를 남기고, 응답에는 안전한 문구 +
 * traceId만 준다.
 */
@RestControllerAdvice
public class HelpDeskExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(HelpDeskExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(IllegalArgumentException e) {
        String traceId = UUID.randomUUID().toString();
        log.warn("[{}] {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(e.getMessage(), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String traceId = UUID.randomUUID().toString();
        log.error("[{}] 처리 중 오류", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", traceId));
    }

    public record ErrorResponse(String message, String traceId) {
    }
}
