package you.v50to.eatwhat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.data.dto.UpdateUserInfoDTO;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.FollowMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserInfoMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.utils.LocationValidationUtil;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserMapper userMapper;
    private UserInfoMapper userInfoMapper;
    private LocationValidationUtil locationValidationUtil;
    private ObjectStorageService objectStorageService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userInfoMapper = mock(UserInfoMapper.class);
        locationValidationUtil = mock(LocationValidationUtil.class);
        objectStorageService = mock(ObjectStorageService.class);

        userService = new UserService();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "locationValidationUtil", locationValidationUtil);
        ReflectionTestUtils.setField(userService, "contactMapper", mock(ContactMapper.class));
        ReflectionTestUtils.setField(userService, "privacyMapper", mock(PrivacyMapper.class));
        ReflectionTestUtils.setField(userService, "followMapper", mock(FollowMapper.class));
        ReflectionTestUtils.setField(userService, "smsService", mock(SmsService.class));
        ReflectionTestUtils.setField(userService, "objectStorageService", objectStorageService);
        ReflectionTestUtils.setField(userService, "notificationService", mock(NotificationService.class));
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
    void updateUserInfoShouldReturnUsernameExistsWhenUsernameAlreadyTaken() {
        when(locationValidationUtil.validateProvinceAndCity(null, null)).thenReturn(null);
        when(userMapper.exists(any())).thenReturn(true);

        UpdateUserInfoDTO dto = new UpdateUserInfoDTO();
        dto.setUserName("takenName");

        Result<Void> result = userService.updateUserInfo(dto);

        assertEquals(BizCode.USERNAME_EXISTS.getCode(), result.getCode());
        verify(userMapper, never()).updateById(any(User.class));
        verify(userInfoMapper, never()).selectById(any());
    }

    @Test
    void updateUserInfoShouldReturnUsernameExistsWhenDatabaseRejectsDuplicateUsername() {
        when(locationValidationUtil.validateProvinceAndCity(null, null)).thenReturn(null);
        when(userMapper.exists(any())).thenReturn(false);
        doThrow(new DataIntegrityViolationException("duplicate username")).when(userMapper).updateById(any(User.class));

        UpdateUserInfoDTO dto = new UpdateUserInfoDTO();
        dto.setUserName("takenName");

        Result<Void> result = userService.updateUserInfo(dto);

        assertEquals(BizCode.USERNAME_EXISTS.getCode(), result.getCode());
        verify(userInfoMapper, never()).selectById(any());
    }
}
