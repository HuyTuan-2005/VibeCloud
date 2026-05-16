package vibecloud.dto.folder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateFolderRequest(
        @NotBlank(message = "Folder name must not be blank")
        @Size(max = 255, message = "Folder name must not exceed 255 characters")
        String name,

        UUID parentId
) {
}
