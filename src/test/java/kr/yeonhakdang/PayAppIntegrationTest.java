package kr.yeonhakdang;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:payapp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.payment.provider=payapp","app.payment.merchant-id=fixture-seller",
    "app.payapp.user-id=fixture-seller","app.payapp.link-key=fixture-key",
    "app.payapp.link-value=fixture-value","app.payapp.request-key=fixture-request-key",
    "app.payapp.public-origin=https://api.example.com","app.payapp.methods=CARD"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PayAppIntegrationTest {
    @Autowired Store db;
    @Autowired AccountService accounts;
    @Autowired ContentService content;
    @Autowired OrderService orders;
    @Autowired RefundService refunds;
    @Autowired PayAppGateway gateway;
    @Autowired OrderResultController snapshots;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    HttpServer server;
    UUID user,admin,result;
    AtomicInteger calls;
    AtomicReference<Map<String,String>> sent;
    String response;
    @BeforeEach void setup() throws Exception {
        for(String table:List.of("SPRING_SESSION","payapp_cancellation","payapp_cancel_intent","payapp_payment","webhook_receipt","refund_request","entitlement","purchase_order","analysis_result","consent","member_account"))
            db.update("delete from "+table);
        user=accounts.login("fixture-user");admin=accounts.login("fixture-admin");
        db.update("update member_account set role='ADMIN' where id=?",admin);
        accounts.consent(user,"2026-10-29-draft",true,true,true,false);
        result=(UUID)content.save(user,new ApiController.AnalysisInput("fixture",Map.of("wood",2,"fire",2,"earth",2,"metal",1,"water",1),List.of(new ApiController.Major("engineering",85)))).get("id");
        calls=new AtomicInteger();sent=new AtomicReference<>();
        response="state=1&mul_no=1234&payurl=https%3A%2F%2Fpayapp.kr%2Ffixture";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();sent.set(PayAppGateway.decode(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();gateway.endpoint(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
    }
    @AfterEach void stop() {if(server!=null)server.stop(0);}
    UUID create(String product) {
        UUID id=(UUID)orders.create(user,"request_"+UUID.randomUUID(),new ApiController.OrderInput(result,product,List.of("연세대학교"),"선배","2026-10-29-draft",true,true)).get("id");
        var results=new HashMap<String,tools.jackson.databind.JsonNode>();
        var variants=new ArrayList<tools.jackson.databind.JsonNode>();
        for(String kind:OrderService.kinds(product)) {
            String body=switch(kind) {
                case "compat" -> "{\"universities\":[{\"name\":\"연세대학교\"}],\"months\":[],\"weeks\":[],\"chips\":[]}";
                case "benefactor" -> "{\"tags\":[],\"hints\":[],\"timeline\":[]}";
                case "charm" -> "{\"tags\":[],\"art\":{\"variant\":0}}";
                default -> throw new IllegalArgumentException();
            };
            results.put(kind,json.readTree(body));
        }
        if(results.containsKey("charm")) for(int i=0;i<4;i++) variants.add(json.readTree("{\"tags\":[],\"art\":{\"variant\":"+i+"}}"));
        var input=new OrderResultController.Input("테스트","2005.03.15",0,0,false,"",true);
        snapshots.save(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.toString(),null),id,
            new OrderResultController.Snapshot(1,input,List.of("연세대학교"),"선배",results,variants));
        return id;
    }
    UUID prepare() {UUID id=create("benefactor");orders.prepare(user,id,"CARD","01012345678");return id;}
    Map<String,String> notification(UUID id,String state) {
        String date=DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(ZonedDateTime.now(ZoneId.of("Asia/Seoul")));
        var event=new HashMap<String,String>();
        event.put("userid","fixture-seller");event.put("linkkey","fixture-key");event.put("linkval","fixture-value");
        event.put("responsedate",date);event.put("hash",OrderService.hash("fixture-seller"+date+"fixture-request-key"));
        event.put("var1",id.toString());event.put("var2",(String)db.one("select callback_token from payapp_payment where order_id=?",id).get("callback_token"));
        event.put("mul_no","1234");event.put("pay_state",state);event.put("price","1900");
        // Keep sub-second precision loss from appearing before order creation.
        event.put("pay_date",date);
        db.update("update purchase_order set created_at=? where id=?",Instant.now().minusSeconds(5),id);
        return event;
    }
    static String form(Map<String,String> values) {
        return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    @Test void requestUsesServerPriceAndSignedFormAndReusesHostedUrl() {
        UUID id=prepare();var data=sent.get();
        assertThat(data).containsEntry("cmd","payrequest").containsEntry("price","1900").containsEntry("recvphone","01012345678")
            .containsEntry("smsuse","n").containsEntry("feedbackurl","https://api.example.com/api/webhooks/payapp")
            .containsEntry("returnurl","https://api.example.com/api/payments/payapp/return?order="+id);
        assertThat(data.get("hash")).isEqualTo(OrderService.hash("fixture-seller"+data.get("requestdate")+"fixture-request-key"));
        assertThat(data).doesNotContainKey("requestkey");
        assertThat(orders.prepare(user,id,"CARD","01012345678")).containsEntry("payUrl","https://payapp.kr/fixture");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(orders.complete(user,id)).containsEntry("status","PENDING");
        assertThat(db.rows("select * from entitlement where order_id=?",id)).isEmpty();
    }
    @Test void minimumPhoneAndMerchantMethodAreEnforcedBeforeProviderCall() {
        assertThatThrownBy(()->orders.prepare(user,create("charm"),"CARD","01012345678")).hasMessage("PAYAPP_MINIMUM_AMOUNT");
        UUID id=create("benefactor");
        assertThatThrownBy(()->orders.prepare(user,id,"CARD",null)).hasMessage("PAYMENT_PHONE_REQUIRED");
        assertThatThrownBy(()->orders.prepare(user,id,"KAKAO","01012345678")).hasMessage("CHANNEL_NOT_CONFIGURED");
        assertThat(calls.get()).isZero();
    }
    @Test void verifiedFormCallbackGrantsOnceAndBrowserReturnNeverGrants() throws Exception {
        UUID id=prepare();
        mvc.perform(post("/api/payments/payapp/return").queryParam("order",id.toString()))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","http://localhost:3000/?checkoutOrder="+id));
        assertThat(db.rows("select * from entitlement where order_id=?",id)).isEmpty();
        String body=form(notification(id,"4"));
        for(int i=0;i<2;i++) mvc.perform(post("/api/webhooks/payapp").contentType("application/x-www-form-urlencoded").content(body))
            .andExpect(status().isOk()).andExpect(content().string("SUCCESS"));
        assertThat(orders.get(user,id)).containsEntry("status","PAID");
        assertThat(db.rows("select * from entitlement where order_id=?",id)).hasSize(1);
    }
    @Test void freshAuthenticatedSessionRecoversVerifiedApprovalAndRestoresOnlyOwnLibrary() throws Exception {
        UUID id=prepare();
        gateway.receive(notification(id,"4")); // Simulate interruption before entitlement provisioning.
        assertThat(db.rows("select * from entitlement where order_id=?",id)).isEmpty();
        mvc.perform(get("/api/me/library").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(user.toString())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(user.toString()))
            .andExpect(jsonPath("$.items[0].order_id").value(id.toString()))
            .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.orders[0].status").value("PAID"));
        mvc.perform(get("/api/me/library").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(admin.toString())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.orders").isEmpty());
        mvc.perform(get("/api/me/library")).andExpect(status().isUnauthorized());
        assertThat(calls.get()).isEqualTo(1); // Recovery uses signed, stored facts, not a new charge.
        assertThat(db.rows("select * from entitlement where order_id=?",id)).hasSize(1);
    }
    @Test void forgedOrStaleFeedbackNeverGrants() {
        UUID id=prepare();
        for(String field:List.of("userid","linkkey","linkval","hash","var2","price","mul_no","responsedate")) {
            var event=notification(id,"4");event.put(field,field.equals("price")?"1000":"forged");
            assertThatThrownBy(()->gateway.receive(event)).isInstanceOf(ApiException.class);
        }
        assertThat(db.rows("select * from entitlement where order_id=?",id)).isEmpty();
        assertThat(gateway.get("yhd-"+id).status()).isEqualTo("PENDING");
    }
    @Test void unknownRequestIsNeverAutomaticallyRetriedAndBadUrlNeverReturned() {
        response="state=1&mul_no=1234&payurl=https%3A%2F%2Fevil.example%2F";
        UUID id=create("benefactor");
        assertThatThrownBy(()->orders.prepare(user,id,"CARD","01012345678")).hasMessage("INVALID_PAYMENT_URL");
        assertThatThrownBy(()->orders.prepare(user,id,"CARD","01012345678")).hasMessage("PAYMENT_REQUEST_UNKNOWN");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void refundWaitsForVerifiedCancellationAndDelayedPaidDoesNotRevive() {
        UUID id=prepare();gateway.receive(notification(id,"4"));orders.complete(user,id);
        UUID refund=(UUID)refunds.request(user,id,"CHANGE_OF_MIND").get("id");
        response="state=1";
        assertThat(refunds.process(admin,refund)).containsEntry("status","PROCESSING");
        assertThat(sent.get()).containsEntry("cmd","paycancelreq").containsEntry("mul_no","1234");
        assertThat(orders.get(user,id)).containsEntry("status","PAID");
        gateway.receive(notification(id,"9"));orders.sync(id);
        assertThat(orders.get(user,id)).containsEntry("status","REFUNDED");
        assertThat(refunds.get(user,id)).containsEntry("status","SUCCEEDED");
        gateway.receive(notification(id,"64"));gateway.receive(notification(id,"4"));orders.sync(id);
        assertThat(gateway.get("yhd-"+id).cancelled()).isEqualTo(1900);
        assertThat(orders.get(user,id)).containsEntry("status","REFUNDED");
    }
    @Test void unexpectedCancellationSuspendsAccessForReview() {
        UUID id=prepare();gateway.receive(notification(id,"4"));orders.complete(user,id);
        gateway.receive(notification(id,"9"));orders.sync(id);
        assertThat(orders.get(user,id)).containsEntry("status","REVIEW_REQUIRED");
        assertThat(db.one("select status from entitlement where order_id=?",id)).containsEntry("status","SUSPENDED");
    }
    @Test void partialCancellationUsesOriginalTransactionAndDeduplicatesItsProviderId() {
        UUID id=prepare();gateway.receive(notification(id,"4"));orders.complete(user,id);
        var event=notification(id,"70");event.put("mul_no","4321");event.put("orig_mul_no","1234");event.put("orig_price","1900");event.put("price","500");
        gateway.receive(event);event.put("pay_state","71");gateway.receive(event);
        assertThat(gateway.get("yhd-"+id).cancelled()).isEqualTo(500);
        assertThat(gateway.get("yhd-"+id).cancellations()).hasSize(1);
    }
    @Test void feedbackInvalidatesAnOlderPaymentSnapshot() {
        UUID id=prepare();var stale=orders.fetch(id);
        gateway.receive(notification(id,"4"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.execute(status->orders.applyPayment(id,stale))).hasMessage("PAYMENT_STATE_CHANGED");
        assertThat(orders.complete(user,id)).containsEntry("status","PAID");
    }
    @Test void duplicateFieldsAreRejectedAndSecretsAreNotNeededForBrowserReturn() {
        assertThatThrownBy(()->PayAppGateway.decode("price=1900&price=1")).isInstanceOf(IllegalArgumentException.class);
        for(String url:List.of("http://payapp.kr/fixture","https://payapp.kr.evil.example/","https://evil.example/","https://user@payapp.kr/"))
            assertThatThrownBy(()->PayAppGateway.paymentUrl(url)).isInstanceOf(Exception.class);
    }
}
