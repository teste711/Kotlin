package br.edu.fatecpg.applistafilmes.view

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.edu.fatecpg.applistafilmes.R

class ListaFilmesSeries : AppCompatActivity() {

    private val filmeSerieDao = FilmeSerieDaoImpl()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lista_filmes_series)

        val recyclerViewFilmes = findViewById<RecyclerView>(R.id.recyclerView_filmes)
        val filmesSeries = filmeSerieDao.obterFilmesSeries()
        recyclerViewFilmes.layoutManager = LinearLayoutManager(this)
        recyclerViewFilmes.adapter = FilmeSerieAdapter(filmesSeries)
        }
    }