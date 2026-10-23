package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import you.v50to.eatwhat.sync.relation.RelationCommands;
import you.v50to.eatwhat.sync.relation.RelationKind;
import you.v50to.eatwhat.sync.profile.ProfileCommands;
import you.v50to.eatwhat.sync.privacy.PrivacyCommands;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.dto.*;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.po.*;
import you.v50to.eatwhat.data.vo.AccountStatusVO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.UserListItemVO;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.FollowMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserInfoMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.data.enums.UploadBiz;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.utils.LocationValidationUtil;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import static you.v50to.eatwhat.utils.ValidUtil.validPage;
import static you.v50to.eatwhat.utils.ValidUtil.validPageSize;

@Service
@Slf4j
public class UserService {
    @Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;

    @Autowired(required=false)
    private RelationCommands relations;

    @Autowired(required=false)
    private ProfileCommands profileCommands;

    @Autowired(required=false)
    private PrivacyCommands privacyCommands;

    @Autowired(required=false)
    private ManagedDomainWriteGuard managedDomainWriteGuard;

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
    @Autowired(required = false)
    private FileLifecycleService fileLifecycleService;
    @Resource
    private NotificationService notificationService;

    public Result<UserInfoDTO> getInfo(Long userId) {
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
        if (scene == null) {
            return Result.fail(BizCode.PARAM_MISSING, "scene不能为空");
        }
        if (scene == Scene.login) {
            return Result.fail(BizCode.PARAM_INVALID, "登录验证码请用 /auth/phone/code");
        }
        if (!StpUtil.hasRole("verified")) {
            return Result.fail(BizCode.STATE_NOT_ALLOWED, "未通过认证，无法发送验证码");
        }
        if (scene == Scene.bind && contactMapper.exists(new LambdaQueryWrapper<Contact>()
                .eq(Contact::getAccountId, StpUtil.getLoginIdAsLong()))) {
            return Result.fail(BizCode.OP_FAILED, "已绑定手机号，无法重复绑定");
        }
        smsService.sendCode(scene, mobile, ip, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    public Result<Void> bindMobile(Scene scene, BindMobileReq bindMobileReq) {
        if(multiwriter!=null && multiwriter.enabled() && !multiwriter.inCommand()) return multiwriter.command(StpUtil.getLoginIdAsLong(),StpUtil.getLoginIdAsLong(),()->bindMobile(scene,bindMobileReq));
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
        if (smsService.verifyCode(scene, mobile, code, userId)) {
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
     * 更新用户个人信息（昵称、头像、性别、生日、签名、家乡）
     *
     * @param dto 更新数据
     * @return 更新结果
     */
    @Transactional
    public Result<Void> updateUserInfo(UpdateUserInfoDTO dto) {
        if(multiwriter!=null && multiwriter.enabled() && !multiwriter.inCommand()) return multiwriter.command(StpUtil.getLoginIdAsLong(),StpUtil.getLoginIdAsLong(),()->updateUserInfo(dto));
        Long userId = StpUtil.getLoginIdAsLong();
        // 省份城市校验（使用独立服务）
        Result<Void> validationResult = locationValidationUtil.validateProvinceAndCity(
                dto.getHometownProvinceId(),
                dto.getHometownCityId());
        if (validationResult != null) {
            return validationResult;
        }

        if (profileCommands != null) {
            return profileCommands.atomic(() -> {
                profileCommands.lockSubject(userId);
                if (ProfileCommands.hasProfileFields(dto) && !profileCommands.canEdit(userId)) {
                    return Result.fail(BizCode.DATA_SYNC_PENDING);
                }
                if (profileCommands.hasOpenConflict(userId, dto)) {
                    return Result.fail(BizCode.DATA_SYNC_PENDING);
                }
                var before = ProfileCommands.hasProfileFields(dto)
                        ? profileCommands.snapshotBeforeUpdate(userId) : null;
                if (before != null && !profileCommands.effectiveHometownValid(dto, before)) {
                    return Result.fail(BizCode.PARAM_INVALID);
                }
                if (before != null) profileCommands.prepareEdit(userId, before);
                Result<Void> result = updateUserInfoLegacy(userId, dto);
                if (result.getCode() == BizCode.SUCCESS.getCode() && before != null) {
                    profileCommands.capture(userId, dto, before);
                }
                return result;
            });
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.profileManaged(userId)
                && ProfileCommands.hasProfileFields(dto)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }
        return updateUserInfoLegacy(userId, dto);
    }

    private Result<Void> updateUserInfoLegacy(Long userId, UpdateUserInfoDTO dto) {
        // 更新 users 表中的昵称和头像（key）。昵称写 display_name，不改登录名，也不查重。
        if (dto.getUserName() != null || dto.getAvatar() != null) {
            User currentUser = userMapper.selectById(userId);

            User user = new User();
            user.setId(userId);
            if (dto.getUserName() != null) {
                user.setDisplayName(dto.getUserName());
            }
            String replacedAvatar = null;
            if (dto.getAvatar() != null) {
                if (fileLifecycleService != null && (currentUser == null || !dto.getAvatar().equals(currentUser.getAvatar()))) {
                    fileLifecycleService.finalizeAndReference(userId, UploadBiz.AVATAR, dto.getAvatar(), "user_avatar", userId.toString(), "PUBLIC");
                    if (currentUser != null) replacedAvatar = currentUser.getAvatar();
                }
                user.setAvatar(dto.getAvatar());
            }
            userMapper.updateById(user);
            // The avatar really changed: release the old key's reference in the same transaction.
            if (replacedAvatar != null) fileLifecycleService.releaseReferences("user_avatar", userId.toString(), List.of(replacedAvatar), "KEY_REPLACED");
        }

        // 更新 user_info 表
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
     * @param userId   用户ID，不传则为当前用户
     * @param page     页码（从1开始）
     * @param pageSize 每页大小
     * @return 粉丝列表
     */
    public Result<PageResult<FansDTO>> getFollowers(Long userId, Integer page, Integer pageSize) {
        if (userId == null || userId <= 0) {
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

        // Privacy is authorization-sensitive. Read the database on every request so stale Redis cannot disclose lists.
        Privacy p = privacyMapper.selectOne(new LambdaQueryWrapper<Privacy>().eq(Privacy::getAccountId, userId));
        boolean visible = p == null || !Boolean.FALSE.equals(p.getFollower());

        if (!visible && !userId.equals(StpUtil.getLoginIdAsLong())) {
            return Result.fail(BizCode.NOT_SUPPORTED, "未公开");
        }

        // 计算偏移量
        int offset = (page - 1) * pageSize;

        // 查询数据和总数
        List<FansDTO> followers = followMapper.selectFollowersByTargetId(userId, offset, pageSize);
        signFansAvatar(followers);
        Long totalItems = followMapper.countFollowersByTargetId(userId);

        // 构建分页结果
        PageResult<FansDTO> pageResult = PageResult.of(followers, page.longValue(), pageSize.longValue(), totalItems);
        return Result.ok(pageResult);
    }

    public Result<Void> changePrivacy(PrivacyDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();

        if (privacyCommands == null && managedDomainWriteGuard != null
                && managedDomainWriteGuard.privacyManaged(userId)
                && (dto.getFollowing() != null || dto.getFollower() != null)) {
            return Result.fail(BizCode.DATA_SYNC_PENDING);
        }

        if (privacyCommands != null) {
            return privacyCommands.atomic(() -> {
                privacyCommands.lockSubject(userId);
                var before = privacyCommands.snapshotBeforeChange(userId);
                privacyCommands.prepareChange(userId, before);
                Result<Void> result = changePrivacyLegacy(userId, dto);
                if (result.getCode() == BizCode.SUCCESS.getCode()
                        && (dto.getFollowing() != null || dto.getFollower() != null)) {
                    privacyCommands.capture(userId, dto, before);
                }
                return result;
            });
        }
        return changePrivacyLegacy(userId, dto);
    }

    private Result<Void> changePrivacyLegacy(Long userId, PrivacyDTO dto) {
        Privacy p = new Privacy();
        p.setAccountId(userId);
        p.setFollowing(dto.getFollowing());
        p.setFollower(dto.getFollower());

        int updated = privacyMapper.update(p, new LambdaUpdateWrapper<Privacy>()
                .eq(Privacy::getAccountId, userId));
        if (updated == 0) {
            // 兼容历史数据：不存在则插入（表上有 UNIQUE(account_id)）
            privacyMapper.insert(p);
        }

        // 刷新/清理缓存，避免隐私设置长期不生效
        String key = "privacy:" + userId;
        try {
            redis.delete(key);
        } catch (Exception ignored) {
        }
        return Result.ok();
    }

    /**
     * 获取关注列表
     *
     * @param userId   用户ID，不传则为当前用户
     * @param page     页码（从1开始）
     * @param pageSize 每页大小
     * @return 关注列表
     */
    public Result<PageResult<FansDTO>> getFollowings(Long userId, Integer page, Integer pageSize) {
        if (userId == null || userId <= 0) {
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

        // Privacy is authorization-sensitive; the cached value is never the access-control decision.
        Privacy p = privacyMapper.selectOne(new LambdaQueryWrapper<Privacy>().eq(Privacy::getAccountId, userId));
        boolean visible = p == null || !Boolean.FALSE.equals(p.getFollowing());

        if (!visible && !userId.equals(StpUtil.getLoginIdAsLong())) {
            return Result.fail(BizCode.NOT_SUPPORTED, "未公开");
        }

        // 计算偏移量
        int offset = (page - 1) * pageSize;

        // 查询数据和总数
        List<FansDTO> followings = followMapper.selectFollowingsByAccountId(userId, offset, pageSize);
        signFansAvatar(followings);
        Long totalItems = followMapper.countFollowingsByAccountId(userId);

        // 构建分页结果
        PageResult<FansDTO> pageResult = PageResult.of(followings, page.longValue(), pageSize.longValue(), totalItems);
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
        if (relations != null) {
            return relations.change(RelationKind.follow, selfId, userId, null, true)
                    ? Result.ok() : Result.fail(BizCode.OP_FAILED, "你已经关注过TA了");
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.follow, selfId, userId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        Follow f = new Follow();
        f.setAccountId(selfId);
        f.setTargetId(userId);
        try {
            followMapper.insert(f);
            notificationService.notifyFollow(selfId, userId);
        } catch (DuplicateKeyException e) {
            return Result.fail(BizCode.OP_FAILED, "你已经关注过TA了");
        }
        return Result.ok();
    }

    public Result<Void> unfollow(Long userId) {
        Long selfId = StpUtil.getLoginIdAsLong();
        if (relations != null) {
            relations.change(RelationKind.follow, selfId, userId, null, false);
            return Result.ok();
        }
        if (managedDomainWriteGuard != null && managedDomainWriteGuard.relationManaged(
                RelationKind.follow, selfId, userId, null)) return Result.fail(BizCode.DATA_SYNC_PENDING);
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getAccountId, selfId)
                .eq(Follow::getTargetId, userId));
        return Result.ok();
    }

    public Result<PageResult<UserListItemVO>> listUsers(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        IPage<UserInfoDTO> result = userMapper.selectUserPage(new Page<>(page, pageSize));
        List<UserListItemVO> vos = result.getRecords().stream().map(u -> {
            UserListItemVO vo = new UserListItemVO();
            vo.setId(u.getId());
            vo.setUserName(u.getUserName());
            vo.setAvatar(objectStorageService.signGetUrl(u.getAvatar()));
            vo.setRole(u.getRole());
            vo.setEmail(u.getEmail());
            vo.setPhone(u.getPhone());
            vo.setVerified(u.getVerified());
            vo.setMethod(u.getMethod());
            vo.setStudentId(u.getStudentId());
            vo.setRealName(u.getRealName());
            vo.setVerifiedEmail(u.getVerifiedEmail());
            vo.setCreatedAt(u.getCreatedAt());
            vo.setUpdatedAt(u.getUpdatedAt());

            AccountStatusVO status = new AccountStatusVO();
            if (Boolean.TRUE.equals(u.getBanned())) {
                status.setAccountStatus("BANNED");
                status.setBanReason(u.getBanReason());
                status.setBanExpireAt(null);
                if (u.getBannedAt() != null) {
                    String iso = java.time.OffsetDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(u.getBannedAt()), ZoneOffset.ofHours(8)
                    ).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    status.setBannedAt(iso);
                }
            } else {
                status.setAccountStatus("NORMAL");
            }
            vo.setAccountStatus(status);
            return vo;
        }).collect(Collectors.toList());

        return Result.ok(PageResult.of(vos, result.getCurrent(), result.getSize(), result.getTotal()));
    }

    /**
     * 封禁用户（永久）
     * 不能封禁管理员
     */
    public Result<Void> banUser(Long userId, BanUserDTO dto) {
        if(multiwriter!=null && multiwriter.enabled() && !multiwriter.inCommand()) return multiwriter.command(StpUtil.getLoginIdAsLong(),userId,()->banUser(userId,dto));
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        if ("admin".equals(user.getRole())) {
            return Result.fail(BizCode.OP_FAILED, "不能封禁管理员");
        }
        // 先关闭新登录入口，再踢出现有 token，避免撤销会话与设置禁用标记之间重新登录。
        StpUtil.disable(userId, -1);
        StpUtil.logout(userId);
        // 同步更新数据库封禁状态、封禁原因和封禁时间
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getBanned, true)
                .set(User::getBanReason, dto.getReason())
                .set(User::getBannedAt, new java.sql.Timestamp(System.currentTimeMillis())));
        return Result.ok();
    }

    /**
     * 解封用户
     */
    public Result<Void> unbanUser(Long userId) {
        if(multiwriter!=null && multiwriter.enabled() && !multiwriter.inCommand()) return multiwriter.command(StpUtil.getLoginIdAsLong(),userId,()->unbanUser(userId));
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        StpUtil.untieDisable(userId);
        // 同步更新数据库封禁状态，清空封禁原因和封禁时间
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getBanned, false)
                .set(User::getBanReason, null)
                .set(User::getBannedAt, null));
        return Result.ok();
    }
}
