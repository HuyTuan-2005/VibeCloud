package vibecloud.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import vibecloud.dto.auth.LoginRequest;
import vibecloud.dto.auth.RegisterRequest;
import vibecloud.dto.auth.UpdateProfileRequest;
import vibecloud.entity.User;
import vibecloud.exception.ResourceConflictException;
import vibecloud.repository.UserRepository;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String PASSWORD_PREFIX = "pbkdf2";
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final int PASSWORD_SALT_BYTES = 16;

    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public User register(RegisterRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        var fullName = normalizeRequired(request.fullName(), "Họ và tên không được để trống");
        var username = normalizeRequired(request.username(), "Tên đăng nhập không được để trống");
        var email = normalizeEmail(request.email());
        var password = normalizeRequired(request.password(), "Mật khẩu không được để trống");
        var confirmPassword = normalizeRequired(request.confirmPassword(), "Xác nhận mật khẩu không được để trống");

        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp");
        }

        if (userRepository.existsByUsername(username)) {
            throw new ResourceConflictException("Tên đăng nhập đã tồn tại");
        }

        if (userRepository.existsByEmail(email)) {
            throw new ResourceConflictException("Email đã được sử dụng");
        }

        var user = User.builder()
                .fullName(fullName)
                .username(username)
                .email(email)
                .password(hashPassword(password))
                .role("USER")
                .build();

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User authenticate(LoginRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        var usernameOrEmail = normalizeRequired(request.usernameOrEmail(), "Vui lòng nhập email hoặc tên đăng nhập");
        var password = normalizeRequired(request.password(), "Vui lòng nhập mật khẩu");
        var user = usernameOrEmail.contains("@")
                ? userRepository.findByEmail(usernameOrEmail.toLowerCase(Locale.ROOT))
                : userRepository.findByUsername(usernameOrEmail);

        if (user.isEmpty() || !verifyPassword(password, user.get().getPassword())) {
            throw new IllegalArgumentException("Email/tên đăng nhập hoặc mật khẩu không đúng");
        }

        return user.get();
    }

    @Transactional(readOnly = true)
    public User getById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng"));
    }

    @Transactional
    public User updateProfile(UUID userId, UpdateProfileRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        var user = getById(userId);
        var fullName = normalizeRequired(request.fullName(), "Họ và tên không được để trống");
        var email = normalizeEmail(request.email());

        userRepository.findByEmail(email)
                .filter(existingUser -> !existingUser.getId().equals(userId))
                .ifPresent(existingUser -> {
                    throw new ResourceConflictException("Email đã được sử dụng");
                });

        user.setFullName(fullName);
        user.setEmail(email);
        return userRepository.save(user);
    }

    private String hashPassword(String rawPassword) {
        var salt = new byte[PASSWORD_SALT_BYTES];
        secureRandom.nextBytes(salt);
        var hash = pbkdf2(rawPassword, salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);

        return "%s$%d$%s$%s".formatted(
                PASSWORD_PREFIX,
                PASSWORD_ITERATIONS,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash)
        );
    }

    private boolean verifyPassword(String rawPassword, String storedPassword) {
        if (!StringUtils.hasText(storedPassword)) {
            return false;
        }

        if (!storedPassword.startsWith(PASSWORD_PREFIX + "$")) {
            return MessageDigest.isEqual(rawPassword.getBytes(), storedPassword.getBytes());
        }

        var parts = storedPassword.split("\\$");
        if (parts.length != 4) {
            return false;
        }

        var iterations = Integer.parseInt(parts[1]);
        var salt = Base64.getDecoder().decode(parts[2]);
        var expectedHash = Base64.getDecoder().decode(parts[3]);
        var actualHash = pbkdf2(rawPassword, salt, iterations, expectedHash.length * 8);

        return MessageDigest.isEqual(expectedHash, actualHash);
    }

    private byte[] pbkdf2(String rawPassword, byte[] salt, int iterations, int keyLength) {
        try {
            KeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, keyLength);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể xử lý mật khẩu", exception);
        }
    }

    private String normalizeRequired(String value, String message) {
        var normalizedValue = StringUtils.trimWhitespace(value);
        if (!StringUtils.hasText(normalizedValue)) {
            throw new IllegalArgumentException(message);
        }

        return normalizedValue;
    }

    private String normalizeEmail(String value) {
        return normalizeRequired(value, "Email không được để trống").toLowerCase(Locale.ROOT);
    }
}
