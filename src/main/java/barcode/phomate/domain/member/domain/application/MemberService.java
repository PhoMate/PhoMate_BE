package barcode.phomate.domain.member.domain.application;

import barcode.phomate.domain.member.dto.MemberResponseDTO;

public interface MemberService {
    MemberResponseDTO getMember(Long memberId);
}
