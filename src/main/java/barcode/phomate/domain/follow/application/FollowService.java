package barcode.phomate.domain.follow.application;

import barcode.phomate.domain.follow.dto.FollowToggleResponseDTO;
import barcode.phomate.domain.follow.dto.FolloweeResponseDTO;

import java.util.List;

public interface FollowService {
    FollowToggleResponseDTO toggleFollow(Long followerId, Long followeeId);
    List<FolloweeResponseDTO> getMyFollowees(Long followerId);
}
