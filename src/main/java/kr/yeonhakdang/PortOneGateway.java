package kr.yeonhakdang;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.payment.provider",havingValue="portone")
public class PortOneGateway implements PaymentGateway {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json; private final String secret,store,baseUrl;
    @org.springframework.beans.factory.annotation.Autowired
    public PortOneGateway(ObjectMapper json,@Value("${app.portone.secret}") String secret,@Value("${app.portone.store-id}") String store) {
        this(json,secret,store,"https://api.portone.io");
    }
    PortOneGateway(ObjectMapper json,String secret,String store,String baseUrl) {
        this.json=json;this.secret=secret;this.store=store;this.baseUrl=baseUrl;
    }
    private JsonNode request(String id,Object body) {
        if(secret.isBlank()||store.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PORTONE_NOT_CONFIGURED");
        // All IDs originate at this server. Never accept a provider URL from a client.
        if(!id.matches("yhd-[a-f0-9-]{36}")) throw new IllegalArgumentException("paymentId");
        try {
            var builder=HttpRequest.newBuilder(URI.create(baseUrl+"/payments/"+id+(body==null?"?storeId="+java.net.URLEncoder.encode(store,java.nio.charset.StandardCharsets.UTF_8):"/cancel")))
                .timeout(Duration.ofSeconds(8)).header("Authorization","PortOne "+secret).header("Content-Type","application/json");
            if(body==null) builder.GET();else builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2) throw new ApiException(HttpStatus.BAD_GATEWAY,"PORTONE_UNAVAILABLE");
            return json.readTree(response.body());
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new ApiException(HttpStatus.BAD_GATEWAY,"PORTONE_UNAVAILABLE"); }
        catch(ApiException e) { throw e; }
        catch(Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"PORTONE_UNAVAILABLE"); }
    }
    @Override public Payment get(String paymentId) {
        var p=request(paymentId,null);
        String custom=p.path("customData").asText("");String order="";
        if(!custom.isBlank()) { try { order=json.readTree(custom).path("orderId").asText(""); } catch(Exception ignored) {} }
        var cancels=new ArrayList<Cancellation>();
        for(var c:p.path("cancellations")) cancels.add(new Cancellation(c.path("id").asText(),c.path("status").asText(),c.path("totalAmount").asInt(-1)));
        String paid=p.path("paidAt").asText("");
        return new Payment(p.path("id").asText(),p.path("status").asText(),p.path("amount").path("total").asInt(-1),
            p.path("amount").path("cancelled").asInt(0),p.path("currency").asText(),p.path("storeId").asText(),order,
            paid.isBlank()?null:Instant.parse(paid),cancels);
    }
    @Override public Cancellation cancel(String id,int amount,int remaining,String reason) {
        var c=request(id,Map.of("storeId",store,"amount",amount,"currentCancellableAmount",remaining,"reason",reason,"requester","ADMIN")).path("cancellation");
        return new Cancellation(c.path("id").asText(),c.path("status").asText(),c.path("totalAmount").asInt(-1));
    }
}
