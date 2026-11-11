package kr.yeonhakdang;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.Clock;
import java.util.*;

@Service
public class AccountService {
    private final Store db; private final Clock clock; private final String version;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    public AccountService(Store db,Clock clock,@Value("${app.policy-version}") String version,org.springframework.transaction.PlatformTransactionManager tm) { this.db=db;this.clock=clock;this.version=version;this.tx=new org.springframework.transaction.support.TransactionTemplate(tm); }
    public UUID login(String kakaoId) {
        try {
            return tx.execute(s->{
                var existing=db.rows("select id from member_account where kakao_id=?",kakaoId);
                if(!existing.isEmpty()) return (UUID)existing.getFirst().get("id");
                UUID id=UUID.randomUUID();
                db.update("insert into member_account(id,kakao_id,status,created_at) values(?,?,'ACTIVE',?)",id,kakaoId,clock.instant());
                return id;
            });
        } catch(org.springframework.dao.DuplicateKeyException race) {
            // Retry the read in a NEW transaction; a PostgreSQL constraint error aborts the old one.
            return tx.execute(s->(UUID)db.one("select id from member_account where kakao_id=?",kakaoId).get("id"));
        }
    }
    @Transactional
    public void consent(UUID user, String suppliedVersion, boolean age14,boolean terms,boolean privacy,boolean marketing) {
        db.active(user);
        if(!version.equals(suppliedVersion)||!age14||!terms||!privacy) throw ApiException.conflict("REQUIRED_CONSENT");
        var items=Map.of("AGE14_SELF_DECLARED",age14,"TERMS",terms,"PRIVACY",privacy,"MARKETING",marketing);
        items.forEach((item,accepted)->db.update("insert into consent values(?,?,?,?,?,?)",UUID.randomUUID(),user,item,version,accepted,clock.instant()));
    }
    public void requireConsent(UUID user) {
        for(String item:List.of("AGE14_SELF_DECLARED","TERMS","PRIVACY")) {
            var c=db.rows("select accepted from consent where member_id=? and item=? and version=? order by recorded_at desc",user,item,version);
            if(c.isEmpty()||!Boolean.TRUE.equals(c.getFirst().get("accepted"))) throw ApiException.conflict("REQUIRED_CONSENT");
        }
    }
    @Transactional
    public Map<String,Object> me(UUID user) {
        db.active(user);return Map.of("id",user,"policyVersion",version,"consents",db.rows("select item,version,accepted,recorded_at from consent where member_id=? order by recorded_at",user));
    }
    @Transactional
    public void withdraw(UUID user) {
        db.active(user);
        // Pending refunds remain in the transaction records and can still be processed by operators.
        // A refund must not prevent deletion of the account's service data.
        db.update("update entitlement set content=null,content_version=null,status='REVOKED' where order_id in (select id from purchase_order where member_id=?)",user);
        db.update("delete from order_result_snapshot where order_id in (select id from purchase_order where member_id=?)",user);
        db.update("delete from member_friendship where member_a=? or member_b=?",user,user);
        db.update("delete from member_invitation where member_id=?",user);
        db.update("delete from account_analysis_profile where member_id=?",user);
        db.update("delete from analysis_result where member_id=?",user);
        db.update("delete from consent where member_id=?",user);
        db.update("update purchase_order set universities=null,relation=null where member_id=?",user);
        db.update("update member_account set kakao_id=null,status='WITHDRAWN',role='USER',withdrawn_at=? where id=?",clock.instant(),user);
        db.update("delete from SPRING_SESSION where PRINCIPAL_NAME=?",user.toString());
    }
}
