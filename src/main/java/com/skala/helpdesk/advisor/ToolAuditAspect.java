package com.skala.helpdesk.advisor;

import java.util.Arrays;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import com.skala.helpdesk.web.TraceIdFilter;

/** Tool 실행 결과를 질문·인자·응답 원문 없이 감사한다. */
@Aspect
@Component
public class ToolAuditAspect {

    private static final Logger audit = LoggerFactory.getLogger("AI_TOOL_AUDIT");

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object auditTool(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        String tool = joinPoint.getSignature().getName();
        String user = maskedStudentId(joinPoint.getArgs());
        String traceId = traceId();
        try {
            Object result = joinPoint.proceed();
            audit.info("tool call ok traceId={} tool={} user={} elapsedMs={}",
                    traceId, tool, user, elapsedMillis(start));
            return result;
        }
        catch (Throwable e) {
            audit.warn("tool call failed traceId={} tool={} user={} elapsedMs={} errorType={}",
                    traceId, tool, user, elapsedMillis(start), e.getClass().getSimpleName());
            throw e;
        }
    }

    private String maskedStudentId(Object[] args) {
        return Arrays.stream(args)
                .filter(ToolContext.class::isInstance)
                .map(ToolContext.class::cast)
                .map(context -> context.getContext().get("studentId"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(studentId -> !studentId.isBlank())
                .map(this::mask)
                .findFirst()
                .orElse("unknown");
    }

    private String mask(String studentId) {
        int visible = Math.min(3, studentId.length());
        return studentId.substring(0, visible) + "*".repeat(studentId.length() - visible);
    }

    private String traceId() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId == null || traceId.isBlank() ? "no-trace" : traceId;
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
