package kr.yeonhakdang;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ServiceIntegrationTest {
    @Autowired Store db;
    @Autowired AccountService accounts;
    @Autowired OrderService orders;
    @Autowired ContentService content;
    @Autowired RefundService refunds;
    @Autowired WebhookService webhooks;
    @Autowired MockMvc mvc;
    @MockitoBean PaymentGateway gateway;
    UUID user,other,admin,result;
    @BeforeEach void setup() {
        reset(gateway);
        for(String t:List.of("SPRING_SESSION","webhook_receipt","refund_request","entitlement","purchase_order","analysis_result","consent","member_account")) db.update("delete from "+t);
        user=accounts.login("kakao-1");other=accounts.login("kakao-2");admin=accounts.login("kakao-admin");
        db.update("update member_account set role='ADMIN' where id=?",admin);
        accounts.consent(user,"2026-10-29-draft",true,true,true,false);
        result=(UUID)content.save(user,new ApiController.AnalysisInput("frontend-435e2f6",Map.of("wood",2,"fire",2,"earth",2,"metal",1,"water",1),List.of(new ApiController.Major("engineering",85)))).get("id");
    }
    UUID create(String product) {
        return (UUID)orders.create(user,"request_"+UUID.randomUUID(),new ApiController.OrderInput(result,product,List.of("연세대학교"),"선배","2026-10-29-draft",true,true)).get("id");
    }
    PaymentGateway.Payment paid(UUID id) {return new PaymentGateway.Payment("yhd-"+id,"PAID",Store.number(db.one("select amount from purchase_order where id=?",id),"amount"),0,"KRW","store-test",id.toString(),Instant.now(),List.of());}
    void pay(UUID id) {when(gateway.get("yhd-"+id)).thenReturn(paid(id));orders.complete(user,id);}
    void publish(UUID id,String product) {content.publish(admin,id,product,"reviewed-v1","{\"title\":\"approved test fixture\"}");}
    @Test void serverPriceAndIdempotency() {
        var input=new ApiController.OrderInput(result,"package",List.of("연세대학교"),"선배","2026-10-29-draft",true,true);
        var a=orders.create(user,"same_request",input);var b=orders.create(user,"same_request",input);
        assertThat(a.get("id")).isEqualTo(b.get("id"));assertThat(Store.number(a,"amount")).isEqualTo(3790);
        assertThatThrownBy(()->orders.create(user,"same_request",new ApiController.OrderInput(result,"charm",List.of(),"","2026-10-29-draft",true,true))).isInstanceOf(ApiException.class);
    }
    @Test void forgedAmountCurrencyStoreOrOrderNeverGrants() {
        for(int fault=0;fault<4;fault++) {
            UUID id=create("charm");var p=paid(id);
            when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"PAID",fault==0?1:1000,0,fault==1?"USD":"KRW",fault==2?"attacker-store":"store-test",fault==3?UUID.randomUUID().toString():id.toString(),Instant.now(),List.of()));
            assertThat(orders.complete(user,id).get("status")).isEqualTo("REVIEW_REQUIRED");
            assertThat(db.rows("select * from entitlement where order_id=?",id)).isEmpty();
        }
    }
    @Test void externalFailureDoesNotCommitPaidOrGrant() {
        UUID id=create("charm");when(gateway.get("yhd-"+id)).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(()->orders.complete(user,id)).isInstanceOf(RuntimeException.class);
        assertThat(orders.get(user,id).get("status")).isEqualTo("CREATED");assertThat(content.locker(user)).isEmpty();
    }
    @Test void failedThenDelayedPaidAndDuplicateCompletion() {
        UUID id=create("package");var p=paid(id);
        when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"FAILED",p.total(),0,p.currency(),p.storeId(),p.orderId(),null,List.of()));
        assertThat(orders.complete(user,id).get("status")).isEqualTo("FAILED");
        pay(id);orders.complete(user,id);assertThat(content.locker(user)).hasSize(3);
    }
    String signature(String id,String ts,String body) throws Exception {
        var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "v1,"+Base64.getEncoder().encodeToString(m.doFinal((id+"."+ts+"."+body).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void signedDuplicateWebhookBeforeBrowserCompletion() throws Exception {
        UUID id=create("package");when(gateway.get("yhd-"+id)).thenReturn(paid(id));
        String body="{\"type\":\"Transaction.Paid\",\"data\":{\"storeId\":\"store-test\",\"paymentId\":\"yhd-"+id+"\"}}";
        String ts=""+Instant.now().getEpochSecond();String sig=signature("event-1",ts,body);
        webhooks.receive("event-1",ts,sig,body);webhooks.receive("event-1",ts,sig,body);orders.complete(user,id);
        assertThat(content.locker(user)).hasSize(3);assertThat(db.rows("select * from webhook_receipt")).hasSize(1);
    }
    @Test void browserCompletionBeforeWebhookIsAlsoIdempotent() throws Exception {
        UUID id=create("package");pay(id);
        String body="{\"type\":\"Transaction.Paid\",\"data\":{\"storeId\":\"store-test\",\"paymentId\":\"yhd-"+id+"\"}}";
        String ts=""+Instant.now().getEpochSecond();webhooks.receive("event-after",ts,signature("event-after",ts,body),body);
        assertThat(content.locker(user)).hasSize(3);
    }
    @Test void invalidStaleAndTamperedWebhooksRejected() throws Exception {
        String ts=""+Instant.now().getEpochSecond();
        assertThatThrownBy(()->webhooks.verify("e",ts,"v1,invalid","{}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->webhooks.verify("e",ts,signature("e",ts,"{}"),"{\"tampered\":true}")).isInstanceOf(ApiException.class);
        String old=""+(Instant.now().getEpochSecond()-301);
        assertThatThrownBy(()->webhooks.verify("e",old,signature("e",old,"{}"),"{}")).isInstanceOf(ApiException.class);
    }
    @Test void webhookApiFailureDoesNotConsumeReceipt() throws Exception {
        UUID id=create("charm");String body="{\"type\":\"Transaction.Paid\",\"data\":{\"storeId\":\"store-test\",\"paymentId\":\"yhd-"+id+"\"}}";
        String ts=""+Instant.now().getEpochSecond(),sig=signature("retry",ts,body);
        when(gateway.get("yhd-"+id)).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(()->webhooks.receive("retry",ts,sig,body)).isInstanceOf(RuntimeException.class);
        assertThat(db.rows("select * from webhook_receipt")).isEmpty();
        doReturn(paid(id)).when(gateway).get("yhd-"+id);webhooks.receive("retry",ts,sig,body);assertThat(content.locker(user)).hasSize(1);
    }
    @Test void ownerOnlyAndPendingContentNeverMarksViewed() {
        UUID id=create("package");pay(id);
        assertThatThrownBy(()->content.open(other,id,"compat")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->content.open(user,id,"compat")).isInstanceOf(ApiException.class).hasMessage("CONTENT_PENDING");
        assertThat(content.locker(user)).allSatisfy(e->assertThat(e.get("first_viewed_at")).isNull());
        publish(id,"compat");content.open(user,id,"compat");Object first=content.open(user,id,"compat").get("firstViewedAt");
        assertThat(content.open(user,id,"compat").get("firstViewedAt")).isEqualTo(first);
        assertThat(content.locker(user).stream().filter(e->e.get("first_viewed_at")!=null)).hasSize(1);
    }
    @Test void packageRefundReservesOnlyUnopenedAndDeduplicates() {
        UUID id=create("package");pay(id);publish(id,"compat");content.open(user,id,"compat");
        var r=refunds.request(user,id,"CHANGE_OF_MIND");assertThat(Store.number(r,"amount")).isEqualTo(1891);
        assertThat(refunds.request(user,id,"CHANGE_OF_MIND").get("id")).isEqualTo(r.get("id"));
        assertThat(content.locker(user).stream().filter(e->"REFUND_PENDING".equals(e.get("status")))).hasSize(2);
        assertThat(content.open(user,id,"compat")).containsKey("content");
        refunds.reject(admin,(UUID)r.get("id"));
        assertThat(content.locker(user)).allSatisfy(e->assertThat(e.get("status")).isEqualTo("ACTIVE"));
    }
    @Test void normalExpiredButExceptionStillRequiresAdminReview() {
        UUID id=create("charm");pay(id);db.update("update purchase_order set paid_at=? where id=?",Instant.now().minus(Duration.ofDays(8)),id);
        assertThatThrownBy(()->refunds.request(user,id,"CHANGE_OF_MIND")).hasMessage("REFUND_WINDOW_EXPIRED");
        assertThat(refunds.request(user,id,"NOT_PROVIDED").get("status")).isEqualTo("REQUESTED");
        verify(gateway,never()).cancel(anyString(),anyInt(),anyInt(),anyString());
    }
    @Test void confirmedPartialCancellationRevokesOnlyReservedProducts() {
        UUID id=create("package");pay(id);publish(id,"compat");content.open(user,id,"compat");
        UUID r=(UUID)refunds.request(user,id,"CHANGE_OF_MIND").get("id");var p=paid(id);
        when(gateway.cancel("yhd-"+id,1891,3790,"CHANGE_OF_MIND")).thenAnswer(inv->{when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"PARTIAL_CANCELLED",3790,1891,"KRW","store-test",id.toString(),p.paidAt(),List.of(new PaymentGateway.Cancellation("cancel-1","SUCCEEDED",1891))));return new PaymentGateway.Cancellation("cancel-1","SUCCEEDED",1891);});
        assertThat(refunds.process(admin,r).get("status")).isEqualTo("SUCCEEDED");
        assertThat(orders.get(user,id).get("status")).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(content.open(user,id,"compat")).containsKey("content");
        assertThatThrownBy(()->content.open(user,id,"charm")).hasMessage("ENTITLEMENT_NOT_ACTIVE");
        assertThatThrownBy(()->refunds.process(admin,r)).isInstanceOf(ApiException.class);
        verify(gateway,times(1)).cancel(anyString(),anyInt(),anyInt(),anyString());
    }
    @Test void timeoutNeverRetriesCancellationAndReconcilesByProviderId() {
        UUID id=create("charm");pay(id);UUID r=(UUID)refunds.request(user,id,"NOT_PROVIDED").get("id");
        when(gateway.cancel(anyString(),anyInt(),anyInt(),anyString())).thenThrow(new RuntimeException("timeout"));
        assertThat(refunds.process(admin,r).get("status")).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->refunds.process(admin,r)).isInstanceOf(ApiException.class);
        var p=paid(id);when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"CANCELLED",1000,1000,"KRW","store-test",id.toString(),p.paidAt(),List.of(new PaymentGateway.Cancellation("lost-response","SUCCEEDED",1000))));
        assertThat(refunds.reconcile(admin,r,"lost-response").get("status")).isEqualTo("SUCCEEDED");
        verify(gateway,times(1)).cancel(anyString(),anyInt(),anyInt(),anyString());
    }
    @Test void laterUnknownConsoleCancellationFreezesPreviouslyRetainedAccess() {
        UUID id=create("package");pay(id);publish(id,"compat");content.open(user,id,"compat");
        UUID r=(UUID)refunds.request(user,id,"CHANGE_OF_MIND").get("id");var p=paid(id);
        when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"PARTIAL_CANCELLED",3790,1891,"KRW","store-test",id.toString(),p.paidAt(),List.of(new PaymentGateway.Cancellation("console-known","SUCCEEDED",1891))));
        assertThat(refunds.reconcile(admin,r,"console-known").get("status")).isEqualTo("SUCCEEDED");
        when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"CANCELLED",3790,3790,"KRW","store-test",id.toString(),p.paidAt(),List.of(new PaymentGateway.Cancellation("console-known","SUCCEEDED",1891),new PaymentGateway.Cancellation("console-extra","SUCCEEDED",1899))));
        assertThat(orders.complete(user,id).get("status")).isEqualTo("REVIEW_REQUIRED");
        assertThatThrownBy(()->content.open(user,id,"compat")).hasMessage("ORDER_NOT_ACCESSIBLE");
    }
    @Test void exceptionalRemainingRefundAfterPartialDoesNotOverRefund() {
        UUID id=create("package");pay(id);publish(id,"compat");content.open(user,id,"compat");
        UUID r=(UUID)refunds.request(user,id,"CHANGE_OF_MIND").get("id");var p=paid(id);
        when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"PARTIAL_CANCELLED",3790,1891,"KRW","store-test",id.toString(),p.paidAt(),List.of(new PaymentGateway.Cancellation("partial","SUCCEEDED",1891))));
        refunds.reconcile(admin,r,"partial");
        var next=refunds.request(user,id,"NOT_AS_DESCRIBED");assertThat(Store.number(next,"amount")).isEqualTo(1899);
        assertThat(next.get("id")).isNotEqualTo(r);
        UUID second=(UUID)next.get("id");
        when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"CANCELLED",3790,3790,"KRW","store-test",id.toString(),p.paidAt(),List.of(new PaymentGateway.Cancellation("partial","SUCCEEDED",1891),new PaymentGateway.Cancellation("remaining","SUCCEEDED",1899))));
        assertThat(refunds.reconcile(admin,second,"remaining").get("status")).isEqualTo("SUCCEEDED");
        assertThat(content.locker(user)).isEmpty();assertThat(orders.get(user,id).get("status")).isEqualTo("REFUNDED");
    }
    @Test void concurrentFirstLoginCreatesExactlyOneAccount() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{await(gate);return accounts.login("new-concurrent-kakao");});
            var b=pool.submit(()->{await(gate);return accounts.login("new-concurrent-kakao");});
            gate.countDown();assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
    }
    @Test void openAndRefundRaceHasExactlyOneWinner() throws Exception {
        UUID id=create("charm");pay(id);publish(id,"charm");var start=new CountDownLatch(1);AtomicInteger success=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{await(start);try {content.open(user,id,"charm");success.incrementAndGet();}catch(ApiException ignored){}});
            var b=pool.submit(()->{await(start);try {refunds.request(user,id,"CHANGE_OF_MIND");success.incrementAndGet();}catch(ApiException ignored){}});
            start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertThat(success.get()).isEqualTo(1);
    }
    static void await(CountDownLatch latch) {try {latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    @Test void withdrawDeletesServiceDataKeepsTransactionAndBlocksOldIdentity() {
        UUID id=create("charm");pay(id);publish(id,"charm");accounts.withdraw(user);
        assertThat(db.rows("select * from analysis_result where member_id=?",user)).isEmpty();
        assertThat(db.rows("select * from consent where member_id=?",user)).isEmpty();
        assertThat(db.one("select * from member_account where id=?",user).get("kakao_id")).isNull();
        assertThat(db.one("select * from entitlement where order_id=?",id).get("content")).isNull();
        assertThat(db.one("select * from purchase_order where id=?",id).get("result_id")).isNull();
        assertThatThrownBy(()->content.locker(user)).hasMessage("ACCOUNT_INACTIVE");
        assertThat(accounts.login("kakao-1")).isNotEqualTo(user);
    }
    @Test void withdrawalDeletesServiceDataButPreservesPendingRefundProcessing() {
        UUID id=create("charm");pay(id);UUID r=(UUID)refunds.request(user,id,"NOT_PROVIDED").get("id");
        accounts.withdraw(user);
        assertThat(db.rows("select * from analysis_result where member_id=?",user)).isEmpty();
        var p=paid(id);when(gateway.get("yhd-"+id)).thenReturn(new PaymentGateway.Payment(p.id(),"CANCELLED",1000,1000,"KRW","store-test",id.toString(),p.paidAt(),List.of(new PaymentGateway.Cancellation("after-withdrawal","SUCCEEDED",1000))));
        assertThat(refunds.reconcile(admin,r,"after-withdrawal").get("status")).isEqualTo("SUCCEEDED");
        assertThat(db.one("select * from entitlement where order_id=?",id).get("status")).isEqualTo("REVOKED");
    }
    @Test void delayedApprovalAfterWithdrawalQueuesRefundInsteadOfResurrectingData() {
        UUID id=create("package");accounts.withdraw(user);when(gateway.get("yhd-"+id)).thenReturn(paid(id));
        assertThat(orders.sync(id).get("status")).isEqualTo("REVIEW_REQUIRED");orders.sync(id);
        assertThat(db.rows("select * from entitlement where order_id=?",id)).isEmpty();
        var queue=db.rows("select * from refund_request where order_id=?",id);
        assertThat(queue).hasSize(1);assertThat(queue.getFirst().get("reason")).isEqualTo("NOT_PROVIDED");
    }
    @Test void csrfOwnershipCorsAndOperatorAuthorization() throws Exception {
        mvc.perform(get("/api/locker")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders").with(user(user.toString()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/refunds").with(user(user.toString()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/refunds").with(user(admin.toString()).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/products").header("Origin","https://untrusted.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"));
        UUID id=create("charm");
        mvc.perform(get("/api/orders/"+id).with(user(other.toString()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/not-a-uuid").with(user(user.toString()))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").with(user(user.toString())).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
    }
    @Test void deploymentHealthCheckIsPublicButDoesNotExposeComponents() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest
    @ValueSource(strings={"https://yeonhakdang.vercel.app","http://localhost:3000"})
    void frontendOriginsSupportCredentialedRequestsAndPreflight(String origin) throws Exception {
        mvc.perform(get("/api/products").header("Origin",origin))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin",origin))
            .andExpect(header().string("Access-Control-Allow-Credentials","true"));
        // Browsers preflight JSON/CSRF/idempotency headers before sending a protected request.
        mvc.perform(options("/api/orders").header("Origin",origin)
            .header("Access-Control-Request-Method","POST")
            .header("Access-Control-Request-Headers","content-type,x-csrf-token,idempotency-key"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin",origin))
            .andExpect(header().string("Access-Control-Allow-Credentials","true"))
            .andExpect(header().string("Access-Control-Allow-Methods",org.hamcrest.Matchers.containsString("POST")))
            .andExpect(header().string("Access-Control-Allow-Headers",org.hamcrest.Matchers.containsString("x-csrf-token")))
            .andExpect(header().string("Access-Control-Allow-Headers",org.hamcrest.Matchers.containsString("idempotency-key")));
        // Frontend code must be able to read a normal 401 instead of seeing a CORS network error.
        mvc.perform(get("/api/locker").header("Origin",origin))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin",origin));
    }
    @ParameterizedTest
    @ValueSource(strings={"https://yeonhakdang.vercel.app.evil.example","http://localhost:3001"})
    void unlistedOriginsCannotPreflightProtectedEndpoints(String origin) throws Exception {
        mvc.perform(options("/api/orders").header("Origin",origin).header("Access-Control-Request-Method","POST"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    @Test void realCsrfTokenWorksWithSessionAndOversizedBodyIsRejected() throws Exception {
        var response=mvc.perform(get("/api/csrf")).andReturn();
        var json=new tools.jackson.databind.ObjectMapper();String token=json.readTree(response.getResponse().getContentAsString()).path("token").asText();
        var cookies=response.getResponse().getCookies();
        assertThat(Arrays.stream(cookies).filter(c->"YHDSESSION".equals(c.getName())).findFirst().orElseThrow().isHttpOnly()).isTrue();
        mvc.perform(post("/api/me/consents").cookie(cookies).with(user(user.toString())).header("X-CSRF-TOKEN",token)
            .contentType("application/json").content("{\"version\":\"2026-10-29-draft\",\"age14\":true,\"terms\":true,\"privacy\":true,\"marketing\":false}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/results").with(user(user.toString())).with(csrf()).contentType("application/json").content("x".repeat(65537))).andExpect(status().isPayloadTooLarge());
    }
}
