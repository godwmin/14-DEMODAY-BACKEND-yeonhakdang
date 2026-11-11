package kr.yeonhakdang;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean org.springframework.session.web.http.CookieSerializer cookieSerializer(
        @Value("${server.servlet.session.cookie.secure}") boolean secure,
        @Value("${server.servlet.session.cookie.same-site}") String sameSite) {
        var cookie=new org.springframework.session.web.http.DefaultCookieSerializer();
        cookie.setCookieName("YHDSESSION");cookie.setUseHttpOnlyCookie(true);cookie.setUseSecureCookie(secure);
        cookie.setSameSite(sameSite);cookie.setCookiePath("/");return cookie;
    }
    @Bean SecurityFilterChain security(HttpSecurity http,AccountService accounts,Store db,
            @Value("${app.oauth-enabled}") boolean oauth,@Value("${app.frontend-origin}") String frontend,
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) throws Exception {
        var cors=new CorsConfiguration();cors.setAllowedOrigins(allowedOrigins);cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type","X-CSRF-TOKEN","Idempotency-Key"));
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",cors);
        http.cors(c->c.configurationSource(source))
            .csrf(c->c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).ignoringRequestMatchers("/api/webhooks/portone","/api/webhooks/payapp","/api/payments/payapp/return"))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/api/csrf","/api/products","/api/policies","/api/webhooks/portone","/api/webhooks/payapp","/api/payments/payapp/return","/oauth2/**","/login/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->json(res,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((req,res,ex)->json(res,403,"FORBIDDEN")))
            .logout(l->l.logoutUrl("/api/logout").invalidateHttpSession(true).deleteCookies("YHDSESSION")
                .logoutSuccessHandler((req,res,auth)->res.setStatus(204)))
            .headers(h->h.cacheControl(c->{}));
        if(oauth) http.oauth2Login(o->o.userInfoEndpoint(u->u.userService(request->{
            var upstream=new DefaultOAuth2UserService().loadUser(request);
            UUID id=accounts.login(upstream.getName());
            String role=(String)db.one("select role from member_account where id=?",id).get("role");
            return new DefaultOAuth2User(Set.of(new SimpleGrantedAuthority("ROLE_"+role)),Map.of("id",id.toString()),"id");
        })).successHandler((req,res,auth)->res.sendRedirect(frontend+"/#9-1"))
            .failureHandler((req,res,ex)->res.sendRedirect(frontend+"/#2-4")));
        // Re-check membership and role on every request, including old sessions on other devices.
        http.addFilterBefore(new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                var a=SecurityContextHolder.getContext().getAuthentication();
                if(a!=null&&a.isAuthenticated()&&!"anonymousUser".equals(a.getName())) {
                    var m=db.rows("select status,role from member_account where id=?",UUID.fromString(a.getName()));
                    if(m.isEmpty()||!"ACTIVE".equals(m.getFirst().get("status"))) { SecurityContextHolder.clearContext();json(res,401,"ACCOUNT_INACTIVE");return; }
                    if(req.getRequestURI().startsWith("/api/admin/")&&!"ADMIN".equals(m.getFirst().get("role"))) {json(res,403,"FORBIDDEN");return;}
                }
                res.setHeader("Cache-Control","no-store");chain.doFilter(req,res);
            }
        },AuthorizationFilter.class);
        return http.build();
    }
    static void json(HttpServletResponse res,int status,String code) throws IOException {res.setStatus(status);res.setContentType("application/json");res.getWriter().write("{\"code\":\""+code+"\"}");}
}
