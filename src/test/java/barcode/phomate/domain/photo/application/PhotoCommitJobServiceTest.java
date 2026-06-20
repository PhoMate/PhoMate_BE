package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.photo.domain.entity.PhotoCommitJob;
import barcode.phomate.domain.photo.domain.entity.PhotoCommitJobStatus;
import barcode.phomate.domain.photo.domain.repository.PhotoCommitJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PhotoCommitJobServiceTest {

    @Mock PhotoCommitJobRepository repository;
    @Mock PhotoCommitJobProcessor  processor;

    @InjectMocks PhotoCommitJobService service;

    private PhotoCommitJob pendingJob;

    @BeforeEach
    void setUp() {
        pendingJob = PhotoCommitJob.builder()
                .batchId("batch-1")
                .photoId(10L)
                .memberId(1L)
                .originalKey("photos/10/o_abc.jpg")
                .etag("etag-val")
                .build();
    }

    // ── claimPending ──────────────────────────────────────────────────────────

    @Test
    void claimPending_WhenPendingJobsExist_MarksThemProcessing() {
        // Given
        given(repository.findPendingForUpdate(5)).willReturn(List.of(pendingJob));

        // When
        List<PhotoCommitJob> claimed = service.claimPending(5);

        // Then
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getStatus()).isEqualTo(PhotoCommitJobStatus.PROCESSING);
    }

    @Test
    void claimPending_WhenNoPendingJobs_ReturnsEmptyList() {
        // Given
        given(repository.findPendingForUpdate(anyInt())).willReturn(List.of());

        // When
        List<PhotoCommitJob> claimed = service.claimPending(10);

        // Then
        assertThat(claimed).isEmpty();
        then(processor).shouldHaveNoInteractions();
    }

    // ── processOne ────────────────────────────────────────────────────────────

    @Test
    void processOne_WhenProcessingSucceeds_MarksJobDone() {
        // Given
        pendingJob.markProcessing();
        given(repository.findById(1L)).willReturn(Optional.of(pendingJob));
        willDoNothing().given(processor).process(pendingJob);

        // When
        service.processOne(1L);

        // Then
        assertThat(pendingJob.getStatus()).isEqualTo(PhotoCommitJobStatus.DONE);
        assertThat(pendingJob.getLastError()).isNull();
    }

    @Test
    void processOne_WhenProcessingFailsAndRetriesRemaining_SchedulesRetry() {
        // Given
        pendingJob.markProcessing();
        given(repository.findById(1L)).willReturn(Optional.of(pendingJob));
        willThrow(new RuntimeException("S3 error")).given(processor).process(pendingJob);

        // When
        service.processOne(1L);

        // Then  — retryCount=1 < MAX_RETRIES=3 → still PENDING
        assertThat(pendingJob.getStatus()).isEqualTo(PhotoCommitJobStatus.PENDING);
        assertThat(pendingJob.getRetryCount()).isEqualTo(1);
        assertThat(pendingJob.getLastError()).contains("S3 error");
        assertThat(pendingJob.getNextAttemptAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void processOne_WhenProcessingFailsAndNoRetriesRemaining_MarksJobFailed() {
        // Given — exhaust retries up to the threshold
        pendingJob.markProcessing();
        pendingJob.scheduleRetry("err", PhotoCommitJobService.MAX_RETRIES, PhotoCommitJobService.BACKOFF_BASE_MS);
        // scheduleRetry increments retryCount to 1 → still PENDING with retryCount=1
        // force retryCount to MAX_RETRIES-1 so next failure tips over
        for (int i = pendingJob.getRetryCount(); i < PhotoCommitJobService.MAX_RETRIES - 1; i++) {
            pendingJob.scheduleRetry("err", PhotoCommitJobService.MAX_RETRIES, PhotoCommitJobService.BACKOFF_BASE_MS);
        }
        pendingJob.markProcessing();

        given(repository.findById(99L)).willReturn(Optional.of(pendingJob));
        willThrow(new RuntimeException("fatal")).given(processor).process(pendingJob);

        // When
        service.processOne(99L);

        // Then
        assertThat(pendingJob.getStatus()).isEqualTo(PhotoCommitJobStatus.FAILED);
        assertThat(pendingJob.getRetryCount()).isEqualTo(PhotoCommitJobService.MAX_RETRIES);
        assertThat(pendingJob.getLastError()).contains("fatal");
    }

    // ── sweepStuck ────────────────────────────────────────────────────────────

    @Test
    void sweepStuck_WhenStuckJobsExist_ResetsThemToPending() {
        // Given
        pendingJob.markProcessing();
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        given(repository.findStuckProcessingForUpdate(eq(threshold), anyInt()))
                .willReturn(List.of(pendingJob));

        // When
        int recovered = service.sweepStuck(threshold, 50);

        // Then
        assertThat(recovered).isEqualTo(1);
        assertThat(pendingJob.getStatus()).isEqualTo(PhotoCommitJobStatus.PENDING);
    }

    @Test
    void sweepStuck_WhenNoStuckJobs_ReturnsZero() {
        // Given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        given(repository.findStuckProcessingForUpdate(any(), anyInt())).willReturn(List.of());

        // When
        int recovered = service.sweepStuck(threshold, 50);

        // Then
        assertThat(recovered).isZero();
    }
}
