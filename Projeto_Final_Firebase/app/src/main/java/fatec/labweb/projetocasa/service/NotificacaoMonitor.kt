package fatec.labweb.projetocasa.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fatec.labweb.projetocasa.CasaApplication
import fatec.labweb.projetocasa.R
import fatec.labweb.projetocasa.model.DadosDispositivo
import fatec.labweb.projetocasa.model.Notificacao
import fatec.labweb.projetocasa.view.NotificacoesActivity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * NotificacaoMonitor: Avalia telemetria de dispositivos ESP32 e gera alertas estruturados
 * (bateria crítica/baixa, desconexão, erro/sinal Wi-Fi, pendência de sync), além de
 * disparar notificações locais do Android com deduplicação por transição de estado.
 */
object NotificacaoMonitor {

    private const val PREFS_MONITOR = "casa_notif_monitor"
    private const val CHANNEL_ALERTAS_ID = "casa_alertas_dispositivo_channel"
    private const val CHANNEL_ALERTAS_NAME = "Alertas do Dispositivo C.A.S.A."

    const val BATERIA_CRITICA_LIMITE = 15
    const val BATERIA_BAIXA_LIMITE = 25
    const val WIFI_FRACO_DBM = -80

    /**
     * Avalia todos os dispositivos vinculados e retorna a lista de alertas/status ativos.
     * Também dispara notificações locais do sistema quando há transição para estado de alerta.
     */
    fun avaliarDispositivos(
        context: Context,
        dispositivos: List<DadosDispositivo>,
        dispararAlertaLocal: Boolean = true
    ): List<Notificacao> {
        val resultado = mutableListOf<Notificacao>()
        val agora = System.currentTimeMillis()

        if (dispositivos.isEmpty()) {
            resultado.add(
                Notificacao(
                    id = "estado_sem_dispositivo",
                    titulo = "Nenhum dispositivo vinculado",
                    mensagem = "Pareie seu dispositivo C.A.S.A. para acompanhar bateria, Wi-Fi e status em tempo real.",
                    data = agora,
                    lida = true,
                    tipo = Notificacao.TIPO_SEM_DISPOSITIVO,
                    severidade = Notificacao.SEVERIDADE_INFO,
                    persistente = true
                )
            )
            return resultado
        }

        val prefs = context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        for (disp in dispositivos) {
            val chaveDisp = disp.mac.ifBlank { disp.id }.ifBlank { "padrao" }
            val nomeDisp = disp.nome.ifBlank { "Dispositivo C.A.S.A." }
            val bateria = disp.bateriaEfetiva.coerceIn(0, 100)
            val isOnline = disp.isOnline
            val refTimestamp = parseSyncMillis(disp.ultima_sincronizacao)
                ?: if (disp.ultimoClique > 0L) disp.ultimoClique else agora

            var possuiAlertaCritico = false

            // 1. Conectividade: Dispositivo desconectado (> 8 min sem sync)
            val estadoOfflineKey = "offline_$chaveDisp"
            val estavaOffline = prefs.getBoolean(estadoOfflineKey, false)
            if (!isOnline) {
                possuiAlertaCritico = true
                val notifOffline = Notificacao(
                    id = "estado_offline_$chaveDisp",
                    titulo = "$nomeDisp desconectado",
                    mensagem = "Sem comunicação há mais de 8 minutos. Verifique se o aparelho está ligado, com bateria e no alcance do Wi-Fi.",
                    data = refTimestamp,
                    lida = false,
                    tipo = Notificacao.TIPO_DESCONECTADO,
                    dispositivoId = disp.mac,
                    nivelBateria = bateria,
                    statusOnline = false,
                    severidade = Notificacao.SEVERIDADE_CRITICO,
                    persistente = true
                )
                resultado.add(notifOffline)

                if (dispararAlertaLocal && !estavaOffline) {
                    notificarSistema(context, notifOffline)
                }
                editor.putBoolean(estadoOfflineKey, true)
            } else {
                editor.putBoolean(estadoOfflineKey, false)
            }

            // 2. Bateria crítica (<= 15%) ou pouca bateria (16%..25%)
            val estadoBatKey = "bat_$chaveDisp"
            val ultimoEstadoBat = prefs.getString(estadoBatKey, "ok") ?: "ok"
            when {
                bateria <= BATERIA_CRITICA_LIMITE -> {
                    possuiAlertaCritico = true
                    val notifBatCritica = Notificacao(
                        id = "estado_bat_critica_$chaveDisp",
                        titulo = "Bateria crítica em $nomeDisp ($bateria%)",
                        mensagem = "Conecte o dispositivo ao carregador imediatamente para evitar desligamento.",
                        data = refTimestamp,
                        lida = false,
                        tipo = Notificacao.TIPO_BATERIA_CRITICA,
                        dispositivoId = disp.mac,
                        nivelBateria = bateria,
                        statusOnline = isOnline,
                        severidade = Notificacao.SEVERIDADE_CRITICO,
                        persistente = true
                    )
                    resultado.add(notifBatCritica)

                    if (dispararAlertaLocal && ultimoEstadoBat != "critica") {
                        notificarSistema(context, notifBatCritica)
                    }
                    editor.putString(estadoBatKey, "critica")
                }

                bateria <= BATERIA_BAIXA_LIMITE -> {
                    possuiAlertaCritico = true
                    val notifBatBaixa = Notificacao(
                        id = "estado_bat_baixa_$chaveDisp",
                        titulo = "Pouca bateria em $nomeDisp ($bateria%)",
                        mensagem = "A carga está baixa. Recomendamos recarregar o aparelho em breve.",
                        data = refTimestamp,
                        lida = false,
                        tipo = Notificacao.TIPO_BATERIA_BAIXA,
                        dispositivoId = disp.mac,
                        nivelBateria = bateria,
                        statusOnline = isOnline,
                        severidade = Notificacao.SEVERIDADE_AVISO,
                        persistente = true
                    )
                    resultado.add(notifBatBaixa)

                    if (dispararAlertaLocal && ultimoEstadoBat == "ok") {
                        notificarSistema(context, notifBatBaixa)
                    }
                    editor.putString(estadoBatKey, "baixa")
                }

                else -> {
                    editor.putString(estadoBatKey, "ok")
                }
            }

            // 3. Erro na aplicação de Wi-Fi
            val estadoWifiErroKey = "wifi_erro_$chaveDisp"
            val tinhaWifiErro = prefs.getBoolean(estadoWifiErroKey, false)
            val temErroWifi = disp.wifi_status.equals("erro", ignoreCase = true) || !disp.wifi_erro.isNullOrBlank()
            if (temErroWifi) {
                possuiAlertaCritico = true
                val detalheErro = disp.wifi_erro?.takeIf { it.isNotBlank() }
                    ?: "Não foi possível conectar à rede configurada. Revise o nome (SSID) e a senha."
                val notifWifiErro = Notificacao(
                    id = "estado_wifi_erro_$chaveDisp",
                    titulo = "Falha no Wi-Fi de $nomeDisp",
                    mensagem = detalheErro,
                    data = refTimestamp,
                    lida = false,
                    tipo = Notificacao.TIPO_WIFI_ERRO,
                    dispositivoId = disp.mac,
                    nivelBateria = bateria,
                    statusOnline = isOnline,
                    severidade = Notificacao.SEVERIDADE_CRITICO,
                    persistente = true
                )
                resultado.add(notifWifiErro)

                if (dispararAlertaLocal && !tinhaWifiErro) {
                    notificarSistema(context, notifWifiErro)
                }
                editor.putBoolean(estadoWifiErroKey, true)
            } else {
                editor.putBoolean(estadoWifiErroKey, false)
            }

            // 4. Sinal Wi-Fi fraco (quando online e RSSI disponível < -80 dBm)
            val rssi = disp.sinal_wifi
            if (isOnline && rssi != null && rssi < WIFI_FRACO_DBM) {
                val ssidInfo = disp.ssid?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                resultado.add(
                    Notificacao(
                        id = "estado_wifi_fraco_$chaveDisp",
                        titulo = "Sinal Wi-Fi fraco em $nomeDisp",
                        mensagem = "Sinal atual de $rssi dBm$ssidInfo. Aproxime o dispositivo do roteador para evitar quedas.",
                        data = refTimestamp,
                        lida = false,
                        tipo = Notificacao.TIPO_WIFI_FRACO,
                        dispositivoId = disp.mac,
                        nivelBateria = bateria,
                        statusOnline = true,
                        severidade = Notificacao.SEVERIDADE_AVISO,
                        persistente = true
                    )
                )
            }

            // 5. Atualização de Wi-Fi pendente de sincronização
            if (disp.wifi_versao > 0 && !disp.wifiAplicado && !temErroWifi) {
                resultado.add(
                    Notificacao(
                        id = "estado_wifi_pendente_$chaveDisp",
                        titulo = "Wi-Fi pendente em $nomeDisp",
                        mensagem = "Nova configuração de rede aguardando sincronização na próxima conexão do aparelho.",
                        data = refTimestamp,
                        lida = true,
                        tipo = Notificacao.TIPO_WIFI_PENDENTE,
                        dispositivoId = disp.mac,
                        nivelBateria = bateria,
                        statusOnline = isOnline,
                        severidade = Notificacao.SEVERIDADE_INFO,
                        persistente = true
                    )
                )
            }

            // 6. Status saudável (Online e sem alertas críticos)
            if (isOnline && !possuiAlertaCritico) {
                val redeTxt = disp.ssid?.takeIf { it.isNotBlank() }?.let { " • Wi-Fi: $it" } ?: ""
                resultado.add(
                    Notificacao(
                        id = "estado_online_$chaveDisp",
                        titulo = "$nomeDisp conectado",
                        mensagem = "Operacional (Online) • Bateria: $bateria%$redeTxt",
                        data = refTimestamp,
                        lida = true,
                        tipo = Notificacao.TIPO_DISPOSITIVO,
                        dispositivoId = disp.mac,
                        nivelBateria = bateria,
                        statusOnline = true,
                        severidade = Notificacao.SEVERIDADE_SUCESSO,
                        persistente = true
                    )
                )
            }
        }

        editor.apply()
        return resultado
    }

    /**
     * Dispara notificação na barra de status do Android respeitando preferência do usuário e permissão.
     */
    fun notificarSistema(context: Context, notificacao: Notificacao) {
        val prefsApp = context.getSharedPreferences(CasaApplication.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefsApp.getBoolean(CasaApplication.KEY_NOTIFICACOES, true)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ALERTAS_ID,
                CHANNEL_ALERTAS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de bateria baixa, desconexão e falhas de Wi-Fi do dispositivo C.A.S.A."
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, NotificacoesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificacao.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTAS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notificacao.titulo)
            .setContentText(notificacao.mensagem)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificacao.mensagem))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        manager.notify(notificacao.id.hashCode(), builder.build())
    }

    private fun parseSyncMillis(ultimaSync: String): Long? {
        if (ultimaSync.isBlank()) return null
        val epochNumerico = ultimaSync.toLongOrNull()
        if (epochNumerico != null) {
            return if (epochNumerico < 10_000_000_000L) epochNumerico * 1000L else epochNumerico
        }
        val formatos = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        )
        for (fmt in formatos) {
            try {
                val parsed = fmt.parse(ultimaSync)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return null
    }
}
