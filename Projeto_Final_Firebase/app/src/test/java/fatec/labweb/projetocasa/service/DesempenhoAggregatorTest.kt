package fatec.labweb.projetocasa.service

import fatec.labweb.projetocasa.model.Acionamento
import fatec.labweb.projetocasa.model.PeriodoAnalytics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DesempenhoAggregatorTest {

    private val spZone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val aggregator = DesempenhoAggregator(zoneId = spZone)

    @Test
    fun `converte UTC para fuso de Sao Paulo corretamente antes de agrupar dia e turno`() {
        // 2026-11-23T01:30:00Z em UTC == 2026-11-22 22:30 em America/Sao_Paulo (UTC-3 -> Noite do dia 22)
        val raw = listOf(
            Acionamento(
                botao_id = "btn_1",
                botao_nome = "Banheiro",
                categoria_nome = "Casa",
                dispositivo = "A0:85:E3:E0:04:A8",
                origem = "dispositivo",
                acionado_em = "2026-11-23T01:30:00Z",
                id = "1"
            )
        )

        val processados = aggregator.processarBrutos(raw)
        assertEquals(1, processados.size)
        assertEquals(22, processados[0].localDate.dayOfMonth)
        assertEquals(22, processados[0].horaLocal)

        val agora = Instant.parse("2026-11-23T15:00:00Z")
        val snap = aggregator.agregar(processados, PeriodoAnalytics.SETE_DIAS, agora)
        assertNotNull(snap)
        val turnoNoite = snap!!.turnos.first { it.nome == "Noite" }
        assertEquals(1, turnoNoite.quantidade)
        assertTrue(turnoNoite.isPico)
    }

    @Test
    fun `detecta urgencia quando mesmo botao_id tem 3 ou mais cliques em ate 120 segundos`() {
        val raw = listOf(
            Acionamento("btn_banheiro", "Banheiro", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-23T17:10:00Z", false, "1"),
            Acionamento("btn_banheiro", "Banheiro", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-23T17:10:40Z", false, "2"),
            Acionamento("btn_banheiro", "Banheiro", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-23T17:11:06Z", false, "3"),
            Acionamento("btn_banheiro", "Banheiro", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-23T17:11:30Z", false, "4")
        )

        val processados = aggregator.processarBrutos(raw)
        val urgencia = aggregator.detectarAlertaUrgencia(processados)

        assertNotNull(urgencia)
        assertEquals("btn_banheiro", urgencia!!.botaoId)
        assertEquals("Banheiro", urgencia.botaoNome)
        assertEquals(4, urgencia.quantidadeSeguida)
        // 17:11:30Z -> 14:11 em America/Sao_Paulo
        assertEquals("14:11", urgencia.horarioFormatado)
    }

    @Test
    fun `nao dispara urgencia quando cliques excedem janela de 120 segundos`() {
        val raw = listOf(
            Acionamento("btn_agua", "Água", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-23T17:00:00Z", false, "1"),
            Acionamento("btn_agua", "Água", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-23T17:01:30Z", false, "2"),
            Acionamento("btn_agua", "Água", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-23T17:03:00Z", false, "3")
        )

        val processados = aggregator.processarBrutos(raw)
        val urgencia = aggregator.detectarAlertaUrgencia(processados)
        assertNull(urgencia)
    }

    @Test
    fun `calcula KPIs, variacao percentual, hardware vs app e diversidade de vocabulario`() {
        // Agora: 2026-11-26T15:00:00Z (12:00 SP)
        // Janela 7 dias atuais: 2026-11-20..2026-11-26 (4 eventos em 2 dias ativos -> média 2.0)
        // Janela 7 dias anteriores: 2026-11-13..2026-11-19 (2 eventos -> variação +100%)
        val raw = listOf(
            // Período atual
            Acionamento("btn_1", "Banheiro", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-26T14:00:00Z", false, "1"),
            Acionamento("btn_1", "Banheiro", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-26T14:10:00Z", false, "2"),
            Acionamento("btn_2", "Água", "Alimentação", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-25T15:00:00Z", false, "3"),
            Acionamento("btn_3", "Dor", "Saúde", null, "app", "2026-11-25T16:00:00Z", false, "4"),
            // Período anterior equivalente
            Acionamento("btn_1", "Banheiro", "Casa", "A0:85:E3:E0:04:A8", "dispositivo", "2026-11-18T14:00:00Z", false, "5"),
            Acionamento("btn_2", "Água", "Alimentação", null, "app", "2026-11-17T14:00:00Z", false, "6")
        )

        val processados = aggregator.processarBrutos(raw)
        val agora = Instant.parse("2026-11-26T15:00:00Z")
        val snap = aggregator.agregar(processados, PeriodoAnalytics.SETE_DIAS, agora)

        assertNotNull(snap)
        snap!!

        assertEquals(4, snap.kpi.totalComunicacoes)
        assertEquals(100, snap.kpi.variacaoPercentual) // (4 - 2) / 2 * 100 = +100%
        assertEquals(2, snap.kpi.diasAtivos)
        assertEquals(2.0, snap.kpi.mediaDiariaAtiva, 0.01)
        assertEquals("Banheiro", snap.kpi.acaoMaisUsadaNome)
        assertEquals(50, snap.kpi.acaoMaisUsadaPercent)
        assertEquals(75, snap.kpi.percentDispositivo)
        assertEquals(25, snap.kpi.percentApp)

        assertEquals(3, snap.insights.diversidadeVocabulario)
        assertEquals("A0:85:E3:E0:04:A8", snap.insights.macAtivo)
        assertEquals(3, snap.categorias.size)
        assertEquals(3, snap.topAcoes.size)
    }
}
