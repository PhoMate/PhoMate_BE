package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.domain.photo.domain.entity.PhotoCommitJob;
import barcode.phomate.domain.photo.domain.repository.PhotoCommitJobRepository;
import barcode.phomate.domain.photo.domain.repository.PhotoRepository;
import barcode.phomate.domain.photo.dto.*;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.s3.application.S3StorageService;
import barcode.phomate.global.util.ImageTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PhotoUploadService {

    private final PhotoRepository           photoRepository;
    private final MemberRepository          memberRepository;
    private final S3StorageService          s3StorageService;
    private final PhotoCommitJobRepository  commitJobRepository;

    private static final long PRESIGNED_EXPIRE_SEC = 60 * 10; // 10min
    private static final long MAX_FILE_SIZE_BYTES  = 30L * 1024 * 1024; // 30MB

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

    /**
     * Validates each item, verifies member ownership, creates one
     * {@link PhotoCommitJob} per photo, and returns immediately.
     * The actual S3 processing (download → resize → upload → embed)
     * is handled asynchronously by {@link PhotoCommitJobPoller}.
     *
     * @param memberId ID of the authenticated member
     * @param request  list of photos to commit; ignored when null or empty
     * @return batch ID and the list of enqueued photo IDs
     * @throws NotFoundException  if any photoId does not exist
     * @throws ForbiddenException if any photo does not belong to {@code memberId}
     */
    public PhotoUploadCommitResponseDTO commit(Long memberId, PhotoUploadCommitRequestDTO request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new PhotoUploadCommitResponseDTO(UUID.randomUUID().toString(), List.of());
        }

        String batchId = UUID.randomUUID().toString();
        List<Long> photoIds = new ArrayList<>(request.items().size());

        for (PhotoUploadCommitItemDTO item : request.items()) {
            validateCommitItem(item);

            Photo photo = photoRepository.findById(item.photoId())
                    .orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));

            if (!photo.getMember().getId().equals(memberId)) {
                throw new ForbiddenException("권한이 없습니다.");
            }

            commitJobRepository.save(PhotoCommitJob.builder()
                    .batchId(batchId)
                    .photoId(photo.getId())
                    .memberId(memberId)
                    .originalKey(item.originalKey())
                    .etag(item.etag())
                    .build());

            photoIds.add(photo.getId());
        }

        log.info("[COMMIT] enqueued batchId={} count={}", batchId, photoIds.size());
        return new PhotoUploadCommitResponseDTO(batchId, photoIds);
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
        // shotAt은 KST 벽시계 기준으로 저장한다 (서버 기본 TZ가 UTC일 수 있으므로 명시)
        if (clientLastModifiedMs != null && clientLastModifiedMs > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(clientLastModifiedMs), ZoneId.of("Asia/Seoul"));
        }
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

}
