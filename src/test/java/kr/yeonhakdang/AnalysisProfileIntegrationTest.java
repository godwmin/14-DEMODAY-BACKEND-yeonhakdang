package kr.yeonhakdang;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class AnalysisProfileIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired Store db;
    UUID member,other;
    @BeforeEach void setup() {
        member=accounts.login("profile-"+UUID.randomUUID());other=accounts.login("profile-"+UUID.randomUUID());
        accounts.consent(member,"2026-10-29-draft",true,true,true,false);
        accounts.consent(other,"2026-10-29-draft",true,true,true,false);
    }
    String payload(UUID owner,long revision) {
        return """
            {"accountId":"%s","revision":%d,"profile":{"name":"연학","birth":"2005.03.15","gender":0,"calendar":0,"leapMonth":false,"time":"","unknownTime":true,"analyzed":true,"friends":[{"name":"친구","birth":"2005.04.10","calendar":0,"leapMonth":false,"time":"","unknownTime":true,"addedAt":10}],"rosterSeenAt":0}}
            """.formatted(owner,revision);
    }
    void save() throws Exception {
        mvc.perform(put("/api/me/analysis-profile").with(user(member.toString())).with(csrf()).contentType("application/json").content(payload(member,0)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
    }
    @Test void restoresAcrossIndependentSessionsAndKeepsAccountsSeparate() throws Exception {
        save();
        mvc.perform(get("/api/me/analysis-profile").with(user(member.toString())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.profile.name").value("연학"))
            .andExpect(jsonPath("$.profile.friends[0].name").value("친구"));
        mvc.perform(get("/api/me/analysis-profile").with(user(other.toString())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0)).andExpect(jsonPath("$.profile").isEmpty());
    }
    @Test void staleWindowsAndChangedAccountsCannotOverwrite() throws Exception {
        save();
        mvc.perform(put("/api/me/analysis-profile").with(user(member.toString())).with(csrf()).contentType("application/json").content(payload(member,0)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_CONFLICT"));
        mvc.perform(put("/api/me/analysis-profile").with(user(other.toString())).with(csrf()).contentType("application/json").content(payload(member,1)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_ACCOUNT_CHANGED"));
    }
    @Test void requiresLoginConsentCsrfAndValidInput() throws Exception {
        mvc.perform(get("/api/me/analysis-profile")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me/analysis-profile").with(user(member.toString())).contentType("application/json").content(payload(member,0)))
            .andExpect(status().isForbidden());
        var unconsented=accounts.login("unconsented-"+UUID.randomUUID());
        mvc.perform(get("/api/me/analysis-profile").with(user(unconsented.toString())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUIRED_CONSENT"));
        mvc.perform(put("/api/me/analysis-profile").with(user(member.toString())).with(csrf()).contentType("application/json").content(payload(member,0).replace("2005.03.15","invalid")))
            .andExpect(status().isBadRequest());
    }
    @Test void withdrawalErasesProfile() throws Exception {
        save();accounts.withdraw(member);
        assertThat(db.rows("select * from account_analysis_profile where member_id=?",member)).isEmpty();
    }
}
