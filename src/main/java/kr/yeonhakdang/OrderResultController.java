package kr.yeonhakdang;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;

/** Immutable display snapshots. Payment and entitlement checks always use server records. */
@RestController
@RequestMapping("/api/orders/{orderId}")
public class OrderResultController {
    public record Input(@NotBlank @Size(max=10) String name,
        @NotNull @Pattern(regexp="[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}") String birth,
        @Min(0) @Max(1) int gender,@Min(0) @Max(1) int calendar,boolean leapMonth,
        @NotNull @Size(max=30) String time,boolean unknownTime) {}
    public record Snapshot(@Min(1) @Max(1) int version,@NotNull @Valid Input input,
        @NotNull @Size(max=6) List<String> universities,@NotNull @Size(max=20) String relation,
        @NotNull Map<String,JsonNode> results,@NotNull @Size(max=4) List<JsonNode> charmVariants) {}
    public record Redraw(@Min(0) @Max(3) int expectedVariant) {}
    private final Store db;private final ObjectMapper json;private final Clock clock;
    public OrderResultController(Store db,ObjectMapper json,Clock clock) {this.db=db;this.json=json;this.clock=clock;}
    private UUID user(Authentication a) {return UUID.fromString(a.getName());}
    private Set<String> kinds(Map<String,Object> order) {return OrderService.kinds((String)order.get("product_code"));}
    private void validResult(String kind,JsonNode value) {
        if(value==null||!value.isObject()) throw new IllegalArgumentException("result");
        var arrays=switch(kind) {
            case "compat" -> List.of("universities","months","weeks","chips");
            case "benefactor" -> List.of("tags","hints","timeline");
            case "charm" -> List.of("tags");
            default -> throw new IllegalArgumentException("product");
        };
        for(String key:arrays) if(!value.path(key).isArray()) throw new IllegalArgumentException("result arrays");
        if(kind.equals("compat")&&value.path("universities").isEmpty()) throw new IllegalArgumentException("universities");
        if(kind.equals("charm")&&!value.path("art").isObject()) throw new IllegalArgumentException("art");
    }
    @PutMapping("/result-snapshot") @Transactional
    public void save(Authentication a,@PathVariable UUID orderId,@Valid @RequestBody Snapshot snapshot) {
        var order=db.owned(user(a),orderId);
        // Exact retries are safe; an existing purchase snapshot can never be replaced.
        String payload=json.writeValueAsString(snapshot);
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000) throw new IllegalArgumentException("size");
        var existing=db.rows("select snapshot from order_result_snapshot where order_id=?",orderId);
        if(!existing.isEmpty()) {
            if(!json.readTree((String)existing.getFirst().get("snapshot")).equals(json.readTree(payload))) throw ApiException.conflict("CONTENT_IMMUTABLE");
            return;
        }
        if(!Set.of("CREATED","READY","FAILED").contains(order.get("status"))) throw ApiException.conflict("ORDER_NOT_PAYABLE");
        if(!snapshot.results().keySet().equals(kinds(order))) throw new IllegalArgumentException("products");
        for(var kind:kinds(order)) validResult(kind,snapshot.results().get(kind));
        if(!json.readTree((String)order.get("universities")).equals(json.valueToTree(snapshot.universities()))||!Objects.equals(order.get("relation"),snapshot.relation())) throw new IllegalArgumentException("choices");
        boolean charm=kinds(order).contains("charm");
        if(snapshot.charmVariants().size()!=(charm?4:0)) throw new IllegalArgumentException("variants");
        for(var v:snapshot.charmVariants()) validResult("charm",v);
        if(charm&&!snapshot.results().get("charm").equals(snapshot.charmVariants().getFirst())) throw new IllegalArgumentException("first variant");
        db.update("insert into order_result_snapshot(order_id,snapshot,created_at) values(?,?,?)",orderId,payload,clock.instant());
    }
    private Map<String,Object> paid(UUID user,UUID id,String kind) {
        var order=db.owned(user,id);
        if(!Set.of("PAID","PARTIALLY_REFUNDED").contains(order.get("status"))) throw ApiException.conflict("ORDER_NOT_ACCESSIBLE");
        if(!kinds(order).contains(kind)) throw ApiException.missing();
        var entitlement=db.one("select * from entitlement where order_id=? and product_code=?",id,kind);
        if(!"ACTIVE".equals(entitlement.get("status"))) throw ApiException.conflict("ENTITLEMENT_NOT_ACTIVE");
        return entitlement;
    }
    private Map<String,Object> response(UUID id,String kind) {
        var rows=db.rows("select snapshot,charm_variant from order_result_snapshot where order_id=?",id);
        if(rows.isEmpty()) throw ApiException.conflict("SAVED_RESULT_MISSING");
        var row=rows.getFirst();var snapshot=json.readValue((String)row.get("snapshot"),Snapshot.class);
        int variant=((Number)row.get("charm_variant")).intValue();
        var result=kind.equals("charm")?snapshot.charmVariants().get(variant):snapshot.results().get(kind);
        validResult(kind,result);
        return Map.of("orderId",id,"kind",kind,"version",snapshot.version(),"input",snapshot.input(),
            "relation",snapshot.relation(),"result",result,"charmRerolls",3-variant);
    }
    @PostMapping("/saved-results/{kind}/open") @Transactional
    public Map<String,Object> open(Authentication a,@PathVariable UUID orderId,@PathVariable String kind) {
        paid(user(a),orderId,kind);var result=response(orderId,kind);
        db.update("update entitlement set first_viewed_at=coalesce(first_viewed_at,?) where order_id=? and product_code=?",clock.instant(),orderId,kind);
        return result;
    }
    @PostMapping("/saved-results/charm/redraw") @Transactional
    public Map<String,Object> redraw(Authentication a,@PathVariable UUID orderId,@Valid @RequestBody Redraw input) {
        paid(user(a),orderId,"charm");response(orderId,"charm");
        int current=Store.number(db.one("select charm_variant from order_result_snapshot where order_id=?",orderId),"charm_variant");
        if(current!=input.expectedVariant()) throw ApiException.conflict("RESULT_CHANGED");
        if(current>=3) throw ApiException.conflict("NO_REDRAWS_LEFT");
        db.update("update order_result_snapshot set charm_variant=charm_variant+1 where order_id=?",orderId);
        return response(orderId,"charm");
    }
}
