package org.suvia.util;

import java.nio.charset.StandardCharsets;

public class EncodingUtils {

    /**
     * 如果字符串里有大量 '��' 这种明显乱码，就尝试按 ISO-8859-1 -> UTF-8 修正；
     * 否则原样返回。
     */
    public static String fixEncodingIfNeeded(String s) {
        if (s == null) {
            return null;
        }
        if (s.contains("��")) {
            return new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
        return s;
    }
}