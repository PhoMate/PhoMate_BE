package barcode.phomate.domain.post.application;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.post.domain.entity.Post;
import barcode.phomate.domain.post.domain.repository.PostRepository;
import barcode.phomate.domain.post.dto.PostCreateRequestDTO;
import barcode.phomate.global.fastapi.application.EmbeddingAsyncService;
import barcode.phomate.global.fastapi.dto.EmbedRequestDTO;
import barcode.phomate.global.s3.service.S3StorageService;
import barcode.phomate.global.tx.AfterCommitExecutor;
import barcode.phomate.global.util.ImageResizeUtil;
import barcode.phomate.global.util.ImageTypeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Transactional
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final S3StorageService s3StorageService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final EmbeddingAsyncService embeddingAsyncService;
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
                .build());

        String prefix = "posts/" + post.getId();

        String ext = ImageTypeUtil.safeExt(image.getContentType(), image.getOriginalFilename());
        String originalContentType = ImageTypeUtil.normalizeContentType(image.getContentType(), ext);

        byte[] originalBytes;
        try {
            originalBytes = image.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded image bytes.", e);
        }

        String originalKey = prefix + "/o." + ext;

        byte[] thumbJpg;
        byte[] previewJpg;
        try {
            thumbJpg = ImageResizeUtil.toJpgResized(originalBytes, 320, 0.82f);
            previewJpg = ImageResizeUtil.toJpgResized(originalBytes, 1080, 0.85f);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode/resize image.", e);
        }

        String cache = "public, max-age=31536000";
        s3StorageService.putBytes(originalKey, originalBytes, originalContentType, cache);
        s3StorageService.putBytes(prefix + "/t.jpg", thumbJpg, "image/jpeg", cache);
        s3StorageService.putBytes(prefix + "/p.jpg", previewJpg, "image/jpeg", cache);

        post.updateImageKeys(prefix, originalKey);

        String previewKey = prefix + "/p.jpg";
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

}
