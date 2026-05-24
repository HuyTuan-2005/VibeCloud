package vibecloud.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Vui lòng nhập email hoặc tên đăng nhập")
        String usernameOrEmail,

        @NotBlank(message = "Vui lòng nhập mật khẩu")
        String password
) {
}
