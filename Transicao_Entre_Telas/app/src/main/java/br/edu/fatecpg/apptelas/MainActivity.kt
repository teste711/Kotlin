package br.edu.fatecpg.apptelas

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val edtNomeUsuario = findViewById<EditText>(R.id.edt_nome_usuario)
        val edtPassword = findViewById<EditText>(R.id.edt_Password)
        val btnSalvar = findViewById<Button>(R.id.btn_salvar)
        val fabAvanca = findViewById<FloatingActionButton>(R.id.fab_avanca)
        var nomeUsuario = "Teste"
        val senhaCorreta = "1234"

        btnSalvar.setOnClickListener {
            nomeUsuario = edtNomeUsuario.text.toString()
            val senhaDigitada = edtPassword.text.toString()
            if (senhaDigitada == senhaCorreta) {
                edtNomeUsuario.text.clear()
                edtPassword.text.clear()

                Toast.makeText(this,"Login realizado com sucesso!",Toast.LENGTH_SHORT).show()
            }
            else {
                Toast.makeText(this,"Senha Incorreta!",Toast.LENGTH_SHORT).show()}
        }
        fabAvanca.setOnClickListener(){
            val intent = Intent(this@MainActivity, UsuarioActivity::class.java)
            intent.putExtra("nome_usuario",nomeUsuario)
            startActivity(intent)
        }
    }
}