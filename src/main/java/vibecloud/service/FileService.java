package vibecloud.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vibecloud.config.AsyncConfig;
import vibecloud.config.S3Config.S3Properties;
import vibecloud.dto.file.FileResponse;
import vibecloud.entity.File;
import vibecloud.entity.FileMetadata;
import vibecloud.entity.Folder;
import vibecloud.enums.FileStatus;
import vibecloud.exception.StorageException;
import vibecloud.repository.FileMetadataRepository;
import vibecloud.repository.FileRepository;
import vibecloud.repository.FolderRepository;
import vibecloud.repository.UserRepository;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final double IMAGE_OUTPUT_QUALITY = 0.82D;
    private static final DateTimeFormatter OBJECT_KEY_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");

    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final FileRepository fileRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final ObjectProvider<FileService> selfProvider;

    @Transactional
    public FileResponse uploadFile(UUID userId, UUID folderId, MultipartFile multipartFile) {
        return FileResponse.from(upload(userId, folderId, multipartFile));
    }

    @Transactional(readOnly = true)
    public List<FileResponse> listFiles(UUID userId, UUID folderId) {
        Objects.requireNonNull(userId, "userId must not be null");

        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        if (folderId != null) {
            resolveFolder(userId, folderId);
            return fileRepository.findByUserIdAndFolderIdOrderByOriginalNameAsc(userId, folderId)
                    .stream()
                    .map(FileResponse::from)
                    .toList();
        }

        return fileRepository.findByUserIdOrderByOriginalNameAsc(userId)
                .stream()
                .map(FileResponse::from)
                .toList();
    }

    @Transactional
    public File upload(UUID userId, MultipartFile multipartFile) {
        return upload(userId, null, multipartFile);
    }

    @Transactional
    public File upload(UUID userId, UUID folderId, MultipartFile multipartFile) {
        validateUploadRequest(userId, multipartFile);

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        var folder = resolveFolder(userId, folderId);
        var originalName = cleanOriginalFilename(multipartFile.getOriginalFilename());
        var mimeType = resolveMimeType(originalName, multipartFile.getContentType());
        var fileKey = buildFileKey(userId, originalName);

        putMultipartObject(fileKey, mimeType, multipartFile);

        var file = File.builder()
                .originalName(originalName)
                .fileKey(fileKey)
                .size(multipartFile.getSize())
                .mimeType(mimeType)
                .status(FileStatus.UPLOADED)
                .user(user)
                .folder(folder)
                .build();

        var savedFile = fileRepository.save(file);

        if (isCompressibleImage(mimeType)) {
            runAfterCommit(() -> selfProvider.getObject().compressImageAsync(savedFile.getId()));
        } else {
            savedFile.setStatus(FileStatus.COMPLETED);
            upsertMetadata(savedFile, null, extractExtension(originalName));
        }

        return savedFile;
    }

    @Async(AsyncConfig.MEDIA_TASK_EXECUTOR)
    @Transactional
    public CompletableFuture<Void> compressImageAsync(UUID fileId) {
        var file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        if (!isCompressibleImage(file.getMimeType())) {
            completeWithoutCompression(file);
            return CompletableFuture.completedFuture(null);
        }

        try {
            file.setStatus(FileStatus.PROCESSING);
            fileRepository.save(file);

            var originalBytes = getObjectBytes(file.getFileKey());
            var image = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (image == null) {
                throw new IllegalArgumentException("Uploaded object is not a readable image: " + file.getFileKey());
            }

            var outputFormat = resolveOutputFormat(file.getMimeType());
            var compressedBytes = compressImage(originalBytes, outputFormat);
            if (compressedBytes.length < originalBytes.length) {
                putBytesObject(file.getFileKey(), file.getMimeType(), compressedBytes);
                file.setSize((long) compressedBytes.length);
            }

            var resolution = "%dx%d".formatted(image.getWidth(), image.getHeight());
            upsertMetadata(file, resolution, extractExtension(file.getOriginalName()));
            file.setStatus(FileStatus.COMPLETED);
            fileRepository.save(file);

            log.info(
                    "Image processing completed. fileId={}, originalSize={}, finalSize={}",
                    fileId,
                    originalBytes.length,
                    file.getSize()
            );
            return CompletableFuture.completedFuture(null);
        } catch (Exception exception) {
            markAsFailed(fileId, exception);
            return CompletableFuture.failedFuture(exception);
        }
    }

    private Folder resolveFolder(UUID userId, UUID folderId) {
        if (folderId == null) {
            return null;
        }

        return folderRepository.findByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Folder not found or does not belong to user. folderId=%s, userId=%s"
                                .formatted(folderId, userId)
                ));
    }

    private void putMultipartObject(String fileKey, String mimeType, MultipartFile multipartFile) {
        var request = PutObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(fileKey)
                .contentType(mimeType)
                .contentLength(multipartFile.getSize())
                .build();

        try (var inputStream = multipartFile.getInputStream()) {
            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, multipartFile.getSize()));
        } catch (IOException exception) {
            throw new StorageException("Cannot read uploaded file stream", exception);
        } catch (S3Exception exception) {
            throw new StorageException("Cannot upload file to S3. key=" + fileKey, exception);
        }
    }

    private byte[] getObjectBytes(String fileKey) throws IOException {
        var request = GetObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(fileKey)
                .build();

        try (var response = s3Client.getObject(request)) {
            return response.readAllBytes();
        } catch (S3Exception exception) {
            throw new StorageException("Cannot download file from S3. key=" + fileKey, exception);
        }
    }

    private void putBytesObject(String fileKey, String mimeType, byte[] bytes) {
        var request = PutObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(fileKey)
                .contentType(mimeType)
                .contentLength((long) bytes.length)
                .build();

        try {
            s3Client.putObject(request, RequestBody.fromBytes(bytes));
        } catch (S3Exception exception) {
            throw new StorageException("Cannot upload processed file to S3. key=" + fileKey, exception);
        }
    }

    private byte[] compressImage(byte[] originalBytes, String outputFormat) throws IOException {
        try (var inputStream = new ByteArrayInputStream(originalBytes);
             var outputStream = new ByteArrayOutputStream()) {
            Thumbnails.of(inputStream)
                    .scale(1.0D)
                    .outputFormat(outputFormat)
                    .outputQuality(IMAGE_OUTPUT_QUALITY)
                    .toOutputStream(outputStream);

            return outputStream.toByteArray();
        }
    }

    private void completeWithoutCompression(File file) {
        upsertMetadata(file, null, extractExtension(file.getOriginalName()));
        file.setStatus(FileStatus.COMPLETED);
        fileRepository.save(file);
    }

    private void upsertMetadata(File file, String resolution, String extension) {
        var metadata = fileMetadataRepository.findByFileId(file.getId())
                .orElseGet(() -> FileMetadata.builder()
                        .file(file)
                        .build());

        metadata.setResolution(resolution);
        metadata.setExtension(extension);
        var savedMetadata = fileMetadataRepository.save(metadata);
        file.setMetadata(savedMetadata);
    }

    private void markAsFailed(UUID fileId, Exception exception) {
        fileRepository.findById(fileId).ifPresent(file -> {
            file.setStatus(FileStatus.FAILED);
            fileRepository.save(file);
        });
        log.error("Image processing failed. fileId={}", fileId, exception);
    }

    private void validateUploadRequest(UUID userId, MultipartFile multipartFile) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(multipartFile, "multipartFile must not be null");

        if (multipartFile.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
    }

    private String cleanOriginalFilename(String originalFilename) {
        var fallbackName = "unnamed-file";
        var cleanedName = StringUtils.cleanPath(
                StringUtils.hasText(originalFilename) ? originalFilename : fallbackName
        );

        if (!StringUtils.hasText(cleanedName) || cleanedName.contains("..")) {
            throw new IllegalArgumentException("Invalid original filename: " + originalFilename);
        }

        return cleanedName;
    }

    private String resolveMimeType(String originalName, String contentType) {
        if (StringUtils.hasText(contentType)) {
            return contentType.toLowerCase(Locale.ROOT);
        }

        return MediaTypeFactory.getMediaType(originalName)
                .map(MediaType::toString)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private String buildFileKey(UUID userId, String originalName) {
        var monthPath = YearMonth.now().format(OBJECT_KEY_MONTH_FORMATTER);
        var extension = extractExtension(originalName);
        var extensionSuffix = StringUtils.hasText(extension) ? "." + extension : "";

        return "users/%s/%s/%s%s".formatted(userId, monthPath, UUID.randomUUID(), extensionSuffix);
    }

    private String extractExtension(String filename) {
        var dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }

        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isCompressibleImage(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg", "image/jpg", "image/png" -> true;
            default -> false;
        };
    }

    private String resolveOutputFormat(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> "png";
            default -> "jpg";
        };
    }

    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
