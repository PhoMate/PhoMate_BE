package barcode.phomate.domain.likes.api;

import barcode.phomate.domain.likes.application.LikesService;
import barcode.phomate.domain.likes.dto.LikesToggleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "좋아요 API", description = "좋아요 API입니다.")
public class LikesController {

    private final LikesService likesService;

    @PostMapping("/posts/{postId}/likes")
    @Operation(summary = "좋아요 토글", description = "좋아요 토글 API")
    public ResponseEntity<LikesToggleResponse> toggleLikes(
            @AuthenticationPrincipal Long memberId,
            @PathVariable("postId") Long postId) {
        LikesToggleResponse response = likesService.toggleLikes(memberId, postId);
        return ResponseEntity.ok(response);
    }
}
