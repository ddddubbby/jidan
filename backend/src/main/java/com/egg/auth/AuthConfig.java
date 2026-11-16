package com.egg.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.egg.common.Access;
import com.egg.common.Access.Who;
import com.egg.common.ApiException;
import com.egg.common.CurrentUser;
import com.egg.common.ErrorCode;
import com.egg.common.Role;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 登录拦截与当前登录人（开发规格 7.2）：每个 /api 请求带 Authorization: Bearer token，
 * 拦截器按 @Access 判断谁能调，并把当前登录人交给控制器方法的 CurrentUser 参数。
 * 拦截器、参数解析器与注册都放在这一个文件里，其它包新增接口只需要标 @Access。
 */
@Configuration
public class AuthConfig implements WebMvcConfigurer {

    private static final String ATTR = AuthConfig.class.getName() + ".user";

    static {
        // CurrentUser 由拦截器注入，不是请求参数，不要出现在 OpenAPI 里
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }

    private final AuthService auth;

    public AuthConfig(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Guard()).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new UserArgument());
    }

    private class Guard implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
            // 没有对应控制器方法的请求（路径不存在等）放过去，由统一错误处理回 404，不必先登录才能看到“不存在”
            if (!(handler instanceof HandlerMethod method)) {
                return true;
            }
            Who who = access(method);
            if (who == Who.PUBLIC) {
                return true;
            }
            CurrentUser user = bearer(req) == null ? null : auth.byToken(bearer(req));
            if (user == null) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "登录已失效，请重新登录");
            }
            boolean allowed = switch (who) {
                case ADMIN -> user.role() == Role.ADMIN;
                case SCALE -> user.role() == Role.SCALE;
                default -> true;
            };
            if (!allowed) {
                throw new ApiException(ErrorCode.WRONG_CLIENT, user.role() == Role.ADMIN
                        ? "管理员账号只能用 Web 管理端" : "磅秤员账号不能使用这个功能");
            }
            req.setAttribute(ATTR, user);
            return true;
        }

        private Who access(HandlerMethod method) {
            Access a = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Access.class);
            if (a == null) {
                a = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Access.class);
            }
            return a == null ? Who.ADMIN : a.value();
        }

        private String bearer(HttpServletRequest req) {
            String h = req.getHeader("Authorization");
            return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
        }
    }

    private static class UserArgument implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter p) {
            return p.getParameterType() == CurrentUser.class;
        }

        @Override
        public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest req,
                WebDataBinderFactory binder) {
            return req.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
