package kr.yeonhakdang;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;

/** Account-owned input snapshot. Purchase entitlements never come from this payload. */
@RestController
@RequestMapping("/api/me/analysis-profile")
public class AnalysisProfileController {
    public record Friend(@NotBlank @Size(max=10) String name,
        @NotNull @Pattern(regexp="[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}") String birth,
        @NotNull @Min(0) @Max(1) Integer calendar, @NotNull Boolean leapMonth,
        @NotNull @Size(max=30) String time, @NotNull Boolean unknownTime, @PositiveOrZero long addedAt) {}
    public record Profile(@NotBlank @Size(max=10) String name,
        @NotNull @Pattern(regexp="[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}") String birth,
        @NotNull @Min(0) @Max(1) Integer gender, @NotNull @Min(0) @Max(1) Integer calendar,
        @NotNull Boolean leapMonth, @NotNull @Size(max=30) String time, @NotNull Boolean unknownTime,
        @NotNull Boolean analyzed, @NotNull @Size(max=200) List<@NotNull @Valid Friend> friends,
        @PositiveOrZero long rosterSeenAt) {}
    public record Snapshot(UUID accountId, long revision, Profile profile) {}
    public record Save(@NotNull UUID accountId, @PositiveOrZero long revision, @NotNull @Valid Profile profile) {}
    private final Store db;
    private final AccountService accounts;
    private final ObjectMapper json;
    private final Clock clock;
    public AnalysisProfileController(Store db,AccountService accounts,ObjectMapper json,Clock clock) {
        this.db=db;this.accounts=accounts;this.json=json;this.clock=clock;
    }
    @GetMapping @Transactional
    public Snapshot get(Authentication auth) {
        UUID user=UUID.fromString(auth.getName());
        db.active(user);accounts.requireConsent(user);
        var rows=db.rows("select profile,revision from account_analysis_profile where member_id=?",user);
        if(rows.isEmpty()) return new Snapshot(user,0,null);
        var row=rows.getFirst();
        return new Snapshot(user,((Number)row.get("revision")).longValue(),json.readValue((String)row.get("profile"),Profile.class));
    }
    @PutMapping @Transactional
    public Snapshot save(Authentication auth,@Valid @RequestBody Save input) {
        UUID user=UUID.fromString(auth.getName());
        db.active(user);accounts.requireConsent(user);
        if(!user.equals(input.accountId())) throw ApiException.conflict("PROFILE_ACCOUNT_CHANGED");
        var current=get(auth);
        if(input.revision()!=current.revision()) throw ApiException.conflict("PROFILE_CONFLICT");
        String value=json.writeValueAsString(input.profile());
        long revision=current.revision()+1;
        if(current.revision()==0) db.update("insert into account_analysis_profile(member_id,profile,revision,updated_at) values(?,?,?,?)",user,value,revision,clock.instant());
        else db.update("update account_analysis_profile set profile=?,revision=?,updated_at=? where member_id=?",value,revision,clock.instant(),user);
        return new Snapshot(user,revision,input.profile());
    }
}
