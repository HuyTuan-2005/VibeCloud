package vibecloud.dto.file;

import vibecloud.entity.FileMetadata;

import java.util.UUID;

public record FileMetadataResponse(
        UUID id,
        String resolution,
        String extension
) {

    public static FileMetadataResponse from(FileMetadata metadata) {
        if (metadata == null) {
            return null;
        }

        return new FileMetadataResponse(
                metadata.getId(),
                metadata.getResolution(),
                metadata.getExtension()
        );
    }
}
