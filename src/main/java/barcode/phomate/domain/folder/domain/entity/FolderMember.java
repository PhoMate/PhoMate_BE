package barcode.phomate.domain.folder.domain.entity;

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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FolderMember extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Folder folder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FolderRole role;

    // 초대 수락 여부
    @Column(nullable = false)
    private boolean isAccepted;

    @Builder
    public FolderMember(Member member, Folder folder, FolderRole role, boolean isAccepted) {
        this.member = member;
        this.folder = folder;
        this.role = role;
        this.isAccepted = isAccepted;
    }

    public void acceptInvite() {
        this.isAccepted = true;
    }

    public void updateRole(FolderRole role) {
        this.role = role;
    }
}
