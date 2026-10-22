package dev.pi.coding.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path

class FileMutationQueue {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val registryMutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withFile(path: Path, block: suspend () -> T): T {
        val key = path.toString()
        val entry = registryMutex.withLock {
            val current = entries.getOrPut(key) { Entry() }
            current.users++
            current
        }
        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            return block()
        } finally {
            if (acquired) {
                entry.mutex.unlock()
            }
            registryMutex.withLock {
                entry.users--
                if (entry.users == 0) {
                    entries.remove(key, entry)
                }
            }
        }
    }
}
