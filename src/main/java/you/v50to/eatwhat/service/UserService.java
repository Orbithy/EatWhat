package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.*;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.po.*;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.FollowMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserInfoMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.utils.LocationValidationUtil;

import java.util.List;

@Service
@Slf4j
public class UserService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private SmsService smsService;
    @Resource
    private ContactMapper contactMapper;
    @Resource
    private UserInfoMapper userInfoMapper;
    @Resource
    private PrivacyMapper privacyMapper;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private LocationValidationUtil locationValidationUtil;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private ObjectStorageService objectStorageService;

    public Result<UserInfoDTO> getInfo() {
        Long userId = StpUtil.getLoginIdAsLong();
        UserInfoDTO info = userMapper.selectUserInfoById(userId);
        if (info == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        info.setAvatar(objectStorageService.signGetUrl(info.getAvatar()));
        return Result.ok(info);
    }

    public Result<Void> getCode(SendCodeReq sendCodeReq, String ip) {
        Scene scene = sendCodeReq.getScene();
        String mobile = sendCodeReq.getMobile();
        if (!StpUtil.hasRole("verified")) {
            return Result.fail(BizCode.STATE_NOT_ALLOWED, "未通过认证，无法发送验证码");
        }
        if (scene.equals(Scene.bind) && contactMapper.exists(new LambdaQueryWrapper<Contact>()
                .eq(Contact::getAccountId, StpUtil.getLoginIdAsLong()))) {
            return Result.fail(BizCode.OP_FAILED, "已绑定手机号，无法重复绑定");
        }
        smsService.sendCode(scene, mobile, ip);
        return Result.ok();
    }

    public Result<Void> bindMobile(Scene scene, BindMobileReq bindMobileReq) {
        String mobile = bindMobileReq.getMobile();
        String code = bindMobileReq.getCode();
        Long userId = StpUtil.getLoginIdAsLong();

        if (contactMapper.exists(new LambdaQueryWrapper<Contact>()
                .eq(Contact::getAccountId, userId))) {
            return Result.fail(BizCode.OP_FAILED, "已绑定手机号，无法重复绑定");
        }
        if (contactMapper.exists(new LambdaQueryWrapper<Contact>()
                .eq(Contact::getPhone, mobile))) {
            return Result.fail(BizCode.OP_FAILED, "该手机号已被绑定");
        }
        if (smsService.verifyCode(scene, mobile, code)) {
            Contact contact = new Contact();
            contact.setAccountId(userId);
            contact.setPhone(mobile);
            contactMapper.insert(contact);
            return Result.ok();
        } else {
            return Result.fail(BizCode.VERIFY_CODE_ERROR);
        }
    }

    /**
     * 更新用户个人信息
     *
     * @param dto 更新数据
     * @return 更新结果
     */
    public Result<Void> updateUserInfo(UpdateUserInfoDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();

        // 省份城市校验（使用独立服务）
        Result<Void> validationResult = locationValidationUtil.validateProvinceAndCity(
                dto.getHometownProvinceId(),
                dto.getHometownCityId());
        if (validationResult != null) {
            return validationResult;
        }

        // 查询现有用户信息
        UserInfo userInfo = userInfoMapper.selectById(userId);

        if (userInfo == null) {
            // 首次填写，创建新记录
            userInfo = new UserInfo();
            userInfo.setId(userId);
            updateUserInfoFields(userInfo, dto);
            userInfoMapper.insert(userInfo);
        } else {
            // 更新已有记录（部分更新）
            updateUserInfoFields(userInfo, dto);
            userInfoMapper.updateById(userInfo);
        }

        return Result.ok();
    }

    /**
     * 更新 UserInfo 的字段（只更新非空字段）
     *
     * @param userInfo 要更新的 UserInfo 对象
     * @param dto      包含更新数据的 DTO
     */
    private void updateUserInfoFields(UserInfo userInfo, UpdateUserInfoDTO dto) {
        if (dto.getGender() != null) {
            userInfo.setGender(dto.getGender());
        }
        if (dto.getBirthday() != null) {
            userInfo.setBirthday(dto.getBirthday());
        }
        if (dto.getSignature() != null) {
            userInfo.setSignature(dto.getSignature());
        }
        if (dto.getHometownProvinceId() != null) {
            userInfo.setHometownProvinceId(dto.getHometownProvinceId());
        }
        if (dto.getHometownCityId() != null) {
            userInfo.setHometownCityId(dto.getHometownCityId());
        }
    }

    /**
     * 获取粉丝列表
     *
     * @param userId 用户ID，不传则为当前用户
     * @param page 页码（从1开始）
     * @param pageSize 每页大小
     * @return 粉丝列表
     */
    public Result<PageResult<FansDTO>> getFollowers(Long userId, Integer page, Integer pageSize) {
        if (userId == null  || userId <= 0) {
            userId = StpUtil.getLoginIdAsLong();
        }

        // 参数校验和默认值
        if (page == null || page < 1) {
            page = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100; // 限制最大每页数量
        }

        // 检查隐私设置
        String key = "privacy:" + userId;
        Object v = redis.opsForHash().get(key, "follower");
        if (v == null) {
            Privacy p = privacyMapper.selectOne(
                    new LambdaQueryWrapper<Privacy>()
                            .eq(Privacy::getAccountId, userId));
            if (p != null) {
                redis.opsForHash().put(key, "follower", p.getFollower().toString());
                redis.opsForHash().put(key, "following", p.getFollowing().toString());
                v = p.getFollower().toString();
            } else {
                // 如果没有隐私设置记录，默认为公开
                v = "true";
            }
        }

        if ("false".equals(v.toString()) && !userId.equals(StpUtil.getLoginIdAsLong())) {
            return Result.fail(BizCode.NOT_SUPPORTED, "未公开");
        }

        // 计算偏移量
        int offset = (page - 1) * pageSize;

        // 查询数据和总数
        List<FansDTO> followers = followMapper.selectFollowersByTargetId(userId, offset, pageSize);
        signFansAvatar(followers);
        Long totalItems = followMapper.countFollowersByTargetId(userId);

        // 构建分页结果
        PageResult<FansDTO> pageResult = PageResult.of(followers, page, pageSize, totalItems);
        return Result.ok(pageResult);
    }

    public Result<Void> changePrivacy(PrivacyDTO dto) {
        Privacy p = new Privacy();
        p.setAccountId(StpUtil.getLoginIdAsLong());
        p.setFollowing(dto.getFollowing());
        p.setFollower(dto.getFollower());
        privacyMapper.updateById(p);
        return Result.ok();
    }

    /**
     * 获取关注列表
     *
     * @param userId 用户ID，不传则为当前用户
     * @param page 页码（从1开始）
     * @param pageSize 每页大小
     * @return 关注列表
     */
    public Result<PageResult<FansDTO>> getFollowings(Long userId, Integer page, Integer pageSize) {
        if (userId == null  || userId <= 0) {
            userId = StpUtil.getLoginIdAsLong();
        }

        // 参数校验和默认值
        if (page == null || page < 1) {
            page = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100; // 限制最大每页数量
        }

        // 检查隐私设置
        String key = "privacy:" + userId;
        Object v = redis.opsForHash().get(key, "following");
        if (v == null) {
            Privacy p = privacyMapper.selectOne(
                    new LambdaQueryWrapper<Privacy>()
                            .eq(Privacy::getAccountId, userId));
            if (p != null) {
                redis.opsForHash().put(key, "follower", p.getFollower().toString());
                redis.opsForHash().put(key, "following", p.getFollowing().toString());
                v = p.getFollowing().toString();
            } else {
                // 如果没有隐私设置记录，默认为公开
                v = "true";
            }
        }

        if ("false".equals(v.toString()) && !userId.equals(StpUtil.getLoginIdAsLong())) {
            return Result.fail(BizCode.NOT_SUPPORTED, "未公开");
        }

        // 计算偏移量
        int offset = (page - 1) * pageSize;

        // 查询数据和总数
        List<FansDTO> followings = followMapper.selectFollowingsByAccountId(userId, offset, pageSize);
        signFansAvatar(followings);
        Long totalItems = followMapper.countFollowingsByAccountId(userId);

        // 构建分页结果
        PageResult<FansDTO> pageResult = PageResult.of(followings, page, pageSize, totalItems);
        return Result.ok(pageResult);
    }

    /**
     * 获取其他用户信息
     *
     * @param userId 目标用户ID
     * @return 用户信息
     */
    public Result<OtherUserInfoDTO> getUserInfo(Long userId) {
        Long currentUserId = StpUtil.getLoginIdAsLong();

        OtherUserInfoDTO info = userMapper.selectOtherUserInfo(userId, currentUserId);

        if (info == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }

        info.setAvatar(objectStorageService.signGetUrl(info.getAvatar()));

        return Result.ok(info);
    }

    private void signFansAvatar(List<FansDTO> fans) {
        if (fans == null || fans.isEmpty()) {
            return;
        }
        for (FansDTO fan : fans) {
            fan.setAvatar(objectStorageService.signGetUrl(fan.getAvatar()));
        }
    }

    public Result<Void> follow(Long userId) {
        Long selfId = StpUtil.getLoginIdAsLong();
        if (userId.equals(selfId)) {
            return Result.fail(BizCode.OP_FAILED, "不能关注自己");
        }
        Follow f = new Follow();
        f.setAccountId(selfId);
        f.setTargetId(userId);
        followMapper.insert(f);
        try {
            followMapper.insert(f);
        } catch (DuplicateKeyException e) {
            return Result.fail(BizCode.OP_FAILED, "你已经关注过TA了");
        }
        return Result.ok();
    }

    public Result<Void> unfollow(Long userId) {
        Long selfId = StpUtil.getLoginIdAsLong();
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getAccountId, selfId)
                .eq(Follow::getTargetId, userId));
        return Result.ok();
    }
}
