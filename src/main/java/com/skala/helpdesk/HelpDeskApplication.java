package com.skala.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SKALA University 학사 안내 HelpDesk AI — 종합 실습(교안 p.307–322).
 *
 * <p>2인 분담 기준: {@code docs/분업-역할표.md} 참조.
 * A(AI·RAG·플랫폼) / B(업무 API·Tool·보안), 담당자는 첫 번째 책임자이고 상대는
 * 두 번째 담당자로서 리뷰·실행 검증에 반드시 참여한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HelpDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelpDeskApplication.class, args);
    }
}
