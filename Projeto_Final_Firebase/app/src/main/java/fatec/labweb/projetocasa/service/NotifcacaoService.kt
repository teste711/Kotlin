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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import fatec.labweb.projetocasa.CasaApplication
import fatec.labweb.projetocasa.R
import fatec.labweb.projetocasa.view.NotificacoesActivity

class NotificacaoService : FirebaseMessagingService() {

    /**
     * Chamado quando um novo token FCM é gerado para o aplicativo.
     * Salva o token no documento do usuário logado em /usuarios/{uid}.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("usuarios")
            .document(uid)
            .set(mapOf("fcm_token" to token), SetOptions.merge())
    }

    /**
     * Chamado quando uma mensagem/notificação chega e o app está em primeiro plano (foreground)
     * ou quando a mensagem é do tipo data-only.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val prefs = getSharedPreferences(CasaApplication.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CasaApplication.KEY_NOTIFICACOES, true)) return

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["titulo"]
            ?: remoteMessage.data["title"]
            ?: "Projeto C.A.S.A."
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["mensagem"]
            ?: remoteMessage.data["body"]
            ?: return

        showNotification(title, body)
    }

    /**
     * Cria e exibe a notificação na barra de status do celular.
     */
    private fun showNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val channelId = "projeto_casa_channel_id"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Avisos do Projeto Casa",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Canal para notificações gerais do aplicativo"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, NotificacoesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
