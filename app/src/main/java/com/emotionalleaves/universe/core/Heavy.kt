package com.emotionalleaves.universe.core

import java.util.concurrent.Executor

/**
 * One memory-hungry job at a time. Loading the catalogues, building the cosmic web and indexing
 * millions of points each need tens of megabytes of scratch space; run side by side at start-up
 * they filled the whole heap. Taking turns keeps the peak low. Waiting jobs go in order of
 * [run]'s priority (lower first), so what you see first loads first.
 */
object Heavy {
    private val lock = Object()
    private var busy = false
    private var seq = 0L
    private val waiting = java.util.PriorityQueue<Long>()

    fun <T> run(priority: Int = 5, block: () -> T): T {
        val ticket: Long
        synchronized(lock) {
            ticket = (priority.toLong() shl 40) or seq++
            waiting.add(ticket)
            while (busy || waiting.peek() != ticket) lock.wait()
            waiting.poll()
            busy = true
        }
        try { return block() } finally {
            synchronized(lock) { busy = false; lock.notifyAll() }
        }
    }

    /** An executor whose jobs wait their turn; [before] runs first, outside the turn (e.g. to wait for start-up). */
    fun turns(inner: Executor, priority: Int = 5, before: () -> Unit = {}): Executor =
        Executor { job -> inner.execute { before(); run(priority) { job.run() } } }
}
