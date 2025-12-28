package barcode.phomate.global.auth.application;


import barcode.phomate.domain.member.domain.application.MemberService;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.entity.SocialProvider;
import barcode.phomate.global.auth.dto.GoogleLoginRequestDTO;
import barcode.phomate.global.auth.dto.GoogleLoginResponseDTO;
import barcode.phomate.global.exception.BadRequestException;
import barcode.phomate.global.exception.UnauthorizedException;
import barcode.phomate.global.google.client.GoogleIdTokenVerifierClient;
import barcode.phomate.global.google.client.GoogleTokenClient;
import barcode.phomate.global.google.dto.GoogleTokenResponseDTO;
import barcode.phomate.global.google.dto.GoogleUserInfoDTO;
import barcode.phomate.global.jwt.JwtTokenProvider;
import barcode.phomate.global.jwt.domain.entity.RefreshToken;
import barcode.phomate.global.jwt.domain.repository.RefreshTokenRepository;
import barcode.phomate.global.jwt.dto.RefreshRequestDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AuthService {

    private final GoogleTokenClient googleTokenClient;
    private final GoogleIdTokenVerifierClient googleIdTokenVerifierClient;

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final MemberService memberService;

    public GoogleLoginResponseDTO loginWithGoogle(GoogleLoginRequestDTO req) {

        log.info("loginWithGoogle start code={}, verifier={}", req.getCode(), req.getCodeVerifier());

        // code -> Google token 교환 + id_token 검증 -> GoogleUserInfo
        GoogleUserInfoDTO userInfo = verifyGoogle(req);
        log.info("verifyGoogle success sub/email={}", userInfo.getName()); // 필드명 맞게


        // 신규 멤버 생성 or 기존 멤버 객체 반환
        Member member = memberService.findOrCreateMember(SocialProvider.GOOGLE, userInfo);
        Long memberId = member.getId();

        // 기존 refresh 폐기
        refreshTokenRepository.deleteByMemberId(memberId);

        // JWT 생성
        String accessToken = jwtTokenProvider.createAccessToken(memberId);
        String refreshToken = jwtTokenProvider.createRefreshToken(memberId);

        // refresh token DB 저장
        RefreshToken rt = RefreshToken.builder()
                .token(refreshToken)
                .memberId(memberId)
                .expiresAt(LocalDateTime.now()
                        .plusSeconds(jwtTokenProvider.getRefreshExpMs() / 1000))
                .build();
        refreshTokenRepository.save(rt);

        GoogleLoginResponseDTO response = new GoogleLoginResponseDTO();
        response.setMemberId(memberId);
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);

        return response;
    }

    public GoogleLoginResponseDTO reissue(RefreshRequestDTO req) {

        String refreshToken = req.getRefreshToken();

        // JWT 형식 검증
        if (!jwtTokenProvider.validate(refreshToken)) {
            throw new BadRequestException("유효하지 않은 refreshToken");
        }

        Long memberId = jwtTokenProvider.getMemberId(refreshToken);

        // DB 존재/만료 검증
        RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new UnauthorizedException("refreshToken not found"));


        if (stored.isExpired(LocalDateTime.now())) {
            refreshTokenRepository.delete(stored);
            throw new UnauthorizedException("refreshToken expired");
        }

        // 새 토큰 발급
        String newAccess = jwtTokenProvider.createAccessToken(memberId);
        String newRefresh = jwtTokenProvider.createRefreshToken(memberId);

        // 기존 삭제 후 새로 저장
        refreshTokenRepository.delete(stored);
        refreshTokenRepository.save(
                RefreshToken.builder()
                        .token(newRefresh)
                        .memberId(memberId)
                        .expiresAt(LocalDateTime.now()
                                .plusSeconds(jwtTokenProvider.getRefreshExpMs() / 1000))
                        .build()
        );

        GoogleLoginResponseDTO response = new GoogleLoginResponseDTO();
        response.setMemberId(memberId);
        response.setAccessToken(newAccess);
        response.setRefreshToken(newRefresh);

        return response;
    }

    private GoogleUserInfoDTO verifyGoogle(GoogleLoginRequestDTO req) {
        GoogleTokenResponseDTO tokenRes = googleTokenClient.exchangeCode(
                req.getCode(),
                req.getRedirectUri(),
                req.getCodeVerifier()
        );

        return googleIdTokenVerifierClient.verify(tokenRes.getIdToken());
    }

}
