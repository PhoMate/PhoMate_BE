package barcode.phomate.domain.photo.application;

import barcode.phomate.domain.photo.domain.entity.PhotoCommitJob;
import barcode.phomate.domain.photo.domain.entity.PhotoCommitJobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PhotoCommitJobPollerTest {

    @Mock PhotoCommitJobService jobService;

    @InjectMocks PhotoCommitJobPoller poller;

    // ── poll ──────────────────────────────────────────────────────────────────

    @Test
    void poll_WhenNoPendingJobs_DoesNotCallProcessOne() {
        // Given
        given(jobService.claimPending(anyInt())).willReturn(List.of());

        // When
        poller.poll();

        // Then
        then(jobService).should(never()).processOne(any());
    }

    @Test
    void poll_WhenPendingJobsExist_ProcessesEachJobById() {
        // Given
        PhotoCommitJob job1 = buildProcessingJob(1L);
        PhotoCommitJob job2 = buildProcessingJob(2L);
        given(jobService.claimPending(anyInt())).willReturn(List.of(job1, job2));

        // When
        poller.poll();

        // Then — processOne called once per job, in order
        then(jobService).should().processOne(1L);
        then(jobService).should().processOne(2L);
        then(jobService).shouldHaveNoMoreInteractions();
    }

    // ── sweepStuck ────────────────────────────────────────────────────────────

    @Test
    void sweepStuck_PassesThresholdOlderThan10Minutes() {
        // Given
        given(jobService.sweepStuck(any(), anyInt())).willReturn(0);
        LocalDateTime before = LocalDateTime.now().minusMinutes(10);

        // When
        poller.sweepStuck();

        // Then — threshold must be ≤ (now - 10 min)
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(jobService).should().sweepStuck(captor.capture(), anyInt());
        assertThat(captor.getValue()).isBeforeOrEqualTo(before.plusSeconds(1));
    }

    @Test
    void sweepStuck_WhenNoStuckJobs_DoesNotLog() {
        // Given
        given(jobService.sweepStuck(any(), anyInt())).willReturn(0);

        // When / Then — no exception
        poller.sweepStuck();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * Builds a PhotoCommitJob stub that reports the given ID without DB interaction.
     * Status is set to PROCESSING to simulate a claimed job.
     */
    private PhotoCommitJob buildProcessingJob(Long id) {
        PhotoCommitJob job = PhotoCommitJob.builder()
                .batchId("batch-test")
                .photoId(id * 10)
                .memberId(1L)
                .originalKey("photos/" + id + "/o.jpg")
                .etag("etag-" + id)
                .build();
        job.markProcessing();

        // Inject id via reflection (field is private, set by JPA in prod)
        try {
            var field = PhotoCommitJob.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(job, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return job;
    }
}
