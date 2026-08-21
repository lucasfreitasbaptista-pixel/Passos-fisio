package com.passosfisio.app

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

// ============================================================
// MainActivity — tela única com 3 estados: login/cadastro,
// código de convite, e contagem de passos
// ============================================================
class MainActivity : ComponentActivity() {

    private var modoCadastro = false

    private lateinit var containerAuth: View
    private lateinit var containerConvite: View
    private lateinit var containerPassos: View

    private lateinit var campoNome: EditText
    private lateinit var campoEmail: EditText
    private lateinit var campoSenha: EditText
    private lateinit var textErroAuth: TextView
    private lateinit var btnEntrar: Button
    private lateinit var linkAlternarModo: TextView

    private lateinit var campoCodigo: EditText
    private lateinit var textErroConvite: TextView
    private lateinit var btnConfirmarConvite: Button

    private val pedirPermissaoSensor = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedida ->
        if (concedida) iniciarServicoDePassos()
        else Toast.makeText(this, "Sem essa permissão o app não consegue contar seus passos", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ligarViews()
        ligarListeners()
        decidirTelaInicial()
    }

    private fun ligarViews() {
        containerAuth = findViewById(R.id.containerAuth)
        containerConvite = findViewById(R.id.containerConvite)
        containerPassos = findViewById(R.id.containerPassos)

        campoNome = findViewById(R.id.campoNome)
        campoEmail = findViewById(R.id.campoEmail)
        campoSenha = findViewById(R.id.campoSenha)
        textErroAuth = findViewById(R.id.textErroAuth)
        btnEntrar = findViewById(R.id.btnEntrar)
        linkAlternarModo = findViewById(R.id.linkAlternarModo)

        campoCodigo = findViewById(R.id.campoCodigo)
        textErroConvite = findViewById(R.id.textErroConvite)
        btnConfirmarConvite = findViewById(R.id.btnConfirmarConvite)
    }

    private fun ligarListeners() {
        linkAlternarModo.setOnClickListener {
            modoCadastro = !modoCadastro
            campoNome.visibility = if (modoCadastro) View.VISIBLE else View.GONE
            btnEntrar.text = if (modoCadastro) "Criar conta" else "Entrar"
            linkAlternarModo.text = if (modoCadastro) "Já tem conta? Entrar" else "Ainda não tem conta? Criar conta"
            textErroAuth.visibility = View.GONE
        }

        btnEntrar.setOnClickListener { autenticar() }
        btnConfirmarConvite.setOnClickListener { confirmarConvite() }
    }

    private fun decidirTelaInicial() {
        if (!SupabaseApi.sessaoAtiva(this)) {
            mostrarSomente(containerAuth)
            return
        }
        lifecycleScope.launch {
            val vinculado = try {
                SupabaseApi.temVinculo(this@MainActivity)
            } catch (e: Exception) {
                false
            }
            if (vinculado) {
                mostrarSomente(containerPassos)
                verificarPermissaoESensor()
            } else {
                mostrarSomente(containerConvite)
            }
        }
    }

    private fun autenticar() {
        val email = campoEmail.text.toString().trim()
        val senha = campoSenha.text.toString()
        val nome = campoNome.text.toString().trim()

        if (email.isEmpty() || senha.isEmpty() || (modoCadastro && nome.isEmpty())) {
            mostrarErro(textErroAuth, "Preencha todos os campos")
            return
        }

        btnEntrar.isEnabled = false
        lifecycleScope.launch {
            try {
                if (modoCadastro) {
                    SupabaseApi.cadastrar(this@MainActivity, nome, email, senha)
                } else {
                    SupabaseApi.entrar(this@MainActivity, email, senha)
                }
                mostrarSomente(containerConvite)
            } catch (e: Exception) {
                mostrarErro(textErroAuth, e.message ?: "Algo deu errado")
            } finally {
                btnEntrar.isEnabled = true
            }
        }
    }

