package fatec.labweb.projetocasa.view

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import fatec.labweb.projetocasa.R
import fatec.labweb.projetocasa.model.AnalyticsSnapshot
import fatec.labweb.projetocasa.model.CategoriaStat
import fatec.labweb.projetocasa.model.DesempenhoUiState
import fatec.labweb.projetocasa.model.PeriodoAnalytics
import fatec.labweb.projetocasa.model.PontoDiario
import fatec.labweb.projetocasa.model.TopAcaoStat
import fatec.labweb.projetocasa.model.TurnoStat
import fatec.labweb.projetocasa.viewmodel.DesempenhoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DesempenhoActivity: Dashboard de Analytics e Performance do Projeto C.A.S.A.
 * Consome StateFlow do DesempenhoViewModel com filtro de período, KPIs 2x2,
 * Resumo Inteligente, 4 gráficos MPAndroidChart e exportação de PDF.
 */
class DesempenhoActivity : BaseDrawerActivity() {

    companion object {
        private const val CHANNEL_ID = "relatorios_downloads_channel"
        private const val CHANNEL_NAME = "Downloads de Relatórios"
        private const val NOTIFICATION_ID = 2001
    }

    override fun getContentLayoutId(): Int = R.layout.activity_desempenho
    override fun getCurrentMenuItemId(): Int = R.id.menuDesempenho

    private val viewModel: DesempenhoViewModel by viewModels()

    // Filtros e estados
    private lateinit var chipGroupPeriodo: ChipGroup
    private lateinit var layoutLoading: View
    private lateinit var layoutEmpty: View
    private lateinit var txtEmptyTitulo: TextView
    private lateinit var btnVerTodosPeriodos: MaterialButton
    private lateinit var layoutConteudo: View

    // KPIs 2x2
    private lateinit var txtTotalAcionamentos: TextView
    private lateinit var txtBadgeVariacao: TextView
    private lateinit var txtVariacaoLegenda: TextView
    private lateinit var txtMediaDiaria: TextView
    private lateinit var txtDiasAtivos: TextView
    private lateinit var txtAcaoMaisUsada: TextView
    private lateinit var txtAcaoMaisUsadaPct: TextView
    private lateinit var txtUsoHardwareResumo: TextView
    private lateinit var viewBarraHardware: View
    private lateinit var viewBarraApp: View
    private lateinit var txtUsoHardwareDetalhe: TextView

    // Resumo Inteligente
    private lateinit var cardAlertaUrgencia: MaterialCardView
    private lateinit var txtAlertaUrgencia: TextView
    private lateinit var txtDiversidadeVocabulario: TextView
    private lateinit var txtTelemetriaDispositivo: TextView

    // Gráficos MPAndroidChart
    private lateinit var chartCategorias: PieChart
    private lateinit var chartTopAcoes: HorizontalBarChart
    private lateinit var chartDesempenho: LineChart
    private lateinit var chartTurnos: BarChart
    private lateinit var txtTurnoPicoSubtitulo: TextView
    private lateinit var btnGerarPdf: MaterialButton

