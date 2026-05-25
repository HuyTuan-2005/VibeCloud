package vibecloud.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.tika.Tika;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vibecloud.config.AsyncConfig;
import vibecloud.config.S3Config.S3Properties;
import vibecloud.dto.file.FileContentResponse;
import vibecloud.dto.file.FileResponse;
import vibecloud.entity.File;
import vibecloud.entity.FileMetadata;
import vibecloud.entity.Folder;
import vibecloud.entity.User;
import vibecloud.enums.FileStatus;
import vibecloud.exception.QuotaExceededException;
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
    private final Tika tika;
    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final FolderService folderService;
    private final FileRepository fileRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final ObjectProvider<FileService> selfProvider;

    @Transactional
    public FileResponse uploadFile(UUID userId, UUID folderId, MultipartFile multipartFile) {
        var file = upload(userId, folderId, multipartFile);
        return FileResponse.from(file, buildPublicUrl(file.getFileKey()));
    }

    @Transactional
    public FileResponse uploadWithFolder(UUID userId, UUID parentId, String folderName, MultipartFile multipartFile) {
        // Find or create provided folderName under parentId (if provided)
        var folder = folderService.findOrCreateFolderByName(userId, parentId, folderName);
        var folderId = folder == null ? null : folder.getId();

        var file = upload(userId, folderId, multipartFile);
        return FileResponse.from(file, buildPublicUrl(file.getFileKey()));
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
                    .map(file -> FileResponse.from(file, buildPublicUrl(file.getFileKey())))
                    .toList();
        }

        return fileRepository.findByUserIdOrderByOriginalNameAsc(userId)
                .stream()
                .map(file -> FileResponse.from(file, buildPublicUrl(file.getFileKey())))
                .toList();
    }

    @Transactional(readOnly = true)
    public FileContentResponse getFileContent(UUID userId, UUID fileId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");

        var file = fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        try {
            return new FileContentResponse(
                    file.getOriginalName(),
                    file.getMimeType(),
                    getObjectBytes(file.getFileKey())
            );
        } catch (IOException exception) {
            throw new StorageException("Cannot read file content. fileId=" + fileId, exception);
        }
    }

    @Transactional
    public FileResponse moveToTrash(UUID userId, UUID fileId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");

        var file = fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));
        file.setStatus(FileStatus.DELETED);

        return FileResponse.from(fileRepository.save(file), buildPublicUrl(file.getFileKey()));
    }

    @Transactional
    public FileResponse restoreFromTrash(UUID userId, UUID fileId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");

        var file = fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        if (file.getStatus() == FileStatus.DELETED) {
            file.setStatus(FileStatus.COMPLETED);
        }

        return FileResponse.from(fileRepository.save(file), buildPublicUrl(file.getFileKey()));
    }

    @Transactional
    public void deletePermanently(UUID userId, UUID fileId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");

        var file = fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        deleteObject(file.getFileKey());
        refundStorage(userId, file.getSize() == null ? 0L : file.getSize());
        fileRepository.delete(file);
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
        var uploadSize = multipartFile.getSize();
        ensureStorageQuotaAvailable(user, uploadSize);

        var folder = resolveFolder(userId, folderId);
        var originalName = cleanOriginalFilename(multipartFile.getOriginalFilename());
        var mimeType = resolveMimeType(originalName, multipartFile);
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
        reserveStorage(user, uploadSize);

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
            var originalSize = file.getSize() == null ? (long) originalBytes.length : file.getSize();
            var image = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (image == null) {
                throw new IllegalArgumentException("Uploaded object is not a readable image: " + file.getFileKey());
            }

            var outputFormat = resolveOutputFormat(file.getMimeType());
            var compressedBytes = compressImage(originalBytes, outputFormat);
            var compressedSize = (long) compressedBytes.length;
            if (compressedSize < originalSize) {
                putBytesObject(file.getFileKey(), file.getMimeType(), compressedBytes);
                refundStorage(file.getUser().getId(), originalSize - compressedSize);
                file.setSize(compressedSize);
            }

            var resolution = "%dx%d".formatted(image.getWidth(), image.getHeight());
            upsertMetadata(file, resolution, extractExtension(file.getOriginalName()));
            file.setStatus(FileStatus.COMPLETED);
            fileRepository.save(file);

            log.info(
                    "Image processing completed. fileId={}, originalSize={}, finalSize={}",
                    fileId,
                    originalSize,
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

    private void deleteObject(String fileKey) {
        var request = DeleteObjectRequest.builder()
                .bucket(s3Properties.bucket())
                .key(fileKey)
                .build();

        try {
            s3Client.deleteObject(request);
        } catch (S3Exception exception) {
            throw new StorageException("Cannot delete file from S3. key=" + fileKey, exception);
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

    private void ensureStorageQuotaAvailable(User user, long uploadSize) {
        var usedStorage = normalizeUsedStorage(user);
        var maxStorage = normalizeMaxStorage(user);

        if (uploadSize > maxStorage - usedStorage) {
            throw new QuotaExceededException("Dung lượng lưu trữ đã đầy");
        }
    }

    private void reserveStorage(User user, long bytes) {
        if (bytes <= 0) {
            return;
        }

        user.setUsedStorage(normalizeUsedStorage(user) + bytes);
        user.setMaxStorage(normalizeMaxStorage(user));
        userRepository.save(user);
    }

    private void refundStorage(UUID userId, long bytes) {
        if (bytes <= 0) {
            return;
        }

        userRepository.findById(userId).ifPresent(user -> {
            var updatedUsedStorage = Math.max(0L, normalizeUsedStorage(user) - bytes);
            user.setUsedStorage(updatedUsedStorage);
            user.setMaxStorage(normalizeMaxStorage(user));
            userRepository.save(user);
        });
    }

    private long normalizeUsedStorage(User user) {
        var usedStorage = user.getUsedStorage();
        if (usedStorage == null || usedStorage < 0) {
            return 0L;
        }

        return usedStorage;
    }

    private long normalizeMaxStorage(User user) {
        var maxStorage = user.getMaxStorage();
        if (maxStorage == null || maxStorage <= 0) {
            return User.DEFAULT_MAX_STORAGE;
        }

        return maxStorage;
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

    private String resolveMimeType(String originalName, MultipartFile multipartFile) {
        try (var inputStream = multipartFile.getInputStream()) {
            var detectedMimeType = tika.detect(inputStream, originalName);
            var normalizedMimeType = StringUtils.hasText(detectedMimeType)
                    ? detectedMimeType.toLowerCase(Locale.ROOT)
                    : MediaType.APPLICATION_OCTET_STREAM_VALUE;

            var declaredMimeType = multipartFile.getContentType();
            if (StringUtils.hasText(declaredMimeType)
                    && !normalizedMimeType.equalsIgnoreCase(declaredMimeType)) {
                log.warn(
                        "Uploaded file content type mismatch. originalName={}, declared={}, detected={}",
                        originalName,
                        declaredMimeType,
                        normalizedMimeType
                );
            }

            return normalizedMimeType;
        } catch (IOException exception) {
            throw new StorageException("Cannot inspect uploaded file content type", exception);
        }
    }

    private String buildFileKey(UUID userId, String originalName) {
        var monthPath = YearMonth.now().format(OBJECT_KEY_MONTH_FORMATTER);
        var extension = extractExtension(originalName);
        var extensionSuffix = StringUtils.hasText(extension) ? "." + extension : "";

        return "users/%s/%s/%s%s".formatted(userId, monthPath, UUID.randomUUID(), extensionSuffix);
    }

    private String buildPublicUrl(String fileKey) {
        if (!StringUtils.hasText(s3Properties.endpoint()) || !StringUtils.hasText(s3Properties.bucket())) {
            throw new IllegalStateException("S3 endpoint and bucket must be configured to build public URL");
        }

        return "%s/%s/%s".formatted(
                removeTrailingSlashes(s3Properties.endpoint()),
                removeWrappingSlashes(s3Properties.bucket()),
                removeLeadingSlashes(fileKey)
        );
    }

    private String removeWrappingSlashes(String value) {
        return removeTrailingSlashes(removeLeadingSlashes(value));
    }

    private String removeLeadingSlashes(String value) {
        var result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private String removeTrailingSlashes(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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
    @Transactional(readOnly = true)
    public List<FileResponse> searchFiles(UUID userId, String keyword) {
        Objects.requireNonNull(userId, "userId must not be null");

        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }

        return fileRepository.findByUserIdAndOriginalNameContainingIgnoreCaseOrderByOriginalNameAsc(userId, keyword)
                .stream()
                .map(file -> FileResponse.from(file, buildPublicUrl(file.getFileKey())))
                .toList();
    }
}
