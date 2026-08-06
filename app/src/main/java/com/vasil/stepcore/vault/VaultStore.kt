package com.vasil.stepcore.vault

import android.content.Context
import java.io.File

/**
 * Файловое хранилище ключей Vault.
 *
 * Отдельная папка files/vault/ — не общая база StepCore и не SharedPreferences.
 * Изоляция здесь не про красоту архитектуры: общий бэкап приложения не должен
 * содержать заметки, иначе пароль обходится через экспорт.
 *
 * Запись атомарная (временный файл + переименование). Обрыв питания посреди
 * смены пароля не должен превращать ключ данных в мусор: либо старый файл,
 * либо новый, третьего нет.
 */
class VaultStore(context: Context) {

    private val dir = File(context.filesDir, "vault")
    private val keyFile = File(dir, "keys.bin")
    private val tmpFile = File(dir, "keys.bin.tmp")

    fun exists(): Boolean = keyFile.isFile && keyFile.length() > 0

    /** @return null если хранилища нет или файл испорчен. */
    fun readKeys(): VaultKeyFile.Keys? =
        if (!exists()) null
        else try {
            VaultKeyFile.decode(keyFile.readBytes())
        } catch (e: Exception) {
            null
        }

    fun writeKeys(keys: VaultKeyFile.Keys) {
        dir.mkdirs()
        val bytes = VaultKeyFile.encode(keys)
        tmpFile.outputStream().use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()   // до переименования байты обязаны лежать на диске
        }
        check(tmpFile.renameTo(keyFile)) { "не удалось заменить файл ключей" }
    }
}

/**
 * Ключ данных, живущий только в оперативной памяти.
 *
 * Ключ появляется при успешном входе и умирает при уходе с экрана Vault.
 * Ни SharedPreferences, ни файла, ни статического кэша "на всякий случай":
 * сохранённый ключ обесценил бы пароль целиком.
 */
object VaultSession {

    @Volatile private var dataKey: ByteArray? = null

    val isOpen: Boolean get() = dataKey != null

    fun open(key: ByteArray) {
        lock()
        dataKey = key
    }

    /** @return копия ключа для операции, либо null если заперто. */
    fun key(): ByteArray? = dataKey?.copyOf()

    /** Ключ затирается нулями, а не просто теряет ссылку. */
    fun lock() {
        dataKey?.fill(0)
        dataKey = null
    }
}
