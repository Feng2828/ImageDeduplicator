package com.sopro.imagededup.scan

import com.sopro.imagededup.model.DuplicateGroup
import com.sopro.imagededup.model.ImageRecord

/**
 * 多重索引哈希分组。
 *
 * 64 位 pHash 切成 4 段，每段 16 位。若两张图的汉明距离 <= t，
 * 按抽屉原理至少有一段差异 <= t/4；因此只要在该段的取值上建桶，
 * 就能在 O(N * 桶查询) 内找出所有候选对，避免 O(N^2) 的两两比较。
 * 几万张图片下候选集依然很小，且全程只持有 Long 和索引，不持有位图。
 */
object SimilarityGrouper {

    fun group(records: List<ImageRecord>, requestedThreshold: Int): List<DuplicateGroup> {
        val threshold = requestedThreshold.coerceIn(0, 7)
        val valid = records.filter { it.width > 0 }
        if (valid.size < 2) return emptyList()

        val parent = IntArray(valid.size) { it }
        val rankSize = IntArray(valid.size) { 1 }
        val buckets = Array(4) { HashMap<Int, MutableList<Int>>() }
        val flipsPerChunk = threshold / 4

        for (i in valid.indices) {
            val hash = valid[i].phash
            for (chunkIndex in 0..3) {
                val chunk = ((hash shr (chunkIndex * 16)) and 0xFFFF).toInt()
                val variants = variantsWithin(chunk, flipsPerChunk)
                for (key in variants) {
                    val bucket = buckets[chunkIndex].getOrPut(key) { ArrayList() }
                    for (j in bucket) {
                        if (i != j &&
                            java.lang.Long.bitCount(hash xor valid[j].phash) <= threshold
                        ) {
                            union(parent, rankSize, i, j)
                        }
                    }
                    bucket.add(i)
                }
            }
        }

        // 按并查集根节点归并，只保留 >= 2 张的组。
        val byRoot = HashMap<Int, MutableList<Int>>()
        for (i in valid.indices) {
            val root = find(parent, i)
            byRoot.getOrPut(root) { ArrayList() }.add(i)
        }

        return byRoot.values
            .filter { it.size >= 2 }
            .sortedByDescending { it.size }
            .mapIndexed { groupIndex, memberIndexes ->
                // 仅作为“建议原图”排序：分辨率高、文件大的排前面。
                // 最终保留哪一张完全由用户在界面上指定。
                val ordered = memberIndexes
                    .map { valid[it] }
                    .sortedWith(
                        compareByDescending<ImageRecord> { it.width.toLong() * it.height.toLong() }
                            .thenByDescending { it.sizeBytes }
                            .thenBy { it.relativePath }
                    )
                DuplicateGroup(
                    id = groupIndex,
                    members = ordered.map { it.id },
                    keepId = ordered.first().id
                )
            }
    }

    private fun find(parent: IntArray, x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var current = x
        while (parent[current] != root) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }

    private fun union(parent: IntArray, size: IntArray, a: Int, b: Int) {
        var rootA = find(parent, a)
        var rootB = find(parent, b)
        if (rootA == rootB) return
        if (size[rootA] < size[rootB]) {
            val temp = rootA
            rootA = rootB
            rootB = temp
        }
        parent[rootB] = rootA
        size[rootA] += size[rootB]
    }

    /** 生成与 16 位 chunk 汉明距离 <= maxFlips 的所有取值。 */
    private fun variantsWithin(chunk: Int, maxFlips: Int): IntArray {
        if (maxFlips <= 0) return intArrayOf(chunk)
        val result = ArrayList<Int>()
        fun walk(value: Int, remaining: Int, startBit: Int) {
            result.add(value)
            if (remaining == 0) return
            for (bit in startBit until 16) {
                walk(value xor (1 shl bit), remaining - 1, bit + 1)
            }
        }
        walk(chunk, maxFlips, 0)
        return result.toIntArray()
    }
}
