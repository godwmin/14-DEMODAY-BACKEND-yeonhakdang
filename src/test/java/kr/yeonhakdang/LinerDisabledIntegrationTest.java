package kr.yeonhakdang;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="LINER_API_KEY=") @ActiveProfiles("test") @AutoConfigureMockMvc
class LinerDisabledIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void missingKeyFallsBackWithoutBreakingTheResultPage() throws Exception {
        String body=SajuExplanationIntegrationTest.input("disabled-fixture");
        mvc.perform(post("/api/ai/saju-explanation").queryParam("generate","true").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.paragraphs").isEmpty());
    }
}
