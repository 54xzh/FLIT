package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockSnapshotTest {
    @Test
    fun `same content produces equal snapshots across parses`() {
        val text = "# 标题\n\n第一段内容。\n\n- 列表项一\n- 列表项二\n\n```kotlin\nval x = 1\n```"
        val first = parseMarkdownForTest(text).snapshots
        val second = parseMarkdownForTest(text).snapshots

        // 两次解析产生全新 AST 实例，但快照按块源文本比较应全部相等
        assertEquals(first, second)
    }

    @Test
    fun `appending inside last paragraph keeps prefix snapshots equal`() {
        val base = "# 标题\n\n第一段完整内容。\n\n第二段正在生成"
        val grown = base + "，继续输出更多文字"
        val baseSnapshots = parseMarkdownForTest(base).snapshots
        val grownSnapshots = parseMarkdownForTest(grown).snapshots

        assertEquals(baseSnapshots.size, grownSnapshots.size)
        // 除正在增长的最后一块外，其余块快照保持相等（对应组合作用域可跳过重组）
        for (i in 0 until baseSnapshots.size - 1) {
            assertEquals("block $i should stay equal", baseSnapshots[i], grownSnapshots[i])
        }
        assertNotEquals(
            baseSnapshots.last(),
            grownSnapshots.last(),
        )
    }

    @Test
    fun `paragraph that stops being last block becomes unequal`() {
        // 段落渲染依赖"后面是否还有节点"决定底部间距：
        // 从"最后一块"变成"非最后一块"时快照必须失效重建
        val single = parseMarkdownForTest("唯一的一段").snapshots
        val followed = parseMarkdownForTest("唯一的一段\n\n新的段落").snapshots

        assertEquals(1, single.size)
        assertTrue(followed.size > 1)
        assertEquals(single[0].blockText, followed[0].blockText)
        assertNotEquals(single[0], followed[0])
    }

    @Test
    fun `snapshots tile the whole preprocessed content`() {
        val text = "开头一段。\n\n> 引用内容\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\n结尾一段。"
        val data = parseMarkdownForTest(text)

        assertEquals(
            data.preprocessed,
            data.snapshots.joinToString(separator = "") { it.blockText },
        )
    }

    @Test
    fun `growing unclosed code fence only changes the fence block`() {
        val base = "说明文字。\n\n```kotlin\nval a = 1\n"
        val grown = base + "val b = 2\n"
        val baseSnapshots = parseMarkdownForTest(base).snapshots
        val grownSnapshots = parseMarkdownForTest(grown).snapshots

        // 前面的普通段落与空行保持相等
        assertEquals(baseSnapshots[0], grownSnapshots[0])
        assertNotEquals(baseSnapshots.last(), grownSnapshots.last())
    }

    @Test
    fun `snapshot nodes are rebased to block-local offsets`() {
        val data = parseMarkdownForTest("# 标题\n\n第一段。\n\n- 列表\n\n```\ncode\n```")

        // 每个块的子树偏移都以块起点为 0，渲染时以 blockText 为源文本自洽
        data.snapshots.forEach { snapshot ->
            assertEquals(0, snapshot.node.startOffset)
            assertEquals(snapshot.blockText.length, snapshot.node.endOffset)
        }
    }

    @Test
    fun `rebased node keeps sibling semantics for paragraph bottom padding`() {
        val snapshots = parseMarkdownForTest("第一段。\n\n最后一段").snapshots

        // 非最后块：合成父节点提供"后面还有内容"的兄弟关系；最后块保持无后继
        val first = snapshots.first()
        val last = snapshots.last()
        assertTrue(first.hasNextSibling)
        assertTrue(first.node.parent != null)
        assertTrue(
            "synthetic parent should expose a following sibling",
            first.node.parent!!.children.indexOf(first.node) < first.node.parent!!.children.lastIndex,
        )
        assertTrue(!last.hasNextSibling)
        assertEquals(null, last.node.parent)
        // 合成父节点被快照显式持有，parent 链不依赖构造副作用与 GC 时序
        assertTrue(first.syntheticParent === first.node.parent)
        assertEquals(null, last.syntheticParent)
    }

    @Test
    fun `editing middle content invalidates the edited block`() {
        val original = "第一段。\n\n第二段旧内容。\n\n第三段。"
        val edited = "第一段。\n\n第二段新内容！\n\n第三段。"
        val originalSnapshots = parseMarkdownForTest(original).snapshots
        val editedSnapshots = parseMarkdownForTest(edited).snapshots

        assertEquals(originalSnapshots.size, editedSnapshots.size)
        assertEquals(originalSnapshots[0], editedSnapshots[0])
        val changedCount = originalSnapshots.indices.count { originalSnapshots[it] != editedSnapshots[it] }
        assertTrue("edited paragraph should invalidate its snapshot", changedCount >= 1)
        assertEquals(originalSnapshots.last(), editedSnapshots.last())
    }
}
