package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /** @RequestBody + @Valid 字段校验失败（如 @NotBlank） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String fieldMsg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    if (StringUtils.hasText(fe.getDefaultMessage())) {
                        return fe.getDefaultMessage();
                    }
                    return fe.getField() + " 校验未通过";
                })
                .collect(Collectors.joining("; "));
        String globalMsg = e.getBindingResult().getGlobalErrors().stream()
                .map(ge -> ge.getDefaultMessage() != null ? ge.getDefaultMessage() : ge.getObjectName())
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("; "));
        String combined = Stream.of(fieldMsg, globalMsg)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("; "));
        String msg = StringUtils.hasText(combined) ? combined : e.getMessage();
        log.warn("参数校验失败: {}", msg, e);
        return Result.fail(StringUtils.hasText(msg) ? msg : ("参数校验失败: " + e.getClass().getSimpleName()));
    }

    /** JSON 体无法解析（缺 Content-Type、非法 JSON 等） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(StringUtils.hasText(e.getMessage()) ? e.getMessage() : "请求体无法解析，请使用 application/json 发送 JSON");
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            BindException.class
    })
    public Result handleBadRequestException(Exception e) {
        log.warn("请求参数错误: {}", e.getMessage(), e);
        String detail = e.getMessage();
        if (e instanceof BindException && !(e instanceof MethodArgumentNotValidException)) {
            BindException be = (BindException) e;
            String bindDetail = be.getBindingResult().getFieldErrors().stream()
                    .map(fe -> StringUtils.hasText(fe.getDefaultMessage())
                            ? fe.getDefaultMessage()
                            : fe.getField() + " 无效")
                    .collect(Collectors.joining("; "));
            if (StringUtils.hasText(bindDetail)) {
                detail = bindDetail;
            }
        }
        if (!StringUtils.hasText(detail)) {
            detail = e.getClass().getSimpleName();
        }
        return Result.fail(detail);
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error("运行时异常", e);
        return Result.fail("服务器异常");
    }

    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("未处理异常", e);
        return Result.fail("服务器异常");
    }
}
