package vibecloud.dto.file;

import vibecloud.entity.File;
import vibecloud.enums.FileStatus;

import java.util.UUID;

public record FileResponse(
        UUID id,
        String originalName,
        String fileKey,
        String url,
        Long size,
        String mimeType,
        FileStatus status,
        UUID userId,
        UUID folderId,
        FileMetadataResponse metadata
) {

    public static FileResponse from(File file) {
        return from(file, null);
    }

    public static FileResponse from(File file, String url) {
        var folderId = file.getFolder() == null ? null : file.getFolder().getId();

        return new FileResponse(
                file.getId(),
                file.getOriginalName(),
                file.getFileKey(),
                url,
                file.getSize(),
                file.getMimeType(),
                file.getStatus(),
                file.getUser().getId(),
                folderId,
                FileMetadataResponse.from(file.getMetadata())
        );
    }
}
