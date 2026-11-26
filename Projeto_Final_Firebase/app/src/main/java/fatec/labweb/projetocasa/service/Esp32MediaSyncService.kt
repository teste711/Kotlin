package fatec.labweb.projetocasa.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import fatec.labweb.projetocasa.model.Categoria
import fatec.labweb.projetocasa.model.CategoriaItem
import fatec.labweb.projetocasa.model.DesenhoBotao
import fatec.labweb.projetocasa.util.AudioPredefinidoHelper
import fatec.labweb.projetocasa.util.MidiaLocalHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class MidiaUploadResult(
    val midiaId: String,
    val nomeArquivo: String,
    val sha256: String,
    val tamanho: Int,
    val totalBlocos: Int = 1
)

/**
 * Esp32MediaSyncService: Implementa o contrato rigoroso de publicação incremental de mídias
 * e fast-path de download para o hardware C.A.S.A. (ESP32-S3 v2.10.0):
 *
 * 1. Deduplicação por SHA-256 (reaproveita midiaId/arquivo/total_blocos sem reenviar)
 * 2. Subir blocos Base64 em /arquivos_midia/{arquivo}/blocos/0000, 0001...
 * 3. Gravar documento /arquivos_midia/{arquivo} { total_blocos, sha256, tamanho, arquivo }
 * 4. Gravar documento /usuarios/{uid}/midias/{imagem_id|audio_id} { arquivo, sha256, tamanho, tipo, total_blocos }
 * 5. Gravar documento /usuarios/{uid}/categorias/{id} com campos denormalizados de fast-path
 *    (imagem_sha, imagem_arquivo, imagem_blocos, audio_sha, audio_arquivo, audio_blocos)
 * 6. Por último (uma única vez), incrementar config_versao e disparar a flag no RTDB: /dispositivo/controle/{MAC}
 */
