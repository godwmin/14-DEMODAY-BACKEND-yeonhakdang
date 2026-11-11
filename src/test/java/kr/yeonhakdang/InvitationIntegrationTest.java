package kr.yeonhakdang;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class InvitationIntegrationTest {
    @Autowired Store db;
    @Autowired AccountService accounts;
    @Autowired AnalysisProfileController profiles;
    @Autowired InvitationController invitations;
    @Autowired MockMvc mvc;
    UUID first,second;
    UsernamePasswordAuthenticationToken auth(UUID id) {return new UsernamePasswordAuthenticationToken(id.toString(),null);}
    AnalysisProfileController.Profile profile(String name) {
        return new AnalysisProfileController.Profile(name,"2005.03.15",0,0,false,"",true,true,List.of(),0);
    }
    @BeforeEach void setup() {
        first=accounts.login("invite-first-"+UUID.randomUUID());second=accounts.login("invite-second-"+UUID.randomUUID());
        for(UUID member:List.of(first,second)) {
            accounts.consent(member,"2026-10-29-draft",true,true,true,false);
            profiles.save(auth(member),new AnalysisProfileController.Save(member,0,profile(member.equals(first)?"초대한사람":"받은사람")));
        }
    }
    @Test void acceptanceAddsBothRostersAndRepeatedOrReverseAcceptNeverDuplicates() {
        var link=invitations.create(auth(first));
        assertThat(invitations.create(auth(first)).token()).isEqualTo(link.token());
        assertThat(invitations.accept(auth(second),link.token()).connected()).isTrue();
        invitations.accept(auth(second),link.token());
        invitations.accept(auth(first),invitations.create(auth(second)).token());
        assertThat(invitations.roster(auth(first)).friends()).singleElement().satisfies(f->assertThat(f).containsEntry("memberId",second).containsEntry("name","받은사람"));
        assertThat(invitations.roster(auth(second)).friends()).singleElement().satisfies(f->assertThat(f).containsEntry("memberId",first).containsEntry("name","초대한사람"));
        assertThat(profiles.get(auth(first)).profile().friends()).isEmpty();
    }
    @Test void selfAcceptanceNeverCreatesAConnection() {
        assertThat(invitations.accept(auth(first),invitations.create(auth(first)).token()).connected()).isFalse();
        assertThat(invitations.roster(auth(first)).friends()).isEmpty();
    }
    @Test void previewIsPublicButWritingRequiresLoginConsentAnalysisAndCsrf() throws Exception {
        var token=invitations.create(auth(first)).token();
        mvc.perform(get("/api/invitations/"+token)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("초대한사람")).andExpect(jsonPath("$.memberId").doesNotExist());
        mvc.perform(post("/api/invitations/"+token+"/accept").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/invitations/"+token+"/accept").with(user(second.toString()))).andExpect(status().isForbidden());
        var empty=accounts.login("empty-"+UUID.randomUUID());
        mvc.perform(post("/api/invitations/"+token+"/accept").with(user(empty.toString())).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUIRED_CONSENT"));
        accounts.consent(empty,"2026-10-29-draft",true,true,true,false);
        mvc.perform(post("/api/invitations/"+token+"/accept").with(user(empty.toString())).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANALYSIS_REQUIRED"));
        mvc.perform(get("/api/me/friends")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/invitations/"+UUID.randomUUID())).andExpect(status().isNotFound());
    }
    @Test void profileChangesAreReflectedWithoutCopyingAnotherMembersPersonalDataIntoSavedProfile() {
        invitations.accept(auth(second),invitations.create(auth(first)).token());
        var saved=profiles.get(auth(first));
        profiles.save(auth(first),new AnalysisProfileController.Save(first,saved.revision(),profile("바뀐이름")));
        assertThat(invitations.roster(auth(second)).friends()).singleElement().satisfies(f->assertThat(f).containsEntry("name","바뀐이름"));
        assertThat(profiles.get(auth(second)).profile().friends()).isEmpty();
    }
    @Test void withdrawalRemovesBothDirectionsAndRevokesExistingLinks() throws Exception {
        var token=invitations.create(auth(first)).token();
        invitations.accept(auth(second),token);accounts.withdraw(first);
        assertThat(invitations.roster(auth(second)).friends()).isEmpty();
        mvc.perform(get("/api/invitations/"+token)).andExpect(status().isNotFound());
    }
    @Test void concurrentAcceptsInBothDirectionsUseOneRelationshipWithoutDeadlock() throws Exception {
        var a=invitations.create(auth(first)).token();var b=invitations.create(auth(second)).token();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            var one=executor.submit(()->{barrier.await();return invitations.accept(auth(first),b);});
            var two=executor.submit(()->{barrier.await();return invitations.accept(auth(second),a);});
            assertThat(one.get(10,TimeUnit.SECONDS).connected()).isTrue();
            assertThat(two.get(10,TimeUnit.SECONDS).connected()).isTrue();
        }
        assertThat(invitations.roster(auth(first)).friends()).hasSize(1);
        assertThat(invitations.roster(auth(second)).friends()).hasSize(1);
    }
}
