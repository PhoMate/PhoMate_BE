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

    @PatchMapping(
            value = "/{postId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(summary = "게시글 수정", description = "게시글 삭제 API, title/description/image 부분 수정 지원. null/공백문자는 미수정")
    public ResponseEntity<Void> updatePost(
            @RequestParam("memberId") Long memberId,
            @PathVariable("postId") Long postId,
            @ModelAttribute PostCreateRequestDTO request,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        postService.updatePost(memberId, postId, request, image);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{postId}")
    @Operation(summary = "게시글 삭제", description = "게시글 삭제 API")
    public ResponseEntity<Void> deletePost(
            @RequestParam("memberId") Long memberId,
            @PathVariable("postId") Long postId
    ) {
        postService.deletePost(memberId, postId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/author/{authorId}")
    @Operation(summary = "특정 유저가 작성한 게시글 조회", description = "특정 유저가 작성한 게시글 조회 API")
    public ResponseEntity<PostFeedResponseDTO> getUserPostsLatest(
            @PathVariable Long authorId,
            @RequestParam(required = false) String cursorTime,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) Long viewerId
    ) {
        PostFeedResponseDTO response = postService.getUserFeedLatest(authorId, cursorTime, cursorId, size, viewerId);
        return ResponseEntity.ok(response);
    }

}
