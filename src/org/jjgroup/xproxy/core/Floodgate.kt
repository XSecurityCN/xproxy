package org.jjgroup.xproxy.core

import org.jjgroup.xproxy.RequestEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Floodgate(val name: String, private val engine: RequestEngine) {
    @JvmField
    val remaining = AtomicInteger(1)

    @JvmField
    val isOpen = AtomicBoolean(false)

    @JvmField
    val fullyQueued = AtomicBoolean(false)
    private val remainingLock = Object()
    private val openLock = Object()

    fun open() {
        if (isOpen.get()) {
            Utils.out("Gate is already open")
            return
        }
        fullyQueued.set(true)

        if (remaining.get() > 0) {
            while (remaining.get() > 0 && engine.attackState.get() < 3) {
                synchronized(remainingLock) {
                    try {
                        remainingLock.wait(100)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }
            makeOpen()
        } else {
            makeOpen()
        }
    }

    private fun makeOpen() {
        synchronized(openLock) {
            isOpen.set(true)
            openLock.notifyAll()
        }
    }

    fun addWaiter() {
        remaining.incrementAndGet()
    }

    @Throws(InterruptedException::class)
    fun waitForGo() {
        remaining.decrementAndGet()
        synchronized(remainingLock) {
            remainingLock.notifyAll()
        }
        synchronized(openLock) {
            while (!isOpen.get()) {
                openLock.wait()
            }
        }
    }

    fun reportReadyWithoutWaiting(): Boolean {
        remaining.decrementAndGet()
        synchronized(remainingLock) {
            remainingLock.notifyAll()
        }
        return remaining.get() == 0 && fullyQueued.get()
    }
}
