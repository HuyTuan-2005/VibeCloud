package vibecloud.dto.file;

import vibecloud.entity.File;
import vibecloud.enums.FileStatus;

import java.util.Locale;
import java.util.UUID;

public record FileResponse(
        UUID id,
        String originalName,
        String fileKey,
        String url,
        Long size,
        String formattedSize,
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
                formatSize(file.getSize()),
                file.getMimeType(),
                file.getStatus(),
                file.getUser().getId(),
                folderId,
                FileMetadataResponse.from(file.getMetadata())
        );
    }

    // Hàm quy đổi Byte sang KB, MB, GB
    private static String formatSize(Long bytes) {
        if (bytes == null) return "0 B";
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = bytes / 1024.0;
        int unitIndex = 0;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.2f %s", size, units[unitIndex]).replace(".00 ", " ");
    }
}