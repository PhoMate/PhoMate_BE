package barcode.phomate.domain.follow.dto;

import barcode.phomate.domain.member.domain.entity.Member;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FolloweeResponseDTO {
    private Long memberId;
    private String nickname;
    private String profileImageUrl;

    public static FolloweeResponseDTO from(Member m) {
        FolloweeResponseDTO res = new FolloweeResponseDTO();
        res.memberId = m.getId();
        res.nickname = m.getNickname();
        res.profileImageUrl = m.getProfileImageUrl();
        return res;
    }
}
