package vibecloud.dto.folder;

import vibecloud.entity.Folder;

import java.util.UUID;

public record FolderResponse(
        UUID id,
        String name,
        UUID userId,
        UUID parentId
) {

    public static FolderResponse from(Folder folder) {
        var parentId = folder.getParent() == null ? null : folder.getParent().getId();

        return new FolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getUser().getId(),
                parentId
        );
    }
}
