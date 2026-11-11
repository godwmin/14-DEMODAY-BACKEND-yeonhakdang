package kr.yeonhakdang;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    public record ConsentInput(@NotBlank @Size(max=80) String version,boolean age14,boolean terms,boolean privacy,boolean marketing) {}
    public record Major(@NotBlank @Size(max=80) String id,@Min(0) @Max(100) int score) {}
    public record AnalysisInput(@NotBlank @Size(max=80) String engineVersion,
        @NotNull @Size(min=5,max=5) Map<String,@NotNull Integer> counts,
        @NotNull @Size(min=1,max=3) List<@Valid Major> top) {}
    public record OrderInput(@NotNull UUID resultId,@NotBlank @Size(max=20) String product,
        @NotNull @Size(max=6) List<@NotBlank @Size(max=80) String> universities,
        @NotNull @Size(max=20) String relation,@NotBlank @Size(max=80) String policyVersion,
        boolean orderConsent,boolean refundConsent) {}
    public record PrepareInput(@NotBlank String method,@Size(max=20) String phone) {}
    public record RefundInput(@NotBlank @Size(max=40) String reason) {}
    private final AccountService accounts;private final OrderService orders;private final ContentService content;private final RefundService refunds;
    private final Store db;private final String version;
    public ApiController(AccountService accounts,OrderService orders,ContentService content,RefundService refunds,Store db,@Value("${app.policy-version}") String version) {
        this.accounts=accounts;this.orders=orders;this.content=content;this.refunds=refunds;this.db=db;this.version=version;
    }
    static UUID user(Authentication a) {return UUID.fromString(a.getName());}
    @GetMapping("/csrf") Map<String,Object> csrf(HttpServletRequest req) {
        CsrfToken t=(CsrfToken)req.getAttribute(CsrfToken.class.getName());
        return Map.of("token",t.getToken(),"headerName",t.getHeaderName());
    }
    @GetMapping("/products") Object products() {return db.rows("select code,title,price from product order by price desc");}
    @GetMapping("/policies") Object policies() {return Map.of("version",version,"required",List.of("AGE14_SELF_DECLARED","TERMS","PRIVACY"),"optional",List.of("MARKETING"),"ageVerification","SELF_DECLARATION_ONLY");}
    @GetMapping("/me") Object me(Authentication a) {return accounts.me(user(a));}
    @PostMapping("/me/consents") void consent(Authentication a,@Valid @RequestBody ConsentInput input) {accounts.consent(user(a),input.version(),input.age14(),input.terms(),input.privacy(),input.marketing());}
    @DeleteMapping("/me") void withdraw(Authentication a,HttpServletRequest req) {accounts.withdraw(user(a));var s=req.getSession(false);if(s!=null)s.invalidate();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @PostMapping("/results") Object save(Authentication a,@Valid @RequestBody AnalysisInput input) {return content.save(user(a),input);}
    @GetMapping("/results") Object results(Authentication a) {return content.results(user(a));}
    @PostMapping("/orders") Object order(Authentication a,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OrderInput input) {return orders.create(user(a),key,input);}
    @GetMapping("/orders") Object history(Authentication a) {return orders.history(user(a));}
    @GetMapping("/orders/{id}") Object status(Authentication a,@PathVariable UUID id) {return orders.get(user(a),id);}
    @PostMapping("/orders/{id}/prepare") Object prepare(Authentication a,@PathVariable UUID id,@Valid @RequestBody PrepareInput input) {return orders.prepare(user(a),id,input.method(),input.phone());}
    @PostMapping("/orders/{id}/complete") Object complete(Authentication a,@PathVariable UUID id) {return orders.complete(user(a),id);}
    @GetMapping("/locker") Object locker(Authentication a) {return content.locker(user(a));}
    @PostMapping("/orders/{id}/products/{product}/open") Object open(Authentication a,@PathVariable UUID id,@PathVariable String product) {return content.open(user(a),id,product);}
    @PostMapping("/orders/{id}/refunds") Object refund(Authentication a,@PathVariable UUID id,@Valid @RequestBody RefundInput input) {return refunds.request(user(a),id,input.reason());}
    @GetMapping("/orders/{id}/refunds") Object refundStatus(Authentication a,@PathVariable UUID id) {return refunds.get(user(a),id);}
}
