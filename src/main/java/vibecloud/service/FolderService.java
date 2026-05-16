package vibecloud.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import vibecloud.dto.folder.CreateFolderRequest;
import vibecloud.dto.folder.FolderResponse;
import vibecloud.entity.Folder;
import vibecloud.exception.ResourceConflictException;
import vibecloud.repository.FolderRepository;
import vibecloud.repository.UserRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FolderService {

    private final UserRepository userRepository;
    private final FolderRepository folderRepository;

    @Transactional
    public FolderResponse createFolder(UUID userId, CreateFolderRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        var parent = resolveParent(userId, request.parentId());
        var folderName = normalizeFolderName(request.name());

        ensureFolderNameIsAvailable(userId, parent, folderName);

        var folder = Folder.builder()
                .name(folderName)
                .user(user)
                .parent(parent)
                .build();

        return FolderResponse.from(folderRepository.save(folder));
    }

    @Transactional(readOnly = true)
    public List<FolderResponse> listFolders(UUID userId, UUID parentId) {
        Objects.requireNonNull(userId, "userId must not be null");

        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        if (parentId != null) {
            resolveParent(userId, parentId);
            return folderRepository.findByUserIdAndParentIdOrderByNameAsc(userId, parentId)
                    .stream()
                    .map(FolderResponse::from)
                    .toList();
        }

        return folderRepository.findByUserIdAndParentIsNullOrderByNameAsc(userId)
                .stream()
                .map(FolderResponse::from)
                .toList();
    }

    private Folder resolveParent(UUID userId, UUID parentId) {
        if (parentId == null) {
            return null;
        }

        return folderRepository.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Parent folder not found or does not belong to user. parentId=%s, userId=%s"
                                .formatted(parentId, userId)
                ));
    }

    private String normalizeFolderName(String name) {
        var normalizedName = StringUtils.trimWhitespace(name);
        if (!StringUtils.hasText(normalizedName)) {
            throw new IllegalArgumentException("Folder name must not be blank");
        }

        return normalizedName;
    }

    private void ensureFolderNameIsAvailable(UUID userId, Folder parent, String folderName) {
        var exists = parent == null
                ? folderRepository.existsByUserIdAndParentIsNullAndNameIgnoreCase(userId, folderName)
                : folderRepository.existsByUserIdAndParentIdAndNameIgnoreCase(userId, parent.getId(), folderName);

        if (exists) {
            throw new ResourceConflictException("Folder name already exists in this location: " + folderName);
        }
    }
}