    private fun confirmarConvite() {
        val codigo = campoCodigo.text.toString().trim().uppercase()
        if (codigo.isEmpty()) {
            mostrarErro(textErroConvite, "Digite o código")
            return
        }

        btnConfirmarConvite.isEnabled = false
        lifecycleScope.launch {
            try {
                val fisioId = SupabaseApi.buscarConvite(this@MainActivity, codigo)
                if (fisioId == null) {
                    mostrarErro(textErroConvite, "Código não encontrado")
                    return@launch
                }
                SupabaseApi.criarVinculo(this@MainActivity, fisioId)
                mostrarSomente(containerPassos)
                verificarPermissaoESensor()
            } catch (e: Exception) {
                mostrarErro(textErroConvite, e.message ?: "Algo deu errado")
            } finally {
                btnConfirmarConvite.isEnabled = true
            }
        }
    }

    private fun mostrarErro(view: TextView, mensagem: String) {
        view.text = mensagem
        view.visibility = View.VISIBLE
    }

    private fun mostrarSomente(container: View) {
        containerAuth.visibility = if (container == containerAuth) View.VISIBLE else View.GONE
        containerConvite.visibility = if (container == containerConvite) View.VISIBLE else View.GONE
        containerPassos.visibility = if (container == containerPassos) View.VISIBLE else View.GONE
    }

    private fun verificarPermissaoESensor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED -> iniciarServicoDePassos()

                else -> pedirPermissaoSensor.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        } else {
            iniciarServicoDePassos()
        }
    }

    private fun iniciarServicoDePassos() {
        val intent = Intent(this, StepCounterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        iniciarAtualizacaoTelaPassos()
    }

    private fun iniciarAtualizacaoTelaPassos() {
        lifecycleScope.launch {
            while (isActive) {
                atualizarTextoPassos()
                delay(2000)
            }
        }
    }

    private fun atualizarTextoPassos() {
        val prefs = getSharedPreferences(StepCounterService.PREFS_NAME, Context.MODE_PRIVATE)
        val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val passos = prefs.getInt(StepCounterService.KEY_STEPS_TODAY + hoje, 0)
        findViewById<TextView>(R.id.textPassosHoje).text = "$passos passos hoje"
    }
}

