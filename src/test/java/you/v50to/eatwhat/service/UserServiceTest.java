package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import you.v50to.eatwhat.data.dto.BanUserDTO;
import you.v50to.eatwhat.data.dto.PrivacyDTO;
import you.v50to.eatwhat.data.dto.UpdateUserInfoDTO;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.po.Privacy;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.FollowMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserInfoMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.support.MvcContractSupport;
import you.v50to.eatwhat.utils.LocationValidationUtil;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest extends MvcContractSupport {

    private UserMapper userMapper;
    private UserInfoMapper userInfoMapper;
    private LocationValidationUtil locationValidationUtil;
    private ObjectStorageService objectStorageService;
    private UserService userService;
    private PrivacyMapper privacyMapper;
    private FollowMapper followMapper;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        // The service reads the current principal through the real Sa-Token servlet context.
        // The shared harness restores request and Sa-Token static state after each test.
        bindToken(loginAs(1L));
        userMapper = mock(UserMapper.class);
        userInfoMapper = mock(UserInfoMapper.class);
        locationValidationUtil = mock(LocationValidationUtil.class);
        objectStorageService = mock(ObjectStorageService.class);
        privacyMapper = mock(PrivacyMapper.class);
        followMapper = mock(FollowMapper.class);
        redis = mock(StringRedisTemplate.class);

        userService = new UserService();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "locationValidationUtil", locationValidationUtil);
        ReflectionTestUtils.setField(userService, "contactMapper", mock(ContactMapper.class));
        ReflectionTestUtils.setField(userService, "privacyMapper", privacyMapper);
        ReflectionTestUtils.setField(userService, "followMapper", followMapper);
        ReflectionTestUtils.setField(userService, "smsService", mock(SmsService.class));
        ReflectionTestUtils.setField(userService, "objectStorageService", objectStorageService);
        ReflectionTestUtils.setField(userService, "notificationService", mock(NotificationService.class));
        ReflectionTestUtils.setField(userService, "redis", redis);
    }

    @Test
    void followerAndFollowingVisibilityUsesDatabaseWhenPrivacyCacheIsStaleOrInvalidationFails() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "privacy-read-test"), Privacy.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> cachedValues = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(cachedValues);
        when(cachedValues.get("privacy:1")).thenReturn("true");

        PrivacyDTO restricted = new PrivacyDTO();
        restricted.setFollower(false);
        restricted.setFollowing(false);
        when(privacyMapper.update(any(Privacy.class), any())).thenReturn(1);
        doThrow(new IllegalStateException("synthetic redis unavailable")).when(redis).delete("privacy:1");
        assertEquals(BizCode.SUCCESS.getCode(), userService.changePrivacy(restricted).getCode());

        Privacy privateRows = new Privacy();
        privateRows.setAccountId(1L);
        privateRows.setFollower(false);
        privateRows.setFollowing(false);
        when(privacyMapper.selectOne(any())).thenReturn(privateRows);
        bindToken(loginAs(2L));
        assertEquals(BizCode.NOT_SUPPORTED.getCode(), userService.getFollowers(1L, 1, 20).getCode());
        assertEquals(BizCode.NOT_SUPPORTED.getCode(), userService.getFollowings(1L, 1, 20).getCode());
        verify(followMapper, never()).selectFollowersByTargetId(any(), any(), any());
        verify(followMapper, never()).selectFollowingsByAccountId(any(), any(), any());

        bindToken(loginAs(1L));
        when(followMapper.selectFollowersByTargetId(1L, 0, 20)).thenReturn(java.util.List.of());
        when(followMapper.countFollowersByTargetId(1L)).thenReturn(0L);
        when(followMapper.selectFollowingsByAccountId(1L, 0, 20)).thenReturn(java.util.List.of());
        when(followMapper.countFollowingsByAccountId(1L)).thenReturn(0L);
        assertEquals(BizCode.SUCCESS.getCode(), userService.getFollowers(1L, 1, 20).getCode());
        assertEquals(BizCode.SUCCESS.getCode(), userService.getFollowings(1L, 1, 20).getCode());

        Privacy publicRows = new Privacy();
        publicRows.setAccountId(3L);
        publicRows.setFollower(true);
        publicRows.setFollowing(true);
        when(privacyMapper.selectOne(any())).thenReturn(publicRows);
        when(cachedValues.get("privacy:3")).thenReturn("false");
        bindToken(loginAs(2L));
        when(followMapper.selectFollowersByTargetId(3L, 0, 20)).thenReturn(java.util.List.of());
        when(followMapper.countFollowersByTargetId(3L)).thenReturn(0L);
        when(followMapper.selectFollowingsByAccountId(3L, 0, 20)).thenReturn(java.util.List.of());
        when(followMapper.countFollowingsByAccountId(3L)).thenReturn(0L);
        assertEquals(BizCode.SUCCESS.getCode(), userService.getFollowers(3L, 1, 20).getCode());
        assertEquals(BizCode.SUCCESS.getCode(), userService.getFollowings(3L, 1, 20).getCode());
        verify(cachedValues, never()).get(any());
    }

    @Test
    void emptyProfileUpdateKeepsLegacySuccessWhenProfileSyncIsDisabled() {
        assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(new UpdateUserInfoDTO()).getCode());
    }

    @Test
    void banClosesTheLoginGateBeforeRevokingExistingSessions() {
        // The real update wrapper needs entity metadata even though this test mocks SQL I/O.
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "user-service-test"), User.class);
        User user = new User();
        user.setId(1L);
        user.setRole("user");
        when(userMapper.selectById(1L)).thenReturn(user);
        String existingToken = StpUtil.getTokenValue();
        AtomicBoolean disabledBeforeRevocation = new AtomicBoolean();
        StpUtil.setStpLogic(new StpLogic(StpUtil.TYPE) {
            @Override
            public void logout(Object loginId) {
                disabledBeforeRevocation.set(isDisable(loginId));
                super.logout(loginId);
            }
        });
        BanUserDTO dto = new BanUserDTO();
        dto.setReason("synthetic regression");

        assertEquals(0, userService.banUser(1L, dto).getCode());

        assertTrue(disabledBeforeRevocation.get(), "A concurrent login must see the ban before old sessions are revoked");
        assertTrue(StpUtil.isDisable(1L));
        assertNull(StpUtil.getLoginIdByToken(existingToken));
    }

    @Test
    void getInfoShouldReturnGenderBirthdayAndExtendedProfileFields() {
        UserInfoDTO dto = new UserInfoDTO();
        dto.setId(1L);
        dto.setUserName("alice");
        dto.setAvatar("avatar-key");
        dto.setGender("female");
        dto.setBirthday(LocalDate.of(2003, 5, 2));
        dto.setSignature("hello");
        dto.setHometownProvinceId(37);
        dto.setHometownCityId(3701);
        when(userMapper.selectUserInfoById(1L)).thenReturn(dto);
        when(objectStorageService.signGetUrl("avatar-key")).thenReturn("signed-avatar");

        Result<UserInfoDTO> result = userService.getInfo(1L);

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals("female", result.getData().getGender());
        assertEquals(LocalDate.of(2003, 5, 2), result.getData().getBirthday());
        assertEquals("hello", result.getData().getSignature());
        assertEquals(37, result.getData().getHometownProvinceId());
        assertEquals(3701, result.getData().getHometownCityId());
        assertEquals("signed-avatar", result.getData().getAvatar());
    }

    @Test
    void nicknameUpdateWritesDisplayNameWithoutUniquenessCheckOrTouchingLoginName() {
        when(locationValidationUtil.validateProvinceAndCity(null, null)).thenReturn(null);

        UpdateUserInfoDTO dto = new UpdateUserInfoDTO();
        dto.setUserName("干饭同学");

        Result<Void> result = userService.updateUserInfo(dto);

        assertEquals(BizCode.SUCCESS.getCode(), result.getCode());
        verify(userMapper, never()).exists(any());
        ArgumentCaptor<User> update = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(update.capture());
        assertEquals("干饭同学", update.getValue().getDisplayName());
        assertNull(update.getValue().getUserName());
    }

    @Test
    void avatarChangeShouldReleaseOnlyTheOldKeyAfterReferencingTheNewOne() {
        FileLifecycleService files = mock(FileLifecycleService.class);
        ReflectionTestUtils.setField(userService, "fileLifecycleService", files);
        User current = new User();
        current.setId(1L);
        current.setAvatar("avatars/old.png");
        when(userMapper.selectById(1L)).thenReturn(current);

        UpdateUserInfoDTO dto = new UpdateUserInfoDTO();
        dto.setAvatar("avatars/new.png");
        assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(dto).getCode());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(files);
        order.verify(files).finalizeAndReference(1L, UploadBiz.AVATAR, "avatars/new.png", "user_avatar", "1", "PUBLIC");
        order.verify(files).releaseReferences("user_avatar", "1", List.of("avatars/old.png"), "KEY_REPLACED");
    }

    @Test
    void avatarResubmissionOrFirstAvatarShouldNotReleaseAnything() {
        FileLifecycleService files = mock(FileLifecycleService.class);
        ReflectionTestUtils.setField(userService, "fileLifecycleService", files);
        User current = new User();
        current.setId(1L);
        current.setAvatar("avatars/same.png");
        when(userMapper.selectById(1L)).thenReturn(current);
        UpdateUserInfoDTO same = new UpdateUserInfoDTO();
        same.setAvatar("avatars/same.png");
        assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(same).getCode());

        User first = new User();
        first.setId(1L);
        when(userMapper.selectById(1L)).thenReturn(first);
        UpdateUserInfoDTO added = new UpdateUserInfoDTO();
        added.setAvatar("avatars/first.png");
        assertEquals(BizCode.SUCCESS.getCode(), userService.updateUserInfo(added).getCode());

        verify(files, never()).releaseReferences(any(), any(), any(), any());
    }
}
