package barcode.phomate.domain.post.application;

import barcode.phomate.domain.post.dto.PostCreateRequestDTO;
import org.springframework.web.multipart.MultipartFile;

public interface PostService {
    Long createPost(Long memberId, PostCreateRequestDTO request, MultipartFile image);
}
