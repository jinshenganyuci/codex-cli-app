package com.nanci.codexremote.core

import java.util.ArrayDeque

/** Snapshot of the exact deletion scope shown to the user, with children before their parents. */
data class ThreadDeletionPlan(
    val rootId: String,
    val threads: List<ThreadSummary>,
    internal val generation: Long,
)

/** Relations are read-only metadata. This helper never infers dependencies from titles or history. */
internal object ThreadDeletionGraph {
    private const val MAX_THREADS = 10_000
    private val relationFields = listOf("forkedFromId", "parentThreadId", "attachedToThreadId")

    fun dependencyIds(thread: ThreadSummary): Set<String> = buildSet {
        relationFields.forEach { field ->
            if (thread.raw.has(field) && !thread.raw.isNull(field)) {
                val value = thread.raw.opt(field)
                if (value !is String || !validId(value))
                    throw CodexException("服务器返回的聊天关联信息无效，已停止删除。")
                add(value)
            }
        }
    }

    fun affectedIds(rootId: String, threads: Map<String, ThreadSummary>): Set<String> {
        validate(rootId, threads)
        val children = childrenByParent(threads)
        val found = linkedSetOf(rootId)
        val pending = ArrayDeque<String>().apply { add(rootId) }
        while (pending.isNotEmpty()) {
            children[pending.removeFirst()].orEmpty().forEach { child ->
                if (found.add(child)) {
                    if (found.size > MAX_THREADS) tooMany()
                    pending.addLast(child)
                }
            }
        }
        return found
    }

    /** Iterative reverse topological order, bounded even for a long fork chain. */
    fun ordered(rootId: String, threads: Map<String, ThreadSummary>): List<ThreadSummary> {
        val affected = affectedIds(rootId, threads)
        val children = childrenByParent(threads)
        val remainingChildren = affected.associateWith { id ->
            children[id].orEmpty().count { it in affected }
        }.toMutableMap()
        val pending = ArrayDeque<String>()
        affected.filter { remainingChildren[it] == 0 }.sorted().forEach(pending::addLast)
        val ordered = ArrayList<ThreadSummary>(affected.size)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            val thread = threads.getValue(id)
            ordered += thread
            dependencyIds(thread).filter { it in affected }.sorted().forEach { parent ->
                val count = remainingChildren.getValue(parent) - 1
                remainingChildren[parent] = count
                if (count == 0) pending.addLast(parent)
            }
        }
        if (ordered.size != affected.size || ordered.lastOrNull()?.id != rootId)
            throw CodexException("聊天之间存在循环引用，无法确定安全删除顺序，已停止删除。")
        return ordered
    }

    private fun childrenByParent(threads: Map<String, ThreadSummary>): Map<String, Set<String>> {
        val children = mutableMapOf<String, MutableSet<String>>()
        threads.values.forEach { thread ->
            dependencyIds(thread).forEach { parent -> children.getOrPut(parent) { linkedSetOf() }.add(thread.id) }
        }
        return children
    }

    private fun validate(rootId: String, threads: Map<String, ThreadSummary>) {
        if (!validId(rootId) || rootId !in threads)
            throw CodexException("聊天已不存在，请刷新后重新选择。")
        if (threads.size > MAX_THREADS) tooMany()
        if (threads.any { (id, thread) -> id != thread.id || !validId(id) })
            throw CodexException("服务器返回的聊天列表无效，已停止删除。")
    }

    private fun validId(id: String) = id.isNotBlank() && id.length <= 512 &&
        id.none { it.code < 32 || it.code == 127 }

    private fun tooMany(): Nothing = throw CodexException("关联聊天过多，无法安全确认删除范围，请先分批处理。")
}
