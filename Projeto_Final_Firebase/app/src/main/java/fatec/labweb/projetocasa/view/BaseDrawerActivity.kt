package fatec.labweb.projetocasa.view

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import fatec.labweb.projetocasa.CasaApplication
import fatec.labweb.projetocasa.R
import fatec.labweb.projetocasa.model.DadosDispositivo
import fatec.labweb.projetocasa.service.NotificacaoMonitor
import fatec.labweb.projetocasa.util.MidiaLocalHelper
import fatec.labweb.projetocasa.util.SoundManager
import java.io.File

abstract class BaseDrawerActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val fontScale = prefs.getFloat(CasaApplication.KEY_FONT_SCALE, 1.0f)
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = fontScale
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var tvInitial: TextView
    private lateinit var imgAvatar: ImageView
    private lateinit var tvUserName: TextView

    private var badgeNotifListener: ListenerRegistration? = null
    private var badgeDispListener: ListenerRegistration? = null
    private var temNotifNaoLida: Boolean = false
    private var temAlertaHardware: Boolean = false

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 9001
        private const val KEY_PERMISSAO_NOTIF_SOLICITADA = "permissao_notif_solicitada"
    }

    abstract fun getContentLayoutId(): Int
    abstract fun getCurrentMenuItemId(): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        // Aplicação forçada e persistência de Dark Mode antes da inflação do layout
        val prefs = getSharedPreferences("app_config", MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("KEY_DARK_MODE", prefs.getBoolean("dark_mode", false))
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_base_drawer)

        layoutInflater.inflate(getContentLayoutId(), findViewById(R.id.contentFrame), true)

        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)

        configurarMenuTelaCheiaResponsivo()
        setupToolbar()
        setupCustomMenuClicks()
        highlightCurrentMenuItem()
        setupProfile()
        loadUser()
        setupBackPressHandler()
        setupNotificationButton()
        solicitarPermissaoNotificacaoSeNecessario()
    }

    override fun onStart() {
        super.onStart()
        iniciarObservadorBadgeNotificacoes()
    }

    override fun onStop() {
        super.onStop()
        badgeNotifListener?.remove()
        badgeNotifListener = null
        badgeDispListener?.remove()
        badgeDispListener = null
    }

    override fun onResume() {
        super.onResume()
        configurarMenuTelaCheiaResponsivo()
        highlightCurrentMenuItem()
        loadUser()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurarMenuTelaCheiaResponsivo()
    }

    /**
     * Configura o Menu de Navegação lateral para ocupar 70% da largura da tela,
     * respeitando Safe Areas em smartphones e tablets.
     */
    private fun configurarMenuTelaCheiaResponsivo() {
        val metrics = resources.displayMetrics
        val larguraTela = metrics.widthPixels
        val larguraSidebar = (larguraTela * 0.70f).toInt()
        val alturaTela = metrics.heightPixels
        val density = metrics.density

        // 1. Remove a margem mínima fixa do DrawerLayout para permitir exatamente 70% em qualquer tela
        try {
            val fieldMargin = DrawerLayout::class.java.getDeclaredField("mMinDrawerMargin")
            fieldMargin.isAccessible = true
            fieldMargin.setInt(drawerLayout, 0)
        } catch (_: Exception) {}

        // 2. Define o limite interno maxWidth do NavigationView exatamente como 70% da largura
        try {
            val fieldMaxWidth = NavigationView::class.java.getDeclaredField("maxWidth")
            fieldMaxWidth.isAccessible = true
            fieldMaxWidth.setInt(navView, larguraSidebar)
        } catch (_: Exception) {}

        navView.layoutParams = (navView.layoutParams as? DrawerLayout.LayoutParams)?.apply {
            width = larguraSidebar
            height = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = GravityCompat.END
        } ?: DrawerLayout.LayoutParams(
            larguraSidebar,
            ViewGroup.LayoutParams.MATCH_PARENT,
            GravityCompat.END
        )
        navView.minimumWidth = larguraSidebar
        navView.minimumHeight = alturaTela

        val headerView = navView.getHeaderView(0) ?: return
        val containerFull = headerView.findViewById<View>(R.id.containerMenuFullViewport) ?: headerView

        headerView.layoutParams = headerView.layoutParams?.apply {
            width = larguraSidebar
            height = alturaTela
        } ?: ViewGroup.LayoutParams(larguraSidebar, alturaTela)
        headerView.minimumWidth = larguraSidebar
        headerView.minimumHeight = alturaTela
        containerFull.minimumHeight = alturaTela

        (headerView.parent as? View)?.let { parentHeader ->
            parentHeader.minimumWidth = larguraSidebar
            parentHeader.minimumHeight = alturaTela
            parentHeader.layoutParams = parentHeader.layoutParams?.apply {
                width = larguraSidebar
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }

        // 3. Aplica Safe Area Insets (status bar, cutout e barra de gestos inferior)
        val padTopBase = (24 * density).toInt()
        val padBottomBase = (20 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(containerFull) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val alturaUtil = (alturaTela - insets.top - insets.bottom).coerceAtLeast(alturaTela)
            view.minimumHeight = alturaUtil
            view.setPadding(
                insets.left,
                insets.top + padTopBase,
                insets.right,
                insets.bottom + padBottomBase
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(containerFull)

        headerView.findViewById<View>(R.id.btnFecharMenuFull)?.setOnClickListener { view ->
            SoundManager.dispararAudioCue(view)
            drawerLayout.closeDrawer(GravityCompat.END)
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_bg))
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
        }
        toolbar.navigationIcon = null

        findViewById<View>(R.id.btnToolbarBurger)?.setOnClickListener { view ->
            SoundManager.dispararAudioCue(view)
            toggleDrawer()
        }

        findViewById<ImageView>(R.id.imgToolbarLogo)?.setOnClickListener {
            navigateTo(HomeActivity::class.java)
        }
    }

    private fun toggleDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                SoundManager.dispararAudioCue(findViewById(android.R.id.home) ?: findViewById(R.id.toolbar) ?: window.decorView)
                toggleDrawer()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupNotificationButton() {
        val notificationButton = findViewById<View>(R.id.containerNotificationBadge)
            ?: findViewById<View>(R.id.notificationBadgeDot)?.parent as? View
            ?: findViewById<View>(R.id.toolbar)?.findViewById<View>(R.id.notificationBadgeDot)?.parent as? View

        notificationButton?.setOnClickListener {
            navigateTo(NotificacoesActivity::class.java)
        }
    }

    private fun iniciarObservadorBadgeNotificacoes() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        badgeNotifListener?.remove()
        badgeNotifListener = db.collection("usuarios").document(uid)
            .collection("notificacoes")
            .whereEqualTo("lida", false)
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener
                temNotifNaoLida = snapshots != null && !snapshots.isEmpty
                atualizarVisibilidadeBadgeNotificacao()
            }

        badgeDispListener?.remove()
        badgeDispListener = db.collection("dispositivos")
            .whereEqualTo("owner_uid", uid)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener
                val dispositivos = snapshots.documents.mapNotNull { doc ->
                    try {
                        doc.toObject(DadosDispositivo::class.java)
                    } catch (_: Exception) {
                        null
                    }
                }
                val alertas = NotificacaoMonitor.avaliarDispositivos(
                    context = this,
                    dispositivos = dispositivos,
                    dispararAlertaLocal = true
                )
                temAlertaHardware = alertas.any { it.isAlertaCritico || it.severidade == fatec.labweb.projetocasa.model.Notificacao.SEVERIDADE_AVISO }
                atualizarVisibilidadeBadgeNotificacao()
            }
    }

    private fun atualizarVisibilidadeBadgeNotificacao() {
        val dot = findViewById<View>(R.id.notificationBadgeDot)
            ?: findViewById<View>(R.id.toolbar)?.findViewById(R.id.notificationBadgeDot)
        dot?.visibility = if (temNotifNaoLida || temAlertaHardware) View.VISIBLE else View.GONE
    }

    private fun solicitarPermissaoNotificacaoSeNecessario() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences(CasaApplication.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CasaApplication.KEY_NOTIFICACOES, true)) return
        if (prefs.getBoolean(KEY_PERMISSAO_NOTIF_SOLICITADA, false)) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            prefs.edit().putBoolean(KEY_PERMISSAO_NOTIF_SOLICITADA, true).apply()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS
            )
        }
    }

    private fun highlightCurrentMenuItem() {
        val headerView = navView.getHeaderView(0) ?: return
        val currentId = getCurrentMenuItemId()

        val menuItems = listOf(
            R.id.menuInicio,
            R.id.menuDispositivo,
            R.id.menuDesempenho,
            R.id.menuHistorico,
            R.id.menuConfiguracoes
        )

        for (itemId in menuItems) {
            val layoutItem = headerView.findViewById<LinearLayout>(itemId) ?: continue
            val imageView = layoutItem.getChildAt(0) as? ImageView
            val textView = layoutItem.getChildAt(1) as? TextView

            if (itemId == currentId) {
                layoutItem.setBackgroundResource(R.drawable.menu_item_selected_bg)
                imageView?.setColorFilter(ContextCompat.getColor(this, android.R.color.white))
                textView?.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            } else {
                layoutItem.background = null
                imageView?.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
                textView?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun setupCustomMenuClicks() {
        val headerView = navView.getHeaderView(0) ?: return

        headerView.findViewById<LinearLayout>(R.id.menuInicio)?.setOnClickListener {
            navigateTo(HomeActivity::class.java)
        }
        headerView.findViewById<LinearLayout>(R.id.menuDispositivo)?.setOnClickListener {
            navigateTo(CategoriaGeralActivity::class.java)
        }
        headerView.findViewById<LinearLayout>(R.id.menuDesempenho)?.setOnClickListener {
            navigateTo(DesempenhoActivity::class.java)
        }
        headerView.findViewById<LinearLayout>(R.id.menuHistorico)?.setOnClickListener {
            navigateTo(HistoricoActivity::class.java)
        }
        headerView.findViewById<LinearLayout>(R.id.menuConfiguracoes)?.setOnClickListener {
            navigateTo(ConfiguracoesActivity::class.java)
        }
    }

    fun dispararAudioCue(view: View) {
        SoundManager.dispararAudioCue(view)
    }

    fun tocarSomClique(view: View) {
        SoundManager.dispararAudioCue(view)
    }

    open fun podeNavegar(): Boolean = true
    open fun avisarAlteracoesNaoSalvas(onConfirmar: () -> Unit) { onConfirmar() }

    fun navigateTo(destination: Class<*>) {
        if (!podeNavegar()) {
            avisarAlteracoesNaoSalvas {
                executarNavegacao(destination)
            }
            return
        }
        executarNavegacao(destination)
    }

    private fun executarNavegacao(destination: Class<*>) {
        SoundManager.playClickSound(context = this)
        drawerLayout.closeDrawer(GravityCompat.END)

        // Se já está na tela atual, apenas fecha o drawer e não recria a tela
        if (destination == this::class.java) {
            return
        }

        val intent = Intent(this, destination).apply {
            if (destination == HomeActivity::class.java) {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            } else {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
        startActivity(intent)
    }

    private fun setupProfile() {
        val headerView = navView.getHeaderView(0) ?: return
        tvInitial = headerView.findViewById(R.id.tvInitial)
        imgAvatar = headerView.findViewById(R.id.imgAvatar)
        tvUserName = headerView.findViewById(R.id.tvUserName)
        tvUserName.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

        headerView.findViewById<View>(R.id.profileContainer)?.setOnClickListener {
            navigateTo(PerfilActivity::class.java)
        }

        headerView.findViewById<View>(R.id.btnDesconectar)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            logout()
        }
    }

    private fun loadUser() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            irParaDesconectar()
            return
        }

        val avatarLocal = MidiaLocalHelper.obterPerfilLocal(this)
        val nomeLocal = MidiaLocalHelper.obterNomePerfilLocal(this)
        if (!nomeLocal.isNullOrBlank()) {
            mostrarNome(nomeLocal)
        }
        tvInitial.visibility = View.GONE
        imgAvatar.visibility = View.VISIBLE
        if (avatarLocal != null && avatarLocal.exists()) {
            Glide.with(this)
                .load(avatarLocal)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .signature(ObjectKey("${avatarLocal.absolutePath}_${avatarLocal.lastModified()}"))
                .circleCrop()
                .placeholder(R.drawable.ic_profile_placeholder)
                .error(R.drawable.ic_profile_placeholder)
                .into(imgAvatar)
        } else {
            imgAvatar.setImageResource(R.drawable.ic_profile_placeholder)
        }

        FirebaseFirestore.getInstance().collection("usuarios").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val nomeFirestore = doc.getString("nome")
                mostrarNome(nomeLocal ?: nomeFirestore ?: user.displayName ?: "Usuário")

                val fotoUrlFirestore = doc.getString("fotoUrl")
                    ?: doc.getString("avatarUrl")
                    ?: user.photoUrl?.toString()

                val localAtual = MidiaLocalHelper.obterPerfilLocal(this)
                tvInitial.visibility = View.GONE
                imgAvatar.visibility = View.VISIBLE
                if (localAtual != null && localAtual.exists()) {
                    Glide.with(this)
                        .load(localAtual)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .signature(ObjectKey("${localAtual.absolutePath}_${localAtual.lastModified()}"))
                        .circleCrop()
                        .placeholder(R.drawable.ic_profile_placeholder)
                        .error(R.drawable.ic_profile_placeholder)
                        .into(imgAvatar)
                } else if (!fotoUrlFirestore.isNullOrBlank()) {
                    Glide.with(this)
                        .load(fotoUrlFirestore)
                        .circleCrop()
                        .placeholder(R.drawable.ic_profile_placeholder)
                        .error(R.drawable.ic_profile_placeholder)
                        .into(imgAvatar)
                } else {
                    imgAvatar.setImageResource(R.drawable.ic_profile_placeholder)
                }
            }
            .addOnFailureListener {
                mostrarNome(nomeLocal ?: user.displayName ?: "Usuário")
                val localAtual = MidiaLocalHelper.obterPerfilLocal(this)
                tvInitial.visibility = View.GONE
                imgAvatar.visibility = View.VISIBLE
                if (localAtual != null && localAtual.exists()) {
                    Glide.with(this)
                        .load(localAtual)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .signature(ObjectKey("${localAtual.absolutePath}_${localAtual.lastModified()}"))
                        .circleCrop()
                        .placeholder(R.drawable.ic_profile_placeholder)
                        .error(R.drawable.ic_profile_placeholder)
                        .into(imgAvatar)
                } else {
                    imgAvatar.setImageResource(R.drawable.ic_profile_placeholder)
                }
            }
    }

    private fun mostrarNome(nome: String) {
        tvUserName.text = nome
        tvInitial.text = nome.trim().firstOrNull()?.uppercase() ?: "U"
    }

    private fun logout() {
        FirebaseAuth.getInstance().signOut()
        irParaDesconectar()
    }

    private fun irParaDesconectar() {
        val intent = Intent(this, DesconectarContaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END)
                } else if (!podeNavegar()) {
                    avisarAlteracoesNaoSalvas {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}
