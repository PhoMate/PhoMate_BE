package barcode.phomate.domain.member.dto;

import barcode.phomate.domain.member.domain.entity.Member;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemberResponseDTO {
    private Long memberId;
    private String nickname;
    private String profileImageUrl;

    public static MemberResponseDTO from(Member m) {
        MemberResponseDTO res = new MemberResponseDTO();
        res.memberId = m.getId();
        res.nickname = m.getNickname();
        res.profileImageUrl = m.getProfileImageUrl();
        return res;
    }
}

