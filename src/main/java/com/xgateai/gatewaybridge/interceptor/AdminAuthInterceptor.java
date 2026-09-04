package com.xgateai.gatewaybridge.interceptor;

import com.alibaba.fastjson2.JSON;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.model.response.HttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * <p>
 * AdminAuthInterceptor 管理端登录鉴权拦截器
 * 校验会话中是否存在管理员登录标记，未登录返回 401
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    /**
     * 请求前置处理：校验管理端登录态，未登录返回 401 并写出统一错误响应
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param handler  处理器对象
     * @return true 放行；false 拦截
     * @throws IOException 响应写出异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(CommonConstant.SESSION_ADMIN_USER) == null) {
            response.setStatus(CommonConstant.HTTP_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.toJSONString(HttpResponse.error("未登录")));
            return false;
        }
        return true;
    }
}
