package barcode.phomate.domain.likes.application;

import barcode.phomate.domain.likes.domain.entity.Likes;
import barcode.phomate.domain.likes.domain.repository.LikesRepository;
import barcode.phomate.domain.likes.dto.LikesToggleResponse;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.post.domain.entity.Post;
import barcode.phomate.domain.post.domain.repository.PostRepository;
import barcode.phomate.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class LikesServiceImpl implements LikesService {

    private final MemberRepository memberRepository;
    private final PostRepository postRepository;
    private final LikesRepository likesRepository;

    @Override
    public LikesToggleResponse toggleLikes(Long memberId, Long postId) {

        Member member = memberRepository.findById(memberId).orElseThrow(
                () -> new NotFoundException("멤버가 존재하지 않습니다."));

        Post post = postRepository.findById(postId).orElseThrow(
                () -> new NotFoundException("게시글이 존재하지 않습니다."));

        boolean liked;

        if (likesRepository.existsByMemberIdAndPostId(memberId, postId)) {
            // unlike
            likesRepository.deleteByMemberIdAndPostId(memberId, postId);
            postRepository.decrementLikeCount(postId);
            liked = false;
        } else {
            // like
            try {
                likesRepository.save(Likes.builder()
                        .member(member)
                        .post(post)
                        .build());
                postRepository.incrementLikeCount(postId);
                liked = true;
            } catch (DataIntegrityViolationException e) {
                log.warn("Duplicate like insert ignored. memberId={}, postId={}", memberId, postId, e);
                liked = true;
            }
        }

        long likeCount = postRepository.findLikeCount(postId);

        LikesToggleResponse response = new LikesToggleResponse();
        response.setLiked(liked);
        response.setLikeCount(likeCount);
        return response;
    }
}