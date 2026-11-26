package fatec.labweb.projetocasa.view

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import fatec.labweb.projetocasa.R
import fatec.labweb.projetocasa.util.DeviceUiAnimator
import fatec.labweb.projetocasa.util.MidiaLocalHelper
import java.io.File

/**
 * Modal de Recorte Interativo (Pinch-to-Zoom, Pan, Rotação 90° e Output exato 240x240px 1:1).
 */
class ModalRecorteAvatar(
    private val activity: Activity,
    private val salvarComoAvatar: Boolean = true,
    private val instrucao: String = "Use dois dedos para dar zoom ou arraste para enquadrar seu perfil.",
    private val onRecorteConfirmado: (arquivoFinal240: File, bitmapFinal: Bitmap) -> Unit
) {

    fun abrirComUri(uri: Uri) {
        val bitmap = decodificarBitmapSeguro(uri)
        if (bitmap == null) {
            Toast.makeText(activity, "Não foi possível carregar a imagem selecionada.", Toast.LENGTH_SHORT).show()
            return
        }
        exibirModal(bitmap)
    }

    fun abrirComArquivo(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(activity, "Arquivo de imagem inválido.", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = decodificarBitmapDeArquivo(file)
        if (bitmap == null) {
            Toast.makeText(activity, "Não foi possível abrir a foto capturada.", Toast.LENGTH_SHORT).show()
            return
        }
        exibirModal(bitmap)
    }

    private fun exibirModal(bitmapOriginal: Bitmap) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val cardContainer = MaterialCardView(activity).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(14).toFloat()
            useCompatPadding = true
            setCardBackgroundColor(Color.parseColor("#111827"))
            strokeColor = Color.parseColor("#374151")
            strokeWidth = dp(1)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        cardContainer.addView(root)

        val tvTitulo = TextView(activity).apply {
            text = "Ajustar e Recortar Foto (240x240)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(tvTitulo)

        val tvInstrucao = TextView(activity).apply {
            text = instrucao
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(tvInstrucao)

        val cropView = AvatarCropView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
            setBitmap(bitmapOriginal)
        }
        root.addView(cropView)

        // Controle deslizante de Zoom (para acessibilidade além de pinch-to-zoom)
        val zoomContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        val tvZoomLabel = TextView(activity).apply {
            text = "Zoom:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val seekBarZoom = SeekBar(activity).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        val btnGirar = MaterialButton(activity).apply {
            text = "Girar 90°"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener {
                cropView.rotacionar90Graus()
                seekBarZoom.progress = 0
            }
        }
        zoomContainer.addView(tvZoomLabel)
        zoomContainer.addView(seekBarZoom)
        zoomContainer.addView(btnGirar)
        root.addView(zoomContainer)

        var atualizandoSeekInterno = false
        cropView.onZoomChanged = { progresso ->
            atualizandoSeekInterno = true
            seekBarZoom.progress = (progresso * 100).toInt().coerceIn(0, 100)
            atualizandoSeekInterno = false
        }
        seekBarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !atualizandoSeekInterno) {
                    cropView.definirZoomNormalizado(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Botões de Ação (Cancelar / Confirmar Recorte)
        val botoesRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(cardContainer)
            .setCancelable(false)
            .create()
        DeviceUiAnimator.estilizarModalDialog(dialog)

        val btnCancelar = MaterialButton(activity).apply {
            text = "Cancelar"
            isAllCaps = false
            cornerRadius = dp(10)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#374151"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }

        val btnConfirmar = MaterialButton(activity).apply {
            text = "Salvar (240x240)"
            isAllCaps = false
            cornerRadius = dp(10)
            setTextColor(Color.WHITE)
            setBackgroundColor(activity.getColor(R.color.brand_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val bitmap240 = cropView.gerarBitmapRecortado240x240()
                if (bitmap240 != null) {
                    val arquivoSalvo = if (salvarComoAvatar) {
                        MidiaLocalHelper.salvarAvatarRecortado240(activity, bitmap240)
                    } else {
                        MidiaLocalHelper.salvarImagemRecortada240(activity, bitmap240)
                    }
                    if (arquivoSalvo != null && arquivoSalvo.exists()) {
                        dialog.dismiss()
                        onRecorteConfirmado(arquivoSalvo, bitmap240)
                    } else {
                        Toast.makeText(activity, "Erro ao salvar foto recortada.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(activity, "Erro ao processar recorte da imagem.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        botoesRow.addView(btnCancelar)
        botoesRow.addView(btnConfirmar)
        root.addView(botoesRow)

        dialog.show()
    }

    private fun decodificarBitmapSeguro(uri: Uri): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            var sampleSize = 1
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxDim / sampleSize > 1600) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null
            corrigirOrientacaoExifUri(bmp, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun decodificarBitmapDeArquivo(file: File): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            var sampleSize = 1
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxDim / sampleSize > 1600) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            corrigirOrientacaoExifArquivo(bmp, file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun corrigirOrientacaoExifUri(bitmap: Bitmap, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return bitmap
        return try {
            val orientation = activity.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            aplicarRotacaoExif(bitmap, orientation)
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun corrigirOrientacaoExifArquivo(bitmap: Bitmap, file: File): Bitmap {
        return try {
            val orientation = ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            aplicarRotacaoExif(bitmap, orientation)
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun aplicarRotacaoExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val graus = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (graus == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(graus) }
        val rotacionado = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotacionado !== bitmap) bitmap.recycle()
        return rotacionado
    }
}
