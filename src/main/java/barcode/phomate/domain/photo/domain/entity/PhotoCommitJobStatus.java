package barcode.phomate.domain.photo.domain.entity;

public enum PhotoCommitJobStatus {
    /** Waiting to be picked up by the poller. */
    PENDING,
    /** Claimed by a poller; S3 processing is in progress. */
    PROCESSING,
    /** Thumbnail/preview upload and embedding trigger completed successfully. */
    DONE,
    /** Permanently failed after exhausting all retry attempts. */
    FAILED
}
