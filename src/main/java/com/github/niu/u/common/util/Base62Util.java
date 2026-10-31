package com.github.niu.u.common.util;

/**
 * Base62 编码工具。
 * <p>
 * 用 0-9a-zA-Z 共 62 个字符把自增 ID 编码成短码。相比原来"对 URL 做 MD5 再截取"：
 * 1) 结果长度可控、可逆；
 * 2) 配合 Redis 自增序列不会产生哈希碰撞；
 * 3) 短码更短（6 位即可表示 568 亿个链接）。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/17
 **/
public final class Base62Util {

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private static final int BASE = ALPHABET.length;

    private Base62Util() {
    }

    /**
     * 把非负整数编码为 Base62 字符串。
     *
     * @param num 非负整数
     * @return Base62 字符串
     */
    public static String encode(long num) {
        if (num < 0) {
            throw new IllegalArgumentException("num must be >= 0");
        }
        if (num == 0) {
            return String.valueOf(ALPHABET[0]);
        }
        StringBuilder sb = new StringBuilder(11);
        while (num > 0) {
            sb.append(ALPHABET[(int) (num % BASE)]);
            num /= BASE;
        }
        return sb.reverse().toString();
    }

    /**
     * 把 Base62 字符串解码为非负整数。
     *
     * @param str Base62 字符串
     * @return 解码后的整数
     */
    public static long decode(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("str must not be empty");
        }
        long num = 0L;
        for (int i = 0; i < str.length(); i++) {
            int idx = indexOf(str.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("illegal base62 char: " + str.charAt(i));
            }
            num = num * BASE + idx;
        }
        return num;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
