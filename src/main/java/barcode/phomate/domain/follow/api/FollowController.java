package barcode.phomate.domain.follow.api;

import barcode.phomate.domain.follow.application.FollowService;
import barcode.phomate.domain.follow.dto.FollowToggleResponseDTO;
import barcode.phomate.domain.follow.dto.FolloweeResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
            @RequestParam Long followerId,
            @RequestParam Long followeeId
    ) {
        return ResponseEntity.ok(followService.toggleFollow(followerId, followeeId));
    }

    // 내가 팔로우한 사람 목록
    @GetMapping("/me")
    @Operation(summary = "팔로우 목록 조회", description = "팔로우 목록 조회 API")
    public ResponseEntity<List<FolloweeResponseDTO>> myFollowees(@RequestParam Long followerId) {
        return ResponseEntity.ok(followService.getMyFollowees(followerId));
    }
}
