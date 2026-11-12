package kr.yeonhakdang;

import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class AccountLibraryController {
    public record Library(UUID accountId,List<Map<String,Object>> items,List<Map<String,Object>> orders) {}
    private final Store db;
    private final OrderService orders;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    public AccountLibraryController(Store db,OrderService orders,PaymentGateway gateway,PlatformTransactionManager tm) {
        this.db=db;this.orders=orders;this.gateway=gateway;this.tx=new TransactionTemplate(tm);
    }
    @GetMapping("/api/me/library")
    public Library get(Authentication auth) {
        UUID member=UUID.fromString(auth.getName());
        // PayApp's signed callback is stored before entitlement provisioning. Recover a
        // committed approval if the callback handler stopped between those transactions.
        if(gateway instanceof PayAppGateway) {
            var pending=tx.execute(s->{
                db.active(member);
                return db.rows("select o.id from purchase_order o join payapp_payment p on p.order_id=o.id where o.member_id=? and p.status='PAID' and p.cancelled=0 and (o.status in ('CREATED','READY','PENDING','FAILED') or (o.status='PAID' and not exists (select 1 from entitlement e where e.order_id=o.id)))",member);
            });
            for(var row:pending) {
                for(int attempt=0;;attempt++) {
                    try { orders.sync((UUID)row.get("id")); break; }
                    catch(ApiException e) { if(!"PAYMENT_STATE_CHANGED".equals(e.code)||attempt==2) throw e; }
                }
            }
        }
        return tx.execute(s->{
            db.active(member);
            return new Library(member,db.rows("select e.order_id,e.product_code,e.status,e.first_viewed_at,e.content_version,(case when e.content is null then false else true end) as content_ready from entitlement e join purchase_order o on o.id=e.order_id where o.member_id=? and e.status not in ('REFUNDED','REVOKED') order by o.created_at desc,o.id desc,e.product_code",member),db.rows("select id,product_code,amount,status,created_at,paid_at from purchase_order where member_id=? order by created_at desc,id desc",member));
        });
    }
}
