package you.v50to.eatwhat.config;

import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.exception.BizException;

import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public Result<Void> notLoginExceptionHandler() {
        return Result.fail(BizCode.NOT_LOGIN);
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
