package kr.yeonhakdang;

import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;

@RestController
public class InvitationController {
    public record Link(UUID token,UUID accountId) {}
    public record Roster(UUID accountId,List<Map<String,Object>> friends) {}
    public record Accepted(UUID accountId,boolean connected,List<Map<String,Object>> friends) {}
    private final Store db;
    private final AccountService accounts;
    private final ObjectMapper json;
    private final Clock clock;
    public InvitationController(Store db,AccountService accounts,ObjectMapper json,Clock clock) {
        this.db=db;this.accounts=accounts;this.json=json;this.clock=clock;
    }
    private AnalysisProfileController.Profile profile(UUID member) {
        var rows=db.rows("select p.profile from account_analysis_profile p join member_account m on m.id=p.member_id where p.member_id=? and m.status='ACTIVE'",member);
        if(rows.isEmpty()) throw ApiException.conflict("ANALYSIS_REQUIRED");
        var profile=json.readValue((String)rows.getFirst().get("profile"),AnalysisProfileController.Profile.class);
        if(!profile.analyzed()) throw ApiException.conflict("ANALYSIS_REQUIRED");
        return profile;
    }
    private Map<String,Object> friend(UUID member,long addedAt) {
        var p=profile(member);
        return Map.of("memberId",member,"name",p.name(),"birth",p.birth(),"calendar",p.calendar(),
            "leapMonth",p.leapMonth(),"time",p.time(),"unknownTime",p.unknownTime(),"addedAt",addedAt);
    }
    @PostMapping("/api/invitations") @Transactional
    public Link create(Authentication auth) {
        UUID member=UUID.fromString(auth.getName());
        db.active(member);accounts.requireConsent(member);profile(member);
        var links=db.rows("select token from member_invitation where member_id=? order by created_at,token",member);
        if(!links.isEmpty()) return new Link((UUID)links.getFirst().get("token"),member);
        UUID token=UUID.randomUUID();
        db.update("insert into member_invitation(token,member_id,created_at) values(?,?,?)",token,member,clock.instant());
        return new Link(token,member);
    }
    private UUID inviter(UUID token) {
        var rows=db.rows("select i.member_id from member_invitation i join member_account m on m.id=i.member_id where i.token=? and m.status='ACTIVE'",token);
        if(rows.isEmpty()) throw ApiException.missing();
        return (UUID)rows.getFirst().get("member_id");
    }
    @GetMapping("/api/invitations/{token}") @Transactional(readOnly=true)
    public Map<String,Object> preview(@PathVariable UUID token) {
        // A bearer invitation exposes only the information shared by the inviter.
        var shared=new HashMap<>(friend(inviter(token),0));
        shared.remove("memberId");
        shared.remove("addedAt");
        return shared;
    }
    @GetMapping("/api/me/friends") @Transactional
    public Roster roster(Authentication auth) {
        UUID member=UUID.fromString(auth.getName());
        db.active(member);accounts.requireConsent(member);
        return new Roster(member,roster(member));
    }
    private List<Map<String,Object>> roster(UUID member) {
        var rows=db.rows("select case when f.member_a=? then f.member_b else f.member_a end as friend_id,f.created_at from member_friendship f join member_account a on a.id=f.member_a join member_account b on b.id=f.member_b join account_analysis_profile pa on pa.member_id=f.member_a join account_analysis_profile pb on pb.member_id=f.member_b where (f.member_a=? or f.member_b=?) and a.status='ACTIVE' and b.status='ACTIVE' order by f.created_at,f.member_a,f.member_b",member,member,member);
        return rows.stream().map(row->friend((UUID)row.get("friend_id"),Store.instant(row.get("created_at")).toEpochMilli())).toList();
    }
    @PostMapping("/api/invitations/{token}/accept") @Transactional
    public Accepted accept(Authentication auth,@PathVariable UUID token) {
        UUID member=UUID.fromString(auth.getName());
        UUID sender=inviter(token);
        if(member.equals(sender)) return new Accepted(member,false,roster(auth).friends());
        // Both directions share one row. All accepts lock the two accounts in the same order.
        UUID first=member.toString().compareTo(sender.toString())<0?member:sender;
        UUID second=first.equals(member)?sender:member;
        for(UUID id:List.of(first,second)) {
            if(!"ACTIVE".equals(db.member(id).get("status"))) {
                if(id.equals(member)) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"ACCOUNT_INACTIVE");
                throw ApiException.missing();
            }
        }
        accounts.requireConsent(member);profile(member);profile(sender);
        if(db.rows("select member_a from member_friendship where member_a=? and member_b=?",first,second).isEmpty())
            db.update("insert into member_friendship(member_a,member_b,created_at) values(?,?,?)",first,second,clock.instant());
        return new Accepted(member,true,roster(member));
    }
}