class Esp32MediaSyncService(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val triggerService: Esp32TriggerService = Esp32TriggerService()
) {

    companion object {
        private const val TAG = "Esp32MediaSyncService"
        const val LARGURA_ESP32 = 240
        const val ALTURA_ESP32 = 240
        const val QUALIDADE_JPEG = 78
        // Chunks de 192 KB (múltiplo de 3, sem padding intermediário em Base64)
        const val TAMANHO_BLOCO = 196608
        private const val PREFS_MIDIA_CACHE = "esp32_midia_cache"
    }

    private fun ehMidiaIdValido(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val s = id.trim()
        return !s.contains("/") &&
            !s.startsWith("http", ignoreCase = true) &&
            !s.startsWith("content:", ignoreCase = true) &&
            !s.startsWith("file:", ignoreCase = true)
    }

    private fun obterHashCache(context: Context, midiaId: String): String? {
        if (!ehMidiaIdValido(midiaId)) return null
        val prefs = context.getSharedPreferences(PREFS_MIDIA_CACHE, Context.MODE_PRIVATE)
        return prefs.getString("hash_$midiaId", null)
    }

    private fun obterCachePorSha(context: Context, userId: String, sha256: String, tipo: String, tamanhoFallback: Int): MidiaUploadResult? {
        val prefs = context.getSharedPreferences(PREFS_MIDIA_CACHE, Context.MODE_PRIVATE)
        val raw = prefs.getString("sha_${userId}_${tipo}_$sha256", null) ?: return null
        val partes = raw.split("|")
        if (partes.size < 3) return null
        val midiaId = partes[0]
        val arquivo = partes[1]
        val blocos = partes[2].toIntOrNull() ?: 0
        if (!ehMidiaIdValido(midiaId) || arquivo.isBlank() || blocos <= 0) return null
        return MidiaUploadResult(midiaId, arquivo, sha256, tamanhoFallback, blocos)
    }

    private fun salvarHashCache(
        context: Context,
        userId: String,
        tipo: String,
        midiaId: String,
        sha256: String,
        nomeArquivo: String,
        totalBlocos: Int
    ) {
        if (!ehMidiaIdValido(midiaId)) return
        val prefs = context.getSharedPreferences(PREFS_MIDIA_CACHE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("hash_$midiaId", sha256)
            .putString("arq_$midiaId", nomeArquivo)
            .putInt("blocos_$midiaId", totalBlocos)
            .putString("sha_${userId}_${tipo}_$sha256", "$midiaId|$nomeArquivo|$totalBlocos")
            .apply()
    }

    private suspend fun obterMidiaExistenteNoFirestore(
        userId: String,
        midiaId: String,
        sha256: String,
        tamanhoFallback: Int
    ): MidiaUploadResult? {
        if (!ehMidiaIdValido(midiaId)) return null
        return try {
            val doc = firestore.collection("usuarios").document(userId)
                .collection("midias").document(midiaId).get().await()
            if (doc.exists() && doc.getString("sha256") == sha256) {
                val arquivo = doc.getString("arquivo") ?: return null
                val tamanho = doc.getLong("tamanho")?.toInt() ?: tamanhoFallback
                val blocosDoc = doc.getLong("total_blocos")?.toInt()
                val totalBlocos = if (blocosDoc != null && blocosDoc > 0) {
                    blocosDoc
                } else {
                    val arqSnap = try {
                        firestore.collection("arquivos_midia").document(arquivo).get().await()
                    } catch (_: Exception) { null }
                    arqSnap?.getLong("total_blocos")?.toInt()?.takeIf { it > 0 }
                        ?: ((tamanho + TAMANHO_BLOCO - 1) / TAMANHO_BLOCO).coerceAtLeast(1)
                }
                MidiaUploadResult(midiaId, arquivo, sha256, tamanho, totalBlocos)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun buscarMidiaPorShaNoFirestore(
        userId: String,
        sha256: String,
        tipo: String,
        tamanhoFallback: Int
    ): MidiaUploadResult? {
        return try {
            // Consulta single-field em sha256 (não exige índice composto no Firestore)
            val snap = firestore.collection("usuarios").document(userId)
                .collection("midias")
                .whereEqualTo("sha256", sha256)
                .get()
                .await()
            val doc = snap.documents.firstOrNull {
                it.getString("tipo") == tipo
            } ?: return null
            val midiaId = doc.id
            val arquivo = doc.getString("arquivo") ?: return null
            val tamanho = doc.getLong("tamanho")?.toInt() ?: tamanhoFallback
            val blocosDoc = doc.getLong("total_blocos")?.toInt()
            val totalBlocos = if (blocosDoc != null && blocosDoc > 0) {
                blocosDoc
            } else {
                val arqSnap = try {
                    firestore.collection("arquivos_midia").document(arquivo).get().await()
                } catch (_: Exception) { null }
                arqSnap?.getLong("total_blocos")?.toInt()?.takeIf { it > 0 }
                    ?: ((tamanho + TAMANHO_BLOCO - 1) / TAMANHO_BLOCO).coerceAtLeast(1)
            }
            MidiaUploadResult(midiaId, arquivo, sha256, tamanho, totalBlocos)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao buscar mídia por SHA-256 ($tipo): ${e.message}")
            null
        }
    }

    /**
     * Processa e publica uma imagem no Firestore com deduplicação por SHA-256:
     * - JPEG baseline 240x240 (qualidade 78%, cabe em 1 único bloco)
     * - Reaproveita midiaId/arquivo/total_blocos se SHA-256 coincidir (sem reupload)
     */
    suspend fun processarESalvarImagem(
        context: Context,
        userId: String,
        fonte: Any,
        idExistente: String? = null,
        shaExistente: String? = null,
        arquivoExistente: String? = null,
        blocosExistentes: Int? = null
    ): Result<MidiaUploadResult> = withContext(Dispatchers.IO) {
        try {
            val bitmapOriginal = extrairBitmap(context, fonte)
                ?: return@withContext Result.failure(IllegalArgumentException("Não foi possível carregar o bitmap da fonte."))

            // 1. Recorta quadrado central e redimensiona para 240x240 sem distorção
            val bitmap240 = cortarERedimensionar(bitmapOriginal, LARGURA_ESP32)

            // 2. Comprime para JPEG Baseline (~78% para caber em 1 bloco)
            val bos = ByteArrayOutputStream()
            bitmap240.compress(Bitmap.CompressFormat.JPEG, QUALIDADE_JPEG, bos)
            val bytes = bos.toByteArray()

            // 3. Calcula SHA-256 (64 hex lowercase)
            val sha256 = calcularSha256(bytes)
            val tamanho = bytes.size
            val totalBlocos = ((tamanho + TAMANHO_BLOCO - 1) / TAMANHO_BLOCO).coerceAtLeast(1)

            // 4. Deduplicação Fast-Path 1: item já tem id válido + sha + arquivo + blocos idênticos
            if (ehMidiaIdValido(idExistente) && shaExistente == sha256 && !arquivoExistente.isNullOrBlank() && (blocosExistentes ?: 0) > 0) {
                salvarHashCache(context, userId, "imagem", idExistente!!, sha256, arquivoExistente, blocosExistentes!!)
                Log.d(TAG, "Imagem inalterada (fast-path): id=$idExistente, sha256=$sha256")
                return@withContext Result.success(MidiaUploadResult(idExistente, arquivoExistente, sha256, tamanho, blocosExistentes))
            }

            // 5. Deduplicação Fast-Path 2: cache local por SHA-256 ou idExistente válido
            obterCachePorSha(context, userId, sha256, "imagem", tamanho)?.let { hitShaLocal ->
                Log.d(TAG, "Imagem inalterada (cache local SHA): id=${hitShaLocal.midiaId}, sha256=$sha256")
                return@withContext Result.success(hitShaLocal)
            }

            if (ehMidiaIdValido(idExistente)) {
                val prefs = context.getSharedPreferences(PREFS_MIDIA_CACHE, Context.MODE_PRIVATE)
                val hashCache = obterHashCache(context, idExistente!!)
                val arqCache = prefs.getString("arq_$idExistente", null)
                val blocosCache = prefs.getInt("blocos_$idExistente", 0)
                if (hashCache == sha256 && !arqCache.isNullOrBlank() && blocosCache > 0) {
                    salvarHashCache(context, userId, "imagem", idExistente, sha256, arqCache, blocosCache)
                    return@withContext Result.success(MidiaUploadResult(idExistente, arqCache, sha256, tamanho, blocosCache))
                }
                val existentePorId = obterMidiaExistenteNoFirestore(userId, idExistente, sha256, tamanho)
                if (existentePorId != null) {
                    salvarHashCache(context, userId, "imagem", existentePorId.midiaId, sha256, existentePorId.nomeArquivo, existentePorId.totalBlocos)
                    Log.d(TAG, "Imagem inalterada (Firestore id hit): id=${existentePorId.midiaId}, sha256=$sha256")
                    return@withContext Result.success(existentePorId)
                }
            }

            // 6. Deduplicação por SHA-256 na coleção usuarios/{uid}/midias
            val existentePorSha = buscarMidiaPorShaNoFirestore(userId, sha256, "imagem", tamanho)
            if (existentePorSha != null) {
                salvarHashCache(context, userId, "imagem", existentePorSha.midiaId, sha256, existentePorSha.nomeArquivo, existentePorSha.totalBlocos)
                Log.d(TAG, "Imagem reaproveitada por SHA-256: id=${existentePorSha.midiaId}, sha256=$sha256")
                return@withContext Result.success(existentePorSha)
            }

            // 7. Mídia nova ou alterada: gera novo ID e sobe blocos -> arquivos_midia -> midias
            val midiaId = "img_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            val nomeArquivo = "$midiaId.jpg"
            salvarHashCache(context, userId, "imagem", midiaId, sha256, nomeArquivo, totalBlocos)
            salvarCacheLocal(context, nomeArquivo, bytes)

            val docArquivoRef = firestore.collection("arquivos_midia").document(nomeArquivo)
            for (idx in 0 until totalBlocos) {
                val inicio = idx * TAMANHO_BLOCO
                val fim = minOf(inicio + TAMANHO_BLOCO, tamanho)
                val fatia = bytes.copyOfRange(inicio, fim)
                val b64 = Base64.encodeToString(fatia, Base64.NO_WRAP)

                docArquivoRef.collection("blocos")
                    .document(String.format(Locale.US, "%04d", idx))
                    .set(mapOf("ordem" to idx, "dados_b64" to b64))
                    .await()
            }

            docArquivoRef.set(
                mapOf(
                    "arquivo" to nomeArquivo,
                    "tamanho" to tamanho,
                    "sha256" to sha256,
                    "total_blocos" to totalBlocos
                )
            ).await()

            firestore.collection("usuarios").document(userId)
                .collection("midias").document(midiaId)
                .set(
                    mapOf(
                        "arquivo" to nomeArquivo,
                        "sha256" to sha256,
                        "tamanho" to tamanho,
                        "total_blocos" to totalBlocos,
                        "tipo" to "imagem"
                    )
                ).await()

            Log.d(TAG, "Imagem processada e salva: id=$midiaId, arquivo=$nomeArquivo, sha256=$sha256, blocos=$totalBlocos")
            Result.success(MidiaUploadResult(midiaId, nomeArquivo, sha256, tamanho, totalBlocos))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar imagem", e)
            Result.failure(e)
        }
    }

    /**
     * Processa e publica um áudio (MP3 ou WAV) no Firestore com deduplicação por SHA-256:
     * - Reaproveita midiaId/arquivo/total_blocos se SHA-256 coincidir (sem reupload)
     */
    suspend fun processarESalvarAudio(
        context: Context,
        userId: String,
        fonte: Any,
        idExistente: String? = null,
        shaExistente: String? = null,
        arquivoExistente: String? = null,
        blocosExistentes: Int? = null
    ): Result<MidiaUploadResult> = withContext(Dispatchers.IO) {
        try {
            val (bytes, extensao) = extrairBytesAudio(context, fonte)
                ?: return@withContext Result.failure(IllegalArgumentException("Não foi possível ler os bytes do áudio."))

            if (bytes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Arquivo de áudio vazio."))
            }

            val sha256 = calcularSha256(bytes)
            val tamanho = bytes.size
            val totalBlocos = ((tamanho + TAMANHO_BLOCO - 1) / TAMANHO_BLOCO).coerceAtLeast(1)

            // 1. Deduplicação Fast-Path 1: item já tem id válido + sha + arquivo + blocos idênticos
            if (ehMidiaIdValido(idExistente) && shaExistente == sha256 && !arquivoExistente.isNullOrBlank() && (blocosExistentes ?: 0) > 0) {
                salvarHashCache(context, userId, "audio", idExistente!!, sha256, arquivoExistente, blocosExistentes!!)
                Log.d(TAG, "Áudio inalterado (fast-path): id=$idExistente, sha256=$sha256")
                return@withContext Result.success(MidiaUploadResult(idExistente, arquivoExistente, sha256, tamanho, blocosExistentes))
            }

            // 2. Deduplicação Fast-Path 2: cache local por SHA-256 ou idExistente válido
            obterCachePorSha(context, userId, sha256, "audio", tamanho)?.let { hitShaLocal ->
                Log.d(TAG, "Áudio inalterado (cache local SHA): id=${hitShaLocal.midiaId}, sha256=$sha256")
                return@withContext Result.success(hitShaLocal)
            }

            if (ehMidiaIdValido(idExistente)) {
                val prefs = context.getSharedPreferences(PREFS_MIDIA_CACHE, Context.MODE_PRIVATE)
                val hashCache = obterHashCache(context, idExistente!!)
                val arqCache = prefs.getString("arq_$idExistente", null)
                val blocosCache = prefs.getInt("blocos_$idExistente", 0)
                if (hashCache == sha256 && !arqCache.isNullOrBlank() && blocosCache > 0) {
                    salvarHashCache(context, userId, "audio", idExistente, sha256, arqCache, blocosCache)
                    return@withContext Result.success(MidiaUploadResult(idExistente, arqCache, sha256, tamanho, blocosCache))
                }
                val existentePorId = obterMidiaExistenteNoFirestore(userId, idExistente, sha256, tamanho)
                if (existentePorId != null) {
                    salvarHashCache(context, userId, "audio", existentePorId.midiaId, sha256, existentePorId.nomeArquivo, existentePorId.totalBlocos)
                    Log.d(TAG, "Áudio inalterado (Firestore id hit): id=${existentePorId.midiaId}, sha256=$sha256")
                    return@withContext Result.success(existentePorId)
                }
            }

            // 3. Deduplicação por SHA-256 na coleção usuarios/{uid}/midias
            val existentePorSha = buscarMidiaPorShaNoFirestore(userId, sha256, "audio", tamanho)
            if (existentePorSha != null) {
                salvarHashCache(context, userId, "audio", existentePorSha.midiaId, sha256, existentePorSha.nomeArquivo, existentePorSha.totalBlocos)
                Log.d(TAG, "Áudio reaproveitado por SHA-256: id=${existentePorSha.midiaId}, sha256=$sha256")
                return@withContext Result.success(existentePorSha)
            }

            // 4. Mídia nova ou alterada: sobe blocos -> arquivos_midia -> midias
            val midiaId = "aud_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            val nomeArquivo = "$midiaId.$extensao"
            salvarHashCache(context, userId, "audio", midiaId, sha256, nomeArquivo, totalBlocos)
            salvarCacheLocal(context, nomeArquivo, bytes)

            val docArquivoRef = firestore.collection("arquivos_midia").document(nomeArquivo)
            for (idx in 0 until totalBlocos) {
                val inicio = idx * TAMANHO_BLOCO
                val fim = minOf(inicio + TAMANHO_BLOCO, tamanho)
                val fatia = bytes.copyOfRange(inicio, fim)
                val b64 = Base64.encodeToString(fatia, Base64.NO_WRAP)

                docArquivoRef.collection("blocos")
                    .document(String.format(Locale.US, "%04d", idx))
                    .set(mapOf("ordem" to idx, "dados_b64" to b64))
                    .await()
            }

            docArquivoRef.set(
                mapOf(
                    "arquivo" to nomeArquivo,
                    "tamanho" to tamanho,
                    "sha256" to sha256,
                    "total_blocos" to totalBlocos
                )
            ).await()

            firestore.collection("usuarios").document(userId)
                .collection("midias").document(midiaId)
                .set(
                    mapOf(
                        "arquivo" to nomeArquivo,
                        "sha256" to sha256,
                        "tamanho" to tamanho,
                        "total_blocos" to totalBlocos,
                        "tipo" to "audio"
                    )
                ).await()

            Log.d(TAG, "Áudio processado e salvo: id=$midiaId, arquivo=$nomeArquivo, sha256=$sha256, blocos=$totalBlocos")
            Result.success(MidiaUploadResult(midiaId, nomeArquivo, sha256, tamanho, totalBlocos))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar áudio", e)
            Result.failure(e)
        }
    }

    /**
     * Sincroniza a prancha completa do usuário no Cloud Firestore com sync incremental (git pull)
     * e fast-path de download (imagem_sha, imagem_arquivo, imagem_blocos, audio_sha, audio_arquivo, audio_blocos).
     * Dispara o gatilho RTDB uma única vez após todas as gravações no Firestore concluírem.
     */
    suspend fun sincronizarPranchaCompleta(
        context: Context,
        userId: String,
        categorias: List<Categoria>,
        dispararTrigger: Boolean = true
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Carrega categorias remotas existentes para preservar metadados de mídia de itens não modificados
            val categoriasRemotasMap = try {
                firestore.collection("usuarios").document(userId)
                    .collection("categorias").get().await()
                    .documents.mapNotNull { it.toObject(Categoria::class.java) }
                    .associateBy { it.id }
            } catch (_: Exception) {
                emptyMap()
            }

            val limiteCategorias = minOf(categorias.size, 3)

            for (catIdx in 0 until limiteCategorias) {
                val cat = categorias[catIdx]
                cat.ordem = catIdx
                val catRemota = categoriasRemotasMap[cat.id]

                // 1.1 Processa imagem do ícone da categoria
                var catImagemId = catRemota?.imagem_id?.takeIf { ehMidiaIdValido(it) }
                    ?: cat.imagem_id?.takeIf { ehMidiaIdValido(it) } ?: ""
                var catImagemSha = catRemota?.imagem_sha?.takeIf { it.isNotBlank() }
                    ?: cat.imagem_sha?.takeIf { it.isNotBlank() } ?: ""
                var catImagemArq = catRemota?.imagem_arquivo?.takeIf { it.isNotBlank() }
                    ?: cat.imagem_arquivo?.takeIf { it.isNotBlank() } ?: ""
                var catImagemBlocos = catRemota?.imagem_blocos?.takeIf { it > 0 }
                    ?: cat.imagem_blocos?.takeIf { it > 0 } ?: 0

                val imgLocalCat = MidiaLocalHelper.obterArquivoLocal(context, cat.id)
                val fonteImgCat: Any? = when {
                    imgLocalCat != null && imgLocalCat.exists() -> imgLocalCat
                    !cat.localPath.isNullOrBlank() && File(cat.localPath!!).exists() -> File(cat.localPath!!)
                    DesenhoBotao.obterDrawable(cat.nome, cat.slug) != null -> DesenhoBotao.obterDrawable(cat.nome, cat.slug)
                    else -> null
                }

                if (fonteImgCat != null) {
                    val resImg = processarESalvarImagem(
                        context = context,
                        userId = userId,
                        fonte = fonteImgCat,
                        idExistente = catImagemId.ifBlank { null },
                        shaExistente = catImagemSha.ifBlank { null },
                        arquivoExistente = catImagemArq.ifBlank { null },
                        blocosExistentes = catImagemBlocos.takeIf { it > 0 }
                    )
                    if (resImg.isSuccess) {
                        val m = resImg.getOrThrow()
                        catImagemId = m.midiaId
                        catImagemSha = m.sha256
                        catImagemArq = m.nomeArquivo
                        catImagemBlocos = m.totalBlocos
                    }
                } else {
                    // Imagem removida: limpa campos de fast-path
                    catImagemId = ""
                    catImagemSha = ""
                    catImagemArq = ""
                    catImagemBlocos = 0
                }
                cat.imagem_id = catImagemId.ifBlank { null }
                cat.imagem_sha = catImagemSha.ifBlank { null }
                cat.imagem_arquivo = catImagemArq.ifBlank { null }
                cat.imagem_blocos = catImagemBlocos.takeIf { it > 0 }

                // 1.2 Processa áudio da categoria
                var catAudioId = catRemota?.audio_id?.takeIf { ehMidiaIdValido(it) }
                    ?: cat.audio_id?.takeIf { ehMidiaIdValido(it) } ?: ""
                var catAudioSha = catRemota?.audio_sha?.takeIf { it.isNotBlank() }
                    ?: cat.audio_sha?.takeIf { it.isNotBlank() } ?: ""
                var catAudioArq = catRemota?.audio_arquivo?.takeIf { it.isNotBlank() }
                    ?: cat.audio_arquivo?.takeIf { it.isNotBlank() } ?: ""
                var catAudioBlocos = catRemota?.audio_blocos?.takeIf { it > 0 }
                    ?: cat.audio_blocos?.takeIf { it > 0 } ?: 0

                val audioLocalCat = MidiaLocalHelper.obterAudioLocal(context, cat.id)
                val fonteAudioCat: Any? = when {
                    audioLocalCat != null && audioLocalCat.exists() -> audioLocalCat
                    !cat.audioUrl.isNullOrBlank() && File(cat.audioUrl!!).exists() -> File(cat.audioUrl!!)
                    else -> {
                        val audioPredef = AudioPredefinidoHelper.obterAudioPredefinido(context, cat.nome, cat.defaultGender)
                        if (audioPredef != null && audioPredef.exists()) {
                            MidiaLocalHelper.salvarAudioLocalmente(context, cat.id, audioPredef)
                            audioPredef
                        } else null
                    }
                }

                if (fonteAudioCat != null) {
                    val resAudio = processarESalvarAudio(
                        context = context,
                        userId = userId,
                        fonte = fonteAudioCat,
                        idExistente = catAudioId.ifBlank { null },
                        shaExistente = catAudioSha.ifBlank { null },
                        arquivoExistente = catAudioArq.ifBlank { null },
                        blocosExistentes = catAudioBlocos.takeIf { it > 0 }
                    )
                    if (resAudio.isSuccess) {
                        val m = resAudio.getOrThrow()
                        catAudioId = m.midiaId
                        catAudioSha = m.sha256
                        catAudioArq = m.nomeArquivo
                        catAudioBlocos = m.totalBlocos
                    }
                } else {
                    catAudioId = ""
                    catAudioSha = ""
                    catAudioArq = ""
                    catAudioBlocos = 0
                }
                cat.audio_id = catAudioId.ifBlank { null }
                cat.audio_sha = catAudioSha.ifBlank { null }
                cat.audio_arquivo = catAudioArq.ifBlank { null }
                cat.audio_blocos = catAudioBlocos.takeIf { it > 0 }

                // 2. Processa cada botão da categoria (máx 5)
                val botoesRemotosMap = catRemota?.botoes?.associateBy { it.id } ?: emptyMap()
                val botoesLista = mutableListOf<Map<String, Any>>()
                val limiteBotoes = minOf(cat.botoes.size, 5)

                for (btnIdx in 0 until limiteBotoes) {
                    val btn = cat.botoes[btnIdx]
                    btn.ordem = btnIdx
                    val btnRemoto = botoesRemotosMap[btn.id]

                    // 2.1 Imagem do botão
                    var btnImagemId = btnRemoto?.imagem_id?.takeIf { ehMidiaIdValido(it) }
                        ?: btn.imagem_id?.takeIf { ehMidiaIdValido(it) } ?: ""
                    var btnImagemSha = btnRemoto?.imagem_sha?.takeIf { it.isNotBlank() }
                        ?: btn.imagem_sha?.takeIf { it.isNotBlank() } ?: ""
                    var btnImagemArq = btnRemoto?.imagem_arquivo?.takeIf { it.isNotBlank() }
                        ?: btn.imagem_arquivo?.takeIf { it.isNotBlank() } ?: ""
                    var btnImagemBlocos = btnRemoto?.imagem_blocos?.takeIf { it > 0 }
                        ?: btn.imagem_blocos?.takeIf { it > 0 } ?: 0

                    val imgLocalBtn = MidiaLocalHelper.obterArquivoLocal(context, btn.id)
                    val fonteImgBtn: Any? = when {
                        imgLocalBtn != null && imgLocalBtn.exists() -> imgLocalBtn
                        !btn.localPath.isNullOrBlank() && File(btn.localPath!!).exists() -> File(btn.localPath!!)
                        DesenhoBotao.obterDrawable(btn.rotulo, btn.significado) != null -> DesenhoBotao.obterDrawable(btn.rotulo, btn.significado)
                        else -> null
                    }

                    if (fonteImgBtn != null) {
                        val resImg = processarESalvarImagem(
                            context = context,
                            userId = userId,
                            fonte = fonteImgBtn,
                            idExistente = btnImagemId.ifBlank { null },
                            shaExistente = btnImagemSha.ifBlank { null },
                            arquivoExistente = btnImagemArq.ifBlank { null },
                            blocosExistentes = btnImagemBlocos.takeIf { it > 0 }
                        )
                        if (resImg.isSuccess) {
                            val m = resImg.getOrThrow()
                            btnImagemId = m.midiaId
                            btnImagemSha = m.sha256
                            btnImagemArq = m.nomeArquivo
                            btnImagemBlocos = m.totalBlocos
                        }
                    } else {
                        btnImagemId = ""
                        btnImagemSha = ""
                        btnImagemArq = ""
                        btnImagemBlocos = 0
                    }
                    btn.imagem_id = btnImagemId.ifBlank { null }
                    btn.imagem_sha = btnImagemSha.ifBlank { null }
                    btn.imagem_arquivo = btnImagemArq.ifBlank { null }
                    btn.imagem_blocos = btnImagemBlocos.takeIf { it > 0 }

                    // 2.2 Áudio do botão
                    var btnAudioId = btnRemoto?.audio_id?.takeIf { ehMidiaIdValido(it) }
                        ?: btn.audio_id?.takeIf { ehMidiaIdValido(it) } ?: ""
                    var btnAudioSha = btnRemoto?.audio_sha?.takeIf { it.isNotBlank() }
                        ?: btn.audio_sha?.takeIf { it.isNotBlank() } ?: ""
                    var btnAudioArq = btnRemoto?.audio_arquivo?.takeIf { it.isNotBlank() }
                        ?: btn.audio_arquivo?.takeIf { it.isNotBlank() } ?: ""
                    var btnAudioBlocos = btnRemoto?.audio_blocos?.takeIf { it > 0 }
                        ?: btn.audio_blocos?.takeIf { it > 0 } ?: 0

                    val audioLocalBtn = MidiaLocalHelper.obterAudioLocal(context, btn.id)
                    val fonteAudioBtn: Any? = when {
                        audioLocalBtn != null && audioLocalBtn.exists() -> audioLocalBtn
                        !btn.audioUrl.isNullOrBlank() && File(btn.audioUrl!!).exists() -> File(btn.audioUrl!!)
                        else -> {
                            val audioPredef = AudioPredefinidoHelper.obterAudioPredefinido(context, btn.rotulo, btn.defaultGender)
                            if (audioPredef != null && audioPredef.exists()) audioPredef else null
                        }
                    }

                    if (fonteAudioBtn != null) {
                        val resAudio = processarESalvarAudio(
                            context = context,
                            userId = userId,
                            fonte = fonteAudioBtn,
                            idExistente = btnAudioId.ifBlank { null },
                            shaExistente = btnAudioSha.ifBlank { null },
                            arquivoExistente = btnAudioArq.ifBlank { null },
                            blocosExistentes = btnAudioBlocos.takeIf { it > 0 }
                        )
                        if (resAudio.isSuccess) {
                            val m = resAudio.getOrThrow()
                            btnAudioId = m.midiaId
                            btnAudioSha = m.sha256
                            btnAudioArq = m.nomeArquivo
                            btnAudioBlocos = m.totalBlocos
                        }
                    } else {
                        btnAudioId = ""
                        btnAudioSha = ""
                        btnAudioArq = ""
                        btnAudioBlocos = 0
                    }
                    btn.audio_id = btnAudioId.ifBlank { null }
                    btn.audio_sha = btnAudioSha.ifBlank { null }
                    btn.audio_arquivo = btnAudioArq.ifBlank { null }
                    btn.audio_blocos = btnAudioBlocos.takeIf { it > 0 }

                    botoesLista.add(
                        mapOf(
                            "ordem" to btnIdx,
                            "id" to btn.id,
                            "rotulo" to btn.rotulo,
                            "significado" to btn.significado.ifBlank { btn.rotulo },
                            "imagem_id" to btnImagemId,
                            "imagem_sha" to btnImagemSha,
                            "imagem_arquivo" to btnImagemArq,
                            "imagem_blocos" to btnImagemBlocos,
                            "audio_id" to btnAudioId,
                            "audio_sha" to btnAudioSha,
                            "audio_arquivo" to btnAudioArq,
                            "audio_blocos" to btnAudioBlocos
                        )
                    )
                }

                // 3. Salva categoria no Firestore canônico com todos os campos de fast-path
                val docCategoriaMap = mapOf(
                    "id" to cat.id,
                    "nome" to cat.nome,
                    "slug" to cat.slug,
                    "cor" to cat.cor,
                    "ordem" to catIdx,
                    "imagem_id" to catImagemId,
                    "imagem_sha" to catImagemSha,
                    "imagem_arquivo" to catImagemArq,
                    "imagem_blocos" to catImagemBlocos,
                    "audio_id" to catAudioId,
                    "audio_sha" to catAudioSha,
                    "audio_arquivo" to catAudioArq,
                    "audio_blocos" to catAudioBlocos,
                    "botoes" to botoesLista
                )

                firestore.collection("usuarios").document(userId)
                    .collection("categorias").document(cat.id)
                    .set(docCategoriaMap, SetOptions.merge())
                    .await()

                // Persiste os metadados de fast-path localmente para o próximo sync incremental
                MidiaLocalHelper.salvarBotoesCategoriaLocalmente(context, cat.id, cat.botoes)
            }

            // 4. Somente após TODAS as gravações no Firestore (blocos, arquivos_midia, midias e categorias):
            // incrementa config_versao e dispara a flag no RTDB uma única vez por dispositivo
            var countDisparos = 0
            if (dispararTrigger) {
                try {
                    val snapsDisp = firestore.collection("dispositivos")
                        .whereEqualTo("owner_uid", userId)
                        .get()
                        .await()
                    for (doc in snapsDisp.documents) {
                        try {
                            doc.reference.update("config_versao", FieldValue.increment(1)).await()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                countDisparos = triggerService.dispararFlagParaTodosDispositivos(userId).getOrDefault(0)
            }

            Log.d(TAG, "Prancha sincronizada (incremental/fast-path) para $limiteCategorias categorias. Flags enviadas: $countDisparos")
            Result.success(countDisparos)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar prancha completa", e)
            Result.failure(e)
        }
    }

    private fun extrairBitmap(context: Context, fonte: Any): Bitmap? {
        return try {
            when (fonte) {
                is Bitmap -> fonte
                is File -> if (fonte.exists()) BitmapFactory.decodeFile(fonte.absolutePath) else null
                is Uri -> {
                    context.contentResolver.openInputStream(fonte)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                is Int -> {
                    val drawable = ContextCompat.getDrawable(context, fonte) ?: return null
                    if (drawable is BitmapDrawable && drawable.bitmap != null) {
                        drawable.bitmap
                    } else {
                        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 240
                        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 240
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                }
                is Drawable -> {
                    if (fonte is BitmapDrawable && fonte.bitmap != null) {
                        fonte.bitmap
                    } else {
                        val width = if (fonte.intrinsicWidth > 0) fonte.intrinsicWidth else 240
                        val height = if (fonte.intrinsicHeight > 0) fonte.intrinsicHeight else 240
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        fonte.setBounds(0, 0, canvas.width, canvas.height)
                        fonte.draw(canvas)
                        bmp
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extrairBytesAudio(context: Context, fonte: Any): Pair<ByteArray, String>? {
        return try {
            when (fonte) {
                is ByteArray -> {
                    val ext = if (fonte.size > 4 && fonte[0] == 'R'.code.toByte() && fonte[1] == 'I'.code.toByte()) "wav" else "mp3"
                    Pair(fonte, ext)
                }
                is File -> {
                    if (!fonte.exists()) return null
                    val bytes = fonte.readBytes()
                    val ext = when (fonte.extension.lowercase()) {
                        "wav" -> "wav"
                        else -> "mp3"
                    }
                    Pair(bytes, ext)
                }
                is Uri -> {
                    val stream: InputStream? = context.contentResolver.openInputStream(fonte)
                    val bytes = stream?.use { it.readBytes() } ?: return null
                    val ext = if (fonte.path?.endsWith(".wav", true) == true) "wav" else "mp3"
                    Pair(bytes, ext)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun salvarCacheLocal(context: Context, nomeArquivo: String, bytes: ByteArray) {
        try {
            val dir = File(context.filesDir, "uploads")
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, nomeArquivo)).use { fos ->
                fos.write(bytes)
            }
        } catch (_: Exception) {}
    }

    private fun cortarERedimensionar(src: Bitmap, targetSize: Int): Bitmap {
        val width = src.width
        val height = src.height
        val minDim = minOf(width, height)
        val xOffset = (width - minDim) / 2
        val yOffset = (height - minDim) / 2

        val quadrado = Bitmap.createBitmap(src, xOffset, yOffset, minDim, minDim)
        return if (minDim != targetSize) {
            Bitmap.createScaledBitmap(quadrado, targetSize, targetSize, true)
        } else {
            quadrado
        }
    }

    private fun calcularSha256(dados: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(dados)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
