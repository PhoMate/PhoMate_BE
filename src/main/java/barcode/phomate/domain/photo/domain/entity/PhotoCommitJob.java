package barcode.phomate.domain.photo.domain.entity;

import barcode.phomate.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a single-photo commit job enqueued by the upload commit endpoint.
 *
 * <p>One job corresponds to one photo. Multiple jobs sharing the same
 * {@code batchId} belong to the same commit request.
 *
 * <p>Status transitions:
 * <pre>
 *   PENDING → PROCESSING → DONE
 *                        ↘ PENDING (retry, with back-off)
 *                        ↘ FAILED  (retry_count ≥ maxRetries)
 * </pre>
 *
 * <p>S3 keys for thumbnail and preview are derived deterministically from
 * {@code photoId}, so re-processing simply overwrites the same objects.
 */
@Entity
@Table(
        name = "photo_commit_job",
        indexes = {
                @Index(name = "idx_pcj_status_next", columnList = "status, next_attempt_at"),
                @Index(name = "idx_pcj_updated_at",  columnList = "updated_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhotoCommitJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Groups all jobs created from the same commit request. */
    @Column(nullable = false, length = 36)
    private String batchId;

    /** FK to {@code photo.id}; the photo is pre-created by the init step. */
    @Column(nullable = false)
    private Long photoId;

    /** Used to verify member ownership before processing. */
    @Column(nullable = false)
    private Long memberId;

    /** Staging S3 key uploaded by the client; source for S3 download. */
    @Column(nullable = false, length = 512)
    private String originalKey;

    /**
     * ETag supplied by the client for integrity verification.
     * Nullable — verification is skipped when blank.
     */
    @Column(length = 255)
    private String etag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PhotoCommitJobStatus status;

    /** Number of processing attempts already made. Starts at 0. */
    @Column(nullable = false)
    private int retryCount;

    /**
     * Earliest time at which the poller may pick up this job.
     * Set to {@code now} on creation; advanced with exponential back-off on retry.
     */
    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    /**
     * Truncated error message from the most recent failed attempt.
     * Null when the job has never failed.
     */
    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Builder
    public PhotoCommitJob(String batchId,
                          Long photoId,
                          Long memberId,
                          String originalKey,
                          String etag) {
        this.batchId       = batchId;
        this.photoId       = photoId;
        this.memberId      = memberId;
        this.originalKey   = originalKey;
        this.etag          = etag;
        this.status        = PhotoCommitJobStatus.PENDING;
        this.retryCount    = 0;
        this.nextAttemptAt = LocalDateTime.now();
    }

    // ── State transitions ────────────────────────────────────────────────────

    /** Moves the job into PROCESSING state. */
    public void markProcessing() {
        this.status = PhotoCommitJobStatus.PROCESSING;
    }

    /** Moves the job into DONE state and clears any previous error. */
    public void markDone() {
        this.status    = PhotoCommitJobStatus.DONE;
        this.lastError = null;
    }

    /**
     * Increments {@code retryCount} and either re-schedules (PENDING with
     * exponential back-off) or permanently marks the job FAILED when
     * {@code retryCount >= maxRetries}.
     *
     * @param errorMessage  human-readable cause; stored truncated to 1 000 chars
     * @param maxRetries    total attempt limit before moving to FAILED; must be ≥ 1
     * @param backoffBaseMs base delay in milliseconds; doubles each retry
     */
    public void scheduleRetry(String errorMessage, int maxRetries, long backoffBaseMs) {
        this.retryCount++;
        this.lastError = truncate(errorMessage, 1_000);

        if (this.retryCount >= maxRetries) {
            this.status = PhotoCommitJobStatus.FAILED;
        } else {
            // Exponential back-off: base * 2^(retryCount-1)
            long delayMs = backoffBaseMs * (1L << (this.retryCount - 1));
            this.status        = PhotoCommitJobStatus.PENDING;
            this.nextAttemptAt = LocalDateTime.now().plusNanos(delayMs * 1_000_000L);
        }
    }

    /**
     * Resets a stuck PROCESSING job back to PENDING so the poller can retry it.
     * Used by the stuck-sweep to recover jobs whose worker died mid-flight.
     */
    public void resetToPending() {
        this.status        = PhotoCommitJobStatus.PENDING;
        this.nextAttemptAt = LocalDateTime.now();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
