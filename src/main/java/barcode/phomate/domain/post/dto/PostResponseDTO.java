package barcode.phomate.domain.post.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PostResponseDTO {
    private Long postId;
    private String title;
    private String thumbnailUrl;
    private long likeCount;
    private boolean likedByMe;

    public static PostResponseDTO of(Long postId, String title, String thumbnailUrl, long likeCount, boolean likedByMe) {
        PostResponseDTO dto = new PostResponseDTO();
        dto.setPostId(postId);
        dto.setTitle(title);
        dto.setThumbnailUrl(thumbnailUrl);
        dto.setLikeCount(likeCount);
        dto.setLikedByMe(likedByMe);
        return dto;
    }
}
