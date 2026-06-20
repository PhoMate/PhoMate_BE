package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.photo.domain.entity.PhotoCommitJob;
import barcode.phomate.domain.photo.domain.repository.PhotoCommitJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages the lifecycle of {@link PhotoCommitJob} entities within the
 * job-table-based async upload pipeline.
 *
 * <p>Each public method runs in its own transaction so that the job-state
 * update (claim / done / retry / FAILED) is committed independently of the
 * photo-processing work handled by {@link PhotoCommitJobProcessor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoCommitJobService {

    static final int  MAX_RETRIES     = 3;
    static final long BACKOFF_BASE_MS = 5_000L;

    private final PhotoCommitJobRepository repository;
    private final PhotoCommitJobProcessor  processor;

    /**
     * Atomically claims up to {@code limit} PENDING jobs by marking them
     * PROCESSING. Uses {@code FOR UPDATE SKIP LOCKED} to prevent concurrent
     * pollers from picking the same rows.
     *
     * @param limit maximum number of jobs to claim; must be &gt; 0
     * @return list of now-PROCESSING jobs (may be empty if nothing is due)
     */
    @Transactional
    public List<PhotoCommitJob> claimPending(int limit) {
        List<PhotoCommitJob> jobs = repository.findPendingForUpdate(limit);
        jobs.forEach(PhotoCommitJob::markProcessing);
        return jobs;
    }

    /**
     * Processes a single job in its own transaction.
     *
     * <p>Delegates the actual S3 work to {@link PhotoCommitJobProcessor#process},
     * which runs in a nested {@code REQUIRES_NEW} transaction. On success the job
     * is marked DONE. On failure {@code retry_count} is incremented and the job
     * is either re-scheduled (PENDING with exponential back-off) or permanently
     * marked FAILED when {@value MAX_RETRIES} attempts have been exhausted.
     *
     * @param jobId ID of an existing job; must be in PROCESSING state
     * @throws IllegalStateException if {@code jobId} does not exist
     */
    @Transactional
    public void processOne(Long jobId) {
        PhotoCommitJob job = repository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        try {
            processor.process(job);
            job.markDone();
            log.info("[JOB] DONE jobId={} photoId={}", jobId, job.getPhotoId());
        } catch (Exception e) {
            log.warn("[JOB] attempt failed jobId={} photoId={} retryCount={} err={}",
                    jobId, job.getPhotoId(), job.getRetryCount() + 1, e.getMessage());
            job.scheduleRetry(e.getMessage(), MAX_RETRIES, BACKOFF_BASE_MS);
            if (job.getStatus().name().equals("FAILED")) {
                log.error("[JOB] permanently FAILED jobId={} photoId={}", jobId, job.getPhotoId());
            }
        }
    }

    /**
     * Recovers PROCESSING jobs that have been stuck beyond {@code threshold}
     * by resetting them to PENDING. This handles the case where a worker node
     * died mid-flight and never committed a final status.
     *
     * @param threshold  jobs last updated before this instant are considered stuck
     * @param limit      maximum number of stuck jobs to recover in one sweep
     * @return number of jobs reset to PENDING
     */
    @Transactional
    public int sweepStuck(LocalDateTime threshold, int limit) {
        List<PhotoCommitJob> stuck = repository.findStuckProcessingForUpdate(threshold, limit);
        stuck.forEach(PhotoCommitJob::resetToPending);
        if (!stuck.isEmpty()) {
            log.warn("[SWEEP] recovered {} stuck jobs older than {}", stuck.size(), threshold);
        }
        return stuck.size();
    }
}
