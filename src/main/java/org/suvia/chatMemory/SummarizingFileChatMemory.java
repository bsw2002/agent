package org.suvia.chatMemory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Development-only file backend. Production should use the JDBC backend. */
public class SummarizingFileChatMemory extends AbstractSummarizingChatMemory {

    private final Path baseDirectory;

    public SummarizingFileChatMemory(
            String directory,
            int maxRecentMessages,
            int maxRecentTokens,
            int summarizeBatchSize,
            int minRecentMessages,
            int maxSummaryCharacters,
            ConversationSummarizer summarizer
    ) {
        super(
                summarizer,
                maxRecentMessages,
                maxRecentTokens,
                summarizeBatchSize,
                minRecentMessages,
                maxSummaryCharacters
        );
        try {
            Path configured = Path.of(directory);
            this.baseDirectory = (configured.isAbsolute()
                    ? configured
                    : Path.of(System.getProperty("user.dir")).resolve(configured))
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(this.baseDirectory);
        } catch (IOException e) {
            throw new MemoryPersistenceException("Unable to create conversation-memory directory", e);
        }
    }

    @Override
    protected ConversationState load(String conversationId) {
        Path path = file(conversationId);
        if (!Files.exists(path)) {
            return new ConversationState();
        }
        try {
            return deserializeState(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new MemoryPersistenceException("Unable to read conversation memory", e);
        }
    }

    @Override
    protected boolean persist(String conversationId, long expectedVersion, ConversationState state) {
        Path target = file(conversationId);
        ConversationState existing = Files.exists(target) ? load(conversationId) : new ConversationState();
        if (existing.version != expectedVersion) {
            return false;
        }

        Path temporary = baseDirectory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, serializeState(state));
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The original failure is more useful to the caller.
            }
            throw new MemoryPersistenceException("Unable to save conversation memory", e);
        }
    }

    @Override
    protected void remove(String conversationId) {
        try {
            Files.deleteIfExists(file(conversationId));
        } catch (IOException e) {
            throw new MemoryPersistenceException("Unable to delete conversation memory", e);
        }
    }

    private Path file(String conversationId) {
        validateConversationId(conversationId);
        Path path = baseDirectory.resolve(sha256(conversationId) + ".kryo").normalize();
        if (!path.startsWith(baseDirectory)) {
            throw new IllegalArgumentException("Conversation-memory path escapes configured directory");
        }
        return path;
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
