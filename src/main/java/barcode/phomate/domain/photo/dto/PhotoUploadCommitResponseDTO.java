package barcode.phomate.domain.photo.dto;

import java.util.List;

/**
 * Response returned immediately when a commit request is accepted.
 *
 * @param batchId  UUID that groups all jobs created from this request;
 *                 clients may use it to poll batch status in the future
 * @param photoIds IDs of the photos whose processing jobs have been enqueued;
 *                 order matches the order of items in the request
 */
public record PhotoUploadCommitResponseDTO(
        String batchId,
        List<Long> photoIds
) {}
