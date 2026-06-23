package barcode.phomate.domain.chat.application;

import barcode.phomate.domain.chat.dto.AgentRequestDTO;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

public interface AgentService {

    // 편집 / 검색 / 폴더 생성 다 여기로
    Flux<ServerSentEvent<String>> run(Long memberId, AgentRequestDTO request);
}
