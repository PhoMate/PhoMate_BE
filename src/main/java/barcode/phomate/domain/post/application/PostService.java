package barcode.phomate.domain.post.application;

import barcode.phomate.domain.post.dto.PostCreateRequestDTO;
import barcode.phomate.domain.post.dto.PostFeedResponseDTO;
import barcode.phomate.domain.post.dto.PostSortType;
import org.springframework.web.multipart.MultipartFile;

public interface PostService {
    Long createPost(Long memberId, PostCreateRequestDTO request, MultipartFile image);
    PostFeedResponseDTO getFeed(PostSortType sort, String cursorTime, Long cursorLike, Long cursorId, int size, Long memberId);
    Long updatePost(Long memberId, Long postId, PostCreateRequestDTO request, MultipartFile image);
    void deletePost(Long memberId, Long postId);
}
