package fatec.labweb.projetocasa.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

enum class PeriodoAnalytics(val label: String, val dias: Int?) {
    HOJE("Hoje", 1),
    SETE_DIAS("7 dias", 7),
    TRINTA_DIAS("30 dias", 30),
    TODOS("Todos", null)
}

data class AcionamentoProcessado(
    val id: String,
    val botaoId: String,
    val botaoNome: String,
    val categoriaNome: String,
    val dispositivoMac: String?,
    val origem: String, // "dispositivo" ou "app"
    val instantUtc: Instant,
    val localDateTime: ZonedDateTime
) {
    val localDate: LocalDate
        get() = localDateTime.toLocalDate()

    val horaLocal: Int
        get() = localDateTime.hour

    val isDispositivoFisico: Boolean
        get() = origem.equals("dispositivo", ignoreCase = true)
}

data class KpiResumo(
    val totalComunicacoes: Int,
    val variacaoPercentual: Int?, // null quando período == TODOS ou período anterior == 0
    val mediaDiariaAtiva: Double,
    val diasAtivos: Int,
    val acaoMaisUsadaNome: String,
    val acaoMaisUsadaCount: Int,
    val acaoMaisUsadaPercent: Int,
    val totalDispositivo: Int,
    val totalApp: Int,
    val percentDispositivo: Int,
    val percentApp: Int
)

data class CategoriaStat(
    val categoriaNome: String,
    val quantidade: Int,
    val percentual: Float,
    val corHex: String
)

data class TopAcaoStat(
    val botaoId: String,
    val botaoNome: String,
    val categoriaNome: String,
    val quantidade: Int,
    val corHex: String
)

data class PontoDiario(
    val data: LocalDate,
    val label: String, // dd/MM
    val quantidade: Int
)

data class TurnoStat(
    val nome: String,
    val faixaLabel: String,
    val quantidade: Int,
    val isPico: Boolean
)

data class AlertaUrgencia(
    val botaoId: String,
    val botaoNome: String,
    val quantidadeSeguida: Int,
    val horarioFormatado: String, // HH:mm
    val dataFormatada: String     // dd/MM
)

data class InsightsResumo(
    val alertaUrgencia: AlertaUrgencia?,
    val diversidadeVocabulario: Int,
    val macAtivo: String?,
    val ultimoDisparoFisicoTexto: String
)

data class AnalyticsSnapshot(
    val periodo: PeriodoAnalytics,
    val kpi: KpiResumo,
    val categorias: List<CategoriaStat>,
    val topAcoes: List<TopAcaoStat>,
    val evolucaoDiaria: List<PontoDiario>,
    val mediaLinhaDiaria: Float,
    val turnos: List<TurnoStat>,
    val insights: InsightsResumo,
    val menosAcionadoNome: String
)

sealed class DesempenhoUiState {
    object Loading : DesempenhoUiState()
    data class Empty(val periodo: PeriodoAnalytics) : DesempenhoUiState()
    data class Success(val snapshot: AnalyticsSnapshot) : DesempenhoUiState()
    data class Error(val mensagem: String) : DesempenhoUiState()
}
