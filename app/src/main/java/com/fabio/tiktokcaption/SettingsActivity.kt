package com.fabio.tiktokcaption

import android.os.Bundle
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

        val editPostForMeKey = findViewById<EditText>(R.id.editPostForMeKey)
        editPostForMeKey.setText(prefs.getString(Prefs.KEY_POSTFORME_API_KEY, ""))

        findViewById<Button>(R.id.btnSavePostForMeKey).setOnClickListener {
            prefs.edit().putString(Prefs.KEY_POSTFORME_API_KEY, editPostForMeKey.text.toString().trim()).apply()
            Toast.makeText(this, "Chave do Post for Me salva", Toast.LENGTH_SHORT).show()
        }
    }
}
