package barcode.phomate.domain.likes.application;

import barcode.phomate.domain.likes.dto.LikesToggleResponse;

public interface LikesService {
    LikesToggleResponse toggleLikes(Long memberId, Long postId);
}
