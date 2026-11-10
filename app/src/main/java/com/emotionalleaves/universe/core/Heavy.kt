package com.emotionalleaves.universe.core

import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock

/**
 * One memory-hungry job at a time. Loading the catalogues, building the cosmic web and indexing
 * millions of points each need tens of megabytes of scratch space; run side by side at start-up
 * they filled the whole heap. Taking turns costs a few seconds of loading and keeps the peak low.
 */
object Heavy {
    private val lock = ReentrantLock(true)

    fun <T> run(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    /** An executor whose jobs wait their turn (see [Heavy]). */
    fun turns(inner: Executor): Executor = Executor { job -> inner.execute { run { job.run() } } }
}
