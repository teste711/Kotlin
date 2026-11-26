package fatec.labweb.projetocasa.service

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import fatec.labweb.projetocasa.model.DadosDispositivo
import fatec.labweb.projetocasa.model.RedeWifiItem
import fatec.labweb.projetocasa.util.WifiCryptoHelper
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DispositivoService(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val triggerService: Esp32TriggerService = Esp32TriggerService()
) {

    private val formatoIso: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    suspend fun listarDispositivosDoUsuario(userId: String): List<DadosDispositivo> {
        return try {
            val snapshot = firestore.collection("dispositivos")
                .whereEqualTo("owner_uid", userId)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                doc.toObject(DadosDispositivo::class.java)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun obterStatusDispositivo(mac: String): DadosDispositivo? {
        return try {
            val doc = firestore.collection("dispositivos").document(mac).get().await()
            doc.toObject(DadosDispositivo::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun obterDispositivoPorMac(mac: String): DadosDispositivo? = obterStatusDispositivo(mac)

    /**
     * Pareia o ESP32 através do código de 6 dígitos exibido no OLED
     * Segue exatamente as regras e fluxos de /dispositivos/parear da API Web FastAPI e do firmware v11.
     */
    suspend fun parearPorCodigo(
        codigo: String,
        nome: String,
        userId: String,
        chaveWifi: String? = null,
        redesReservas: List<RedeWifiItem>? = null
    ): Result<DadosDispositivo> {
        val codLimpo = codigo.trim()
        if (!codLimpo.matches(Regex("^[0-9]{6}$"))) {
            return Result.failure(IllegalArgumentException("O código de pareamento deve conter exatamente 6 dígitos numéricos."))
        }

        return try {
            // 1. Busca código temporário gravado pelo ESP32
            val refCodigo = firestore.collection("pareamentos").document(codLimpo)
            val snapCodigo = refCodigo.get().await()
            if (!snapCodigo.exists()) {
                return Result.failure(IllegalStateException("Código de pareamento inválido ou expirado. Verifique o visor do aparelho."))
            }

            val mac = (snapCodigo.getString("mac") ?: "").trim().uppercase()
            if (mac.length != 17) {
                try { refCodigo.delete().await() } catch (_: Exception) {}
                return Result.failure(IllegalStateException("Registro de pareamento corrompido no aparelho."))
            }

            // 2. Verifica se o aparelho já pertence a outra conta e realiza transferência limpa
            val refDisp = firestore.collection("dispositivos").document(mac)
            val snapDisp = refDisp.get().await()
            if (snapDisp.exists()) {
                val donoAtual = snapDisp.getString("owner_uid")
                if (!donoAtual.isNullOrBlank() && donoAtual != userId) {
                    try {
                        firestore.collection("usuarios").document(donoAtual)
                            .update(
                                mapOf(
                                    "dispositivos" to FieldValue.arrayRemove(mac),
                                    "dispositivosVinculados" to FieldValue.arrayRemove(mac)
                                )
                            )
                            .await()
                    } catch (_: Exception) {}

                    try {
                        firestore.collection("usuarios").document(donoAtual)
                            .collection("chaves_dispositivos").document(mac)
                            .delete()
                            .await()
                    } catch (_: Exception) {}
                }
            }

            // 3. Recria dispositivos/{mac} do zero (sem merge) para não herdar lixo da conta anterior
            // e grava pareado_em (ISO 8601 UTC) exigido pelo handshake do firmware v2.10.0
            val agora = formatoIso.format(Date())
            val nomeFinal = nome.trim().ifBlank { "Dispositivo C.A.S.A." }
            val dadosDisp = hashMapOf<String, Any>(
                "mac" to mac,
                "owner_uid" to userId,
                "config_versao" to 1,
                "modo" to "casa",
                "volume" to 70,
                "nome" to nomeFinal,
                "pareado_em" to agora,
                "wifi_versao" to if (!redesReservas.isNullOrEmpty()) 1 else 0,
                "wifi_versao_aplicada" to -1,
                "wifi_redes" to (redesReservas?.map { it.toMap() } ?: emptyList<Any>())
            )

            // A chave AES do BLE NUNCA deve ser gravada em dispositivos/{MAC}
            if (!chaveWifi.isNullOrBlank()) {
                try {
                    firestore.collection("usuarios").document(userId)
                        .collection("chaves_dispositivos").document(mac)
                        .set(mapOf("chave" to chaveWifi, "atualizado_em" to agora), SetOptions.merge())
                        .await()
                } catch (_: Exception) {}
            }

            // set(...) SEM SetOptions.merge() garante recriação limpa do documento
            refDisp.set(dadosDisp).await()

            // 4. Remove o código de pareamento já utilizado
            try {
                refCodigo.delete().await()
            } catch (_: Exception) {}

            // 5. Adiciona MAC à lista padronizada 'dispositivos' do usuário
            firestore.collection("usuarios").document(userId)
                .set(mapOf("dispositivos" to FieldValue.arrayUnion(mac)), SetOptions.merge())
                .await()

            // 6. DISPARA a flag no nó do MAC do aparelho no Realtime Database: /dispositivo/controle/{MAC}
            triggerService.dispararFlagMac(mac)

            // 7. Notificação no Firestore
            registrarNotificacao(
                userId = userId,
                tipo = "pareamento",
                titulo = "Dispositivo Conectado",
                mensagem = "$nomeFinal pareado com sucesso.",
                dispositivoId = mac,
                agoraIso = agora
            )

            val dispCriado = refDisp.get().await().toObject(DadosDispositivo::class.java)
                ?: DadosDispositivo(mac = mac, nome = nomeFinal, owner_uid = userId)
            Result.success(dispCriado)
        } catch (e: Exception) {
            val erroMsg = if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
                e.message?.contains("insufficient permissions", ignoreCase = true) == true) {
                "Permissão negada no Firestore: Atualize as Regras no Console do Firebase (aba Firestore -> Regras) com o arquivo backend/firestore.rules."
            } else {
                e.message ?: "Falha ao parear dispositivo."
            }
            Result.failure(Exception(erroMsg, e))
        }
    }

    /**
     * Aguarda o ESP32 reiniciar, conectar ao Wi-Fi e publicar pareamentos/{pin} no Firestore.
     * Quando o documento aparecer, vincula o aparelho automaticamente sem nova ação do usuário.
     */
    suspend fun aguardarEFinalizarPareamento(
        pin: String,
        macConhecido: String,
        nomeAparelho: String,
        userId: String,
        chaveWifi: String? = null,
        redesReservas: List<RedeWifiItem>? = null,
        timeoutMs: Long = 75000,
        intervaloPollMs: Long = 2000,
        onStatusUpdate: (String) -> Unit = {}
    ): Result<DadosDispositivo> {
        val inicio = System.currentTimeMillis()
        val codLimpo = pin.trim()
        val refCodigo = firestore.collection("pareamentos").document(codLimpo)

        while (System.currentTimeMillis() - inicio < timeoutMs) {
            val decorridoSegundos = (System.currentTimeMillis() - inicio) / 1000
            onStatusUpdate("Aguardando o dispositivo conectar ao Wi-Fi (${decorridoSegundos}s)...")

            try {
                val snap = refCodigo.get().await()
                if (snap.exists()) {
                    onStatusUpdate("Dispositivo conectado à nuvem! Vinculando à sua conta...")
                    return parearPorCodigo(codLimpo, nomeAparelho, userId, chaveWifi, redesReservas)
                }
            } catch (e: Exception) {
                if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
                    e.message?.contains("insufficient permissions", ignoreCase = true) == true) {
                    return Result.failure(Exception("Permissão negada no Firestore: Atualize as Regras no Console do Firebase (aba Firestore -> Regras) com o arquivo backend/firestore.rules."))
                }
            }

            // Verifica se o documento do dispositivo já foi criado diretamente
            if (macConhecido.isNotBlank()) {
                try {
                    val snapDisp = firestore.collection("dispositivos").document(macConhecido.trim().uppercase()).get().await()
                    if (snapDisp.exists() && snapDisp.getString("owner_uid") == userId) {
                        val disp = snapDisp.toObject(DadosDispositivo::class.java)
                        if (disp != null) return Result.success(disp)
                    }
                } catch (_: Exception) {}
            }

            kotlinx.coroutines.delay(intervaloPollMs)
        }

        return Result.failure(
            IllegalStateException("Tempo limite excedido aguardando o dispositivo conectar à rede Wi-Fi. Verifique se o SSID e senha estão corretos e se o sinal da rede alcança o aparelho.")
        )
    }

    /**
     * Atualiza a lista de redes Wi-Fi (reservas de contingência) do dispositivo ESP32.
     * Cifra as senhas utilizando a chave AES do dispositivo e incrementa wifi_versao no Firestore.
     */
    suspend fun atualizarRedesWifi(
        mac: String,
        redes: List<RedeWifiItem>,
        chaveWifiB64: String
    ): Result<Unit> {
        val macLimpo = mac.trim().uppercase()
        if (redes.isEmpty() || redes.size > 4) {
            return Result.failure(IllegalArgumentException("A lista de redes reservas deve conter entre 1 e 4 redes Wi-Fi."))
        }

        return try {
            val ssidsVistos = mutableSetOf<String>()
            val redesProcessadas = mutableListOf<Map<String, Any>>()

            for (rede in redes) {
                val ssidLimpo = rede.ssid.trim()
                if (ssidLimpo.isEmpty() || ssidLimpo.toByteArray(Charsets.UTF_8).size > 32) {
                    return Result.failure(IllegalArgumentException("O SSID '$ssidLimpo' deve ter entre 1 e 32 bytes."))
                }
                if (!ssidsVistos.add(ssidLimpo)) {
                    return Result.failure(IllegalArgumentException("O SSID '$ssidLimpo' está duplicado na lista."))
                }

                val senhaCifrada = if (rede.senhaCifrada.isNotBlank()) {
                    rede.senhaCifrada
                } else if (rede.senha.isNotEmpty()) {
                    if (rede.senha.length !in 8..63) {
                        return Result.failure(IllegalArgumentException("A senha da rede '$ssidLimpo' deve ter entre 8 e 63 caracteres."))
                    }
                    WifiCryptoHelper.cifrarSenhaWifi(rede.senha, chaveWifiB64)
                } else {
                    "" // Rede aberta
                }

                redesProcessadas.add(
                    mapOf(
                        "ssid" to ssidLimpo,
                        "senha_cifrada" to senhaCifrada
                    )
                )
            }

            val docRef = firestore.collection("dispositivos").document(macLimpo)
            val updates = mapOf(
                "wifi_redes" to redesProcessadas,
                "wifi_versao" to FieldValue.increment(1)
            )
            docRef.update(updates).await()

            // Dispara a flag no nó do MAC do aparelho conforme contrato C.3
            triggerService.dispararFlagMac(macLimpo)

            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                val principal = redes.firstOrNull()?.ssid?.trim().orEmpty()
                registrarNotificacao(
                    userId = uid,
                    tipo = "wifi_pendente",
                    titulo = "Redes Wi-Fi atualizadas",
                    mensagem = if (principal.isNotBlank()) {
                        "Configuração enviada (${redes.size} rede(s), principal: $principal). Será aplicada na próxima conexão."
                    } else {
                        "Configuração de Wi-Fi enviada ao dispositivo."
                    },
                    dispositivoId = macLimpo
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Obtém as redes Wi-Fi cadastradas no documento do dispositivo
     */
    suspend fun obterRedesWifi(mac: String): Result<List<RedeWifiItem>> {
        return try {
            val snap = firestore.collection("dispositivos").document(mac.trim().uppercase()).get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalStateException("Dispositivo não encontrado."))
            }
            @Suppress("UNCHECKED_CAST")
            val redesRaw = snap.get("wifi_redes") as? List<Map<String, Any>> ?: emptyList()
            val lista = redesRaw.mapIndexed { idx, map ->
                RedeWifiItem(
                    ssid = map["ssid"] as? String ?: "",
                    senhaCifrada = map["senha_cifrada"] as? String ?: "",
                    principal = (idx == 0)
                )
            }
            Result.success(lista)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Atualiza nome e volume (0-100) do dispositivo
     */
    suspend fun atualizarDispositivo(mac: String, nome: String, volume: Int): Result<Unit> {
        val macLimpo = mac.trim().uppercase()
        return try {
            val volumeValido = volume.coerceIn(0, 100)
            val atualizacao = mapOf(
                "nome" to nome.trim(),
                "volume" to volumeValido
            )
            firestore.collection("dispositivos").document(macLimpo)
                .update(atualizacao)
                .await()

            // Dispara a flag no nó do MAC do aparelho após alterar volume conforme contrato C.3
            triggerService.dispararFlagMac(macLimpo)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove / Desvincula o dispositivo da conta do usuário:
     * 1. Apaga dispositivos/{MAC} no Firestore
     * 2. Remove MAC de usuarios/{uid}.dispositivos (e dispositivosVinculados legado)
     * 3. Apaga usuarios/{uid}/chaves_dispositivos/{MAC}
     * 4. Remove o nó /dispositivo/controle/{MAC} no RTDB
     */
    suspend fun removerDispositivo(mac: String, userId: String): Result<Unit> {
        val macLimpo = mac.trim().uppercase()
        return try {
            firestore.collection("dispositivos").document(macLimpo).delete().await()
            firestore.collection("usuarios").document(userId)
                .set(
                    mapOf(
                        "dispositivos" to FieldValue.arrayRemove(macLimpo),
                        "dispositivosVinculados" to FieldValue.arrayRemove(macLimpo)
                    ),
                    SetOptions.merge()
                )
                .await()

            try {
                firestore.collection("usuarios").document(userId)
                    .collection("chaves_dispositivos").document(macLimpo)
                    .delete()
                    .await()
            } catch (_: Exception) {}

            try {
                triggerService.removerNoControleMac(macLimpo)
            } catch (_: Exception) {}

            registrarNotificacao(
                userId = userId,
                tipo = "desvinculacao",
                titulo = "Dispositivo desvinculado",
                mensagem = "O aparelho ($macLimpo) foi removido da sua conta.",
                dispositivoId = macLimpo
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Incrementa config_versao e dispara a flag para todos os dispositivos do usuário
     */
    suspend fun sincronizarDispositivos(userId: String): Result<Int> {
        return try {
            val snapshot = firestore.collection("dispositivos")
                .whereEqualTo("owner_uid", userId)
                .get()
                .await()

            var count = 0
            for (doc in snapshot.documents) {
                doc.reference.update("config_versao", FieldValue.increment(1)).await()
                val mac = (doc.getString("mac") ?: doc.id).trim().uppercase()
                if (mac.isNotBlank()) {
                    triggerService.dispararFlagMac(mac)
                }
                count++
            }
            if (count > 0) {
                registrarNotificacao(
                    userId = userId,
                    tipo = "sincronizacao",
                    titulo = "Sincronização enviada",
                    mensagem = "Atualização de botões e categorias enviada para $count dispositivo(s)."
                )
            }

            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun registrarNotificacao(
        userId: String,
        tipo: String,
        titulo: String,
        mensagem: String,
        dispositivoId: String = "",
        agoraIso: String? = null
    ) {
        try {
            val timestampIso = agoraIso ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val notif = hashMapOf<String, Any>(
                "tipo" to tipo,
                "titulo" to titulo,
                "mensagem" to mensagem,
                "criada_em" to timestampIso,
                "data" to System.currentTimeMillis(),
                "lida" to false
            )
            if (dispositivoId.isNotBlank()) {
                notif["dispositivoId"] = dispositivoId
            }

            firestore.collection("usuarios").document(userId)
                .collection("notificacoes")
                .add(notif)
                .await()
        } catch (_: Exception) {}
    }
}
