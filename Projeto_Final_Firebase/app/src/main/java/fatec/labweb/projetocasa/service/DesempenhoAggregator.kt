package fatec.labweb.projetocasa.service

import fatec.labweb.projetocasa.model.Acionamento
import fatec.labweb.projetocasa.model.AcionamentoProcessado
import fatec.labweb.projetocasa.model.AlertaUrgencia
import fatec.labweb.projetocasa.model.AnalyticsSnapshot
import fatec.labweb.projetocasa.model.CategoriaStat
import fatec.labweb.projetocasa.model.InsightsResumo
import fatec.labweb.projetocasa.model.KpiResumo
import fatec.labweb.projetocasa.model.PeriodoAnalytics
import fatec.labweb.projetocasa.model.PontoDiario
import fatec.labweb.projetocasa.model.TopAcaoStat
import fatec.labweb.projetocasa.model.TurnoStat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * DesempenhoAggregator: Engine pura de agregação de analytics do Projeto C.A.S.A.
 * Converte timestamps UTC para o fuso local, preserva nomes históricos desnormalizados,
 * agrupa por botao_id e detecta padrões comportamentais (urgência <= 120s).
 */
class DesempenhoAggregator(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    companion object {
        // Paleta de alto contraste (Light/Dark friendly, sem roxo)
        private val PALETA_CATEGORIAS = listOf(
            "#2563EB", // Azul C.A.S.A.
            "#10B981", // Esmeralda
            "#F59E0B", // Âmbar
            "#0EA5E9", // Ciano
            "#F97316", // Laranja
            "#14B8A6", // Turquesa
            "#64748B"  // Ardósia
        )

        private const val URGENCIA_MIN_CLIQUES = 3
        private const val URGENCIA_JANELA_SEGUNDOS = 120L

        private val FMT_DIA_MES = DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault())
        private val FMT_HORA_MIN = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    }

    fun processarBrutos(acionamentos: List<Acionamento>): List<AcionamentoProcessado> {
        return acionamentos.mapNotNull { item ->
            val instant = parseInstantUtc(item.acionado_em) ?: return@mapNotNull null
            val zoned = instant.atZone(zoneId)
            val nomeBotao = item.botao_nome.trim().ifBlank { "Botão sem nome" }
            val nomeCategoria = item.categoria_nome.trim().ifBlank { "Geral" }
            val idBotao = item.botao_id.trim().ifBlank { nomeBotao.lowercase(Locale.ROOT) }

            AcionamentoProcessado(
                id = item.id,
                botaoId = idBotao,
                botaoNome = nomeBotao,
                categoriaNome = nomeCategoria,
                dispositivoMac = item.dispositivo?.trim()?.takeIf { it.isNotBlank() },
                origem = item.origem.trim().ifBlank { "app" },
                instantUtc = instant,
                localDateTime = zoned
            )
        }.sortedByDescending { it.instantUtc }
    }

    fun agregar(
        todosProcessados: List<AcionamentoProcessado>,
        periodo: PeriodoAnalytics,
        agora: Instant = Instant.now()
    ): AnalyticsSnapshot? {
        val hojeLocal = agora.atZone(zoneId).toLocalDate()

        val (eventosPeriodo, eventosAnteriores) = filtrarPorPeriodo(
            todos = todosProcessados,
            periodo = periodo,
            hojeLocal = hojeLocal
        )

        if (eventosPeriodo.isEmpty()) return null

        val total = eventosPeriodo.size

        // 1. Variação percentual vs período anterior equivalente
        val variacaoPercentual: Int? = if (periodo == PeriodoAnalytics.TODOS) {
            null
        } else {
            val prevCount = eventosAnteriores.size
            if (prevCount == 0) {
                null
            } else {
                (((total - prevCount).toDouble() / prevCount.toDouble()) * 100.0).roundToInt()
            }
        }

        // 2. Média diária por dia ativo no período
        val diasAtivos = eventosPeriodo.map { it.localDate }.distinct().size.coerceAtLeast(1)
        val mediaDiariaAtiva = total.toDouble() / diasAtivos.toDouble()

        // 3. Agrupamento por categoria_nome (com cor consistente)
        val mapaCoresCategoria = linkedMapOf<String, String>()
        val categoriasOrdenadas = eventosPeriodo
            .groupBy { it.categoriaNome }
            .entries
            .sortedByDescending { it.value.size }

        val categoriasStats = categoriasOrdenadas.mapIndexed { idx, (catNome, lista) ->
            val cor = PALETA_CATEGORIAS[idx % PALETA_CATEGORIAS.size]
            mapaCoresCategoria[catNome] = cor
            CategoriaStat(
                categoriaNome = catNome,
                quantidade = lista.size,
                percentual = (lista.size.toFloat() / total.toFloat()) * 100f,
                corHex = cor
            )
        }

        // 4. Agrupamento por botao_id (exibindo botao_nome histórico do evento mais recente do id)
        val acoesAgrupadas = eventosPeriodo
            .groupBy { it.botaoId }
            .entries
            .sortedByDescending { it.value.size }

        val maisUsadaEntry = acoesAgrupadas.first()
        val maisUsadaRef = maisUsadaEntry.value.maxByOrNull { it.instantUtc }!!
        val acaoMaisUsadaCount = maisUsadaEntry.value.size
        val acaoMaisUsadaPercent = ((acaoMaisUsadaCount.toDouble() / total.toDouble()) * 100.0).roundToInt()

        val menosAcionadoRef = acoesAgrupadas.last().value.maxByOrNull { it.instantUtc }!!

        val topAcoes = acoesAgrupadas.take(5).map { (botaoId, lista) ->
            val ref = lista.maxByOrNull { it.instantUtc }!!
            val cor = mapaCoresCategoria[ref.categoriaNome] ?: PALETA_CATEGORIAS[0]
            TopAcaoStat(
                botaoId = botaoId,
                botaoNome = ref.botaoNome,
                categoriaNome = ref.categoriaNome,
                quantidade = lista.size,
                corHex = cor
            )
        }

        // 5. Uso no Hardware (dispositivo vs app)
        val totalDispositivo = eventosPeriodo.count { it.isDispositivoFisico }
        val totalApp = total - totalDispositivo
        val percentDispositivo = ((totalDispositivo.toDouble() / total.toDouble()) * 100.0).roundToInt()
        val percentApp = 100 - percentDispositivo

        val kpi = KpiResumo(
            totalComunicacoes = total,
            variacaoPercentual = variacaoPercentual,
            mediaDiariaAtiva = mediaDiariaAtiva,
            diasAtivos = diasAtivos,
            acaoMaisUsadaNome = maisUsadaRef.botaoNome,
            acaoMaisUsadaCount = acaoMaisUsadaCount,
            acaoMaisUsadaPercent = acaoMaisUsadaPercent,
            totalDispositivo = totalDispositivo,
            totalApp = totalApp,
            percentDispositivo = percentDispositivo,
            percentApp = percentApp
        )

        // 6. Evolução diária (dd/MM)
        val evolucaoDiaria = calcularEvolucaoDiaria(eventosPeriodo, periodo, hojeLocal)
        val mediaLinha = if (evolucaoDiaria.isNotEmpty()) {
            evolucaoDiaria.map { it.quantidade }.average().toFloat()
        } else {
            0f
        }

        // 7. Padrão de Horários (Turnos locais)
        val turnos = calcularTurnos(eventosPeriodo)

        // 8. Insights comportamentais
        val alertaUrgencia = detectarAlertaUrgencia(eventosPeriodo)
        val diversidade = eventosPeriodo.distinctBy { it.botaoId }.size
        val ultimoFisico = todosProcessados.firstOrNull { it.isDispositivoFisico }
            ?: eventosPeriodo.firstOrNull { it.isDispositivoFisico }
        val macAtivo = ultimoFisico?.dispositivoMac
            ?: todosProcessados.firstNotNullOfOrNull { it.dispositivoMac }
        val ultimoDisparoTexto = if (ultimoFisico != null) {
            formatarTempoRelativo(ultimoFisico.instantUtc, agora)
        } else {
            "Nenhum acionamento físico registrado"
        }

        val insights = InsightsResumo(
            alertaUrgencia = alertaUrgencia,
            diversidadeVocabulario = diversidade,
            macAtivo = macAtivo,
            ultimoDisparoFisicoTexto = ultimoDisparoTexto
        )

        return AnalyticsSnapshot(
            periodo = periodo,
            kpi = kpi,
            categorias = categoriasStats,
            topAcoes = topAcoes,
            evolucaoDiaria = evolucaoDiaria,
            mediaLinhaDiaria = mediaLinha,
            turnos = turnos,
            insights = insights,
            menosAcionadoNome = menosAcionadoRef.botaoNome
        )
    }

    private fun filtrarPorPeriodo(
        todos: List<AcionamentoProcessado>,
        periodo: PeriodoAnalytics,
        hojeLocal: LocalDate
    ): Pair<List<AcionamentoProcessado>, List<AcionamentoProcessado>> {
        val dias = periodo.dias ?: return Pair(todos, emptyList())

        val inicioAtual = hojeLocal.minusDays((dias - 1).toLong())
        val fimAnterior = inicioAtual.minusDays(1)
        val inicioAnterior = fimAnterior.minusDays((dias - 1).toLong())

        val atuais = todos.filter { !it.localDate.isBefore(inicioAtual) && !it.localDate.isAfter(hojeLocal) }
        val anteriores = todos.filter { !it.localDate.isBefore(inicioAnterior) && !it.localDate.isAfter(fimAnterior) }
        return Pair(atuais, anteriores)
    }

    private fun calcularEvolucaoDiaria(
        eventos: List<AcionamentoProcessado>,
        periodo: PeriodoAnalytics,
        hojeLocal: LocalDate
    ): List<PontoDiario> {
        val contagemPorDia = eventos.groupingBy { it.localDate }.eachCount()

        val dataInicio: LocalDate = when (periodo) {
            PeriodoAnalytics.HOJE -> hojeLocal
            PeriodoAnalytics.SETE_DIAS -> hojeLocal.minusDays(6)
            PeriodoAnalytics.TRINTA_DIAS -> hojeLocal.minusDays(29)
            PeriodoAnalytics.TODOS -> {
                val minData = eventos.minOf { it.localDate }
                val limite = hojeLocal.minusDays(29)
                if (minData.isBefore(limite)) limite else minData
            }
        }

        val pontos = mutableListOf<PontoDiario>()
        var cursor = dataInicio
        while (!cursor.isAfter(hojeLocal)) {
            pontos.add(
                PontoDiario(
                    data = cursor,
                    label = cursor.format(FMT_DIA_MES),
                    quantidade = contagemPorDia[cursor] ?: 0
                )
            )
            cursor = cursor.plusDays(1)
        }
        return pontos
    }

    private fun calcularTurnos(eventos: List<AcionamentoProcessado>): List<TurnoStat> {
        var madrugada = 0
        var manha = 0
        var tarde = 0
        var noite = 0

        for (ev in eventos) {
            when (ev.horaLocal) {
                in 0..5 -> madrugada++
                in 6..11 -> manha++
                in 12..17 -> tarde++
                else -> noite++
            }
        }

        val maxVal = maxOf(madrugada, manha, tarde, noite)
        return listOf(
            TurnoStat("Madrugada", "00h–05h", madrugada, maxVal > 0 && madrugada == maxVal),
            TurnoStat("Manhã", "06h–11h", manha, maxVal > 0 && manha == maxVal),
            TurnoStat("Tarde", "12h–17h", tarde, maxVal > 0 && tarde == maxVal),
            TurnoStat("Noite", "18h–23h", noite, maxVal > 0 && noite == maxVal)
        )
    }

    /**
     * Detecta insistência/urgência: mesmo botao_id acionado >= 3 vezes dentro de uma janela
     * de <= 120 segundos entre o primeiro e o último clique do grupo.
     */
    fun detectarAlertaUrgencia(eventos: List<AcionamentoProcessado>): AlertaUrgencia? {
        var melhorAlerta: AlertaUrgencia? = null
        var melhorInstante: Instant? = null

        val porBotao = eventos.groupBy { it.botaoId }
        for ((botaoId, lista) in porBotao) {
            if (lista.size < URGENCIA_MIN_CLIQUES) continue
            val ordenados = lista.sortedBy { it.instantUtc }

            var i = 0
            while (i < ordenados.size) {
                var j = i
                while (j + 1 < ordenados.size &&
                    Duration.between(ordenados[i].instantUtc, ordenados[j + 1].instantUtc).seconds <= URGENCIA_JANELA_SEGUNDOS
                ) {
                    j++
                }

                val qtdNoCluster = j - i + 1
                if (qtdNoCluster >= URGENCIA_MIN_CLIQUES) {
                    val ultimoEvento = ordenados[j]
                    if (melhorInstante == null || ultimoEvento.instantUtc.isAfter(melhorInstante)) {
                        melhorInstante = ultimoEvento.instantUtc
                        melhorAlerta = AlertaUrgencia(
                            botaoId = botaoId,
                            botaoNome = ultimoEvento.botaoNome,
                            quantidadeSeguida = qtdNoCluster,
                            horarioFormatado = ultimoEvento.localDateTime.format(FMT_HORA_MIN),
                            dataFormatada = ultimoEvento.localDateTime.format(FMT_DIA_MES)
                        )
                    }
                    i = j + 1
                } else {
                    i++
                }
            }
        }
        return melhorAlerta
    }

    fun parseInstantUtc(isoString: String): Instant? {
        val limpo = isoString.trim()
        if (limpo.isBlank()) return null

        // 1. Formato padrão ISO-8601 UTC (ex: 2026-11-23T17:11:06Z)
        try {
            return Instant.parse(limpo)
        } catch (_: Exception) {}

        // 2. Fallback: ISO sem sufixo Z ou espaço separador
        return try {
            val normalizado = limpo.replace(" ", "T").substringBefore(".")
            LocalDateTime.parse(normalizado).toInstant(ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatarTempoRelativo(eventoUtc: Instant, agoraUtc: Instant): String {
        val diffSeg = Duration.between(eventoUtc, agoraUtc).seconds.coerceAtLeast(0L)
        return when {
            diffSeg < 60L -> "há poucos segundos"
            diffSeg < 3600L -> "há ${diffSeg / 60L} min"
            diffSeg < 86400L -> "há ${diffSeg / 3600L} h"
            else -> "há ${diffSeg / 86400L} dia(s)"
        }
    }
}
