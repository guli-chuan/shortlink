package com.github.niu.u.config;

import com.github.niu.u.common.core.R;
import com.github.niu.u.common.enums.ResultCode;
import com.github.niu.u.common.exception.BaseException;
import com.github.niu.u.common.ratelimit.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/24 12:28 下午
 **/
@RestControllerAdvice
@ResponseBody
public class MvcExceptionHandler {
    private Logger logger = LoggerFactory.getLogger(MvcExceptionHandler.class);
    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e, HttpServletRequest request, HttpServletResponse response){

        logger.error("请求：{}发生异常：{}", request.getRequestURI(), e);

        return R.failed(e.getMessage());
    }
    @ExceptionHandler(BaseException.class)
    public Object handleBaseException(Exception e, HttpServletRequest request, HttpServletResponse response){

        logger.error("请求：{}发生异常：{}", request.getRequestURI(), e);

        return R.failed(e.getMessage());
    }

    /**
     * 限流拒绝。
     * <p>
     * 这里必须把 HTTP 状态码改成 429，而不是只靠响应体里的 code。
     * 原因：调用方（尤其是脚本、网关、重试框架）通常先看状态码决定行为，
     * 返回 200 + {"success":false} 会被当成"请求成功但业务失败"，
     * 于是触发业务重试——越重试越超限，等于自己加压。
     * 429 是一个明确的、可被自动识别的信号：该退避了。
     * <p>
     * 日志用 debug 而不是 warn：拦截器那边已经在"本窗口第一次被拦"时打过一条 WARN，
     * 这里再打就是重复刷屏。被刷时每秒几百条日志，会把真正有用的信息淹掉。
     */
    @ExceptionHandler(RateLimitException.class)
    public Object handleRateLimit(RateLimitException e, HttpServletRequest request, HttpServletResponse response) {
        // Servlet 4.0 的 HttpServletResponse 里没有 SC_TOO_MANY_REQUESTS 常量（Servlet 6 才有），只能写字面量
        response.setStatus(429);
        // 告诉调用方等多久再试。没有这个头，客户端只能盲目重试或干脆放弃
        response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        response.setHeader("X-RateLimit-Limit", String.valueOf(e.getLimit()));
        response.setHeader("X-RateLimit-Remaining", "0");

        logger.debug("请求：{} 触发限流，{} 秒后重试", request.getRequestURI(), e.getRetryAfterSeconds());

        return R.failed(429, e.getMessage());
    }

    /**
     * 自定义注解异常拦截
     */
    @ExceptionHandler({BindException.class, ConstraintViolationException.class, MethodArgumentNotValidException.class})

    public Object handleMethodArgumentNotValidException(Exception e, HttpServletRequest request) {

        logger.error("请求：{}发生异常：{}", request.getRequestURI(), e);

        // 错误信息
        StringBuilder sb = new StringBuilder("参数校验失败：");
        // 错误信息map
        Map<String, String> error = new HashMap<>();

        String msg = "";
        if (!(e instanceof BindException) && !(e instanceof MethodArgumentNotValidException)) {
            for (ConstraintViolation cv: ((ConstraintViolationException)e).getConstraintViolations()) {
                msg = cv.getMessage();
                sb.append(msg).append("；");

                Iterator<Path.Node> it = cv.getPropertyPath().iterator();
                Path.Node last = null;
                while (it.hasNext()) {
                    last = (Path.Node)it.next();
                }
                /*for(last = null; it.hasNext(); last = (Path.Node)it.next()) {
                }*/
                error.put(last != null ? last.getName() : "", msg);
            }
        } else {
            List<ObjectError> allErrors = null;
            if (e instanceof BindException) {
                allErrors = ((BindException)e).getAllErrors();
            } else {
                allErrors = ((MethodArgumentNotValidException)e).getBindingResult().getAllErrors();
            }
            // 拼接错误信息
            for (ObjectError oe : allErrors) {
                msg = oe.getDefaultMessage();
                sb.append(msg).append("；");
                if (oe instanceof FieldError) {
                    error.put(((FieldError)oe).getField(), msg);
                } else {
                    error.put(oe.getObjectName(), msg);
                }
            }
        }
        R r =   R.failed(ResultCode.PARAM_IS_INVALID,sb.toString());
        r.setData(error);
        return  r;
    }
}
