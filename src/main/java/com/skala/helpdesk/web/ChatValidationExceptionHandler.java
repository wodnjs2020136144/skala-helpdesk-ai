package com.skala.helpdesk.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 채팅 요청 DTO의 입력 오류를 HTTP 400으로 변환한다.
 *
 * <p>완성 제공 파일인 {@link HelpDeskExceptionHandler}는 AI·도구 실행 중 발생하는 예기치
 * 않은 오류를 안전한 500 응답으로 감싸는 역할을 유지한다. 입력 검증 실패는 서버 장애가
 * 아니므로 이 구체적인 처리기에서 먼저 받아 필드별 메시지만 반환한다.
 */
@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
                "message", "요청 값을 확인해 주세요.",
                "fieldErrors", fieldErrors));
    }
}
