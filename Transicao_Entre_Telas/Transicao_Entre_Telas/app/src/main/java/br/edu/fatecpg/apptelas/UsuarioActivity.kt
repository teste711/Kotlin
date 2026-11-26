package br.edu.fatecpg.apptelas

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

class UsuarioActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val txvSaudacao = findViewById<TextView>(R.id.txv_saudacao)
        val fabVolta = findViewById<FloatingActionButton>(R.id.fab_volta)

        val nomeUsuario = intent.getStringExtra("nome_usuario")
        "Bem-Vindo Usuário $nomeUsuario"

        fabVolta.setOnClickListener(){
            finish()
        }

    }
}