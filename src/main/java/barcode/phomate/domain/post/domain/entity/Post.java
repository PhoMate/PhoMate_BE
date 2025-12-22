package barcode.phomate.domain.post.domain.entity;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Column(nullable=false)
    private String title;

    @Column(nullable=false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 512)
    private String imagePrefix;

    @Column(nullable = false, length = 512)
    private String originalKey;

    @Column(nullable = false, length = 512)
    private String thumbnailKey;

    @Column(nullable = false, length = 512)
    private String previewKey;

    @Column(nullable = false)
    private long likeCount;

    @Builder
    public Post(Member member, String title, String description,
                String imagePrefix, String originalKey,
                String thumbnailKey, String previewKey) {
        this.member = member;
        this.title = title;
        this.description = description;
        this.imagePrefix = imagePrefix;
        this.originalKey = originalKey;
        this.thumbnailKey = thumbnailKey;
        this.previewKey = previewKey;
        this.likeCount = 0;
    }

    public void updateImageKeys(String imagePrefix, String originalKey, String thumbnailKey, String previewKey) {
        this.imagePrefix = imagePrefix;
        this.originalKey = originalKey;
        this.thumbnailKey = thumbnailKey;
        this.previewKey = previewKey;
    }

    public void updateText(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String thumbnailKey() { return thumbnailKey; }
    public String previewKey()   { return previewKey; }

}

