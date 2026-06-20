package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.photo.domain.entity.PhotoCommitJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled poller that drives the photo commit job pipeline.
 *
 * <p>Two independent schedules run here:
 * <ul>
 *   <li>{@link #poll()} — every 2 s, claims PENDING jobs and processes each
 *       one in its own transaction via {@link PhotoCommitJobService}.</li>
 *   <li>{@link #sweepStuck()} — every 60 s, resets PROCESSING jobs that have
 *       been stuck beyond {@value #STUCK_THRESHOLD_MINUTES} minutes back to
 *       PENDING so they can be retried.</li>
 * </ul>
 *
 * <p>Each job is processed in a separate transaction (via
 * {@link PhotoCommitJobService#processOne}), so a failure in one job never
 * affects the others claimed in the same poll cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoCommitJobPoller {

    private static final int  POLL_BATCH_SIZE         = 10;
    private static final int  SWEEP_LIMIT             = 50;
    private static final long STUCK_THRESHOLD_MINUTES = 10L;

    private final PhotoCommitJobService jobService;

    /**
     * Claims up to {@value #POLL_BATCH_SIZE} PENDING jobs and processes each
     * sequentially. Runs every 2 seconds with a fixed delay between completions
     * to avoid overlapping executions.
     */
    @Scheduled(fixedDelay = 2_000)
    public void poll() {
        List<PhotoCommitJob> claimed = jobService.claimPending(POLL_BATCH_SIZE);
        if (claimed.isEmpty()) return;

        log.info("[POLLER] claimed {} jobs", claimed.size());
        for (PhotoCommitJob job : claimed) {
            jobService.processOne(job.getId());
        }
    }

    /**
     * Finds PROCESSING jobs whose {@code updated_at} is older than
     * {@value #STUCK_THRESHOLD_MINUTES} minutes and resets them to PENDING.
     * Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void sweepStuck() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(STUCK_THRESHOLD_MINUTES));
        int recovered = jobService.sweepStuck(threshold, SWEEP_LIMIT);
        if (recovered > 0) {
            log.info("[SWEEP] reset {} stuck jobs to PENDING", recovered);
        }
    }
}
