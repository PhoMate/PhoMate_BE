package barcode.phomate.domain.member.application;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.entity.SocialProvider;
import barcode.phomate.domain.member.dto.MemberResponseDTO;
import barcode.phomate.global.google.dto.GoogleUserInfoDTO;

public interface MemberService {
    MemberResponseDTO getMember(Long memberId);
    Member findOrCreateMember(SocialProvider provider, GoogleUserInfoDTO info);
}
