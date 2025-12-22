package barcode.phomate.domain.member.domain.application;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.entity.SocialLogin;
import barcode.phomate.domain.member.domain.entity.SocialProvider;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.member.domain.repository.SocialLoginRepository;
import barcode.phomate.domain.member.dto.MemberResponseDTO;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.google.dto.GoogleUserInfoDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberServiceImpl implements MemberService {

    private final SocialLoginRepository socialLoginRepository;
    private final MemberRepository memberRepository;

    @Override
    public Member findOrCreateMember(SocialProvider provider, GoogleUserInfoDTO info) {
        return socialLoginRepository
                .findByProviderAndProviderId(provider, info.getProviderId())
                .map(SocialLogin::getMember)
                .orElseGet(() -> {
                    Member member = memberRepository.save(
                            Member.builder()
                                    .nickname(info.getName() != null ? info.getName() : "unknown")
                                    .profileImageUrl(info.getPicture())
                                    .build()
                    );

                    socialLoginRepository.save(
                            SocialLogin.builder()
                                    .provider(provider)
                                    .providerId(info.getProviderId())
                                    .member(member)
                                    .build()
                    );

                    return member;
                });
    }
    @Override
    public MemberResponseDTO getMember(Long memberId) {

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("멤버가 존재하지 않습니다."));

        return MemberResponseDTO.from(member);
    }
}
