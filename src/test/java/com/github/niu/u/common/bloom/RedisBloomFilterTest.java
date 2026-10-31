package com.github.niu.u.common.bloom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布隆过滤器的哈希与误判率测试。
 * <p>
 * 这些用例<b>不连 Redis</b>：位数组在测试里用 {@link BitSet} 模拟，
 * 被测的就是"算下标"和"置位/查位"这两件事。真正依赖 Redis 的部分（SETBIT 命令、
 * Lua 脚本、pipeline 预热）只能在端到端验收里验，单测mock 掉没有意义。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/29
 **/
class RedisBloomFilterTest {

    private static final long BIT_SIZE = 10_000_000L;
    private static final int HASH_COUNT = 3;

    @Test
    @DisplayName("同一个短码多次计算结果完全一致（哈希不能带随机性）")
    void positionsShouldBeDeterministic() {
        long[] first = RedisBloomFilter.positions("10000G", BIT_SIZE, HASH_COUNT);
        long[] second = RedisBloomFilter.positions("10000G", BIT_SIZE, HASH_COUNT);
        assertEquals(HASH_COUNT, first.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], "第 " + i + " 个下标必须稳定，否则写进去也查不到");
        }
    }

    @Test
    @DisplayName("下标必须落在 [0, bitSize) 内，且三个位置互不相同")
    void positionsShouldBeInRangeAndDistinct() {
        for (int i = 0; i < 500; i++) {
            String code = "code" + i;
            long[] offsets = RedisBloomFilter.positions(code, BIT_SIZE, HASH_COUNT);
            Set<Long> distinct = new HashSet<>();
            for (long offset : offsets) {
                // 负数下标会让 SETBIT 直接报错，Math.floorMod 就是防这个的
                assertTrue(offset >= 0, "下标不能为负: " + offset);
                assertTrue(offset < BIT_SIZE, "下标不能越界: " + offset);
                distinct.add(offset);
            }
            assertEquals(HASH_COUNT, distinct.size(), "三个位置重复会降低有效位密度: " + code);
        }
    }

    @Test
    @DisplayName("m/n=10、k=3 时误判率应接近理论值 1%（实测 < 3%）")
    void falsePositiveRateShouldMatchTheory() {
        int elementCount = 10_000;
        long bitSize = (long) elementCount * 10;
        BitSet bitmap = new BitSet((int) bitSize);

        // 写入 1 万个"真实存在"的短码
        for (int i = 0; i < elementCount; i++) {
            for (long offset : RedisBloomFilter.positions("real-" + i, bitSize, HASH_COUNT)) {
                bitmap.set((int) offset);
            }
        }

        // 再用 1 万个"不存在"的短码去查，统计误判
        int falsePositive = 0;
        for (int i = 0; i < elementCount; i++) {
            boolean maybe = true;
            for (long offset : RedisBloomFilter.positions("fake-" + i, bitSize, HASH_COUNT)) {
                if (!bitmap.get((int) offset)) {
                    maybe = false;
                    break;
                }
            }
            if (maybe) {
                falsePositive++;
            }
        }

        double rate = falsePositive / (double) elementCount;
        // 理论值 (1 - e^(-3/10))^3 ≈ 0.89%，留足余量避免偶发抖动导致 CI 挂
        assertTrue(rate < 0.03, "误判率应低于 3%，实际=" + rate + "（" + falsePositive + "/" + elementCount + "）");
    }

    @Test
    @DisplayName("已写入的短码一定查得到（布隆过滤器不允许假阴性）")
    void insertedCodeShouldAlwaysBeFound() {
        long bitSize = 100_000L;
        BitSet bitmap = new BitSet((int) bitSize);
        String code = "100004";
        for (long offset : RedisBloomFilter.positions(code, bitSize, HASH_COUNT)) {
            bitmap.set((int) offset);
        }

        boolean maybe = true;
        for (long offset : RedisBloomFilter.positions(code, bitSize, HASH_COUNT)) {
            if (!bitmap.get((int) offset)) {
                maybe = false;
                break;
            }
        }
        assertTrue(maybe, "写进去的短码必须查得到，否则有效链接会被自己的过滤器拦成 404");
    }

    @Test
    @DisplayName("极端输入不抛异常：空串、单字符、超长短码")
    void shouldHandleEdgeInputs() {
        String[] inputs = {"", "a", repeat('x', 4096)};
        for (String input : inputs) {
            long[] offsets = RedisBloomFilter.positions(input, BIT_SIZE, HASH_COUNT);
            assertEquals(HASH_COUNT, offsets.length);
            for (long offset : offsets) {
                assertTrue(offset >= 0 && offset < BIT_SIZE);
            }
        }
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
