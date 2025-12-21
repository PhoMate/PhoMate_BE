package barcode.phomate.domain.edit.domain.entity;

import barcode.phomate.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "edit_version",
        uniqueConstraints = @UniqueConstraint(columnNames = {"edit_session_id","version_index"}))
public class EditVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 EditSession에 속한 버전인지
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "edit_session_id", nullable = false)
    private EditSession editSession;

    // 버전 인덱스
    // undo/redo 시 사용
    @Column(name = "version_index", nullable = false)
    private int versionIndex;

    @Column(nullable = false, length = 512)
    private String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EditSourceType sourceType;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Builder
    public EditVersion(EditSession editSession, int versionIndex, String s3Key,
                       EditSourceType sourceType, String prompt) {
        this.editSession = editSession;
        this.versionIndex = versionIndex;
        this.s3Key = s3Key;
        this.sourceType = sourceType;
        this.prompt = prompt;
    }
}
