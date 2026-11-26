package br.edu.fatecpg.applistafilmes.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import br.edu.fatecpg.applistafilmes.R

class FilmeSerieAdapter(private val filmesSeries: List<FilmeSerie>) :
    RecyclerView.Adapter<FilmeSerieAdapter.ViewHolder>(){

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView){
            val textViewNome: TextView = itemView.findViewById(R.id.textView_nomeFilme)
            val textViewGenero: TextView = itemView.findViewById(R.id.textView_genero)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_filmeserie, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val filmeserie = filmesSeries[position]
        holder.textViewNome.text = filmeserie.nome
        holder.textViewGenero.text = filmeserie.genero
    }

    override fun getItemCount(): Int {
        return filmesSeries.size
    }
    }