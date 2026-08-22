package com.skala.helpdesk.tools;

/**
 * 담당: A. {@code helpdesk.tool.max-calls}(요청 단위 도구 호출 상한)를 초과했을 때 던진다.
 *
 * <p>모델 장애가 아니라 우리 안전장치가 의도적으로 발동한 것이므로,
 * {@code HelpDeskService.ask()}는 이 예외를 폴백 재시도 대상에서 제외한다 — 그렇지 않으면
 * 폴백이 새 요청 단위 카운터로 도구를 처음부터 다시 호출해 상한을 두 배로 만든다.
 * {@code HelpDeskExceptionHandler}(완성 제공)가 안전한 500 응답으로 변환한다.
 */
public class ToolCallLimitExceededException extends RuntimeException {

    public ToolCallLimitExceededException(String message) {
        super(message);
    }
}
