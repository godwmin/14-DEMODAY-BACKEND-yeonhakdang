package kr.yeonhakdang;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@Service
public class WebhookService {
    private final Store db;private final OrderService orders;private final ObjectMapper json;private final Clock clock;private final String secret,store;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private static final Set<String> EVENTS=Set.of("Transaction.Ready","Transaction.Paid","Transaction.VirtualAccountIssued","Transaction.PartialCancelled","Transaction.Cancelled","Transaction.Failed","Transaction.PayPending","Transaction.CancelPending");
    public WebhookService(Store db,OrderService orders,ObjectMapper json,Clock clock,@Value("${app.portone.webhook-secret}") String secret,@Value("${app.portone.store-id}") String store,org.springframework.transaction.PlatformTransactionManager tm) {
        tx=new org.springframework.transaction.support.TransactionTemplate(tm);
        this.db=db;this.orders=orders;this.json=json;this.clock=clock;this.secret=secret;this.store=store;
    }
    public void verify(String id,String timestamp,String signatures,String body) {
        if(secret.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"WEBHOOK_NOT_CONFIGURED");
        try {
            if(id==null||id.length()>200||timestamp==null||signatures==null||body.length()>65536) throw new IllegalArgumentException();
            long t=Long.parseLong(timestamp),now=clock.instant().getEpochSecond();if(t<now-300||t>now+300) throw new IllegalArgumentException();
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.startsWith("whsec_")?secret.substring(6):secret),"HmacSHA256"));
            byte[] expected=mac.doFinal((id+"."+timestamp+"."+body).getBytes(StandardCharsets.UTF_8));
            boolean valid=false;
            for(String s:signatures.split(" ")) {var p=s.split(",",3);if(p.length>=2&&"v1".equals(p[0])) try {valid|=MessageDigest.isEqual(expected,Base64.getDecoder().decode(p[1]));}catch(IllegalArgumentException ignored){}}
            if(!valid) throw new IllegalArgumentException();
        } catch(Exception e) {throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_WEBHOOK_SIGNATURE");}
    }
    public void receive(String id,String timestamp,String signature,String body) {
        verify(id,timestamp,signature,body);
        var event=json.readTree(body);
        if(!EVENTS.contains(event.path("type").asText())) return;
        if(!store.equals(event.path("data").path("storeId").asText())) throw new ApiException(HttpStatus.BAD_REQUEST,"WEBHOOK_STORE_MISMATCH");
        var candidates=db.rows("select id,member_id from purchase_order where payment_id=?",event.path("data").path("paymentId").asText());
        if(candidates.isEmpty()) return; // Not an order issued by this service.
        var o=candidates.getFirst();
        if(!db.rows("select id from webhook_receipt where id=?",id).isEmpty()) return;
        var payment=orders.fetch((UUID)o.get("id"));
        tx.executeWithoutResult(s->{
            db.member((UUID)o.get("member_id"));db.order((UUID)o.get("id"));
            if(!db.rows("select id from webhook_receipt where id=?",id).isEmpty()) return;
            orders.applyPayment((UUID)o.get("id"),payment);
            db.update("insert into webhook_receipt values(?,?)",id,clock.instant());
        });
    }
}
