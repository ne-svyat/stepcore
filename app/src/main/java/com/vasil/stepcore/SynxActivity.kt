package com.vasil.stepcore

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** SYNX — экран модуля обучения (v193: заглушка-вход). */
class SynxActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_synx)
        // v195: большая живая стихия. Пока вода (покой) - данные
        // для других состояний появятся в L2/L3.
        findViewById<SynxOrbView>(R.id.synxHeroOrb)
            .setElement(SynxOrbView.Element.WATER, 0.5f)
    }
}
