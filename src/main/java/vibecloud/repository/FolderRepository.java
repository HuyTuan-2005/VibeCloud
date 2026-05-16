package vibecloud.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;
import vibecloud.entity.Folder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FolderRepository extends JpaRepository<Folder, UUID> {

    Optional<Folder> findByIdAndUserId(UUID id, UUID userId);

    List<Folder> findByUserIdAndParentId(UUID userId, UUID parentId);

    List<Folder> findByUserIdAndParentIsNull(UUID userId);

    @EntityGraph(attributePaths = {"user", "parent"})
    List<Folder> findByUserIdAndParentIdOrderByNameAsc(UUID userId, UUID parentId);

    @EntityGraph(attributePaths = {"user", "parent"})
    List<Folder> findByUserIdAndParentIsNullOrderByNameAsc(UUID userId);

    boolean existsByUserIdAndParentIdAndNameIgnoreCase(UUID userId, UUID parentId, String name);

    boolean existsByUserIdAndParentIsNullAndNameIgnoreCase(UUID userId, String name);
}
