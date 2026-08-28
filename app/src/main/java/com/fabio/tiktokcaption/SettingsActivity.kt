package com.fabio.tiktokcaption

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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

                                                        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
                                                                      prefs.edit().putString(Prefs.KEY_API_KEY, editApiKey.text.toString().trim()).apply()
                                                                                  Toast.makeText(this, "Chave salva", Toast.LENGTH_SHORT).show()
                                                        }

                                                                findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
                                                                              startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                                                }
      }
}
