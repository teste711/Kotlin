package fatec.labweb.projetocasa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fatec.labweb.projetocasa.model.AcionamentoProcessado
import fatec.labweb.projetocasa.model.DesempenhoUiState
import fatec.labweb.projetocasa.model.PeriodoAnalytics
import fatec.labweb.projetocasa.service.DesempehoService
import fatec.labweb.projetocasa.service.DesempenhoAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DesempenhoViewModel: Gerencia o estado reativo (StateFlow) do módulo de Analytics,
 * carregando acionamentos do Firestore uma única vez e recalculando métricas, gráficos
 * e insights comportamentais reativamente conforme o filtro de período selecionado.
 */
class DesempenhoViewModel(
    private val service: DesempehoService = DesempehoService(),
    private val aggregator: DesempenhoAggregator = DesempenhoAggregator()
) : ViewModel() {

    private val _periodoSelecionado = MutableStateFlow(PeriodoAnalytics.SETE_DIAS)
    val periodoSelecionado: StateFlow<PeriodoAnalytics> = _periodoSelecionado.asStateFlow()

    private val _uiState = MutableStateFlow<DesempenhoUiState>(DesempenhoUiState.Loading)
    val uiState: StateFlow<DesempenhoUiState> = _uiState.asStateFlow()

    private var cacheProcessados: List<AcionamentoProcessado> = emptyList()
    private var dadosCarregados: Boolean = false

    fun carregarDados(userId: String, forcarRecarga: Boolean = false) {
        if (dadosCarregados && !forcarRecarga) {
            recalcularMetricas(_periodoSelecionado.value)
            return
        }

        _uiState.value = DesempenhoUiState.Loading
        viewModelScope.launch {
            try {
                val brutos = withContext(Dispatchers.IO) {
                    service.carregarAcionamentosBrutos(userId)
                }
                cacheProcessados = withContext(Dispatchers.Default) {
                    aggregator.processarBrutos(brutos)
                }
                dadosCarregados = true
                recalcularMetricas(_periodoSelecionado.value)
            } catch (e: Exception) {
                _uiState.value = DesempenhoUiState.Error(
                    e.message ?: "Não foi possível carregar os dados de desempenho."
                )
            }
        }
    }

    fun selecionarPeriodo(periodo: PeriodoAnalytics) {
        if (_periodoSelecionado.value == periodo && _uiState.value !is DesempenhoUiState.Loading) {
            return
        }
        _periodoSelecionado.value = periodo
        if (dadosCarregados) {
            recalcularMetricas(periodo)
        }
    }

    private fun recalcularMetricas(periodo: PeriodoAnalytics) {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                aggregator.agregar(cacheProcessados, periodo)
            }
            _uiState.value = if (snapshot == null) {
                DesempenhoUiState.Empty(periodo)
            } else {
                DesempenhoUiState.Success(snapshot)
            }
        }
    }
}
