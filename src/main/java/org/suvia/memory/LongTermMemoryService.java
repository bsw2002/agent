package org.suvia.memory;

import org.springframework.stereotype.Service;
import org.suvia.security.RequestIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class LongTermMemoryService {

    private static final int CANDIDATE_LIMIT = 200;

    private final MemoryRepository repository;
    private final MemoryWritePolicy writePolicy;

    public LongTermMemoryService(MemoryRepository repository, MemoryWritePolicy writePolicy) {
        this.repository = repository;
        this.writePolicy = writePolicy;
    }

    public MemoryRecord rememberExplicit(
            RequestIdentity identity,
            MemoryScope scope,
            String scopeKey,
            String content,
            MemorySensitivity sensitivity
    ) {
        validateScope(scope, scopeKey);
        String normalized = writePolicy.validateAndNormalize(
                content,
                MemoryKind.SEMANTIC,
                MemorySource.USER_EXPLICIT,
                1.0
        );
        Instant now = Instant.now();
        MemoryRecord record = new MemoryRecord(
                UUID.randomUUID(),
                identity.tenantId(),
                identity.userId(),
                scope,
                scope == MemoryScope.USER ? "" : scopeKey,
                MemoryKind.SEMANTIC,
                normalized,
                sha256(normalized),
                MemorySource.USER_EXPLICIT,
                1.0,
                sensitivity == null ? MemorySensitivity.INTERNAL : sensitivity,
                MemoryStatus.ACTIVE,
                now,
                null,
                now,
                now,
                1
        );
        return repository.create(record);
    }

    public List<MemoryRecord> recall(
            RequestIdentity identity,
            String conversationScopeKey,
            String query,
            int limit
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        Set<String> queryTerms = terms(query);
        return repository.findActiveCandidates(identity, conversationScopeKey, CANDIDATE_LIMIT)
                .stream()
                .sorted(Comparator
                        .comparingDouble((MemoryRecord memory) -> relevance(memory, queryTerms))
                        .reversed()
                        .thenComparing(MemoryRecord::updatedAt, Comparator.reverseOrder()))
                .limit(boundedLimit)
                .toList();
    }

    public void forget(RequestIdentity identity, UUID memoryId) {
        MemoryRecord existing = repository.findOwned(memoryId, identity)
                .filter(memory -> memory.status() == MemoryStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Memory not found"));
        if (!repository.softDeleteOwned(memoryId, identity, existing.version())) {
            throw new IllegalStateException("Memory changed concurrently; reload before deleting");
        }
    }

    private double relevance(MemoryRecord memory, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return memory.confidence();
        }
        Set<String> memoryTerms = terms(memory.content());
        long overlap = queryTerms.stream().filter(memoryTerms::contains).count();
        double lexical = (double) overlap / queryTerms.size();
        double scopeSpecificity = memory.scope() == MemoryScope.CONVERSATION ? 0.05 : 0.0;
        return lexical * 0.80 + memory.confidence() * 0.15 + scopeSpecificity;
    }

    private Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                if (isCjk(codePoint)) {
                    flushWord(word, terms);
                    terms.add(new String(Character.toChars(codePoint)));
                } else {
                    word.appendCodePoint(codePoint);
                }
            } else {
                flushWord(word, terms);
            }
        });
        flushWord(word, terms);
        return terms;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static void flushWord(StringBuilder word, Set<String> terms) {
        if (!word.isEmpty()) {
            terms.add(word.toString());
            word.setLength(0);
        }
    }

    private void validateScope(MemoryScope scope, String scopeKey) {
        if (scope == null) {
            throw new IllegalArgumentException("Memory scope is required");
        }
        if (scope == MemoryScope.CONVERSATION
                && (scopeKey == null || !scopeKey.matches("[A-Za-z0-9._:-]{1,128}"))) {
            throw new IllegalArgumentException("Conversation-scoped memory requires a valid internal scope key");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