// ============================================================
// StepCounterService — lê o sensor de hardware TYPE_STEP_COUNTER
// em segundo plano e sincroniza com o Supabase periodicamente
// ============================================================
class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private lateinit var prefs: android.content.SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    companion object {
        const val CHANNEL_ID = "passos_service_channel"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "passos_prefs"
        const val KEY_BASELINE = "baseline_"
        const val KEY_STEPS_TODAY = "steps_today_"
        const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getStepsToday()))

        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        startPeriodicSync()
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return

        val totalDesdeOBoot = event.values[0].toInt()
        val hoje = dateFormat.format(Date())
        val baselineKey = KEY_BASELINE + hoje

        if (!prefs.contains(baselineKey)) {
            prefs.edit().putInt(baselineKey, totalDesdeOBoot).apply()
        }

        val baseline = prefs.getInt(baselineKey, totalDesdeOBoot)
        val passosHoje = totalDesdeOBoot - baseline

        prefs.edit().putInt(KEY_STEPS_TODAY + hoje, passosHoje).apply()
        atualizarNotificacao(passosHoje)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun getStepsToday(): Int {
        val hoje = dateFormat.format(Date())
        return prefs.getInt(KEY_STEPS_TODAY + hoje, 0)
    }

    private fun startPeriodicSync() {scope.launch {
            while (isActive) {
                sincronizarComSupabase()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private suspend fun sincronizarComSupabase() {
        try {
            val hoje = dateFormat.format(Date())
            val passos = getStepsToday()
            SupabaseApi.upsertPassosDiarios(applicationContext, hoje, passos)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(passos: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Contando seus passos")
            .setContentText("$passos passos hoje")
            .setSmallIcon(R.drawable.ic_walk)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun atualizarNotificacao(passos: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(passos))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Contagem de passos",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ============================================================
// SupabaseApi — chamadas diretas à API REST/Auth do Supabase,
// sem SDK (só OkHttp puro, embutido no APK)
// ============================================================
object SupabaseApi {

    private const val SUPABASE_URL = "https://rpvdkwvlcqwzndkjsvyk.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_Kl89lDnjM9MnvpV5KodLVA_6Kawrite"

    private val client = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    private fun tokenDoUsuario(context: Context): String {
        val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        return prefs.getString("access_token", "") ?: ""
    }

    private fun salvarSessao(context: Context, accessToken: String, userId: String) {
        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit()
            .putString("access_token", accessToken)
            .putString("user_id", userId)
            .apply()
    }

    fun logout(context: Context) {
        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun sessaoAtiva(context: Context): Boolean =
        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).contains("access_token")

    suspend fun cadastrar(context: Context, nome: String, email: String, senha: String) =
        withContext(Dispatchers.IO) {
            val bodyAuth = JSONObject().apply {
                put("email", email)
                put("password", senha)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/signup")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(bodyAuth)
                .build()

            client.newCall(request).execute().use { response ->
                val texto = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    throw IOException(mensagemDeErro(texto, "Falha ao criar conta"))
                }
                val json = JSONObject(texto)
                val accessToken = json.optString("access_token", "")
                val userId = json.getJSONObject("user").getString("id")

                if (accessToken.isEmpty()) {
                    throw IOException("Conta criada! Confirme seu e-mail antes de entrar.")
                }

                salvarSessao(context, accessToken, userId)
                criarPerfil(context, userId, nome, accessToken)
            }
        }

    private fun criarPerfil(context: Context, userId: String, nome: String, accessToken: String) {
        val body = JSONObject().apply {
            put("id", userId)
            put("role", "paciente")
            put("nome", nome)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/passos_perfis")
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Falha ao criar perfil: ${response.code} ${response.body?.string()}")
            }
        }
    }

    suspend fun entrar(context: Context, email: String, senha: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("email", email)
            put("password", senha)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$SUPABASE_URL/auth/v1/token?grant_type=password")
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val texto = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw IOException(mensagemDeErro(texto, "E-mail ou senha incorretos"))
            }
            val json = JSONObject(texto)
            salvarSessao(context, json.getString("access_token"), json.getJSONObject("user").getString("id"))
        }
    }

    suspend fun temVinculo(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pacienteId = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            .getString("user_id", null) ?: return@withContext false

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/passos_vinculos?paciente_id=eq.$pacienteId&select=id&limit=1")
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${tokenDoUsuario(context)}")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext false
            org.json.JSONArray(response.body?.string() ?: "[]").length() > 0
        }
    }

    private fun mensagemDeErro(corpoResposta: String, padrao: String): String =
        try {
            JSONObject(corpoResposta).optString("msg", padrao)
                .ifEmpty { JSONObject(corpoResposta).optString("error_description", padrao) }
        } catch (e: Exception) {
            padrao
        }

    suspend fun upsertPassosDiarios(context: Context, data: String, passos: Int) =
        withContext(Dispatchers.IO) {
            val pacienteId = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                .getString("user_id", null) ?: return@withContext

            val body = JSONObject().apply {
                put("paciente_id", pacienteId)
                put("data", data)
                put("passos", passos)
                put("atualizado_em", java.time.Instant.now().toString())
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/passos_diarios")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${tokenDoUsuario(context)}")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Falha ao sincronizar passos: ${response.code} ${response.body?.string()}")
                }
            }
        }

    suspend fun buscarConvite(context: Context, codigo: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/passos_convites?codigo=eq.$codigo&select=fisio_id")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${tokenDoUsuario(context)}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val texto = response.body?.string() ?: "[]"
                if (!response.isSuccessful) {
                    throw IOException("Erro ao buscar convite: ${response.code} $texto")
                }
                val arr = org.json.JSONArray(texto)
                if (arr.length() == 0) return@withContext null
                arr.getJSONObject(0).optString("fisio_id", null)
            }
        }

    suspend fun criarVinculo(context: Context, fisioId: String) = withContext(Dispatchers.IO) {
        val pacienteId = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            .getString("user_id", null) ?: return@withContext

        val body = JSONObject().apply {
            put("paciente_id", pacienteId)
            put("fisio_id", fisioId)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/passos_vinculos")
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${tokenDoUsuario(context)}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Falha ao criar vínculo: ${response.code} ${response.body?.string()}")
            }
        }
    }
}

// ============================================================
// BootReceiver — religa a contagem depois que o celular reinicia
// ============================================================
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, StepCounterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
