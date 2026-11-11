package dev.lanlink

import android.content.Context
import android.os.Environment
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.security.SecureRandom

/**
 * SFTP server the desktop mounts with sshfs. Started on request, password is
 * random per server lifetime and only ever sent over the paired TLS link.
 */
object Sftp {
    const val USER = "kdeconnect"
    private var server: SshServer? = null
    var password = ""
        private set

    val root: File get() = Environment.getExternalStorageDirectory()

    @Synchronized
    fun start(ctx: Context): Int {
        server?.let { if (it.isStarted) return it.port }
        OsUtils.setAndroid(true)
        System.setProperty("user.home", ctx.filesDir.path)
        PathUtils.setUserHomeFolderResolver { ctx.filesDir.toPath() }
        password = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        var last: Exception? = null
        for (port in 1739..1764) {
            val s = SshServer.setUpDefaultServer().apply {
                this.port = port
                keyPairProvider = SimpleGeneratorHostKeyProvider(File(ctx.filesDir, "sftp_host_key").toPath())
                passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { u, p, _ -> u == USER && p == password }
                publickeyAuthenticator = null
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            }
            try {
                s.start()
                server = s
                return port
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no free port")
    }

    @Synchronized
    fun stop() {
        try { server?.stop(true) } catch (_: Exception) {}
        server = null
    }
}
