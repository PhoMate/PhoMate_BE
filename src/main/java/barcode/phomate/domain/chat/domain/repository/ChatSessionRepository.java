package barcode.phomate.domain.chat.domain.repository;

import barcode.phomate.domain.chat.domain.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
}
