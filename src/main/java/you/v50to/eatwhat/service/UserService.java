package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.mapper.UserMapper;

@Service
@Slf4j
public class UserService {

    @Resource
    private UserMapper userMapper;

    public Result<UserInfoDTO> getInfo() {
        Long userId = StpUtil.getLoginIdAsLong();
        UserInfoDTO info = userMapper.selectUserInfoById(userId);
        if (info == null) {
            return Result.fail(BizCode.USER_NOT_FOUND);
        }
        return Result.ok(info);
    }
}
