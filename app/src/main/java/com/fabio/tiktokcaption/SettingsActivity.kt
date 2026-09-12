package com.fabio.tiktokcaption

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

        override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                            setContentView(R.layout.activity_settings)

                                    val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                                            val editApiKey = findViewById<EditText>(R.id.editApiKey)
                                                    editApiKey.setText(prefs.getString(Prefs.KEY_API_KEY, ""))

                                                            findViewById<Button>(R.id.btnToggleApiKey).setOnClickListener {
                                                                            toggleVisibility(editApiKey, it as Button)
                                                            }

                                                                    findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
                                                                                    prefs.edit().putString(Prefs.KEY_API_KEY, cleanKey(editApiKey.text.toString())).apply()
                                                                                                Toast.makeText(this, "Chave salva", Toast.LENGTH_SHORT).show()
                                                                    }

                                                                            val editPostForMeKey = findViewById<EditText>(R.id.editPostForMeKey)
                                                                                    editPostForMeKey.setText(prefs.getString(Prefs.KEY_POSTFORME_API_KEY, ""))

                                                                                            findViewById<Button>(R.id.btnTogglePostForMeKey).setOnClickListener {
                                                                                                            toggleVisibility(editPostForMeKey, it as Button)
                                                                                            }

                                                                                                    findViewById<Button>(R.id.btnSavePostForMeKey).setOnClickListener {
                                                                                                                    prefs.edit().putString(Prefs.KEY_POSTFORME_API_KEY, cleanKey(editPostForMeKey.text.toString())).apply()
                                                                                                                                Toast.makeText(this, "Chave do Post for Me salva", Toast.LENGTH_SHORT).show()
                                                                                                    }
        }

            /**
                 * Tira qualquer espaco, quebra de linha ou tabulacao de dentro da chave colada --
                      * nao so no inicio/fim (trim), mas em qualquer lugar. Copiar de um bloco de notas,
                           * PDF ou mensagem as vezes traz uma quebra de linha invisivel no meio ou no fim,
                                * o que invalida a chave silenciosamente (da erro de autenticacao sem motivo
                                     * aparente, ja que ela "parece" certa visualmente).
                                          */
                                              private fun cleanKey(raw: String): String = raw.replace(Regex("\\s"), "")

                                                  /** Alterna entre mostrar a chave em texto puro e escondida (bolinhas), pra
                                                       * conferir visualmente se colou certo antes de salvar. */
                                                           private fun toggleVisibility(field: EditText, button: Button) {
                                                                       val isHidden = field.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                                                                       if (isHidden) {
                                                                                       field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                                                                       button.text = "Ocultar"
                                                                       } else {
                                                                                       field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                                                                                       button.text = "Mostrar"
                                                                       }
                                                                               field.setSelection(field.text.length)
                                                           }
}
