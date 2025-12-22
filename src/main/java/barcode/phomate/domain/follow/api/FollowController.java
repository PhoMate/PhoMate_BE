package barcode.phomate.domain.follow.api;

import barcode.phomate.domain.follow.application.FollowService;
import barcode.phomate.domain.follow.dto.FollowToggleResponseDTO;
import barcode.phomate.domain.follow.dto.FolloweeResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/follows")
public class FollowController {

    private final FollowService followService;

    @PostMapping("/toggle")
    @Operation(summary = "팔로우 토글", description = "팔로우 토글 API")
    public ResponseEntity<FollowToggleResponseDTO> toggleFollow(
            @AuthenticationPrincipal Long followerId,
            @RequestParam Long followeeId
    ) {
        return ResponseEntity.ok(followService.toggleFollow(followerId, followeeId));
    }

    @GetMapping("/me")
    @Operation(summary = "팔로우 목록 조회", description = "팔로우 목록 조회 API")
    public ResponseEntity<List<FolloweeResponseDTO>> myFollowees(@AuthenticationPrincipal Long followerId) {
        return ResponseEntity.ok(followService.getMyFollowees(followerId));
    }
}
