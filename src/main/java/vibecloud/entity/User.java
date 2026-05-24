package vibecloud.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_users_email", columnNames = "email")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    public static final long DEFAULT_MAX_STORAGE = 10L * 1024 * 1024 * 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "full_name", length = 120)
    private String fullName;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "email", nullable = false, length = 120)
    private String email;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Builder.Default
    @Column(name = "used_storage", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private Long usedStorage = 0L;

    @Builder.Default
    @Column(name = "max_storage", nullable = false, columnDefinition = "BIGINT DEFAULT 10737418240")
    private Long maxStorage = DEFAULT_MAX_STORAGE;

    @Builder.Default
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Folder> folders = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<File> files = new ArrayList<>();
}
