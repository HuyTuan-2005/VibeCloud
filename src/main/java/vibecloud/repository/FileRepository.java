package vibecloud.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;
import vibecloud.entity.File;
import vibecloud.enums.FileStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<File, UUID> {

    Optional<File> findByFileKey(String fileKey);

    List<File> findByUserId(UUID userId);

    List<File> findByFolderId(UUID folderId);

    List<File> findByUserIdAndFolderId(UUID userId, UUID folderId);

    @EntityGraph(attributePaths = {"user", "folder", "metadata"})
    List<File> findByUserIdOrderByOriginalNameAsc(UUID userId);

    @EntityGraph(attributePaths = {"user", "folder", "metadata"})
    List<File> findByUserIdAndFolderIdOrderByOriginalNameAsc(UUID userId, UUID folderId);

    List<File> findByStatus(FileStatus status);
}
