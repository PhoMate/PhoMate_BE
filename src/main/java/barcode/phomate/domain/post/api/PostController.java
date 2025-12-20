package barcode.phomate.domain.post.api;

import barcode.phomate.domain.post.application.PostService;
import barcode.phomate.domain.post.dto.PostCreateRequestDTO;
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
}
