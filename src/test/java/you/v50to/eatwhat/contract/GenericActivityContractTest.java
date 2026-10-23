package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import you.v50to.eatwhat.controller.ActivityAdminController;
import you.v50to.eatwhat.controller.ActivityController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.ActivityPostVO;
import you.v50to.eatwhat.data.vo.ActivityVO;
import you.v50to.eatwhat.data.vo.CreatedResourceVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.ActivityAdminService;
import you.v50to.eatwhat.service.ActivityService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;
import java.util.Map;

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

class GenericActivityContractTest extends MvcContractSupport {
    private ActivityService activities;
    private ActivityAdminService admin;
    private MockMvc http;

    @BeforeEach
    void createControllers() {
        activities = mock(ActivityService.class);
        admin = mock(ActivityAdminService.class);
        ActivityController activityController = new ActivityController();
        ReflectionTestUtils.setField(activityController, "activityService", activities);
        ActivityAdminController adminController = new ActivityAdminController();
        ReflectionTestUtils.setField(adminController, "activityAdminService", admin);
        http = mvc(activityController, adminController);
    }

    @Test
    void guestsReadActivitiesAndPostsWithDefaultPaging() throws Exception {
        ActivityVO activity = new ActivityVO();
        activity.setId(5001L);
        activity.setCode("spring-food");
        activity.setPostingOpen(true);
        activity.setFieldSchema(List.of(Map.of("key", "dish", "label", "菜名", "type", "TEXT", "required", true, "maxLength", 20)));
        when(activities.listOnline()).thenReturn(Result.ok(List.of(activity)));
        http.perform(get("/activity/list"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(5001))
                .andExpect(jsonPath("$.data[0].code").value("spring-food"))
                .andExpect(jsonPath("$.data[0].fieldSchema[0].key").value("dish"));

        ActivityPostVO post = new ActivityPostVO();
        post.setId(8001L);
        post.setExtra(Map.of("dish", "春卷"));
        post.setIsLiked(false);
        when(activities.listPosts("spring-food", "NEWEST", 1, 20))
                .thenReturn(Result.ok(PageResult.of(List.of(post), 1L, 20L, 1L)));
        http.perform(get("/activity/spring-food/posts"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(8001))
                .andExpect(jsonPath("$.data.items[0].extra.dish").value("春卷"));
        verify(activities).listPosts("spring-food", "NEWEST", 1, 20);

        when(activities.getActivity("spring-food")).thenReturn(Result.ok(activity));
        http.perform(get("/activity/spring-food")).andExpect(jsonPath("$.data.postingOpen").value(true));
        when(activities.getPost(8001L)).thenReturn(Result.ok(post));
        http.perform(get("/activity/posts/8001")).andExpect(jsonPath("$.data.id").value(8001));
    }

    @Test
    void postSortAcceptsOnlyKnownValues() throws Exception {
        http.perform(get("/activity/spring-food/posts").param("sort", "OLDEST"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        when(activities.listPosts("spring-food", "LIKES", 2, 10))
                .thenReturn(Result.ok(PageResult.of(List.of(), 2L, 10L, 0L)));
        http.perform(get("/activity/spring-food/posts").param("sort", "LIKES").param("page", "2").param("pageSize", "10"))
                .andExpect(jsonPath("$.code").value(0));
        verify(activities).listPosts("spring-food", "LIKES", 2, 10);
    }

    @Test
    void publishingNeedsVerifiedRoleAndValidBody() throws Exception {
        String body = "{\"title\":\"春卷\",\"pictureUrl\":[\"activity/99/a.jpg\"],\"extra\":{\"dish\":\"春卷\"}}";
        http.perform(post("/activity/spring-food/posts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        http.perform(as(post("/activity/spring-food/posts").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        http.perform(as(post("/activity/spring-food/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pictureUrl\":[\"https://cdn.example.test/a.jpg\"]}"), "verified"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("activity key")));
        verifyNoInteractions(activities);

        when(activities.publishPost(eq("spring-food"), any())).thenReturn(Result.ok(new CreatedResourceVO(8003L)));
        http.perform(as(post("/activity/spring-food/posts").contentType(MediaType.APPLICATION_JSON).content(body), "verified"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.id").value(8003));
        verify(activities).publishPost(eq("spring-food"), argThat(dto -> "春卷".equals(dto.getTitle())
                && "春卷".equals(dto.getExtra().get("dish")) && dto.getPictureUrl().equals(List.of("activity/99/a.jpg"))));
    }

    @Test
    void deleteAndLikesNeedVerifiedRole() throws Exception {
        http.perform(as(delete("/activity/posts/8001"))).andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        http.perform(as(post("/activity/posts/8001/like"))).andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        verifyNoInteractions(activities);
        when(activities.deletePost(8001L)).thenReturn(Result.fail(BizCode.NO_PERMISSION, "仅可删除自己发布的内容"));
        http.perform(as(delete("/activity/posts/8001"), "verified"))
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("仅可删除自己发布的内容")));
        when(activities.like(8001L)).thenReturn(Result.ok());
        when(activities.unlike(8001L)).thenReturn(Result.ok());
        http.perform(as(post("/activity/posts/8001/like"), "verified")).andExpect(jsonPath("$.code").value(0));
        http.perform(as(delete("/activity/posts/8001/like"), "verified")).andExpect(jsonPath("$.code").value(0));
        verify(activities).like(8001L);
        verify(activities).unlike(8001L);
    }

    @Test
    void retiredFoodAndDinnerRoutesAndOldLikeEndpointAreGone() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(post("/activity/foods"), post("/activity/dinners"),
                post("/activity/likes"), delete("/activity/likes"))) {
            http.perform(as(request.contentType(MediaType.APPLICATION_JSON).content("{}"), "verified"))
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        }
        verifyNoInteractions(activities);
    }

    @Test
    void administratorsManageActivities() throws Exception {
        String body = """
                {"code":"spring-food","name":"春天的味道","fieldSchema":[
                  {"key":"dish","label":"菜名","type":"TEXT","required":true,"maxLength":20}]}
                """;
        http.perform(as(post("/admin/activities").contentType(MediaType.APPLICATION_JSON).content(body), "verified"))
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        verifyNoInteractions(admin);
        when(admin.create(any())).thenReturn(Result.ok(new CreatedResourceVO(5001L)));
        http.perform(as(post("/admin/activities").contentType(MediaType.APPLICATION_JSON).content(body), "admin"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.id").value(5001));
        verify(admin).create(argThat(dto -> "spring-food".equals(dto.getCode()) && dto.getFieldSchema().size() == 1));

        http.perform(as(post("/admin/activities").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Bad Code\",\"name\":\"x\",\"fieldSchema\":[]}"), "admin"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        http.perform(as(post("/admin/activities/5001/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"), "admin"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        when(admin.changeStatus(eq(5001L), any())).thenReturn(Result.ok());
        http.perform(as(post("/admin/activities/5001/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ONLINE\"}"), "admin"))
                .andExpect(jsonPath("$.code").value(0));
        when(admin.update(eq(5001L), any())).thenReturn(Result.ok());
        http.perform(as(put("/admin/activities/5001").contentType(MediaType.APPLICATION_JSON).content(body), "admin"))
                .andExpect(jsonPath("$.code").value(0));
        when(admin.delete(5001L)).thenReturn(Result.fail(BizCode.ACTIVITY_HAS_POSTS));
        http.perform(as(delete("/admin/activities/5001"), "admin"))
                .andExpect(jsonPath("$.code").value(BizCode.ACTIVITY_HAS_POSTS.getCode()));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.header("satoken", "Bearer " + loginAs(99L, roles));
    }
}
