package barcode.phomate.domain.chat.domain.repository;

import barcode.phomate.domain.chat.domain.entity.ChatMessage;
import barcode.phomate.domain.chat.domain.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByChatSessionOrderByCreatedAtAsc(ChatSession session);
}
