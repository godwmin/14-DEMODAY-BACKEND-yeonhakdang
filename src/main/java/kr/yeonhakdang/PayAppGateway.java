package kr.yeonhakdang;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** PayApp REST v2. Payment authority is the authenticated feedback, never the browser return. */
@Component
@ConditionalOnProperty(name="app.payment.provider",havingValue="payapp",matchIfMissing=true)
public class PayAppGateway implements PaymentGateway {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private final Store db;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String user,key,value,requestKey,origin,frontend;
    private final Set<String> methods;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private URI endpoint=URI.create("https://api.payapp.kr/oapi/v2");

    public PayAppGateway(Store db,Clock clock,PlatformTransactionManager tm,
        @Value("${app.payapp.user-id}") String user,@Value("${app.payapp.link-key}") String key,
        @Value("${app.payapp.link-value}") String value,@Value("${app.payapp.request-key}") String requestKey,
        @Value("${app.payapp.public-origin}") String origin,@Value("${app.frontend-origin}") String frontend,
        @Value("${app.payapp.methods}") String methods) {
        this.db=db;this.clock=clock;tx=new TransactionTemplate(tm);this.user=user;this.key=key;this.value=value;
        this.requestKey=requestKey;this.origin=origin.replaceAll("/+$","");this.frontend=frontend.replaceAll("/+$","");
        this.methods=new HashSet<>(Arrays.asList(methods.split(",")));
    }
    // Package-private for contract tests against a local server, never configurable by a browser.
    void endpoint(URI endpoint) {this.endpoint=endpoint;}
    private void configured() {
        if(user.isBlank()||key.isBlank()||value.isBlank()||requestKey.isBlank()||!origin.startsWith("https://"))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PAYAPP_NOT_CONFIGURED");
    }
    static Map<String,String> decode(String body) {
        var result=new LinkedHashMap<String,String>();
        for(String part:body.split("&")) {
            var pair=part.split("=",2);
            String name=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            if(result.putIfAbsent(name,pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"")!=null)
                throw new IllegalArgumentException("duplicate field");
        }
        return result;
    }
    private Map<String,String> request(Map<String,String> fields) {
        configured();
        var data=new LinkedHashMap<>(fields);
        String date=DATE.format(clock.instant().atZone(SEOUL));
        data.put("userid",user);data.put("linkkey",key);data.put("requestdate",date);
        data.put("hash",OrderService.hash(user+date+requestKey));
        String body=data.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        try {
            var req=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>65536) throw new IllegalStateException();
            var parsed=decode(response.body());
            if(!"1".equals(parsed.get("state"))) throw new ApiException(HttpStatus.BAD_GATEWAY,"PAYAPP_REQUEST_REJECTED");
            return parsed;
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ApiException(HttpStatus.BAD_GATEWAY,"PAYAPP_UNAVAILABLE");}
        catch(ApiException e) {throw e;}
        catch(Exception e) {throw new ApiException(HttpStatus.BAD_GATEWAY,"PAYAPP_UNAVAILABLE");}
    }
    static String paymentUrl(String raw) {
        URI uri=URI.create(raw);
        String host=uri.getHost();
        if(!"https".equals(uri.getScheme())||host==null||!(host.equals("payapp.kr")||host.endsWith(".payapp.kr"))||uri.getUserInfo()!=null||uri.getPort()!=-1)
            throw new ApiException(HttpStatus.BAD_GATEWAY,"INVALID_PAYMENT_URL");
        return uri.toString();
    }
    public Map<String,Object> prepare(UUID owner,UUID order,String method,String phone) {
        configured();
        if(method==null) throw new IllegalArgumentException("payment method");
        String payType=Map.of("CARD","card","KAKAO","kakaopay","TOSS","tosspay").get(method);
        if(payType==null||!methods.contains(method)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CHANNEL_NOT_CONFIGURED");
        String normalized=phone==null?"":phone.replaceAll("[ -]","");
        if(!normalized.matches("01[016789][0-9]{7,8}")) throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_PHONE_REQUIRED");
        var reservation=tx.execute(s->{
            var o=db.owned(owner,order);
            if(!Set.of("CREATED","READY","FAILED").contains(o.get("status"))) throw ApiException.conflict("ORDER_NOT_PAYABLE");
            // PayApp documents a 1,000 KRW minimum. Do not silently change catalog prices.
            if(Store.number(o,"amount")<1000) throw ApiException.conflict("PAYAPP_MINIMUM_AMOUNT");
            var old=db.rows("select * from payapp_payment where order_id=?",order);
            if(!old.isEmpty()) {
                if("READY".equals(old.getFirst().get("request_state"))) return old.getFirst();
                throw ApiException.conflict("PAYMENT_REQUEST_UNKNOWN");
            }
            String token=UUID.randomUUID().toString();
            db.update("insert into payapp_payment(payment_id,order_id,callback_token,request_state) values(?,?,?,'REQUESTING')",o.get("payment_id"),order,token);
            db.update("update purchase_order set status='READY' where id=?",order);
            var result=new HashMap<String,Object>(o);result.put("callback_token",token);return result;
        });
        if(reservation.get("pay_url")!=null) return Map.of("provider","PAYAPP","payUrl",reservation.get("pay_url"));
        try {
            String title=(String)db.one("select title from product where code=?",reservation.get("product_code")).get("title");
            var fields=new LinkedHashMap<String,String>();
            fields.put("cmd","payrequest");fields.put("goodname",title);fields.put("price",reservation.get("amount").toString());
            fields.put("recvphone",normalized);fields.put("smsuse","n");fields.put("checkretry","y");fields.put("openpaytype",payType);
            fields.put("var1",order.toString());fields.put("var2",reservation.get("callback_token").toString());
            fields.put("feedbackurl",origin+"/api/webhooks/payapp");fields.put("returnurl",origin+"/api/payments/payapp/return?order="+order);
            var response=request(fields);
            String mul=response.getOrDefault("mul_no","");
            if(!mul.matches("[0-9]{1,40}")) throw new IllegalArgumentException();
            String url=paymentUrl(response.getOrDefault("payurl",""));
            tx.executeWithoutResult(s->{db.owned(owner,order);
                db.update("update payapp_payment set mul_no=?,pay_url=?,request_state='READY' where order_id=?",mul,url,order);});
            return Map.of("provider","PAYAPP","payUrl",url);
        } catch(Exception e) {
            // A timeout may have created a real provider request. Never issue another automatically.
            db.update("update payapp_payment set request_state='UNKNOWN' where order_id=?",order);
            if(e instanceof ApiException a) throw a;
            throw new ApiException(HttpStatus.BAD_GATEWAY,"PAYAPP_UNAVAILABLE");
        }
    }
    @Override public Payment get(String paymentId) {
        var o=db.one("select o.id,o.amount,p.* from purchase_order o join payapp_payment p on p.order_id=o.id where o.payment_id=?",paymentId);
        var cancellations=db.rows("select cancellation_id,amount from payapp_cancellation where payment_id=?",paymentId).stream()
            .map(r->new Cancellation((String)r.get("cancellation_id"),"SUCCEEDED",Store.number(r,"amount"))).toList();
        return new Payment(paymentId,(String)o.get("status"),Store.number(o,"amount"),Store.number(o,"cancelled"),"KRW",user,
            o.get("order_id").toString(),Store.instant(o.get("paid_at")),cancellations);
    }
    @Override public Cancellation cancel(String paymentId,int amount,int remaining,String reason) {
        String intent="payapp-"+UUID.randomUUID();
        var p=tx.execute(s->{
            var row=db.one("select p.*,o.member_id from payapp_payment p join purchase_order o on o.id=p.order_id where p.payment_id=?",paymentId);
            db.member((UUID)row.get("member_id"));db.order((UUID)row.get("order_id"));
            var payment=get(paymentId);
            if(!Set.of("PAID","PARTIAL_CANCELLED").contains(payment.status())||payment.total()-payment.cancelled()!=remaining||amount<=0||amount>remaining)
                throw ApiException.conflict("PROVIDER_REVIEW_REQUIRED");
            if(!db.rows("select id from payapp_cancel_intent where payment_id=? and state='REQUESTED'",paymentId).isEmpty())
                throw ApiException.conflict("REFUND_ALREADY_PROCESSED_OR_UNKNOWN");
            db.update("insert into payapp_cancel_intent values(?,?,?,'REQUESTED')",intent,paymentId,amount);return row;
        });
        // paycancelreq also supports settled payments. Success means REQUESTED, not refunded.
        request(Map.of("cmd","paycancelreq","mul_no",p.get("mul_no").toString(),"cancelmemo",reason,
            "partcancel",amount==remaining?"0":"1","cancelprice",Integer.toString(amount)));
        return new Cancellation(intent,"REQUESTED",amount);
    }
    private static boolean same(String a,String b) {
        return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
    private static Instant date(String input) {return LocalDateTime.parse(input,DATE).atZone(SEOUL).toInstant();}
    public UUID receive(Map<String,String> event) {
        configured();
        try {
            String timestamp=event.get("responsedate");
            if(!same(user,event.get("userid"))||!same(key,event.get("linkkey"))||!same(value,event.get("linkval"))
                ||!same(OrderService.hash(user+timestamp+requestKey),event.getOrDefault("hash","").toLowerCase(Locale.ROOT))
                ||Math.abs(Duration.between(date(timestamp),clock.instant()).getSeconds())>900) throw new IllegalArgumentException();
        } catch(Exception e) {throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_WEBHOOK_SIGNATURE");}
        UUID order;
        int amount;
        try {order=UUID.fromString(event.get("var1"));amount=Integer.parseInt(event.get("price"));}
        catch(Exception e) {throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_NOTIFICATION");}
        final int price=amount;
        return tx.execute(s->{
            var owner=db.one("select member_id from purchase_order where id=?",order);db.member((UUID)owner.get("member_id"));
            var o=db.order(order);var p=db.one("select * from payapp_payment where order_id=? for update",order);
            String state=event.getOrDefault("pay_state",""),mul=event.getOrDefault("mul_no","");
            boolean partial=Set.of("70","71").contains(state);
            String original=partial?event.get("orig_mul_no"):mul;
            if(!same((String)p.get("callback_token"),event.get("var2"))||!mul.matches("[0-9]{1,40}")
                ||!Objects.equals(p.get("mul_no"),original)||price<=0||(!partial&&price!=Store.number(o,"amount")))
                throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_NOTIFICATION_MISMATCH");
            if(partial&&(!Integer.toString(Store.number(o,"amount")).equals(event.get("orig_price"))||price>Store.number(o,"amount")))
                throw new ApiException(HttpStatus.BAD_REQUEST,"PAYMENT_NOTIFICATION_MISMATCH");
            String receipt="payapp:"+OrderService.hash(order+":"+mul+":"+state);
            if(!db.rows("select id from webhook_receipt where id=?",receipt).isEmpty()) return order;
            String paymentId=(String)p.get("payment_id");
            if("4".equals(state)) {
                Instant paid;
                try {paid=date(event.get("pay_date"));}catch(Exception e){throw new IllegalArgumentException("payment time");}
                if(paid.isBefore(Store.instant(o.get("created_at")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))||paid.isAfter(clock.instant().plusSeconds(60)))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PROVIDER_PAYMENT_TIME");
                // A delayed paid notification must not revive a cancelled order.
                db.update("update payapp_payment set paid_at=coalesce(paid_at,?),status=case when cancelled=0 and status not in ('CANCELLED','REFUNDED','PARTIAL_CANCELLED') then 'PAID' else status end where order_id=?",paid,order);
            } else if(Set.of("9","64","70","71").contains(state)) {
                if(db.rows("select provider_id from payapp_cancellation where provider_id=?",mul).isEmpty()) {
                    int cancelled=Store.number(p,"cancelled");int delta=partial?price:Store.number(o,"amount")-cancelled;
                    if(delta<=0||cancelled+delta>Store.number(o,"amount")) throw ApiException.conflict("PROVIDER_REVIEW_REQUIRED");
                    var intents=db.rows("select id from payapp_cancel_intent where payment_id=? and amount=? and state='REQUESTED'",paymentId,delta);
                    String cid=intents.size()==1?(String)intents.getFirst().get("id"):"external-"+mul;
                    db.update("insert into payapp_cancellation values(?,?,?,?)",mul,paymentId,cid,delta);
                    db.update("update payapp_cancel_intent set state='SUCCEEDED' where id=?",cid);
                    // A provider callback may arrive before the cancellation HTTP response.
                    db.update("update refund_request set cancellation_id=? where order_id=? and status in ('PROCESSING','UNKNOWN') and amount=? and cancellation_id is null",cid,order,delta);
                    int total=cancelled+delta;
                    db.update("update payapp_payment set cancelled=?,status=? where order_id=?",total,total==Store.number(o,"amount")?"CANCELLED":"PARTIAL_CANCELLED",order);
                }
            } else if(Set.of("8","32").contains(state)) {
                db.update("update payapp_payment set status='CANCELLED' where order_id=? and paid_at is null and cancelled=0",order);
            } else if(!Set.of("1","10").contains(state)) throw new IllegalArgumentException("payment state");
            db.update("update purchase_order set sync_revision=sync_revision+1 where id=?",order);
            db.update("insert into webhook_receipt values(?,?)",receipt,clock.instant());return order;
        });
    }
    String returnLocation(UUID order) {return frontend+"/?checkoutOrder="+order;}
}
