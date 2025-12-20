package barcode.phomate.domain.post.api;

import barcode.phomate.domain.post.application.PostService;
import barcode.phomate.domain.post.dto.PostCreateRequestDTO;
import barcode.phomate.domain.post.dto.PostFeedResponseDTO;
import barcode.phomate.domain.post.dto.PostSortType;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(summary = "게시글 등록", description = "게시글 등록 API")
    public ResponseEntity<Void> createPost(
            @RequestParam("memberId") Long memberId,
            @ModelAttribute PostCreateRequestDTO request,
            @RequestPart(value = "image") MultipartFile image
    ) {
        Long postId = postService.createPost(memberId, request, image);
        return ResponseEntity
                .created(URI.create("/posts/" + postId))
                .build();
    }

    @GetMapping
    @Operation(summary = "게시글 조회", description = "게시글 조회 API")
    public ResponseEntity<PostFeedResponseDTO> getPosts(
            @RequestParam(defaultValue = "LATEST") PostSortType sort,
            @RequestParam(required = false) String cursorTime,
            @RequestParam(required = false) Long cursorLike,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) Long memberId
    ) {
        PostFeedResponseDTO response = postService.getFeed(sort, cursorTime, cursorLike, cursorId, size, memberId);
        return ResponseEntity.ok(response);
    }
}
