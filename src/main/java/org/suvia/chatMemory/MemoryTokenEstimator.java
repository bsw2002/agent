package org.suvia.chatMemory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Conservative, tokenizer-independent budget estimator. CJK code points count as
 * one token and other code points as one quarter token, plus message overhead.
 */
public final class MemoryTokenEstimator {

    public int estimate(List<Message> messages) {
        long tokens = 0;
        for (Message message : messages) {
            tokens += 4;
            tokens += estimate(message == null ? null : message.getText());
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }
}
