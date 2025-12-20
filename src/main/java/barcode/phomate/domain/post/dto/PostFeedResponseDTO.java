package barcode.phomate.domain.post.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PostFeedResponseDTO {

    private List<PostResponseDTO> items;
    private Cursor nextCursor;
    private boolean hasNext;

    public static PostFeedResponseDTO of(List<PostResponseDTO> items, Cursor nextCursor, boolean hasNext) {
        PostFeedResponseDTO res = new PostFeedResponseDTO();
        res.setItems(items);
        res.setNextCursor(nextCursor);
        res.setHasNext(hasNext);
        return res;
    }

    public static PostFeedResponseDTO empty() {
        return PostFeedResponseDTO.of(List.of(), null, false);
    }

    @Getter
    @Setter
    public static class Cursor {
        private PostSortType sort;

        private String cursorTime;
        private Long cursorId;

        private Long cursorLike;

        public static Cursor latest(String cursorTime, Long cursorId) {
            Cursor c = new Cursor();
            c.sort = PostSortType.LATEST;
            c.cursorTime = cursorTime;
            c.cursorId = cursorId;
            return c;
        }

        public static Cursor like(Long cursorLike, Long cursorId) {
            Cursor c = new Cursor();
            c.sort = PostSortType.LIKE;
            c.cursorLike = cursorLike;
            c.cursorId = cursorId;
            return c;
        }
    }
}

