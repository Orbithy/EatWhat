package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import you.v50to.eatwhat.controller.RecommendationProfileController;
import you.v50to.eatwhat.data.dto.FoodPreferencesRow;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.mapper.RecommendationProfileMapper;
import you.v50to.eatwhat.service.RecommendationProfileService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;
import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecommendationProfileContractTest extends MvcContractSupport {
    private RecommendationProfileMapper mapper;
    private MockMvc http;
    private static final String BASE = "/user/recommendation-profile";

    @BeforeEach
    void controller() {
        mapper = mock(RecommendationProfileMapper.class);
                http = mvc(new RecommendationProfileController(new RecommendationProfileService(mapper)));
    }

    @Test
    void settingsExposeOnlyExplicitChoicesAndUseSessionIdentity() throws Exception {
        FoodPreferencesRow row = new FoodPreferencesRow();
        row.setLikedTags(new String[]{"清淡"}); row.setDislikedTags(new String[]{"油腻"});
        row.setUpdatedAt(123L);
        when(mapper.selectPreferences(99L)).thenReturn(row);
        http.perform(get(BASE + "/preferences").param("accountId", "100")
                        .header("satoken", "Bearer " + loginAs(99L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.likedTags[0]").value("清淡"))
                .andExpect(jsonPath("$.data.dislikedTags[0]").value("油腻"))
                .andExpect(jsonPath("$.data.updatedAt").value(123))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(3)));
        verify(mapper).selectPreferences(99L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void inferredProfileHasNoPublicRoute() throws Exception {
        http.perform(get(BASE).header("satoken", "Bearer " + loginAs(99L)))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertInstanceOf(
                        org.springframework.web.servlet.NoHandlerFoundException.class, result.getResolvedException()));
        verifyNoInteractions(mapper);
    }

    @Test
    void everyProfileEndpointRequiresAuthentication() throws Exception {
        http.perform(get(BASE + "/preferences")).andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        http.perform(put(BASE + "/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"likedTags\":[],\"dislikedTags\":[]}"))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        http.perform(delete(BASE + "/preferences"))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        verifyNoInteractions(mapper);
    }

    @Test
    void manualPreferencesAreNormalizedAndCannotTargetAnotherUser() throws Exception {
        String token = loginAs(99L);
        http.perform(put(BASE + "/preferences").param("accountId", "100")
                        .header("satoken", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"likedTags\":[\" Spicy  Food \",\"SPICY FOOD\"],\"dislikedTags\":[\"Greasy\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(3)));
        verify(mapper).savePreferences(99L, List.of("spicy food"), List.of("greasy"));
        verify(mapper).selectPreferences(99L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingNullBlankAndOversizedTasteInputsAreRejected() throws Exception {
        String token = loginAs(99L);
        for (String body : List.of("{}", "{\"likedTags\":null,\"dislikedTags\":[]}",
                "{\"likedTags\":[null],\"dislikedTags\":[]}",
                "{\"likedTags\":[\"  \"],\"dislikedTags\":[]}",
                "{\"likedTags\":[\"" + "a".repeat(33) + "\"],\"dislikedTags\":[]}",
                "{\"likedTags\":[" + String.join(",", Collections.nCopies(21, "\"spicy\"")) + "],\"dislikedTags\":[]}")) {
            http.perform(put(BASE + "/preferences").header("satoken", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void contradictoryTastesReturnValidationEnvelopeBeforeAnyWrite() throws Exception {
        String token = loginAs(99L);
        http.perform(put(BASE + "/preferences").header("satoken", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"likedTags\":[\"SPICY\"],\"dislikedTags\":[\" spicy \"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(mapper);
    }

    @Test
    void clearingPreferencesOnlyTouchesTheCurrentAccount() throws Exception {
        String token = loginAs(99L);
        http.perform(delete(BASE + "/preferences").param("accountId", "100")
                        .header("satoken", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.likedTags").isEmpty())
                .andExpect(jsonPath("$.data.dislikedTags").isEmpty())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(3)));
        verify(mapper).deletePreferences(99L);
        verify(mapper).selectPreferences(99L);
        verifyNoMoreInteractions(mapper);
    }
}
