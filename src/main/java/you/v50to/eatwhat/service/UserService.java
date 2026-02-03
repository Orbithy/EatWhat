package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Contact;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.UserMapper;

@Service
@Slf4j
public class UserService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private SmsService smsService;
    @Resource
    private ContactMapper contactMapper;

    public Result<UserInfoDTO> getInfo() {
        Long userId = StpUtil.getLoginIdAsLong();
        UserInfoDTO info = userMapper.selectUserInfoById(userId);
        if (info == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        return Result.ok(info);
    }

    public Result<Void> getCode(String scene, String mobile, String ip) {
        if (StpUtil.getRoleList() == null){
            return Result.fail(BizCode.STATE_NOT_ALLOWED, "未通过认证，无法发送验证码");
        }
        smsService.sendCode(scene, mobile, ip);
        return Result.ok();
    }

    public Result<Void> bindMobile(String scene, String mobile, String code) {
        if (smsService.verifyCode(scene, mobile,code)){
            Contact contact = new Contact();
            contact.setAccountId(StpUtil.getLoginIdAsLong());
            contact.setPhone(mobile);
            contactMapper.insert(contact);
            return Result.ok();
        } else {
            return Result.fail(BizCode.VERIFY_CODE_ERROR);
        }
    }
}
