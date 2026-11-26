package br.edu.fatecpg.applistafilmes.view

interface FilmeSerieDao {
    fun adicionarFilmeSerie(filmeSerie: FilmeSerie)

    fun obterFilmesSeries(): List<FilmeSerie>
}