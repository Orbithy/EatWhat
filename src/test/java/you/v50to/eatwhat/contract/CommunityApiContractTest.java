package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import you.v50to.eatwhat.controller.HistoryController;
import you.v50to.eatwhat.controller.NotificationController;
import you.v50to.eatwhat.controller.UserController;
import you.v50to.eatwhat.data.dto.BrowseHistoryItemDTO;
import you.v50to.eatwhat.data.dto.NotificationDTO;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.BrowseHistoryService;
import you.v50to.eatwhat.service.NotificationService;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.support.MvcContractSupport;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommunityApiContractTest extends MvcContractSupport {
    private UserService users;
    private NotificationService notifications;
    private BrowseHistoryService history;
    private MockMvc http;

    @BeforeEach
    void createControllers() {
        users = mock(UserService.class);
        notifications = mock(NotificationService.class);
        history = mock(BrowseHistoryService.class);
        UserController userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", users);
        NotificationController notificationController = new NotificationController();
        ReflectionTestUtils.setField(notificationController, "notificationService", notifications);
        HistoryController historyController = new HistoryController();
        ReflectionTestUtils.setField(historyController, "browseHistoryService", history);
        http = mvc(userController, notificationController, historyController);
    }

    @Test
    void userInfoUsesSessionAccountAndSerializesDatesAsIso() throws Exception {
        UserInfoDTO user = new UserInfoDTO();
        user.setId(99L);
        user.setUserName("tester");
        user.setBirthday(LocalDate.of(2003, 5, 2));
        user.setVerified(true);
        user.setMethod("school_email");
        user.setCreatedAt(1700000000000L);
        when(users.getInfo(99L)).thenReturn(Result.ok(user));
        http.perform(as(get("/user/info")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(99))
                .andExpect(jsonPath("$.data.userName").value("tester"))
                .andExpect(jsonPath("$.data.birthday").value("2003-05-02"))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.method").value("school_email"))
                .andExpect(jsonPath("$.data.createdAt").value(1700000000000L));
        verify(users).getInfo(99L);
    }

    @Test
    void userProfileUpdateKeepsPutAndPartialProfileFields() throws Exception {
        when(users.updateUserInfo(any())).thenReturn(Result.ok());
        http.perform(as(put("/user/updateInfo").contentType(MediaType.APPLICATION_JSON).content("""
                        {"userName":"tester","avatar":"avatar/99/test.jpg","gender":"other",
                         "signature":"测试签名","hometownProvinceId":37,"hometownCityId":5}
                        """)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(users).updateUserInfo(argThat(dto -> "tester".equals(dto.getUserName())
                && "avatar/99/test.jpg".equals(dto.getAvatar()) && "other".equals(dto.getGender())
                && dto.getHometownProvinceId().equals(37) && dto.getHometownCityId().equals(5)
                && dto.getBirthday() == null));
    }

    @Test
    void followAndUnfollowBindQueryParameterRatherThanJsonBody() throws Exception {
        when(users.follow(100L)).thenReturn(Result.ok());
        when(users.unfollow(100L)).thenReturn(Result.ok());
        String token = loginAs(99L, "verified");
        http.perform(post("/user/follow").param("userId", "100").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(post("/user/unfollow").param("userId", "100").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(users).follow(100L);
        verify(users).unfollow(100L);
    }

    @Test
    void followingRequiresVerificationButRemovingAnExistingFollowOnlyRequiresLogin() throws Exception {
        http.perform(as(post("/user/follow").param("userId","100")))
                .andExpect(jsonPath("$.code").value(20004));
        verify(users, never()).follow(anyLong());
        when(users.unfollow(100L)).thenReturn(Result.ok());
        http.perform(as(post("/user/unfollow").param("userId","100")))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void clearHistoryRequiresLoginAndOnlyAcceptsFoodOrRestaurantScope() throws Exception {
        when(history.clearBrowseHistory(99L,null)).thenReturn(Result.ok());
        when(history.clearBrowseHistory(99L,"food")).thenReturn(Result.ok());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/history/browse"))
                .andExpect(jsonPath("$.code").value(20001));
        http.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/history/browse")))
                .andExpect(jsonPath("$.code").value(0));
        http.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/history/browse").param("targetType","food").param("accountId","100")))
                .andExpect(jsonPath("$.code").value(0));
        http.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/history/browse").param("targetType","user")))
                .andExpect(jsonPath("$.code").value(10001));
        verify(history).clearBrowseHistory(99L,null);
        verify(history).clearBrowseHistory(99L,"food");
    }

    @Test
    void privacyPostPreservesSeparateFollowingAndFollowerBooleans() throws Exception {
        when(users.changePrivacy(any())).thenReturn(Result.ok());
        http.perform(as(post("/user/changePrivacy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"following\":false,\"follower\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(users).changePrivacy(argThat(dto -> Boolean.FALSE.equals(dto.getFollowing())
                && Boolean.TRUE.equals(dto.getFollower())));
    }

    @Test
    void notificationListRetainsStringJsonPayloadAndNumericIdentity() throws Exception {
        NotificationDTO notification = new NotificationDTO();
        notification.setId(9001L);
        notification.setActorId(100L);
        notification.setTargetId(8001L);
        notification.setType("like");
        notification.setTargetType("food");
        notification.setData("{\"targetId\":8001}");
        notification.setRead(false);
        when(notifications.listMyNotifications(1, 20))
                .thenReturn(Result.ok(PageResult.of(List.of(notification), 1L, 20L, 1L)));
        http.perform(as(get("/notification/list")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(9001))
                .andExpect(jsonPath("$.data.items[0].actorId").value(100))
                .andExpect(jsonPath("$.data.items[0].targetId").value(8001))
                .andExpect(jsonPath("$.data.items[0].data").value("{\"targetId\":8001}"))
                .andExpect(jsonPath("$.data.items[0].read").value(false));
        verify(notifications).listMyNotifications(1, 20);
    }

    @Test
    void notificationReadAllResolvesStaticRouteAndReadOneUsesNumericPath() throws Exception {
        when(notifications.markAllAsRead()).thenReturn(Result.ok());
        when(notifications.markOneAsRead(9001L)).thenReturn(Result.ok());
        when(notifications.countUnread()).thenReturn(Result.ok(3L));
        String token = loginAs(99L);
        http.perform(post("/notification/read/all").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(post("/notification/read/9001").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(get("/notification/unread/count").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(3));
        verify(notifications).markAllAsRead();
        verify(notifications).markOneAsRead(9001L);
    }

    @Test
    void readingAnUnknownOrForeignNumericNotificationKeepsTheLegacySuccessEnvelope() throws Exception {
        when(notifications.markOneAsRead(9002L)).thenReturn(Result.ok());
        String token = loginAs(99L);

        http.perform(post("/notification/read/9002").header("satoken", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(notifications).markOneAsRead(9002L);
    }

    @Test
    void browseHistoryBindsQueryAndUsesAuthenticatedAccount() throws Exception {
        BrowseHistoryItemDTO item = new BrowseHistoryItemDTO();
        item.setTargetType("restaurant");
        item.setTargetId(4201L);
        item.setViewedAt(1700000000000L);
        when(history.getBrowseHistory(eq(99L), any()))
                .thenReturn(Result.ok(PageResult.of(List.of(item), 2L, 5L, 6L)));
        http.perform(as(get("/history/browse").param("targetType", "restaurant")
                        .param("page", "2").param("pageSize", "5"), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.currentPage").value(2))
                .andExpect(jsonPath("$.data.items[0].targetId").value(4201))
                .andExpect(jsonPath("$.data.items[0].viewedAt").value(1700000000000L));
        verify(history).getBrowseHistory(eq(99L), argThat(dto -> "restaurant".equals(dto.getTargetType())
                && dto.getPage().equals(2) && dto.getPageSize().equals(5)));
    }

    @Test
    void browseHistoryRejectsInvalidTargetTypeBeforeService() throws Exception {
        http.perform(as(get("/history/browse").param("targetType", "user"), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.msg").value(containsString("restaurant 或 food")));
        verifyNoInteractions(history);
    }

    @Test
    void browseHistoryNeedsLoginButNotVerified() throws Exception {
        when(history.getBrowseHistory(eq(99L), any())).thenReturn(Result.ok(PageResult.of(List.of(), 1L, 20L, 0L)));
        http.perform(get("/history/browse")).andExpect(jsonPath("$.code").value(20001));
        http.perform(as(get("/history/browse").param("targetType", "food"))).andExpect(jsonPath("$.code").value(0));
        verify(history).getBrowseHistory(eq(99L), any());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.header("satoken", "Bearer " + loginAs(99L, roles));
    }
}