    private var snapshotAtual: AnalyticsSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initViews()
        criarCanalNotificacao()
        configurarEstilosIniciaisGraficos()
        setupListeners()
        observarEstado()

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "usuario_local"
        viewModel.carregarDados(uid)
    }

    private fun initViews() {
        chipGroupPeriodo = findViewById(R.id.chipGroupPeriodo)
        layoutLoading = findViewById(R.id.layoutLoadingAnalytics)
        layoutEmpty = findViewById(R.id.layoutEmptyAnalytics)
        txtEmptyTitulo = findViewById(R.id.txtEmptyTitulo)
        btnVerTodosPeriodos = findViewById(R.id.btnVerTodosPeriodos)
        layoutConteudo = findViewById(R.id.layoutConteudoAnalytics)

        txtTotalAcionamentos = findViewById(R.id.txtTotalAcionamentos)
        txtBadgeVariacao = findViewById(R.id.txtBadgeVariacao)
        txtVariacaoLegenda = findViewById(R.id.txtVariacaoLegenda)
        txtMediaDiaria = findViewById(R.id.txtMediaDiaria)
        txtDiasAtivos = findViewById(R.id.txtDiasAtivos)
        txtAcaoMaisUsada = findViewById(R.id.txtAcaoMaisUsada)
        txtAcaoMaisUsadaPct = findViewById(R.id.txtAcaoMaisUsadaPct)
        txtUsoHardwareResumo = findViewById(R.id.txtUsoHardwareResumo)
        viewBarraHardware = findViewById(R.id.viewBarraHardware)
        viewBarraApp = findViewById(R.id.viewBarraApp)
        txtUsoHardwareDetalhe = findViewById(R.id.txtUsoHardwareDetalhe)

        cardAlertaUrgencia = findViewById(R.id.cardAlertaUrgencia)
        txtAlertaUrgencia = findViewById(R.id.txtAlertaUrgencia)
        txtDiversidadeVocabulario = findViewById(R.id.txtDiversidadeVocabulario)
        txtTelemetriaDispositivo = findViewById(R.id.txtTelemetriaDispositivo)

        chartCategorias = findViewById(R.id.chartCategorias)
        chartTopAcoes = findViewById(R.id.chartTopAcoes)
        chartDesempenho = findViewById(R.id.chartDesempenho)
        chartTurnos = findViewById(R.id.chartTurnos)
        txtTurnoPicoSubtitulo = findViewById(R.id.txtTurnoPicoSubtitulo)
        btnGerarPdf = findViewById(R.id.btnGerarPdf)
    }

    private fun setupListeners() {
        chipGroupPeriodo.setOnCheckedStateChangeListener { _, checkedIds ->
            val periodo = when (checkedIds.firstOrNull()) {
                R.id.chipPeriodoHoje -> PeriodoAnalytics.HOJE
                R.id.chipPeriodo30Dias -> PeriodoAnalytics.TRINTA_DIAS
                R.id.chipPeriodoTodos -> PeriodoAnalytics.TODOS
                else -> PeriodoAnalytics.SETE_DIAS
            }
            viewModel.selecionarPeriodo(periodo)
        }

        btnVerTodosPeriodos.setOnClickListener {
            chipGroupPeriodo.check(R.id.chipPeriodoTodos)
            viewModel.selecionarPeriodo(PeriodoAnalytics.TODOS)
        }

        btnGerarPdf.setOnClickListener {
            exportarRelatorioPdf()
        }
    }

    private fun observarEstado() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is DesempenhoUiState.Loading -> {
                            layoutLoading.visibility = View.VISIBLE
                            layoutEmpty.visibility = View.GONE
                            layoutConteudo.visibility = View.GONE
                        }

                        is DesempenhoUiState.Empty -> {
                            snapshotAtual = null
                            layoutLoading.visibility = View.GONE
                            layoutConteudo.visibility = View.GONE
                            layoutEmpty.visibility = View.VISIBLE
                            txtEmptyTitulo.text = "Nenhum acionamento em \"${state.periodo.label}\""
                            btnVerTodosPeriodos.visibility =
                                if (state.periodo == PeriodoAnalytics.TODOS) View.GONE else View.VISIBLE
                        }

                        is DesempenhoUiState.Success -> {
                            snapshotAtual = state.snapshot
                            layoutLoading.visibility = View.GONE
                            layoutEmpty.visibility = View.GONE
                            layoutConteudo.visibility = View.VISIBLE
                            renderizarSnapshot(state.snapshot)
                        }

                        is DesempenhoUiState.Error -> {
                            layoutLoading.visibility = View.GONE
                            layoutEmpty.visibility = View.VISIBLE
                            layoutConteudo.visibility = View.GONE
                            txtEmptyTitulo.text = state.mensagem
                            Toast.makeText(this@DesempenhoActivity, state.mensagem, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun configurarEstilosIniciaisGraficos() {
        val corMuted = ContextCompat.getColor(this, R.color.text_secondary)

        chartCategorias.apply {
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 58f
            transparentCircleRadius = 62f
            setHoleColor(Color.TRANSPARENT)
            setDrawEntryLabels(false)
            setNoDataText("Sem dados de categoria")
            setNoDataTextColor(corMuted)
            legend.apply {
                isEnabled = true
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                orientation = Legend.LegendOrientation.HORIZONTAL
                isWordWrapEnabled = true
                textColor = corMuted
                textSize = 11f
            }
        }

        chartTopAcoes.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setScaleEnabled(false)
            setPinchZoom(false)
            setFitBars(true)
            setNoDataText("Sem ações registradas")
            setNoDataTextColor(corMuted)
        }

        chartDesempenho.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)
            setNoDataText("Sem dados diários")
            setNoDataTextColor(corMuted)
        }

        chartTurnos.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setScaleEnabled(false)
            setPinchZoom(false)
            setFitBars(true)
            setNoDataText("Sem dados por turno")
            setNoDataTextColor(corMuted)
        }
    }

    private fun renderizarSnapshot(snapshot: AnalyticsSnapshot) {
        val kpi = snapshot.kpi

        // 1. Grid 2x2 de KPIs
        txtTotalAcionamentos.text = kpi.totalComunicacoes.toString()
        val variacao = kpi.variacaoPercentual
        if (variacao != null) {
            txtBadgeVariacao.visibility = View.VISIBLE
            txtVariacaoLegenda.visibility = View.VISIBLE
            val sinal = if (variacao >= 0) "+" else ""
            txtBadgeVariacao.text = "$sinal$variacao%"
            if (variacao >= 0) {
                txtBadgeVariacao.setBackgroundColor(Color.parseColor("#D1FAE5"))
                txtBadgeVariacao.setTextColor(Color.parseColor("#065F46"))
            } else {
                txtBadgeVariacao.setBackgroundColor(Color.parseColor("#FEE2E2"))
                txtBadgeVariacao.setTextColor(Color.parseColor("#991B1B"))
            }
        } else {
            txtBadgeVariacao.visibility = View.GONE
            txtVariacaoLegenda.visibility = View.GONE
        }

        txtMediaDiaria.text = String.format(Locale.getDefault(), "%.1f", kpi.mediaDiariaAtiva)
        val diaLabel = if (kpi.diasAtivos == 1) "dia ativo" else "dias ativos"
        txtDiasAtivos.text = "em ${kpi.diasAtivos} $diaLabel"

        txtAcaoMaisUsada.text = kpi.acaoMaisUsadaNome
        txtAcaoMaisUsadaPct.text = "${kpi.acaoMaisUsadaCount}x (${kpi.acaoMaisUsadaPercent}% do total)"

        txtUsoHardwareResumo.text = "${kpi.percentDispositivo}% ESP32"
        txtUsoHardwareDetalhe.text = "ESP32 ${kpi.percentDispositivo}% (${kpi.totalDispositivo}) • App ${kpi.percentApp}% (${kpi.totalApp})"

        val pesoHw = kpi.percentDispositivo.coerceAtLeast(0).toFloat()
        val pesoApp = kpi.percentApp.coerceAtLeast(0).toFloat()
        (viewBarraHardware.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = pesoHw
            viewBarraHardware.layoutParams = lp
        }
        (viewBarraApp.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = pesoApp
            viewBarraApp.layoutParams = lp
        }

        // 2. Resumo Inteligente (Insights)
        val urgencia = snapshot.insights.alertaUrgencia
        if (urgencia != null) {
            cardAlertaUrgencia.visibility = View.VISIBLE
            txtAlertaUrgencia.text =
                "Urgência detectada: '${urgencia.botaoNome}' acionado ${urgencia.quantidadeSeguida}x seguidas às ${urgencia.horarioFormatado} (${urgencia.dataFormatada})"
        } else {
            cardAlertaUrgencia.visibility = View.GONE
        }

        val div = snapshot.insights.diversidadeVocabulario
        val btnPlural = if (div == 1) "botão único" else "botões únicos"
        txtDiversidadeVocabulario.text = "Diversidade de vocabulário: $div $btnPlural utilizado(s) no período"

        val macInfo = snapshot.insights.macAtivo ?: "Sem placa vinculada"
        txtTelemetriaDispositivo.text = "Hardware ($macInfo) • Último disparo físico: ${snapshot.insights.ultimoDisparoFisicoTexto}"

        // 3. Renderizar os 4 gráficos MPAndroidChart
        renderizarDonutCategorias(snapshot.categorias, kpi.totalComunicacoes)
        renderizarTopAcoesHorizontais(snapshot.topAcoes)
        renderizarEvolucaoDiaria(snapshot.evolucaoDiaria, snapshot.mediaLinhaDiaria)
        renderizarTurnos(snapshot.turnos)
    }

    private fun renderizarDonutCategorias(categorias: List<CategoriaStat>, total: Int) {
        val corTexto = ContextCompat.getColor(this, R.color.text_primary)
        val entries = categorias.map { cat ->
            val pct = String.format(Locale.getDefault(), "%.0f%%", cat.percentual)
            PieEntry(cat.quantidade.toFloat(), "${cat.categoriaNome} (${cat.quantidade} • $pct)")
        }
        val cores = categorias.map { Color.parseColor(it.corHex) }

        val dataSet = PieDataSet(entries, "").apply {
            colors = cores
            sliceSpace = 2.5f
            setDrawValues(false)
        }

        chartCategorias.apply {
            centerText = "$total\ntotal"
            setCenterTextColor(corTexto)
            setCenterTextSize(15f)
            data = PieData(dataSet)
            animateY(500)
            invalidate()
        }
    }

    private fun renderizarTopAcoesHorizontais(topAcoes: List<TopAcaoStat>) {
        if (topAcoes.isEmpty()) {
            chartTopAcoes.clear()
            return
        }

        // Inverte para o maior ficar no topo do HorizontalBarChart
        val ordenados = topAcoes.reversed()
        val corTexto = ContextCompat.getColor(this, R.color.text_primary)
        val corSecundario = ContextCompat.getColor(this, R.color.text_secondary)
        val corGrid = ContextCompat.getColor(this, R.color.divider_color)

        val entries = ordenados.mapIndexed { idx, item ->
            BarEntry(idx.toFloat(), item.quantidade.toFloat())
        }
        val cores = ordenados.map { Color.parseColor(it.corHex) }

        val dataSet = BarDataSet(entries, "Top Ações").apply {
            colors = cores
            valueTextColor = corTexto
            valueTextSize = 11f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
            }
        }

        chartTopAcoes.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            labelCount = ordenados.size
            textColor = corTexto
            textSize = 11f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val idx = value.toInt()
                    val nome = if (idx in ordenados.indices) ordenados[idx].botaoNome else ""
                    return if (nome.length > 16) nome.take(15) + "…" else nome
                }
            }
        }

        chartTopAcoes.axisLeft.apply {
            axisMinimum = 0f
            granularity = 1f
            textColor = corSecundario
            gridColor = corGrid
            setDrawAxisLine(false)
        }
        chartTopAcoes.axisRight.isEnabled = false
        chartTopAcoes.extraLeftOffset = 12f
        chartTopAcoes.extraRightOffset = 20f

        chartTopAcoes.data = BarData(dataSet).apply { barWidth = 0.6f }
        chartTopAcoes.animateY(500)
        chartTopAcoes.invalidate()
    }

    private fun renderizarEvolucaoDiaria(pontos: List<PontoDiario>, mediaPeriodo: Float) {
        if (pontos.isEmpty()) {
            chartDesempenho.clear()
            return
        }

        val corBrand = ContextCompat.getColor(this, R.color.brand_primary)
        val corTexto = ContextCompat.getColor(this, R.color.text_primary)
        val corSecundario = ContextCompat.getColor(this, R.color.text_secondary)
        val corGrid = ContextCompat.getColor(this, R.color.divider_color)

        val entries = pontos.mapIndexed { idx, p ->
            Entry(idx.toFloat(), p.quantidade.toFloat())
        }

        val dataSet = LineDataSet(entries, "Acionamentos").apply {
            color = corBrand
            setCircleColor(corBrand)
            circleRadius = 4f
            circleHoleRadius = 2f
            circleHoleColor = ContextCompat.getColor(this@DesempenhoActivity, R.color.card_surface)
            lineWidth = 2.5f
            setDrawValues(pontos.size <= 10)
            valueTextSize = 10f
            valueTextColor = corTexto
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
            }
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
            setDrawFilled(true)
            fillColor = corBrand
            fillAlpha = 45
        }

        chartDesempenho.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = corSecundario
            textSize = 10f
            granularity = 1f
            labelCount = pontos.size.coerceAtMost(7)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val idx = value.toInt()
                    return if (idx in pontos.indices) pontos[idx].label else ""
                }
            }
        }

        chartDesempenho.axisLeft.apply {
            removeAllLimitLines()
            setDrawGridLines(true)
            gridColor = corGrid
            textColor = corSecundario
            axisMinimum = 0f
            granularity = 1f

            if (mediaPeriodo > 0f) {
                val limitLine = LimitLine(
                    mediaPeriodo,
                    String.format(Locale.getDefault(), "Média %.1f", mediaPeriodo)
                ).apply {
                    lineWidth = 1.5f
                    lineColor = Color.parseColor("#F59E0B")
                    enableDashedLine(10f, 8f, 0f)
                    labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                    textSize = 10f
                    textColor = Color.parseColor("#D97706")
                }
                addLimitLine(limitLine)
            }
        }
        chartDesempenho.axisRight.isEnabled = false

        chartDesempenho.data = LineData(dataSet)
        chartDesempenho.animateY(500)
        chartDesempenho.invalidate()
    }

    private fun renderizarTurnos(turnos: List<TurnoStat>) {
        if (turnos.isEmpty()) {
            chartTurnos.clear()
            return
        }

        val corTexto = ContextCompat.getColor(this, R.color.text_primary)
        val corSecundario = ContextCompat.getColor(this, R.color.text_secondary)
        val corGrid = ContextCompat.getColor(this, R.color.divider_color)
        val corPico = Color.parseColor("#F59E0B")
        val corPadrao = ContextCompat.getColor(this, R.color.brand_primary)

        val turnoPico = turnos.firstOrNull { it.isPico }
        txtTurnoPicoSubtitulo.text = if (turnoPico != null && turnoPico.quantidade > 0) {
            "Horário de pico: ${turnoPico.nome} (${turnoPico.faixaLabel}) com ${turnoPico.quantidade} acionamento(s)"
        } else {
            "Distribuição no fuso local por turno do dia"
        }

        val entries = turnos.mapIndexed { idx, t ->
            BarEntry(idx.toFloat(), t.quantidade.toFloat())
        }
        val cores = turnos.map { if (it.isPico) corPico else corPadrao }

        val dataSet = BarDataSet(entries, "Turnos").apply {
            colors = cores
            valueTextColor = corTexto
            valueTextSize = 11f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = value.toInt().toString()
            }
        }

        chartTurnos.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            labelCount = turnos.size
            textColor = corSecundario
            textSize = 11f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val idx = value.toInt()
                    return if (idx in turnos.indices) turnos[idx].nome else ""
                }
            }
        }

        chartTurnos.axisLeft.apply {
            axisMinimum = 0f
            granularity = 1f
            textColor = corSecundario
            gridColor = corGrid
        }
        chartTurnos.axisRight.isEnabled = false

        chartTurnos.data = BarData(dataSet).apply { barWidth = 0.55f }
        chartTurnos.animateY(500)
        chartTurnos.invalidate()
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações de conclusão de exportação de relatórios PDF"
                enableLights(true)
                lightColor = Color.BLUE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Geração programática de documento PDF utilizando PdfDocument nativo com os dados do período selecionado.
     */
    private fun exportarRelatorioPdf() {
        val snap = snapshotAtual
        if (snap == null) {
            Toast.makeText(this, "Sem dados no período selecionado para exportar.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var pdfDoc: PdfDocument? = null
            try {
                pdfDoc = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas = page.canvas

                val paintHeaderBg = Paint().apply { color = Color.parseColor("#2563EB") }
                val paintHeaderWhite = Paint().apply {
                    color = Color.WHITE
                    textSize = 18f
                    isFakeBoldText = true
                }
                val paintHeaderSub = Paint().apply {
                    color = Color.parseColor("#DBEAFE")
                    textSize = 11f
                }
                val paintSecao = Paint().apply {
                    color = Color.parseColor("#1E293B")
                    textSize = 14f
                    isFakeBoldText = true
                }
                val paintTexto = Paint().apply {
                    color = Color.parseColor("#334155")
                    textSize = 12f
                }
                val paintMuted = Paint().apply {
                    color = Color.parseColor("#64748B")
                    textSize = 10f
                }
                val paintLinha = Paint().apply {
                    color = Color.parseColor("#E2E8F0")
                    strokeWidth = 1f
                }
                val paintCardBg = Paint().apply { color = Color.parseColor("#F8FAFC") }
                val paintBigNum = Paint().apply {
                    color = Color.parseColor("#2563EB")
                    textSize = 20f
                    isFakeBoldText = true
                }

                // 1. Cabeçalho
                canvas.drawRect(0f, 0f, 595f, 100f, paintHeaderBg)
                canvas.drawText("PROJETO C.A.S.A. - RELATÓRIO DE DESEMPENHO", 36f, 48f, paintHeaderWhite)
                val dataFormatada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
                canvas.drawText("Período: ${snap.periodo.label} • Emitido em: $dataFormatada", 36f, 74f, paintHeaderSub)

                // 2. Identificação
                val user = FirebaseAuth.getInstance().currentUser
                val nomeUsuario = user?.displayName ?: "Usuário C.A.S.A."
                val emailUsuario = user?.email ?: "Não informado"
                val macHardware = snap.insights.macAtivo ?: "Não identificado"

                canvas.drawText("Identificação:", 36f, 130f, paintSecao)
                canvas.drawText("Usuário: $nomeUsuario ($emailUsuario)", 44f, 150f, paintTexto)
                canvas.drawText("Hardware ESP32: $macHardware (${snap.insights.ultimoDisparoFisicoTexto})", 44f, 170f, paintTexto)
                canvas.drawLine(36f, 188f, 559f, 188f, paintLinha)

                // 3. KPIs Principais
                canvas.drawText("Resumo do Período (${snap.periodo.label}):", 36f, 212f, paintSecao)
                canvas.drawRoundRect(36f, 224f, 286f, 286f, 8f, 8f, paintCardBg)
                canvas.drawText("Total de Comunicações", 50f, 246f, paintMuted)
                canvas.drawText("${snap.kpi.totalComunicacoes} acionamentos", 50f, 272f, paintBigNum)

                canvas.drawRoundRect(306f, 224f, 559f, 286f, 8f, 8f, paintCardBg)
                canvas.drawText("Média por Dia Ativo", 320f, 246f, paintMuted)
                canvas.drawText(String.format(Locale.getDefault(), "%.1f / dia", snap.kpi.mediaDiariaAtiva), 320f, 272f, paintBigNum)

                // 4. Top 5 Ações
                var yPos = 318f
                canvas.drawText("Top 5 Ações Mais Acionadas:", 36f, yPos, paintSecao)
                yPos += 24f
                for (acao in snap.topAcoes) {
                    canvas.drawText("• ${acao.botaoNome} (${acao.categoriaNome})", 44f, yPos, paintTexto)
                    canvas.drawText("${acao.quantidade}x", 320f, yPos, paintTexto)
                    yPos += 22f
                }

                // 5. Insights Comportamentais
                yPos += 14f
                canvas.drawLine(36f, yPos, 559f, yPos, paintLinha)
                yPos += 24f
                canvas.drawText("Resumo Inteligente:", 36f, yPos, paintSecao)
                yPos += 22f
                canvas.drawText("• Uso no Hardware: ESP32 ${snap.kpi.percentDispositivo}% vs App ${snap.kpi.percentApp}%", 44f, yPos, paintTexto)
                yPos += 20f
                canvas.drawText("• Diversidade de vocabulário: ${snap.insights.diversidadeVocabulario} botão(ões) único(s)", 44f, yPos, paintTexto)
                yPos += 20f
                snap.insights.alertaUrgencia?.let { urg ->
                    canvas.drawText("• Urgência: '${urg.botaoNome}' acionado ${urg.quantidadeSeguida}x seguidas às ${urg.horarioFormatado}", 44f, yPos, paintTexto)
                    yPos += 20f
                }
                canvas.drawText("• Botão menos acionado: ${snap.menosAcionadoNome}", 44f, yPos, paintTexto)

                // Rodapé
                canvas.drawLine(36f, 780f, 559f, 780f, paintLinha)
                canvas.drawText("Documento gerado pelo aplicativo Projeto C.A.S.A. (Android Native / Kotlin)", 36f, 800f, paintMuted)
                canvas.drawText("Página 1 de 1", 500f, 800f, paintMuted)

                pdfDoc.finishPage(page)

                val nomeArquivo = "relatorio_desempenho_${System.currentTimeMillis()}.pdf"
                val uriSalva = salvarPdfEmDownloads(this@DesempenhoActivity, pdfDoc, nomeArquivo)

                withContext(Dispatchers.Main) {
                    if (uriSalva != null) {
                        dispararNotificacaoSucesso(nomeArquivo, uriSalva, snap.periodo.label)
                        Toast.makeText(
                            this@DesempenhoActivity,
                            "Relatório salvo com sucesso na pasta Downloads:\n$nomeArquivo",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this@DesempenhoActivity, "Não foi possível gravar o arquivo do relatório.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DesempenhoActivity, "Erro ao exportar PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                pdfDoc?.close()
            }
        }
    }

    private fun salvarPdfEmDownloads(context: Context, pdfDocument: PdfDocument, nomeArquivo: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nomeArquivo)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ProjetoCASA")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            var uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            }

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { os ->
                    pdfDocument.writeTo(os)
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } else {
            val dirDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dirApp = File(dirDownloads, "ProjetoCASA")
            if (!dirApp.exists()) dirApp.mkdirs()

            val arquivo = File(dirApp, nomeArquivo)
            FileOutputStream(arquivo).use { os ->
                pdfDocument.writeTo(os)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.provider", arquivo)
        }
    }

    private fun dispararNotificacaoSucesso(nomeArquivo: String, fileUri: Uri, periodoLabel: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/pdf")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Relatório C.A.S.A. Gerado")
            .setContentText("Arquivo salvo em Downloads: $nomeArquivo")
            .setStyle(NotificationCompat.BigTextStyle().bigText("O relatório de desempenho ($periodoLabel) foi salvo em Downloads/ProjetoCASA/$nomeArquivo. Toque para visualizar."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }
}
