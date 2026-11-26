package fatec.labweb.projetocasa.view

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import fatec.labweb.projetocasa.R
import fatec.labweb.projetocasa.adapter.NotificacoesAdapter
import fatec.labweb.projetocasa.model.DadosDispositivo
import fatec.labweb.projetocasa.model.Notificacao
import fatec.labweb.projetocasa.service.NotificacaoMonitor
import fatec.labweb.projetocasa.util.SoundManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * NotificacoesActivity: Central de Notificações em Tempo Real focada em saúde do hardware,
 * bateria, conectividade Wi-Fi, ciclo de vida de pareamento e resumo diário consolidado.
 */
class NotificacoesActivity : BaseDrawerActivity() {

    override fun getContentLayoutId(): Int = R.layout.activity_notifications
    override fun getCurrentMenuItemId(): Int = -1

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var rvNotificacoes: RecyclerView
    private lateinit var adapter: NotificacoesAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutSemNotificacoes: View
    private lateinit var btnFechar: MaterialButton
    private lateinit var btnLimpar: MaterialButton
    private lateinit var chipGroupFiltro: ChipGroup

    // Views do Card de Status do Dispositivo
    private lateinit var cardStatusDispositivo: MaterialCardView
    private lateinit var dotStatusDispositivo: View
    private lateinit var txtStatusDispositivoTitulo: TextView
    private lateinit var txtStatusDispositivoDetalhe: TextView
    private lateinit var imgIconeBateria: ImageView

    // Registros de Listeners para limpeza de memória
    private var listenerNotificacoes: ListenerRegistration? = null
    private var listenerDispositivos: ListenerRegistration? = null
    private var listenerAcionamentos: ListenerRegistration? = null

