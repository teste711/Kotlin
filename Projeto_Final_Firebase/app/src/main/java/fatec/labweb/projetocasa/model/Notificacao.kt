package fatec.labweb.projetocasa.model

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Notificacao: Modelo de dados para alertas e avisos em tempo real do Projeto C.A.S.A.
 * Mapeia eventos da API Web (FastAPI), telemetria de dispositivos ESP32 e eventos locais.
 */
@IgnoreExtraProperties
data class Notificacao(
    var id: String = "",
    val titulo: String = "",
    val mensagem: String = "",
    val data: Long = 0L,              // timestamp em milissegundos
    val lida: Boolean = false,
    val tipo: String = TIPO_INFO,     // constantes em companion object
    val dispositivoId: String = "",   // MAC ou ID do dispositivo
    val nivelBateria: Int? = null,    // Percentual de bateria se aplicável
    val statusOnline: Boolean? = null,// Status online do hardware
    val timestamp: Any? = null,
    var criada_em: String = "",       // Formato ISO 8601 da API Web (ex: 2026-04-12T15:30:00Z)
    var meta: Map<String, Any>? = null,
    @get:Exclude
    val severidade: String = SEVERIDADE_INFO, // "critico", "aviso", "sucesso", "info"
    @get:Exclude
    val persistente: Boolean = false          // true para alertas de estado ativo do hardware
) {
    companion object {
        // Tipos de alerta de saúde/conectividade do hardware
        const val TIPO_BATERIA_CRITICA = "bateria_critica"
        const val TIPO_BATERIA_BAIXA = "bateria_baixa"
        const val TIPO_DESCONECTADO = "desconectado"
        const val TIPO_WIFI_ERRO = "wifi_erro"
        const val TIPO_WIFI_FRACO = "wifi_fraco"
        const val TIPO_WIFI_PENDENTE = "wifi_pendente"
        const val TIPO_DISPOSITIVO = "dispositivo"
        const val TIPO_SEM_DISPOSITIVO = "sem_dispositivo"

        // Tipos de ciclo de vida / sistema
        const val TIPO_PAREAMENTO = "pareamento"
        const val TIPO_DESVINCULACAO = "desvinculacao"
        const val TIPO_SINCRONIZACAO = "sincronizacao"
        const val TIPO_RESUMO_ATIVIDADE = "resumo_atividade"
        const val TIPO_INFO = "info"
        const val TIPO_ALERTA = "alerta"

        // Severidades visuais
        const val SEVERIDADE_CRITICO = "critico"
        const val SEVERIDADE_AVISO = "aviso"
        const val SEVERIDADE_SUCESSO = "sucesso"
        const val SEVERIDADE_INFO = "info"
    }

    @get:Exclude
    val timestampMillis: Long
        get() {
            if (data > 0L) return data
            if (criada_em.isNotBlank()) {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val limpa = criada_em.replace("Z", "").substringBefore(".")
                    return sdf.parse(limpa)?.time ?: 0L
                } catch (ignored: Exception) {}
            }
            return 0L
        }

    @get:Exclude
    val isAlertaCritico: Boolean
        get() = severidade == SEVERIDADE_CRITICO || tipo.lowercase(Locale.ROOT) in setOf(
            TIPO_BATERIA_CRITICA,
            TIPO_DESCONECTADO,
            TIPO_WIFI_ERRO,
            "bateria",
            "alerta",
            "aviso"
        )

    @get:Exclude
    val categoriaFiltro: String
        get() = when (tipo.lowercase(Locale.ROOT)) {
            TIPO_BATERIA_CRITICA, TIPO_BATERIA_BAIXA, TIPO_DESCONECTADO,
            TIPO_WIFI_ERRO, TIPO_WIFI_FRACO, "bateria", "alerta", "aviso" -> "alertas"

            TIPO_DISPOSITIVO, TIPO_PAREAMENTO, TIPO_DESVINCULACAO,
            TIPO_WIFI_PENDENTE, TIPO_SEM_DISPOSITIVO, "conexao" -> "dispositivo"

            else -> "sistema"
        }
}
