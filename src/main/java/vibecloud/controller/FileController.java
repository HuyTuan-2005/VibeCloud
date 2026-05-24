package vibecloud.controller;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vibecloud.dto.file.FileResponse;
import vibecloud.service.FileService;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse uploadFile(
            @RequestParam @NotNull UUID userId,
            @RequestParam(required = false) UUID folderId,
            @RequestPart("file") MultipartFile file
    ) {
        return fileService.uploadFile(userId, folderId, file);
    }

    @PostMapping(value = "/upload-with-folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse uploadWithFolder(
            @RequestParam @NotNull UUID userId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) String folderName,
            @RequestPart("file") MultipartFile file
    ) {
        return fileService.uploadWithFolder(userId, parentId, folderName, file);
    }

    @GetMapping
    public List<FileResponse> listFiles(
            @RequestParam @NotNull UUID userId,
            @RequestParam(required = false) UUID folderId
    ) {
        return fileService.listFiles(userId, folderId);
    }

    @GetMapping("/{fileId}/preview")
    public ResponseEntity<byte[]> previewFile(
            @PathVariable UUID fileId,
            @RequestParam @NotNull UUID userId
    ) {
        var content = fileService.getFileContent(userId, fileId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .contentLength(content.bytes().length)
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(content.originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(content.bytes());
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable UUID fileId,
            @RequestParam @NotNull UUID userId
    ) {
        var content = fileService.getFileContent(userId, fileId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .contentLength(content.bytes().length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(content.bytes());
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveToTrash(
            @PathVariable UUID fileId,
            @RequestParam @NotNull UUID userId
    ) {
        fileService.moveToTrash(userId, fileId);
    }

    @PostMapping("/{fileId}/restore")
    public FileResponse restoreFromTrash(
            @PathVariable UUID fileId,
            @RequestParam @NotNull UUID userId
    ) {
        return fileService.restoreFromTrash(userId, fileId);
    }

    @DeleteMapping("/{fileId}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePermanently(
            @PathVariable UUID fileId,
            @RequestParam @NotNull UUID userId
    ) {
        fileService.deletePermanently(userId, fileId);
    }
}
