package vibecloud.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vibecloud.dto.folder.CreateFolderRequest;
import vibecloud.dto.folder.FolderResponse;
import vibecloud.service.FolderService;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FolderResponse createFolder(
            @RequestParam @NotNull UUID userId,
            @Valid @RequestBody CreateFolderRequest request
    ) {
        return folderService.createFolder(userId, request);
    }

    @GetMapping
    public List<FolderResponse> listFolders(
            @RequestParam @NotNull UUID userId,
            @RequestParam(required = false) UUID parentId
    ) {
        return folderService.listFolders(userId, parentId);
    }
}
