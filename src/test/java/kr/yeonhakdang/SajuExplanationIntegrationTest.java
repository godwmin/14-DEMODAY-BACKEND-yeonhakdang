package kr.yeonhakdang;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"LINER_API_KEY=fixture-key","LINER_DAILY_LIMIT=20"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class SajuExplanationIntegrationTest {
    @Autowired AccountService accounts;@Autowired MockMvc mvc;@Autowired Store db;@Autowired LinerClient liner;@Autowired tools.jackson.databind.ObjectMapper json;
    HttpServer server;AtomicInteger calls;AtomicReference<String> prompt,authorization;String response;int status,delay;boolean rejectBareJson;
    @BeforeEach void setup() throws Exception {
        db.update("delete from ai_explanation");db.update("delete from ai_generation_quota");db.update("update ai_generation_budget set budget_day='',used=0 where id=1");
        calls=new AtomicInteger();prompt=new AtomicReference<>();authorization=new AtomicReference<>();status=200;delay=0;rejectBareJson=false;
        String paragraphs=json.writeValueAsString(Map.of("paragraphs",List.of("목의 기운을 중심으로 관심과 생각을 차근차근 펼쳐보는 성향으로 해석할 수 있어요.","추천 학과는 확정된 적성이나 합격 가능성이 아니라 관심 분야를 탐색할 출발점으로 살펴보세요.","작은 프로젝트를 해보며 흥미를 확인해 보세요. 사주 해석은 진로 탐색을 위한 참고 자료예요.")));
        response=json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",paragraphs)))));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();prompt.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {Thread.sleep(delay);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
            String userContent=json.readTree(prompt.get()).path("messages").path(1).path("content").asText();
            boolean rejected=rejectBareJson&&userContent.stripLeading().startsWith("{");
            String payload=rejected?"{\"error\":{\"code\":\"invalid_request\",\"message\":\"The request was rejected as invalid. Check the request and try again.\"}}":response;
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(rejected?400:status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();liner.endpoint(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
    }
    @AfterEach void stop() {server.stop(0);}
    static String input(String version) {return """
        {"engineVersion":"%s","pillars":["무자","을묘","갑인","신미"],"dayMaster":"갑","counts":{"wood":4,"fire":0,"earth":2,"metal":1,"water":1},"top":[{"id":"M1","label":"컴퓨터공학과","score":90},{"id":"M2","label":"수학과","score":85},{"id":"M3","label":"기계공학과","score":80}]}
        """.formatted(version);}
    org.springframework.test.web.servlet.ResultActions send(String body,boolean generate,MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/ai/saju-explanation").queryParam("generate",String.valueOf(generate)).session(session).with(csrf()).contentType("application/json").content(body));
    }
    @Test void explicitRequestGeneratesOnceAndRestoresAcrossIndependentSessions() throws Exception {
        send(input("test"),false,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_GENERATED"));assertThat(calls.get()).isZero();
        send(input("test"),true,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.paragraphs.length()").value(3));
        send(input("test"),false,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(calls.get()).isEqualTo(1);assertThat(authorization.get()).isEqualTo("Bearer fixture-key");
        assertThat(prompt.get()).contains("liner-gpt-router","컴퓨터공학과","max_completion_tokens").doesNotContain("birth","accountId","fixture-key");
        assertThat(db.rows("select * from ai_explanation where state='READY'")).hasSize(1);
    }
    @Test void malformedOrFailedProviderResponsesFallBackAndDoNotLoopCharges() throws Exception {
        response="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not-json\"}}]}";
        send(input("bad-json"),true,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        send(input("bad-json"),true,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        assertThat(calls.get()).isEqualTo(1);
        status=401;send(input("bad-key"),true,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"401,AI_AUTH_FAILED","402,AI_CREDITS_REQUIRED","403,AI_ACCESS_DENIED","400,AI_REQUEST_REJECTED","429,AI_PROVIDER_RATE_LIMITED","503,AI_PROVIDER_UNAVAILABLE"})
    void providerFailuresHaveSafeActionableCodes(int httpStatus,String code) throws Exception {
        status=httpStatus;response="{\"error\":{\"code\":\"fixture_code\",\"message\":\"secret-value-never-expose\"}}";
        String body=send(input("failure"+httpStatus),true,new MockHttpSession()).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UNAVAILABLE")).andExpect(jsonPath("$.failureCode").value(code))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret-value-never-expose","fixture-key");
        send(input("failure"+httpStatus),false,new MockHttpSession()).andExpect(jsonPath("$.failureCode").value(code));
        assertThat(db.one("select failure_code from ai_explanation").get("failure_code")).isEqualTo(code);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void acceptsJsonFencesButStillValidatesParagraphs() throws Exception {
        var root=json.readTree(response);
        String text=root.path("choices").path(0).path("message").path("content").asText();
        response=json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","```json\n"+text+"\n```")))));
        send(input("fenced"),true,new MockHttpSession()).andExpect(jsonPath("$.status").value("READY"));
    }
    @Test void diagnosticReasonRedactsSecretsQuotedValuesAndControlCharacters() {
        String reason=liner.safeReason("Bad request: fixture-key 'private input' \"another secret\" `private`\n"+"a".repeat(60));
        assertThat(reason).contains("Bad request:").doesNotContain("fixture-key","private input","another secret","private`","\n","a".repeat(60));
        assertThat(reason.length()).isLessThanOrEqualTo(240);
    }
    @Test void derivedJsonIsIntroducedAsAnExplanationRequestInsteadOfABareUserMessage() throws Exception {
        // Live Liner rejected a bare JSON user message with 400 invalid_request.
        // The same values with an explicit explanation request were accepted.
        rejectBareJson=true;
        send(input("json-user-message"),true,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(calls.get()).isEqualTo(1);
        String content=json.readTree(prompt.get()).path("messages").path(1).path("content").asText();
        assertThat(content).doesNotStartWith("{").contains("분석 데이터:","컴퓨터공학과");
    }
    @Test void csrfAndInputValidationRunBeforeAnyModelCall() throws Exception {
        mvc.perform(post("/api/ai/saju-explanation").contentType("application/json").content(input("test"))).andExpect(status().isForbidden());
        send(input("test").replace("\"wood\":4","\"wood\":3"),true,new MockHttpSession()).andExpect(status().isBadRequest());
        send(input("test").replace("컴퓨터공학과","<script>"),true,new MockHttpSession()).andExpect(status().isBadRequest());
        assertThat(calls.get()).isZero();
    }
    @Test void hourlyAndDailyBudgetsAreSharedAcrossRequestsAndCachedReadsAreFree() throws Exception {
        UUID member=accounts.login("ai-quota-"+UUID.randomUUID());accounts.consent(member,"2026-10-29-draft",true,true,true,false);
        for(int i=0;i<5;i++) mvc.perform(post("/api/ai/saju-explanation").queryParam("generate","true").with(user(member.toString())).with(csrf()).contentType("application/json").content(input("v"+i)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(post("/api/ai/saju-explanation").queryParam("generate","true").with(user(member.toString())).with(csrf()).contentType("application/json").content(input("v5")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIMITED"));
        assertThat(calls.get()).isEqualTo(5);
        db.update("update ai_generation_budget set budget_day=?,used=20 where id=1",LocalDate.now(ZoneOffset.UTC).toString());
        send(input("daily"),true,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIMITED"));
        send(input("v0"),false,new MockHttpSession()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(calls.get()).isEqualTo(5);
    }
    @Test void guestCookieRetainsTheGenerationLimitAcrossRequests() throws Exception {
        var first=send(input("guest0"),true,new MockHttpSession()).andExpect(status().isOk()).andReturn().getResponse();
        String header=first.getHeader("Set-Cookie");assertThat(header).isNotNull();
        String[] pair=header.split(";",2)[0].split("=",2);
        var cookie=new jakarta.servlet.http.Cookie(pair[0],pair[1]);
        for(int i=1;i<6;i++) mvc.perform(post("/api/ai/saju-explanation").queryParam("generate","true").cookie(cookie).with(csrf()).contentType("application/json").content(input("guest"+i)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(i<5?"READY":"LIMITED"));
        assertThat(calls.get()).isEqualTo(5);
    }
    @Test void simultaneousRequestsReserveOnlyOneGeneration() throws Exception {
        delay=300;
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CyclicBarrier(2);
            Callable<String> task=()->{gate.await();return send(input("concurrent"),true,new MockHttpSession()).andReturn().getResponse().getContentAsString();};
            var a=executor.submit(task);var b=executor.submit(task);
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).anyMatch(v->v.contains("READY")).anyMatch(v->v.contains("PENDING"));
        }
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void missingKeyIsDisabled() {assertThat(new LinerClient(json,"  ","liner-gpt-router").configured()).isFalse();}
}
