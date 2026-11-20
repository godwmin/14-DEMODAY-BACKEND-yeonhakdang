package kr.yeonhakdang;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

/** Bounds JSON bodies too, including chunked requests with no Content-Length. */
@Component
public class BodyLimitFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        if(!req.getRequestURI().startsWith("/api/")||!java.util.Set.of("POST","PUT","PATCH").contains(req.getMethod())) {chain.doFilter(req,res);return;}
        // Read at most 64KiB + 1 byte, before any JSON parsing or signature validation.
        byte[] bytes=req.getInputStream().readNBytes(65537);
        if(bytes.length>65536) {SecurityConfig.json(res,413,"REQUEST_TOO_LARGE");return;}
        Map<String,String[]> parameters;
        try {parameters=formParameters(req,bytes);}
        catch(IllegalArgumentException|HttpMessageNotReadableException ex) {SecurityConfig.json(res,400,"INVALID_REQUEST");return;}
        chain.doFilter(new HttpServletRequestWrapper(req){
            @Override public Map<String,String[]> getParameterMap() {return parameters==null?super.getParameterMap():parameters;}
            @Override public String getParameter(String name) {
                if(parameters==null) return super.getParameter(name);
                String[] values=parameters.get(name);
                return values==null?null:values[0];
            }
            @Override public String[] getParameterValues(String name) {return parameters==null?super.getParameterValues(name):parameters.get(name);}
            @Override public Enumeration<String> getParameterNames() {return parameters==null?super.getParameterNames():Collections.enumeration(parameters.keySet());}
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(bytes);
                return new ServletInputStream(){
                    public int read(){return input.read();}
                    public int read(byte[] b,int off,int len){return input.read(b,off,len);}
                    public boolean isFinished(){return input.available()==0;}
                    public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },res);
    }

    private static Map<String,String[]> formParameters(HttpServletRequest req,byte[] bytes) throws IOException {
        String contentType=req.getContentType();
        if(!"POST".equals(req.getMethod())||contentType==null||
            !MediaType.APPLICATION_FORM_URLENCODED_VALUE.equalsIgnoreCase(contentType.split(";",2)[0].trim())) return null;

        // Tomcat will not parse a form after getInputStream() has been used. Spring's
        // @RequestBody handling reconstructs form POST bodies from these parameters.
        var input=new ServletServerHttpRequest(req){
            @Override public InputStream getBody() {return new ByteArrayInputStream(bytes);}
        };
        var form=new FormHttpMessageConverter().read(null,input);
        var parameters=new LinkedHashMap<String,String[]>();
        // The original request still supplies query parameters, which precede form values.
        req.getParameterMap().forEach((name,values)->parameters.put(name,values.clone()));
        form.forEach((name,values)->{
            String[] bodyValues=values.stream().map(value->value==null?"":value).toArray(String[]::new);
            parameters.merge(name,bodyValues,(queryValues,formValues)->{
                String[] combined=Arrays.copyOf(queryValues,queryValues.length+formValues.length);
                System.arraycopy(formValues,0,combined,queryValues.length,formValues.length);
                return combined;
            });
        });
        return Collections.unmodifiableMap(parameters);
    }
}
