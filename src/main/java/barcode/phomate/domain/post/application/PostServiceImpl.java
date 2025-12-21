package barcode.phomate.domain.post.application;

import barcode.phomate.domain.likes.domain.repository.LikesRepository;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.post.domain.entity.Post;
import barcode.phomate.domain.post.domain.repository.PostRepository;
import barcode.phomate.domain.post.dto.PostCreateRequestDTO;
import barcode.phomate.domain.post.dto.PostFeedResponseDTO;
import barcode.phomate.domain.post.dto.PostResponseDTO;
import barcode.phomate.domain.post.dto.PostSortType;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.fastapi.application.EmbeddingAsyncService;
import barcode.phomate.global.fastapi.dto.EmbedRequestDTO;
import barcode.phomate.global.s3.application.S3DeleteAsyncService;
import barcode.phomate.global.s3.application.S3StorageService;
import barcode.phomate.global.tx.AfterCommitExecutor;
import barcode.phomate.global.util.ImageResizeUtil;
import barcode.phomate.global.util.ImageTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final LikesRepository likesRepository;
    private final S3StorageService s3StorageService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final EmbeddingAsyncService embeddingAsyncService;
    private final S3DeleteAsyncService s3DeleteAsyncService;
    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;
    @Override
    public Long createPost(Long memberId, PostCreateRequestDTO request, MultipartFile image) {
        Member member = memberRepository.findById(memberId).orElseThrow();

        Post post = postRepository.save(Post.builder()
                .member(member)
                .title(request.getTitle())
                .description(request.getDescription())
                .imagePrefix("TEMP")
                .originalKey("TEMP")
                .thumbnailKey("TEMP")
                .previewKey("TEMP")
                .build());

        String prefix = "posts/" + post.getId();

        long v = System.currentTimeMillis();

        String ext = ImageTypeUtil.safeExt(image.getContentType(), image.getOriginalFilename());
        String originalContentType = ImageTypeUtil.normalizeContentType(image.getContentType(), ext);

        byte[] originalBytes;
        try {
            originalBytes = image.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded image bytes.", e);
        }

        byte[] thumbJpg;
        byte[] previewJpg;
        try {
            thumbJpg = ImageResizeUtil.toJpgResized(originalBytes, 320, 0.82f);
            previewJpg = ImageResizeUtil.toJpgResized(originalBytes, 1080, 0.85f);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode/resize image.", e);
        }

        String originalKey = prefix + "/o_" + v + "." + ext;
        String thumbKey    = prefix + "/t_" + v + ".jpg";
        String previewKey  = prefix + "/p_" + v + ".jpg";

        String cache = "public, max-age=31536000";
        s3StorageService.putBytes(originalKey, originalBytes, originalContentType, cache);
        s3StorageService.putBytes(thumbKey, thumbJpg, "image/jpeg", cache);
        s3StorageService.putBytes(previewKey, previewJpg, "image/jpeg", cache);

        post.updateImageKeys(prefix, originalKey, thumbKey, previewKey);

        String previewUrl = cloudFrontBaseUrl + "/" + previewKey;

        Long createdAtMs = post.getCreatedAt()
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        EmbedRequestDTO reqDto = new EmbedRequestDTO(
                post.getId(),
                member.getId(),
                previewUrl,
                request.getTitle() + " " + request.getDescription(),
                createdAtMs
        );

        afterCommitExecutor.run(() -> embeddingAsyncService.embedPost(reqDto));
        return post.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public PostFeedResponseDTO getFeed(PostSortType sort,
                                       String cursorTime,
                                       Long cursorLike,
                                       Long cursorId,
                                       int size,
                                       Long memberId) {

        int pageSize = Math.min(Math.max(size, 1), 50);
        var pageable = PageRequest.of(0, pageSize);

        List<Post> posts = switch (sort) {
            case LATEST -> {
                LocalDateTime time = (cursorTime == null || cursorTime.isBlank())
                        ? null
                        : LocalDateTime.parse(cursorTime);
                yield postRepository.findFeedLatest(time, cursorId, pageable);
            }
            case LIKE -> postRepository.findFeedLike(cursorLike, cursorId, pageable);
        };

        if (posts.isEmpty()) {
            return PostFeedResponseDTO.empty();
        }

        List<Long> postIds = posts.stream().map(Post::getId).toList();

        final Set<Long> likedSet =
                (memberId == null || postIds.isEmpty())
                        ? Set.of()
                        : new HashSet<>(likesRepository.findLikedPostIds(memberId, postIds));

        List<PostResponseDTO> items = posts.stream()
                .map(p -> PostResponseDTO.of(
                        p.getId(),
                        p.getTitle(),
                        cloudFrontBaseUrl + "/" + p.getThumbnailKey(),
                        p.getLikeCount(),
                        likedSet.contains(p.getId())
                ))
                .toList();

        Post last = posts.get(posts.size() - 1);
        PostFeedResponseDTO.Cursor nextCursor = (sort == PostSortType.LATEST)
                ? PostFeedResponseDTO.Cursor.latest(last.getCreatedAt().toString(), last.getId())
                : PostFeedResponseDTO.Cursor.like(last.getLikeCount(), last.getId());

        boolean hasNext = posts.size() == pageSize;
        return PostFeedResponseDTO.of(items, nextCursor, hasNext);
    }

    @Override
    public Long updatePost(Long memberId, Long postId, PostCreateRequestDTO request, MultipartFile image) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new NotFoundException("게시글을 찾을 수 없습니다."));

        if (!post.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("수정 권한이 없습니다.");
        }

        String newTitle = hasText(request.getTitle()) ? request.getTitle().trim() : post.getTitle();
        String newDesc  = hasText(request.getDescription()) ? request.getDescription().trim() : post.getDescription();
        post.updateText(newTitle, newDesc);

        final String oldOriginalKey = post.getOriginalKey();
        final String oldThumbKey = post.getThumbnailKey();
        final String oldPreviewKey = post.getPreviewKey();

        final String prefix = "posts/" + post.getId();

        boolean imageChanged = (image != null && !image.isEmpty());

        String newOriginalKey = oldOriginalKey;
        String newThumbKey = oldThumbKey;
        String newPreviewKey = oldPreviewKey;

        if (imageChanged) {
            long v = System.currentTimeMillis();

            String ext = ImageTypeUtil.safeExt(image.getContentType(), image.getOriginalFilename());
            String originalContentType = ImageTypeUtil.normalizeContentType(image.getContentType(), ext);

            byte[] originalBytes;
            try {
                originalBytes = image.getBytes();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read uploaded image bytes.", e);
            }

            byte[] thumbJpg;
            byte[] previewJpg;
            try {
                thumbJpg = ImageResizeUtil.toJpgResized(originalBytes, 320, 0.82f);
                previewJpg = ImageResizeUtil.toJpgResized(originalBytes, 1080, 0.85f);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode/resize image.", e);
            }

            newOriginalKey = prefix + "/o_" + v + "." + ext;
            newThumbKey    = prefix + "/t_" + v + ".jpg";
            newPreviewKey  = prefix + "/p_" + v + ".jpg";

            String cache = "public, max-age=31536000";
            s3StorageService.putBytes(newOriginalKey, originalBytes, originalContentType, cache);
            s3StorageService.putBytes(newThumbKey, thumbJpg, "image/jpeg", cache);
            s3StorageService.putBytes(newPreviewKey, previewJpg, "image/jpeg", cache);

            post.updateImageKeys(prefix, newOriginalKey, newThumbKey, newPreviewKey);
        }

        final String previewUrl = cloudFrontBaseUrl + "/" + newPreviewKey;
        final Long createdAtMs = post.getCreatedAt()
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        final EmbedRequestDTO reqDto = new EmbedRequestDTO(
                post.getId(),
                post.getMember().getId(),
                previewUrl,
                newTitle + " " + newDesc,
                createdAtMs
        );

        afterCommitExecutor.run(() -> {
            embeddingAsyncService.embedPost(reqDto);
            if (imageChanged) {
                s3DeleteAsyncService.deletePostImages(postId, oldOriginalKey, oldThumbKey, oldPreviewKey);
            }
        });

        return post.getId();
    }

    @Override
    public void deletePost(Long memberId, Long postId) {

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new NotFoundException("게시글을 찾을 수 없습니다."));

        Long ownerId = post.getMember().getId();
        if (!ownerId.equals(memberId)) {
            throw new ForbiddenException("삭제 권한이 없습니다.");
        }

        final String originalKey = post.getOriginalKey();
        final String thumbKey = post.getThumbnailKey();
        final String previewKey = post.getPreviewKey();

        postRepository.delete(post);

        afterCommitExecutor.run(() -> {
            try {
                embeddingAsyncService.deletePostVector(postId);
            } catch (Exception e) {
                log.error("[VEC-DEL] enqueue failed postId={} err={}", postId, e.getMessage(), e);
            }

            try {
                s3DeleteAsyncService.deletePostImages(postId, originalKey, thumbKey, previewKey);
            } catch (Exception e) {
                log.error("[S3-DEL] enqueue failed postId={} originalKey={} thumbKey={} previewKey={} err={}",
                        postId, originalKey, thumbKey, previewKey, e.getMessage(), e);
            }
        });

        log.info("[POST-DEL] deleted postId={} memberId={}", postId, memberId);
    }


    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