    // Cache local de listas em tempo real
    private var notificacoesFirestore = mutableListOf<Notificacao>()
    private var notificacoesDispositivo = mutableListOf<Notificacao>()
    private var resumoAtividadeHoje: Notificacao? = null
    private var filtroAtual: String = "todas"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initViews()
        setupRecyclerView()
        setupListeners()
        iniciarObservadoresEmTempoReal()
    }

    override fun onDestroy() {
        super.onDestroy()
        removerListeners()
    }

    private fun initViews() {
        rvNotificacoes = findViewById(R.id.rvNotificacoes)
        progressBar = findViewById(R.id.progressBarNotificacoes)
        layoutSemNotificacoes = findViewById(R.id.layoutSemNotificacoes)
        btnFechar = findViewById(R.id.btnFecharNotificacoes)
        btnLimpar = findViewById(R.id.btnLimparNotificacoes)
        chipGroupFiltro = findViewById(R.id.chipGroupFiltroNotificacoes)

        cardStatusDispositivo = findViewById(R.id.cardStatusDispositivo)
        dotStatusDispositivo = findViewById(R.id.dotStatusDispositivo)
        txtStatusDispositivoTitulo = findViewById(R.id.txtStatusDispositivoTitulo)
        txtStatusDispositivoDetalhe = findViewById(R.id.txtStatusDispositivoDetalhe)
        imgIconeBateria = findViewById(R.id.imgIconeBateria)
    }

    private fun setupRecyclerView() {
        adapter = NotificacoesAdapter(
            itens = emptyList(),
            onItemClick = { notif ->
                if (notif.tipo == Notificacao.TIPO_RESUMO_ATIVIDADE) {
                    navigateTo(HistoricoActivity::class.java)
                } else {
                    marcarNotificacaoComoLida(notif)
                }
            },
            onExcluirClick = { notif ->
                excluirNotificacao(notif)
            }
        )
        rvNotificacoes.layoutManager = LinearLayoutManager(this)
        rvNotificacoes.adapter = adapter
    }

    private fun setupListeners() {
        btnFechar.setOnClickListener { view ->
            SoundManager.dispararAudioCue(view)
            finish()
        }

        btnLimpar.setOnClickListener { view ->
            SoundManager.dispararAudioCue(view)
            limparTodasNotificacoes()
        }

        chipGroupFiltro.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: R.id.chipFiltroTodas
            filtroAtual = when (selectedId) {
                R.id.chipFiltroAlertas -> "alertas"
                R.id.chipFiltroDispositivo -> "dispositivo"
                R.id.chipFiltroSistema -> "sistema"
                else -> "todas"
            }
            consolidarEAtualizarLista()
        }
    }

    /**
     * Inicia ouvintes em tempo real para notificações persistidas, saúde dos dispositivos
     * e resumo diário consolidado de acionamentos (sem poluir a lista com toques individuais).
     */
    private fun iniciarObservadoresEmTempoReal() {
        val userId = auth.currentUser?.uid
        if (userId.isNullOrBlank()) {
            carregarNotificacoesDemonstracao()
            return
        }

        progressBar.visibility = View.VISIBLE

        // 1. Notificações persistidas: /usuarios/{uid}/notificacoes
        listenerNotificacoes = db.collection("usuarios").document(userId)
            .collection("notificacoes")
            .addSnapshotListener { snapshots, error ->
                progressBar.visibility = View.GONE
                if (error != null) return@addSnapshotListener

                val lista = mutableListOf<Notificacao>()
                if (snapshots != null) {
                    for (doc in snapshots) {
                        try {
                            val notif = doc.toObject(Notificacao::class.java).apply {
                                id = doc.id
                            }
                            // Ignora eventuais registros legados de acionamento individual na coleção
                            val tipoLower = notif.tipo.lowercase(Locale.ROOT)
                            if (tipoLower !in setOf("clique", "interacao", "acionamento")) {
                                lista.add(notif)
                            }
                        } catch (_: Exception) {}
                    }
                }
                notificacoesFirestore = lista
                consolidarEAtualizarLista()
            }

        // 2. Saúde de todos os dispositivos vinculados: /dispositivos (owner_uid == userId)
        listenerDispositivos = db.collection("dispositivos")
            .whereEqualTo("owner_uid", userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

                val dispositivos = snapshots.documents.mapNotNull { doc ->
                    try {
                        doc.toObject(DadosDispositivo::class.java)?.let { d ->
                            if (d.id.isBlank()) d.copy(id = doc.id) else d
                        }
                    } catch (_: Exception) {
                        null
                    }
                }

                if (dispositivos.isNotEmpty()) {
                    atualizarCardDispositivos(dispositivos)
                } else {
                    cardStatusDispositivo.visibility = View.GONE
                }

                notificacoesDispositivo = NotificacaoMonitor.avaliarDispositivos(
                    context = this,
                    dispositivos = dispositivos,
                    dispararAlertaLocal = true
                ).toMutableList()
                consolidarEAtualizarLista()
            }

        // 3. Resumo diário consolidado (no máximo 1 card clicável para o Histórico)
        listenerAcionamentos = db.collection("usuarios").document(userId)
            .collection("acionamentos")
            .orderBy("acionado_em", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

                val inicioDiaMillis = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                var totalHoje = 0
                var ultimoBotao = ""
                var ultimaCategoria = ""
                var ultimoTimestamp = 0L

                for (doc in snapshots) {
                    val acionadoEm = doc.getString("acionado_em") ?: ""
                    val dataMillis = parseIsoParaMillis(acionadoEm)
                    if (dataMillis >= inicioDiaMillis) {
                        totalHoje++
                        if (ultimoTimestamp == 0L) {
                            ultimoBotao = doc.getString("botao_nome") ?: "Botão"
                            ultimaCategoria = doc.getString("categoria_nome") ?: "Geral"
                            ultimoTimestamp = dataMillis
                        }
                    }
                }

                resumoAtividadeHoje = if (totalHoje > 0) {
                    val plural = if (totalHoje == 1) "acionamento" else "acionamentos"
                    Notificacao(
                        id = "resumo_atividade_hoje",
                        titulo = "Atividade hoje: $totalHoje $plural",
                        mensagem = "Último: $ultimoBotao ($ultimaCategoria) • Toque para abrir o Histórico completo.",
                        data = ultimoTimestamp,
                        lida = true,
                        tipo = Notificacao.TIPO_RESUMO_ATIVIDADE,
                        severidade = Notificacao.SEVERIDADE_INFO,
                        persistente = true
                    )
                } else {
                    null
                }
                consolidarEAtualizarLista()
            }
    }

    /**
     * Mescla alertas de hardware, notificações persistidas e o card único de resumo diário,
     * aplicando o filtro selecionado e priorizando alertas críticos no topo.
     */
    private fun consolidarEAtualizarLista() {
        val mapaConsolidado = LinkedHashMap<String, Notificacao>()

        for (item in notificacoesDispositivo) {
            mapaConsolidado[item.id] = item
        }
        for (item in notificacoesFirestore) {
            mapaConsolidado[item.id] = item
        }
        resumoAtividadeHoje?.let { resumo ->
            mapaConsolidado[resumo.id] = resumo
        }

        val filtrada = mapaConsolidado.values.filter { notif ->
            filtroAtual == "todas" || notif.categoriaFiltro == filtroAtual
        }

        val listaFinal = filtrada.sortedWith(
            compareByDescending<Notificacao> { it.isAlertaCritico }
                .thenByDescending { it.timestampMillis }
        )

        adapter.atualizarLista(listaFinal)

        val vazia = listaFinal.isEmpty()
        layoutSemNotificacoes.visibility = if (vazia) View.VISIBLE else View.GONE
        rvNotificacoes.visibility = if (vazia) View.GONE else View.VISIBLE
        btnLimpar.visibility = if (notificacoesFirestore.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun atualizarCardDispositivos(dispositivos: List<DadosDispositivo>) {
        cardStatusDispositivo.visibility = View.VISIBLE

        val total = dispositivos.size
        val onlineList = dispositivos.filter { it.isOnline }
        val principal = dispositivos.firstOrNull { !it.isOnline || it.bateriaEfetiva <= NotificacaoMonitor.BATERIA_BAIXA_LIMITE }
            ?: dispositivos.first()

        val bateria = principal.bateriaEfetiva.coerceIn(0, 100)
        val todosOnline = onlineList.size == total

        if (todosOnline) {
            txtStatusDispositivoTitulo.text = if (total == 1) "Dispositivo Conectado" else "$total Dispositivos Conectados"
            dotStatusDispositivo.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            cardStatusDispositivo.setCardBackgroundColor(Color.parseColor("#1E3A8A"))
        } else {
            txtStatusDispositivoTitulo.text = if (total == 1) {
                "Dispositivo Desconectado"
            } else {
                "${total - onlineList.size} de $total Dispositivo(s) Offline"
            }
            dotStatusDispositivo.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            cardStatusDispositivo.setCardBackgroundColor(Color.parseColor("#374151"))
        }

        val nomeDisp = principal.nome.ifBlank { "Dispositivo C.A.S.A." }
        val statusTexto = if (principal.isOnline) "Online" else "Offline"
        txtStatusDispositivoDetalhe.text = "$nomeDisp • Bateria: $bateria% • $statusTexto"

        val corBateria = when {
            bateria > NotificacaoMonitor.BATERIA_BAIXA_LIMITE -> Color.parseColor("#10B981")
            bateria > NotificacaoMonitor.BATERIA_CRITICA_LIMITE -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#EF4444")
        }
        imgIconeBateria.imageTintList = ColorStateList.valueOf(corBateria)
    }

    private fun marcarNotificacaoComoLida(notificacao: Notificacao) {
        val userId = auth.currentUser?.uid ?: return
        if (notificacao.persistente) return

        db.collection("usuarios").document(userId)
            .collection("notificacoes").document(notificacao.id)
            .update("lida", true)
            .addOnFailureListener { /* Regras Firestore v2 */ }
    }

    private fun excluirNotificacao(notificacao: Notificacao) {
        if (notificacao.persistente) return
        adapter.removerNotificacao(notificacao.id)
        notificacoesFirestore.removeAll { it.id == notificacao.id }
        val userId = auth.currentUser?.uid ?: return

        db.collection("usuarios").document(userId)
            .collection("notificacoes").document(notificacao.id)
            .delete()
            .addOnFailureListener { /* Regras Firestore v2 */ }

        btnLimpar.visibility = if (notificacoesFirestore.isNotEmpty()) View.VISIBLE else View.GONE
        Toast.makeText(this, "Notificação removida", Toast.LENGTH_SHORT).show()
    }

    private fun limparTodasNotificacoes() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            for (notif in notificacoesFirestore) {
                db.collection("usuarios").document(userId)
                    .collection("notificacoes").document(notif.id)
                    .delete()
            }
        }
        notificacoesFirestore.clear()
        consolidarEAtualizarLista()
        Toast.makeText(this, "Avisos dispensáveis limpos", Toast.LENGTH_SHORT).show()
    }

    private fun removerListeners() {
        listenerNotificacoes?.remove()
        listenerNotificacoes = null
        listenerDispositivos?.remove()
        listenerDispositivos = null
        listenerAcionamentos?.remove()
        listenerAcionamentos = null
    }

    private fun parseIsoParaMillis(isoString: String): Long {
        if (isoString.isBlank()) return 0L
        val formatos = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        )
        for (fmt in formatos) {
            try {
                val parsed = fmt.parse(isoString)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun carregarNotificacoesDemonstracao() {
        progressBar.visibility = View.GONE
        notificacoesDispositivo = mutableListOf(
            Notificacao(
                id = "demo_bat",
                titulo = "Pouca bateria em Dispositivo C.A.S.A. (20%)",
                mensagem = "A carga está baixa. Recomendamos recarregar o aparelho em breve.",
                data = System.currentTimeMillis() - 120_000,
                lida = false,
                tipo = Notificacao.TIPO_BATERIA_BAIXA,
                nivelBateria = 20,
                severidade = Notificacao.SEVERIDADE_AVISO,
                persistente = true
            ),
            Notificacao(
                id = "demo_disp",
                titulo = "Dispositivo C.A.S.A. conectado",
                mensagem = "Operacional (Online) • Bateria: 95%",
                data = System.currentTimeMillis() - 600_000,
                lida = true,
                tipo = Notificacao.TIPO_DISPOSITIVO,
                nivelBateria = 95,
                severidade = Notificacao.SEVERIDADE_SUCESSO,
                persistente = true
            )
        )
        resumoAtividadeHoje = Notificacao(
            id = "demo_resumo",
            titulo = "Atividade hoje: 6 acionamentos",
            mensagem = "Último: Água (Geral) • Toque para abrir o Histórico completo.",
            data = System.currentTimeMillis() - 300_000,
            lida = true,
            tipo = Notificacao.TIPO_RESUMO_ATIVIDADE,
            severidade = Notificacao.SEVERIDADE_INFO,
            persistente = true
        )
        consolidarEAtualizarLista()
    }
}
