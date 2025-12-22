package barcode.phomate.domain.chat.domain.entity;

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
@Table(name = "chat_session")
public class ChatSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Column(length = 2048)
    private String currentSearchQuery;

    @Builder
    public ChatSession(Member member) {
        this.member = member;
    }

    public void updateSearchQuery(String query) {
        this.currentSearchQuery = query;
    }
}
