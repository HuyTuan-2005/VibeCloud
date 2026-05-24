package vibecloud.dto.file;

public record FileContentResponse(
        String originalName,
        String mimeType,
        byte[] bytes
) {
}
