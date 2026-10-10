package com.blockhost

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts secrets with a non-exportable AES key stored in the Android Keystore. */
object SecretStore {
    private const val ALIAS = "blockhost_secrets"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }
    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

/**
 * Optional "bring your own server" reverse SSH tunnel (equivalent to `ssh -R`). The user supplies a VPS they control;
 * BlockHost forwards remotePort on the VPS to the local Minecraft port. Key-based auth only. The host key is pinned on
 * first use (TOFU). The VPS needs `GatewayPorts yes` (or clientspecified) in sshd_config for public access.
 */
class Tunnel(private val ctx: Context) {
    val state = MutableStateFlow("Off")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val prefs get() = (ctx.applicationContext as BlockHostApp).prefs

    fun hasKey() = prefs.getString("ssh_key").isNotEmpty()
    fun saveKey(pem: String) { prefs.putString("ssh_key", SecretStore.encrypt(pem.trim() + "\n")) }
    fun clearKey() { prefs.putString("ssh_key", ""); prefs.putString("ssh_hostkey", "") }

    fun start(host: String, sshPort: Int, user: String, remotePort: Int, localPort: Int) {
        stop()
        if (!hasKey()) { state.value = "Error: no private key stored"; return }
        job = scope.launch {
            var session: Session? = null
            var backoff = 5000L
            while (isActive) {
                try {
                    state.value = "Connecting to $host…"
                    val jsch = JSch()
                    jsch.addIdentity("blockhost", SecretStore.decrypt(prefs.getString("ssh_key")).toByteArray(), null, null)
                    val s = jsch.getSession(user, host, sshPort)
                    s.setConfig("StrictHostKeyChecking", "no")
                    s.setConfig("PreferredAuthentications", "publickey")
                    s.setServerAliveInterval(30000)
                    s.connect(15000)
                    val fp = s.hostKey.getFingerPrint(jsch)
                    val pinned = prefs.getString("ssh_hostkey")
                    if (pinned.isEmpty()) prefs.putString("ssh_hostkey", fp)
                    else if (pinned != fp) { s.disconnect(); state.value = "Error: SSH host key changed (possible attack). Clear the key to re-pin."; return@launch }
                    s.setPortForwardingR("0.0.0.0", remotePort, "127.0.0.1", localPort)
                    session = s; backoff = 5000L
                    state.value = "Connected: $host:$remotePort → local $localPort (host key $fp)"
                    while (isActive && s.isConnected) delay(3000)
                    if (isActive) state.value = "Connection lost; retrying…"
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    state.value = "Error: ${e.message}; retrying in ${backoff / 1000}s"
                } finally { try { session?.disconnect() } catch (_: Exception) {}; session = null }
                delay(backoff); backoff = minOf(backoff * 2, 60000L)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; state.value = "Off" }
}
