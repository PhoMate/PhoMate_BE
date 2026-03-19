package barcode.phomate.domain.edit.domain.entity;

import barcode.phomate.domain.member.domain.entity.Member;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import barcode.phomate.domain.photo.domain.entity.Photo;


@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "edit_session")
public class EditSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "photo_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Photo photo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EditSessionStatus status;

    // undo/redo 포인터
    @Column(nullable = false)
    private int currentIndex;

    @Builder
    public EditSession(Photo photo, Member member) {
        this.photo = photo;
        this.member = member;
        this.status = EditSessionStatus.ACTIVE;
        this.currentIndex = 0;
    }

    public void moveTo(int index) {
        this.currentIndex = index;
    }

    public void finalizeSession() {
        this.status = EditSessionStatus.FINALIZED;
    }

    public void cancelSession() {
        this.status = EditSessionStatus.CANCELLED;
    }
}
