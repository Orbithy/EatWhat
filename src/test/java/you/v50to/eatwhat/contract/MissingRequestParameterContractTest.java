package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import you.v50to.eatwhat.controller.UserController;
import you.v50to.eatwhat.data.dto.OtherUserInfoDTO;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.support.MvcContractSupport;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MissingRequestParameterContractTest extends MvcContractSupport {
    private UserService users;
    private MockMvc http;
    private String token;

    @BeforeEach
    void createController() {
        users = mock(UserService.class);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", users);
        http = mvc(controller);
        token = loginAs(99L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingOrEmptyRequiredUserIdReturnsParameterMissing(String userId) throws Exception {
        var request = get("/user/userInfo").header(TOKEN_HEADER, "Bearer " + token);
        if (userId != null) request.param("userId", userId);

        http.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10002))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.msg").value("缺少必填参数：userId"))
                .andExpect(jsonPath("$.timestamp").isNumber());
        verifyNoInteractions(users);
    }

    @Test
    void malformedRequiredUserIdStillReturnsParameterInvalid() throws Exception {
        http.perform(get("/user/userInfo").param("userId", "abc")
                        .header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(users);
    }

    @Test
    void validRequiredUserIdStillReachesService() throws Exception {
        when(users.getUserInfo(100L)).thenReturn(Result.<OtherUserInfoDTO>ok());

        http.perform(get("/user/userInfo").param("userId", "100")
                        .header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(users).getUserInfo(100L);
    }

    @Test
    void omittedOptionalAndDefaultedParametersStillReachService() throws Exception {
        when(users.getFollowers(null, 1, 20)).thenReturn(Result.ok());

        http.perform(get("/user/followers").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(users).getFollowers(null, 1, 20);
    }
}
