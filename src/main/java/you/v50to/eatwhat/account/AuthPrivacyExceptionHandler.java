package you.v50to.eatwhat.account;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.controller.CallbackController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.exception.BizException;

import java.util.Map;
import java.util.Set;

/** Authentication parse errors may contain passwords/proofs; never attach their messages or causes to logs/APM. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AuthController.class, CallbackController.class, AccountConsentController.class})
public final class AuthPrivacyExceptionHandler {
    // Fixed application text audited in SmsService. Never pass through provider text or request values.
    private static final Map<BizCode, Set<String>> SAFE_BUSINESS_DETAILS = Map.of(
            BizCode.TOO_MANY_REQUESTS, Set.of("请求过于频繁，请稍后再试", "短信发送过于频繁"),
            BizCode.THIRD_PARTY_TIMEOUT, Set.of("短信服务请求超时"),
            BizCode.THIRD_PARTY_UNAVAILABLE, Set.of("短信服务不可用"),
            BizCode.PARAM_MISSING, Set.of("scene不能为空", "手机号不能为空"),
            BizCode.PARAM_INVALID, Set.of("手机号格式不正确"));

    @ExceptionHandler(Exception.class)
    public Result<?> safe(Exception failure) {
        if (failure instanceof NotLoginException) return Result.fail(BizCode.NOT_LOGIN);
        if (failure instanceof DisableServiceException) return Result.fail(BizCode.USER_DISABLED);
        if (failure instanceof NotRoleException role) {
            String message = switch (role.getRole()) {
                case "verified" -> "用户未验证";
                case "admin" -> "需要管理员权限";
                default -> "权限不足";
            };
            return Result.fail(BizCode.NO_PERMISSION, message);
        }
        if (failure instanceof NotPermissionException) return Result.fail(BizCode.NO_PERMISSION, "权限不足");
        if (failure instanceof BizException business) {
            BizCode code = business.getBizCode();
            for (String detail : SAFE_BUSINESS_DETAILS.getOrDefault(code, Set.of())) {
                if ((code.getMsg() + "：" + detail).equals(business.getMessage())) return Result.fail(code, detail);
            }
            return Result.fail(code);
        }
        if (failure instanceof HttpMessageNotReadableException || failure instanceof MethodArgumentTypeMismatchException) {
            return Result.fail(BizCode.PARAM_INVALID);
        }
        if (failure instanceof MethodArgumentNotValidException invalid) {
            var error = invalid.getBindingResult().getFieldError();
            // Only application-authored validation messages; never the rejected field value or exception body.
            return error == null ? Result.fail(BizCode.PARAM_INVALID)
                    : Result.fail(BizCode.PARAM_INVALID, error.getDefaultMessage());
        }
        if (failure instanceof BadSqlGrammarException) return Result.fail(BizCode.DB_ERROR, "数据库未初始化或表不存在");
        if (failure instanceof AccountUnavailableException || failure instanceof ConsentStore.ConsentException) {
            return Result.fail(BizCode.AUTH_UNAVAILABLE);
        }
        return Result.fail(BizCode.UNKNOWN_ERROR);
    }
}
