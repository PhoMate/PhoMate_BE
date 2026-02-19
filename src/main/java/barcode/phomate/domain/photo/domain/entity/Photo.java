package barcode.phomate.domain.photo.domain.entity;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Photo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    /**
     앨범 정렬 기준
     1. EXIF 촬영시간
     2. clientLastModified
     3. 서버 수신 시간
     */
    @Column(nullable = false)
    private LocalDateTime shotAt;

    // S3 관련 키
    @Column(nullable = false, length = 512)
    private String imagePrefix;

    @Column(nullable = false, length = 512)
    private String originalKey;

    @Column(nullable = false, length = 512)
    private String thumbnailKey;

    @Column(nullable = false, length = 512)
    private String previewKey;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder
    public Photo(Member member,
                 LocalDateTime shotAt,
                 String imagePrefix,
                 String originalKey,
                 String thumbnailKey,
                 String previewKey,
                 String description) {

        this.member = member;
        this.shotAt = shotAt;
        this.imagePrefix = imagePrefix;
        this.originalKey = originalKey;
        this.thumbnailKey = thumbnailKey;
        this.previewKey = previewKey;
        this.description = description;
    }

    public void updateImageKeys(String imagePrefix,
                                String originalKey,
                                String thumbnailKey,
                                String previewKey) {
        this.imagePrefix = imagePrefix;
        this.originalKey = originalKey;
        this.thumbnailKey = thumbnailKey;
        this.previewKey = previewKey;
    }

    public String thumbnailKey() { return thumbnailKey; }
    public String previewKey()   { return previewKey; }
}
