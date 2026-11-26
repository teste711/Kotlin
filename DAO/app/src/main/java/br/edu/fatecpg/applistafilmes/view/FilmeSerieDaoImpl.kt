package br.edu.fatecpg.applistafilmes.view

class FilmeSerieDaoImpl : FilmeSerieDao {
    companion object {
        private val filmesSeries = mutableListOf<FilmeSerie>()
    }
    override fun adicionarFilmeSerie(filmeSerie: FilmeSerie) {
        Companion.filmesSeries.add(filmeSerie)
    }

    override fun obterFilmesSeries(): List<FilmeSerie> {
        return Companion.filmesSeries
    }

}