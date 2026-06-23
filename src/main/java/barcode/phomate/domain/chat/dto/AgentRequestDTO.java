package barcode.phomate.domain.chat.dto;

import lombok.Getter;

@Getter
public class AgentRequestDTO {

    // 기존 채팅 세션 id
    private Long chatSessionId;

    // 편집 챗봇에서 넘어올 때만 사용 (검색/폴더는 null)
    private Long editSessionId;

    // 사용자가 보낸 자연어
    private String userText;
}
