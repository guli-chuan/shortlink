package com.github.niu.u.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Base62 工具类测试
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/17
 **/
class Base62UtilTest {

    @Test
    void encodeDecodeShouldBeReversible() {
        long[] samples = {0L, 1L, 61L, 62L, 63L, 916132832L, 916132833L, Long.MAX_VALUE};
        for (long n : samples) {
            String encoded = Base62Util.encode(n);
            assertEquals(n, Base62Util.decode(encoded), "编号 " + n + " 编解码不一致");
        }
    }

    @Test
    void offsetShouldProduceAtLeastSixChars() {
        // 62^5 = 916132832 是 6 位 Base62 的起点
        assertEquals(6, Base62Util.encode(916132832L).length());
    }

    @Test
    void negativeShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> Base62Util.encode(-1));
    }
}
