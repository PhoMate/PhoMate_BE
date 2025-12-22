package barcode.phomate.domain.post.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class PostDetailResponseDTO {

    private Long postId;

    private Long authorId;
    private String authorNickname;
    private String authorProfileImageUrl;

    private String title;
    private String description;
    private String originalUrl;

    private long likeCount;
    private boolean likedByMe;

    private LocalDateTime createdAt;

    public static PostDetailResponseDTO of(Long postId,
                                           Long authorId,
                                           String authorNickname,
                                           String authorProfileImageUrl,
                                           String title,
                                           String description,
                                           String originalUrl,
                                           long likeCount,
                                           boolean likedByMe,
                                           LocalDateTime createdAt) {
        PostDetailResponseDTO dto = new PostDetailResponseDTO();
        dto.setPostId(postId);
        dto.setAuthorId(authorId);
        dto.setAuthorNickname(authorNickname);
        dto.setAuthorProfileImageUrl(authorProfileImageUrl);
        dto.setTitle(title);
        dto.setDescription(description);
        dto.setOriginalUrl(originalUrl);
        dto.setLikeCount(likeCount);
        dto.setLikedByMe(likedByMe);
        dto.setCreatedAt(createdAt);
        return dto;
    }
}
