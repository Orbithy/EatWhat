package you.v50to.eatwhat.config;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.exception.BizException;

import java.util.List;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public Result<Void> notLoginExceptionHandler() {
        return Result.fail(BizCode.NOT_LOGIN);
    }

    @ExceptionHandler(cn.dev33.satoken.exception.DisableServiceException.class)
    public Result<Void> disableServiceExceptionHandler() {
        return Result.fail(BizCode.USER_DISABLED);
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotRoleException.class)
    public Result<Void> notRoleExceptionHandler(cn.dev33.satoken.exception.NotRoleException e) {
        String msg = switch (e.getRole()) {
            case "verified" -> "用户未验证";
            case "admin" -> "需要管理员权限";
            default -> "权限不足";
        };
        return Result.fail(BizCode.NO_PERMISSION, msg);
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotPermissionException.class)
    public Result<Void> notPermissionExceptionHandler() {
        return Result.fail(BizCode.NO_PERMISSION, "权限不足");
    }

    /**
     * 统一处理业务异常
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        return new Result<>(
                e.getBizCode().getCode(),
                null,
                e.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Sentry.withScope(scope -> {
            scope.setLevel(SentryLevel.WARNING);
            scope.setFingerprint(List.of("param-type-mismatch", ex.getName()));
            scope.setTag("error_type", "param_type_mismatch");
            scope.setExtra("param", ex.getName());
            scope.setExtra("value", String.valueOf(ex.getValue()));
            Sentry.captureException(ex);
        });

        return Result.fail(BizCode.PARAM_INVALID);
    }

    @ExceptionHandler(BadSqlGrammarException.class)
    public Result<Void> handleBadSql(BadSqlGrammarException e) {
        log.error(e.getMessage());
        Sentry.captureException(e);
        return Result.fail(BizCode.DB_ERROR, "数据库未初始化或表不存在");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.error(ex.getMessage(), ex);
        Sentry.captureException(ex);
        return Result.fail(BizCode.PARAM_INVALID);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidException(MethodArgumentNotValidException e) {
        String msg = Objects.requireNonNull(e.getBindingResult()
                        .getFieldError())
                .getDefaultMessage();
        return Result.fail(BizCode.PARAM_INVALID, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleAll(Exception e) {
        log.error(e.getMessage(), e);
        Sentry.captureException(e);
        return Result.fail(BizCode.UNKNOWN_ERROR);
    }
}
