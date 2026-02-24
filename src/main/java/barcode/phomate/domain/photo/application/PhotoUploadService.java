package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.domain.photo.domain.repository.PhotoRepository;
import barcode.phomate.domain.photo.dto.*;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.fastapi.application.EmbeddingAsyncService;
import barcode.phomate.global.fastapi.dto.EmbedRequestDTO;
import barcode.phomate.global.s3.application.S3StorageService;
import barcode.phomate.global.tx.AfterCommitExecutor;
import barcode.phomate.global.util.ImageResizeUtil;
import barcode.phomate.global.util.ImageTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PhotoUploadService {

    private final PhotoRepository photoRepository;
    private final MemberRepository memberRepository;
    private final S3StorageService s3StorageService;

    private final AfterCommitExecutor afterCommitExecutor;
    private final EmbeddingAsyncService embeddingAsyncService;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    private static final long PRESIGNED_EXPIRE_SEC = 60 * 10; // 10min
    private static final long MAX_FILE_SIZE_BYTES = 30L * 1024 * 1024; // 30MB
    private static final String CACHE_CONTROL = "public, max-age=31536000";

    public PhotoUploadInitResponseDTO init(Long memberId, PhotoUploadInitRequestDTO request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("회원을 찾을 수 없습니다."));

        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new PhotoUploadInitResponseDTO(List.of());
        }

        List<PhotoUploadInitResultDTO> results = new ArrayList<>(request.items().size());

        Instant now = Instant.now();
        long expiresAtMs = now.plusSeconds(PRESIGNED_EXPIRE_SEC).toEpochMilli();

        for (PhotoUploadInitItemDTO item : request.items()) {
            validateInitItem(item);

            LocalDateTime shotAt = resolveShotAt(item.clientLastModifiedMs());

            Photo photo = photoRepository.save(Photo.builder()
                    .member(member)
                    .shotAt(shotAt)
                    .description(null)
                    .imagePrefix("TEMP")
                    .originalKey("TEMP")
                    .thumbnailKey("TEMP")
                    .previewKey("TEMP")
                    .build());

            String ext = ImageTypeUtil.safeExt(item.contentType(), item.originalFilename());

            String prefix = "photos/" + photo.getId();

            String v = UUID.randomUUID().toString().replace("-", "");

            String originalKey = prefix + "/o_" + v + "." + ext;

            String putUrl = s3StorageService.createPresignedPutUrl(originalKey, item.contentType(), PRESIGNED_EXPIRE_SEC);

            photo.updateImageKeys(prefix, originalKey, "TEMP", "TEMP");

            results.add(new PhotoUploadInitResultDTO(photo.getId(), originalKey, putUrl, expiresAtMs));
        }

        return new PhotoUploadInitResponseDTO(results);
    }

    public PhotoUploadCommitResponseDTO commit(Long memberId, PhotoUploadCommitRequestDTO request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new PhotoUploadCommitResponseDTO(List.of());
        }

        List<PhotoUploadCommitResultDTO> results = new ArrayList<>(request.items().size());

        for (PhotoUploadCommitItemDTO item : request.items()) {
            validateCommitItem(item);

            Photo photo = photoRepository.findById(item.photoId())
                    .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));

            if (!photo.getMember().getId().equals(memberId)) {
                throw new ForbiddenException("권한이 없습니다.");
            }

            HeadObjectResponse head = s3StorageService.headObject(item.originalKey());

            if (item.etag() != null && !item.etag().isBlank()) {
                String serverEtag = normalizeEtag(head.eTag());
                String clientEtag = normalizeEtag(item.etag());

                if (serverEtag != null && clientEtag != null && !serverEtag.equals(clientEtag)) {
                    throw new IllegalStateException("ETag mismatch: 업로드된 파일이 다릅니다.");
                }
            }

            byte[] originalBytes = s3StorageService.getObjectBytes(item.originalKey());

            byte[] thumbJpg;
            byte[] previewJpg;
            try {
                thumbJpg = ImageResizeUtil.toJpgResized(originalBytes, 320, 0.82f);
                previewJpg = ImageResizeUtil.toJpgResized(originalBytes, 1080, 0.85f);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode/resize image.", e);
            }

            String prefix = (photo.getImagePrefix() != null && !"TEMP".equals(photo.getImagePrefix()))
                    ? photo.getImagePrefix()
                    : "photos/" + photo.getId();

            long v = System.currentTimeMillis();
            String thumbKey = prefix + "/t_" + v + ".jpg";
            String previewKey = prefix + "/p_" + v + ".jpg";

            s3StorageService.putBytes(thumbKey, thumbJpg, "image/jpeg", CACHE_CONTROL);
            s3StorageService.putBytes(previewKey, previewJpg, "image/jpeg", CACHE_CONTROL);

            photo.updateImageKeys(prefix, item.originalKey(), thumbKey, previewKey);

            String previewUrl = cloudFrontBaseUrl + "/" + previewKey;

            Long createdAtMs = photo.getCreatedAt()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();

            String textForEmbedding = (photo.getDescription() == null) ? "" : photo.getDescription().trim();

            EmbedRequestDTO reqDto = new EmbedRequestDTO(
                    photo.getId(),
                    memberId,
                    previewUrl,
                    textForEmbedding,
                    createdAtMs
            );

            afterCommitExecutor.run(() -> {
                try {
                    embeddingAsyncService.embedPhoto(reqDto);
                } catch (Exception e) {
                    log.error("[EMBED] enqueue failed photoId={} err={}", photo.getId(), e.getMessage(), e);
                }
            });

            results.add(new PhotoUploadCommitResultDTO(photo.getId(), previewUrl));
        }

        return new PhotoUploadCommitResponseDTO(results);
    }

    private void validateInitItem(PhotoUploadInitItemDTO item) {
        if (item == null) {
            throw new IllegalArgumentException("items 요소가 null 입니다.");
        }

        String contentType = (item.contentType() == null) ? "" : item.contentType().trim().toLowerCase();
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다. contentType=" + item.contentType());
        }

        if (item.size() != null && item.size() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("파일이 너무 큽니다. max=" + MAX_FILE_SIZE_BYTES + " bytes");
        }
    }

    private void validateCommitItem(PhotoUploadCommitItemDTO item) {
        if (item == null) {
            throw new IllegalArgumentException("items 요소가 null 입니다.");
        }
        if (item.photoId() == null) {
            throw new IllegalArgumentException("photoId는 필수입니다.");
        }
        if (item.originalKey() == null || item.originalKey().isBlank()) {
            throw new IllegalArgumentException("originalKey는 필수입니다.");
        }
        if (item.etag() == null || item.etag().isBlank()) {
            throw new IllegalArgumentException("etag는 필수입니다.");
        }
    }

    private LocalDateTime resolveShotAt(Long clientLastModifiedMs) {
        if (clientLastModifiedMs != null && clientLastModifiedMs > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(clientLastModifiedMs), ZoneId.systemDefault());
        }
        return LocalDateTime.now();
    }

    private String normalizeEtag(String etag) {
        if (etag == null) return null;
        String t = etag.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t.isBlank() ? null : t;
    }
}
