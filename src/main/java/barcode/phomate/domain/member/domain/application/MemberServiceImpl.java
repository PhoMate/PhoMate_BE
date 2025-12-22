package barcode.phomate.domain.member.domain.application;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.member.dto.MemberResponseDTO;
import barcode.phomate.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;

    @Override
    public MemberResponseDTO getMember(Long memberId) {

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("멤버가 존재하지 않습니다."));

        return MemberResponseDTO.from(member);
    }
}
