package barcode.phomate.domain.photo.domain.repository;

import barcode.phomate.domain.photo.domain.entity.PhotoCommitJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PhotoCommitJobRepository extends JpaRepository<PhotoCommitJob, Long> {

    /**
     * Selects up to {@code limit} PENDING jobs whose {@code next_attempt_at} is
     * due, locks them with {@code FOR UPDATE SKIP LOCKED} to prevent concurrent
     * pollers from claiming the same rows.
     *
     * <p><strong>Must be called within an active transaction.</strong>
     *
     * @param limit maximum number of rows to return; must be &gt; 0
     * @return locked jobs in ascending {@code created_at} order
     */
    @Query(value = """
            SELECT * FROM photo_commit_job
            WHERE  status = 'PENDING'
              AND  next_attempt_at <= NOW()
            ORDER  BY created_at ASC
            LIMIT  :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PhotoCommitJob> findPendingForUpdate(@Param("limit") int limit);

    /**
     * Selects up to {@code limit} PROCESSING jobs whose {@code updated_at} is
     * older than {@code threshold}, locked with {@code FOR UPDATE SKIP LOCKED}.
     * These are jobs whose worker died before completing.
     *
     * <p><strong>Must be called within an active transaction.</strong>
     *
     * @param threshold  cut-off time; jobs last updated before this are considered stuck
     * @param limit      maximum number of rows to return; must be &gt; 0
     * @return locked stuck jobs in ascending {@code updated_at} order
     */
    @Query(value = """
            SELECT * FROM photo_commit_job
            WHERE  status = 'PROCESSING'
              AND  updated_at < :threshold
            ORDER  BY updated_at ASC
            LIMIT  :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PhotoCommitJob> findStuckProcessingForUpdate(
            @Param("threshold") LocalDateTime threshold,
            @Param("limit") int limit);
}
