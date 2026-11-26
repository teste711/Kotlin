package br.edu.fatecpg.applistafilmes.view

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import br.edu.fatecpg.applistafilmes.R
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private val FilmeSerieDao = FilmeSerieDaoImpl()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editTextNome = findViewById<EditText>(R.id.txt_nome)
        val editTextGenero = findViewById<EditText>(R.id.txt_genero)
        val buttonListar = findViewById<Button>(R.id.button_add)
        val buttonListarFilmesSeries = findViewById<FloatingActionButton>(R.id.fab_listar_filmesseries)

        buttonListar.setOnClickListener {
            val nome = editTextNome.text.toString()
            val genero = editTextGenero.text.toString()
            val filmeSerie = FilmeSerie(nome, genero)

            FilmeSerieDao.adicionarFilmeSerie(filmeSerie)

            Toast.makeText(this, "Filme/Série salva com sucesso!", Toast.LENGTH_SHORT).show()
            editTextNome.text.clear()
            editTextGenero.text.clear()
        }

        buttonListarFilmesSeries.setOnClickListener {
            val intent = Intent(this, ListaFilmesSeries::class.java)
            startActivity(intent)
        }
        }
    }