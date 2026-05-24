package vibecloud.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vibecloud.entity.User;

public record UpdateProfileRequest(
        @NotBlank(message = "Vui lòng nhập họ và tên")
        @Size(max = 120, message = "Họ và tên tối đa 120 ký tự")
        String fullName,

        @NotBlank(message = "Vui lòng nhập email")
        @Email(message = "Email không hợp lệ")
        @Size(max = 120, message = "Email tối đa 120 ký tự")
        String email
) {

    public static UpdateProfileRequest from(User user) {
        return new UpdateProfileRequest(user.getFullName(), user.getEmail());
    }
}
