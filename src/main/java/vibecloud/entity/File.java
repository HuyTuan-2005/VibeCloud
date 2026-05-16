package vibecloud.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import vibecloud.enums.FileStatus;

import java.util.UUID;

@Entity
@Table(
        name = "files",
        indexes = {
                @Index(name = "idx_files_user_id", columnList = "user_id"),
                @Index(name = "idx_files_folder_id", columnList = "folder_id"),
                @Index(name = "idx_files_status", columnList = "status")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_files_file_key", columnNames = "file_key")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class File {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "file_key", nullable = false, length = 1024)
    private String fileKey;

    @Column(name = "size", nullable = false)
    private Long size;

    @Column(name = "mime_type", nullable = false, length = 120)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private FileStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Folder folder;

    @OneToOne(mappedBy = "file", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private FileMetadata metadata;
}
