package you.v50to.eatwhat.contract;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.controller.ProfileConflictController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.sync.profile.ProfileConflictService;
import you.v50to.eatwhat.sync.profile.ProfileCommands;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileConflictControllerContractTest extends MvcContractSupport {
    private static final UUID CONFLICT = UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-4000-8000-000000000202");
    private static final UUID HEAD = UUID.fromString("00000000-0000-4000-8000-000000000203");
    private MockMvc http;

    @BeforeEach
    void createController() {
        SiteProperties campus = new SiteProperties();
        campus.setMode(SiteProperties.Mode.CAMPUS);
        http = mvc(new ProfileConflictController(conflicts(
                mock(JdbcTemplate.class), campus, "relation-external")));
    }

    @Test
    void ordinaryUserCannotReadSensitiveHeadsOrResolveConflict() throws Exception {
        String token = loginAs(71L, "user");

        http.perform(get("/admin/sync/profile-conflicts").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        http.perform(post("/admin/sync/profile-conflicts/{id}/resolve", CONFLICT)
                        .header(TOKEN_HEADER, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"expectedRevision\":1,\"selectedHeadId\":\"" + HEAD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));

    }

    @Test
    void campusAdminCanUseTheAdditiveConflictRoutes() throws Exception {
        var conflict = new ProfileConflictService.Conflict(CONFLICT, SUBJECT, "signature", 4,
                List.of(Map.of("id", HEAD.toString(), "value", "private-head-value")));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ProfileConflictService.Conflict>>any()))
                .thenReturn(List.of(conflict));
        SiteProperties campus = new SiteProperties();
        campus.setMode(SiteProperties.Mode.CAMPUS);
        MockMvc adminHttp = mvc(new ProfileConflictController(conflicts(
                jdbc, campus, "relation-external")));
        String token = loginAs(72L, "admin");

        adminHttp.perform(get("/admin/sync/profile-conflicts").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].conflictId").value(CONFLICT.toString()))
                .andExpect(jsonPath("$.data[0].heads[0].value").value("private-head-value"));
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ProfileConflictService.Conflict>>any());
    }

    @Test
    void externalAdminAndExpiredSessionCannotReadOrResolveCampusConflictValues() throws Exception {
        SiteProperties external = new SiteProperties();
        external.setMode(SiteProperties.Mode.EXTERNAL);
        ProfileConflictService externalConflicts = conflicts(null, external, "relation-campus");
        MockMvc externalHttp = mvc(new ProfileConflictController(externalConflicts));
        String token = loginAs(73L, "admin");
        StpUtil.logout(73L);

        externalHttp.perform(get("/admin/sync/profile-conflicts").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));

        String freshExternalAdmin = loginAs(74L, "admin");
        externalHttp.perform(get("/admin/sync/profile-conflicts").header(TOKEN_HEADER, "Bearer " + freshExternalAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        externalHttp.perform(post("/admin/sync/profile-conflicts/{id}/resolve", CONFLICT)
                        .header(TOKEN_HEADER, "Bearer " + freshExternalAdmin)
                        .contentType("application/json")
                        .content("{\"expectedRevision\":1,\"selectedHeadId\":\"" + HEAD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
    }

    private ProfileConflictService conflicts(JdbcTemplate jdbc, SiteProperties site, String peer) {
        ProfileCommands commands = new ProfileCommands(jdbc, null, null, null, null, null, site, peer);
        return new ProfileConflictService(jdbc, null, commands, null, null, site, peer);
    }
}
