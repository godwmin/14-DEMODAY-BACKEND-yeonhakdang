package kr.yeonhakdang;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/ai/saju-explanation")
public class SajuExplanationController {
    public record Major(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String id,
        @NotBlank @Pattern(regexp="[가-힣A-Za-z0-9· /()&.,_-]{1,80}") String label,@Min(0) @Max(100) int score) {}
    public record Input(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,80}") String engineVersion,
        @NotNull @Size(min=3,max=4) List<@NotNull @Pattern(regexp="[갑을병정무기경신임계][자축인묘진사오미신유술해]") String> pillars,
        @NotNull @Pattern(regexp="[갑을병정무기경신임계]") String dayMaster,
        @NotNull @Size(min=5,max=5) Map<String,@NotNull @Min(0) @Max(8) Integer> counts,
        @NotNull @Size(min=3,max=3) List<@NotNull @Valid Major> top) {}
    public record Explanation(String status,List<String> paragraphs,String failureCode) {
        public Explanation(String status,List<String> paragraphs) {this(status,paragraphs,null);}
    }
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SajuExplanationController.class);
    private final Store db;private final AccountService accounts;private final LinerClient liner;
    private final ObjectMapper json;private final Clock clock;private final TransactionTemplate tx;private final int dailyLimit;
    public SajuExplanationController(Store db,AccountService accounts,LinerClient liner,ObjectMapper json,Clock clock,PlatformTransactionManager tm,@Value("${LINER_DAILY_LIMIT:200}") int dailyLimit) {
        this.db=db;this.accounts=accounts;this.liner=liner;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(tm);this.dailyLimit=dailyLimit;
    }
    private Explanation status(String value) {return new Explanation(value,List.of());}
    @PostMapping
    public Explanation explain(@Valid @RequestBody Input input,@RequestParam(defaultValue="false") boolean generate,Authentication auth,HttpServletRequest request) {
        if(!input.counts().keySet().equals(Set.of("wood","fire","earth","metal","water"))
            ||input.counts().values().stream().mapToInt(Integer::intValue).sum()!=input.pillars().size()*2
            ||!input.dayMaster().equals(input.pillars().get(2).substring(0,1))) throw new IllegalArgumentException("analysis");
        boolean member=auth!=null&&auth.isAuthenticated()&&!"anonymousUser".equals(auth.getName());
        String owner=member?"member:"+auth.getName():"guest:"+request.getSession().getId();
        String ownerHash=OrderService.hash(owner);
        // Canonicalize the map: JS clients can send the same counts in a different key order.
        Input canonical=new Input(input.engineVersion(),input.pillars(),input.dayMaster(),new TreeMap<>(input.counts()),input.top());
        String key=OrderService.hash("saju-explanation-v1:"+liner.model()+":"+json.writeValueAsString(canonical));
        Instant now=clock.instant();
        var reservation=tx.execute(s->{
            if(member) {UUID id=UUID.fromString(auth.getName());db.active(id);accounts.requireConsent(id);}
            // A single short-lived lock serializes reservations, never the network request.
            var budget=db.one("select * from ai_generation_budget where id=1 for update");
            var cached=db.rows("select * from ai_explanation where cache_key=?",key);
            if(!cached.isEmpty()) {
                var row=cached.getFirst();
                if("READY".equals(row.get("state"))) return new Explanation("READY",Arrays.asList(json.readValue((String)row.get("paragraphs"),String[].class)));
                var age=Duration.between(Store.instant(row.get("updated_at")),now).getSeconds();
                if("PENDING".equals(row.get("state"))&&age<45) return status("PENDING");
                if("FAILED".equals(row.get("state"))&&age<120) return new Explanation("UNAVAILABLE",List.of(),(String)row.get("failure_code"));
            }
            if(!liner.configured()) return status("DISABLED");
            if(!generate) return status("NOT_GENERATED");
            String day=now.atZone(ZoneOffset.UTC).toLocalDate().toString();
            String bucket=now.toString().substring(0,13);
            int used=day.equals(budget.get("budget_day"))?Store.number(budget,"used"):0;
            var quotas=db.rows("select * from ai_generation_quota where owner_hash=?",ownerHash);
            int hourly=!quotas.isEmpty()&&bucket.equals(quotas.getFirst().get("bucket"))?Store.number(quotas.getFirst(),"used"):0;
            if(used>=dailyLimit||hourly>=5) return status("LIMITED");
            db.update("update ai_generation_budget set budget_day=?,used=? where id=1",day,used+1);
            if(quotas.isEmpty()) db.update("insert into ai_generation_quota values(?,?,?)",ownerHash,bucket,1);
            else db.update("update ai_generation_quota set bucket=?,used=? where owner_hash=?",bucket,hourly+1,ownerHash);
            db.update("delete from ai_generation_quota where bucket<?",now.minus(Duration.ofDays(1)).toString().substring(0,13));
            if(cached.isEmpty()) db.update("insert into ai_explanation(cache_key,state,updated_at) values(?,'PENDING',?)",key,now);
            else db.update("update ai_explanation set state='PENDING',failure_code=null,updated_at=? where cache_key=?",now,key);
            return status("GENERATE");
        });
        if(!"GENERATE".equals(reservation.status())) return reservation;
        try {
            var paragraphs=liner.explain(canonical);
            tx.executeWithoutResult(s->db.update("update ai_explanation set state='READY',failure_code=null,paragraphs=?,updated_at=? where cache_key=?",json.writeValueAsString(paragraphs),clock.instant(),key));
            return new Explanation("READY",paragraphs);
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            String failureCode=e instanceof LinerClient.Failure f?f.code:"AI_INTERNAL_ERROR";
            int httpStatus=e instanceof LinerClient.Failure f?f.httpStatus:0;
            String providerCode=e instanceof LinerClient.Failure f?f.providerCode:"";
            // Fixed codes only: do not print the response body, prompt, key or exception message.
            log.warn("Liner explanation unavailable failureCode={} httpStatus={} providerCode={}",failureCode,httpStatus,providerCode);
            tx.executeWithoutResult(s->db.update("update ai_explanation set state='FAILED',failure_code=?,updated_at=? where cache_key=?",failureCode,clock.instant(),key));
            return new Explanation("UNAVAILABLE",List.of(),failureCode);
        }
    }
}
