package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.domain.photo.domain.entity.PhotoCommitJob;
import barcode.phomate.domain.photo.domain.repository.PhotoRepository;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.fastapi.application.EmbeddingAsyncService;
import barcode.phomate.global.fastapi.dto.EmbedRequestDTO;
import barcode.phomate.global.s3.application.S3StorageService;
import barcode.phomate.global.tx.AfterCommitExecutor;
import barcode.phomate.global.util.ExifUtil;
import barcode.phomate.global.util.ImageResizeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Executes the per-photo S3 processing pipeline for a single {@link PhotoCommitJob}.
 *
 * <p>Runs in its own {@code REQUIRES_NEW} transaction so that photo-side changes
 * commit (or roll back) independently of the job-state transaction managed by
 * {@link PhotoCommitJobService}. This ensures that a processing failure never
 * contaminates the job status update.
 *
 * <p><strong>Idempotency</strong>: thumbnail and preview S3 keys are derived
 * deterministically from {@code photo.id} ({@code photos/{id}/t.jpg},
 * {@code photos/{id}/p.jpg}). Re-processing the same job simply overwrites the
 * same objects, making retries safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoCommitJobProcessor {

    private final PhotoRepository          photoRepository;
    private final S3StorageService         s3StorageService;
    private final EmbeddingAsyncService    embeddingAsyncService;
    private final AfterCommitExecutor      afterCommitExecutor;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    private static final String CACHE_CONTROL  = "public, max-age=31536000";
    private static final int    THUMB_SHORT_PX = 320;
    private static final int    PREVIEW_SHORT_PX = 1_080;
    private static final float  THUMB_QUALITY  = 0.82f;
    private static final float  PREVIEW_QUALITY = 0.85f;

    /**
     * Downloads the original image from S3, generates thumbnail and preview
     * JPEGs, uploads them, updates the {@link Photo} entity, and enqueues an
     * embedding trigger to run after this transaction commits.
     *
     * @param job  the job to process; only its fields are read — the entity is
     *             not mutated here
     * @throws NotFoundException      if the associated photo no longer exists in the DB
     * @throws ForbiddenException     if {@code job.memberId} does not own the photo
     * @throws IllegalStateException  if the client-supplied ETag does not match S3,
     *                                or if the image cannot be decoded/resized
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(PhotoCommitJob job) {
        Photo photo = photoRepository.findById(job.getPhotoId())
                .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));

        if (!photo.getMember().getId().equals(job.getMemberId())) {
            throw new ForbiddenException("권한이 없습니다.");
        }

        // ETag integrity check (optional — skipped when etag is blank)
        if (job.getEtag() != null && !job.getEtag().isBlank()) {
            HeadObjectResponse head = s3StorageService.headObject(job.getOriginalKey());
            String serverEtag = normalizeEtag(head.eTag());
            String clientEtag = normalizeEtag(job.getEtag());
            if (serverEtag != null && clientEtag != null && !serverEtag.equals(clientEtag)) {
                throw new IllegalStateException("ETag mismatch: 업로드된 파일이 다릅니다.");
            }
        }

        byte[] originalBytes = s3StorageService.getObjectBytes(job.getOriginalKey());

        // EXIF 촬영일이 있으면 shotAt을 실제 촬영 시각으로 갱신 (없으면 업로드 시 값 유지)
        ExifUtil.extractShotAt(originalBytes).ifPresent(photo::updateShotAt);

        byte[] thumbJpg;
        byte[] previewJpg;
        try {
            thumbJpg   = ImageResizeUtil.toJpgResized(originalBytes, THUMB_SHORT_PX,   THUMB_QUALITY);
            previewJpg = ImageResizeUtil.toJpgResized(originalBytes, PREVIEW_SHORT_PX, PREVIEW_QUALITY);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode/resize image.", e);
        }

        // Deterministic keys derived from photo ID → overwrite-safe on retry
        String prefix     = "photos/" + photo.getId();
        String thumbKey   = prefix + "/t.jpg";
        String previewKey = prefix + "/p.jpg";

        s3StorageService.putBytes(thumbKey,   thumbJpg,   "image/jpeg", CACHE_CONTROL);
        s3StorageService.putBytes(previewKey, previewJpg, "image/jpeg", CACHE_CONTROL);

        photo.updateImageKeys(prefix, job.getOriginalKey(), thumbKey, previewKey);

        String previewUrl  = cloudFrontBaseUrl + "/" + previewKey;

        // Qdrant 날짜 필터용: 촬영 시각(shotAt, KST 벽시계)을 KST 기준 epoch ms로 변환해 보낸다.
        // (EmbedRequestDTO의 필드명은 createdAtMs지만 실제로는 shotAt을 담는다 → 워커 무수정)
        LocalDateTime shotAt = (photo.getShotAt() != null) ? photo.getShotAt() : photo.getCreatedAt();
        long shotAtMs = shotAt.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();

        String text = (photo.getDescription() == null) ? "" : photo.getDescription().trim();

        EmbedRequestDTO embedReq = new EmbedRequestDTO(
                photo.getId(), job.getMemberId(), previewUrl, text, shotAtMs);

        // Trigger embedding only after this REQUIRES_NEW transaction commits
        afterCommitExecutor.run(() -> {
            try {
                embeddingAsyncService.embedPhoto(embedReq);
            } catch (Exception e) {
                log.error("[EMBED] enqueue failed photoId={} err={}", photo.getId(), e.getMessage(), e);
            }
        });

        log.info("[PROCESSOR] done photoId={} batchId={}", photo.getId(), job.getBatchId());
    }

    /**
     * Strips surrounding double-quotes from an ETag value so that
     * {@code "abc"} and {@code abc} compare equal.
     *
     * @param etag raw ETag string, may be null
     * @return unquoted ETag, or null if the result would be blank
     */
    private String normalizeEtag(String etag) {
        if (etag == null) return null;
        String t = etag.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t.isBlank() ? null : t;
    }
}
