package vibecloud.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import vibecloud.exception.RateLimitExceededException;
import vibecloud.service.RateLimitingService;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String USER_ID_PARAMETER = "userId";
    private static final String UNKNOWN_IP = "unknown";

    private final RateLimitingService rateLimitingService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var userId = request.getParameter(USER_ID_PARAMETER);
        var clientIp = resolveClientIp(request);

        if (!rateLimitingService.tryConsumeUploadToken(userId, clientIp)) {
            throw new RateLimitExceededException("Upload rate limit exceeded. Maximum 5 files per minute.");
        }

        return true;
    }

    private String resolveClientIp(HttpServletRequest request) {
        var forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }

        var realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }

        var remoteAddress = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddress) ? remoteAddress : UNKNOWN_IP;
    }
}
