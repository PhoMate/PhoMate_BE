package barcode.phomate.domain.chat.application;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingFolderCache {

    private final Map<Long, PendingFolder> store = new ConcurrentHashMap<>();

    public void put(Long chatSessionId, String folderName, List<Long> photoIds) {
        store.put(chatSessionId, new PendingFolder(folderName, photoIds));
    }

    public PendingFolder get(Long chatSessionId) {
        return store.get(chatSessionId);
    }

    public void clear(Long chatSessionId) {
        store.remove(chatSessionId);
    }

    public record PendingFolder(String folderName, List<Long> photoIds) {}
}
