package com.skala.helpdesk.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 7 (교안 p.317 소유자 검증 이후 단계).
 *
 * <p>학번을 클라이언트 입력으로 신뢰하지 않고 인증 사용자 이름에서만 얻는다. 현재 계정
 * 저장소는 종합실습을 로컬에서 재현하기 위한 인메모리 구현이다. 운영 환경에서는 같은
 * {@link UserDetailsService} 경계를 학교 인증 시스템으로 교체한다.
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
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/chat/**").hasRole("STUDENT")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 로컬 실습 계정. 기본 비밀번호는 샘플 요청 재현용이며 환경변수로 반드시 교체할 수 있다.
     */
    @Bean
    public UserDetailsService userDetailsService(
            PasswordEncoder encoder,
            @Value("${HELPDESK_STUDENT_PASSWORD:student}") String studentPassword,
            @Value("${HELPDESK_ADMIN_PASSWORD:admin}") String adminPassword) {
        var student2021001 = User.withUsername("2021001")
                .password(encoder.encode(studentPassword))
                .roles("STUDENT")
                .build();
        var student2021002 = User.withUsername("2021002")
                .password(encoder.encode(studentPassword))
                .roles("STUDENT")
                .build();
        var admin = User.withUsername("admin")
                .password(encoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(student2021001, student2021002, admin);
    }
}
