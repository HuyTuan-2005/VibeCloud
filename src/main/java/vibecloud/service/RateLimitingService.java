package vibecloud.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class RateLimitingService {

    private static final long UPLOAD_LIMIT = 5L;
    private static final Duration UPLOAD_LIMIT_PERIOD = Duration.ofMinutes(1);

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryConsumeUploadToken(String userId, String clientIp) {
        var ipBucket = resolveBucket("ip:%s".formatted(clientIp));
        if (!ipBucket.tryConsume(1)) {
            return false;
        }

        if (!StringUtils.hasText(userId)) {
            return true;
        }

        var userBucket = resolveBucket("user:%s".formatted(userId));
        if (userBucket.tryConsume(1)) {
            return true;
        }

        ipBucket.addTokens(1);
        return false;
    }

    private Bucket resolveBucket(String key) {
        return buckets.computeIfAbsent(key, ignored -> createUploadBucket());
    }

    private Bucket createUploadBucket() {
        var refill = Refill.intervally(UPLOAD_LIMIT, UPLOAD_LIMIT_PERIOD);
        var limit = Bandwidth.classic(UPLOAD_LIMIT, refill);
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }
}
