package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.domain.photo.domain.repository.PhotoRepository;
import barcode.phomate.domain.photo.dto.PhotoDetailResponseDTO;
import barcode.phomate.domain.photo.dto.PhotoFeedResponseDTO;
import barcode.phomate.domain.photo.dto.PhotoResponseDTO;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final MemberRepository memberRepository;
    private final S3StorageService s3StorageService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final EmbeddingAsyncService embeddingAsyncService;
    private final S3DeleteAsyncService s3DeleteAsyncService;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    public Long createPhoto(Long memberId, MultipartFile image, Long clientLastModifiedMs) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("회원을 찾을 수 없습니다."));

        LocalDateTime shotAt = resolveShotAt(clientLastModifiedMs);

        Photo photo = photoRepository.save(Photo.builder()
                .member(member)
                .shotAt(shotAt)
                .description(null) // 추후 vision ai 활용한 자동생성 기능 추가
                .imagePrefix("TEMP")
                .originalKey("TEMP")
                .thumbnailKey("TEMP")
                .previewKey("TEMP")
                .build());

        String prefix = "photos/" + photo.getId();
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

        photo.updateImageKeys(prefix, originalKey, thumbKey, previewKey);

        String previewUrl = cloudFrontBaseUrl + "/" + previewKey;

        Long createdAtMs = photo.getCreatedAt()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        String textForEmbedding = (photo.getDescription() == null) ? "" : photo.getDescription().trim();

        EmbedRequestDTO reqDto = new EmbedRequestDTO(
                photo.getId(),
                member.getId(),
                previewUrl,
                textForEmbedding,
                createdAtMs
        );

        afterCommitExecutor.run(() -> embeddingAsyncService.embedPhoto(reqDto));
        return photo.getId();
    }

    public Long updatePhoto(Long memberId, Long photoId, MultipartFile image, Long clientLastModifiedMs) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));

        if (!photo.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("수정 권한이 없습니다.");
        }

        boolean imageChanged = (image != null && !image.isEmpty());

        if (imageChanged) {
            photo.updateShotAt(resolveShotAt(clientLastModifiedMs));
        }

        final String oldOriginalKey = photo.getOriginalKey();
        final String oldThumbKey = photo.getThumbnailKey();
        final String oldPreviewKey = photo.getPreviewKey();

        final String prefix = "photos/" + photo.getId();

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

            photo.updateImageKeys(prefix, newOriginalKey, newThumbKey, newPreviewKey);
        }

        final String previewUrl = cloudFrontBaseUrl + "/" + newPreviewKey;
        final Long createdAtMs = photo.getCreatedAt()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        String textForEmbedding = (photo.getDescription() == null) ? "" : photo.getDescription().trim();

        final EmbedRequestDTO reqDto = new EmbedRequestDTO(
                photo.getId(),
                photo.getMember().getId(),
                previewUrl,
                textForEmbedding,
                createdAtMs
        );

        afterCommitExecutor.run(() -> {
            embeddingAsyncService.embedPhoto(reqDto);

            if (imageChanged) {
                s3DeleteAsyncService.deletePhotoImages(photoId, oldOriginalKey, oldThumbKey, oldPreviewKey);
            }
        });

        return photo.getId();
    }

    /**
     * 휴지통 보내기 (Soft Delete)
     * - DB row는 유지
     * - S3/Vector 삭제하지 않음
     */
    public void moveToTrash(Long memberId, Long photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));

        if (!photo.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("삭제 권한이 없습니다.");
        }

        // 멱등 처리
        if (!photo.isDeleted()) {
            photo.moveToTrash(LocalDateTime.now());
        }

        log.info("[PHOTO-TRASH] moved photoId={} memberId={}", photoId, memberId);
    }

    /**
     * 완전삭제 (Hard Delete / Purge)
     * 정책: 휴지통에 있는 사진만 완전삭제 가능
     * - DB row 삭제
     * - afterCommit으로 Vector/S3 삭제 enqueue
     */
    public void purgePhoto(Long memberId, Long photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));

        if (!photo.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("삭제 권한이 없습니다.");
        }

        if (!photo.isDeleted()) {
            throw new ForbiddenException("휴지통에 있는 사진만 완전삭제할 수 있습니다.");
        }

        final String originalKey = photo.getOriginalKey();
        final String thumbKey = photo.getThumbnailKey();
        final String previewKey = photo.getPreviewKey();

        photoRepository.delete(photo);

        afterCommitExecutor.run(() -> {
            try {
                embeddingAsyncService.deletePhotoVector(photoId);
            } catch (Exception e) {
                log.error("[VEC-DEL] enqueue failed photoId={} err={}", photoId, e.getMessage(), e);
            }

            try {
                s3DeleteAsyncService.deletePhotoImages(photoId, originalKey, thumbKey, previewKey);
            } catch (Exception e) {
                log.error("[S3-DEL] enqueue failed photoId={} originalKey={} thumbKey={} previewKey={} err={}",
                        photoId, originalKey, thumbKey, previewKey, e.getMessage(), e);
            }
        });

        log.info("[PHOTO-PURGE] deleted photoId={} memberId={}", photoId, memberId);
    }

    /**
     * 휴지통에서 복구
     */
    public void restorePhoto(Long memberId, Long photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));

        if (!photo.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("권한이 없습니다.");
        }

        // 멱등 처리
        if (photo.isDeleted()) {
            photo.restore();
        }

        log.info("[PHOTO-RESTORE] restored photoId={} memberId={}", photoId, memberId);
    }

    @Transactional(readOnly = true)
    public PhotoFeedResponseDTO getAlbumLatest(String cursorShotAt, Long cursorId, int size, Long memberId) {

        int pageSize = Math.min(Math.max(size, 1), 50);
        var pageable = PageRequest.of(0, pageSize);

        LocalDateTime shotAt = (cursorShotAt == null || cursorShotAt.isBlank())
                ? null
                : LocalDateTime.parse(cursorShotAt);

        List<Photo> photos = photoRepository.findAlbumLatest(memberId, shotAt, cursorId, pageable);

        if (photos.isEmpty()) {
            return PhotoFeedResponseDTO.empty();
        }

        List<PhotoResponseDTO> items = photos.stream()
                .map(p -> PhotoResponseDTO.of(
                        p.getId(),
                        cloudFrontBaseUrl + "/" + p.getThumbnailKey(),
                        cloudFrontBaseUrl + "/" + p.getPreviewKey(),
                        p.getShotAt()
                ))
                .toList();

        Photo last = photos.get(photos.size() - 1);
        PhotoFeedResponseDTO.Cursor nextCursor =
                PhotoFeedResponseDTO.Cursor.latest(last.getShotAt().toString(), last.getId());

        boolean hasNext = photos.size() == pageSize;
        return PhotoFeedResponseDTO.of(items, nextCursor, hasNext);
    }

    @Transactional(readOnly = true)
    public PhotoFeedResponseDTO getTrashLatest(String cursorDeletedAt, Long cursorId, int size, Long memberId) {

        int pageSize = Math.min(Math.max(size, 1), 50);
        var pageable = PageRequest.of(0, pageSize);

        LocalDateTime deletedAt = (cursorDeletedAt == null || cursorDeletedAt.isBlank())
                ? null
                : LocalDateTime.parse(cursorDeletedAt);

        List<Photo> photos = photoRepository.findTrashLatest(memberId, deletedAt, cursorId, pageable);

        if (photos.isEmpty()) return PhotoFeedResponseDTO.empty();

        List<PhotoResponseDTO> items = photos.stream()
                .map(p -> PhotoResponseDTO.of(
                        p.getId(),
                        cloudFrontBaseUrl + "/" + p.getThumbnailKey(),
                        cloudFrontBaseUrl + "/" + p.getPreviewKey(),
                        p.getShotAt()
                ))
                .toList();

        Photo last = photos.get(photos.size() - 1);
        PhotoFeedResponseDTO.Cursor nextCursor =
                PhotoFeedResponseDTO.Cursor.latest(last.getDeletedAt().toString(), last.getId());

        boolean hasNext = photos.size() == pageSize;
        return PhotoFeedResponseDTO.of(items, nextCursor, hasNext);
    }

    @Transactional(readOnly = true)
    public PhotoDetailResponseDTO getPhotoDetail(Long photoId, Long memberId) {

        Photo photo = photoRepository.findById(photoId).orElseThrow(
                () -> new NotFoundException("사진이 존재하지 않습니다.")
        );

        String originalUrl = cloudFrontBaseUrl + "/" + photo.getOriginalKey();

        Member author = photo.getMember();

        return PhotoDetailResponseDTO.of(
                photo.getId(),
                originalUrl,
                photo.getShotAt()
        );
    }


    private LocalDateTime resolveShotAt(Long clientLastModifiedMs) {
        // shotAt은 KST 벽시계 기준으로 저장한다 (서버 기본 TZ가 UTC일 수 있으므로 명시)
        if (clientLastModifiedMs != null && clientLastModifiedMs > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(clientLastModifiedMs), ZoneId.of("Asia/Seoul"));
        }
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
