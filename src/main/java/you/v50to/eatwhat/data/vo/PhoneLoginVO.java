package you.v50to.eatwhat.data.vo;

import cn.dev33.satoken.stp.SaTokenInfo;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.BeanUtils;

/** 登录会话信息，另带 newUser：本次是否新注册（前端据此进入认证引导）。 */
@Getter
@Setter
public class PhoneLoginVO extends SaTokenInfo {
    private Boolean newUser;

    public static PhoneLoginVO of(SaTokenInfo info, boolean newUser) {
        PhoneLoginVO vo = new PhoneLoginVO();
        BeanUtils.copyProperties(info, vo);
        vo.setNewUser(newUser);
        return vo;
    }
}
