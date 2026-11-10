package kr.yeonhakdang;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class OrderResultIntegrationTest {
    @Autowired MockMvc mvc;@Autowired Store db;@Autowired AccountService accounts;
    @Autowired OrderService orders;@Autowired ContentService content;
    @MockitoBean PaymentGateway gateway;
    UUID member,other,order;
    @BeforeEach void setup() {
        reset(gateway);
        member=accounts.login("saved-result-"+UUID.randomUUID());other=accounts.login("other-"+UUID.randomUUID());
        accounts.consent(member,"2026-10-29-draft",true,true,true,false);
        UUID result=(UUID)content.save(member,new ApiController.AnalysisInput("test",Map.of("wood",2,"fire",2,"earth",2,"metal",1,"water",1),List.of(new ApiController.Major("major",85)))).get("id");
        order=(UUID)orders.create(member,"snapshot_"+UUID.randomUUID(),new ApiController.OrderInput(result,"package",List.of("연세대학교"),"선배","2026-10-29-draft",true,true)).get("id");
    }
    String snapshot() {
        return """
            {"version":1,"input":{"name":"구매당시이름","birth":"2005.03.15","gender":0,"calendar":0,"leapMonth":false,"time":"","unknownTime":true},"universities":["연세대학교"],"relation":"선배","results":{"compat":{"universities":[{"name":"연세대학교","score":85}],"months":[],"weeks":[],"chips":[]},"benefactor":{"tags":[],"hints":[],"timeline":[]},"charm":{"tags":[],"art":{"variant":0}}},"charmVariants":[{"tags":[],"art":{"variant":0}},{"tags":[],"art":{"variant":1}},{"tags":[],"art":{"variant":2}},{"tags":[],"art":{"variant":3}}]}
            """;
    }
    void save() throws Exception {
        mvc.perform(put("/api/orders/"+order+"/result-snapshot").with(user(member.toString())).with(csrf()).contentType("application/json").content(snapshot())).andExpect(status().isOk());
    }
    void pay() {
        when(gateway.get("yhd-"+order)).thenReturn(new PaymentGateway.Payment("yhd-"+order,"PAID",3790,0,"KRW","store-test",order.toString(),Instant.now(),List.of()));
        orders.complete(member,order);
    }
    String open(String kind) {return "/api/orders/"+order+"/saved-results/"+kind+"/open";}
    @Test void preparationRequiresDurableSnapshotBeforeOpeningPayment() throws Exception {
        assertThatThrownBy(()->orders.prepare(member,order,"CARD")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("SAVED_RESULT_MISSING"));
        save();
        assertThat(orders.prepare(member,order,"CARD")).containsEntry("paymentId","yhd-"+order);
        verifyNoInteractions(gateway);
    }
    @Test void freezesPurchaseAndRestoresExactReportAcrossSessions() throws Exception {
        save();save();
        mvc.perform(post(open("compat")).with(user(member.toString())).with(csrf())).andExpect(status().isConflict());
        pay();
        mvc.perform(post(open("compat")).with(user(member.toString())).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.input.name").value("구매당시이름"))
            .andExpect(jsonPath("$.result.universities[0].score").value(85));
        var first=db.one("select first_viewed_at from entitlement where order_id=? and product_code='compat'",order).get("first_viewed_at");
        mvc.perform(post(open("compat")).with(user(member.toString())).with(csrf())).andExpect(status().isOk());
        assertThat(db.one("select first_viewed_at from entitlement where order_id=? and product_code='compat'",order).get("first_viewed_at")).isEqualTo(first);
        mvc.perform(put("/api/orders/"+order+"/result-snapshot").with(user(member.toString())).with(csrf()).contentType("application/json").content(snapshot().replace("구매당시이름","바뀐이름")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTENT_IMMUTABLE"));
    }
    @Test void requiresOwnershipPaymentEntitlementAndCsrf() throws Exception {
        save();pay();
        mvc.perform(post(open("compat")).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(open("compat")).with(user(other.toString())).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(open("compat")).with(user(member.toString()))).andExpect(status().isForbidden());
        db.update("update entitlement set status='REFUNDED' where order_id=? and product_code='compat'",order);
        mvc.perform(post(open("compat")).with(user(member.toString())).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTITLEMENT_NOT_ACTIVE"));
    }
    @Test void redrawIsDurableLimitedAndSafeAgainstConcurrentWindows() throws Exception {
        save();pay();
        for(int variant=0;variant<3;variant++) {
            mvc.perform(post("/api/orders/"+order+"/saved-results/charm/redraw").with(user(member.toString())).with(csrf()).contentType("application/json").content("{\"expectedVariant\":"+variant+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.charmRerolls").value(2-variant)).andExpect(jsonPath("$.result.art.variant").value(variant+1));
        }
        mvc.perform(post(open("charm")).with(user(member.toString())).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.result.art.variant").value(3)).andExpect(jsonPath("$.charmRerolls").value(0));
        mvc.perform(post("/api/orders/"+order+"/saved-results/charm/redraw").with(user(member.toString())).with(csrf()).contentType("application/json").content("{\"expectedVariant\":0}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESULT_CHANGED"));
        mvc.perform(post("/api/orders/"+order+"/saved-results/charm/redraw").with(user(member.toString())).with(csrf()).contentType("application/json").content("{\"expectedVariant\":3}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_REDRAWS_LEFT"));
    }
    @Test void missingSnapshotNeverPretendsToBeThePurchasedReport() throws Exception {
        pay();
        mvc.perform(post(open("compat")).with(user(member.toString())).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAVED_RESULT_MISSING"));
        assertThat(db.one("select first_viewed_at from entitlement where order_id=? and product_code='compat'",order).get("first_viewed_at")).isNull();
    }
    @Test void withdrawalDeletesPersonalSnapshotButRetainsTransaction() throws Exception {
        save();pay();accounts.withdraw(member);
        assertThat(db.rows("select * from order_result_snapshot where order_id=?",order)).isEmpty();
        assertThat(db.rows("select id from purchase_order where id=?",order)).hasSize(1);
    }
}
