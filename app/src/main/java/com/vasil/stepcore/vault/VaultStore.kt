package com.vasil.stepcore.vault

import android.content.Context
import java.io.File

/**
 * Файловое хранилище тайников.
 *
 * Отдельная папка files/vault/ — не общая база StepCore. Изоляция здесь не
 * про красоту архитектуры: общий бэкап приложения не должен содержать
 * заметки, иначе пароль обходится через экспорт.
 *
 * Запись атомарная (временный файл + переименование + fsync). Обрыв питания
 * посреди добавления тайника не должен превращать ключи в мусор: либо
 * старый файл целиком, либо новый целиком, третьего нет.
 */
class VaultStore(context: Context) {

    private val dir = File(context.filesDir, "vault")
    private val keyFile = File(dir, "keys.bin")
    private val tmpFile = File(dir, "keys.bin.tmp")

    fun exists(): Boolean = keyFile.isFile && keyFile.length() > 0

    /** @return null если файла нет или он не разбирается. */
    fun read(): VaultFile.Box? =
        if (!exists()) null
        else try {
            VaultFile.decode(keyFile.readBytes())
        } catch (e: Exception) {
            null
        }

    fun write(box: VaultFile.Box) {
        dir.mkdirs()
        val bytes = VaultFile.encode(box)
        tmpFile.outputStream().use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()   // до переименования байты обязаны лежать на диске
        }
        check(tmpFile.renameTo(keyFile)) { "не удалось заменить файл ключей" }
    }
}

/**
 * Ключ данных открытого тайника. Живёт только в оперативной памяти.
 *
 * Ключ появляется при успешном входе и умирает при уходе с экрана. Ни
 * SharedPreferences, ни файла, ни статического кэша "на всякий случай":
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
