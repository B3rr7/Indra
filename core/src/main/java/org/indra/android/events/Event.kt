package org.indra.android.events

import java.net.InetAddress

open class Event

class Newline(
    val line: String,
) : Event()

/**
 * Emitted when a child exits normally.
 *
 * [exitValue] is `WEXITSTATUS` — the *command id* is not carried here, it arrives as the
 * `id` argument of `ChildProcessManager.onEvent`. Native code passes it through
 * `create_child_end_event()` (`cSploitClient/command.c`), which narrows it to one byte,
 * so values above 255 are truncated (exit codes are 0..255 by definition).
 */
class ChildEnd(
    val exitValue: Int,
) : Event()

/**
 * Emitted when a child was killed by a signal.
 *
 * [signal] is the terminating signal (`WTERMSIG`), not a command id — same split as
 * [ChildEnd]: the command id arrives as `ChildProcessManager.onEvent`'s first argument.
 */
class ChildDied(
    val signal: Int,
) : Event()

class StderrNewline(
    val line: String,
) : Event()

class Hop(
    val id: Int,
    val seq: Long,
    val address: InetAddress?,
    val info: String?,
) : Event()

class Port(
    val protocol: String?,
    val port: Int,
    val service: String?,
    val version: String?,
) : Event()

class Os(
    val accuracy: Short,
    val name: String?,
    val type: String?,
) : Event()

class Ready : Event()

class Account(
    val address: InetAddress?,
    val protocol: String?,
    val username: String?,
    val password: String?,
) : Event()

class Message(
    val severity: String?,
    val message: String?,
) : Event()

class Login(
    val id: Int,
    val address: InetAddress?,
    val username: String?,
    val password: String?,
) : Event()

class Attempts(
    val sent: Long,
    val left: Long,
    val elapsed: Long,
    val eta: Long,
) : Event()

class Packet(
    val source: InetAddress?,
    val destination: InetAddress?,
    val length: Int,
) : Event()

class FuseBind(
    val path: String?,
    val target: String?,
) : Event()

class Host(
    val mac: ByteArray?,
    val address: InetAddress?,
    val hostname: String?,
) : Event()

class HostLost(
    val address: InetAddress?,
) : Event()
