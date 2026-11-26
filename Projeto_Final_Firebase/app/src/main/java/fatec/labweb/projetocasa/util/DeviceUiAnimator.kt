package fatec.labweb.projetocasa.util

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.google.android.material.card.MaterialCardView
import fatec.labweb.projetocasa.R

object DeviceUiAnimator {

    private val TAG_ALERTA_ANIM = 0x7F0A1001
    private val TAG_BOTAO_ANIM = 0x7F0A1002

    /**
     * Configura janela de modal com fundo transparente (para exibir bordas arredondadas e sombra
     * reais do MaterialCardView) e transição suave de entrada/saída.
     */
    fun estilizarModalDialog(dialog: Dialog?) {
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes?.windowAnimations = R.style.CasaModalDialogAnimation
        }
    }

    /**
     * Animação de atenção para alertas de pendência: borda piscando suavemente em âmbar
     * combinada com micro-pulso de escala.
     */
    fun animarAlertaPendencia(card: MaterialCardView?) {
        if (card == null) return
        if (card.getTag(TAG_ALERTA_ANIM) is Animator) return

        val strokeAnim = ValueAnimator.ofObject(
            ArgbEvaluator(),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#FEF08A"),
            Color.parseColor("#D97706"),
            Color.parseColor("#F59E0B")
        ).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                card.strokeColor = anim.animatedValue as Int
            }
        }

        val scaleX = ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, 1.015f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scaleY = ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, 1.015f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val set = AnimatorSet().apply {
            playTogether(strokeAnim, scaleX, scaleY)
            start()
        }
        card.setTag(TAG_ALERTA_ANIM, set)

        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                pararAnimacaoAlerta(card)
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    fun pararAnimacaoAlerta(card: MaterialCardView?) {
        if (card == null) return
        (card.getTag(TAG_ALERTA_ANIM) as? Animator)?.cancel()
        card.setTag(TAG_ALERTA_ANIM, null)
        card.scaleX = 1f
        card.scaleY = 1f
        card.strokeColor = Color.parseColor("#F59E0B")
    }

    /**
     * Animação de "pulo" sutil para botões prioritários (ex: Salvar no Dispositivo,
     * Adicionar quando há pendência).
     */
    fun animarBotaoDestaque(button: View?) {
        if (button == null) return
        if (button.getTag(TAG_BOTAO_ANIM) is Animator) return

        val density = button.resources.displayMetrics.density
        val hopPx = -5f * density

        val hopAnim = ObjectAnimator.ofFloat(
            button,
            View.TRANSLATION_Y,
            0f, hopPx, 0f, hopPx * 0.45f, 0f, 0f, 0f
        ).apply {
            duration = 2100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scaleX = ObjectAnimator.ofFloat(
            button,
            View.SCALE_X,
            1f, 1.022f, 1f, 1.01f, 1f, 1f, 1f
        ).apply {
            duration = 2100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scaleY = ObjectAnimator.ofFloat(
            button,
            View.SCALE_Y,
            1f, 1.022f, 1f, 1.01f, 1f, 1f, 1f
        ).apply {
            duration = 2100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val set = AnimatorSet().apply {
            playTogether(hopAnim, scaleX, scaleY)
            start()
        }
        button.setTag(TAG_BOTAO_ANIM, set)

        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                pararAnimacaoBotao(button)
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    fun pararAnimacaoBotao(button: View?) {
        if (button == null) return
        (button.getTag(TAG_BOTAO_ANIM) as? Animator)?.cancel()
        button.setTag(TAG_BOTAO_ANIM, null)
        button.translationY = 0f
        button.scaleX = 1f
        button.scaleY = 1f
    }

    /**
     * Aplica micro-animação de hover (mouse/trackpad) e toque para ícones clicáveis.
     */
    fun aplicarHoverEToqueIcone(view: View?) {
        if (view == null) return
        view.setOnHoverListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    v.animate().scaleX(1.16f).scaleY(1.16f).setDuration(120)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false
        }
    }
}
