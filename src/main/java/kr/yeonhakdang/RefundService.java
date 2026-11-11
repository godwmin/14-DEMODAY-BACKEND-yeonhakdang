package kr.yeonhakdang;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.*;

@Service
public class RefundService {
    public static final Set<String> REASONS=Set.of("CHANGE_OF_MIND","INPUT_ERROR","NOT_PROVIDED","DUPLICATE_PAYMENT","SYSTEM_FAILURE","NOT_AS_DESCRIBED","MINOR_WITHOUT_CONSENT");
    private final Store db;private final Clock clock;private final PaymentGateway gateway;private final OrderService orders;private final ContentService content;
    private final TransactionTemplate tx;
    public RefundService(Store db,Clock clock,PaymentGateway gateway,OrderService orders,ContentService content,PlatformTransactionManager tm) {
        this.db=db;this.clock=clock;this.gateway=gateway;this.orders=orders;this.content=content;tx=new TransactionTemplate(tm);
    }
    public Map<String,Object> request(UUID user,UUID order,String reason) {
        return tx.execute(s->{
            var o=db.owned(user,order);
            if(!REASONS.contains(reason)) throw new IllegalArgumentException("reason");
            var old=db.rows("select * from refund_request where order_id=? order by created_at desc",order);
            var active=old.stream().filter(r->!Set.of("SUCCEEDED","REJECTED").contains(r.get("status"))).findFirst();
            if(active.isPresent()) return active.get();
            if(!old.isEmpty()&&reason.equals(old.getFirst().get("reason"))&&"SUCCEEDED".equals(old.getFirst().get("status"))) return old.getFirst();
            if(!Set.of("PAID","PARTIALLY_REFUNDED").contains(o.get("status"))) throw ApiException.conflict("ORDER_NOT_PAID");
            boolean normal=Set.of("CHANGE_OF_MIND","INPUT_ERROR").contains(reason);
            Set<String> unopened=new HashSet<>();
            for(var e:db.rows("select product_code,first_viewed_at,status from entitlement where order_id=?",order))
                if(e.get("first_viewed_at")==null&&"ACTIVE".equals(e.get("status"))) unopened.add((String)e.get("product_code"));
            int alreadyRefunded=old.stream().filter(r->"SUCCEEDED".equals(r.get("status"))).mapToInt(r->Store.number(r,"amount")).sum();
            int amount=Store.number(o,"amount")-alreadyRefunded;
            if(amount<=0) throw ApiException.conflict("NOTHING_TO_REFUND");
            if(normal) {
                if(!RefundPolicy.inWindow(Store.instant(o.get("paid_at")),clock.instant())) throw ApiException.conflict("REFUND_WINDOW_EXPIRED");
                amount="package".equals(o.get("product_code"))?RefundPolicy.packageAmount(unopened):unopened.isEmpty()?0:amount;
                if(amount==0) throw ApiException.conflict("ALREADY_VIEWED");
            }
            UUID id=UUID.randomUUID();
            db.update("insert into refund_request(id,order_id,reason,status,amount,created_at) values(?,?,?,'REQUESTED',?,?)",id,order,reason,amount,clock.instant());
            // Exception claims are reviewed by an operator; users never decide eligibility or refund amounts.
            if(normal) for(String p:unopened) db.update("update entitlement set status='REFUND_PENDING' where order_id=? and product_code=?",order,p);
            else db.update("update entitlement set status='REFUND_PENDING' where order_id=? and status='ACTIVE'",order);
            return db.one("select * from refund_request where id=?",id);
        });
    }
    public Map<String,Object> get(UUID user,UUID order) {return tx.execute(s->{db.owned(user,order);return db.one("select * from refund_request where order_id=? order by created_at desc",order);});}
    private Map<String,Object> locked(UUID admin,UUID id) {
        content.requireAdmin(admin);
        var r=db.one("select * from refund_request where id=?",id);
        UUID order=(UUID)r.get("order_id");UUID user=(UUID)db.one("select member_id from purchase_order where id=?",order).get("member_id");
        db.member(user);db.order(order);return db.one("select * from refund_request where id=? for update",id);
    }
    public void reject(UUID admin,UUID id) {
        tx.executeWithoutResult(s->{var r=locked(admin,id);
            if(!"REQUESTED".equals(r.get("status"))) throw ApiException.conflict("REFUND_NOT_REVIEWABLE");
            db.update("update refund_request set status='REJECTED',reviewed_by=?,reviewed_at=? where id=?",admin,clock.instant(),id);
            db.update("update entitlement set status='ACTIVE' where order_id=? and status='REFUND_PENDING'",r.get("order_id"));
        });
    }
    private PaymentGateway.Payment paymentFor(UUID admin,UUID id) {
        content.requireAdmin(admin);
        var r=db.one("select order_id from refund_request where id=?",id);
        return orders.fetch((UUID)r.get("order_id")).payment();
    }
    public Map<String,Object> process(UUID admin,UUID id) {
        var p=paymentFor(admin,id);
        var intent=tx.execute(s->{var r=locked(admin,id);
            if(!"REQUESTED".equals(r.get("status"))) throw ApiException.conflict("REFUND_ALREADY_PROCESSED_OR_UNKNOWN");
            var o=db.order((UUID)r.get("order_id"));
            int known=db.rows("select amount from refund_request where order_id=? and status='SUCCEEDED'",r.get("order_id")).stream().mapToInt(x->Store.number(x,"amount")).sum();
            if(!orders.matches(o,p)||!Set.of("PAID","PARTIAL_CANCELLED").contains(p.status())||p.cancelled()!=known) throw ApiException.conflict("PROVIDER_REVIEW_REQUIRED");
            db.update("update refund_request set status='PROCESSING',reviewed_by=?,reviewed_at=?,baseline_cancelled=? where id=?",admin,clock.instant(),p.cancelled(),id);
            return Map.of("paymentId",o.get("payment_id"),"amount",r.get("amount"),"remaining",Store.number(o,"amount")-p.cancelled(),"reason",r.get("reason"),"order",r.get("order_id"));
        });
        // Durable reservation above commits BEFORE the irreversible provider call. Never retry POST automatically.
        try {
            var cancellation=gateway.cancel((String)intent.get("paymentId"),(int)intent.get("amount"),(int)intent.get("remaining"),(String)intent.get("reason"));
            tx.executeWithoutResult(s->{locked(admin,id);db.update("update refund_request set cancellation_id=?,status=? where id=? and status<>'SUCCEEDED'",cancellation.id(),"FAILED".equals(cancellation.status())?"UNKNOWN":"PROCESSING",id);});
            orders.sync((UUID)intent.get("order"));
        } catch(Exception e) {
            tx.executeWithoutResult(s->{locked(admin,id);db.update("update refund_request set status='UNKNOWN' where id=? and status<>'SUCCEEDED'",id);});
        }
        return db.one("select * from refund_request where id=?",id);
    }
    public Map<String,Object> reconcile(UUID admin,UUID id,String cancellationId) {
        var p=paymentFor(admin,id);
        UUID order=tx.execute(s->{var r=locked(admin,id);
            if(!Set.of("REQUESTED","PROCESSING","UNKNOWN").contains(r.get("status"))) throw ApiException.conflict("REFUND_NOT_PENDING");
            var o=db.order((UUID)r.get("order_id"));
            if(!orders.matches(o,p)) throw ApiException.conflict("PROVIDER_REVIEW_REQUIRED");
            var c=p.cancellations().stream().filter(x->x.id().equals(cancellationId)&&x.amount()==Store.number(r,"amount")).findFirst().orElseThrow(()->ApiException.conflict("CANCELLATION_NOT_FOUND"));
            if(!Set.of("SUCCEEDED","REQUESTED").contains(c.status())) throw ApiException.conflict("CANCELLATION_NOT_CONFIRMED");
            if(!db.rows("select id from refund_request where cancellation_id=? and id<>?",c.id(),id).isEmpty()) throw ApiException.conflict("CANCELLATION_ALREADY_USED");
            db.update("update refund_request set cancellation_id=?,status='PROCESSING',reviewed_by=?,reviewed_at=? where id=?",c.id(),admin,clock.instant(),id);
            return (UUID)r.get("order_id");
        });
        orders.sync(order);return db.one("select * from refund_request where id=?",id);
    }
}
