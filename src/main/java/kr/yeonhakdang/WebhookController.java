package kr.yeonhakdang;

import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.payment.provider",havingValue="portone")
public class WebhookController {
    private final WebhookService service;
    public WebhookController(WebhookService service) {this.service=service;}
    @PostMapping(value="/api/webhooks/portone",consumes="application/json")
    void webhook(@RequestHeader(value="webhook-id",required=false) String id,
        @RequestHeader(value="webhook-timestamp",required=false) String timestamp,
        @RequestHeader(value="webhook-signature",required=false) String signature,@RequestBody String body) {
        service.receive(id,timestamp,signature,body);
    }
}
