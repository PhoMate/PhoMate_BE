package barcode.phomate.domain.follow.application;

import barcode.phomate.domain.follow.domain.entity.Follow;
import barcode.phomate.domain.follow.domain.repository.FollowRepository;
import barcode.phomate.domain.follow.dto.FollowToggleResponseDTO;
import barcode.phomate.domain.follow.dto.FolloweeResponseDTO;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.global.exception.BadRequestException;
import barcode.phomate.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class FollowServiceImpl implements FollowService {

    private final MemberRepository memberRepository;
    private final FollowRepository followRepository;

    @Override
    public FollowToggleResponseDTO toggleFollow(Long followerId, Long followeeId) {

        if (followerId.equals(followeeId)) {
            throw new BadRequestException("자기 자신은 팔로우할 수 없습니다.");
        }

        Member follower = memberRepository.findById(followerId)
                .orElseThrow(() -> new NotFoundException("멤버가 존재하지 않습니다."));

        Member followee = memberRepository.findById(followeeId)
                .orElseThrow(() -> new NotFoundException("멤버가 존재하지 않습니다."));

        boolean followed;

        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            followRepository.deleteByFollowerIdAndFolloweeId(followerId, followeeId);
            followed = false;
        } else {
            try {
                followRepository.save(Follow.builder()
                        .follower(follower)
                        .followee(followee)
                        .build());
                followed = true;
            } catch (DataIntegrityViolationException e) {
                log.warn("Duplicate follow insert ignored. followerId={}, followeeId={}", followerId, followeeId, e);
                followed = true;
            }
        }

        FollowToggleResponseDTO res = new FollowToggleResponseDTO();
        res.setFollowed(followed);
        return res;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FolloweeResponseDTO> getMyFollowees(Long followerId) {
        if (!memberRepository.existsById(followerId)) {
            throw new NotFoundException("멤버가 존재하지 않습니다.");
        }

        List<Member> followees = followRepository.findFollowees(followerId);
        return followees.stream().map(FolloweeResponseDTO::from).toList();
    }
}
