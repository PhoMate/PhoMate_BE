package barcode.phomate.domain.post.domain.repository;

import barcode.phomate.domain.post.domain.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, Long> {
}
