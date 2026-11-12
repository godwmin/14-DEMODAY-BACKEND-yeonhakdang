package kr.yeonhakdang;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class OrderService {
    private final Store db;private final AccountService accounts;private final ObjectMapper json;private final Clock clock;
    private final PaymentGateway gateway;private final String store,version,frontend;private final boolean sales;
    private final Map<String,String> channels;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    public OrderService(Store db,AccountService accounts,ObjectMapper json,Clock clock,PaymentGateway gateway,
        @Value("${app.payment.merchant-id}") String store,@Value("${app.portone.store-id}") String legacyStore,@Value("${app.policy-version}") String version,
        @Value("${app.sales-enabled}") boolean sales,@Value("${app.portone.card-channel}") String card,
        @Value("${app.portone.kakao-channel}") String kakao,@Value("${app.portone.toss-channel}") String toss,@Value("${app.frontend-origin}") String frontend,org.springframework.transaction.PlatformTransactionManager tm) {
        tx=new org.springframework.transaction.support.TransactionTemplate(tm);
        this.db=db;this.accounts=accounts;this.json=json;this.clock=clock;this.gateway=gateway;this.store=gateway instanceof PayAppGateway?store:legacyStore;this.version=version;this.sales=sales;
        channels=Map.of("CARD",card,"KAKAO",kakao,"TOSS",toss);this.frontend=frontend;
    }
    @Transactional
    public Map<String,Object> create(UUID user,String key,ApiController.OrderInput input) {
        db.active(user);accounts.requireConsent(user);
        if(!version.equals(input.policyVersion())||!input.orderConsent()||!input.refundConsent()) throw ApiException.conflict("PURCHASE_CONSENT_REQUIRED");
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,80}")) throw new IllegalArgumentException("idempotency key");
        String fingerprint=hash(json.writeValueAsString(input));
        var old=db.rows("select * from purchase_order where member_id=? and idempotency_key=?",user,key);
        if(!old.isEmpty()) {if(!fingerprint.equals(old.getFirst().get("request_fingerprint"))) throw ApiException.conflict("IDEMPOTENCY_CONFLICT");return old.getFirst();}
        var r=db.one("select id from analysis_result where id=? and member_id=?",input.resultId(),user);
        var p=db.one("select * from product where code=?",input.product());
        if(Set.of("compat","package").contains(input.product())&&(input.universities().isEmpty()||input.universities().size()>6)) throw new IllegalArgumentException("universities");
        if(Set.of("benefactor","package").contains(input.product())&&!Set.of("선배","동기","교수님","연인").contains(input.relation())) throw new IllegalArgumentException("relation");
        UUID id=UUID.randomUUID();
        db.update("insert into purchase_order(id,member_id,result_id,product_code,amount,status,payment_id,created_at,consent_version,consent_at,universities,relation,idempotency_key,request_fingerprint) values(?,?,?,?,?,'CREATED',?,?,?,?,?,?,?,?)",
            id,user,r.get("id"),input.product(),p.get("price"),"yhd-"+id,clock.instant(),version,clock.instant(),json.writeValueAsString(input.universities()),input.relation(),key,fingerprint);
        return db.order(id);
    }
    public Map<String,Object> prepare(UUID user,UUID id,String method) { return prepare(user,id,method,null); }
    public Map<String,Object> prepare(UUID user,UUID id,String method,String phone) {
        if(gateway instanceof PayAppGateway payapp) {
            tx.executeWithoutResult(s->{db.owned(user,id);accounts.requireConsent(user);
                if(!sales) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PAID_CONTENT_NOT_READY");
                if(db.rows("select order_id from order_result_snapshot where order_id=?",id).isEmpty()) throw ApiException.conflict("SAVED_RESULT_MISSING");
                rejectDuplicateReport(user,id);});
            return payapp.prepare(user,id,method,phone);
        }
        return tx.execute(s->prepareLegacy(user,id,method));
    }
    private Map<String,Object> prepareLegacy(UUID user,UUID id,String method) {
        var o=db.owned(user,id);accounts.requireConsent(user);
        if(!sales) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PAID_CONTENT_NOT_READY");
        if(db.rows("select order_id from order_result_snapshot where order_id=?",id).isEmpty()) throw ApiException.conflict("SAVED_RESULT_MISSING");
        rejectDuplicateReport(user,id);
        if(!Set.of("CREATED","READY","FAILED").contains(o.get("status"))) throw ApiException.conflict("ORDER_NOT_PAYABLE");
        String channel=channels.get(method);
        if(channel==null||channel.isBlank()||store.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CHANNEL_NOT_CONFIGURED");
        db.update("update purchase_order set status='READY' where id=?",id);
        String title=(String)db.one("select title from product where code=?",o.get("product_code")).get("title");
        var sdk=new LinkedHashMap<String,Object>();
        sdk.put("storeId",store);sdk.put("channelKey",channel);sdk.put("paymentId",o.get("payment_id"));sdk.put("orderName",title);
        sdk.put("totalAmount",o.get("amount"));sdk.put("currency","CURRENCY_KRW");
        sdk.put("payMethod","CARD".equals(method)?"CARD":"EASY_PAY");
        if(!"CARD".equals(method)) sdk.put("easyPay",Map.of("easyPayProvider","KAKAO".equals(method)?"KAKAOPAY":"TOSSPAY"));
        sdk.put("customData",Map.of("orderId",id.toString()));
        sdk.put("redirectUrl",frontend+"/?checkoutOrder="+id);sdk.put("forceRedirect",true);return sdk;
    }
    private void rejectDuplicateReport(UUID member,UUID orderId) {
        var candidate=json.readValue((String)db.one("select snapshot from order_result_snapshot where order_id=?",orderId).get("snapshot"),OrderResultController.Snapshot.class);
        var order=db.order(orderId);
        var owned=new HashSet<String>();
        var previous=db.rows("select e.product_code,s.snapshot from entitlement e join purchase_order o on o.id=e.order_id join order_result_snapshot s on s.order_id=o.id where o.member_id=? and o.id<>? and e.status='ACTIVE' and o.status in ('PAID','PARTIALLY_REFUNDED')",member,orderId);
        for(var row:previous) {
            String kind=(String)row.get("product_code");
            var saved=json.readValue((String)row.get("snapshot"),OrderResultController.Snapshot.class);
            if(!saved.input().equals(candidate.input())) continue;
            if("compat".equals(kind)&&!saved.universities().equals(candidate.universities())) continue;
            if("benefactor".equals(kind)&&!saved.relation().equals(candidate.relation())) continue;
            owned.add(kind);
        }
        if(owned.containsAll(kinds((String)order.get("product_code")))) throw ApiException.conflict("ALREADY_OWNED");
    }
    public Map<String,Object> complete(UUID user,UUID id) {
        tx.executeWithoutResult(s->db.owned(user,id));
        var payment=fetch(id); // No database locks during provider I/O.
        return tx.execute(s->{db.owned(user,id);return applyPayment(id,payment);});
    }
    record PaymentSnapshot(PaymentGateway.Payment payment,long revision) {}
    PaymentSnapshot fetch(UUID id) {
        var o=db.one("select payment_id,sync_revision from purchase_order where id=?",id);
        return new PaymentSnapshot(gateway.get((String)o.get("payment_id")),((Number)o.get("sync_revision")).longValue());
    }
    public Map<String,Object> sync(UUID id) {
        var payment=fetch(id);
        return tx.execute(s->applyPayment(id,payment));
    }
    // Must run inside a transaction; always lock member before order and re-read local state.
    Map<String,Object> applyPayment(UUID id,PaymentSnapshot snapshot) {
        var owner=db.one("select member_id from purchase_order where id=?",id).get("member_id");
        var member=db.member((UUID)owner);var o=db.order(id);
        if(((Number)o.get("sync_revision")).longValue()!=snapshot.revision()) throw ApiException.conflict("PAYMENT_STATE_CHANGED");
        var payment=snapshot.payment();
        db.update("update purchase_order set sync_revision=sync_revision+1 where id=?",id);
        if(!matches(o,payment)) {
            db.update("update purchase_order set status='REVIEW_REQUIRED' where id=?",id);
            db.update("update entitlement set status='SUSPENDED' where order_id=? and status='ACTIVE'",id);
            return db.order(id);
        }
        var refund=db.rows("select * from refund_request where order_id=?",id);
        if(payment.cancelled()>0) {
            for(var r:refund) if(Set.of("PROCESSING","UNKNOWN").contains(r.get("status"))) {
                String cid=(String)r.get("cancellation_id");
                // Never infer a particular refund succeeded solely from the aggregate cancelled balance.
                boolean confirmed=cid!=null&&payment.cancellations().stream().anyMatch(c->cid.equals(c.id())&&"SUCCEEDED".equals(c.status())&&c.amount()==Store.number(r,"amount"));
                if(confirmed) {
                    db.update("update refund_request set status='SUCCEEDED' where id=?",r.get("id"));
                    db.update("update entitlement set status='REFUNDED',content=null where order_id=? and status='REFUND_PENDING'",id);
                }
            }
            int verified=db.rows("select amount from refund_request where order_id=? and status='SUCCEEDED'",id).stream().mapToInt(r->Store.number(r,"amount")).sum();
            if(payment.cancelled()==verified) {
                db.update("update purchase_order set status=? where id=?",payment.cancelled()==payment.total()?"REFUNDED":"PARTIALLY_REFUNDED",id);
            } else {
                // Includes unexpected EXTRA cancellations after a known partial refund.
                db.update("update purchase_order set status='REVIEW_REQUIRED' where id=?",id);
                db.update("update entitlement set status='SUSPENDED' where order_id=? and status='ACTIVE'",id);
            }
            return db.order(id);
        }
        if("PAID".equals(payment.status())) {
            if(payment.paidAt()==null||payment.paidAt().isBefore(Store.instant(o.get("created_at")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))||payment.paidAt().isAfter(clock.instant().plusSeconds(60)))
                throw new ApiException(HttpStatus.BAD_GATEWAY,"INVALID_PROVIDER_PAYMENT_TIME");
            if(Set.of("REFUNDED","PARTIALLY_REFUNDED").contains(o.get("status"))) return o;
            db.update("update purchase_order set status='PAID',paid_at=coalesce(paid_at,?) where id=?",payment.paidAt(),id);
            if("ACTIVE".equals(member.get("status"))) for(String kind:kinds((String)o.get("product_code"))) {
                if(db.rows("select product_code from entitlement where order_id=? and product_code=?",id,kind).isEmpty())
                    db.update("insert into entitlement(order_id,product_code,status) values(?,?,'ACTIVE')",id,kind);
            }
            else {
                // Approval can arrive after the member has withdrawn. Do not resurrect deleted data.
                // Queue a non-provision refund review so the payment cannot silently remain orphaned.
                db.update("update purchase_order set status='REVIEW_REQUIRED' where id=?",id);
                if(refund.stream().noneMatch(r->"NOT_PROVIDED".equals(r.get("reason"))))
                    db.update("insert into refund_request(id,order_id,reason,status,amount,created_at) values(?,?,'NOT_PROVIDED','REQUESTED',?,?)",UUID.randomUUID(),id,Store.number(o,"amount"),clock.instant());
            }
        } else if(!"PAID".equals(o.get("status"))&&!Set.of("REFUNDED","PARTIALLY_REFUNDED","REVIEW_REQUIRED").contains(o.get("status"))) {
            String status=switch(payment.status()) {case "FAILED"->"FAILED";case "CANCELLED"->"CANCELLED";default->"PENDING";};
            db.update("update purchase_order set status=? where id=?",status,id);
        }
        return db.order(id);
    }
    boolean matches(Map<String,Object> order,PaymentGateway.Payment p) {
        return Objects.equals(order.get("payment_id"),p.id())&&Objects.equals(order.get("id").toString(),p.orderId())
            &&Store.number(order,"amount")==p.total()&&"KRW".equals(p.currency())&&store.equals(p.storeId());
    }
    @Transactional public Map<String,Object> get(UUID user,UUID id) {return db.owned(user,id);}
    @Transactional public List<Map<String,Object>> history(UUID user) {db.active(user);return db.rows("select id,product_code,amount,status,created_at,paid_at from purchase_order where member_id=? order by created_at desc",user);}
    public static Set<String> kinds(String product) {return "package".equals(product)?RefundPolicy.PRICES.keySet():Set.of(product);}
    static String hash(String value) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
