package fatec.labweb.projetocasa.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import fatec.labweb.projetocasa.R
import fatec.labweb.projetocasa.model.Notificacao
import fatec.labweb.projetocasa.util.SoundManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NotificacoesAdapter: Adaptador de RecyclerView para exibição de alertas de hardware,
 * conectividade, bateria, Wi-Fi e avisos do sistema com diferenciação visual clara.
 */
class NotificacoesAdapter(
    private var itens: List<Notificacao> = emptyList(),
    private val onItemClick: ((Notificacao) -> Unit)? = null,
    private val onExcluirClick: ((Notificacao) -> Unit)? = null
) : RecyclerView.Adapter<NotificacoesAdapter.NotificacaoViewHolder>() {

    fun atualizarLista(novaLista: List<Notificacao>) {
        this.itens = novaLista
        notifyDataSetChanged()
    }

    fun removerNotificacao(notificacaoId: String) {
        val index = itens.indexOfFirst { it.id == notificacaoId }
        if (index != -1) {
            val lista = itens.toMutableList()
            lista.removeAt(index)
            this.itens = lista
            notifyItemRemoved(index)
        }
    }

    fun obterLista(): List<Notificacao> = itens

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificacaoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notificacao, parent, false)
        return NotificacaoViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificacaoViewHolder, position: Int) {
        holder.itemView.isSoundEffectsEnabled = true
        holder.bind(itens[position])
    }

    override fun getItemCount(): Int = itens.size

    inner class NotificacaoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardNotificacao: MaterialCardView = itemView.findViewById(R.id.cardNotificacao)
        private val flIcone: FrameLayout = itemView.findViewById(R.id.flIconeNotificacao)
        private val imgIcone: ImageView = itemView.findViewById(R.id.imgIconeNotificacao)
        private val txtTitulo: TextView = itemView.findViewById(R.id.txtTituloNotificacao)
        private val txtMensagem: TextView = itemView.findViewById(R.id.txtMensagemNotificacao)
        private val txtTagTipo: TextView = itemView.findViewById(R.id.txtTagTipo)
        private val txtData: TextView = itemView.findViewById(R.id.txtDataNotificacao)
        private val dotNaoLida: View = itemView.findViewById(R.id.dotNaoLida)
        private val btnExcluir: ImageButton = itemView.findViewById(R.id.btnExcluirNotificacao)

        fun bind(item: Notificacao) {
            txtTitulo.text = item.titulo.ifBlank { "Notificação" }
            txtMensagem.text = item.mensagem.ifBlank { "Sem detalhes adicionais." }
            txtData.text = formatarTimestamp(item.timestampMillis)
            dotNaoLida.visibility = if (!item.lida) View.VISIBLE else View.GONE
            btnExcluir.visibility = if (item.persistente) View.GONE else View.VISIBLE

            when (item.tipo.lowercase(Locale.ROOT)) {
                Notificacao.TIPO_BATERIA_CRITICA -> {
                    aplicarEstilo(
                        strokeHex = "#EF4444",
                        strokeWidth = 2,
                        bgIconHex = "#FEE2E2",
                        iconRes = android.R.drawable.ic_lock_idle_low_battery,
                        iconTintHex = "#DC2626",
                        tagTexto = if (item.nivelBateria != null) "BATERIA • ${item.nivelBateria}%" else "BATERIA CRÍTICA",
                        tagBgHex = "#FEE2E2",
                        tagTextHex = "#991B1B"
                    )
                }

                Notificacao.TIPO_BATERIA_BAIXA -> {
                    aplicarEstilo(
                        strokeHex = "#F59E0B",
                        strokeWidth = 2,
                        bgIconHex = "#FEF3C7",
                        iconRes = android.R.drawable.ic_lock_idle_low_battery,
                        iconTintHex = "#D97706",
                        tagTexto = if (item.nivelBateria != null) "BATERIA • ${item.nivelBateria}%" else "POUCA BATERIA",
                        tagBgHex = "#FEF3C7",
                        tagTextHex = "#92400E"
                    )
                }

                Notificacao.TIPO_DESCONECTADO -> {
                    aplicarEstilo(
                        strokeHex = "#EF4444",
                        strokeWidth = 2,
                        bgIconHex = "#FEE2E2",
                        iconRes = android.R.drawable.stat_notify_error,
                        iconTintHex = "#DC2626",
                        tagTexto = "DESCONECTADO",
                        tagBgHex = "#FEE2E2",
                        tagTextHex = "#991B1B"
                    )
                }

                Notificacao.TIPO_WIFI_ERRO -> {
                    aplicarEstilo(
                        strokeHex = "#EF4444",
                        strokeWidth = 2,
                        bgIconHex = "#FEE2E2",
                        iconRes = android.R.drawable.stat_notify_error,
                        iconTintHex = "#DC2626",
                        tagTexto = "ERRO WI-FI",
                        tagBgHex = "#FEE2E2",
                        tagTextHex = "#991B1B"
                    )
                }

                Notificacao.TIPO_WIFI_FRACO -> {
                    aplicarEstilo(
                        strokeHex = "#F59E0B",
                        strokeWidth = 2,
                        bgIconHex = "#FEF3C7",
                        iconRes = android.R.drawable.ic_dialog_alert,
                        iconTintHex = "#D97706",
                        tagTexto = "WI-FI FRACO",
                        tagBgHex = "#FEF3C7",
                        tagTextHex = "#92400E"
                    )
                }

                Notificacao.TIPO_WIFI_PENDENTE -> {
                    aplicarEstilo(
                        strokeHex = "#3B82F6",
                        strokeWidth = 1,
                        bgIconHex = "#DBEAFE",
                        iconRes = android.R.drawable.stat_notify_sync,
                        iconTintHex = "#2563EB",
                        tagTexto = "WI-FI",
                        tagBgHex = "#DBEAFE",
                        tagTextHex = "#1E40AF"
                    )
                }

                Notificacao.TIPO_DISPOSITIVO, Notificacao.TIPO_PAREAMENTO, "conexao" -> {
                    aplicarEstilo(
                        strokeHex = "#10B981",
                        strokeWidth = 2,
                        bgIconHex = "#D1FAE5",
                        iconRes = android.R.drawable.stat_sys_data_bluetooth,
                        iconTintHex = "#059669",
                        tagTexto = if (item.nivelBateria != null) "DISPOSITIVO • ${item.nivelBateria}%" else "DISPOSITIVO",
                        tagBgHex = "#D1FAE5",
                        tagTextHex = "#065F46"
                    )
                }

                Notificacao.TIPO_DESVINCULACAO -> {
                    aplicarEstilo(
                        strokeHex = "#94A3B8",
                        strokeWidth = 1,
                        bgIconHex = "#F1F5F9",
                        iconRes = android.R.drawable.ic_menu_delete,
                        iconTintHex = "#475569",
                        tagTexto = "DESVINCULADO",
                        tagBgHex = "#F1F5F9",
                        tagTextHex = "#334155"
                    )
                }

                Notificacao.TIPO_SINCRONIZACAO -> {
                    aplicarEstilo(
                        strokeHex = "#3B82F6",
                        strokeWidth = 1,
                        bgIconHex = "#DBEAFE",
                        iconRes = android.R.drawable.stat_notify_sync,
                        iconTintHex = "#2563EB",
                        tagTexto = "SINCRONIZAÇÃO",
                        tagBgHex = "#DBEAFE",
                        tagTextHex = "#1E40AF"
                    )
                }

                Notificacao.TIPO_RESUMO_ATIVIDADE -> {
                    aplicarEstilo(
                        strokeHex = "#2563EB",
                        strokeWidth = 1,
                        bgIconHex = "#EEF2FF",
                        iconRes = android.R.drawable.ic_menu_recent_history,
                        iconTintHex = "#2563EB",
                        tagTexto = "RESUMO HOJE",
                        tagBgHex = "#DBEAFE",
                        tagTextHex = "#1E40AF"
                    )
                }

                "bateria", Notificacao.TIPO_ALERTA, "aviso" -> {
                    aplicarEstilo(
                        strokeHex = "#EF4444",
                        strokeWidth = 2,
                        bgIconHex = "#FEE2E2",
                        iconRes = android.R.drawable.stat_notify_error,
                        iconTintHex = "#DC2626",
                        tagTexto = "ALERTA",
                        tagBgHex = "#FEE2E2",
                        tagTextHex = "#991B1B"
                    )
                }

                else -> {
                    aplicarEstilo(
                        strokeHex = "#E2E8F0",
                        strokeWidth = 1,
                        bgIconHex = "#EEF2FF",
                        iconRes = android.R.drawable.ic_popup_reminder,
                        iconTintHex = "#2563EB",
                        tagTexto = "SISTEMA",
                        tagBgHex = "#DBEAFE",
                        tagTextHex = "#1E40AF"
                    )
                }
            }

            itemView.setOnClickListener { view ->
                view.playSoundEffect(SoundEffectConstants.CLICK)
                SoundManager.dispararAudioCue(view)
                onItemClick?.invoke(item)
            }

            btnExcluir.setOnClickListener { view ->
                view.playSoundEffect(SoundEffectConstants.CLICK)
                SoundManager.dispararAudioCue(view)
                onExcluirClick?.invoke(item)
            }
        }

        private fun aplicarEstilo(
            strokeHex: String,
            strokeWidth: Int,
            bgIconHex: String,
            iconRes: Int,
            iconTintHex: String,
            tagTexto: String,
            tagBgHex: String,
            tagTextHex: String
        ) {
            cardNotificacao.strokeColor = Color.parseColor(strokeHex)
            cardNotificacao.strokeWidth = strokeWidth
            flIcone.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bgIconHex))
            imgIcone.setImageResource(iconRes)
            imgIcone.imageTintList = ColorStateList.valueOf(Color.parseColor(iconTintHex))
            txtTagTipo.text = tagTexto
            txtTagTipo.backgroundTintList = ColorStateList.valueOf(Color.parseColor(tagBgHex))
            txtTagTipo.setTextColor(Color.parseColor(tagTextHex))
        }

        private fun formatarTimestamp(timestamp: Long): String {
            if (timestamp <= 0L) return "Recentemente"
            val agora = System.currentTimeMillis()
            val diferenca = agora - timestamp

            return when {
                diferenca < 60_000L -> "Agora mesmo"
                diferenca < 3_600_000L -> "Há ${diferenca / 60_000L} min"
                diferenca < 86_400_000L -> "Há ${diferenca / 3_600_000L} h"
                else -> {
                    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    sdf.format(Date(timestamp))
                }
            }
        }
    }
}
