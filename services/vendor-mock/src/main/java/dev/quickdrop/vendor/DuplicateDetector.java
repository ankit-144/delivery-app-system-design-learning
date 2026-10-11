package dev.quickdrop.vendor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the last million message ids. A repeated id means the caller sent the same SMS twice —
 * the customer got two texts and we paid twice (scenario K3, at-least-once delivery).
 */
class DuplicateDetector {

    private static final int CAPACITY = 1_000_000;

    private final Map<String, Boolean> seen = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CAPACITY;
        }
    };

    synchronized boolean isDuplicate(String messageId) {
        return seen.put(messageId, Boolean.TRUE) != null;
    }

    synchronized void clear() {
        seen.clear();
    }

    /** Message ids received so far that start with {@code prefix} (for reconciliation, scenario 6.6). */
    synchronized java.util.List<String> idsStartingWith(String prefix) {
        return seen.keySet().stream().filter(id -> id.startsWith(prefix)).toList();
    }
}
