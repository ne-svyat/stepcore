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

    /**
     * Снести файл ключей целиком.
     *
     * Нужен при удалении ПОСЛЕДНЕГО тайника: файл с нулём слотов невалиден
     * по формату, и записать «пустой Box» невозможно в принципе. Базу это
     * не трогает — к этому моменту своих строк там уже не осталось.
     */
    fun destroy() {
        tmpFile.delete()
        keyFile.delete()
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
    @Volatile private var leftAtMs = 0L

    /**
     * Льготное окно после ухода с экрана.
     *
     * Прежде тайник запирался мгновенно, и скриншот, ответ в мессенджере
     * или случайный свайп стоили повторного ввода пароля. Это не
     * безопасность, а наказание за обычное пользование телефоном.
     *
     * Честная цена: полторы минуты ключ живёт в памяти при свёрнутом
     * приложении. Против того, кто выхватил телефон из рук, эти секунды
     * работают. Против случайного взгляда - нет, потому что превью в
     * списке задач запрещено отдельно.
     */
    const val GRACE_MS = 90_000L

    val isOpen: Boolean get() = dataKey != null

    fun open(key: ByteArray) {
        lock()
        dataKey = key
        leftAtMs = 0L
    }

    /** Ушли с экрана: ключ пока живёт, но время пошло. */
    fun leave(nowMs: Long) {
        if (dataKey != null) leftAtMs = nowMs
    }

    /**
     * Вернулись. Если льгота истекла - запираем.
     * @return true, если тайник всё ещё открыт.
     */
    fun resume(nowMs: Long): Boolean {
        if (dataKey == null) return false
        if (leftAtMs != 0L && nowMs - leftAtMs > GRACE_MS) {
            lock()
            return false
        }
        leftAtMs = 0L
        return true
    }

    /** @return копия ключа для операции, либо null если заперто. */
    fun key(): ByteArray? = dataKey?.copyOf()

    /** Ключ затирается нулями, а не просто теряет ссылку. */
    fun lock() {
        dataKey?.fill(0)
        dataKey = null
        leftAtMs = 0L
    }
}
