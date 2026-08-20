package com.skala.helpdesk.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 7 (교안 p.317 소유자 검증 이후 단계).
 *
 * <p>지금은 <b>모든 요청을 허용</b>한다(스캐폴드 단계 — Swagger·curl로 부팅 확인이
 * 먼저다). Phase 7에서 아래를 채운다.
 *
 * <ul>
 *   <li>학생 역할: 자기 학번으로만 {@code /api/chat/**} 사용 가능(인증 주체에서 학번을
 *       꺼내 {@code ChatController}가 요청 파라미터 대신 쓰도록 바꾼다)</li>
 *   <li>학사팀 역할: {@code /api/admin/**}만 허용 — {@code @PreAuthorize("hasRole('ADMIN')")}
 *       를 {@code AdminController}에 붙인다</li>
 *   <li>레드팀 검증 시나리오 ⑤⑥이 이 설정 없이는 "차단"이 아니라 "우연히 안 뚫림"이다
 *       — 반드시 403을 코드로 강제한 뒤 재검증한다</li>
 * </ul>
 *
 * <p>참고: {@code SpringAI_실습/ch10_toolsafe}(Spring Security + {@code @PreAuthorize}),
 * 교안 p.312 SecurityConfig 언급, day3-consult-agent README의 확장 과제 절.
 */
@Configuration
public class SecurityConfig {

    // TODO(B, Phase 7): permitAll을 걷어내고 역할 기반 인가로 바꾼다.
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
