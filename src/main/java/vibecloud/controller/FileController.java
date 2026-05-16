package vibecloud.controller;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.util.UUID;

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

    @GetMapping
    public List<FileResponse> listFiles(
            @RequestParam @NotNull UUID userId,
            @RequestParam(required = false) UUID folderId
    ) {
        return fileService.listFiles(userId, folderId);
    }
}
