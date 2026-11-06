package dev.ryanbuu.infrapilot.security;

import dev.ryanbuu.infrapilot.config.InfraPilotProperties;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
  @Bean
  org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(InfraPilotProperties p) {
    String password = p.getAdminPassword();
    if (password.isBlank()) {
      if (!p.getMode().equals("demo"))
        throw new IllegalStateException(
            "Live mode requires INFRAPILOT_ADMIN_PASSWORD (at least 16 characters)");
      password = "infrapilot-demo";
    }
    if (p.getMode().equals("live") && password.length() < 16)
      throw new IllegalStateException("INFRAPILOT_ADMIN_PASSWORD must have at least 16 characters");
    var encoder = new BCryptPasswordEncoder();
    List<UserDetails> users = new ArrayList<>();
    users.add(
        User.withUsername(p.getAdminUsername())
            .password(encoder.encode(password))
            .roles("ADMIN")
            .build());
    if (!p.getViewerPassword().isBlank())
      users.add(
          User.withUsername(p.getViewerUsername())
              .password(encoder.encode(p.getViewerPassword()))
              .roles("VIEWER")
              .build());
    return new InMemoryUserDetailsManager(users);
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/csrf", "/api/meta", "/api/login", "/error")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/audit")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/gateway", "/api/gateway/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/bind", "/api/bind/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/forwarding/backups")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/zones/**",
                        "/api/dhcp/**",
                        "/api/forwarding/**")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        org.springframework.http.HttpMethod.PUT, "/api/zones/**", "/api/dhcp/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .csrf(c -> c.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/login")
                    .successHandler(
                        (q, r, a) -> {
                          r.setContentType("application/json");
                          r.getWriter().write("{\"ok\":true}");
                        })
                    .failureHandler(
                        (q, r, e) -> {
                          r.setStatus(401);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter().write("{\"message\":\"用户名或密码错误\"}");
                        }))
        .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((q, r, a) -> r.setStatus(204)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> {
                          r.setStatus(401);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter().write("{\"message\":\"请先登录\"}");
                        })
                    .accessDeniedHandler(
                        (q, r, x) -> {
                          r.setStatus(403);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter().write("{\"message\":\"无操作权限或会话校验已过期，请刷新后重试\"}");
                        }))
        .headers(h -> h.contentTypeOptions(Customizer.withDefaults()).frameOptions(f -> f.deny()))
        .build();
  }
}
