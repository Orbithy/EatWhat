package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import you.v50to.eatwhat.controller.AdminController;
import you.v50to.eatwhat.controller.FoodTagAdminController;
import you.v50to.eatwhat.controller.HubController;
import you.v50to.eatwhat.controller.LocationController;
import you.v50to.eatwhat.controller.UserController;
import you.v50to.eatwhat.data.dto.CityDTO;
import you.v50to.eatwhat.data.dto.HubDTO;
import you.v50to.eatwhat.data.dto.ProvinceDTO;
import you.v50to.eatwhat.data.po.Hub;
import you.v50to.eatwhat.data.vo.FoodTagSummaryVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.SummaryVO;
import you.v50to.eatwhat.service.AdminService;
import you.v50to.eatwhat.service.FoodTagService;
import you.v50to.eatwhat.service.HubService;
import you.v50to.eatwhat.service.LocationService;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
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

class ManagementApiContractTest extends MvcContractSupport {
    private LocationService locations;
    private HubService hubs;
    private FoodTagService tags;
    private AdminService admin;
    private UserService users;
    private MockMvc http;

    @BeforeEach
    void createControllers() {
        locations = mock(LocationService.class);
        hubs = mock(HubService.class);
        tags = mock(FoodTagService.class);
        admin = mock(AdminService.class);
        users = mock(UserService.class);
        HubController hubController = new HubController();
        ReflectionTestUtils.setField(hubController, "hubService", hubs);
        FoodTagAdminController tagController = new FoodTagAdminController();
        ReflectionTestUtils.setField(tagController, "foodTagService", tags);
        AdminController adminController = new AdminController();
        ReflectionTestUtils.setField(adminController, "adminService", admin);
        UserController userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", users);
        http = mvc(new LocationController(locations), hubController, tagController, adminController, userController);
    }

    @Test
    void locationEndpointsKeepTheirExistingApiPrefixAndIntegerReferences() throws Exception {
        when(locations.getAllProvinces()).thenReturn(List.of(new ProvinceDTO(37, "山东省")));
        when(locations.getCitiesByProvinceId(37)).thenReturn(List.of(new CityDTO(5, 37, "济南市")));
        String token = loginAs(99L);
        http.perform(get("/api/location/provinces").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(37))
                .andExpect(jsonPath("$.data[0].name").value("山东省"));
        http.perform(get("/api/location/provinces/37/cities").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(5))
                .andExpect(jsonPath("$.data[0].provinceId").value(37));
        verify(locations).getCitiesByProvinceId(37);
    }

    @Test
    void locationDictionaryIsOpenToGuests() throws Exception {
        when(locations.getAllProvinces()).thenReturn(List.of());
        http.perform(get("/api/location/provinces"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(locations).getAllProvinces();
    }

    @Test
    void hubReadRoutesKeepCoordinateAndPagingParameters() throws Exception {
        Hub hub = new Hub();
        hub.setId(71L);
        hub.setName("测试商圈");
        when(hubs.findHubByLocation(117.03, 36.68)).thenReturn(Result.ok(hub));
        when(hubs.listHubs(null, 1, 20)).thenReturn(Result.ok(PageResult.of(List.of(hub), 1L, 20L, 1L)));
        String token = loginAs(99L);
        http.perform(get("/hub/locate").param("gcjLng", "117.03").param("gcjLat", "36.68")
                        .header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(71));
        http.perform(get("/hub/list").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentPage").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("测试商圈"));
        verify(hubs).findHubByLocation(117.03, 36.68);
        verify(hubs).listHubs(null, 1, 20);
    }

    @Test
    void hubRebindAdminOperationRetainsNumericAffectedRowResult() throws Exception {
        when(hubs.rebindRestaurants(71L)).thenReturn(Result.ok(4));
        http.perform(as(post("/hub/rebind-restaurants/71"), "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(4));
        verify(hubs).rebindRestaurants(71L);
    }

    @Test
    void hubCreateAllowsAnUnsetBoundaryGeometry() throws Exception {
        when(hubs.addHub(any())).thenReturn(Result.ok());
        String token = loginAs(99L, "admin");
        http.perform(post("/hub/add").header("satoken", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"无边界商圈\",\"gcjLng\":117.03,\"gcjLat\":36.68}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<HubDTO> dto = ArgumentCaptor.forClass(HubDTO.class);
        verify(hubs).addHub(dto.capture());
        org.junit.jupiter.api.Assertions.assertEquals("无边界商圈", dto.getValue().getName());
        org.junit.jupiter.api.Assertions.assertNull(dto.getValue().getBoundaryGeoJson());
    }

    @Test
    void verifiedUserCannotPerformAdminHubMutation() throws Exception {
        http.perform(as(delete("/hub/delete/71"), "verified"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(20004))
                .andExpect(jsonPath("$.msg").value("权限不足：需要管理员权限"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        verifyNoInteractions(hubs);
    }

    @Test
    void systemTagCreateAndRenameKeepAdminBodyContracts() throws Exception {
        when(tags.createSystemTag(any())).thenReturn(Result.ok(new FoodTagSummaryVO(7L, "清淡")));
        when(tags.updateSystemTag(eq(7L), any())).thenReturn(Result.ok());
        String token = loginAs(99L, "admin");
        http.perform(post("/admin/food-tags").header("satoken", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"清淡\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(7)).andExpect(jsonPath("$.data.name").value("清淡"));
        http.perform(put("/admin/food-tags/7").header("satoken", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"微辣\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(tags).createSystemTag(argThat(dto -> "清淡".equals(dto.getName())));
        verify(tags).updateSystemTag(eq(7L), argThat(dto -> "微辣".equals(dto.getName())));
    }

    @Test
    void systemTagCreationRejectsBlankNameBeforeService() throws Exception {
        http.perform(as(post("/admin/food-tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"), "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(tags);
    }

    @Test
    void adminSummaryRetainsAggregateFieldNamesAndNumericValues() throws Exception {
        when(admin.summary()).thenReturn(Result.ok(new SummaryVO(100L, 200L, 30L, 1L, 2L, 3L, 0.75)));
        http.perform(as(get("/admin/summary"), "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalUsers").value(100))
                .andExpect(jsonPath("$.data.totalFoods").value(200))
                .andExpect(jsonPath("$.data.totalRestaurants").value(30))
                .andExpect(jsonPath("$.data.todayUsers").value(1))
                .andExpect(jsonPath("$.data.todayFoods").value(2))
                .andExpect(jsonPath("$.data.todayRestaurants").value(3))
                .andExpect(jsonPath("$.data.verifiedRate").value(0.75));
        verify(admin).summary();
    }

    @Test
    void banAndUnbanKeepAdminPostRoutesAndReasonBody() throws Exception {
        when(users.banUser(eq(100L), any())).thenReturn(Result.ok());
        when(users.unbanUser(100L)).thenReturn(Result.ok());
        String token = loginAs(99L, "admin");
        http.perform(post("/user/ban/100").header("satoken", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"测试封禁原因\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(post("/user/unban/100").header("satoken", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(users).banUser(eq(100L), argThat(dto -> "测试封禁原因".equals(dto.getReason())));
        verify(users).unbanUser(100L);
    }

    @Test
    void invalidNumericPathRetainsParameterErrorEnvelope() throws Exception {
        http.perform(as(post("/hub/rebind-restaurants/not-a-number"), "admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.msg").value("参数非法"));
        verifyNoInteractions(hubs);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.header("satoken", "Bearer " + loginAs(99L, roles));
    }
}
