package kr.yeonhakdang;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP is required: MockMvc does not reproduce Tomcat's consumed form stream. */
class BodyLimitFilterHttpTest {
    @TempDir(factory = BuildTempDirFactory.class) static Path directory;
    static Tomcat tomcat;
    static URI base;
    static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static class BuildTempDirFactory implements TempDirFactory {
        @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws Exception {
            Path parent = Files.createDirectories(Path.of("build", "tmp", "body-limit-http").toAbsolutePath());
            return Files.createTempDirectory(parent, "tomcat-");
        }
    }

    @BeforeAll static void start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setMaxPostSize(65536);
        var context = tomcat.addContext("", directory.toString());
        var filter = new FilterDef();
        filter.setFilterName("bodyLimit");
        filter.setFilter(new BodyLimitFilter());
        context.addFilterDef(filter);
        var mapping = new FilterMap();
        mapping.setFilterName("bodyLimit");
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);
        var spring = new AnnotationConfigWebApplicationContext();
        spring.register(WebConfig.class);
        Tomcat.addServlet(context, "mvc", new DispatcherServlet(spring));
        context.addServletMappingDecoded("/", "mvc");
        tomcat.start();
        base = URI.create("http://localhost:" + tomcat.getConnector().getLocalPort());
    }

    @AfterAll static void stop() throws Exception {
        if (tomcat != null) {
            try { tomcat.stop(); }
            finally { tomcat.destroy(); }
        }
    }

    @Test void formPostRemainsReadableByRequestBody() throws Exception {
        var response = post("/api/test/body", "application/x-www-form-urlencoded; charset=UTF-8",
            "userid=test-merchant&pay_state=4&price=1900&mul_no=123456&var1=test-order", false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("pay_state=4", "price=1900", "mul_no=123456", "var1=test-order");
    }

    @Test void chunkedFormPostRemainsReadableByRequestBody() throws Exception {
        var response = post("/api/test/body", "application/x-www-form-urlencoded",
            "pay_state=4&price=1900&mul_no=123456", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("pay_state=4&price=1900&mul_no=123456");
    }

    @Test void formParameterApisPreserveEncodingRepeatedAndEmptyValues() throws Exception {
        var response = post("/api/test/parameters", "application/x-www-form-urlencoded; charset=UTF-8",
            "goodname=%EA%B7%80%EC%9D%B8+%2B%26%3D&tag=first&tag=second&blank=&bare", false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("귀인 +&=|first|first,second|first,second|bare,blank,goodname,tag|||true");
    }

    @Test void queryAndFormParametersKeepAllValuesWithQueryFirst() throws Exception {
        var response = post("/api/test/parameters?tag=query&source=callback", "application/x-www-form-urlencoded",
            "goodname=report&tag=first&tag=second&blank=&bare", false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("report|query|query,first,second|query,first,second|bare,blank,goodname,source,tag|||true");
    }

    @Test void parameterAccessAlsoPreservesRawInputStreamAndReader() throws Exception {
        String body = "goodname=%EA%B7%80%EC%9D%B8&tag=a%2Bb&blank=";
        var response = post("/api/test/raw", "application/x-www-form-urlencoded; charset=UTF-8", body, false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body + "\n" + body);
    }

    @Test void jsonBodyIsPreservedExactlyForSignatureVerification() throws Exception {
        String body = "{\n  \"type\": \"Transaction.Paid\", \"value\": \"귀인+&=\"\n}\n";
        var response = post("/api/test/body", "application/json; charset=UTF-8", body, false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void formAtSizeLimitIsAccepted(boolean chunked) throws Exception {
        String body = "memo=" + "x".repeat(65531);
        var response = post("/api/test/body", "application/x-www-form-urlencoded", body, chunked);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedJsonAndFormAreRejected(boolean chunked) throws Exception {
        for (String contentType : new String[]{"application/json", "application/x-www-form-urlencoded"}) {
            var response = post("/api/test/body", contentType, "x".repeat(65537), chunked);

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("REQUEST_TOO_LARGE");
        }
    }

    @Test void invalidFormEncodingIsRejected() throws Exception {
        var response = post("/api/test/body", "application/x-www-form-urlencoded", "tag=%GG", false);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_REQUEST");
    }

    static HttpResponse<String> post(String path, String contentType, String body, boolean chunked) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var publisher = chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
            : HttpRequest.BodyPublishers.ofByteArray(bytes);
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", contentType).POST(publisher).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({ProbeController.class, ErrorHandler.class})
    static class WebConfig {}

    @RestController
    @RequestMapping(produces = "text/plain;charset=UTF-8")
    static class ProbeController {
        @PostMapping("/api/test/body") String body(@RequestBody String body) { return body; }

        @PostMapping("/api/test/parameters") String parameters(HttpServletRequest request) {
            var names = Collections.list(request.getParameterNames());
            Collections.sort(names);
            return String.join("|", request.getParameter("goodname"), request.getParameter("tag"),
                String.join(",", request.getParameterValues("tag")),
                String.join(",", request.getParameterMap().get("tag")), String.join(",", names),
                request.getParameter("blank"), request.getParameter("bare"),
                Boolean.toString(request.getParameter("missing") == null && request.getParameterValues("missing") == null));
        }

        @PostMapping("/api/test/raw") String raw(HttpServletRequest request) throws Exception {
            request.getParameterMap();
            String stream = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return stream + "\n" + request.getReader().readLine();
        }
    }
}
