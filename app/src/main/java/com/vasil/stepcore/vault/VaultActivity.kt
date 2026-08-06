package com.vasil.stepcore.vault

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vasil.stepcore.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран Vault. Три состояния: создание хранилища, вход, внутренность.
 *
 * Разметка собирается кодом, а не XML, сознательно: экран существует только
 * для тех, кто знает жест, и не должен появляться в ресурсах отдельным
 * файлом рядом с обычными экранами.
 *
 * FLAG_SECURE обязателен. Без него система кладёт превью экрана в список
 * задач, и заметки утекают мимо всей криптографии.
 */
class VaultActivity : AppCompatActivity() {

    private val store by lazy { VaultStore(this) }
    private lateinit var root: LinearLayout
    private var busy = false

    /**
     * Бюджет ожидания при входе. Подобран под будущую анимацию двери:
     * пользователь смотрит на открывающийся механизм, а не на пустой экран.
     * Чем дороже вход, тем дороже перебор — здесь красота и стойкость
     * совпадают.
     */
    private val unlockBudgetMs = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            addView(root, LinearLayout.LayoutParams(-1, -2))
        })

        if (VaultSession.isOpen) showInside()
        else if (store.exists()) showUnlock()
        else showSetup()
    }

    /** Уход с экрана запирает хранилище. Ключ не переживает сворачивание. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            VaultSession.lock()
            finish()
        }
    }

    // ---------------------------------------------------------------- создание

    private fun showSetup() {
        root.removeAllViews()
        title("Тайник")
        dim("Здесь ничего нет, пока ты не создашь хранилище. " +
            "Заметки шифруются на этом устройстве и никуда не отправляются.")

        val pass = secretField("Пароль")
        val pass2 = secretField("Пароль ещё раз")

        gap()
        dim("Секрет восстановления — второй способ войти, если пароль забыт. " +
            "Слова, цифры, предложение — что угодно, лишь бы ты это помнил или " +
            "где-то записал. Это НЕ подсказка к паролю: он открывает тайник " +
            "точно так же, поэтому хранить его надо так же бережно.")

        val rec = secretField("Секрет восстановления")
        val rec2 = secretField("Секрет восстановления ещё раз")

        val ack = CheckBox(this).apply {
            text = "Я записал секрет восстановления или точно его помню"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
        }
        root.addView(ack)

        val warn = warnLabel()
        val go = button("Создать тайник")

        gap()
        dim("Восстановить пароль невозможно — это не недоработка. Если бы " +
            "приложение умело его вернуть, вернуть его смог бы и посторонний. " +
            "Забыл оба секрета — заметки потеряны навсегда.")

        go.setOnClickListener {
            if (busy) return@setOnClickListener
            val p = pass.chars(); val p2 = pass2.chars()
            val r = rec.chars(); val r2 = rec2.chars()

            val problem: String? = when {
                VaultCrypto.checkSecret(p) != null -> "Пароль: " + VaultCrypto.checkSecret(p)
                !p.contentEquals(p2) -> "Пароли не совпадают"
                VaultCrypto.checkSecret(r) != null -> "Секрет восстановления: " + VaultCrypto.checkSecret(r)
                !r.contentEquals(r2) -> "Секреты восстановления не совпадают"
                p.contentEquals(r) -> "Пароль и секрет восстановления совпадают — тогда второго ключа нет"
                !ack.isChecked -> "Подтверди, что секрет восстановления не потеряется"
                else -> null
            }

            if (problem != null) { warn.text = problem; warn.visibility = View.VISIBLE; return@setOnClickListener }
            warn.visibility = View.GONE
            createVault(p, r, go)
        }
    }

    private fun createVault(pass: CharArray, phrase: CharArray, go: Button) {
        busy = true
        go.isEnabled = false
        go.text = "Кую замок…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                try {
                    // N меряется здесь и запоминается внутри обёрток: замок
                    // настраивается по этому телефону, а не по константе.
                    val n = VaultCrypto.calibrateN(unlockBudgetMs)
                    val dataKey = VaultCrypto.newDataKey()
                    val keys = VaultKeyFile.Keys(
                        VaultCrypto.wrap(dataKey, pass, n),
                        VaultCrypto.wrap(dataKey, phrase, n)
                    )
                    store.writeKeys(keys)
                    VaultSession.open(dataKey)
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    pass.fill('\u0000'); phrase.fill('\u0000')
                }
            }
            busy = false
            if (ok) showInside() else {
                go.isEnabled = true
                go.text = "Создать тайник"
                toast("Не удалось создать хранилище")
            }
        }
    }

    // -------------------------------------------------------------------- вход

    private fun showUnlock() {
        root.removeAllViews()
        title("Тайник")
        dim("Введи пароль или секрет восстановления.")

        val field = secretField("Пароль или секрет восстановления")
        val warn = warnLabel()
        val go = button("Открыть")

        gap()
        dim("Проверка занимает секунду-другую намеренно: она стоит памяти и " +
            "времени, поэтому перебор паролей бессмысленен. Количество попыток " +
            "не ограничено — счётчик позволил бы постороннему уничтожить твои " +
            "заметки чужими руками.")

        go.setOnClickListener {
            if (busy) return@setOnClickListener
            val s = field.chars()
            if (s.isEmpty()) return@setOnClickListener
            warn.visibility = View.GONE
            unlock(s, field, go, warn)
        }
    }

    private fun unlock(secret: CharArray, field: EditText, go: Button, warn: TextView) {
        busy = true
        go.isEnabled = false
        go.text = "Открываю…"
        lifecycleScope.launch {
            val key = withContext(Dispatchers.Default) {
                try {
                    val keys = store.readKeys()
                    // Сначала пароль: обычный вход не должен ждать дважды.
                    // Секрет восстановления пробуется вторым — он редкий.
                    keys?.let {
                        VaultCrypto.unwrap(it.byPassword, secret)
                            ?: VaultCrypto.unwrap(it.byPhrase, secret)
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    secret.fill('\u0000')
                }
            }
            busy = false
            if (key != null) {
                VaultSession.open(key)
                showInside()
            } else {
                go.isEnabled = true
                go.text = "Открыть"
                field.setText("")
                warn.text = "Не подходит"
                warn.visibility = View.VISIBLE
            }
        }
    }

    // -------------------------------------------------------------- внутренность

    private fun showInside() {
        root.removeAllViews()
        title("Тайник открыт")
        dim("Замок работает. Заметки, теги и поиск — следующий этап.\n\n" +
            "Ключ живёт только в памяти: уйдёшь с этого экрана — тайник " +
            "запрётся сам.")
        button("Закрыть").setOnClickListener {
            VaultSession.lock()
            finish()
        }
    }

    // ------------------------------------------------------------------- мелочи

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun EditText.chars(): CharArray {
        val e = text
        val out = CharArray(e.length)
        e.getChars(0, e.length, out, 0)
        return out
    }

    private fun title(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 26f
            setTextColor(getColor(R.color.accent_violet_bright))
            setPadding(0, 0, 0, dp(12))
        })
    }

    private fun dim(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun gap() {
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
    }

    private fun secretField(hint: String): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF6A6A75.toInt())
            setBackgroundColor(0xFF1F1F26.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        root.addView(e, lp)
        return e
    }

    private fun warnLabel(): TextView {
        val t = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.accent_red_bright))
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(t)
        return t
    }

    private fun button(label: String): Button {
        val b = Button(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(12)
        root.addView(b, lp)
        return b
    }

    private fun toast(t: String) {
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()
    }
}
