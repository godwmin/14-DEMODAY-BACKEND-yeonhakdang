package kr.yeonhakdang;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name="app.payment.provider",havingValue="payapp",matchIfMissing=true)
public class PayAppController {
    private final PayAppGateway gateway;
    private final OrderService orders;
    public PayAppController(PayAppGateway gateway,OrderService orders) {this.gateway=gateway;this.orders=orders;}
    @PostMapping(value="/api/webhooks/payapp",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_PLAIN_VALUE)
    String feedback(@RequestBody String body) {
        UUID order=gateway.receive(PayAppGateway.decode(body));orders.sync(order);return "SUCCESS";
    }
    // Browser return carries no payment authority. A 303 converts provider POST to a frontend GET.
    @RequestMapping(value="/api/payments/payapp/return",method={RequestMethod.GET,RequestMethod.POST})
    ResponseEntity<Void> returned(@RequestParam UUID order) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(gateway.returnLocation(order)))
            .cacheControl(CacheControl.noStore()).build();
    }
}
